package dev.pov.tv

import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.TextUtils
import android.util.LruCache
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import androidx.media3.ui.PlayerView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import dev.pov.tv.data.*
import kotlin.math.roundToInt
import java.util.Calendar
import java.util.concurrent.Executors

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class MainActivity : Activity() {
    private lateinit var store: SecureStore
    private lateinit var torbox: TorBoxApi
    private lateinit var weTrakrApi: WeTrakrApi
    private lateinit var simklApi: SimklApi
    private lateinit var weTrakrOutbox: PlaybackStore
    private lateinit var simklOutbox: PlaybackStore
    /** The tracker chosen in Settings (WeTrakr or Simkl). Every tracking feature below goes through it. */
    private val wetrakr: Tracker get() = if (simklChosen) simklApi else weTrakrApi
    private val wetrakrOutbox: PlaybackStore get() = if (simklChosen) simklOutbox else weTrakrOutbox
    /** Read once (encrypted settings decrypt on every read) and updated when the service changes. */
    private var simklChosen = false
    private fun trackerId() = if (simklChosen) Trackers.SIMKL else Trackers.WETRAKR
    /** The account's watched history behind the ✓ badges on cards and episodes. */
    private var watched = WatchedState.EMPTY
    private val watchedCache by lazy { WatchedCache(java.io.File(filesDir, "watched.json")) }
    @Volatile private var watchedLoading = false
    /** Re-reads the Continue Watching row on Home in place; set while that row exists. */
    private var refreshContinue: (() -> Unit)? = null
    /** The local (no tracker) Continue Watching row on Home, and the pageRevision() that Home was built at. */
    private var localContinueRow: LinearLayout? = null
    private var homeRevision = ""
    /** The player was opened from here, so returning to this screen refreshes Continue Watching. */
    private var playerShown = false
    private var watchedCheckedAt = 0L
    private lateinit var resolver: SourceResolver
    /** Search and details: the chosen add-on (e.g. a self-hosted AIOMetadata) when set, else Cinemeta. */
    private var metadataSource: MetadataSource? = null
    private val catalog = CatalogApi(source = { metadataSource }, publicJson = Net::publicJson)
    private lateinit var history: PlaybackStore
    private lateinit var body: LinearLayout
    private lateinit var themePreferences: ThemePreferences
    private var theme = ThemeSpec.FLIX
    private lateinit var shellRoot: FrameLayout
    private lateinit var heroBackdrop: FrameLayout
    private lateinit var backdropArt: ImageView
    /** The link list's own layers: the phone's poster art and a dimming scrim over the artwork. */
    private lateinit var sourcesArt: ImageView
    private lateinit var sourcesScrim: View
    private var sourcesPage = false
    private lateinit var scroller: ScrollView
    private val navigationViews = mutableMapOf<String, View>()
    private var destination = "home"
    private var isHome = false
    // Debug capture uses public discovery only; no account state or viewing history is shown.
    private var publicPreview = false
    private lateinit var anime: AnimeCatalogApi
    private val worker = Executors.newFixedThreadPool(4)
    // Artwork decodes at background priority so the UI thread always wins the CPU while scrolling.
    private val backgroundThreads = java.util.concurrent.ThreadFactory { work ->
        Thread { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); work.run() }
    }
    /** Artwork for the page in front of the viewer loads first, top to bottom; older pages wait. */
    private class ImageTask(val page: Int, val order: Long, val work: Runnable) : Runnable, Comparable<ImageTask> {
        override fun run() = work.run()
        override fun compareTo(other: ImageTask) = if (page != other.page) other.page.compareTo(page) else order.compareTo(other.order)
    }
    private var imageOrder = 0L
    // Artwork servers answer slowly but in parallel, so a screen of posters loads several at a time.
    private val images = java.util.concurrent.ThreadPoolExecutor(8, 8, 0L, java.util.concurrent.TimeUnit.MILLISECONDS,
        java.util.concurrent.PriorityBlockingQueue<Runnable>(), backgroundThreads)
    // Feature artwork never waits behind a page full of shelf thumbnails.
    private val featureImages = Executors.newFixedThreadPool(2, backgroundThreads)
    private val metadataWorker = Executors.newSingleThreadExecutor()
    /** IMDb scores have their own thread: a slow IMDb answer never holds up the cast or other details work. */
    private val imdbWorker = Executors.newSingleThreadExecutor()
    private val cloudArtwork = mutableMapOf<String, MediaItemData?>()
    private val bitmapCache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 6).toInt().coerceIn(18 * 1024 * 1024, 64 * 1024 * 1024)) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private var authorization: DeviceAuthorizationDialog? = null
    private var apiKeyDialog: ApiKeyDialog? = null
    @Volatile private var generation = 0
    private lateinit var focusController: TvFocusController
    private val contentFocusChanged = android.view.ViewTreeObserver.OnGlobalFocusChangeListener { _, _ ->
        if (::focusController.isInitialized) focusController.revealCurrent()
        if (flix && android.os.SystemClock.uptimeMillis() < menuHoldUntil && destination == "home" && navigationViews.values.none { it.hasFocus() }) body.post { openMenu() }
        else if (flix) setRailExpanded(navigationViews.values.any { it.hasFocus() })
        if (::body.isInitialized) refreshHeroTrailer()
    }
    private data class RetainedPage(val children: List<View>, val padding: IntArray, val home: Boolean, val generation: Int,
        val heroVisibility: Int, val heroHeight: Int, val heroArt: android.graphics.drawable.Drawable?, val heroAddress: String?, val revision: String,
        val sources: Boolean = false)
    private data class NavigationSnapshot(val screen: () -> Unit, val focus: TvFocusSnapshot, val destination: String, val retained: RetainedPage?)
    private var pageSequence = 0
    private val pendingLoads = mutableMapOf<Int, Int>()
    private var cachedPlayback: PlaybackIdentity? = null
    private var searchDialog: android.app.Dialog? = null
    private var pendingFocus: TvFocusSnapshot? = null
    private var wantsContentFocus = false
    private var resumed = false
    private var miniPlayer: PlayerView? = null
    private var miniFrame: FrameLayout? = null
    private var miniLabel: TextView? = null
    private var miniFocus: MiniPlayerFocusRouter? = null
    internal var currentPlaybackOptionsDialog: AlertDialog? = null
        private set
    private val playbackChanged: () -> Unit = { runOnUiThread { if (resumed && !isDestroyed) { updateMiniPlayer(); refreshHeroTrailer() } } }
    private val authWorker = Executors.newSingleThreadExecutor()
    private val selectedSeasons = mutableMapOf<String, Int>()
    private val catalogOffsets = mutableMapOf<String, ArrayDeque<Int>>()
    private var resolvingGeneration: Int? = null
    private var launchingPlayer = false
    /** Release text and size of the link about to play; the chosen file's name is added once it is known. */
    private var playingSourceText = ""
    private var playingMedia: Pair<String, Long?>? = null
    /** The add-on badge (PenguPlay) for the direct link about to play. */
    private var playingAddon: String? = null
    private fun mediaFile(name: String, size: Long?) {
        playingMedia = listOf(name, playingSourceText).filter { it.isNotBlank() }.distinct().joinToString(" · ") to size?.takeIf { it > 0 }
    }
    private val backStack = ArrayDeque<NavigationSnapshot>()
    private var current: () -> Unit = { home() }
    private val remoteProgress = mutableMapOf<String, Double>()
    private val bg get() = theme.palette.background
    private val panel get() = theme.palette.panel
    private val muted get() = theme.palette.muted
    private val accent get() = theme.palette.accent
    private val ink get() = theme.palette.text
    private val flix get() = theme == ThemeSpec.FLIX
    private var rail: LinearLayout? = null
    private var railExpanded = false
    private var railAnimator: ValueAnimator? = null
    private val railLabels = mutableListOf<View>()
    private val detailsTrailerChrome by lazy { DetailsTrailerChrome({ !isHome && trailerPermitted() }, ::hideDetailsTrailerChrome, ::showDetailsTrailerChrome) }
    private val fadedTrailerViews = java.util.IdentityHashMap<View, Float>()
    private var trailerBackdropY = 0f
    private var trailerFadeGeneration = -1
    private var homeTrailerFocus: View? = null
    private var heroTrailer: HeroTrailerPreview? = null
    private val trailerCatalog = HeroTrailerCatalog()
    private fun heroTrailersEnabled() = DeviceLayout.tv && getSharedPreferences("pov_hero_trailers", 0).getBoolean("enabled", true)
    private fun heroTrailerFillEnabled() = getSharedPreferences("pov_hero_trailers", 0).getBoolean("fill_screen", true)
    private fun heroTrailerHighQuality() = getSharedPreferences("pov_hero_trailers", 0).getBoolean("high_quality", true)
    private fun heroTrailerSoundEnabled() = getSharedPreferences("pov_hero_trailers", 0).getBoolean("sound", true)
    private var featureShown: MediaItemData? = null
    private val featureHandler = Handler(Looper.getMainLooper())
    private val rotationHandler = Handler(Looper.getMainLooper())
    private var heroRotation: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        DeviceLayout.fit(resources)
        super.onCreate(savedInstanceState)
        markUnsupportedPrefer()
        if (packageName.endsWith(".preview")) {
            // Preview-only test switches: start with an empty artwork cache, and choose card image size.
            if (intent.getBooleanExtra("clear_artwork_cache", false)) java.io.File(cacheDir, "artwork-http").deleteRecursively()
        }
        ArtworkClient.init(this)
        val debug = applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
        val sourcePreview = debug && intent.getBooleanExtra("source_choice_preview", false)
        publicPreview = debug && (intent.getBooleanExtra("public_preview", false) || sourcePreview)
        if (DeviceLayout.tv) window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        else { window.statusBarColor = 0xFF141414.toInt(); window.navigationBarColor = PHONE_BAR_COLOR }
        store = SecureStore(this); metadataSource = MetadataSource.fromJson(store.get(MetadataSource.KEY))?.takeIf { store.get(METADATA_ON) != "false" }; torbox = TorBoxApi(store); weTrakrApi = WeTrakrApi(store); simklApi = SimklApi(store)
        weTrakrOutbox = PlaybackStore(this, Trackers.outboxKey(Trackers.WETRAKR)); simklOutbox = PlaybackStore(this, Trackers.outboxKey(Trackers.SIMKL))
        simklChosen = Trackers.selected(store) == Trackers.SIMKL
        resolver = SourceResolver(store, torbox); history = PlaybackStore(this)
        StreamChoices.searcher = { identity -> searchStreamsInBackground(identity, delayMs = 0) }
        // The player's Info / Episodes panels read the same cached details and TMDB key as the details page.
        PlayerShowInfo.details = { identity ->
            val item = MediaItemData(identity.imdbId, identity.type, identity.title)
            runCatching { cachedDetails(item) ?: fetchDetails(item) }.getOrNull()
        }
        PlayerShowInfo.tmdb = { if (publicPreview) null else store.get(TMDB_KEY).takeIf { it.isNotBlank() }?.let { TmdbCatalog(this, it, tmdbRegion()) } }
        themePreferences = ThemePreferences(this); theme = themePreferences.selected()
        // Retire the old tracker locally; never replay its queued updates after this upgrade.
        if (store.get("tracking.wetrakr_only") != "true") {
            listOf("trakt.client_id", "trakt.redirect_uri", "trakt.access_token", "trakt.refresh_token", "trakt.expires_at", "trakt.account_revision", "trakt.refresh_pending").forEach(store::remove)
            store.remove("tracking.library"); history.clearPending(); store.put("tracking.wetrakr_only", "true")
        }
        anime = AnimeCatalogApi()
        InAppPlaybackSession.initialize(this)
        InAppPlaybackSession.addListener(playbackChanged)
        if (savedInstanceState == null && !publicPreview && !sourcePreview) showSplash()
        if (!publicPreview) refreshWatched()
        shell(); if (sourcePreview) sourceChoiceAudit() else home(); body.post { openLauncherResume(intent); openAddonLink(intent) }; if (!publicPreview) { retrySync(); checkStartupUpdate() }
        window.decorView.viewTreeObserver.addOnGlobalFocusChangeListener(contentFocusChanged)
        // A restore reopens the app; say what happened once it is back.
        store.get(ACCOUNT_NOTICE).takeIf { it.isNotBlank() }?.let { notice -> store.remove(ACCOUNT_NOTICE); body.postDelayed({ toast(notice) }, 1_800) }
    }

    private val cloud by lazy { CloudAccount(this, store) }
    private val accountDialogs by lazy { AccountDialogs(this, cloud, worker, { if (destination == "settings") refreshSettings() }, ::restartAfterRestore) }

    /** Restored values are read by many screens and singletons: start the app afresh so every one of them sees them. */
    private fun restartAfterRestore(message: String) {
        store.put(ACCOUNT_NOTICE, message)
        InAppPlaybackSession.stop()
        val launch = packageManager.getLaunchIntentForPackage(packageName)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            ?: Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        startActivity(launch)
        finishAffinity()
        Runtime.getRuntime().exit(0)
    }

    /** Offered once, after Home first appears, to anyone without an account. */
    private fun offerAccount() {
        if (publicPreview || isDestroyed || cloud.signedIn || cloud.welcomeDone) return
        if (!isHome || window.decorView.hasWindowFocus().not()) { body.postDelayed({ offerAccount() }, 3_000); return }
        accountDialogs.welcome()
    }

    /** Automatic backup when the app goes to the background; nothing is sent when nothing changed. */
    private fun backupInBackground() {
        if (publicPreview || !::store.isInitialized) return
        val account = cloud
        if (!account.signedIn || !account.autoBackup) return
        Thread({ try { account.backup() } catch (_: Exception) { /* Tried again next time the app goes to the background. */ } }, "pov-backup").start()
    }

    override fun onResume() {
        super.onResume(); resumed = true; launchingPlayer = false
        // Links that arrived while the player was in front: the list catches up once, now that it is seen.
        activeSearch?.let { search ->
            // A Usenet link that failed in the player shows UNAVAILABLE as soon as the list is back.
            val failed = search.items.count { UsenetPlayback.failed(it.directUrl) }
            if (failed != search.failedUsenet) { search.failedUsenet = failed; search.needsRender = true }
            if (search.needsRender && search.rendered && generation == search.generation) { search.needsRender = false; renderSearch(search) }
        }
        if (!publicPreview) { TvContinueWatching.schedule(this); TvContinueWatching.request(this) }
        // Back from approving a sign-in code in the browser: check for it straight away.
        authorization?.takeIf { it.isShowing }?.pollNow()
        if (playerShown) { playerShown = false; scheduleContinueRefresh() }
        if (!publicPreview && ::store.isInitialized && android.os.SystemClock.elapsedRealtime() - watchedCheckedAt > 60_000) refreshWatched()
        if (::shellRoot.isInitialized) { updateMiniPlayer(); refreshHeroTrailer() }
        if (BackHoldGesture.settingsRequested && ::shellRoot.isInitialized) { BackHoldGesture.settingsRequested = false; openSettingsAnywhere() }
        PlayerShowInfo.pickedEpisode?.let { picked ->
            PlayerShowInfo.pickedEpisode = null
            if (::shellRoot.isInitialized) {
                toast("Playing S${picked.season} E${picked.episode}")
                navigate { showSources(picked, autoplayNext = true) }
            }
        }
        PlayerActivity.nextEpisodeAfter?.let { finished ->
            PlayerActivity.nextEpisodeAfter = null
            if (::shellRoot.isInitialized) runCatching { PlaybackIdentity.fromJson(org.json.JSONObject(finished)) }.getOrNull()?.let(::playNextEpisode)
        }
    }

    /**
     * The player's Next episode: the following episode of the same season, else the next season's first (specials
     * skipped), from the show's episode list. Its links are searched and the best one starts, as with Auto-play.
     */
    private fun playNextEpisode(finished: PlaybackIdentity) {
        if (finished.type != "series") return
        val show = MediaItemData(finished.imdbId, "series", finished.title)
        load(generation, { cachedDetails(show) ?: fetchDetails(show) }) { details ->
            val today = java.time.LocalDate.now().toString()
            val next = details.episodes.filter { it.season > 0 && (it.released.isBlank() || it.released.take(10) <= today) }
                .sortedWith(compareBy({ it.season }, { it.episode }))
                .firstOrNull { it.season > finished.season || (it.season == finished.season && it.episode > finished.episode) }
            if (next == null) { toast("That was the latest episode available."); return@load }
            toast("Playing S${next.season} E${next.episode}${if (next.title.isNotBlank()) " · ${next.title}" else ""}")
            navigate { showSources(finished.copy(season = next.season, episode = next.episode), autoplayNext = true) }
        }
    }
    override fun onPause() {
        // Keep the outgoing target until the incoming fullscreen view can attach first.
        heroTrailer?.stop()
        detailsTrailerChrome.stop()
        resumed = false; miniFrame?.setTag(R.id.pov_mini_shown, false)
        super.onPause()
    }
    override fun onStop() {
        miniPlayer?.let(InAppPlaybackSession::detachPlayerView)
        backupInBackground()
        super.onStop()
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) heroTrailer?.stop() else if (::body.isInitialized) refreshHeroTrailer()
    }
    override fun onDestroy() {
        heroTrailer?.stop(); heroTrailer = null
        detailsTrailerChrome.stop()
        window.decorView.viewTreeObserver.takeIf { it.isAlive }?.removeOnGlobalFocusChangeListener(contentFocusChanged)
        currentPlaybackOptionsDialog?.dismiss(); currentPlaybackOptionsDialog = null
        searchDialog?.dismiss(); authorization?.dismiss(); apiKeyDialog?.dismiss(); miniPlayer?.let(InAppPlaybackSession::detachPlayerView)
        InAppPlaybackSession.removeListener(playbackChanged)
        StreamChoices.searcher = null
        PlayerShowInfo.details = null; PlayerShowInfo.tmdb = null
        rotationHandler.removeCallbacksAndMessages(null); featureHandler.removeCallbacksAndMessages(null)
        worker.shutdownNow(); metadataWorker.shutdownNow(); imdbWorker.shutdownNow(); images.shutdownNow(); featureImages.shutdownNow(); authWorker.shutdownNow(); bitmapCache.evictAll()
        super.onDestroy()
    }
    @Deprecated("Android TV Back handling") override fun onBackPressed() {
        if (DeviceLayout.phone && backStack.isEmpty() && destination != "home") { openDestination(destinations().first { it.key == "home" }); return }
        // Flix: content → menu → exit; other top-level pages (Settings, Movies…) return to Home's content, so the
        // rows are right there instead of one or more presses away in the menu.
        if (flix && DeviceLayout.tv && backStack.isEmpty()) {
            val inMenu = navigationViews.values.any { it.hasFocus() }
            if (destination != "home") { openHomeContent(); return }
            if (!inMenu && openMenu()) return
        }
        if (backStack.isNotEmpty()) {
            val saved = backStack.removeLast(); current = saved.screen; destination = saved.destination
            pendingFocus = saved.focus
            val cached = saved.retained
            // Settings pages are always redrawn: switches on their sub-pages change values a kept copy would show stale.
            if (cached != null && cached.revision == pageRevision() && saved.destination != "settings") {
                restorePage(cached)
                wantsContentFocus = true; focusAfterRender(generation)
            } else current()
            refreshNavigation()
        } else AlertDialog.Builder(this).setTitle("Leave ${BuildConfig.APP_SHORT}?")
            .setPositiveButton(if (InAppPlaybackSession.hasActive()) "Stop and leave" else "Leave") { _, _ -> InAppPlaybackSession.stop(); finish() }
            .setNegativeButton("Stay", null).show()
    }
    private var splash: View? = null
    private var splashShownAt = 0L
    /**
     * Launch splash: the POV logo, continuing the system launch screen, held until Home's shelves are on
     * screen. It sits on the window itself, above every page, so theme rebuilds never cover it.
     */
    private fun showSplash() {
        val root = window.decorView as? FrameLayout ?: return
        val overlay = FrameLayout(this).apply { setBackgroundColor(0xFF141414.toInt()); isClickable = true; elevation = 100f }
        val size = if (DeviceLayout.phone) dp(120) else (resources.displayMetrics.widthPixels * 0.11f).roundToInt()
        // A neon glow in the logo's own cyan and magenta, breathing behind it.
        val glow = View(this).apply {
            background = GradientDrawable().apply {
                gradientType = GradientDrawable.RADIAL_GRADIENT; gradientRadius = size * 1.1f
                colors = intArrayOf(0x66C23CFF, 0x3322D3EE, 0x00141414)
            }
            alpha = 0f
        }
        overlay.addView(glow, FrameLayout.LayoutParams(size * 3, size * 3, Gravity.CENTER))
        // The logo sits in a clipped frame so a light sweep can glide across it.
        val badge = FrameLayout(this).apply {
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                // A round brand logo is clipped to a circle, so the shine never shows past its edge.
                override fun getOutline(view: View, outline: android.graphics.Outline) =
                    if (resources.getBoolean(R.bool.pov_logo_round)) outline.setOval(0, 0, view.width, view.height)
                    else outline.setRoundRect(0, 0, view.width, view.height, view.width * 0.06f)
            }
        }
        badge.addView(ImageView(this).apply { setImageResource(R.drawable.pov_logo); scaleType = ImageView.ScaleType.FIT_CENTER; contentDescription = "POV" }, FrameLayout.LayoutParams(-1, -1))
        val shine = View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0x00FFFFFF, 0x55FFFFFF, 0x00FFFFFF))
            rotation = 20f; translationX = -size * 1.2f
        }
        badge.addView(shine, FrameLayout.LayoutParams(size / 3, size * 2, Gravity.CENTER))
        overlay.addView(badge, FrameLayout.LayoutParams(size, size, Gravity.CENTER))
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        splash = overlay; splashShownAt = android.os.SystemClock.uptimeMillis()

        val running = mutableListOf<android.animation.Animator>()
        overlay.tag = running
        fun loop(animator: ValueAnimator) = animator.apply { repeatCount = ValueAnimator.INFINITE; start() }.also(running::add)
        // Spring in: from small and slightly tilted to upright, overshooting a touch.
        badge.alpha = 0f; badge.scaleX = 0.55f; badge.scaleY = 0.55f; badge.rotation = -10f
        badge.animate().alpha(1f).scaleX(1f).scaleY(1f).rotation(0f).setDuration(650)
            .setInterpolator(android.view.animation.OvershootInterpolator(1.8f)).withEndAction {
                if (splash !== overlay) return@withEndAction
                loop(ValueAnimator.ofFloat(1f, 1.05f).apply {
                    duration = 1_100; repeatMode = ValueAnimator.REVERSE
                    interpolator = android.view.animation.AccelerateDecelerateInterpolator()
                    addUpdateListener { badge.scaleX = it.animatedValue as Float; badge.scaleY = badge.scaleX }
                })
                loop(ValueAnimator.ofFloat(-size * 1.2f, size * 1.2f).apply {
                    duration = 1_400; startDelay = 200; repeatMode = ValueAnimator.RESTART
                    interpolator = android.view.animation.DecelerateInterpolator()
                    addUpdateListener { shine.translationX = it.animatedValue as Float }
                })
            }.start()
        glow.animate().alpha(1f).setDuration(700).withEndAction {
            if (splash !== overlay) return@withEndAction
            loop(ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 1_300; repeatMode = ValueAnimator.REVERSE
                addUpdateListener { val f = it.animatedValue as Float; glow.scaleX = 0.9f + f * 0.25f; glow.scaleY = glow.scaleX; glow.alpha = 0.55f + f * 0.45f }
            })
        }.start()
        // Normally Home's first shelves dismiss it; this only guards against a load that never returns.
        overlay.postDelayed({ dismissSplash() }, 10_000)
    }

    private fun dismissSplash() {
        val overlay = splash ?: return
        // Never flash: keep the logo up long enough to finish its entrance.
        val wait = 1_200 - (android.os.SystemClock.uptimeMillis() - splashShownAt)
        if (wait > 0) { overlay.postDelayed({ dismissSplash() }, wait); return }
        splash = null
        body.postDelayed({ offerAccount() }, 1_500)
        @Suppress("UNCHECKED_CAST") (overlay.tag as? List<android.animation.Animator>)?.forEach { it.cancel() }
        // Exit: the logo pushes forward as the splash dissolves into Home.
        (overlay as? ViewGroup)?.let { group -> (0 until group.childCount).map(group::getChildAt).forEach {
            it.animate().scaleX(it.scaleX * 1.18f).scaleY(it.scaleY * 1.18f).setDuration(380).start()
        } }
        overlay.animate().alpha(0f).setDuration(380).withEndAction { (overlay.parent as? ViewGroup)?.removeView(overlay); refreshHeroTrailer() }.start()
    }

    private var lastRepeatAt = 0L
    private val backHold = BackHoldGesture { openSettingsAnywhere() }
    /** Hold Back for one second: Settings, from whatever page is open. */
    private fun openSettingsAnywhere() {
        searchDialog?.dismiss()
        retainHome()
        destination = "settings"; backStack.clear(); current = { settings() }
        settings(); refreshNavigation()
        if (flix) body.post { if (!focusController.first()) wantsContentFocus = true }
    }
    override fun dispatchTouchEvent(event: android.view.MotionEvent): Boolean {
        // A tap during the auto-play countdown stops it (the tapped link, if any, still opens).
        if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN && autoplayCountdown != null && cancelAutoplayCountdown()) toast("Auto-play stopped · choose a link")
        return super.dispatchTouchEvent(event)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val back = event.keyCode == KeyEvent.KEYCODE_BACK || event.keyCode == KeyEvent.KEYCODE_ESCAPE
        if (swallowBackUp && back && event.action == KeyEvent.ACTION_UP) { swallowBackUp = false; return true }
        // Any key during the auto-play countdown stops it; Back stops it without leaving the link list.
        if (event.action == KeyEvent.ACTION_DOWN && autoplayCountdown != null && cancelAutoplayCountdown()) {
            toast("Auto-play stopped · choose a link")
            if (back) { swallowBackUp = true; return true }
        }
        if (back && event.action == KeyEvent.ACTION_DOWN && clearSourceFilter?.invoke() == true) { swallowBackUp = true; return true }
        if (detailsTrailerChrome.key(event)) return true
        if (backHold.intercept(event)) return true
        // Show page: one Down from the buttons goes to the next episode's card (and brings the season tabs into view).
        if (flix && event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            val target = episodeDownTarget; val actions = episodeActions
            if (target != null && actions != null && target.isAttachedToWindow && actions.isAttachedToWindow && actions.findFocus() != null) {
                target.requestFocus(); return true
            }
        }
        // Holding the D-pad on Home moves at a readable pace instead of racing ahead of the scroll.
        if (flix && isHome && event.action == KeyEvent.ACTION_DOWN && event.repeatCount > 0 && ::body.isInitialized && body.hasFocus() && event.keyCode in setOf(
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT)) {
            val now = android.os.SystemClock.uptimeMillis()
            // Up and down step a whole shelf with a short scroll (HOME_ROW_SCROLL_MS), so they can repeat nearly as fast as sideways.
            val gap = if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP || event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) 150 else 140
            if (now - lastRepeatAt < gap) return true
            lastRepeatAt = now
        }
        if (event.keyCode == KeyEvent.KEYCODE_MENU && InAppPlaybackSession.hasActive()) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) playbackOptions()
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN && ::focusController.isInitialized) {
            val direction = when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> View.FOCUS_LEFT
                KeyEvent.KEYCODE_DPAD_RIGHT -> View.FOCUS_RIGHT
                KeyEvent.KEYCODE_DPAD_UP -> View.FOCUS_UP
                KeyEvent.KEYCODE_DPAD_DOWN -> View.FOCUS_DOWN
                else -> null
            }
            if (direction != null && miniFocus?.route(direction, currentFocus) == true) return true
            if (flix && (direction == View.FOCUS_UP || direction == View.FOCUS_DOWN)) {
                // The menu stops at its first and last entries instead of leaving for the content.
                val menu = navigationViews.values.filter { it.isShown }.sortedBy { it.top }
                val index = menu.indexOfFirst { it.hasFocus() }
                if (index >= 0) {
                    val next = menu.getOrNull(index + if (direction == View.FOCUS_DOWN) 1 else -1) ?: return true
                    next.requestFocus(); return true
                }
            }
            // Settings: from any setting under the section cards, Right and Left go straight to the next and previous
            // section (focus on its card, at the top); Left on the first section opens the menu. Up from the top setting
            // returns to the shown section's card instead of jumping diagonally onto another one.
            if (destination == "settings" && direction != null && direction != View.FOCUS_DOWN && inSettingsContent(currentFocus)) {
                val target = focusController.next(direction)
                val ontoCard = (target?.tag as? String)?.startsWith("settings-section:") == true
                when {
                    direction == View.FOCUS_RIGHT || direction == View.FOCUS_LEFT -> {
                        val index = settingsSections.indexOfFirst { it.first == settingsSection }
                        val next = settingsSections.getOrNull(index + if (direction == View.FOCUS_RIGHT) 1 else -1)?.first
                        if (next == null) {
                            if (direction == View.FOCUS_LEFT && theme.layout.navigation == NavigationPlacement.LEFT_RAIL) {
                                if (flix) openMenu() else navigationViews[destination]?.requestFocus()
                            }
                            return true
                        }
                        settingsChoose?.invoke(next)
                        body.findViewWithTag<View>("settings-section:$next")?.requestFocus()
                        scroller.smoothScrollTo(0, 0)
                        return true
                    }
                    ontoCard || target == null -> {
                        body.findViewWithTag<View>("settings-section:$settingsSection")?.requestFocus()
                        scroller.smoothScrollTo(0, 0)
                        return true
                    }
                }
            }
            // A shelf ends at its last card ("See all") rather than wrapping elsewhere, even when a card of the next
            // row lies further right.
            if (direction == View.FOCUS_RIGHT && body.hasFocus() && atRowEnd(currentFocus)) return true
            if (flix && direction == View.FOCUS_RIGHT && body.hasFocus() && focusController.next(direction) == null) return true
            if (body.hasFocus() && direction != null && focusController.next(direction) == null) {
                val leaving = (theme.layout.navigation == NavigationPlacement.TOP_BAR && direction == View.FOCUS_UP) ||
                    (theme.layout.navigation == NavigationPlacement.LEFT_RAIL && direction == View.FOCUS_LEFT)
                if (leaving && (if (flix) openMenu() else (navigationViews["nowplaying"] ?: navigationViews[destination])?.requestFocus() == true)) {
                    if (direction == View.FOCUS_UP) scroller.scrollTo(0, 0)
                    return true
                }
            }
            if (body.hasFocus() && event.keyCode in setOf(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_UP))
                return focusController.move(if (event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) View.FOCUS_DOWN else View.FOCUS_UP)
            val entering = (theme.layout.navigation == NavigationPlacement.LEFT_RAIL && event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) ||
                (theme.layout.navigation == NavigationPlacement.TOP_BAR && event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN)
            if (entering && navigationViews.values.any { it.hasFocus() } && focusController.first()) return true
        }
        return super.dispatchKeyEvent(event)
    }

    private data class Destination(val key: String, val label: String, val screen: () -> Unit)
    /** On TV, Now playing heads the menu so the video is one press away; the phone's tab bar keeps it last. */
    private fun destinations(): List<Destination> {
        val pages = listOf(
            Destination("home", "Home") { home() }, Destination("movie", "Movies") { catalogPage("movie") },
            Destination("series", "Shows") { catalogPage("series") }, Destination("anime", "Anime") { animePage() },
            Destination("library", "Library") { selectedCloud() }, Destination("search", "Search") { search() },
            Destination("wetrakr", wetrakr.name) { weTrakrPage() }, Destination("settings", "Settings") { settings() })
        if (publicPreview || !InAppPlaybackSession.hasActive()) return pages
        val playing = Destination("nowplaying", "Now playing") { returnToVideo() }
        return if (DeviceLayout.tv) listOf(playing) + pages else pages + playing
    }

    /** Status bar height on the phone (0 on TV), added to each page's top padding. */
    private var systemTop = 0
    private var statusScrim: View? = null

    private fun shell() {
        heroTrailer?.stop(); heroTrailer = null
        miniPlayer?.let(InAppPlaybackSession::detachPlayerView)
        miniPlayer = null; miniFrame = null; miniLabel = null; miniFocus = null
        navigationViews.clear(); rail = null; railExpanded = false; railLabels.clear(); notice = null
        shellRoot = FrameLayout(this).apply { setBackgroundColor(bg); clipChildren = false }
        // Flix draws the page colour once from the window, and the backdrop as one pre-shaded image.
        if (flix) { window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(bg)); shellRoot.background = null }
        heroBackdrop = FrameLayout(this).apply { visibility = View.GONE }
        backdropArt = FixedSizeImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        heroBackdrop.addView(backdropArt, FrameLayout.LayoutParams(-1, -1))
        sourcesArt = FixedSizeImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; visibility = View.GONE }
        heroBackdrop.addView(sourcesArt, FrameLayout.LayoutParams(-1, -1))
        sourcesScrim = View(this).apply { visibility = View.GONE }
        heroBackdrop.addView(sourcesScrim, FrameLayout.LayoutParams(-1, -1))
        if (!flix) {
            heroBackdrop.addView(View(this).apply { background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(bg, withAlpha(bg, 180), Color.TRANSPARENT)) }, FrameLayout.LayoutParams(-1, -1))
            heroBackdrop.addView(View(this).apply { background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(bg, Color.TRANSPARENT, Color.TRANSPARENT)) }, FrameLayout.LayoutParams(-1, -1))
        }
        // The flix layout keeps a full-screen living backdrop behind every row.
        shellRoot.addView(heroBackdrop, FrameLayout.LayoutParams(-1, if (flix) -1 else dp(325)))
        scroller = TvScrollView(this) { !(flix && isHome) }.apply { isFillViewport = true; isVerticalScrollBarEnabled = false; clipChildren = false; clipToPadding = false }
        body = CullingLinearLayout(this, resources.displayMetrics.heightPixels / 2).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
        scroller.addView(body)
        focusController = TvFocusController(body, scroller, ::placeFlixRow) {
            val rect = android.graphics.Rect()
            miniFrame?.takeIf { it.isShown && it.getGlobalVisibleRect(rect) }?.let { rect }
        }
        scroller.setOnScrollChangeListener { _, _, y, _, _ ->
            // The feature copy stays pinned while rows slide beneath it.
            statusScrim?.alpha = (y / dp(40).toFloat()).coerceIn(0f, 1f)
            // On the phone the banner and its copy scroll away with the page.
            // The link list's art stays put behind the list.
            if (flix && DeviceLayout.tv) featureHero()?.translationY = y.toFloat() else if (!sourcesPage) heroBackdrop.translationY = -y.toFloat()
            heroTrailer?.stop()
            body.postDelayed({ refreshHeroTrailer() }, 400)
            body.invalidate()
        }
        val layout = theme.layout
        shellRoot.addView(scroller, FrameLayout.LayoutParams(-1, -1).apply {
            leftMargin = dp(layout.railWidthDp + layout.contentInsetDp)
            rightMargin = dp(layout.contentInsetDp)
            topMargin = dp(layout.headerHeightDp)
            if (layout.navigation == NavigationPlacement.BOTTOM_BAR) bottomMargin = dp(BOTTOM_BAR_HEIGHT)
        })
        if (layout.navigation == NavigationPlacement.BOTTOM_BAR) {
            val bar = LinearLayout(this).apply { setBackgroundColor(PHONE_BAR_COLOR); elevation = dp(4).toFloat() }
            shellRoot.addView(bar, FrameLayout.LayoutParams(-1, dp(BOTTOM_BAR_HEIGHT), Gravity.BOTTOM))
            destinations().forEach { target -> bar.addView(bottomNavigation(target), LinearLayout.LayoutParams(0, -1, 1f)) }
        } else if (layout.navigation == NavigationPlacement.LEFT_RAIL) {
            val rail = column().apply {
                setPadding(dp(12), dp(22), dp(1), dp(13))
                background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(bg, withAlpha(bg, 235)))
            }
            if (flix) {
                // Hidden until Back or Left from the first column; it then slides in over the content.
                rail.setPadding(dp(14), dp(20), dp(8), dp(13)); rail.background = railBackground(false); this.rail = rail
                rail.visibility = View.VISIBLE; rail.clipChildren = true
            }
            shellRoot.addView(rail, FrameLayout.LayoutParams(dp(layout.railWidthDp), -1))
            if (flix) rail.addView(logo(), margin(40, 40, bottom = 34).apply { leftMargin = dp(10) })
            else rail.addView(logo(), margin(61, 58, bottom = 24).apply { leftMargin = dp(10) })
            destinations().forEach { target -> rail.addView(navigation(target, false), margin(-1, 35, bottom = 4)) }
            rail.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        } else {
            val header = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL; setPadding(dp(layout.contentInsetDp), dp(5), dp(16), dp(5))
            }
            shellRoot.addView(header, FrameLayout.LayoutParams(-1, dp(layout.headerHeightDp)))
            header.addView(logo(), margin(45, 45, right = 22))
            destinations().forEach { target -> header.addView(navigation(target, true), LinearLayout.LayoutParams(0, dp(38), 1f)) }
        }
        if (DeviceLayout.phone) shellRoot.setOnApplyWindowInsetsListener { root, insets ->
            // Android 15 draws apps edge to edge: Home's banner may sit under the status bar, but page
            // titles move below it, and the tab bar, mini player and notices stay above the gesture bar.
            @Suppress("DEPRECATION") val top = insets.systemWindowInsetTop
            @Suppress("DEPRECATION") val bottom = insets.systemWindowInsetBottom
            root.setPadding(0, 0, 0, bottom)
            statusScrim?.let { scrim -> if (scrim.layoutParams.height != top) scrim.layoutParams = scrim.layoutParams.apply { height = top } }
            if (top != systemTop) {
                if (!isHome) body.setPadding(body.paddingLeft, body.paddingTop + top - systemTop, body.paddingRight, body.paddingBottom)
                systemTop = top
            }
            insets
        }
        statusScrim = if (DeviceLayout.phone) View(this).apply { setBackgroundColor(bg); alpha = 0f; elevation = dp(5).toFloat() }
            .also { shellRoot.addView(it, FrameLayout.LayoutParams(-1, systemTop, Gravity.TOP)) } else null
        setContentView(shellRoot)
        refreshNavigation()
        if (resumed) updateMiniPlayer()
    }

    private fun logo() = ImageView(this).apply {
        setImageResource(R.drawable.pov_logo); scaleType = ImageView.ScaleType.FIT_CENTER; contentDescription = "POV"
    }

    private fun navigation(target: Destination, top: Boolean): View {
        val frame = FrameLayout(this).apply {
            isFocusable = true; isFocusableInTouchMode = DeviceLayout.tv; isClickable = true; contentDescription = target.label
            tag = "nav:${target.key}"
        }
        val line = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        if (!top) {
            line.addView(NavigationIconView(this, target.key, muted), margin(19, 19, right = 12))
            line.setPadding(dp(11), 0, 0, 0)
        } else line.gravity = Gravity.CENTER
        val label = text(target.label, if (top) 12 else 12, muted)
        if (flix && !top) {
            label.visibility = if (railExpanded) View.VISIBLE else View.GONE; label.maxLines = 1; railLabels += label
        }
        if (target.key == "wetrakr" && wetrakr is WeTrakrApi && !(flix && !top)) {
            if (!top && line.childCount > 0) line.removeViewAt(0)
            line.addView(ImageView(this).apply {
                setImageResource(R.drawable.wetrakr_logo)
                scaleType = ImageView.ScaleType.FIT_CENTER; contentDescription = "WeTrakr"
            }, LinearLayout.LayoutParams(if (top) dp(85) else -1, dp(26)))
        } else line.addView(label, LinearLayout.LayoutParams(-2, -2))
        frame.addView(line, FrameLayout.LayoutParams(-1, -1))
        val indicator = View(this).apply { tag = "indicator"; background = rounded(accent, 2) }
        frame.addView(indicator, if (top) FrameLayout.LayoutParams(dp(31), dp(2), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
            else FrameLayout.LayoutParams(dp(3), dp(29), Gravity.START or Gravity.CENTER_VERTICAL))
        fun paint(focused: Boolean) {
            val selected = destination == target.key
            val color = if (selected || focused) ink else muted
            label.setTextColor(color); label.typeface = Typeface.create("sans-serif", if (selected) Typeface.BOLD else Typeface.NORMAL)
            for (index in 0 until line.childCount) (line.getChildAt(index) as? NavigationIconView)?.tint = color
            indicator.visibility = if (selected) View.VISIBLE else View.INVISIBLE
            if (flix) {
                frame.background = rounded(if (focused) theme.palette.navSelected else Color.TRANSPARENT, 4)
                frame.foreground = null
                return
            }
            frame.background = rounded(if (focused || (!top && selected)) theme.palette.navSelected else Color.TRANSPARENT, 7)
            frame.foreground = if (focused) FocusRingDrawable(accent, dp(7).toFloat(), true, dp(1).toFloat()) else null
        }
        frame.setOnFocusChangeListener { _, focused -> paint(focused) }
        frame.setOnClickListener { openDestination(target) }
        navigationViews[target.key] = frame
        paint(false)
        return frame
    }

    private fun openDestination(target: Destination) {
        if (target.key == "search" || target.key == "nowplaying") target.screen()
        else {
            if (target.key != "home") retainHome()
            destination = target.key; backStack.clear(); current = target.screen; current(); refreshNavigation()
            // The chosen page takes focus and the menu tucks away; pages still loading claim it when ready.
            if (flix && DeviceLayout.tv) body.post { if (!focusController.first()) wantsContentFocus = true }
        }
    }

    /** Phone navigation: an icon over a short label, the current tab in white. */
    private fun bottomNavigation(target: Destination): View {
        val cell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; isClickable = true
            tag = "nav:${target.key}"; contentDescription = target.label
            background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x33FFFFFF), null, null)
        }
        val icon = NavigationIconView(this, target.key, muted)
        cell.addView(icon, margin(21, 21))
        val label = text(if (target.key == "nowplaying") "Playing" else target.label, 9, muted).apply { maxLines = 1; gravity = Gravity.CENTER }
        cell.addView(label, margin(-2, -2, top = 3))
        fun paint() {
            val color = if (destination == target.key) ink else muted
            label.setTextColor(color); icon.tint = color
            label.typeface = Typeface.create("sans-serif", if (destination == target.key) Typeface.BOLD else Typeface.NORMAL)
        }
        // refreshNavigation() repaints every tab through this listener.
        cell.setOnFocusChangeListener { _, _ -> paint() }
        cell.setOnClickListener { openDestination(target) }
        navigationViews[target.key] = cell
        paint()
        return cell
    }

    private fun refreshNavigation() {
        // Rebuilding a nav cell's state does not steal focus from remote navigation.
        navigationViews.values.forEach { view -> view.onFocusChangeListener?.onFocusChange(view, view.hasFocus()) }
    }

    private var retainedHome: RetainedPage? = null
    private var retainedHomeAt = 0L
    /** Keeps the built Home page (from screen or from the back stack) when the menu leads elsewhere. */
    private fun retainHome() {
        val page = if (isHome && pendingLoads[generation].orZero() == 0) retainPage() else backStack.firstOrNull { it.retained?.home == true }?.retained
        if (page != null) { retainedHome = page; retainedHomeAt = android.os.SystemClock.elapsedRealtime() }
    }
    private fun retainPage() = RetainedPage(
        (0 until body.childCount).map(body::getChildAt), intArrayOf(body.paddingLeft, body.paddingTop, body.paddingRight, body.paddingBottom),
        isHome, generation, heroBackdrop.visibility, heroBackdrop.layoutParams.height, backdropArt.drawable, backdropArt.tag as? String, pageRevision(), sourcesPage)
    private fun restorePage(cached: RetainedPage) {
        homeTrailerFocus = null
        heroTrailer?.stop()
        generation = cached.generation; resolvingGeneration = null; isHome = cached.home; flixScrollTarget = -1
        focusController.reset(); body.removeAllViews()
        body.animate().cancel(); body.alpha = 1f; body.translationY = 0f
        body.setPadding(cached.padding[0], cached.padding[1], cached.padding[2], cached.padding[3])
        cached.children.forEach { (it.parent as? ViewGroup)?.removeView(it); body.addView(it) }
        heroBackdrop.animate().cancel(); heroBackdrop.alpha = 1f; heroBackdrop.scaleX = 1f; heroBackdrop.scaleY = 1f
        heroBackdrop.visibility = cached.heroVisibility; heroBackdrop.layoutParams.height = cached.heroHeight
        if (cached.sources && flix) { sourcesPage = true; sourcesLayers() } else leaveSourcesBackdrop()
        placeBackdrop(home = cached.home)
        backdropArt.tag = cached.heroAddress; backdropArt.setImageDrawable(cached.heroArt)
        backdropArt.setTag(R.id.pov_backdrop_key, cached.heroAddress?.let { if (cached.home) "backdrop-corner:$it" else "backdrop:$it" })
        if (cached.heroArt == null) cached.heroAddress?.let { image(backdropArt, it) }
        heroBackdrop.requestLayout(); featureShown = null; backdropArt.alpha = 1f
        body.post { refreshHeroTrailer() }
    }

    private fun navigate(screen: () -> Unit) {
        val retained = if (pendingLoads[generation].orZero() == 0) retainPage() else null
        backStack.addLast(NavigationSnapshot(current, focusController.capture(), destination, retained))
        while (backStack.count { it.retained != null } > 4) {
            val oldest = backStack.indexOfFirst { it.retained != null }; backStack[oldest] = backStack[oldest].copy(retained = null)
        }
        pendingFocus = null; current = screen; screen()
    }

    private fun Int?.orZero() = this ?: 0
    private fun pageRevision() = java.security.MessageDigest.getInstance("SHA-256").digest(
        listOf(theme.id, wetrakr.accountRevision(), wetrakr.connected().toString(), store.get("offcloud.key"), store.get("torbox.token"), store.get("wetrakr.home_liked_opt_in"), store.get("sources.endpoint"), store.get(SourceProviders.KEY), store.get(TMDB_KEY), store.get(TMDB_REGION), store.get(TVDB_KEY), store.get(TVDB_SHOWS), store.get(RESUME_CARDS), store.get(CatalogAddons.KEY), homeRows.revision,
            // Without a tracker, Home and title pages show local progress, so a kept copy is stale once something was watched.
            if (publicPreview || wetrakr.connected()) "" else history.continueRevision()).joinToString("\u0000").toByteArray()
    ).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun focusAfterRender(id: Int) {
        body.post {
            if (id != generation || isDestroyed) return@post
            val restore = pendingFocus
            if (restore != null) {
                if (focusController.restore(restore) || focusController.restore(null)) { pendingFocus = null; wantsContentFocus = false }
            } else if (wantsContentFocus && !focusController.hasVisibleLeafFocus()) {
                if (focusController.restore(null)) wantsContentFocus = false
            }
        }
    }
    private fun page(title: String, subtitle: String = ""): Int {
        // Leaving the link list stops its auto-play countdown; the list re-rendering itself keeps it.
        if (!renderingSearch) cancelAutoplayCountdown()
        homeTrailerFocus = null
        heroTrailer?.stop()
        generation = ++pageSequence; resolvingGeneration = null; isHome = false; wantsContentFocus = true; flixScrollTarget = -1
        featureHandler.removeCallbacksAndMessages(null); featureShown = null; backdropArt.alpha = 1f
        bottomAligner?.let(body::removeOnLayoutChangeListener); bottomAligner = null
        for (index in 0 until body.childCount) body.getChildAt(index).animate().cancel()
        heroBackdrop.animate().cancel(); heroBackdrop.alpha = 1f; heroBackdrop.scaleX = 1f; heroBackdrop.scaleY = 1f
        focusController.reset(); body.removeAllViews(); heroBackdrop.visibility = View.GONE; leaveSourcesBackdrop()
        body.animate().cancel(); body.alpha = 1f; body.translationY = 0f
        body.setPadding(0, systemTop + dp(when (theme.layout.navigation) { NavigationPlacement.LEFT_RAIL -> 63; NavigationPlacement.BOTTOM_BAR -> 16; else -> 15 }), 0, contentBottomPadding())
        scroller.scrollTo(0, 0); heroBackdrop.translationY = 0f
        if (title.isNotBlank()) body.addView(text(title, if (DeviceLayout.phone) 24 else 29, ink, true), margin(-1, -2, bottom = 5))
        if (subtitle.isNotBlank()) body.addView(text(subtitle, 12, muted), margin(-1, -2, bottom = 18))
        return generation
    }

    private fun home() {
        current = { home() }
        destination = "home"; refreshNavigation()
        val kept = retainedHome; retainedHome = null
        if (kept != null && kept.revision == pageRevision() && android.os.SystemClock.elapsedRealtime() - retainedHomeAt < HOME_KEEP_MS) {
            // Coming back through the menu shows the same Home at once, from the top.
            restorePage(kept); homeRevision = kept.revision
            scroller.scrollTo(0, 0)
            if (flix) {
                featureHero()?.let { hero -> hero.translationY = 0f; hero.findViewWithTag<View>("feature-actions")?.alpha = 1f }
                for (index in 0 until body.childCount) body.getChildAt(index).let { it.animate().cancel(); it.alpha = 1f }
            }
            if (homeMenuFocus) { homeMenuFocus = false; wantsContentFocus = false; openMenu() }
            else { wantsContentFocus = true; focusAfterRender(generation) }
            return
        }
        val id = page(""); isHome = true; body.setPadding(0, 0, 0, contentBottomPadding())
        localContinueRow = null; homeRevision = pageRevision()
        val remoteHome = !publicPreview && wetrakr.connected()
        val recent = if (publicPreview || remoteHome) emptyList() else history.continueWatching()
        // Something in progress: the feature trims so the first screen ends on a complete row.
        val compact = flix && if (remoteHome) !cachedContinueWatching().isNullOrEmpty() else recent.isNotEmpty()
        fun render(shelves: List<CatalogApi.HomeShelf>) {
            // Shelves saved before the Top 10 lineup still name their charts "Popular …".
            val movies = (shelves.firstOrNull { it.title == TOP10_MOVIES } ?: shelves.firstOrNull { it.title == "Popular Movies" })?.items.orEmpty()
            val series = (shelves.firstOrNull { it.title == TOP10_SHOWS } ?: shelves.firstOrNull { it.title == "Popular Shows" })?.items.orEmpty()
            val rotation = if (flix) (0 until 10).flatMap { listOfNotNull(movies.getOrNull(it), series.getOrNull(it)) } else emptyList()
            (movies.firstOrNull() ?: shelves.firstOrNull { it.items.isNotEmpty() }?.items?.firstOrNull())?.let { if (flix) streamingHero(it, true, rotation, compact) else hero(it) }
            if (remoteHome) homeContinueWatching(id) else if (recent.isNotEmpty()) resumeRow(recent)
            if (flix && !compact) {
                rankedRow("Top 10 Movies", movies)
                rankedRow("Top 10 Shows", series)
            }
            var topTenPending = flix && compact
            shelves.filter { it.title != TOP10_MOVIES && it.title != TOP10_SHOWS }.forEach { shelf ->
                if (shelf.items.isNotEmpty()) {
                    mediaRow(shelf.title, shelf.items, seeAll = if (rowPageable(shelf.id)) ({ navigate { rowGrid(shelf) } }) else null)
                    // With Continue Watching on the first screen, the tall Top 10 shelves follow the first regular row.
                    if (topTenPending) { topTenPending = false; rankedRow("Top 10 Movies", movies); rankedRow("Top 10 Shows", series) }
                } else {
                    body.addView(button("${shelf.title}  ›") { navigate { shelfGrid(shelf.title, shelf.items) } }
                        .apply { tag = "shelf:${shelf.title}" }, margin(-1, 40, top = 10, bottom = 7))
                    body.addView(text(if (shelf.unavailable) "This catalog is temporarily unavailable." else "No titles returned for this catalog.", 13, muted))
                    if (shelf.unavailable) body.addView(button("Retry ${shelf.title}") { home() }, margin(240, 38, bottom = 10))
                }
            }
            if (!publicPreview && wetrakr.connected() && store.get("wetrakr.home_liked_opt_in") == "true") homeLikedLists(id)
            // Credits live in Settings → About; Home ends at its last shelf. The feature's Play starts focused.
            body.post { dismissSplash() }
            if (flix) warmUpHome((movies.take(3) + series.take(3) + shelves.filter { it.title != TOP10_MOVIES && it.title != TOP10_SHOWS }
                .take(2).flatMap { it.items.take(2) }).distinct())
            if (flix) {
                if (homeMenuFocus) { homeMenuFocus = false; wantsContentFocus = false; if (navigationViews.values.none { it.hasFocus() }) openMenu() } else focusAfterRender(id)
            }
        }
        // Home opens with the rows saved from the last visit (like Netflix and YouTube), then refreshes them quietly.
        val revision = shelfRevision()
        val saved = if (publicPreview || skipHomeRefresh) null else shelfCache.load(revision)
        val refreshed = skipHomeRefresh; skipHomeRefresh = false
        if (saved != null) {
            render(saved); focusAfterRender(id)
            worker.execute {
                val fresh = try { homeShelves() } catch (_: Exception) { return@execute }
                shelfCache.save(revision, fresh)
                runOnUiThread {
                    // New rows replace the saved ones only while the viewer is still at the top of Home; otherwise
                    // they wait for the next visit, so nothing moves under a browsing viewer.
                    if (id == generation && isHome && !isFinishing && !isDestroyed && scroller.scrollY == 0 &&
                        !HomeShelfCache.sameRows(saved, fresh)) { pendingShelves = fresh; skipHomeRefresh = true; home() }
                }
            }
        } else {
            val ready = pendingShelves.takeIf { refreshed }; pendingShelves = null
            if (ready != null) { render(ready); focusAfterRender(id) }
            else read(id, { homeShelves().also { if (!publicPreview) shelfCache.save(revision, it) } }) { shelves -> render(shelves) }
        }
    }

    private val shelfCache by lazy { HomeShelfCache(java.io.File(filesDir, "home_shelves.json")) }
    private var skipHomeRefresh = false
    private var pendingShelves: List<CatalogApi.HomeShelf>? = null
    /** Saved rows belong to one catalog setup: changing the TMDB key, country or theme starts fresh. */
    private fun shelfRevision() = java.security.MessageDigest.getInstance("SHA-256")
        .digest(listOf(SHELF_SET_VERSION, theme.id, store.get(TMDB_KEY), tmdbRegion(), store.get(CatalogAddons.KEY), homeRows.revision).joinToString("\u0000").toByteArray())
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun cachedContinueWatching(): List<Pair<PlaybackIdentity, Double>>? = try {
        org.json.JSONObject(store.get(CONTINUE_CACHE)).takeIf { it.optString("revision") == wetrakr.accountRevision() }?.optJSONArray("items")?.let { items ->
            (0 until items.length()).map { index -> items.getJSONObject(index).let { PlaybackIdentity.fromJson(it.getJSONObject("id")) to it.getDouble("percent") } }
        }
    } catch (_: Exception) { null }

    /**
     * Home's lineup. Top 10 is a daily chart: TMDB Trending Today with a key, Queued Up's global trending without one
     * (Cinemeta's new releases if Queued Up is down). Latest Releases come from Queued Up (falling back to TMDB's or
     * Cinemeta's newest), and no list used for Top 10 is shown again as a row.
     */
    private fun homeShelves(): List<CatalogApi.HomeShelf> {
        val key = if (publicPreview) "" else store.get(TMDB_KEY)
        val queued = QueuedUpCatalog(this)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(6)
        try {
            fun ask(list: () -> List<MediaItemData>?) = pool.submit(java.util.concurrent.Callable { runCatching(list).getOrNull() })
            val trending = listOf("movie", "series").associateWith { type -> ask { queued.trending(type) } }
            val latest = listOf("movie", "series").associateWith { type -> ask { queued.latest(type) } }
            // The viewer's catalog add-ons load alongside; each is someone else's service, so a failure only marks its row.
            val addonTasks = if (publicPreview) emptyList() else catalogAddons.load().flatMap { addon -> addon.catalogs.map { catalog ->
                Triple(addon, catalog, pool.submit(java.util.concurrent.Callable { runCatching { catalogAddons.items(addon, catalog) } })) } }
            // A slow Queued Up must not hold Home back; past this wait its rows use the fallbacks.
            fun answer(task: java.util.concurrent.Future<List<MediaItemData>?>?) =
                try { task?.get(6, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Exception) { null }?.takeIf { it.isNotEmpty() }
            fun addonRows() = addonTasks.map { (addon, catalog, task) ->
                val result = try { task.get(8, java.util.concurrent.TimeUnit.SECONDS) } catch (failure: Exception) { Result.failure(failure) }
                CatalogApi.HomeShelf(catalog.name, catalog.type, result.getOrDefault(emptyList()), result.isFailure, CatalogAddons.rowId(addon, catalog))
            }
            fun row(id: String, title: String, type: String, items: List<MediaItemData>) = CatalogApi.HomeShelf(title, type, items, id = id)
            fun CatalogApi.HomeShelf?.named(id: String) = this?.copy(id = id)
            if (key.isNotBlank()) {
                val tmdb = TmdbCatalog(this, key, tmdbRegion())
                val shelves = tmdb.homeShelves()
                if (shelves.any { !it.unavailable && it.items.isNotEmpty() }) {
                    fun shelf(title: String) = shelves.firstOrNull { it.title == title }
                    fun newest(type: String) = answer(latest[type]) ?: runCatching { tmdb.discover(type, "", TmdbCatalog.SORT_NEWEST, 1) }.getOrDefault(emptyList())
                    return homeLineup(shelf("Trending Movies Today")?.items.orEmpty(), shelf("Trending Shows Today")?.items.orEmpty(), listOf(
                        row(ROW_LATEST_MOVIES, LATEST_MOVIES, "movie", newest("movie")), shelf("Now Playing in Cinemas").named(ROW_CINEMAS),
                        row(ROW_LATEST_SHOWS, LATEST_SHOWS, "series", newest("series")), shelf("Popular Movies").named(ROW_POPULAR_MOVIES),
                        shelf("Popular Shows").named(ROW_POPULAR_SHOWS)) + addonRows())
                }
                runOnUiThread { toast("TMDB is unavailable. Showing Cinemeta catalogs instead.") }
            }
            val shelves = catalog.homeShelves()
            fun shelf(title: String) = shelves.firstOrNull { it.title == title }
            val newMovies = shelf("New Movies")?.items.orEmpty()
            val newShows = shelf("New Shows")?.items.orEmpty()
            return homeLineup(answer(trending["movie"]) ?: newMovies, answer(trending["series"]) ?: newShows, listOf(
                row(ROW_LATEST_MOVIES, LATEST_MOVIES, "movie", answer(latest["movie"]) ?: newMovies),
                row(ROW_LATEST_SHOWS, LATEST_SHOWS, "series", answer(latest["series"]) ?: newShows),
                shelf("Featured Movies").named(ROW_FEATURED_MOVIES), shelf("Featured Shows").named(ROW_FEATURED_SHOWS),
                shelf("Popular Movies").named(ROW_POPULAR_MOVIES), shelf("Popular Shows").named(ROW_POPULAR_SHOWS)) + addonRows())
        } finally { pool.shutdownNow() }
    }

    private val homeRows by lazy {
        val preferences = getSharedPreferences("pov_home_rows", MODE_PRIVATE)
        HomeRows({ preferences.getString(it, null) }, { key, value -> preferences.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply() })
    }
    private val catalogAddons get() = CatalogAddons(store)

    /** Every row the viewer can place on Home right now, in default order: built-in rows for this setup, then add-on rows. */
    private fun editableHomeRows(): List<HomeRow> {
        val builtIn = if (store.get(TMDB_KEY).isNotBlank()) listOf(
            HomeRow(ROW_LATEST_MOVIES, LATEST_MOVIES), HomeRow(ROW_CINEMAS, "Now Playing in Cinemas"), HomeRow(ROW_LATEST_SHOWS, LATEST_SHOWS),
            HomeRow(ROW_POPULAR_MOVIES, "Popular Movies"), HomeRow(ROW_POPULAR_SHOWS, "Popular Shows"))
        else listOf(HomeRow(ROW_LATEST_MOVIES, LATEST_MOVIES), HomeRow(ROW_LATEST_SHOWS, LATEST_SHOWS),
            HomeRow(ROW_FEATURED_MOVIES, "Featured Movies"), HomeRow(ROW_FEATURED_SHOWS, "Featured Shows"),
            HomeRow(ROW_POPULAR_MOVIES, "Popular Movies"), HomeRow(ROW_POPULAR_SHOWS, "Popular Shows"))
        val addons = catalogAddons.load().flatMap { addon -> addon.catalogs.map { HomeRow(CatalogAddons.rowId(addon, it), "${it.name} (${addon.name})") } }
        return builtIn + addons
    }

    private fun rowPageable(id: String) = id.isNotBlank() && id != ROW_CINEMAS

    /**
     * One page of a Home row's catalog for "See all": [page] counts pages from 1 (TMDB), [skip] counts titles
     * (Stremio catalogs). Call on a worker.
     */
    private fun rowPage(id: String, page: Int, skip: Int): List<MediaItemData> {
        val key = if (publicPreview) "" else store.get(TMDB_KEY)
        val tmdb = if (key.isNotBlank()) TmdbCatalog(this, key, tmdbRegion()) else null
        val type = id.substringAfter(':').substringBefore(':')
        return when {
            id.startsWith("latest:") -> QueuedUpCatalog(this).newest(type, "", skip)
                ?: tmdb?.discover(type, "", TmdbCatalog.SORT_NEWEST, page)
                ?: if (skip == 0) catalog.newReleases(type) else emptyList()
            id.startsWith("popular:") -> tmdb?.discover(type, "", TmdbCatalog.SORT_POPULAR, page) ?: catalog.popular(type, skip)
            id.startsWith("featured:") -> catalog.discover(type, "", CatalogApi.SORT_FEATURED, skip)
            id.startsWith("addon:") -> {
                val parts = id.split(":", limit = 4)
                val addon = catalogAddons.load().firstOrNull { it.id == parts.getOrNull(1) } ?: throw IllegalStateException("This catalog add-on was removed.")
                val chosen = addon.catalogs.firstOrNull { it.type == parts.getOrNull(2) && it.id == parts.getOrNull(3) } ?: throw IllegalStateException("This catalog was removed.")
                catalogAddons.items(addon, chosen, skip)
            }
            else -> emptyList()
        }
    }

    /** "See all" for a Home row: its whole catalog, a page at a time; "More titles" adds the next page below. */
    private fun rowGrid(shelf: CatalogApi.HomeShelf) {
        current = { rowGrid(shelf) }; val id = page(shelf.title)
        val host = column(); body.addView(host)
        val seen = mutableSetOf<String>()
        fun more(page: Int, skip: Int) {
            read(id, { rowPage(shelf.id, page, skip) }) { items ->
                val fresh = items.filter { seen.add(it.type + it.imdbId) }
                if (page == 1 && fresh.isEmpty()) { mediaGrid(shelf.items, host = host); focusAfterRender(id); return@read }
                val before = host.childCount
                if (fresh.isNotEmpty()) mediaGrid(fresh, host = host)
                if (page == 1) focusAfterRender(id)
                else (host.getChildAt(before) as? ViewGroup)?.getChildAt(0)?.takeIf { DeviceLayout.tv }?.requestFocus()
                if (fresh.isNotEmpty()) host.addView(button("More titles  ›") {}.apply {
                    tag = "row-more"
                    setOnClickListener { host.removeView(this); more(page + 1, skip + items.size) }
                }, margin(180, 42, top = 20))
            }
        }
        more(1, 0)
    }

    /** Settings → Home rows: order and switch off the rows after Top 10, and manage catalog add-ons. */
    private fun homeRowsPage(focusTag: String? = null) {
        val keepScroll = scroller.scrollY
        current = { homeRowsPage() }
        destination = "settings"; refreshNavigation()
        page("Home rows", "Continue Watching and Top 10 stay at the top. Arrange the rows that follow them.")
        val rows = homeRows.arrange(editableHomeRows()) { it.id }
        val ids = rows.map { it.id }
        val hidden = homeRows.hidden
        val list = settingsGroup("Rows after Top 10", "home")
        rows.forEachIndexed { index, row ->
            settingsDivider(list)
            val on = row.id !in hidden
            val line = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(8), dp(8), dp(8)) }
            line.addView(text(row.title, 14, if (on) ink else muted, true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END },
                LinearLayout.LayoutParams(0, -2, 1f))
            fun control(label: String, tagName: String, description: String, enabled: Boolean, action: () -> Unit) {
                line.addView(button(label) { if (enabled) action() }.apply {
                    gravity = Gravity.CENTER; tag = "$tagName:${row.id}"; contentDescription = description
                    alpha = if (enabled) 1f else 0.3f; isFocusable = enabled
                }, margin(if (label.length > 2) 64 else 46, 38).apply { leftMargin = dp(6) })
            }
            control("▲", "row-up", "Move ${row.title} up", index > 0) { homeRows.move(ids, row.id, true); homeRowsPage("row-up:${row.id}") }
            control("▼", "row-down", "Move ${row.title} down", index < rows.lastIndex) { homeRows.move(ids, row.id, false); homeRowsPage("row-down:${row.id}") }
            control(if (on) "On" else "Off", "row-on", "${row.title} · ${if (on) "On" else "Off"}", true) { homeRows.setVisible(row.id, !on); homeRowsPage("row-on:${row.id}") }
            list.addView(line, margin(-1, -2))
        }
        settingsRow(list, "Reset to default order", "", "Default order, every row on.") {
            confirm("Reset Home rows to the default order, with every row on?") { homeRows.reset(); homeRowsPage() }
        }

        val addons = settingsGroup("Catalog add-ons", "library")
        catalogAddons.load().forEach { addon ->
            settingsRow(addons, addon.name, "${addon.catalogs.size} ${if (addon.catalogs.size == 1) "row" else "rows"}", "Select to choose its rows or remove it.") { catalogAddonActions(addon) }
        }
        settingsRow(addons, "Add catalog add-on", "", "Paste a Stremio catalog add-on URL, such as your AIOMetadata manifest, then pick the catalogs to show on Home.") { addCatalogAddon() }
        body.addView(View(this), margin(-1, 24))
        if (focusTag != null) keepPlace(generation, focusTag, keepScroll) else focusAfterRender(generation)
    }

    private fun addCatalogAddon() {
        inputSetting("Add catalog add-on", "HTTPS add-on or manifest.json URL", "") { address ->
            load(generation, { address to catalogAddons.inspect(address) }) { (url, found) ->
                val (name, catalogs) = found
                if (catalogs.isEmpty()) toast("$name has no movie or TV catalogs that can be Home rows.")
                else pickAddonCatalogs(null, url, name, catalogs)
            }
        }
    }

    private fun pickAddonCatalogs(existing: CatalogAddon?, address: String, name: String, catalogs: List<AddonCatalog>) {
        val picked = BooleanArray(catalogs.size) { index ->
            existing?.catalogs?.any { it.type == catalogs[index].type && it.id == catalogs[index].id } ?: (index < 4)
        }
        AlertDialog.Builder(this).setTitle("$name · rows for Home")
            .setMultiChoiceItems(catalogs.map { it.name }.toTypedArray(), picked) { _, index, checked -> picked[index] = checked }
            .setPositiveButton("Save") { _, _ ->
                val chosen = catalogs.filterIndexed { index, _ -> picked[index] }
                if (chosen.isEmpty()) { toast("Pick at least one catalog"); return@setPositiveButton }
                try {
                    val all = catalogAddons.load()
                    if (existing == null) {
                        require(all.none { it.address == SourceProviders.endpoint(address) }) { "This add-on is already added." }
                        catalogAddons.save(all + catalogAddons.create(address, name, chosen))
                    } else {
                        catalogAddons.save(all.map { if (it.id == existing.id) it.copy(catalogs = chosen) else it })
                        existing.catalogs.filterNot { old -> chosen.any { it.type == old.type && it.id == old.id } }
                            .forEach { homeRows.forget(CatalogAddons.rowId(existing, it)) }
                    }
                    toast("${chosen.size} ${if (chosen.size == 1) "row" else "rows"} from $name added to Home")
                    homeRowsPage()
                } catch (error: Exception) { toast(uiErrorMessage(error)) }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun catalogAddonActions(addon: CatalogAddon) {
        AlertDialog.Builder(this).setTitle(addon.name)
            .setItems(arrayOf("Choose rows", "Remove")) { _, index ->
                if (index == 0) load(generation, { catalogAddons.inspect(addon.address) }) { (_, catalogs) ->
                    if (catalogs.isEmpty()) toast("${addon.name} has no movie or TV catalogs right now.")
                    else pickAddonCatalogs(addon, addon.address, addon.name, catalogs)
                }
                else confirm("Remove ${addon.name} and its rows from Home?") {
                    catalogAddons.save(catalogAddons.load().filterNot { it.id == addon.id })
                    homeRows.forget("addon:${addon.id}:"); homeRowsPage()
                }
            }.setNegativeButton("Back", null).show()
    }

    /** Top 10 shelves first; titles already in a Top 10 leave the other rows, and a row emptied that way is dropped. */
    private fun homeLineup(movies: List<MediaItemData>, shows: List<MediaItemData>, rows: List<CatalogApi.HomeShelf?>): List<CatalogApi.HomeShelf> {
        val ranked = mapOf("movie" to movies.take(10).map { it.imdbId }.toSet(), "series" to shows.take(10).map { it.imdbId }.toSet())
        // Rows after Top 10 follow the viewer's order from Settings → Home rows, without the ones they switched off.
        val others = homeRows.visible(rows.filterNotNull()) { it.id }.mapNotNull { row ->
            if (row.unavailable) return@mapNotNull row
            row.copy(items = row.items.filter { it.imdbId !in ranked[row.type].orEmpty() }).takeIf { it.items.isNotEmpty() }
        }
        return listOf(CatalogApi.HomeShelf(TOP10_MOVIES, "movie", movies), CatalogApi.HomeShelf(TOP10_SHOWS, "series", shows)) + others
    }
    private fun tmdbRegion() = store.get(TMDB_REGION).ifBlank { java.util.Locale.getDefault().country.ifBlank { "US" } }
    private fun countryName(code: String) = java.util.Locale("", code).getDisplayCountry(java.util.Locale.ENGLISH).ifBlank { code }

    private fun homeContinueWatching(id: Int) {
        val host = column().apply { tag = "wetrakr-home-continue" }
        body.addView(host, margin(-1, -2, top = 10))
        host.addView(text("Continue Watching · ${wetrakr.name}", if (flix) theme.layout.sectionTitleSizeSp else 20, ink, true), margin(-1, -2, bottom = if (flix) 6 else 8))
        val row = if (flix) horizontalRow(host) else LinearLayout(this).apply { setPadding(dp(3), dp(4), dp(10), dp(4)); clipChildren = false }
        if (!flix) {
            val scroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; clipChildren = false; isFocusable = false }
            scroll.addView(row); host.addView(scroll)
        }
        fun fill(progress: List<Pair<PlaybackIdentity, Double>>) {
            row.removeAllViews(); host.findViewWithTag<View>("wetrakr-empty")?.let(host::removeView)
            // WeTrakr is authoritative here, including progress made on other devices.
            if (progress.isEmpty()) host.addView(text("Nothing in progress on ${wetrakr.name}.", 13, muted).apply { tag = "wetrakr-empty" })
            progress.forEach { (identity, percent) -> remoteProgress[identityKey(identity)] = percent }
            // A tracker can hold hundreds of paused titles; Home shows the most recent ones.
            progress.take(CONTINUE_LIMIT).forEach { (identity, percent) ->
                val caption = listOf(if (identity.type == "series") "S${identity.season} E${identity.episode}" else "", "${percent.toInt()}%").filter { it.isNotBlank() }.joinToString(" · ")
                val item = MediaItemData(identity.imdbId, identity.type, identity.title)
                val open: () -> Unit = { continuePlayback(identity) }
                row.addView((if (resumeCards()) resumeCard(item, percent / 100, episodeLine(identity), "${percent.toInt()}% watched", open) else tile(item, percent / 100, caption, open))
                    .apply { continueOptions(this, "", identity, identity.title) }, continueSlot())
            }
        }
        // The last list is kept encrypted with the account revision, so the row shows instantly on launch.
        val revision = wetrakr.accountRevision()
        refreshContinue = {
            val tracker = wetrakr
            worker.execute {
                val fresh = try { tracker.refreshCaches(); tracker.continueWatching() } catch (_: Exception) { null }
                runOnUiThread {
                    if (fresh == null || isDestroyed || tracker.accountRevision() != revision || !row.isAttachedToWindow) return@runOnUiThread
                    TvContinueWatching.rememberRemote(this,revision,fresh)
                    if (!row.hasFocus()) fill(fresh)
                }
            }
        }
        val cached = cachedContinueWatching()
        cached?.let(::fill)
        read(id, { wetrakr.continueWatching() }) { progress ->
            TvContinueWatching.rememberRemote(this,revision,progress)
            // Refreshing never pulls the row out from under a viewer who is browsing it.
            if (progress != cached && !row.hasFocus()) fill(progress)
        }
    }

    private fun homeLikedLists(id: Int) {
        if (store.get("wetrakr.home_liked_opt_in") != "true") return
        likedLists(id, true)
    }

    private fun likedLists(id: Int, home: Boolean) {
        val host = column().apply { tag = if (home) "wetrakr-home-liked" else "wetrakr-section-liked" }
        body.addView(host, margin(-1, -2, top = 10))
        if (flix) { flixLikedLists(id, host); return }
        read(id, { wetrakr.likedLists() }) { lists ->
            if (lists.isEmpty() && !home) host.addView(text("No liked lists on ${wetrakr.name} yet.", 13, muted))
            if (lists.isNotEmpty()) {
                host.addView(text("Liked lists · ${wetrakr.name}", 20, ink, true), margin(-1, -2, bottom = 8))
                lists.forEach { (listId, title) ->
                    host.addView(button("$title  ›") {
                        navigate { val pageId = page(title, "Liked list · WeTrakr"); read(pageId, { wetrakr.likedListItems(listId) }) { mediaGrid(it) } }
                    }, margin(-1, 40, bottom = 4))
                    val row = LinearLayout(this).apply { setPadding(dp(3), dp(4), dp(10), dp(4)); clipChildren = false }
                    host.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; isFocusable = false; clipChildren = false; addView(row) })
                    read(id, { wetrakr.likedListPreview(listId) }) { items ->
                        items.forEach { item -> row.addView(card(item), margin(cardWidth(), theme.layout.cardHeightDp, right = theme.layout.cardGapDp)) }
                    }
                }
            }
        }
    }

    /**
     * Flix: every liked list becomes an ordinary shelf (title, cards, "See all" for the whole list). Each shelf is
     * its own page section so it tucks away under the feature like the others; slots keep the lists' order.
     */
    private fun flixLikedLists(id: Int, anchor: LinearLayout) {
        read(id, { wetrakr.likedLists() }) { lists ->
            var index = body.indexOfChild(anchor)
            lists.forEach { (listId, title) ->
                val slot = column().apply { tag = "wetrakr-liked:$listId" }
                body.addView(slot, ++index, margin(-1, -2))
                // A liked list its owner made private or deleted answers 401/403 while the account is fine:
                // drop that shelf instead of a page-wide "authorization expired" error.
                read(id, { try { wetrakr.likedListPreview(listId) } catch (_: Exception) { null } }) { items ->
                    if (items == null) { body.removeView(slot); return@read }
                    mediaRow(title, items, slot) {
                        navigate { val pageId = page(title, "Liked list · WeTrakr"); read(pageId, { wetrakr.likedListItems(listId) }) { mediaGrid(it) } }
                    }
                }
            }
        }
    }

    private fun hero(item: MediaItemData, showAction: Boolean = true) = hero(item, showAction, 0)
    private fun hero(item: MediaItemData, showAction: Boolean, synopsisLines: Int) {
        if (flix) { streamingHero(item, showAction, synopsisLines = synopsisLines); return }
        val layout = theme.layout
        val split = layout.hero == HeroComposition.SPLIT
        val full = layout.hero == HeroComposition.FULL_BLEED
        val height = if (!showAction) 262 else layout.heroHeightDp
        val frame = FrameLayout(this).apply { clipChildren = false; tag = "featured-hero" }
        val art = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        val address = item.background.ifBlank { item.poster }
        if (full) {
            heroBackdrop.layoutParams.height = dp(height + layout.headerHeightDp + 22) + body.paddingTop
            heroBackdrop.requestLayout()
            heroBackdrop.visibility = View.VISIBLE
            image(backdropArt, address)
        } else {
            val artFrame = FrameLayout(this).apply { background = rounded(panel, layout.cornerRadiusDp); clipToOutline = true }
            artFrame.addView(art, FrameLayout.LayoutParams(-1, -1)); image(art, address)
            if (!split) artFrame.addView(View(this).apply { background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.BLACK, withAlpha(Color.BLACK, 130), Color.TRANSPARENT)) }, FrameLayout.LayoutParams(-1, -1))
            frame.addView(artFrame, FrameLayout.LayoutParams(if (split) dp(577) else -1, -1, Gravity.END))
        }
        val copy = column()
        val copyWidth = if (split) 311 else 410
        val title = text(item.title.uppercase(), layout.titleSizeSp, ink).apply {
            typeface = Typeface.create(layout.titleTypeface, Typeface.NORMAL)
            letterSpacing = layout.titleLetterSpacing; maxLines = 2; ellipsize = TextUtils.TruncateAt.END
            val measured = paint.measureText(text.toString()) + (text.length - 1).coerceAtLeast(0) * letterSpacing * textSize
            val ratio = (dp(copyWidth).toFloat() / measured.coerceAtLeast(1f)).coerceIn(0.58f, 1f)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, textSize * ratio)
        }
        if (split && showAction) copy.addView(text(greeting(), 16, ink), margin(-1, -2, bottom = 6))
        copy.addView(title, margin(-1, -2))
        copy.addView(text(listOf(item.year, item.genres.firstOrNull().orEmpty(), item.runtime).filter(String::isNotBlank).joinToString("  ·  "), 13, muted), margin(-1, -2, top = 5))
        val description = text(item.description, 13, muted).apply {
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END; setLineSpacing(dp(2).toFloat(), 1f)
        }
        if (item.description.isNotBlank()) copy.addView(description, margin(if (split) -1 else 345, -2, top = 10))
        val left = 0
        val copyTop = if (!showAction) 15 else 8
        val actionBottom = if (split) 10 else 16
        val availableCopy = dp(if (showAction) height - actionBottom - 38 - copyTop - 8 else height - copyTop - 10)
        repeat(10) {
            copy.measure(View.MeasureSpec.makeMeasureSpec(dp(copyWidth), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            if (copy.measuredHeight > availableCopy) {
                if (description.maxLines > 1) description.maxLines--
                else title.setTextSize(TypedValue.COMPLEX_UNIT_PX, (title.textSize * 0.9f).coerceAtLeast(dp(18).toFloat()))
            }
        }
        frame.addView(copy, FrameLayout.LayoutParams(dp(copyWidth), -2).apply { leftMargin = dp(left); topMargin = dp(copyTop) })
        if (showAction) {
            val actions = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            val saved = if (publicPreview) null else history.continueWatching().firstOrNull { it.identity?.imdbId == item.imdbId }
            actions.addView(primaryButton(if (saved != null) "▶  Play" else "▶  Explore") {
                if (saved?.identity != null) navigate { showSources(saved.identity) } else navigate { details(item) }
            }, margin(160, 38, right = 17))
            if (!split) actions.addView(button("＋  Add to Library") {
                val tracker = weTrakrClient()
                if (tracker.connected()) load(generation, { tracker.setWatchlist(PlaybackIdentity(item.imdbId, item.type, item.title), true) }) { toast("Added to your ${tracker.name} watchlist") }
                else { toast("Connect a tracker to save titles to your library"); navigate { weTrakrPage() } }
            }.apply { background = rounded(withAlpha(panel, 90), 24) }, margin(159, 38))
            frame.addView(actions, FrameLayout.LayoutParams(dp(copyWidth), dp(38), Gravity.BOTTOM or Gravity.START).apply {
                leftMargin = dp(left); bottomMargin = dp(actionBottom)
            })
        }
        body.addView(frame, margin(-1, height, bottom = if (split) 10 else 0))
    }

    private var homeMenuFocus = false
    private var menuHoldUntil = 0L

    /** Home with focus on its content (the feature's Play, as at launch), the menu closed. */
    private fun openHomeContent() {
        if (retainedHome == null) retainHome()
        homeMenuFocus = false; destination = "home"; backStack.clear(); current = { home() }
        // Back pressed from the open menu: close it, so focus lands on the rows instead of staying in the menu.
        if (navigationViews.values.any { it.hasFocus() }) { setRailExpanded(false, animate = false); currentFocus?.clearFocus() }
        wantsContentFocus = true
        home(); refreshNavigation()
    }

    /** Android refuses focus to zero-width views, so the hidden menu opens before taking focus. */
    private fun openMenu(): Boolean {
        heroTrailer?.stop()
        val view = rail ?: return false
        // While a video plays, the menu opens on Now playing so it is one press away.
        val target = navigationViews["nowplaying"] ?: navigationViews[destination] ?: navigationViews["home"] ?: return false
        setRailExpanded(true, animate = false)
        fun attempt() { if (!target.requestFocus()) setRailExpanded(false) }
        // Focus only once the widened menu has been laid out; until then its items have no size.
        if (!view.isLayoutRequested && view.width > 0) view.post(::attempt)
        else view.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
            override fun onLayoutChange(v: View, l: Int, t: Int, r: Int, b: Int, ol: Int, ot: Int, or: Int, ob: Int) {
                v.removeOnLayoutChangeListener(this); v.post(::attempt)
            }
        })
        return true
    }

    private var bottomAligner: View.OnLayoutChangeListener? = null

    /**
     * Details open with their parts rising into place one after another: title, buttons, season tabs, episodes.
     * Each part moves on its own (never the whole page), briefly, so it stays smooth on TV hardware.
     */
    private fun riseIn() {
        val lift = dp(24).toFloat()
        for (index in 0 until minOf(body.childCount, 6)) {
            val child = body.getChildAt(index)
            child.animate().cancel(); child.alpha = 0f; child.translationY = lift
            child.animate().alpha(1f).translationY(0f).setStartDelay(index * 40L).setDuration(260).setInterpolator(EASE_OUT).start()
        }
    }
    /**
     * Movie details sit at the foot of the screen so the backdrop stays open above them. Re-applied on
     * layout, because the title logo arriving changes the content height.
     */
    private fun alignToBottom(id: Int, reveal: () -> Unit = {}) {
        // The details stay hidden until they sit at the foot of the screen, so they never drop into place.
        var placed = false
        fun reveal() {
            if (placed || id != generation) return
            placed = true; body.alpha = 1f; reveal()
        }
        body.animate().cancel(); body.alpha = 0f; body.translationY = 0f
        val listener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (id != generation || scroller.height == 0) return@OnLayoutChangeListener
            var content = 0
            for (index in 0 until body.childCount) {
                val child = body.getChildAt(index); val params = child.layoutParams as ViewGroup.MarginLayoutParams
                if (child.visibility != View.GONE) content += child.height + params.topMargin + params.bottomMargin
            }
            val top = (scroller.height - content - body.paddingBottom - dp(10)).coerceAtLeast(dp(20))
            if (kotlin.math.abs(top - body.paddingTop) > 2) body.post {
                if (id != generation) return@post
                body.setPadding(body.paddingLeft, top, body.paddingRight, body.paddingBottom)
                if (!placed) body.post(::reveal)
            } else body.post(::reveal)
        }
        bottomAligner?.let(body::removeOnLayoutChangeListener)
        bottomAligner = listener; body.addOnLayoutChangeListener(listener); body.requestLayout()
        // Never leave the page hidden if layout settles without another pass.
        body.postDelayed(::reveal, 1_200)
    }

    private fun streamingHero(item: MediaItemData, showAction: Boolean, rotation: List<MediaItemData> = emptyList(), compact: Boolean = false, synopsisLines: Int = 0) {
        val layout = theme.layout
        val height = if (!showAction) 250 else if (compact) COMPACT_HERO_HEIGHT else layout.heroHeightDp
        val frame = (if (showAction && DeviceLayout.tv) LayoutBoundaryFrame(this) else FrameLayout(this)).apply { clipChildren = false; tag = "featured-hero"; setTag(R.id.pov_feature_item, item) }
        placeBackdrop(home = showAction)
        heroBackdrop.visibility = View.VISIBLE
        image(backdropArt, backdropFor(item), feature = true)
        // Shadowed display text is rasterized into one layer, redrawn only when the feature changes.
        val copy = column().apply { tag = "feature-copy"; setLayerType(View.LAYER_TYPE_HARDWARE, null) }
        // The phone sizes the logo to its own shape, so a wide logo leaves no empty band beneath it.
        if (DeviceLayout.phone) copy.addView(ImageView(this).apply {
            adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_START; visibility = View.GONE; maxWidth = dp(240); maxHeight = dp(64)
        }, margin(-2, -2))
        else copy.addView(FixedSizeImageView(this).apply { scaleType = ImageView.ScaleType.FIT_START; visibility = View.GONE }, margin(350, if (compact) 72 else 88))
        copy.addView(text("", layout.titleSizeSp, ink, true).apply {
            tag = "feature-title"; typeface = Typeface.create(layout.titleTypeface, Typeface.BOLD); maxLines = 2; ellipsize = TextUtils.TruncateAt.END
            setShadowLayer(dp(8).toFloat(), 0f, dp(1).toFloat(), 0xB0000000.toInt())
        }, margin(-1, -2))
        copy.addView(text("", 12, Color.WHITE, true).apply { tag = "feature-meta" }, margin(-1, -2, top = 10))
        copy.addView(text("", 13, 0xFFE6E6E6.toInt()).apply {
            tag = "feature-description"; setLineSpacing(dp(2).toFloat(), 1f)
            if (showAction) { maxLines = if (compact && DeviceLayout.tv) 2 else 3; ellipsize = TextUtils.TruncateAt.END }
            else if (synopsisLines > 0) { maxLines = synopsisLines; ellipsize = TextUtils.TruncateAt.END }
            setShadowLayer(dp(4).toFloat(), 0f, 0f, 0xB0000000.toInt())
        }, margin(-1, -2, top = 8))
        // A shortened synopsis keeps the rest one press away.
        if (!showAction && synopsisLines > 0 && item.description.length > SYNOPSIS_MORE_CHARS) copy.addView(button("More") {
            // Whatever the header shows now: the show's synopsis, or the focused episode's.
            synopsisDialog(item.title, copy.findViewWithTag<TextView>("feature-description")?.text?.toString()?.ifBlank { null } ?: item.description)
        }.apply { gravity = Gravity.CENTER; tag = TvFocusController.SKIP_FIRST }, margin(90, 34, top = 10))
        if (DeviceLayout.phone) frame.addView(copy, FrameLayout.LayoutParams(if (DeviceLayout.wide) dp(DeviceLayout.PHONE_SHORT_SIDE) else -1, -2).apply { topMargin = dp(if (DeviceLayout.wide) PHONE_WIDE_COPY_TOP else PHONE_COPY_TOP) })
        else frame.addView(copy, FrameLayout.LayoutParams(dp(if (showAction) 420 else 560), -2).apply { topMargin = dp(if (!showAction) 12 else if (compact) 24 else 30) })
        if (showAction) {
            val actions = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; tag = "feature-actions"; clipChildren = false }
            // Actions follow whichever title the rotating feature currently shows.
            fun shown() = frame.getTag(R.id.pov_feature_item) as? MediaItemData ?: item
            actions.addView(primaryButton(playLabel(item)) {
                val current = shown()
                val saved = savedFor(current)
                when {
                    // Only Continue Watching reuses the saved link; Play always finds fresh links (and still resumes).
                    saved?.identity != null -> navigate { showSources(saved.identity) }
                    current.type == "movie" -> navigate { showSources(PlaybackIdentity(current.imdbId, current.type, current.title)) }
                    else -> navigate { details(current) }
                }
            }, margin(if (DeviceLayout.phone) 108 else 122, 38, right = if (DeviceLayout.phone) 8 else 12))
            actions.addView(button("ⓘ  More Info") { val current = shown(); navigate { details(current) } }.apply { gravity = Gravity.CENTER },
                margin(if (DeviceLayout.phone) 124 else 136, 38, right = if (DeviceLayout.phone) 8 else 12))
            actions.addView(button("＋  My List") {
                val item = shown()
                val tracker = weTrakrClient()
                if (tracker.connected()) load(generation, { tracker.setWatchlist(PlaybackIdentity(item.imdbId, item.type, item.title), true) }) { toast("Added to your ${tracker.name} watchlist") }
                else { toast("Connect a tracker to save titles to your library"); navigate { weTrakrPage() } }
            }.apply { gravity = Gravity.CENTER }, margin(if (DeviceLayout.phone) 108 else 122, 38))
            if (DeviceLayout.phone) copy.addView(actions, margin(-1, 38, top = 14))
            else frame.addView(actions, FrameLayout.LayoutParams(-2, dp(38), Gravity.BOTTOM or Gravity.START).apply { bottomMargin = dp(26) })
        }
        // Details pages grow to fit the complete synopsis, as does the phone's Home feature.
        body.addView(frame, margin(-1, if (showAction && DeviceLayout.tv) height else -2).apply { if (!showAction || DeviceLayout.phone) bottomMargin = dp(14) })
        renderFeature(frame, item)
        if (!showAction) {
            val meta = frame.findViewWithTag<TextView>("feature-meta")
            meta.visibility = View.GONE
            copy.addView(metaChips(item), copy.indexOfChild(meta), margin(-1, -2, top = 12))
        }
        if (showAction && rotation.size > 1) rotateFeature(frame, rotation)
        frame.post { if (body.findViewWithTag<View>("featured-hero") === frame) refreshHeroTrailer() }

    }

    private fun savedFor(item: MediaItemData) = if (publicPreview) null else history.continueWatching().firstOrNull { it.identity?.imdbId == item.imdbId }
    /** Outside Continue Watching the action is always Play: a fresh link search that still resumes the saved position. */
    private fun playLabel(@Suppress("UNUSED_PARAMETER") item: MediaItemData) = "▶  Play"

    /** Cycles the billboard through the Top 10 titles every five seconds while no shelf is in use. */
    private fun rotateFeature(frame: FrameLayout, rotation: List<MediaItemData>) {
        heroRotation?.let(rotationHandler::removeCallbacks)
        var index = rotation.indexOfFirst { it.imdbId == (frame.getTag(R.id.pov_feature_item) as? MediaItemData)?.imdbId }.coerceAtLeast(0)
        val step = object : Runnable {
            override fun run() {
                if (heroRotation !== this || isDestroyed) return
                rotationHandler.postDelayed(this, FEATURE_INTERVAL_MS)
                val focus = currentFocus
                val inShelves = focus != null && within(focus, body) && !within(focus, frame)
                if (!resumed || !flix || featureHero() !== frame || inShelves) return
                index = (index + 1) % rotation.size
                val next = rotation[index]
                homeTrailerFocus = null
                rotation[(index + 1) % rotation.size].let { prefetch(backdropFor(it), backdrop = true); prefetch(logoFor(it)) }
                frame.setTag(R.id.pov_feature_item, next)
                (frame.findViewWithTag<LinearLayout>("feature-actions")?.getChildAt(0) as? TextView)?.text = playLabel(next)
                val copy = frame.findViewWithTag<View>("feature-copy")
                copy.animate().alpha(0f).setDuration(180).withEndAction {
                    showFeature(next); copy.animate().alpha(1f).setDuration(260).start()
                }.start()
            }
        }
        heroRotation = step
        rotation.getOrNull(1)?.let { prefetch(backdropFor(it), backdrop = true); prefetch(logoFor(it)) }
        rotationHandler.postDelayed(step, FEATURE_INTERVAL_MS)
    }

    /** Details badges: the IMDb score (live from IMDb when it answers), year, runtime and type chips, then genre pills. */
    private fun metaChips(item: MediaItemData): View {
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        fun chip(label: String, fill: Int, ink: Int, outline: Int = Color.TRANSPARENT) = text(label, 11, ink, true).apply {
            gravity = Gravity.CENTER; setPadding(dp(9), dp(4), dp(9), dp(4)); background = rounded(fill, 4, outline)
        }
        val imdb = imdbChip()
        row.addView(imdb, margin(-2, -2, right = 8))
        item.rating.takeIf { it.isNotBlank() && it != "0" }?.let { setImdbBase(imdb, it, "") }
        // A show's live score arrives with its season's episode scores (seasonPicker).
        if (item.type == "movie") loadTitleImdb(item)
        listOf(item.year, item.runtime, if (item.type == "series") "Series" else "Movie").filter(String::isNotBlank).forEach {
            row.addView(chip(it, Color.TRANSPARENT, ink, withAlpha(Color.WHITE, 90)), margin(-2, -2, right = 8))
        }
        item.genres.take(if (DeviceLayout.phone) 1 else 3).forEach { row.addView(chip(it, withAlpha(Color.WHITE, 30), ink), margin(-2, -2, right = 8)) }
        return row
    }

    /** Where Play should start on a show: the episode in progress, else the first released episode of the chosen season. */
    private fun playTarget(result: MediaItemData): Pair<PlaybackIdentity, Boolean>? {
        if (result.type != "series") return null
        savedFor(result)?.identity?.takeIf { it.season > 0 || it.episode > 0 }?.let { return it to true }
        val season = EpisodePresentation.selectedSeason(result.episodes, selectedSeasons[result.imdbId]) ?: return null
        val first = result.episodes.filter { it.season == season }.sortedBy { it.episode }.firstOrNull { !EpisodePresentation.future(it.released) } ?: return null
        return PlaybackIdentity(result.imdbId, "series", result.title, first.season, first.episode) to false
    }

    /**
     * Home's backdrop sits in the top-right 70% of the screen, its baked-in shading fading into the page,
     * leaving the left and lower area darker for the copy and rows; details pages keep it full-screen.
     */
    private fun placeBackdrop(home: Boolean) {
        if (!flix) return
        val params = heroBackdrop.layoutParams as FrameLayout.LayoutParams
        if (DeviceLayout.phone) {
            // The link list's art fills the phone; other pages use the banner.
            val height = if (sourcesPage) -1 else dp(DeviceLayout.PHONE_SHORT_SIDE) * 9 / 16
            if (params.width != -1 || params.height != height) {
                params.width = -1; params.height = height; params.gravity = Gravity.TOP; heroBackdrop.layoutParams = params
            }
            return
        }
        val width = if (home) (resources.displayMetrics.widthPixels * HOME_BACKDROP_SCALE).toInt() else -1
        val height = if (home) width * 9 / 16 else -1
        val growsFromCorner = !home && params.width > 0
        // Opened from a page without a backdrop (Movies, Shows, search, lists): fade in while settling from a
        // slight zoom, so every details page arrives the way it does from Home.
        if (!home && !growsFromCorner && heroBackdrop.visibility != View.VISIBLE) {
            heroBackdrop.animate().cancel()
            heroBackdrop.pivotX = resources.displayMetrics.widthPixels / 2f; heroBackdrop.pivotY = 0f
            heroBackdrop.alpha = 0f; heroBackdrop.scaleX = 1.06f; heroBackdrop.scaleY = 1.06f
            heroBackdrop.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(420).setInterpolator(EASE_OUT).start()
        }
        if (params.width != width || params.height != height) {
            val wasCorner = params.width > 0
            params.width = width; params.height = height; params.gravity = Gravity.TOP or Gravity.END
            heroBackdrop.layoutParams = params
            // Grow from Home's corner to full screen (or settle back), scaling about the top-right corner
            // where the two layouts meet, instead of snapping between sizes.
            if (wasCorner != home) {
                val screen = resources.displayMetrics.widthPixels.toFloat()
                heroBackdrop.animate().cancel()
                heroBackdrop.pivotX = if (home) width.toFloat() else screen; heroBackdrop.pivotY = 0f
                val from = if (home) 1f / HOME_BACKDROP_SCALE else HOME_BACKDROP_SCALE
                heroBackdrop.scaleX = from; heroBackdrop.scaleY = from
                heroBackdrop.animate().scaleX(1f).scaleY(1f).setDuration(380).setInterpolator(EASE_OUT).start()
            }
        }
    }

    private fun featureHero(): FrameLayout? = if (isHome) body.findViewWithTag("featured-hero") else null
    // TMDB backdrops use the 780 px file the cards already load, so opening a title reuses the card's image.
    private fun backdropFor(item: MediaItemData) = item.background.replace("/t/p/w1280/", "/t/p/w780/").ifBlank {
        if (item.imdbId.matches(Regex("tt\\d+"))) "https://images.metahub.space/background/medium/${item.imdbId}/img" else item.poster
    }
    private fun logoFor(item: MediaItemData) =
        if (item.imdbId.matches(Regex("tt\\d+"))) "https://images.metahub.space/logo/medium/${item.imdbId}/img" else ""

    /** Writes a title into the pinned feature copy, preferring its artwork logo once it arrives. */
    private fun renderFeature(frame: View, item: MediaItemData) {
        frame.setTag(R.id.pov_feature_item, item)
        val copy = frame.findViewWithTag<LinearLayout>("feature-copy")
        // The image loader owns the logo's view tag, so the logo is found by position.
        val logo = copy.getChildAt(0) as ImageView
        val title = frame.findViewWithTag<TextView>("feature-title")
        title.text = item.title
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(when {
            item.title.length > 30 -> if (DeviceLayout.phone) 21 else 28
            item.title.length > 16 -> if (DeviceLayout.phone) 25 else 36
            else -> theme.layout.titleSizeSp }).toFloat())
        logo.setTag(R.id.pov_feature_item, item)
        // The logo's space is held empty while it loads; plain text appears only if there is no logo,
        // so the title never flashes as text and then swaps to artwork.
        fun useText() { if (logo.getTag(R.id.pov_feature_item) == item && logo.visibility != View.VISIBLE) { logo.visibility = View.GONE; title.visibility = View.VISIBLE } }
        logo.visibility = View.INVISIBLE; title.visibility = View.GONE
        logo.removeCallbacks(logo.getTag(R.id.pov_logo_fallback) as? Runnable)
        val fallback = Runnable(::useText).also { logo.setTag(R.id.pov_logo_fallback, it) }
        image(logo, logoFor(item), feature = true) { loaded ->
            if (logo.getTag(R.id.pov_feature_item) != item) return@image
            logo.removeCallbacks(fallback)
            if (loaded) { logo.visibility = View.VISIBLE; title.visibility = View.GONE } else useText()
        }
        if (logo.visibility != View.VISIBLE && title.visibility != View.VISIBLE) logo.postDelayed(fallback, 1_500)
        val rating = item.rating.takeIf { it.isNotBlank() && it != "0" }?.let { "★ $it" }.orEmpty()
        frame.findViewWithTag<TextView>("feature-meta").text = listOf(rating, item.year, item.genres.take(2).joinToString(" · "), item.runtime)
            .filter(String::isNotBlank).joinToString("   ")
        frame.findViewWithTag<TextView>("feature-description").apply { text = item.description; visibility = if (item.description.isBlank()) View.GONE else View.VISIBLE }
        // A longer description may now reach the shelf above the focused one.
        if (isHome && flixScrollTarget > 0) frame.post { if (isHome && flixScrollTarget > 0) fadeShelvesAbove(flixScrollTarget + featureCopyBottom()) }
    }

    private var warmedUp = false
    /**
     * After Home appears, while the viewer looks: prepare the hero backdrops of the first posters (download and shade),
     * and run the focus animation code once off-screen, so the first move isn't the one that pays for all of it.
     */
    private fun warmUpHome(items: List<MediaItemData>) {
        window.decorView.postDelayed({
            if (isDestroyed || !isHome) return@postDelayed
            if (!warmedUp) {
                warmedUp = true
                runCatching {
                    val probe = FrameLayout(this)
                    FadeDrawable.foreground(probe, FocusRingDrawable(Color.WHITE, dp(4).toFloat(), true, dp(1).toFloat()))
                    SlidingFocusPill(LinearLayout(this), fadeText = false, ring = 2f).focusChanged(probe, false)
                    android.graphics.drawable.TransitionDrawable(arrayOf(android.graphics.drawable.ColorDrawable(0), android.graphics.drawable.ColorDrawable(0))).startTransition(1)
                    android.animation.ValueAnimator.ofFloat(0f, 1f).setDuration(16).start()
                }
            }
            items.take(WARM_BACKDROPS).forEach { item ->
                val address = backdropFor(item)
                val key = "backdrop-corner:$address"
                if (address.startsWith("https://") && bitmapCache.get(key) == null) featureImages.execute {
                    runCatching { fetchBitmap(address, BACKDROP_WIDTH)?.let { shadedBackdrop(it, true) }?.let { bitmapCache.put(key, it) } }
                }
            }
        }, 1_500)
    }

    private fun scheduleFeature(item: MediaItemData) {
        prefetchDetails(item)
        if (!flix || !isHome) return
        heroTrailer?.stop()
        homeTrailerFocus = currentFocus
        featureHandler.removeCallbacksAndMessages(null)
        featureHandler.postDelayed({ showFeature(item) }, 280)
    }

    private fun hideDetailsTrailerChrome() {
        if (isHome || !::shellRoot.isInitialized) return
        trailerFadeGeneration = generation
        trailerBackdropY = heroBackdrop.translationY
        for (i in 0 until shellRoot.childCount) {
            val view = shellRoot.getChildAt(i)
            if (view === heroBackdrop) continue
            fadedTrailerViews[view] = view.alpha
            view.animate().cancel(); view.animate().alpha(0f).setDuration(350).start()
        }
        heroBackdrop.animate().cancel(); heroBackdrop.animate().translationY(0f).setDuration(350).start()
        heroTrailer?.setUnobstructed(true)
    }
    private fun showDetailsTrailerChrome() {
        fadedTrailerViews.forEach { (view, alpha) ->
            view.animate().cancel(); view.animate().alpha(alpha).setDuration(180).start()
        }
        fadedTrailerViews.clear()
        if (::heroBackdrop.isInitialized && trailerFadeGeneration == generation) {
            heroBackdrop.animate().cancel(); heroBackdrop.animate().translationY(trailerBackdropY).setDuration(180).start()
        }
        heroTrailer?.setUnobstructed(false)
        trailerFadeGeneration = -1
    }

    private fun trailerHero(): FrameLayout? = if (flix && !sourcesPage && ::body.isInitialized) body.findViewWithTag("featured-hero") else null

    /** Native video shares the backdrop layer beneath Home or details copy and controls. */
    private fun trailerArea(): android.graphics.Rect? {
        if (!flix || !DeviceLayout.tv || !::heroBackdrop.isInitialized) return null
        if (trailerHero() == null || !heroBackdrop.isShown || heroBackdrop.width == 0 || heroBackdrop.height == 0) return null
        if (!heroBackdrop.getGlobalVisibleRect(android.graphics.Rect())) return null
        if (isHome) {
            // One wide moving hero, weighted to the right and kept near the visible shelf line.
            val height = minOf(dp(FLIX_ROW_LINE + 32), heroBackdrop.height)
            return android.graphics.Rect(0, 0, heroBackdrop.width, height)
        }
        return android.graphics.Rect(0, 0, heroBackdrop.width, heroBackdrop.height)
    }
    private fun trailerPermitted() = heroTrailersEnabled() && (!isHome || (homeTrailerFocus != null && currentFocus === homeTrailerFocus)) && resumed && hasWindowFocus() && trailerHero() != null && flix &&
        !InAppPlaybackSession.hasActive() && !isDestroyed && splash == null && navigationViews.values.none { it.hasFocus() }

    private fun refreshHeroTrailer() {
        if (!trailerPermitted()) { heroTrailer?.stop(); return }
        val item = (if (isHome) featureShown else null) ?: trailerHero()?.getTag(R.id.pov_feature_item) as? MediaItemData ?: return
        scheduleHeroTrailer(item)
    }
    private fun onHeroTrailerPlaying(playing: Boolean) {
        detailsTrailerChrome.playbackChanged(playing)
        if (!::backdropArt.isInitialized) return
        backdropArt.animate().cancel()
        if (isHome && playing) backdropArt.animate().alpha(0f).setDuration(450).start()
        else backdropArt.alpha = 1f
    }
    private fun scheduleHeroTrailer(item: MediaItemData) {
        if (!trailerPermitted()) { heroTrailer?.stop(); return }
        if (heroTrailer == null) heroTrailer = HeroTrailerPreview(this, heroBackdrop, worker, trailerCatalog::videos, ::trailerArea, ::trailerPermitted, bg, ::heroTrailerSoundEnabled, ::onHeroTrailerPlaying, ::heroTrailerFillEnabled, ::heroTrailerHighQuality)
        heroTrailer?.select(item)
    }

    private fun showFeature(item: MediaItemData) {
        scheduleHeroTrailer(item)
        if (item == featureShown || isDestroyed) return
        featureShown = item
        featureHero()?.let { renderFeature(it, item) }
        val address = backdropFor(item)
        if (address.isBlank() || backdropArt.tag == address) return
        // The previous artwork stays until the next arrives; image() then cross-fades old into new.
        fun reveal(loaded: Boolean) {
            if (!loaded || featureShown != item) return
            backdropArt.animate().cancel()
            backdropArt.alpha = if (isHome && heroTrailer?.status == "playing") 0f else 1f
        }
        image(backdropArt, address, keep = true, feature = true) { loaded ->
            if (loaded) reveal(true)
            else if (featureShown == item && item.poster.startsWith("https://")) image(backdropArt, item.poster, keep = true, feature = true, loaded = ::reveal)
        }
    }

    /** Flix home: focused shelves rise to a fixed line beneath the pinned feature copy. */
    private fun placeFlixRow(view: View): Boolean {
        if (!flix || !isHome) return false
        val hero = featureHero() ?: return false
        val actions = hero.findViewWithTag<View>("feature-actions")
        if (within(view, hero)) {
            actions?.animate()?.alpha(1f)?.setDuration(160)?.start()
            flixScrollTarget = -1
            fadeShelvesAbove(Int.MIN_VALUE)
            scrollHomeTo(0)
            featureHandler.removeCallbacksAndMessages(null)
            (hero.getTag(R.id.pov_feature_item) as? MediaItemData)?.let(::showFeature)
            return true
        }
        var strip: View? = view
        while (strip != null && strip !is HorizontalScrollView) strip = strip.parent as? View
        if (strip !is HorizontalScrollView) return false
        val row = strip.getChildAt(0) as? ViewGroup ?: return false
        var card = view
        while (card.parent !== row) card = card.parent as? View ?: return false
        // Rows read left to right from the focused title.
        strip.smoothScrollTo((card.left - row.paddingLeft).coerceAtLeast(0), 0)
        val parent = strip.parent as ViewGroup
        val header = parent.getChildAt(parent.indexOfChild(strip) - 1)?.takeIf { it !is HorizontalScrollView }
        val top = topInBody(header ?: strip)
        actions?.animate()?.alpha(0f)?.setDuration(160)?.start()
        // The last shelves cannot rise all the way to the line, so hide only what would really sit under the feature copy.
        val target = (top - dp(FLIX_ROW_LINE)).coerceIn(0, (body.height - scroller.height).coerceAtLeast(0))
        flixScrollTarget = target
        fadeShelvesAbove(target + featureCopyBottom())
        scrollHomeTo(target)
        return true
    }

    private var homeScroll: ValueAnimator? = null

    /**
     * Home's shelves move with one short eased scroll per step. The platform's smooth scroll (about 250 ms, and an
     * instant jump when presses come faster) made held Up/Down feel slow and uneven.
     */
    private fun scrollHomeTo(target: Int) {
        homeScroll?.cancel()
        val from = scroller.scrollY
        if (from == target) return
        homeScroll = ValueAnimator.ofInt(from, target).apply {
            duration = HOME_ROW_SCROLL_MS; interpolator = EASE_OUT
            addUpdateListener { scroller.scrollTo(0, it.animatedValue as Int) }
            start()
        }
    }

    private var flixScrollTarget = -1

    /** Where the pinned feature text ends on screen: a short title leaves room for the shelf above the last one. */
    private fun featureCopyBottom(): Int {
        val copy = featureHero()?.findViewWithTag<View>("feature-copy") ?: return dp(FLIX_ROW_LINE)
        val here = IntArray(2); val origin = IntArray(2)
        copy.getLocationOnScreen(here); scroller.getLocationOnScreen(origin)
        return (here[1] - origin[1] + copy.height + dp(8)).coerceAtMost(dp(FLIX_ROW_LINE))
    }

    /** Hides every shelf that starts above [cutoff] (body coordinates), i.e. that would overlap the pinned feature copy. */
    private fun fadeShelvesAbove(cutoff: Int) {
        for (index in 0 until body.childCount) {
            val child = body.getChildAt(index)
            if (child.tag == "featured-hero") continue
            val target = if (child.top < cutoff) 0f else 1f
            // A shelf leaving upward hides at once: it already touches the feature artwork, so any fade showed it sliding over it.
            // A returning one fades in gently, in clear space.
            if (child.alpha == target) continue
            child.animate().cancel()
            if (target == 0f) child.alpha = 0f else child.animate().alpha(1f).setDuration(160).withLayer().start()
        }
    }
    private fun within(view: View, parent: View): Boolean {
        var node: View? = view
        while (node != null) { if (node === parent) return true; node = node.parent as? View }
        return false
    }
    private fun topInBody(view: View): Int {
        var y = 0; var node: View? = view
        while (node != null && node !== body) { y += node.top; node = node.parent as? View }
        return y
    }

    private fun railBackground(expanded: Boolean) = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
        if (expanded) intArrayOf(Color.BLACK, withAlpha(Color.BLACK, 235), withAlpha(Color.BLACK, 0)) else intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT))

    /**
     * The hidden menu slides and fades over the content as one piece (GPU only). It used to animate its width, which
     * re-measured the whole screen every frame and dropped its labels and shade at once, so closing looked choppy.
     * Its width changes only while it is invisible: full width before it slides in, none after it has faded out.
     */
    private fun setRailExpanded(expanded: Boolean, animate: Boolean = true) {
        val view = rail ?: return
        if (railExpanded == expanded) return
        railExpanded = expanded
        railAnimator?.cancel(); railAnimator = null
        view.animate().cancel()
        val full = dp(270)
        val offset = -dp(40).toFloat()
        fun showContent() {
            railLabels.forEach { it.visibility = View.VISIBLE }
            view.background = railBackground(true)
            if (view.layoutParams.width != full) view.layoutParams = view.layoutParams.apply { width = full }
        }
        fun settleCollapsed() {
            railLabels.forEach { it.visibility = View.GONE }
            view.background = railBackground(false)
            view.layoutParams = view.layoutParams.apply { width = 0 }
            view.translationX = 0f; view.alpha = 1f
        }
        if (!animate) { if (expanded) { showContent(); view.translationX = 0f; view.alpha = 1f } else settleCollapsed(); return }
        if (expanded) {
            // From fully hidden, start a little to the left and transparent; a reopen mid-fade continues from where it is.
            if (view.layoutParams.width != full) { view.translationX = offset; view.alpha = 0f }
            showContent()
            view.animate().translationX(0f).alpha(1f).setDuration(180)
                .setInterpolator(android.view.animation.DecelerateInterpolator()).withLayer().start()
        } else {
            view.animate().translationX(offset).alpha(0f).setDuration(150)
                .setInterpolator(android.view.animation.AccelerateInterpolator()).withLayer()
                .withEndAction { if (!railExpanded) settleCollapsed() }.start()
        }
    }

    private fun rankedRow(title: String, items: List<MediaItemData>) {
        if (items.isEmpty()) return
        body.addView(text(title, theme.layout.sectionTitleSizeSp, ink, true), margin(-1, -2, top = 14, bottom = 6))
        val row = horizontalRow()
        val (wide, narrow, tall) = if (DeviceLayout.phone) Triple(150, 128, 108) else Triple(200, 170, 144)
        items.take(10).forEachIndexed { index, item -> row.addView(rankTile(item, index + 1), margin(if (index == 9) wide else narrow, tall, right = 4)) }
    }

    private fun rankTile(item: MediaItemData, rank: Int): View {
        val frame = FrameLayout(this).apply {
            isFocusable = true; isFocusableInTouchMode = DeviceLayout.tv; isClickable = true; clipChildren = false
            tag = "top10:${item.type}:${item.imdbId}"; contentDescription = "Number $rank, ${item.title}"
        }
        val posterWidth = dp(if (DeviceLayout.phone) 72 else 96)
        frame.addView(RankNumberView(this, rank.toString(), bg, 0xFF8C8C8C.toInt()).apply { posterInset = posterWidth - dp(16).toFloat() }, FrameLayout.LayoutParams(-1, -1))
        val poster = FrameLayout(this).apply { background = rounded(panel, 4); clipToOutline = true }
        val art = FixedSizeImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        poster.addView(art, FrameLayout.LayoutParams(-1, -1))
        image(art, thumbnail(item.poster.ifBlank { if (item.imdbId.matches(Regex("tt\\d+"))) "https://images.metahub.space/poster/medium/${item.imdbId}/img" else "" }, wide = false))
        frame.addView(poster, FrameLayout.LayoutParams(posterWidth, -1, Gravity.END))
        watchedBadge(if (item.type == "movie") "movie" else "series", item.imdbId)?.let {
            poster.addView(it, FrameLayout.LayoutParams(dp(18), dp(18), Gravity.TOP or Gravity.END).apply { topMargin = dp(5); rightMargin = dp(5) })
        }
        poster.tag = "rank-poster"
        frame.setOnFocusChangeListener { view, focused ->
            // One outline glides along the Top 10 row, around the poster only (not the rank number).
            val glide = if (flix) posterGlide(view, radius = 4, scale = 1.07f) { card -> card.findViewWithTag("rank-poster") } else null
            if (glide != null) glide.focusChanged(view, focused)
            else poster.foreground = if (focused) FocusRingDrawable(Color.WHITE, dp(4).toFloat(), true, dp(1).toFloat()) else null
            view.animate().scaleX(if (focused) 1.07f else 1f).scaleY(if (focused) 1.07f else 1f).setDuration(140).start()
            if (focused) scheduleFeature(item)
        }
        frame.setOnClickListener { navigate { details(item) } }
        frame.setOnLongClickListener { titleMenu(item) { navigate { details(item) } }; true }
        return frame.pressable(4)
    }

    private fun greeting(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
        in 5..11 -> "Good morning."; in 12..17 -> "Good afternoon."; else -> "Good evening."
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent); openLauncherResume(intent); openAddonLink(intent)
    }
    /**
     * A stremio:// install link, or an https add-on link shared to POV: "Add source provider" opens with it filled in.
     * Nothing is added until the viewer presses Save, so a web page can't add an add-on on its own.
     */
    private fun openAddonLink(launch: Intent) {
        if (publicPreview || !::store.isInitialized) return
        val link = AddonLinks.from(launch.action, launch.data?.toString(), launch.getStringExtra(Intent.EXTRA_TEXT)) ?: run {
            if (launch.action == Intent.ACTION_SEND) toast("That isn't an add-on link (it should start with https:// or stremio://).")
            return
        }
        // Consume once so rotating or resuming can't open it again.
        intent = Intent(this, MainActivity::class.java)
        editSourceProvider(null, link)
    }

    private fun openLauncherResume(launch: Intent) {
        val identity = LauncherResumeLink.parse(this,launch) ?: return
        // Consume once so rotating/resuming the activity cannot start it again.
        intent = Intent(this,MainActivity::class.java)
        continuePlayback(identity)
    }
    private fun continuePlayback(identity: PlaybackIdentity) {
        val link = LastPlayedLinks(store).find(identity)
        if (link == null) { navigate { showSources(identity) }; return }
        cachedPlayback = identity
        // The saved file name and size give the resumed video its badges.
        playingMedia = link.name to link.size
        // The logos it played with (an add-on's, or Usenet's provider and indexer) come back with it.
        playingAddon = link.addon.ifBlank { null }
        play(link.url, identity.title, identity, link.key, link.service, resumeImmediately = true, headers = link.headers)
    }
    private fun retainedRoots(): List<View> = listOf(body) + backStack.flatMap { it.retained?.children.orEmpty() }
    private fun hideContinue(marker: String) {
        fun hide(root: View) {
            if (root.getTag(R.id.pov_continue_identity) == marker) root.visibility = View.GONE
            if (root is ViewGroup) (0 until root.childCount).map(root::getChildAt).forEach(::hide)
        }
        retainedRoots().forEach(::hide)
    }
    private fun removeContinueViews(view: View) {
        val parent = view.parent as? ViewGroup
        val index = parent?.indexOfChild(view) ?: 0
        hideContinue(view.getTag(R.id.pov_continue_identity).toString())
        // Rows run newest first: focus moves to the newer card on the left, or the next one when this was first.
        val target = parent?.let { group -> (index - 1 downTo 0).map(group::getChildAt).firstOrNull { it.visibility == View.VISIBLE && it.isFocusable }
            ?: (index until group.childCount).map(group::getChildAt).firstOrNull { it.visibility == View.VISIBLE && it.isFocusable } }
        target?.requestFocus()
        if (!focusController.hasVisibleLeafFocus()) focusController.first()
        // The cards after the removed one shift left but the row keeps its scroll, which would leave the focused
        // card off-screen. Once the row has laid out again, it scrolls to the focused card, as the remote would.
        if (target != null && parent != null) parent.viewTreeObserver.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                parent.viewTreeObserver.removeOnGlobalLayoutListener(this)
                if (isDestroyed || !target.isAttachedToWindow) return
                if (!placeFlixRow(target)) (parent.parent as? HorizontalScrollView)?.smoothScrollTo((target.left - parent.paddingLeft).coerceAtLeast(0), 0)
            }
        })
        parent?.requestLayout()
    }
    private fun continueOptions(view: View, key: String, identity: PlaybackIdentity?, title: String) {
        view.setTag(R.id.pov_continue_identity, identity?.let(::identityKey) ?: key)
        view.setOnLongClickListener {
            val account = wetrakr.accountRevision()
            val options = mutableListOf<Pair<String, () -> Unit>>("Open Media" to {
                if (identity != null) {
                    if (identity.type == "series") selectedSeasons[identity.imdbId] = identity.season
                    navigate { detailsFor(MediaItemData(identity.imdbId, identity.type, identity.title), identity.takeIf { it.type == "series" }) }
                } else navigate { selectedCloud() }
            })
            if (identity != null) options += "☰  Streams" to { navigate { showSources(identity, manual = true) } }
            options += "Remove from Continue Watching" to {
                if (identity != null && wetrakr.connected()) {
                    load(generation, {
                        wetrakr.removeContinueWatching(identity, account)
                        wetrakrOutbox.discard(identity)
                        history.hide(key, identity)
                    }) { remoteProgress.remove(identityKey(identity)); toast("Removed from ${wetrakr.name} Continue Watching"); removeContinueViews(view) }
                } else { history.hide(key, identity); toast("Removed from Continue Watching on this TV"); removeContinueViews(view) }
            }
            holdMenu(title, options)
            true
        }
    }

    private fun checkStartupUpdate() {
        if (store.get("updates.startup") == "false") return  // on unless the viewer turns it off
        worker.execute {
            val latest = try { GitHubUpdates(this).latest() } catch (_: Exception) { null }
            if (latest != null) runOnUiThread {
                if (resumed && !isDestroyed && !isFinishing && !InAppPlaybackSession.hasActive()) {
                    SpacedChoiceDialog.show(this, "Update available · ${latest.version.removeSuffix("-rajput")}",
                        "A new published release of ${BuildConfig.APP_NAME} is available.", "View update", "Later") {
                        startActivity(Intent(this, UpdateActivity::class.java))
                    }
                }
            }
        }
    }

    private fun resumeRow(recent: List<PlaybackStore.Recent>) {
        section("Continue Watching")
        val row = horizontalRow(); localContinueRow = row
        refreshContinue = { if (row.isAttachedToWindow && !row.hasFocus()) { row.removeAllViews(); fillResumeRow(row, history.continueWatching()) } }
        fillResumeRow(row, recent)
    }

    private fun fillResumeRow(row: LinearLayout, recent: List<PlaybackStore.Recent>) {
        recent.take(12).forEach { saved ->
            val identity = saved.identity
            val item = MediaItemData(identity?.imdbId.orEmpty(), identity?.type ?: "movie", saved.title,
                background = identity?.imdbId?.let { "https://images.metahub.space/background/medium/$it/img" }.orEmpty())
            val remaining = listOf(identity?.takeIf { it.type == "series" }?.let { "S${it.season} E${it.episode}" }.orEmpty(),
                "${((saved.duration - saved.position).coerceAtLeast(0) / 60000)}m left").filter(String::isNotBlank).joinToString(" · ")
            val action: () -> Unit = {
                resumeSaved(saved)
            }
            val progress = saved.position.toDouble() / saved.duration.coerceAtLeast(1)
            val timeLeft = "${((saved.duration - saved.position).coerceAtLeast(0) / 60000)}m left"
            row.addView((if (resumeCards()) resumeCard(item, progress, identity?.let(::episodeLine) ?: "Cloud video", timeLeft, action) else tile(item, progress, remaining, action))
                .apply { tag = "resume:${saved.key}"; continueOptions(this, saved.key, saved.identity, saved.title) }, continueSlot())
        }
    }


    private fun resumeSaved(saved: PlaybackStore.Recent) {
        if (saved.key.startsWith("cloud:")) {
            val parts = saved.key.split(':')
            val folder = parts.getOrNull(2)?.toLongOrNull(); val file = parts.getOrNull(3)?.toLongOrNull()
            val kind = parts.getOrNull(1)
            if (parts.size == 4 && kind in setOf("torrents", "usenet", "webdl") && folder != null && file != null) {
                read(generation, { torbox.streamUrl(kind!!, folder, file) }) { url -> play(url, saved.title, saved.identity, saved.key, "torbox") }
                return
            }
        }
        saved.identity?.let { identity -> continuePlayback(identity) } ?: navigate { selectedCloud() }
    }

    private fun returnToVideo() {
        if (!InAppPlaybackSession.hasActive()) return
        if (PlayerActivity.openCurrent(this)) playerShown = true
    }

    private fun playbackOptions() {
        if (!InAppPlaybackSession.hasActive() || currentPlaybackOptionsDialog?.isShowing == true) return
        val dialog = AlertDialog.Builder(this).setTitle("Now playing · ${InAppPlaybackSession.title}")
            .setItems(arrayOf("Return to video", "Stop playback")) { _, choice ->
                if (choice == 0) returnToVideo() else { InAppPlaybackSession.stop(); scheduleContinueRefresh() }
            }.setNegativeButton("Keep browsing", null).create()
        dialog.setOnShowListener { dialog.window?.decorView?.tag = "pov_mini_playback_options" }
        currentPlaybackOptionsDialog = dialog
        dialog.setOnDismissListener { if (currentPlaybackOptionsDialog === dialog) currentPlaybackOptionsDialog = null }
        dialog.show()
    }

    private fun updateMiniPlayer() {
        val active = !publicPreview && InAppPlaybackSession.hasActive()
        updatePlaybackNavigation(active)
        val bottomPadding = contentBottomPadding()
        if (body.paddingBottom != bottomPadding) body.setPadding(body.paddingLeft, body.paddingTop, body.paddingRight, bottomPadding)
        if (!active) {
            miniPlayer?.let(InAppPlaybackSession::detachPlayerView); miniFrame?.visibility = View.GONE; miniFrame?.setTag(R.id.pov_mini_shown, false)
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            return
        }
        val exo = InAppPlaybackSession.playerOrNull
        if (exo?.playWhenReady == true && exo.playerError == null && exo.playbackState != androidx.media3.common.Player.STATE_ENDED)
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (miniFrame == null) {
            val frame = FrameLayout(this).apply {
                background = rounded(Color.BLACK, 8); clipToOutline = true; isFocusable = true; isFocusableInTouchMode = DeviceLayout.tv; isClickable = true
                tag = "return-to-video"; contentDescription = "Now playing. Select to return to video. Hold Select or press Menu for playback options."
            }
            val video = PlayerView(this).apply {
                useController = false; setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                isFocusable = false; isFocusableInTouchMode = false
                descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            }
            frame.addView(video, FrameLayout.LayoutParams(-1, -1))
            val caption = column().apply {
                setBackgroundColor(0xB0000000.toInt()); setPadding(dp(8), dp(6), dp(8), dp(6))
            }
            val label = text("", 11, Color.WHITE, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
            caption.addView(label)
            caption.addView(text(if (DeviceLayout.phone) "Tap to return · Hold for options" else "Select to return · Hold Select for options", 9, 0xFFD3DFED.toInt()).apply { maxLines = 1 })
            frame.addView(caption, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
            frame.setOnFocusChangeListener { view, focused ->
                if (focused) { pendingFocus = null; wantsContentFocus = false }
                // Flix: a white ring and a neutral caption, matching the app's other focused controls.
                view.foreground = if (!focused) null else if (flix) crispOutline(8) else FocusRingDrawable(accent, dp(8).toFloat(), true, dp(2).toFloat())
                caption.setBackgroundColor(if (focused) (if (flix) 0xE6000000.toInt() else 0xF0091824.toInt()) else 0xB0000000.toInt())
            }
            frame.setOnClickListener { returnToVideo() }
            frame.setOnLongClickListener { playbackOptions(); true }
            if (DeviceLayout.phone) shellRoot.addView(frame, FrameLayout.LayoutParams(dp(176), dp(99), Gravity.BOTTOM or Gravity.END).apply {
                rightMargin = dp(10); bottomMargin = dp(BOTTOM_BAR_HEIGHT + 8)
            }) else shellRoot.addView(frame, FrameLayout.LayoutParams(dp(247), dp(145), Gravity.BOTTOM or Gravity.END).apply {
                rightMargin = dp(20); bottomMargin = dp(15)
            })
            miniFrame = frame; miniPlayer = video; miniLabel = label
            miniFocus = MiniPlayerFocusRouter(body, frame)
            frame.post { focusController.revealCurrent() }
        }
        miniFrame?.let { frame ->
            if (frame.visibility != View.VISIBLE || frame.getTag(R.id.pov_mini_shown) != true) {
                // Arriving from the full player: the mini player grows into its corner instead of appearing.
                frame.setTag(R.id.pov_mini_shown, true); frame.visibility = View.VISIBLE
                frame.pivotX = frame.layoutParams.width.toFloat(); frame.pivotY = frame.layoutParams.height.toFloat()
                frame.alpha = 0f; frame.scaleX = 0.6f; frame.scaleY = 0.6f
                frame.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(300).setInterpolator(EASE_OUT).start()
            }
        }
        val status = when {
            exo?.playerError != null -> "Playback stopped"
            exo?.playbackState == androidx.media3.common.Player.STATE_ENDED -> "Finished"
            exo?.isPlaying == true -> "Playing"
            exo?.playWhenReady == true && exo.playbackState == androidx.media3.common.Player.STATE_BUFFERING -> "Loading"
            else -> "Paused"
        }
        miniLabel?.text = "$status · ${InAppPlaybackSession.title}"
        miniPlayer?.let(InAppPlaybackSession::attachPlayerView)
    }

    private fun updatePlaybackNavigation(active: Boolean) {
        val existing = navigationViews["nowplaying"]
        if (active == (existing != null)) return
        if (!active) {
            val restoreFocus = existing?.hasFocus() == true
            (existing?.parent as? ViewGroup)?.removeView(existing)
            navigationViews.remove("nowplaying")
            if (restoreFocus) navigationViews[destination]?.requestFocus()
            return
        }
        val host = navigationViews["home"]?.parent as? LinearLayout ?: return
        val navigationIndexes = (0 until host.childCount).filter { (host.getChildAt(it).tag as? String)?.startsWith("nav:") == true }
        if (navigationIndexes.isEmpty()) return
        val top = theme.layout.navigation == NavigationPlacement.TOP_BAR
        val bottom = theme.layout.navigation == NavigationPlacement.BOTTOM_BAR
        val target = Destination("nowplaying", "Now playing") { returnToVideo() }
        val cell = if (bottom) bottomNavigation(target) else navigation(target, top)
        val params = if (bottom) LinearLayout.LayoutParams(0, -1, 1f) else if (top) LinearLayout.LayoutParams(0, dp(38), 1f) else margin(-1, 35, bottom = 4)
        // TV: above Home; phone: the last tab.
        host.addView(cell, if (DeviceLayout.tv) navigationIndexes.first() else navigationIndexes.last() + 1, params)
    }

    private fun contentBottomPadding() = dp(if (!publicPreview && InAppPlaybackSession.hasActive()) (if (DeviceLayout.phone) 120 else 184) else 24)


    private fun animePage(type: String = "series", query: String = "", skip: Int = 0) {
        current = { animePage(type, query, skip) }
        destination = "anime"; refreshNavigation()
        val id = page(if (query.isBlank()) "Anime" else "Anime · $query", "Discover anime series, films, and new worlds.")
        if (DeviceLayout.phone) {
            // Phone: search first, then three short tabs that fit the width, so nothing hides off-screen.
            body.addView(button("⌕   Search anime") { searchAnime(type) }, margin(-1, 42, bottom = 10))
            phoneTabs(listOf("series" to "Series", "movie" to "Movies", "special" to "Specials").map { (key, title) ->
                (if (type == key) "●  $title" else title) to { animePage(key) }
            })
        } else {
            val tabs = horizontalRow()
            // Search leads the row, wider than the category tabs.
            tabs.addView(button("⌕   Search anime") { searchAnime(type) }, margin(230, 40, right = 16))
            listOf("series" to "Series & seasons", "movie" to "Anime movies", "special" to "Specials & OVAs").forEach { (key, title) ->
                tabs.addView(button(if (type == key) "●  $title" else title) { animePage(key) }, margin(153, 40, right = 8))
            }
        }
        read(id, { if (query.isBlank()) anime.popular(type, skip) else anime.search(query, type, skip) }) { result ->
            if (result.items.isEmpty()) body.addView(text("No anime found. Try a different title or category.", 14, muted), margin(-1, -2, top = 20))
            val width = DeviceLayout.canvasWidth - theme.layout.railWidthDp - theme.layout.contentInsetDp * 2
            val count = if (DeviceLayout.phone) DeviceLayout.phoneColumns(width) else (width / (cardWidth() + theme.layout.cardGapDp)).coerceAtLeast(1)
            // Flix columns stretch to the full content width, as the Movies and Shows grids do.
            val tileWidth = if (flix) (width - 6 - (count - 1) * theme.layout.cardGapDp) / count else cardWidth()
            val tileHeight = if (flix) tileWidth * 9 / 16 else theme.layout.cardHeightDp
            result.items.chunked(count).forEach { group ->
                val row = LinearLayout(this).apply { tag = "media-grid-row" }; body.addView(row, margin(-1, -2, top = 14))
                group.forEach { title ->
                    val media = MediaItemData("", title.type, title.title, title.year, title.poster, title.background, title.description, title.genres)
                    row.addView(tile(media, caption = animeFormat(title)) { navigate { animeDetails(title) } }.apply { tag = "anime:${title.kitsuId}" }, margin(tileWidth, tileHeight, right = theme.layout.cardGapDp))
                }
            }
            val paging = horizontalRow()
            if (skip > 0) paging.addView(button("Previous page") { animePage(type, query, (skip - 20).coerceAtLeast(0)) }, margin(150, 40, top = 16, right = 10))
            if (result.hasMore) paging.addView(button("More anime  ›") { animePage(type, query, result.nextOffset) }, margin(150, 40, top = 16))
            body.addView(text("Anime catalog: Kitsu. Episode lists and titles are confirmed before playback.", 10, muted), margin(-1, -2, top = 20))
        }
    }

    private fun searchAnime(type: String = "series") {
        val input = TvEditText(this).apply { hint = "Anime title"; setSingleLine() }
        AlertDialog.Builder(this).setTitle("Search anime").setView(input)
            .setPositiveButton("Search") { _, _ -> input.text.toString().trim().takeIf(String::isNotBlank)?.let { query -> navigate { animePage(type, query) } } }
            .setNegativeButton("Cancel", null).show().submitOnEnter(input)
    }

    private fun animeFormat(title: AnimeTitle): String = when (title.category) {
        "movie" -> "Anime movie"
        "special" -> if (title.subtype.equals("OVA", true)) "OVA / extra" else "Special / story chapter"
        else -> "Series / season"
    }

    private fun animeDetails(title: AnimeTitle) {
        current = { animeDetails(title) }
        val id = page(title.title, animeFormat(title))
        if (flix) {
            // The card's own picture shows at once (never the previous title's), then the sharp backdrop
            // fades in while the IMDb match is still being looked up.
            val card = MediaItemData("", title.type, title.title, title.year, title.poster, title.background, title.description, title.genres)
            placeBackdrop(home = false); heroBackdrop.visibility = View.VISIBLE
            backdropStandIn(card); image(backdropArt, backdropFor(card), feature = true)
        }
        body.addView(button("Choose a matching title manually") { matchAnime(title) }, margin(280, 40, bottom = 10))
        fun render(result: AnimeEpisodeCatalog) {
            // The player asks AniSkip for skip times by this anime's own episode numbers.
            AnimeEpisodeRefs.remember(this, result)
            // Play sits right under the synopsis, then one row of related entries, then the episodes.
            body.removeAllViews(); hero(result.media, false, synopsisLines = if (DeviceLayout.phone) 4 else 3)
            mediaActions(result.media)
            if (!flix) body.addView(text(animeFormat(title) + " · " + result.notice, 12, muted), margin(-1, -2, top = 9, bottom = 12))
            val relatedHost = column(); body.addView(relatedHost, margin(-1, -2, top = 10))
            relatedHost.addView(text("Finding related seasons and films…", 12, muted))
            loadRelatedAnime(title, relatedHost, id)
            if (title.type == "series") animeEpisodePicker(result)
            body.addView(button("Choose a matching title manually") { matchAnime(title) }, margin(280, 40, top = 15))
        }
        val cached = anime.cachedDetails(title)
        if (cached != null) { render(cached); focusAfterRender(id) } else read(id, { anime.details(title) }) { render(it) }
    }

    private fun loadRelatedAnime(title: AnimeTitle, host: LinearLayout, id: Int) {
        worker.execute {
            try {
                val related = anime.related(title)
                runOnUiThread {
                    if (id == generation && !isDestroyed) {
                        host.removeAllViews()
                        // Three wide buttons: the row scrolls sideways on a phone.
                        val row = horizontalRow(host)
                        fun add(short: String, label: String, choices: List<AnimeTitle>, key: String) {
                            row.addView(button("$short (${choices.size})") {
                                if (choices.isEmpty()) toast("No catalog-linked $label are available")
                                else AlertDialog.Builder(this).setTitle(label).setItems(choices.map { it.title }.toTypedArray()) { _, index ->
                                    navigate { animeDetails(choices[index]) }
                                }.setNegativeButton("Cancel", null).show()
                            }.apply { tag = "related:$key"; gravity = Gravity.CENTER }, margin(150, 38, right = 8))
                        }
                        add("Seasons", "Seasons & story parts", related.seasons, "seasons")
                        add("Movies", "Anime movies", related.movies, "movies")
                        add("Specials", "Specials & OVAs", related.specials, "specials")
                        if (related.otherSeries.isNotEmpty()) add("Spin-offs", "Spin-off series", related.otherSeries, "spinoffs")
                        if (related.truncated) host.addView(text("More related entries may be available through Anime search.", 11, muted), margin(-1, -2, top = 6))
                        focusAfterRender(id)
                    }
                }
            } catch (_: Exception) { runOnUiThread {
                if (id == generation && !isDestroyed) {
                    host.removeAllViews(); host.addView(button("Retry related seasons and movies") { loadRelatedAnime(title, host, id) }, margin(310, 40))
                    focusAfterRender(id)
                }
            } }
        }
    }

    private fun animeEpisodePicker(result: AnimeEpisodeCatalog) {
        if (result.groups.isEmpty()) {
            body.addView(text("No verified episodes are available for this selected entry. A film has its own movie action; episode numbers are never substituted.", 14, muted), margin(-1, -2, top = 14)); return
        }
        val key = "anime:${result.anime.kitsuId}"
        val groupIndex = selectedSeasons[key]?.coerceIn(0, result.groups.lastIndex) ?: 0
        val selector = button("") { }
        val host = column()
        fun render(index: Int) {
            selectedSeasons[key] = index
            val group = result.groups[index]
            selector.text = "${group.title} · ${group.episodes.size} episodes  ▾"
            selector.contentDescription = selector.text; selector.tag = "anime-group:$key"
            host.removeAllViews()
            host.addView(text("${result.anime.title} · ${group.title}", 17, ink, true), margin(-1, -2, top = 15, bottom = 9))
            // Flix: the same sideways row of wide episode cards as shows, with the header following the focused episode.
            if (flix && DeviceLayout.tv) host.addView(episodeStrip(result.media, group.episodes.map { choice ->
                Triple(choice.identity, EpisodeData(choice.identity.season, choice.identity.episode, choice.title, choice.released, choice.thumbnail),
                    "Episode ${choice.catalogEpisode}")
            }, null), margin(-1, -2))
            else group.episodes.forEach { choice ->
                val identity = choice.identity
                val extra = buildList {
                    if (choice.catalogEpisode != identity.episode) add("Part episode ${choice.catalogEpisode}")
                    choice.absoluteEpisode?.let { add("Overall episode $it") }
                    if (choice.released.isNotBlank()) add(choice.released.take(10))
                }.joinToString(" · ")
                if (flix) host.addView(episodeRow(identity, EpisodeData(identity.season, identity.episode, choice.title, choice.released, choice.thumbnail),
                    result.media, "Episode ${choice.catalogEpisode}", extra), margin(-1, -2, bottom = 8))
                else host.addView(episodeButton(identity, choice.title, choice.released, extra), margin(-1, 62, bottom = 7))
            }
            focusAfterRender(generation)
        }
        selector.setOnClickListener {
            AlertDialog.Builder(this).setTitle("Episodes in this entry").setSingleChoiceItems(
                result.groups.map { "${it.title} · ${it.episodes.size} episodes" }.toTypedArray(), selectedSeasons[key] ?: 0
            ) { dialog, index -> dialog.dismiss(); render(index); selector.requestFocus() }.setNegativeButton("Cancel", null).show()
        }
        body.addView(selector, margin(if (DeviceLayout.phone) -1 else 380, 42, top = 10)); body.addView(host)
        render(groupIndex)
    }

    private fun matchAnime(title: AnimeTitle) {
        val input = TvEditText(this).apply { setText(title.title); hint = "Title to search"; setSingleLine() }
        AlertDialog.Builder(this).setTitle("Find the correct ${if (title.type == "movie") "film" else "show"}")
            .setMessage("Choose the matching catalog title. For sequels and split seasons, check the show's season and episode list before playing.")
            .setView(input).setPositiveButton("Search") { _, _ ->
                val query = input.text.toString().trim()
                if (query.isNotBlank()) load(generation, { catalog.search(query, title.type) }) { matches ->
                    if (matches.isEmpty()) toast("No match found. Try the English or original title.")
                    else AlertDialog.Builder(this).setTitle("Confirm the correct title")
                        .setItems(matches.map { "${it.title} · ${it.year}" }.toTypedArray()) { _, index -> navigate { details(matches[index]) } }
                        .setNegativeButton("Cancel", null).show()
                }
            }.setNegativeButton("Cancel", null).show().submitOnEnter(input)
    }

    /**
     * Movies and Shows double as Discover: a sort and a genre narrow the grid. With a TMDB key the lists come from
     * TMDB's discover (paged by 20, [skip] counts pages); without one from Cinemeta's genre catalogs ([skip] counts titles).
     */
    private fun catalogPage(type: String, query: String = "", skip: Int = 0, genre: String = "", sort: String = "") {
        current = { catalogPage(type, query, skip, genre, sort) }
        destination = type; refreshNavigation()
        val tmdbKey = if (publicPreview) "" else store.get(TMDB_KEY)
        val tmdb = tmdbKey.isNotBlank()
        // Newest leads and opens by default; without a TMDB key it comes from Queued Up's latest-release lists.
        val sorts = if (tmdb) listOf(TmdbCatalog.SORT_NEWEST to "Newest", TmdbCatalog.SORT_POPULAR to "Popular", TmdbCatalog.SORT_TOP_RATED to "Top rated")
            else listOf(SORT_NEWEST to "Newest", CatalogApi.SORT_POPULAR to "Popular", CatalogApi.SORT_FEATURED to "Featured")
        val order = sort.takeIf { wanted -> sorts.any { it.first == wanted } } ?: sorts.first().first
        val genres = when {
            tmdb -> TmdbCatalog.discoverGenres(type).keys.toList()
            order == SORT_NEWEST -> QueuedUpCatalog.GENRES.keys.toList()
            else -> CatalogApi.discoverGenres(type)
        }
        val chosen = genre.takeIf { it in genres }.orEmpty()
        val offsets = "$type|$chosen|$order"
        if (skip == 0 && query.isBlank()) catalogOffsets[offsets]?.clear()
        val id = page(if (query.isBlank()) if (type == "movie") "Movies" else "TV shows" else "Results for “$query”", "Find something worth your evening.")
        fun open(nextGenre: String = chosen, nextSort: String = order) = catalogPage(type, genre = nextGenre, sort = nextSort)
        fun chip(label: String, selected: Boolean, action: () -> Unit) = button(if (selected) "●  $label" else label, action)
        if (query.isBlank()) {
            if (DeviceLayout.phone) {
                body.addView(button("⌕   Search movies & TV") { search() }, margin(-1, 42, bottom = 10))
                phoneTabs(sorts.map { (key, label) -> (if (key == order) "●  $label" else label) to { open(nextSort = key) } })
            } else {
                val actions = horizontalRow()
                actions.addView(button("Search movies & TV") { search() }, margin(190, 38, right = 16))
                sorts.forEach { (key, label) -> actions.addView(chip(label, key == order) { open(nextSort = key) }, margin(-2, 38, right = 8)) }
            }
            val genreRow = horizontalRow()
            genreRow.addView(chip("All genres", chosen.isEmpty()) { open(nextGenre = "") }.apply { tag = "genre:" }, margin(-2, 36, right = 8))
            genres.forEach { name -> genreRow.addView(chip(name, name == chosen) { open(nextGenre = name) }.apply { tag = "genre:$name" }, margin(-2, 36, right = 8)) }
            if (skip > 0) body.addView(button("Previous page") {
                catalogPage(type, skip = catalogOffsets[offsets]?.removeLastOrNull() ?: 0, genre = chosen, sort = order)
            }, margin(140, 38, top = 6))
        } else horizontalRow().addView(button("Search movies & TV") { search() }, margin(190, 38, right = 8))
        read(id, {
            when {
                query.isNotBlank() -> catalog.search(query, type)
                tmdb -> TmdbCatalog(this, tmdbKey, tmdbRegion()).discover(type, chosen, order, skip + 1)
                // Queued Up down: this year's Cinemeta releases, narrowed to the genre by each title's own genres.
                order == SORT_NEWEST -> QueuedUpCatalog(this).newest(type, chosen, skip) ?: if (skip > 0) emptyList() else
                    catalog.newReleases(type).filter { chosen.isEmpty() || it.genres.any { genre -> genre.equals(chosen, true) } }
                chosen.isEmpty() && order == CatalogApi.SORT_POPULAR -> catalog.popular(type, skip)
                else -> catalog.discover(type, chosen, order, skip)
            }
        }) { items ->
            if (items.isEmpty()) body.addView(text("Nothing here yet. Try another genre or sort.", 14, muted), margin(-1, -2, top = 20))
            mediaGrid(items)
            if (query.isBlank() && items.isNotEmpty()) body.addView(button("More titles  ›") {
                catalogOffsets.getOrPut(offsets) { ArrayDeque() }.addLast(skip)
                catalogPage(type, skip = if (tmdb) skip + 1 else skip + items.size, genre = chosen, sort = order)
            }, margin(180, 42, top = 20))
        }
    }

    private fun search(initialQuery: String = "") {
        // A full-screen search overlay: one large field, then Search, Voice search and Cancel.
        val dialog = android.app.Dialog(this, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen)
        val input = TvEditText(this).apply {
            hint = "Movie or TV show"; setText(initialQuery); setSelection(text.length); setSingleLine(); inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(18).toFloat()); setTextColor(ink); setHintTextColor(muted)
            background = null; setPadding(dp(10), 0, dp(10), 0); includeFontPadding = false
        }
        fun submit() {
            val query = input.text.toString().trim().take(200)
            if (query.isEmpty()) return
            (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(input.windowToken, 0)
            dialog.dismiss(); navigate { searchResults(query) }
        }
        val root = FrameLayout(this).apply { setBackgroundColor(withAlpha(bg, 242)) }
        val card = column().apply { gravity = Gravity.CENTER_HORIZONTAL }
        card.addView(text("Search", 30, ink, true), margin(-2, -2))
        card.addView(text("Find movies and TV shows by title", 13, muted), margin(-2, -2, top = 6, bottom = 22))
        val field = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), 0, dp(12), 0)
            background = rounded(withAlpha(Color.WHITE, 26), 28, withAlpha(Color.WHITE, 40))
        }
        field.addView(NavigationIconView(this, "search", ink), margin(22, 22))
        field.addView(input, LinearLayout.LayoutParams(0, -1, 1f))
        input.setOnFocusChangeListener { _, focused ->
            field.background = rounded(withAlpha(Color.WHITE, if (focused) 38 else 26), 28, if (focused) Color.WHITE else withAlpha(Color.WHITE, 40))
        }
        card.addView(field, margin(-1, 56))
        val actions = LinearLayout(this).apply { gravity = Gravity.CENTER; clipChildren = false }
        val searchAction = primaryButton("⌕  Search") { submit() }.apply { contentDescription = "Search" }
        actions.addView(searchAction, margin(if (DeviceLayout.phone) 110 else 150, 40, right = if (DeviceLayout.phone) 8 else 12))
        input.onBack = { hideKeyboard(input); searchAction.requestFocus() }
        actions.addView(button(if (DeviceLayout.phone) "🎤  Voice" else "🎤  Voice search") { dialog.dismiss(); voiceSearch() }.apply { gravity = Gravity.CENTER; tag = "search-voice" },
            margin(if (DeviceLayout.phone) 110 else 170, 40, right = if (DeviceLayout.phone) 8 else 12))
        actions.addView(button("Cancel") { dialog.dismiss() }.apply { gravity = Gravity.CENTER }, margin(if (DeviceLayout.phone) 90 else 110, 40))
        card.addView(actions, margin(-2, -2, top = 22))
        if (DeviceLayout.tv) card.addView(text("Press OK to type · the keyboard's Search key searches straight away", 11, muted), margin(-2, -2, top = 18))
        // The phone keeps the field near the top, clear of the on-screen keyboard.
        if (DeviceLayout.phone) root.addView(card, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply { topMargin = dp(70); leftMargin = dp(16); rightMargin = dp(16) })
        else root.addView(card, FrameLayout.LayoutParams(dp(560), -2, Gravity.CENTER).apply { bottomMargin = dp(60) })
        dialog.setContentView(root)
        input.setOnEditorActionListener { _, action, event ->
            if (action in setOf(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH, android.view.inputmethod.EditorInfo.IME_ACTION_DONE, android.view.inputmethod.EditorInfo.IME_ACTION_GO)) { submit(); true }
            else if (event?.keyCode in setOf(KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER)) {
                if (event?.action == KeyEvent.ACTION_UP) submit()
                true
            } else false
        }
        input.setOnKeyListener { _, key, event ->
            // TV: the field starts selected with the keyboard closed; OK opens it.
            if (DeviceLayout.tv && key == KeyEvent.KEYCODE_DPAD_CENTER) {
                if (event.action == KeyEvent.ACTION_UP) (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).showSoftInput(input, 0)
                return@setOnKeyListener true
            }
            if (key == KeyEvent.KEYCODE_ENTER || key == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                if (event.action == KeyEvent.ACTION_UP) submit()
                true
            } else false
        }
        if (DeviceLayout.phone) dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        else { input.showSoftInputOnFocus = false; dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN) }
        searchDialog = dialog; dialog.show(); input.requestFocus()
    }
    /**
     * Voice search listens in the app with the device's speech service (Google on Android TV), showing a mic
     * and the words as they are heard; the system voice screen is only a fallback when that service is missing.
     */
    private fun voiceSearch() {
        if (android.speech.SpeechRecognizer.isRecognitionAvailable(this)) {
            if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) listen()
            else requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), MIC_REQUEST)
            return
        }
        systemVoiceSearch()
    }

    @Deprecated("Permission result compatibility for Android TV")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        @Suppress("DEPRECATION") super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != MIC_REQUEST) return
        if (grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED) listen()
        else { toast("Microphone access is off. Using the TV's voice search instead."); systemVoiceSearch() }
    }

    private fun listen() {
        val recognizer = android.speech.SpeechRecognizer.createSpeechRecognizer(this)
        val dialog = android.app.Dialog(this, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen)
        val root = FrameLayout(this).apply { setBackgroundColor(withAlpha(bg, 240)) }
        val column = column().apply { gravity = Gravity.CENTER_HORIZONTAL }
        val micSize = dp(64)
        val micFrame = FrameLayout(this)
        val pulse = View(this).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(withAlpha(accent, 70)) } }
        val mic = FrameLayout(this).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(accent) } }
        mic.addView(MicIconView(this), FrameLayout.LayoutParams(micSize / 2, micSize / 2, Gravity.CENTER))
        micFrame.addView(pulse, FrameLayout.LayoutParams(micSize, micSize, Gravity.CENTER))
        micFrame.addView(mic, FrameLayout.LayoutParams(micSize, micSize, Gravity.CENTER))
        column.addView(micFrame, margin(110, 110))
        val status = text("Listening…", 15, muted, true)
        val heard = text("Say a movie or show", 30, ink, true).apply { gravity = Gravity.CENTER; maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        column.addView(status, margin(-2, -2, top = 16))
        column.addView(heard, margin(560, -2, top = 12))
        val retry = primaryButton("🎤  Try again") { }.apply { visibility = View.GONE }
        column.addView(retry, margin(170, 40, top = 22))
        root.addView(column, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        dialog.setContentView(root)
        // The pulse follows the speaker's volume.
        val idle = ValueAnimator.ofFloat(1f, 1.18f).apply {
            duration = 900; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE
            addUpdateListener { pulse.scaleX = it.animatedValue as Float; pulse.scaleY = pulse.scaleX }
        }
        fun start() {
            status.text = "Listening…"; heard.text = "Say a movie or show"; retry.visibility = View.GONE; idle.start()
            recognizer.startListening(Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(android.speech.RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(android.speech.RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName))
        }
        retry.setOnClickListener { start() }
        recognizer.setRecognitionListener(object : android.speech.RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() { status.text = "Listening…" }
            override fun onRmsChanged(rmsdB: Float) {
                val level = 1f + (rmsdB.coerceIn(0f, 10f) / 10f) * 0.45f
                idle.pause(); pulse.scaleX = level; pulse.scaleY = level
            }
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() { status.text = "Searching…"; idle.resume() }
            override fun onError(error: Int) {
                idle.cancel(); pulse.scaleX = 1f; pulse.scaleY = 1f
                status.text = if (error == android.speech.SpeechRecognizer.ERROR_NO_MATCH || error == android.speech.SpeechRecognizer.ERROR_SPEECH_TIMEOUT) "Didn't catch that" else "Voice search is unavailable right now"
                heard.text = "Press OK to try again"; retry.visibility = View.VISIBLE; retry.requestFocus()
            }
            override fun onResults(results: Bundle?) {
                val query = results?.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty().take(200)
                if (query.isBlank()) { onError(android.speech.SpeechRecognizer.ERROR_NO_MATCH); return }
                heard.text = query; dialog.dismiss(); navigate { searchResults(query) }
            }
            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf(String::isNotBlank)?.let { heard.text = it }
            }
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        dialog.setOnDismissListener { idle.cancel(); recognizer.cancel(); recognizer.destroy() }
        dialog.show(); start()
    }

    private fun systemVoiceSearch() {
        val intent = Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Search movies & TV shows")
        try { @Suppress("DEPRECATION") startActivityForResult(intent, VOICE_REQUEST) }
        catch (_: android.content.ActivityNotFoundException) { toast("Voice search is unavailable on this TV. Use the keyboard."); search() }
    }
    @Deprecated("Activity result compatibility for Android TV")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VOICE_REQUEST) {
            val query = data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.trim().orEmpty().take(200)
            if (resultCode == RESULT_OK && query.isNotBlank()) navigate { searchResults(query) } else search()
        } else if (requestCode == PLAYBACK_REQUEST) {
            // A finished title reaches the service's history a few seconds after the player closes.
            if (!publicPreview) window.decorView.postDelayed({ if (!isDestroyed) refreshWatched() }, 8_000)
            val identity = cachedPlayback ?: runCatching { data?.getStringExtra("cached_identity")?.let { PlaybackIdentity.fromJson(org.json.JSONObject(it)) } }.getOrNull()
            cachedPlayback = null; launchingPlayer = false
            StreamChoices.pendingSwitch?.let { (switchIdentity, source, at) ->
                StreamChoices.pendingSwitch = null
                toast("Switching to ${source.title.lineSequence().first().take(60)}…")
                switchStream(switchIdentity, source, at)
                return
            }
            if (resultCode == RESULT_FIRST_USER && identity != null) {
                LastPlayedLinks(store).remove(identity); toast("Last stream unavailable. Finding fresh sources…"); navigate { showSources(identity) }
            }
        }
    }
    private companion object {
        private const val QR_DP = 110; const val WARM_BACKDROPS = 6; const val HIDE_UNCACHED = "sources.hide_uncached"; const val BACKDROP_FADE_MS = 220; const val VOICE_REQUEST = 701; const val PLAYBACK_REQUEST = 702; const val MIC_REQUEST = 703; const val FLIX_ROW_LINE = 236; const val FEATURE_INTERVAL_MS = 5_000L; const val AUTOPLAY_FIRST = "sources.autoplay_first"; const val AUTOPLAY_MAX_GB = "sources.autoplay_max_gb"; const val AUTOPLAY_MAX_GB_EPISODE = "sources.autoplay_max_gb_episode"; const val AUTOPLAY_MAX_MBPS = "sources.autoplay_max_mbps"; const val AUTOPLAY_LIMIT_ON = "sources.autoplay_limit_on"; const val BACKDROP_WIDTH = 1280; const val CONTINUE_CACHE = "wetrakr.continue_cache"
        const val TMDB_KEY = "tmdb.key"; const val TMDB_REGION = "tmdb.region"
        const val TVDB_KEY = "tvdb.key"; const val TVDB_SHOWS = "tvdb.shows"
        /** "false" keeps the search-and-details add-on saved but switched off. */
        const val METADATA_ON = "metadata.source.on"
        const val IMDB_YELLOW = 0xFFF5C518.toInt()
        /** One Home shelf step up or down. */
        const val HOME_ROW_SCROLL_MS = 200L
        const val CAST_CARD_WIDTH = 132; const val CAST_PHOTO = 112
        /** Room kept for the cast row while it loads, so the details don't move when it arrives. */
        const val CAST_ROW_HEIGHT = 250
        /** AIOStreams library files loaded per step (its catalog page size). */
        const val AIO_LIBRARY_PAGE = 100
        const val CAST_CARD_WIDTH_PHONE = 108; const val CAST_PHOTO_PHONE = 96; const val CAST_ROW_HEIGHT_PHONE = 210
        /** "round" brings back the 0.3.19 round portraits (sizes below); anything else is the large portraits. */
        const val CAST_STYLE = "ui.cast_style"
        const val ROUND_CAST_WIDTH = 112; const val ROUND_CAST_PHOTO = 92; const val ROUND_CAST_ROW = 202
        const val ROUND_CAST_WIDTH_PHONE = 80; const val ROUND_CAST_PHOTO_PHONE = 62; const val ROUND_CAST_ROW_PHONE = 158
        const val LIBRARY_FIRST = "sources.library_first"
        /** An actor's rows show their 20 most popular titles of each kind; See all shows every one, newest first. */
        const val PERSON_ROW_TITLES = 20
        /** Titles per column on the phone's actor page before See all. */
        const val PERSON_COLUMN_TITLES = 10
        /** Shown once after a restore restarts the app. Never part of a backup (account.* is excluded). */
        const val ACCOUNT_NOTICE = "account.notice"
        /** The app's website. Until the site itself exists it forwards to the GitHub page. */
        const val WEBSITE = "https://nabrio.rajput.cc"
        /** Where people reach the developer: forms served by server/backup. */
        const val BUG_PAGE = "https://bug.rajput.cc"
        const val FEATURE_PAGE = "https://feature.rajput.cc"
        const val SETTINGS_SECTION = "settings.section"
        const val HOME_KEEP_MS = 15 * 60_000L
        const val HOME_BACKDROP_SCALE = 0.72f
        const val COMPACT_HERO_HEIGHT = 236
        val EASE_OUT = android.view.animation.DecelerateInterpolator(1.6f)
        const val DETAILS_KEEP_MS = 10 * 60_000L
        /** Bump when the set of Home rows changes, so rows saved under the old set are not shown. */
        // 3: Top 10 charts and Latest Releases (saved shelves from before are not reused).
        const val SHELF_SET_VERSION = "3"
        const val TOP10_MOVIES = "Top 10 Movies"; const val TOP10_SHOWS = "Top 10 Shows"
        const val SORT_NEWEST = "newest"
        const val LATEST_MOVIES = "Latest Releases · Movies"; const val LATEST_SHOWS = "Latest Releases · Shows"
        /** Home row ids (Settings → Home rows order and "See all" paging); never rename, saved orders refer to them. */
        const val ROW_LATEST_MOVIES = "latest:movie"; const val ROW_LATEST_SHOWS = "latest:series"; const val ROW_CINEMAS = "tmdb:now_playing"
        const val ROW_POPULAR_MOVIES = "popular:movie"; const val ROW_POPULAR_SHOWS = "popular:series"
        const val ROW_FEATURED_MOVIES = "featured:movie"; const val ROW_FEATURED_SHOWS = "featured:series"
        const val RESUME_CARD_WIDTH = 300; const val RESUME_CARDS = "home.resume_cards"
        const val SYNOPSIS_MORE_CHARS = 220; const val CONTINUE_LIMIT = 30
        const val PHONE_INLINE_VALUE = 18; const val BOTTOM_BAR_HEIGHT = 56; const val PHONE_BAR_COLOR = 0xFF0B0B0B.toInt()
        /** Where the phone's feature copy starts: over the foot of the 225-unit banner. */
        const val PHONE_COPY_TOP = 150
        /** A landscape screen (car, tablet) starts the feature copy higher, so the shorter banner still frames it. */
        const val PHONE_WIDE_COPY_TOP = 96 }

    private fun searchResults(query: String, filter: String = "all", cached: CatalogApi.SearchResults? = null) {
        current = { searchResults(query, filter, cached) }
        destination = "search"; refreshNavigation()
        val id = page("Results for “$query”", "Movies and TV shows in one search.")
        val actions = horizontalRow()
        for ((value, label) in listOf("all" to "All", "movie" to "Movies", "series" to "TV shows")) {
            actions.addView(button(if (value == filter) "✓ $label" else label) { searchResults(query, value, cached) }, margin(130, 38, right = 8))
        }
        actions.addView(button("New search") { search(query) }, margin(150, 38))
        fun render(result: CatalogApi.SearchResults) {
            // Rebind filters to these results so switching categories does not repeat network requests.
            current = { searchResults(query, filter, result) }
            for (index in 0..2) actions.getChildAt(index).setOnClickListener {
                searchResults(query, listOf("all", "movie", "series")[index], result)
            }
            if (result.unavailableTypes.isNotEmpty()) {
                val missing = result.unavailableTypes.joinToString(" and ") { if (it == "movie") "Movie" else "TV show" }
                body.addView(text("$missing results are temporarily unavailable.", 13, muted), margin(-1, -2, top = 12))
                body.addView(button("Retry search") { searchResults(query, filter) }, margin(150, 38))
            }
            mediaGrid(result.items.filter { filter == "all" || it.type == filter }, showType = true)
        }
        if (cached == null) read(id, { catalog.searchAll(query) }, ::render) else { render(cached); focusAfterRender(id) }
    }

    private fun details(item: MediaItemData) = detailsFor(item, null)
    private fun detailsFor(item: MediaItemData, episode: PlaybackIdentity?) {
        current = { detailsFor(item, episode) }
        fun render(id: Int, result: MediaItemData) {
            body.removeAllViews(); hero(result, false, if (flix && result.type == "series") 3 else 0); mediaActions(result, episode)
            // Shows: on TV the cast sits under the episode row and season tabs; the phone's episodes are a long
            // vertical list, so there the cast comes first.
            // The cast loads first; the keywords and About request (it also carries the credits) waits for it, so the
            // two never download the same credits side by side.
            var startDiscovery: (() -> Unit)? = null
            var castDone = false
            val afterCast = { castDone = true; startDiscovery?.invoke(); Unit }
            if (result.type == "series" && DeviceLayout.phone) { castRow(id, result, afterCast); seasonPicker(result) }
            else { if (result.type == "series") seasonPicker(result); castRow(id, result, afterCast) }
            startDiscovery = detailsDiscovery(id, result)
            if (castDone) startDiscovery()
            if (result.type == "series") { if (flix) body.post { if (id == generation) riseIn() } }
            else if (flix && DeviceLayout.tv) alignToBottom(id, reveal = ::riseIn)
            else if (flix) body.post { if (id == generation) riseIn() }
        }
        // Details fetched while the poster had focus (or opened recently) show at once.
        cachedDetails(item)?.let { result -> val id = page(""); backdropStandIn(item, result); render(id, result); focusAfterRender(id); return }
        // Otherwise the backdrop carries over straight away and the details rise in when they arrive,
        // with no "Loading" page in between.
        val id = page("")
        if (flix) { placeBackdrop(home = false); heroBackdrop.visibility = View.VISIBLE; backdropStandIn(item); image(backdropArt, backdropFor(item), feature = true) }
        load(id, { fetchDetails(item) }, readOnly = true, quiet = flix) { result -> render(id, result) }
    }

    private class CachedDetails(val result: MediaItemData, val at: Long)
    private val detailsCache = LruCache<String, CachedDetails>(60)
    private val detailsInFlight = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private val prefetchHandler = Handler(Looper.getMainLooper())
    private fun detailsKey(item: MediaItemData) = "${item.type}:${item.imdbId}"
    private fun cachedDetails(item: MediaItemData): MediaItemData? = synchronized(detailsCache) {
        detailsCache.get(detailsKey(item))?.takeIf { android.os.SystemClock.elapsedRealtime() - it.at < DETAILS_KEEP_MS }?.result
    }
    private fun fetchDetails(item: MediaItemData): MediaItemData = withTvdb(catalog.details(item)).also { result ->
        synchronized(detailsCache) { detailsCache.put(detailsKey(item), CachedDetails(result, android.os.SystemClock.elapsedRealtime())) }
    }

    /**
     * With the viewer's TVDB key (and Use TVDB for shows on), a show's seasons and episodes come from TVDB, usually
     * more complete than Cinemeta's. A still or synopsis TVDB lacks is kept from Cinemeta; a show TVDB doesn't know,
     * or any TVDB error, leaves Cinemeta's list as it was. Call on a worker.
     */
    private fun withTvdb(result: MediaItemData): MediaItemData {
        if (result.type != "series" || publicPreview) return result
        val key = store.get(TVDB_KEY)
        if (key.isBlank() || store.get(TVDB_SHOWS) == "false") return result
        val episodes = runCatching { TvdbApi(this, key).episodes(result.imdbId) }.getOrNull() ?: return result
        val known = result.episodes.associateBy { it.season to it.episode }
        return result.copy(episodes = episodes.map { episode ->
            val old = known[episode.season to episode.episode]
            episode.copy(thumbnail = episode.thumbnail.ifBlank { old?.thumbnail.orEmpty() },
                overview = episode.overview.ifBlank { old?.overview.orEmpty() },
                title = if (episode.title == "Episode ${episode.episode}" && old != null) old.title else episode.title)
        })
    }
    private fun forgetDetails() = synchronized(detailsCache) { detailsCache.evictAll() }

    /** Downloads and shades a details backdrop ahead of time, so the page opens with its picture ready. */
    private fun preloadBackdrop(address: String) {
        if (!flix || !address.startsWith("https://")) return
        val key = "backdrop:$address"
        if (bitmapCache.get(key) != null || !backdropsInFlight.add(key)) return
        featureImages.execute {
            try { fetchBitmap(address, BACKDROP_WIDTH)?.let { shadedBackdrop(it, false) }?.let { bitmapCache.put(key, it) } }
            catch (_: Exception) { /* The page loads it again when opened. */ } finally { backdropsInFlight.remove(key) }
        }
    }
    private val backdropsInFlight = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /**
     * Until the sharp backdrop is ready, the card's own picture (already loaded) fills the screen, dimmed,
     * and the backdrop fades in over it; the page never opens on an empty background.
     */
    private fun backdropStandIn(item: MediaItemData, result: MediaItemData = item) {
        if (!flix || bitmapCache.get("backdrop:${backdropFor(result)}") != null) return
        val card = listOf(item.background, backdropFor(item)).filter(String::isNotBlank).map(::thumbnail)
            .firstNotNullOfOrNull { bitmapCache.get(it) }
        backdropArt.animate().cancel(); backdropArt.setTag(R.id.pov_backdrop_key, null)
        // Without this title's own picture, the page opens dark rather than on the previous title's art.
        if (card != null) { backdropArt.setImageBitmap(card); backdropArt.alpha = 0.55f }
        else { backdropArt.setImageDrawable(null); backdropArt.tag = null; backdropArt.alpha = 0f }
    }

    /** A short rest on a poster fetches its details in the background, so opening it is usually instant. */
    private fun prefetchDetails(item: MediaItemData) {
        prefetchHandler.removeCallbacksAndMessages(null)
        if (!item.imdbId.matches(Regex("tt\\d+")) || cachedDetails(item) != null) return
        prefetchHandler.postDelayed({
            val key = detailsKey(item)
            if (cachedDetails(item) != null || !detailsInFlight.add(key)) return@postDelayed
            metadataWorker.execute {
                try { preloadBackdrop(backdropFor(fetchDetails(item))) }
                catch (_: Exception) { /* Opening it fetches again. */ } finally { detailsInFlight.remove(key) }
            }
        }, 300)
    }

    private fun mediaActions(result: MediaItemData, episode: PlaybackIdentity? = null) {
        val identity = PlaybackIdentity(result.imdbId, result.type, result.title)
        val actions = horizontalRow()
        // Flix: one white Play (Resume or the right episode for shows), then compact icon actions.
        fun play(label: String, width: Int, action: () -> Unit) = actions.addView(
            if (flix) primaryButton(label, action) else button(label, action).apply { gravity = Gravity.CENTER }, margin(width, 44, right = 12))
        // Streams sits beside Play: the full link list for picking by hand, never auto-played even with Auto-play on.
        fun streams(target: PlaybackIdentity) = actions.addView(button("☰  Streams") { navigate { showSources(target, manual = true) } }
            .apply { gravity = Gravity.CENTER; tag = "details-streams" }, margin(140, 44, right = 12))
        if (result.type == "movie") {
            val target = savedFor(result)?.identity ?: identity
            play("▶  Play", 130) { navigate { showSources(target) } }
            streams(target)
        }
        if (episode != null) { play("▶  Play S${episode.season} E${episode.episode}", 180) { navigate { showSources(episode) } }; streams(episode) }
        else if (flix) playTarget(result)?.let { (target, _) ->
            // On a show page both follow the episode focused in the row below (see episodeFocused).
            episodeTarget = target; episodeActions = actions
            play("▶  Play S${target.season} E${target.episode}", 200) {
                navigate { showSources(episodeTarget ?: target) }
            }
            detailsPlayButton = actions.getChildAt(actions.childCount - 1) as? TextView
            actions.addView(button("☰  Streams") { navigate { showSources(episodeTarget ?: target, manual = true) } }
                .apply { gravity = Gravity.CENTER; tag = "details-streams" }, margin(140, 44, right = 12))
        }
        // The flix details page already shows the full synopsis.
        if (result.description.isNotBlank() && !flix) actions.addView(button("Synopsis") {
            synopsisDialog(result.title, result.description)
        }, margin(110, 44, right = 8))
        val tracker = weTrakrClient()
        if (tracker.connected()) {
            actions.addView(button("Manage  ▾") { mediaManagement(result, episode) }.apply {
                gravity = Gravity.CENTER; tag = "details-manage"
            }, margin(150, 44, right = 8))
        }
        if (!publicPreview && store.get(TMDB_KEY).isNotBlank()) actions.addView(button("Similar titles  ›") {
            navigate { similarTitlesPage(result) }
        }.apply { gravity = Gravity.CENTER; tag = "details-similar" }, margin(170, 44, right = 8))
        if (DeviceLayout.phone) fitActionsToPhone(actions)
    }

    /** One menu preserves the existing tracker actions and confirmations without crowding details. */
    private fun mediaManagement(result: MediaItemData, episode: PlaybackIdentity?) {
        val identity = PlaybackIdentity(result.imdbId, result.type, result.title)
        val tracker = weTrakrClient()
        if (!tracker.connected()) return
        val options = mutableListOf<Pair<String, () -> Unit>>(
            "Add to watchlist" to { load(generation, { tracker.setWatchlist(identity, true) }) { toast("Added to your ${tracker.name} watchlist") } },
            "Remove from watchlist" to { confirm("Remove ${result.title} from your ${tracker.name} watchlist?") {
                load(generation, { tracker.setWatchlist(identity, false) }) {
                    removeFromListViews("watchlist:${identity.type}", identity); toast("Removed from your ${tracker.name} watchlist")
                }
            } }
        )
        if (result.type == "movie" || episode != null) options += "Mark watched" to {
            confirm("Mark ${result.title}${episode?.let { " S${it.season} E${it.episode}" }.orEmpty()} as watched?") {
                load(generation, { tracker.markWatched(episode ?: identity) }) {
                    hideContinue(identityKey(episode ?: identity)); markedWatched(episode ?: identity); toast("${tracker.name} history updated")
                }
            }
        }
        if (tracker.supportsLists) options += "Remove from playlist" to {
                read(generation, { tracker.lists() }) { lists ->
                    if (lists.isEmpty()) toast("No personal playlists on ${tracker.name}") else AlertDialog.Builder(this).setTitle("Choose your playlist")
                        .setItems(lists.map { it.second }.toTypedArray()) { _, index ->
                            confirm("Remove ${result.title} from ${lists[index].second}?") {
                                load(generation, { tracker.removeListItem(lists[index].first, identity) }) { removeFromListViews("playlist:${lists[index].first}", identity); toast("Removed from ${lists[index].second}") }
                            }
                        }.setNegativeButton("Cancel", null).show()
                }
        }
        holdMenu(result.title, options)
    }

    /**
     * Phone: the details buttons fit the screen instead of running off its edge. Play and Streams share the first
     * row; the rest (watchlist, watched, playlists) share the rows below, three to a row.
     */
    private fun fitActionsToPhone(actions: LinearLayout) {
        val scroll = actions.parent as? View ?: return
        val host = scroll.parent as? LinearLayout ?: return
        val index = host.indexOfChild(scroll)
        val buttons = (0 until actions.childCount).map(actions::getChildAt)
        actions.removeAllViews()
        val (main, rest) = buttons.partition { view -> (view as? TextView)?.text?.let { it.startsWith("▶") || it.contains("Streams") } == true }
        val box = column()
        fun line(views: List<View>) {
            if (views.isEmpty()) return
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            views.forEachIndexed { position, view ->
                (view as? TextView)?.apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; gravity = Gravity.CENTER }
                row.addView(view, LinearLayout.LayoutParams(0, dp(44), 1f).apply { if (position < views.lastIndex) rightMargin = dp(8) })
            }
            box.addView(row, margin(-1, -2, bottom = 8))
        }
        line(main); rest.chunked(3).forEach(::line)
        host.removeView(scroll); host.addView(box, index, margin(-1, -2))
    }

    private val castCache = LruCache<String, List<TmdbCatalog.CastMember>>(60)

    /**
     * Up to 7 actors under a title's details, from TMDB (needs the viewer's TMDB key; hidden without one). Loaded after
     * the page shows so it never slows it down. Its room is kept from the start, so it shows on the first screen under
     * the buttons and the details don't move when it arrives; with no cast the room closes.
     */
    private fun castRow(id: Int, result: MediaItemData, then: () -> Unit = {}) {
        val key = if (publicPreview) "" else store.get(TMDB_KEY)
        if (key.isBlank() || !result.imdbId.startsWith("tt")) { then(); return }
        val round = roundCast()
        val slot = column().apply { minimumHeight = dp(when {
            round -> if (DeviceLayout.phone) ROUND_CAST_ROW_PHONE else ROUND_CAST_ROW
            else -> if (DeviceLayout.phone) CAST_ROW_HEIGHT_PHONE else CAST_ROW_HEIGHT
        }) }
        body.addView(slot, margin(-1, -2))
        fun show(cast: List<TmdbCatalog.CastMember>) {
            if (id != generation || !slot.isAttachedToWindow) return
            if (cast.isEmpty()) { slot.visibility = View.GONE; return }
            slot.addView(text("Cast", theme.layout.sectionTitleSizeSp, ink, true), margin(-1, -2, top = 10, bottom = 4))
            val row = horizontalRow(slot)
            // The phone shows smaller cards, four and a half at a time, so the row clearly scrolls for more.
            val width = if (round) (if (DeviceLayout.phone) ROUND_CAST_WIDTH_PHONE else ROUND_CAST_WIDTH)
                else if (DeviceLayout.phone) CAST_CARD_WIDTH_PHONE else CAST_CARD_WIDTH
            cast.forEach { member -> row.addView(castCard(member, round), margin(width, -2, right = if (DeviceLayout.phone) 8 else 12)) }
        }
        val cacheKey = detailsKey(result)
        castCache.get(cacheKey)?.let { show(it); then(); return }
        val region = tmdbRegion()
        worker.execute {
            val cast = runCatching { TmdbCatalog(this, key, region).cast(result.imdbId, result.type) }.getOrNull()
            runOnUiThread { if (isFinishing || isDestroyed) return@runOnUiThread; if (cast != null) castCache.put(cacheKey, cast); show(cast.orEmpty()); then() }
        }
    }

    private fun oval(color: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }

    private fun roundCast() = store.get(CAST_STYLE) == "round"

    /**
     * Large portrait cards keep faces recognizable on phones and TVs; [round] is the 0.3.19 round portrait with a
     * one-line name (Settings → Home & appearance → Cast cards). Either opens the actor's page.
     */
    /** [photoLoads] collects the portrait download instead of starting it (the crew row loads them when it nears the screen). */
    private fun castCard(member: TmdbCatalog.CastMember, round: Boolean = roundCast(), photoLoads: MutableList<() -> Unit>? = null): View {
        val size = dp(when {
            round -> if (DeviceLayout.phone) ROUND_CAST_PHOTO_PHONE else ROUND_CAST_PHOTO
            else -> if (DeviceLayout.phone) CAST_PHOTO_PHONE else CAST_PHOTO
        })
        val portraitHeight = if (round) size else dp(if (DeviceLayout.phone) 128 else 140)
        val card = LinearLayout(this).apply {
            // Room above the portrait: the focused one grows a little with its white ring, and the row clips at its top.
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(0, dp(10), 0, dp(4))
            // The grown ring reaches into that padding: draw it there instead of cutting it at the padding's edge.
            clipToPadding = false; clipChildren = false
            isFocusable = true; isFocusableInTouchMode = DeviceLayout.tv; isClickable = true; tag = "${if (member.crew) "crew" else "cast"}:${member.personId}"
            contentDescription = if (member.character.isBlank()) member.name else if (member.crew) "${member.name}, ${member.character}" else "${member.name} as ${member.character}"
        }
        val ring = FrameLayout(this).apply { setPadding(dp(3), dp(3), dp(3), dp(3)) }
        val photo = FixedSizeImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = if (round) oval(panel) else rounded(panel, if (DeviceLayout.phone) 8 else 10)
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND; clipToOutline = true
        }
        ring.addView(photo, FrameLayout.LayoutParams(size, portraitHeight))
        if (member.photo.isNotBlank()) { if (photoLoads != null) photoLoads += { image(photo, member.photo) } else image(photo, member.photo) }
        else ring.addView(text(member.name.split(' ').filter(String::isNotBlank).take(2).joinToString("") { it.take(1).uppercase() }, 26, muted, true)
            .apply { gravity = Gravity.CENTER }, FrameLayout.LayoutParams(size, portraitHeight).apply { gravity = Gravity.CENTER })
        card.addView(ring, LinearLayout.LayoutParams(-2, -2))
        val name = text(member.name, if (round || DeviceLayout.phone) 12 else 14, ink, true).apply {
            gravity = Gravity.CENTER; ellipsize = TextUtils.TruncateAt.END
            if (round) maxLines = 1 else { maxLines = 2; minHeight = dp(if (DeviceLayout.phone) 30 else 34) }
        }
        card.addView(name, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        if (member.character.isNotBlank()) card.addView(text(member.character, if (round || DeviceLayout.phone) 11 else 12, muted).apply {
            gravity = Gravity.CENTER; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(3) })
        card.setOnFocusChangeListener { _, focused ->
            ring.background = if (!focused) null else if (round) oval(Color.WHITE) else rounded(Color.WHITE, if (DeviceLayout.phone) 11 else 13)
            val grow = if (round) 1.06f else 1.04f
            ring.animate().scaleX(if (focused) grow else 1f).scaleY(if (focused) grow else 1f).setDuration(120).start()
        }
        card.setOnClickListener { navigate { personPage(member) } }
        return card
    }

    private data class TimedDetails<T>(val value: T, val at: Long = android.os.SystemClock.elapsedRealtime())
    private val discoveryCache = LruCache<String, TimedDetails<TmdbCatalog.DetailExtras>>(40)

    /** Optional TMDB discovery alongside the title's existing genres and cast. */
    /** Lays out the genres now; returns the keywords and About fetch for the caller to start once the cast is in. */
    private fun detailsDiscovery(id: Int, item: MediaItemData): () -> Unit {
        val key = if (publicPreview) "" else store.get(TMDB_KEY)
        val host = column().apply { tag = "details-discovery" }
        body.addView(host, margin(-1, -2, bottom = 16))
        val available = if (key.isNotBlank()) TmdbCatalog.discoverGenres(item.type).keys else CatalogApi.discoverGenres(item.type)
        val genres = item.genres.distinct().mapNotNull { genre ->
            val aliases = when (genre.lowercase()) {
                "sci-fi", "science fiction" -> listOf("Science Fiction", "Sci-Fi & Fantasy", "Sci-Fi")
                "fantasy" -> listOf("Fantasy", "Sci-Fi & Fantasy")
                "action", "adventure" -> listOf(genre, "Action & Adventure")
                else -> listOf(genre)
            }
            val filter = aliases.firstNotNullOfOrNull { alias -> available.firstOrNull { it.equals(alias, true) } } ?: return@mapNotNull null
            genre to { navigate { catalogPage(item.type, genre = filter, sort = if (key.isNotBlank()) TmdbCatalog.SORT_POPULAR else CatalogApi.SORT_POPULAR) } }
        }
        if (genres.isNotEmpty()) {
            host.addView(text("Genres", theme.layout.sectionTitleSizeSp, ink, true), margin(-1, -2, top = 14, bottom = 8))
            topicChips(host, genres, "genre")
        }
        val topics = column()
        host.addView(topics)
        fun renderExtra(extra: TmdbCatalog.DetailExtras) {
            topics.removeAllViews()
            if (extra.keywords.isNotEmpty()) {
                topics.addView(text("Keywords", theme.layout.sectionTitleSizeSp, ink, true), margin(-1, -2, top = 14, bottom = 8))
                topicChips(topics, extra.keywords.map { topic -> topic.name to { navigate { keywordPage(item.type, topic) } } }, "keyword")
            }
            if (extra.crew.isNotEmpty()) {
                topics.addView(text("Crew", theme.layout.sectionTitleSizeSp, ink, true), margin(-1, -2, top = 18, bottom = 6))
                val row = horizontalRow(topics).apply { tag = "details-crew" }
                // Crew portraits wait until the row nears the screen, so they never slow the cast photos above.
                val loads = mutableListOf<() -> Unit>()
                // Same card style as the cast (Settings → Cast & crew cards): large portraits or round.
                val round = roundCast()
                val width = if (round) (if (DeviceLayout.phone) ROUND_CAST_WIDTH_PHONE else ROUND_CAST_WIDTH)
                    else if (DeviceLayout.phone) CAST_CARD_WIDTH_PHONE else CAST_CARD_WIDTH
                extra.crew.forEach { member -> row.addView(castCard(member, round, photoLoads = loads), margin(width, -2, right = if (DeviceLayout.phone) 8 else 12)) }
                loadWhenNear(row, loads)
            }
            if (extra.facts.isNotEmpty()) aboutSection(topics, extra.facts)
        }
        if (key.isBlank() || !item.imdbId.matches(Regex("tt\\d{5,12}"))) return {}
        var started = false
        return start@{
            if (started) return@start
            started = true
            val region = tmdbRegion(); val fullKey = "${key.hashCode()}:$region:${detailsKey(item)}"; val cacheKey = "keywords:$fullKey"
            // A full result cached by the Similar titles page already holds the keywords.
            val cached = (discoveryCache.get(fullKey) ?: discoveryCache.get(cacheKey))
                ?.takeIf { android.os.SystemClock.elapsedRealtime() - it.at < 6 * 60 * 60_000L }
            if (cached != null) renderExtra(cached.value) else worker.execute {
                val extra = runCatching { TmdbCatalog(this, key, region).detailExtras(item.imdbId, item.type, withSimilar = false) }.getOrNull()
                runOnUiThread {
                    if (!isDestroyed && id == generation && host.isAttachedToWindow && extra != null) {
                        discoveryCache.put(cacheKey, TimedDetails(extra)); renderExtra(extra)
                    }
                }
            }
        }
    }

    private val imdbTitleScores = LruCache<String, TimedDetails<ImdbRatings.Score>>(80)
    private val imdbSeasonScores = LruCache<String, TimedDetails<ImdbRatings.SeasonScores>>(60)
    private fun <T> TimedDetails<T>?.fresh(): T? = this?.takeIf { android.os.SystemClock.elapsedRealtime() - it.at < 6 * 60 * 60_000L }?.value
    private val imdbIdPattern = Regex("tt\\d{5,12}")

    /** IMDb's yellow mark, then the score and a muted vote count; hidden until there is a score. */
    private fun imdbChip() = LinearLayout(this).apply {
        tag = "imdb-chip"; gravity = Gravity.CENTER_VERTICAL; visibility = View.GONE
        background = rounded(withAlpha(Color.BLACK, 110), 4, withAlpha(IMDB_YELLOW, 150))
        setPadding(dp(2), dp(2), dp(8), dp(2))
        addView(text("IMDb", 11, Color.BLACK, true).apply {
            setPadding(dp(5), dp(1), dp(5), dp(1)); background = rounded(IMDB_YELLOW, 3)
        }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(7) })
        addView(text("", 13, Color.WHITE, true).apply { tag = "imdb-score" })
        addView(text("", 11, muted).apply { tag = "imdb-votes" }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(6) })
    }

    private fun paintImdb(chip: View, score: String, votes: String) {
        chip.findViewWithTag<TextView>("imdb-score")?.text = score
        chip.findViewWithTag<TextView>("imdb-votes")?.apply { text = votes; visibility = if (votes.isBlank()) View.GONE else View.VISIBLE }
        chip.contentDescription = "IMDb $score" + if (votes.isBlank()) "" else ", $votes votes"
        chip.visibility = if (score.isBlank()) View.GONE else View.VISIBLE
    }

    /** The title's own score: shown now, and again whenever focus is not on an episode that has one. */
    private fun setImdbBase(chip: View, score: String, votes: String) {
        chip.setTag(R.id.pov_imdb_base, score to votes)
        val target = episodeTarget
        val onEpisode = target != null && body.findViewWithTag<View>("episode:${target.imdbId}:${target.season}:${target.episode}")?.hasFocus() == true
        if (!onEpisode || target?.let(::cachedEpisodeScore) == null) paintImdb(chip, score, votes)
    }

    /** On a show page the chip near the title follows the focused episode, as the rest of the header does. */
    private fun paintFocusedImdb(identity: PlaybackIdentity) {
        val chip = body.findViewWithTag<View>("imdb-chip") ?: return
        val episode = cachedEpisodeScore(identity)
        if (episode != null) paintImdb(chip, episode.label, episode.votesLabel)
        else (chip.getTag(R.id.pov_imdb_base) as? Pair<*, *>)?.let { (score, votes) -> paintImdb(chip, score as String, votes as String) }
    }

    private fun cachedEpisodeScore(identity: PlaybackIdentity): ImdbRatings.Score? =
        imdbSeasonScores.get("${identity.imdbId}:${identity.season}").fresh()?.episodes?.get(identity.episode)

    /** A small yellow star and score beside an episode's number; filled in when the season's scores arrive. */
    private fun episodeScoreView(identity: PlaybackIdentity): TextView = text("", 11, IMDB_YELLOW, true).apply {
        tag = "imdb-ep:${identity.imdbId}:${identity.season}:${identity.episode}"
        val score = cachedEpisodeScore(identity)
        if (score != null) { text = "★ ${score.label}"; contentDescription = "IMDb ${score.label}" } else visibility = View.GONE
    }

    private fun loadTitleImdb(item: MediaItemData) {
        if (publicPreview || !item.imdbId.matches(imdbIdPattern)) return
        fun show(score: ImdbRatings.Score) { body.findViewWithTag<View>("imdb-chip")?.let { setImdbBase(it, score.label, score.votesLabel) } }
        imdbTitleScores.get(item.imdbId).fresh()?.let { show(it); return }
        val page = generation
        imdbWorker.execute {
            val score = ImdbRatings().title(item.imdbId)
            runOnUiThread {
                if (isDestroyed || score == null) return@runOnUiThread
                imdbTitleScores.put(item.imdbId, TimedDetails(score))
                if (page == generation) show(score)
            }
        }
    }

    /** One request brings the show's live score and every episode score of [season]. Specials have no IMDb season. */
    private fun loadSeasonImdb(show: MediaItemData, season: Int) {
        if (publicPreview || !show.imdbId.matches(imdbIdPattern)) return
        if (season <= 0) { loadTitleImdb(show); return }
        val key = "${show.imdbId}:$season"
        imdbSeasonScores.get(key).fresh()?.let { applySeasonImdb(show, season, it); return }
        val page = generation
        imdbWorker.execute {
            val scores = ImdbRatings().season(show.imdbId, season)
            runOnUiThread {
                if (isDestroyed || scores == null) return@runOnUiThread
                imdbSeasonScores.put(key, TimedDetails(scores))
                scores.title?.let { imdbTitleScores.put(show.imdbId, TimedDetails(it)) }
                if (page == generation) applySeasonImdb(show, season, scores)
            }
        }
    }

    private fun applySeasonImdb(show: MediaItemData, season: Int, scores: ImdbRatings.SeasonScores) {
        val prefix = "${show.imdbId}:$season:"
        fun visit(view: View) {
            val tag = view.tag as? String
            if (view is TextView && tag?.startsWith("imdb-ep:$prefix") == true) {
                scores.episodes[tag.substringAfterLast(':').toIntOrNull()]?.let {
                    view.text = "★ ${it.label}"; view.contentDescription = "IMDb ${it.label}"; view.visibility = View.VISIBLE
                }
            }
            (view.getTag(R.id.pov_imdb_text) as? Pair<*, *>)?.let { (key, base) ->
                if (view is TextView && key is String && key.startsWith(prefix))
                    scores.episodes[key.substringAfterLast(':').toIntOrNull()]?.let { view.text = "$base  ·  ★ ${it.label}" }
            }
            if (view is TextView && tag?.startsWith("imdb-date:$prefix") == true) {
                scores.airDates[tag.substringAfterLast(':').toIntOrNull()]?.let { date ->
                    view.text = listOf(view.getTag(R.id.pov_episode_number) as? String, EpisodePresentation.shortAirDate(date))
                        .filterNot { it.isNullOrBlank() }.joinToString("  ·  ")
                }
            }
            if (view is ViewGroup) (0 until view.childCount).forEach { visit(view.getChildAt(it)) }
        }
        visit(body)
        val target = episodeTarget; val episode = focusedEpisode
        if (target != null && episode != null && target.imdbId == show.imdbId && target.season == season) paintEpisodeDate(target, episode)
        val chip = body.findViewWithTag<View>("imdb-chip") ?: return
        scores.title?.let { setImdbBase(chip, it.label, it.votesLabel) }
        episodeTarget?.takeIf { it.imdbId == show.imdbId && it.season == season }
            ?.takeIf { body.findViewWithTag<View>("episode:${it.imdbId}:${it.season}:${it.episode}")?.hasFocus() == true }
            ?.let(::paintFocusedImdb)
    }


    /**
     * The page's closing facts (director or creators, dates, runtime, studio, money) in a label-over-value grid,
     * three across on TV and two on the phone. On TV the block takes focus as one quiet target, so Down from the
     * keywords scrolls it into view.
     */
    private fun aboutSection(host: LinearLayout, facts: List<Pair<String, String>>) {
        host.addView(text("About", theme.layout.sectionTitleSizeSp, ink, true), margin(-1, -2, top = 18, bottom = 6))
        val panel = column().apply {
            tag = "details-about"; setPadding(dp(12), dp(10), dp(12), dp(4))
            if (DeviceLayout.tv) {
                isFocusable = true; isFocusableInTouchMode = true
                contentDescription = facts.joinToString(". ") { "${it.first}: ${it.second}" }
                setOnFocusChangeListener { view, focused ->
                    view.background = if (focused) rounded(withAlpha(Color.WHITE, 14), 10, withAlpha(Color.WHITE, 90)) else null
                }
            }
        }
        val across = if (DeviceLayout.phone) 2 else 3
        facts.chunked(across).forEach { line ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            line.forEach { (label, value) ->
                val cell = column()
                cell.addView(text(label, 12, muted))
                cell.addView(text(value, 14, ink).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }, margin(-1, -2, top = 2))
                row.addView(cell, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(16) })
            }
            // Keep the last row's cells the same width as the rows above.
            repeat(across - line.size) { row.addView(View(this), LinearLayout.LayoutParams(0, 0, 1f)) }
            panel.addView(row, margin(-1, -2, bottom = 12))
        }
        host.addView(panel, margin(-1, -2, bottom = 8))
    }

    private fun similarTitlesPage(item: MediaItemData, loaded: List<MediaItemData>? = null, nextPage: Int? = 1, tmdbId: Long = 0) {
        current = { similarTitlesPage(item, loaded, nextPage, tmdbId) }
        val id = page("Similar titles", "Related to ${item.title}")
        val key = store.get(TMDB_KEY); val region = tmdbRegion()
        if (key.isBlank()) { body.addView(text("Connect TMDB in Settings to find similar titles.", 14, muted)); return }
        val cacheKey = "${key.hashCode()}:$region:${detailsKey(item)}"
        val grid = column(); val controls = column()
        body.addView(grid); body.addView(controls)
        val items = loaded.orEmpty().toMutableList()
        var cursor = nextPage; var knownId = tmdbId
        fun remember() { val snapshot = items.toList(); val page = cursor; val ref = knownId
            current = { similarTitlesPage(item, snapshot, page, ref) }
        }
        fun moreButton() {
            controls.removeAllViews()
            if (cursor == null) {
                controls.addView(text("All available similar titles loaded.", 13, muted), margin(-1, -2, top = 12)); return
            }
            val more = button("More titles  ›") {}.apply { tag = "similar-more" }
            controls.addView(more, margin(if (DeviceLayout.phone) -1 else 180, 44, top = 16))
            more.setOnClickListener {
                val start = cursor ?: return@setOnClickListener
                if (!more.isEnabled) return@setOnClickListener
                more.isEnabled = false; more.text = "Loading…"
                val excluded = items.map { it.imdbId }.toSet() + item.imdbId
                val scroll = scroller.scrollY
                worker.execute {
                    val result = runCatching {
                        val api = TmdbCatalog(this, key, region)
                        var page: Int? = start
                        var batch = TmdbCatalog.RelatedPage(emptyList(), page)
                        // Skip a few duplicate-only or unmappable pages without requiring repeated presses.
                        var attempts = 0
                        while (page != null && batch.items.isEmpty() && attempts++ < 3) {
                            batch = api.relatedTitles(item.type, knownId, page, excluded); page = batch.nextPage
                        }
                        batch
                    }
                    runOnUiThread {
                        if (isDestroyed) return@runOnUiThread
                        more.isEnabled = true; more.text = "More titles  ›"
                        if (id != generation || !grid.isAttachedToWindow) return@runOnUiThread
                        result.fold(onSuccess = { batch ->
                            val additions = batch.items.filterNot { it.imdbId in excluded }.distinctBy { it.imdbId }
                            val firstRow = grid.childCount
                            if (additions.isNotEmpty()) { items += additions; mediaGrid(additions, host = grid) }
                            cursor = batch.nextPage; remember(); moreButton()
                            grid.post {
                                if (id != generation) return@post
                                if (DeviceLayout.tv) {
                                    val row = grid.getChildAt(if (additions.isNotEmpty()) firstRow else grid.childCount - 1) as? ViewGroup
                                    row?.getChildAt(if (additions.isNotEmpty()) 0 else row.childCount - 1)?.requestFocus()
                                    focusController.revealCurrent()
                                } else scroller.scrollTo(0, scroll)
                            }
                        }, onFailure = { toast("Could not load more titles. Please try again.") })
                    }
                }
            }
        }
        fun render(extra: TmdbCatalog.DetailExtras) {
            items.clear(); items += extra.similar.distinctBy { it.imdbId }
            knownId = extra.relatedTmdbId; cursor = if (extra.hasMoreRelated && knownId > 0) 1 else null
            if (items.isEmpty()) grid.addView(text("No related titles are available for this title yet.", 14, muted), margin(-1, -2, top = 16))
            else mediaGrid(items, host = grid)
            remember(); moreButton()
        }
        if (loaded != null) { mediaGrid(items, host = grid); moreButton(); remember(); focusAfterRender(id); return }
        val cached = discoveryCache.get(cacheKey)?.takeIf { android.os.SystemClock.elapsedRealtime() - it.at < 6 * 60 * 60_000L }
        if (cached != null) { render(cached.value); focusAfterRender(id) }
        else read(id, { TmdbCatalog(this, key, region).detailExtras(item.imdbId, item.type) }) { extra ->
            discoveryCache.put(cacheKey, TimedDetails(extra)); render(extra)
        }
    }

    /** Wrap at the actual screen width, with touch-sized chips and normal D-pad focus targets. */
    private fun topicChips(host: LinearLayout, topics: List<Pair<String, () -> Unit>>, kind: String) {
        val width = dp(DeviceLayout.canvasWidth - theme.layout.railWidthDp - theme.layout.contentInsetDp * 2 - 8)
        var row = LinearLayout(this); var used = 0
        host.addView(row, margin(-1, -2, bottom = 6))
        topics.forEach { (label, action) ->
            val chip = button(label, action).apply {
                tag = "topic:$kind:$label"; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                background = rounded(withAlpha(Color.WHITE, 18), 18, withAlpha(Color.WHITE, 70))
                setOnFocusChangeListener { _, focused ->
                    background = rounded(if (focused) Color.WHITE else withAlpha(Color.WHITE, 18), 18, withAlpha(Color.WHITE, 70))
                    setTextColor(if (focused) Color.BLACK else ink)
                }
            }
            chip.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.AT_MOST), View.MeasureSpec.makeMeasureSpec(dp(44), View.MeasureSpec.EXACTLY))
            val chipWidth = chip.measuredWidth.coerceAtMost(width)
            if (used > 0 && used + chipWidth + dp(8) > width) {
                row = LinearLayout(this); host.addView(row, margin(-1, -2, bottom = 6)); used = 0
            }
            row.addView(chip, LinearLayout.LayoutParams(chipWidth, dp(44)).apply { rightMargin = dp(8) })
            used += chipWidth + dp(8)
        }
    }

    private fun keywordPage(type: String, topic: TmdbCatalog.Topic, number: Int = 1) {
        current = { keywordPage(type, topic, number) }
        val id = page(topic.name, "${if (type == "movie") "Movies" else "TV shows"} with this keyword · TMDB")
        val key = store.get(TMDB_KEY)
        if (key.isBlank()) { body.addView(text("Connect TMDB in Settings to browse keywords.", 14, muted)); return }
        val region = tmdbRegion()
        read(id, { TmdbCatalog(this, key, region).keywordTitles(type, topic.id, number) }) { items ->
            if (items.isEmpty()) body.addView(text(if (number == 1) "No titles found for this keyword." else "No more titles found for this keyword.", 14, muted), margin(-1, -2, top = 16))
            mediaGrid(items)
            if (number > 1) body.addView(button("Previous page") { keywordPage(type, topic, number - 1) }, margin(180, 44, top = 12))
            if (items.isNotEmpty() && number < 500) body.addView(button("More titles  ›") { keywordPage(type, topic, number + 1) }, margin(180, 44, top = 12))
        }
    }

    /** An actor's page: their portrait and name, then a row of their films and a row of their shows. */
    private fun personPage(member: TmdbCatalog.CastMember) {
        current = { personPage(member) }
        val id = page("")
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val size = dp(if (DeviceLayout.phone) 72 else 96)
        val photo = FixedSizeImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP; background = oval(panel)
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND; clipToOutline = true
        }
        header.addView(photo, LinearLayout.LayoutParams(size, size))
        if (member.photo.isNotBlank()) image(photo, member.photo)
        val names = column()
        names.addView(text(member.name, if (DeviceLayout.phone) 22 else 28, ink, true))
        names.addView(text(if (member.crew) member.character else "Movies and shows", 13, muted), margin(-1, -2, top = 6))
        header.addView(names, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(18) })
        body.addView(header, margin(-1, -2, bottom = 8))
        val key = if (publicPreview) "" else store.get(TMDB_KEY)
        load(id, { filmography(key, member.personId, PERSON_ROW_TITLES, crew = member.crew) }) { result ->
            if (result.movies.isEmpty() && result.shows.isEmpty()) body.addView(text("TMDB lists no films or shows for ${member.name}.", 14, muted), margin(-1, -2, top = 12))
            // The rows show the most popular; See all loads the actor's whole list.
            if (flix && DeviceLayout.phone) personColumns(member, result.movies, result.shows)
            else {
                mediaRow("Movies", result.movies) { navigate { personGrid(member, "movie") } }
                mediaRow("Shows", result.shows) { navigate { personGrid(member, "series") } }
            }
            focusAfterRender(id)
        }
    }

    /**
     * Phone: the actor's movies in the left column and shows in the right, scrolling down with the page, each ending
     * in See all (the full grid). Sideways rows read oddly on a narrow screen.
     */
    private fun personColumns(member: TmdbCatalog.CastMember, movies: List<MediaItemData>, shows: List<MediaItemData>) {
        val width = DeviceLayout.canvasWidth - theme.layout.railWidthDp - theme.layout.contentInsetDp * 2
        val gap = theme.layout.cardGapDp
        val columnWidth = (width - 6 - gap) / 2
        val cardHeight = columnWidth * 9 / 16
        val columns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; tag = "person-columns" }
        fun addColumn(title: String, type: String, items: List<MediaItemData>, last: Boolean) {
            val host = column()
            host.addView(text(title, theme.layout.sectionTitleSizeSp, ink, true), margin(-1, -2, top = 14, bottom = 6))
            if (items.isEmpty()) host.addView(text("None listed", 13, muted), margin(-1, -2, top = 4))
            items.take(PERSON_COLUMN_TITLES).forEach { item ->
                host.addView(card(item).apply { tag = "person:$type:${item.imdbId}" }, margin(-1, cardHeight, bottom = gap))
            }
            if (items.isNotEmpty()) host.addView(text("See all  ›", 13, ink, true).apply {
                gravity = Gravity.CENTER; tag = "person-see-all:$type"; contentDescription = "See all $title"
                isFocusable = true; isClickable = true; background = rounded(withAlpha(Color.WHITE, 22), theme.layout.cornerRadiusDp)
                setOnClickListener { navigate { personGrid(member, type) } }
            }.pressable(theme.layout.cornerRadiusDp), margin(-1, 44, top = 2))
            columns.addView(host, LinearLayout.LayoutParams(0, -2, 1f).apply { if (!last) rightMargin = dp(gap) })
        }
        addColumn("Movies", "movie", movies, last = false)
        addColumn("Shows", "series", shows, last = true)
        body.addView(columns, margin(-1, -2))
    }

    private val filmographyCache = LruCache<String, TmdbCatalog.Filmography>(12)
    /** Call on a worker. Kept per actor so Back and See all don't fetch again. */
    private fun filmography(key: String, personId: Long, limit: Int, byRelease: Boolean = false, crew: Boolean = false): TmdbCatalog.Filmography {
        val cacheKey = "$personId:$limit:$byRelease:$crew"
        filmographyCache.get(cacheKey)?.let { return it }
        return TmdbCatalog(this, key, tmdbRegion()).filmography(personId, limit, byRelease, crew).also { filmographyCache.put(cacheKey, it) }
    }

    /** All of an actor's films (or shows), newest release first. */
    private fun personGrid(member: TmdbCatalog.CastMember, type: String) {
        current = { personGrid(member, type) }
        val id = page("${member.name} · ${if (type == "movie") "Movies" else "Shows"}")
        val key = if (publicPreview) "" else store.get(TMDB_KEY)
        load(id, { filmography(key, member.personId, Int.MAX_VALUE, byRelease = true, crew = member.crew) }) { result ->
            mediaGrid(if (type == "movie") result.movies else result.shows)
            focusAfterRender(id)
        }
    }

    private fun seasonPicker(result: MediaItemData) {
        val seasons = EpisodePresentation.seasons(result.episodes)
        if (seasons.isEmpty()) { body.addView(text("Episode information is currently unavailable.", 14, muted), margin(-1, -2, top = 18)); return }
        val selector = button("") { }
        val host = column()
        val tabs = mutableListOf<Pair<Int, TextView>>()
        var shown: Int? = null
        fun render(season: Int) {
            val previous = shown; shown = season
            selectedSeasons[result.imdbId] = season
            val label = if (season == 0) "Specials & extras" else "Season $season"
            val episodes = result.episodes.filter { it.season == season }.sortedBy { it.episode }
            selector.text = "$label · ${episodes.size} episodes  ▾"; selector.contentDescription = selector.text; selector.tag = "season:${result.imdbId}"
            host.removeAllViews()
            if (flix) tabs.forEach { (value, tab) -> paintSeasonTab(tab, value == season, tab.hasFocus()) }
            else host.addView(text(label, 18, ink, true), margin(-1, -2, top = 16, bottom = 10))
            // TV: the sideways row of wide cards. Phone: the vertical list (still, title, date, synopsis) suits scrolling.
            if (flix && DeviceLayout.phone) episodes.forEach { episode ->
                host.addView(episodeRow(PlaybackIdentity(result.imdbId, "series", result.title, episode.season, episode.episode), episode, result), margin(-1, -2, bottom = 8))
            }
            else if (flix) host.addView(episodeStrip(result, episodes.map { episode ->
                Triple(PlaybackIdentity(result.imdbId, "series", result.title, episode.season, episode.episode), episode, "S${episode.season} · E${episode.episode}")
            }, episodeTarget), margin(-1, -2))
            else episodes.forEach { episode ->
                val identity = PlaybackIdentity(result.imdbId, "series", result.title, episode.season, episode.episode)
                host.addView(episodeButton(identity, episode.title, episode.released, EpisodePresentation.shortAirDate(airDate(identity, episode))), margin(-1, 62, bottom = 7))
            }
            loadSeasonImdb(result, season)
            // A later season slides in from the right, an earlier one from the left.
            if (flix && previous != null) {
                host.animate().cancel(); host.alpha = 0f; host.translationX = dp(if (season > previous) 36 else -36).toFloat()
                host.animate().alpha(1f).translationX(0f).setDuration(240).setInterpolator(EASE_OUT).start()
            }
            focusAfterRender(generation)
        }
        selector.setOnClickListener {
            val selected = selectedSeasons[result.imdbId]
            AlertDialog.Builder(this).setTitle("Choose a season").setSingleChoiceItems(seasons.map { season ->
                if (season == 0) "Specials & extras" else "Season $season"
            }.toTypedArray(), seasons.indexOf(selected).coerceAtLeast(0)) { dialog, index ->
                dialog.dismiss(); render(seasons[index]); selector.requestFocus()
            }.setNegativeButton("Cancel", null).show()
        }
        selector.setOnLongClickListener {
            selectedSeasons[result.imdbId]?.let { seasonWatchMenu(result, it) }; true
        }
        if (flix) {
            // The episode row comes first, right under the buttons (no heading, so whole cards fit on the first screen);
            // the seasons sit in a row of tabs beneath it.
            if (DeviceLayout.tv) body.addView(host, margin(-1, -2, top = 14))
            val row = horizontalRow()
            episodeTabs = row.parent as? View ?: row
            seasons.forEach { season ->
                val tab = text(if (season == 0) "Specials" else "Season $season", 13, ink, true).apply {
                    gravity = Gravity.CENTER; setPadding(dp(18), 0, dp(18), 0); isFocusable = true; isFocusableInTouchMode = DeviceLayout.tv; isClickable = true
                    tag = "season:${result.imdbId}:$season"; contentDescription = text
                }
                tab.setOnFocusChangeListener { view, focused -> paintSeasonTab(tab, selectedSeasons[result.imdbId] == season, focused)
                    view.animate().scaleX(if (focused) 1.06f else 1f).scaleY(if (focused) 1.06f else 1f).setDuration(120).start() }
                tab.setOnClickListener { if (selectedSeasons[result.imdbId] != season) render(season) }
                tab.setOnLongClickListener { seasonWatchMenu(result, season); true }
                tabs += season to tab
                row.addView(tab, margin(-2, 36, right = 8))
            }
            // On the phone the season tabs sit above the episode list.
            if (DeviceLayout.phone) body.addView(host, margin(-1, -2, top = 8))
        } else { body.addView(selector, margin(370, 44, top = 15)); body.addView(host) }
        render(EpisodePresentation.selectedSeason(result.episodes, selectedSeasons[result.imdbId])!!)
    }

    private fun seasonWatchMenu(show: MediaItemData, season: Int) {
        val tracker = weTrakrClient()
        if (!tracker.connected()) { toast("Connect your tracking service in Settings to mark a season watched."); return }
        val label = if (season == 0) "Specials" else "Season $season"
        holdMenu("${show.title} · $label", listOf("Mark season watched" to {
            val pending = SeasonWatchedSelection.pending(show, season, watched)
            if (pending.isEmpty()) toast("No unwatched available episodes in $label")
            else confirm("Mark ${pending.size} ${if (pending.size == 1) "episode" else "episodes"} of ${show.title} · $label watched on ${tracker.name}? Future and already watched episodes are skipped.") {
                val identity = PlaybackIdentity(show.imdbId, "series", show.title)
                load(generation, { tracker.markSeasonWatched(identity, season, pending.map { it.episode }) }) {
                    watched = pending.fold(watched) { state, episode -> state.with(episode) }
                    val markers = pending.map(::identityKey).toSet()
                    fun hide(root: View) {
                        if (root.getTag(R.id.pov_continue_identity) in markers) root.visibility = View.GONE
                        if (root is ViewGroup) (0 until root.childCount).forEach { hide(root.getChildAt(it)) }
                    }
                    retainedRoots().forEach(::hide)
                    markers.forEach { remoteProgress.remove(it) }
                    refreshBadges(); scheduleContinueRefresh()
                    toast("$label marked watched on ${tracker.name}")
                }
            }
        }))
    }

    private var focusedEpisode: EpisodeData? = null

    /** The air date to show: IMDb's original date when this season's scores are in, else Cinemeta's. */
    private fun airDate(identity: PlaybackIdentity, episode: EpisodeData): java.time.LocalDate? =
        imdbSeasonScores.get("${identity.imdbId}:${identity.season}").fresh()?.airDates?.get(identity.episode)
            ?: EpisodePresentation.releaseDate(episode.released)

    /** A quiet line under the focused episode's title in the header: when it aired, or when it airs. */
    private fun paintEpisodeDate(identity: PlaybackIdentity, episode: EpisodeData) {
        val copy = body.findViewWithTag<LinearLayout>("feature-copy") ?: return
        val line = copy.findViewWithTag<TextView>("episode-line") ?: return
        val date = copy.findViewWithTag<TextView>("episode-date") ?: text("", 12, 0xFFD0D0D0.toInt()).apply {
            tag = "episode-date"; maxLines = 1
            setShadowLayer(dp(4).toFloat(), 0f, 0f, 0xB0000000.toInt())
        }.also { copy.addView(it, copy.indexOfChild(line) + 1, margin(-1, -2, top = 3)) }
        date.text = EpisodePresentation.airLine(airDate(identity, episode))
        date.visibility = if (date.text.isNullOrBlank()) View.GONE else View.VISIBLE
    }

    /** The episode the show page's Play and Streams buttons act on: the focused one in the episode row. */
    private var episodeTarget: PlaybackIdentity? = null
    /** Down from the show page's buttons lands on [episodeDownTarget] (the next episode), not the nearest card. */
    private var episodeActions: ViewGroup? = null
    private var episodeDownTarget: View? = null
    private var episodeTabs: View? = null

    /**
     * The row scrolls only until a focused card touches its edge; the card then grows and takes its outline, which would
     * be cut off. Keep a gap beside it on either side so the whole card always shows.
     */
    private fun keepCardInRow(card: View) {
        val scroll = card.parent?.parent as? HorizontalScrollView ?: return
        scroll.post {
            if (!card.isAttachedToWindow) return@post
            val gap = dp(24) + (card.width * 0.05f).toInt()
            val right = card.right + gap - scroll.width
            val left = card.left - gap
            when {
                right > scroll.scrollX -> scroll.smoothScrollTo(right, 0)
                left < scroll.scrollX -> scroll.smoothScrollTo(left.coerceAtLeast(0), 0)
            }
        }
    }

    /** With an episode focused, the season tabs beneath the row are scrolled into view too. */
    private fun revealEpisodeTabs() {
        val tabs = episodeTabs?.takeIf { it.isAttachedToWindow } ?: return
        body.post {
            if (!tabs.isAttachedToWindow) return@post
            val rect = android.graphics.Rect(); tabs.getDrawingRect(rect); body.offsetDescendantRectToMyCoords(tabs, rect)
            val needed = body.top + rect.bottom + dp(18) - scroller.height
            if (needed > scroller.scrollY) scroller.smoothScrollTo(0, needed)
        }
    }
    private var detailsPlayButton: TextView? = null

    /**
     * A show's episodes as a sideways row of wide cards (still, title, number). Focusing one updates the page header:
     * its number and title, its synopsis, and the Play / Streams targets. The row opens scrolled to [focusOn].
     */
    private fun episodeStrip(show: MediaItemData, items: List<Triple<PlaybackIdentity, EpisodeData, String>>, focusOn: PlaybackIdentity?): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false; clipToPadding = false; setPadding(dp(12), dp(10), dp(24), dp(12)) }
        val scroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; isFocusable = false; clipChildren = false; clipToPadding = false; addView(row) }
        val (width, height) = if (DeviceLayout.phone) 210 to 118 else 250 to 141
        items.forEach { (identity, episode, label) -> row.addView(episodeCard(identity, episode, show, label), margin(width, height, right = 14)) }
        // The next episode to watch (or the first): the row opens on it, the header shows it, and Down lands on it.
        val index = focusOn?.let { target -> items.indexOfFirst { it.first.season == target.season && it.first.episode == target.episode } } ?: -1
        if (index > 0) scroll.post { row.getChildAt(index)?.let { scroll.scrollTo((it.left - dp(12)).coerceAtLeast(0), 0) } }
        episodeDownTarget = row.getChildAt(index.coerceAtLeast(0))
        items.getOrNull(index.coerceAtLeast(0))?.let { (identity, episode, _) -> body.post { episodeFocused(identity, episode, show, animate = false) } }
        return scroll
    }

    private fun episodeCard(identity: PlaybackIdentity, episode: EpisodeData, show: MediaItemData, label: String): View {
        val future = EpisodePresentation.future(episode.released)
        val frame = FrameLayout(this).apply {
            isFocusable = true; isFocusableInTouchMode = DeviceLayout.tv; isClickable = true; clipChildren = false
            tag = "episode:${identity.imdbId}:${identity.season}:${identity.episode}"
            contentDescription = "$label. ${episode.title}" + if (future) ". Not yet released" else ""
        }
        val artFrame = FrameLayout(this).apply { background = rounded(panel, 8); clipToOutline = true }
        val art = FixedSizeImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        artFrame.addView(art, FrameLayout.LayoutParams(-1, -1))
        image(art, thumbnail(episode.thumbnail.ifBlank { backdropFor(show) }))
        artFrame.addView(View(this).apply { background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, 0xD9000000.toInt())) }, FrameLayout.LayoutParams(-1, -1))
        val copy = column().apply { setPadding(dp(10), 0, dp(10), dp(8)) }
        copy.addView(text(episode.title.ifBlank { label }, 13, Color.WHITE, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, margin(-1, -2))
        val labelLine = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        labelLine.addView(text(if (future) "$label · Not yet released" else label, 11, 0xFFD0D0D0.toInt()))
        if (!future) labelLine.addView(episodeScoreView(identity), LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
        copy.addView(labelLine, margin(-1, -2, top = 2))
        artFrame.addView(copy, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        frame.addView(artFrame, FrameLayout.LayoutParams(-1, -1))
        // The same watched ✓ as the episode list and posters, in the still's corner.
        watchedBadge("episode", identity.imdbId, identity.season, identity.episode)?.let {
            frame.addView(it, FrameLayout.LayoutParams(dp(20), dp(20), Gravity.TOP or Gravity.END).apply { topMargin = dp(7); rightMargin = dp(7) })
        }
        if (future) artFrame.alpha = 0.55f
        frame.setOnFocusChangeListener { view, focused ->
            val glide = posterGlide(view, radius = 8, scale = 1.05f)
            if (glide != null) glide.focusChanged(view, focused)
            view.animate().scaleX(if (focused) 1.05f else 1f).scaleY(if (focused) 1.05f else 1f).setDuration(130).start()
            if (focused) { episodeFocused(identity, episode, show); revealEpisodeTabs(); keepCardInRow(view) }
        }
        frame.setOnClickListener { if (future) toast("This episode has not been released yet") else navigate { showSources(identity) } }
        if (!future) frame.setOnLongClickListener { episodeMenu(identity, "S${identity.season} E${identity.episode}"); true }
        return frame.pressable(8)
    }

    /** The header follows the focused episode: its number and title, its synopsis, and what Play and Streams open. */
    private fun episodeFocused(identity: PlaybackIdentity, episode: EpisodeData, show: MediaItemData, animate: Boolean = true) {
        episodeTarget = identity
        val copy = body.findViewWithTag<LinearLayout>("feature-copy") ?: return
        val description = copy.findViewWithTag<TextView>("feature-description")
        val line = copy.findViewWithTag<TextView>("episode-line") ?: text("", 15, Color.WHITE, true).apply {
            tag = "episode-line"; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            setShadowLayer(dp(4).toFloat(), 0f, 0f, 0xB0000000.toInt())
        }.also { copy.addView(it, (if (description != null) copy.indexOfChild(description) else copy.childCount), margin(-1, -2, top = 10)) }
        line.text = "S${identity.season} · E${identity.episode}" + if (episode.title.isNotBlank()) "   ${episode.title}" else ""
        focusedEpisode = episode
        paintEpisodeDate(identity, episode)
        description?.text = episode.overview.ifBlank { show.description }
        if (animate) listOfNotNull<View>(line, description).forEach { view ->
            view.animate().cancel(); view.alpha = 0.35f; view.animate().alpha(1f).setDuration(140).start()
        }
        detailsPlayButton?.takeIf { it.isAttachedToWindow }?.text = "▶  Play S${identity.season} E${identity.episode}"
        paintFocusedImdb(identity)
    }

    private fun paintSeasonTab(tab: TextView, selected: Boolean, focused: Boolean) {
        tab.background = rounded(if (selected) Color.WHITE else withAlpha(Color.WHITE, if (focused) 70 else 24), 18)
        tab.setTextColor(if (selected) Color.BLACK else ink)
        tab.foreground = if (focused) FocusRingDrawable(Color.WHITE, dp(18).toFloat(), true, dp(1).toFloat()) else null
    }

    /** Flix episode list: a still from the episode beside its number, title and air date. */
    private fun episodeRow(identity: PlaybackIdentity, episode: EpisodeData, show: MediaItemData, label: String = "", note: String = ""): View {
        val tracker = weTrakrClient()
        val future = EpisodePresentation.future(episode.released)
        val number = label.ifBlank { if (identity.season == 0) "Special ${identity.episode}" else "Episode ${identity.episode}" }
        val detail = if (future) "Not yet released" else note.ifBlank { EpisodePresentation.shortAirDate(airDate(identity, episode)) }
        val row = LinearLayout(this).apply {
            gravity = Gravity.TOP; minimumHeight = dp(if (DeviceLayout.phone) 80 else 107); setPadding(dp(6), dp(6), dp(12), dp(6)); background = rounded(withAlpha(Color.WHITE, 14), 6)
            isFocusable = true; isFocusableInTouchMode = DeviceLayout.tv; isClickable = true
            tag = "episode:${identity.imdbId}:${identity.season}:${identity.episode}"
            contentDescription = "$number. ${episode.title}. $detail"
        }
        val still = FrameLayout(this).apply { background = rounded(panel, 4); clipToOutline = true }
        val art = FixedSizeImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        still.addView(art, FrameLayout.LayoutParams(-1, -1))
        // Stills use the priority pool so they never queue behind the Home page's thumbnails.
        image(art, episode.thumbnail.ifBlank { backdropFor(show) }, feature = true)
        if (future) still.addView(View(this).apply { setBackgroundColor(0x99000000.toInt()) }, FrameLayout.LayoutParams(-1, -1))
        watchedBadge("episode", identity.imdbId, identity.season, identity.episode)?.let {
            still.addView(it, FrameLayout.LayoutParams(dp(18), dp(18), Gravity.TOP or Gravity.END).apply { topMargin = dp(5); rightMargin = dp(5) })
        }
        if (DeviceLayout.phone) row.addView(still, LinearLayout.LayoutParams(dp(120), dp(68)))
        else row.addView(still, LinearLayout.LayoutParams(dp(168), dp(95)))
        val copy = column().apply { setPadding(dp(if (DeviceLayout.phone) 10 else 14), dp(2), 0, 0) }
        val numberLine = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        numberLine.addView(text(listOf(number, detail).filter(String::isNotBlank).joinToString("  ·  "), 11, muted, true).apply {
            if (!future && note.isBlank()) { tag = "imdb-date:${identity.imdbId}:${identity.season}:${identity.episode}"; setTag(R.id.pov_episode_number, number) }
        })
        if (!future) numberLine.addView(episodeScoreView(identity), LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
        copy.addView(numberLine)
        copy.addView(text(episode.title, 15, ink, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, margin(-1, -2, top = 4))
        // Every episode's synopsis is visible in the list, as in a TV guide.
        if (episode.overview.isNotBlank() && !future) copy.addView(text(episode.overview, 12, 0xFFD2D2D2.toInt()).apply {
            maxLines = if (DeviceLayout.phone) 2 else 3; ellipsize = TextUtils.TruncateAt.END; setLineSpacing(dp(2).toFloat(), 1f)
        }, margin(-1, -2, top = 5))
        row.addView(copy, LinearLayout.LayoutParams(0, -2, 1f))
        row.setOnFocusChangeListener { view, focused ->
            // One highlight glides up and down the episode list instead of each row lighting up on its own.
            listGlide(view)?.focusChanged(view, focused)
            FadeDrawable.foreground(still, if (focused) FocusRingDrawable(Color.WHITE, dp(4).toFloat(), true, dp(1).toFloat()) else null)
        }
        row.setOnClickListener { if (future) toast("This episode has not been released yet") else navigate { showSources(identity) } }
        row.pressable(6)
        if (!future) row.setOnLongClickListener { episodeMenu(identity, "S${identity.season} E${identity.episode}"); true }
        return row
    }

    private fun episodeButton(identity: PlaybackIdentity, title: String, released: String, detail: String): View {
        val tracker = weTrakrClient()
        val future = EpisodePresentation.future(released)
        val number = if (identity.season == 0) "Special ${identity.episode}" else "S${identity.season} · E${identity.episode}"
        val base = "$number   $title\n${if (future) "Not yet released" else detail}"
        return button(base) {
            if (future) toast("This episode has not been released yet") else navigate { showSources(identity) }
        }.apply {
            tag = "episode:${identity.imdbId}:${identity.season}:${identity.episode}"
            if (!future) {
                setTag(R.id.pov_imdb_text, "${identity.imdbId}:${identity.season}:${identity.episode}" to base)
                cachedEpisodeScore(identity)?.let { text = "$base  ·  ★ ${it.label}" }
            }
            contentDescription = "$number. $title. ${if (future) "Not yet released" else detail}"
            if (!future) setOnLongClickListener { episodeMenu(identity, number); true }
        }
    }

    /**
     * The link list for [identity]; [manual] (the Streams button) never auto-plays, whatever the Auto-play setting;
     * [autoplayNext] (the player's Next episode) always does, once.
     */
    private fun showSources(identity: PlaybackIdentity, manual: Boolean = false, autoplayNext: Boolean = false) {
        current = { showSources(identity, manual) }
        val suffix = if (identity.type == "series") "  ·  S${identity.season} E${identity.episode}" else ""
        val connected = DebridServices.connected(store)
        val order = connected.joinToString(" → ") { DebridServices.name(it) }
        // Without a debrid service, addons can still supply direct links; torrents are left out.
        val artShown = flix && heroBackdrop.visibility == View.VISIBLE
        val id = page(identity.title + suffix, if (connected.isEmpty()) "Direct links only. Torrent sources need a debrid service." else "Cached source priority: $order.")
        sourcesBackdrop(identity, keep = artShown)
        // The title's runtime (for each link's estimated bitrate) loads alongside the source search, which takes far
        // longer, so it is ready when the list appears however the sources were opened.
        val title = MediaItemData(identity.imdbId, identity.type, identity.title)
        val details = if (cachedDetails(title) != null) null else metadataWorker.submit(java.util.concurrent.Callable { runCatching { fetchDetails(title) }.getOrNull() })
        val preferences = SourceSearchPreferences(this)
        val chosen = preferences.mode(store.get(AUTOPLAY_FIRST) == "true")
        val search = LinkSearch(identity, id).apply {
            // A manual search never auto-plays; the player's next episode always does, straight away (it counted down already).
            autoplay = when { autoplayNext -> chosen.takeIf { it != SourceSearchPreferences.OFF } ?: SourceSearchPreferences.END; manual -> SourceSearchPreferences.OFF; else -> chosen }
            countdownSeconds = if (autoplayNext) 0 else SourceSearchPreferences.COUNTDOWN_SECONDS
        }
        activeSearch = search
        val status = text("Searching for links…", 13, muted); body.addView(status, margin(-1, -2, top = 12, bottom = 12))
        if (search.autoplay == SourceSearchPreferences.WAIT) countdownHandler.postDelayed({
            search.waitOver = true; if (search.rendered && generation == search.generation && !isDestroyed) renderSearch(search)
        }, preferences.waitSeconds * 1000L)
        val limit = preferences.timeLimitSeconds * 1000L
        // Search goal: the time limit only, a number of good links, best per quality full, or whichever comes first.
        val until = preferences.searchUntil(SourceSortPreferences(this).keepSearching)
        val stopAfter = if (until == SourceSearchPreferences.UNTIL_LINKS || until == SourceSearchPreferences.UNTIL_EITHER) preferences.goodLinksNeeded else 0
        val untilBest = until == SourceSearchPreferences.UNTIL_BEST || until == SourceSearchPreferences.UNTIL_EITHER
        worker.execute {
            try {
                val result = resolver.search(identity, limit, timing = { provider, ms -> preferences.record(provider.id, ms) },
                    ownLimitMs = { id -> preferences.ownLimit(id).takeIf { it > 0 }?.let { it * 1000L } }) { step ->
                    runOnUiThread { if (!isDestroyed) { search.take(step); scheduleRender(search) } }
                    val full = untilBest && !step.finished && bestPerQualityFull(step.items, identity)
                    if (full) search.bestFull = true
                    full || (stopAfter > 0 && !step.finished && goodLinkCount(step.items, identity) >= stopAfter)
                }
                runCatching { details?.get(1_500, java.util.concurrent.TimeUnit.MILLISECONDS) }
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    search.items = result.items; search.unavailable = result.unavailableProviders; search.late = result.lateProviders
                    search.stoppedEarly = result.stoppedEarly
                    search.waiting = emptyList(); search.finished = true
                    if (generation == search.generation) { if (resumed) renderSearch(search) else search.needsRender = true }
                }
            } catch (error: Exception) { runOnUiThread {
                if (!isDestroyed && generation == search.generation) { search.finished = true; body.removeView(status); showError(error); focusAfterRender(generation) }
            } }
        }
    }

    /** A link search, in progress or done: the link list re-renders from it as add-ons answer. */
    private class LinkSearch(val identity: PlaybackIdentity, var generation: Int) {
        var items: List<SourceItem> = emptyList(); var unavailable: List<String> = emptyList(); var late: List<String> = emptyList(); var stoppedEarly = false
        var answered = 0; var total = 0; var waiting: List<String> = emptyList(); var finished = false
        var autoplay = SourceSearchPreferences.OFF; var countdownSeconds = SourceSearchPreferences.COUNTDOWN_SECONDS
        var autoplayDone = false; var waitOver = false; var showHidden = false; var showAll = false; var rendered = false
        /** The source the header logos are filtering to (debrid service or add-on id), or null for all links. */
        var sourceFilter: String? = null
        /** Stop early because Best per quality filled up (not because of the Stop early count). */
        @Volatile var bestFull = false
        var hadLinks = false
        /** The first link's key in the last render: focus on it follows the top spot as better links arrive. */
        var topKey: String? = null
        /** Links arrived while the list was hidden (player in front): redraw once on return. */
        var needsRender = false
        /** How many of its Usenet links had failed when the list was last drawn. */
        var failedUsenet = 0
        var lastRender = 0L; var renderQueued = false; var countdownEndsAt = 0L
        /** Links that may auto-play (play at once, fit this device, under the size limit), best first. */
        var good: List<SourceItem> = emptyList()
        /** Real-Debrid-style candidates when nothing is known to be cached. */
        var tryOnPlay: List<SourceItem> = emptyList()
        var banner: TextView? = null
        fun take(step: SourceResolver.SearchProgress) {
            items = step.items; unavailable = step.unavailable; answered = step.answered; total = step.total; waiting = step.waiting
        }
    }
    private var activeSearch: LinkSearch? = null
    private var renderingSearch = false
    private val countdownHandler = Handler(Looper.getMainLooper())
    private var autoplayCountdown: Runnable? = null
    private var swallowBackUp = false
    /** Back on a filtered link list shows every link again (true when it did). */
    private var clearSourceFilter: (() -> Boolean)? = null

    /** Re-renders at most about twice a second while add-ons keep answering, keeping focus on the same link. */
    private fun scheduleRender(search: LinkSearch) {
        if (generation != search.generation || search.renderQueued || search.finished) return
        // Behind the player the list is not redrawn: that work would take the TV's processor from the video.
        if (!resumed) { search.needsRender = true; return }
        val wait = (800 - (android.os.SystemClock.elapsedRealtime() - search.lastRender)).coerceAtLeast(0)
        search.renderQueued = true
        countdownHandler.postDelayed({
            search.renderQueued = false
            if (!isDestroyed && generation == search.generation && !search.finished) renderSearch(search)
        }, wait)
    }

    private fun renderSearch(search: LinkSearch) {
        // Until links were on screen there is nothing worth keeping focus on: the first links get it.
        val captured = if (search.rendered && search.hadLinks && generation == search.generation) focusController.capture() else null
        // Still on the top link (the viewer hasn't moved): focus follows the top spot, so a better link that arrives gets it.
        val keep = captured?.takeUnless { it.key != null && it.key == search.topKey }
        if (keep?.key != null && DeviceLayout.tv && pendingFocus == null) pendingFocus = keep
        renderingSearch = true
        try { sourceResults(search.identity, search.items, search.unavailable, animate = !search.rendered, search = search) }
        finally { renderingSearch = false }
        search.rendered = true; search.generation = generation; search.lastRender = android.os.SystemClock.elapsedRealtime()
        search.hadLinks = search.items.isNotEmpty()
        // Touch screens keep their scroll place instead of a focused row.
        if (keep != null && (keep.key == null || !DeviceLayout.tv)) body.post { if (generation == search.generation) scroller.scrollTo(0, keep.scrollY) }
    }

    private fun searchLine(search: LinkSearch): String = if (search.answered == 0) "Searching ${search.total.takeIf { it > 0 } ?: ""} add-ons…".replace("  ", " ")
        else "Searching ${search.answered}/${search.total} · waiting for ${search.waiting.take(3).joinToString(", ")}${if (search.waiting.size > 3) " +${search.waiting.size - 3}" else ""}"

    /** Good links (play at once, fit this device) among [items]; run on a worker to decide an early stop. */
    private fun goodLinkCount(items: List<SourceItem>, identity: PlaybackIdentity): Int {
        val caps = DeviceVideoSupport.caps()
        return orderForStreams(items, identity).count { playsAtOnce(it) && DeviceVideoSupport.problem(it, caps) == null && withinAutoplayLimit(it, identity) }
    }

    /** Under the Auto-play size/bitrate limit (always true when the limit is off): the same test Auto-play uses. */
    private fun withinAutoplayLimit(source: SourceItem, identity: PlaybackIdentity): Boolean {
        if (store.get(AUTOPLAY_LIMIT_ON) != "true") return true
        val minutes = AutoplayLimit.runtimeMinutes(cachedDetails(MediaItemData(identity.imdbId, identity.type, identity.title))?.runtime)
        return !AutoplayLimit.tooBig(source.sizeBytes, minutes, autoplayMaxMbps(), autoplayLimitGb(episode = identity.type == "series") * 1_000_000_000L)
    }

    /**
     * With auto-play on and its limit set, links over the limit go below the ones it may play (each group keeps the
     * viewer's sort order), so the link auto-play starts sits at the top. Null when that doesn't apply.
     */
    private fun overAutoplayLimit(identity: PlaybackIdentity): ((SourceItem) -> Boolean)? {
        if (store.get(AUTOPLAY_LIMIT_ON) != "true") return null
        if (SourceSearchPreferences(this).mode(store.get(AUTOPLAY_FIRST) == "true") == SourceSearchPreferences.OFF) return null
        return { source -> !withinAutoplayLimit(source, identity) }
    }

    /**
     * Best per quality is full (Search goal is "Best per quality is full" or "Either one"): 4K, 1080p and 720p (those not
     * switched off in Link filters) each have enough good links. Runs on a worker.
     */
    private fun bestPerQualityFull(items: List<SourceItem>, identity: PlaybackIdentity): Boolean {
        val sorting = SourceSortPreferences(this)
        val perQuality = sorting.bestPerQuality
        if (perQuality <= 0) return false
        val wanted = listOf("4k", "1080", "720").filter { it !in sorting.rules.hiddenQualities }.ifEmpty { return false }
        val caps = DeviceVideoSupport.caps()
        val good = orderForStreams(items, identity).filter { playsAtOnce(it) && DeviceVideoSupport.problem(it, caps) == null && withinAutoplayLimit(it, identity) }
        return wanted.all { key -> good.count { LinkRules.qualityKey(it.qualityHeight) == key } >= perQuality }
    }

    /** A movie's release year from its details (already loaded or loading beside the search), for the year filter. */
    private fun movieYear(identity: PlaybackIdentity?): Int? = identity?.takeIf { it.type == "movie" }?.let { movie ->
        cachedDetails(MediaItemData(movie.imdbId, movie.type, movie.title))?.year?.take(4)?.toIntOrNull()
    }

    /**
     * Hidden by Link filters: the viewer's switches, words and pattern, Dolby Vision only links this device can't play,
     * and movie links naming another year (another film with the same name).
     */
    private fun hiddenByRules(source: SourceItem, rules: LinkRules.Rules, caps: DeviceVideoSupport.Caps, identity: PlaybackIdentity? = null, year: Int? = null): Boolean =
        rules.hides(source.title) || rules.hidesQuality(source.qualityHeight) ||
        (LinkRules.HIDE_DV_ONLY in rules.hidePresets && DeviceVideoSupport.problem(source, caps)?.startsWith("DOLBY VISION ONLY") == true) ||
        (rules.hideOtherYears && identity != null && year != null && LinkRules.otherYear(source.title, identity.title, year))

    private fun maybeAutoplay(search: LinkSearch, checkOnPlay: Boolean) {
        if (search.autoplayDone || search.autoplay == SourceSearchPreferences.OFF) return
        val ready = when (search.autoplay) {
            SourceSearchPreferences.FIRST -> true
            SourceSearchPreferences.WAIT -> search.waitOver || search.finished
            else -> search.finished
        }
        if (!ready) return
        if (autoplayPick(search) != null) { if (autoplayCountdown == null) startAutoplayCountdown(search) else updateCountdownLine(search) }
        else if (search.finished && checkOnPlay && autoplayCountdown == null && search.tryOnPlay.isNotEmpty()) {
            search.autoplayDone = true; resolveFirstCached(search.tryOnPlay, search.identity, null)
        } else if (search.finished && autoplayCountdown == null) {
            // Nothing found fits: say so once and leave the choice to the viewer.
            search.autoplayDone = true
            toast(if (store.get(AUTOPLAY_LIMIT_ON) == "true") "No link under your auto-play limit · pick one" else "No link can auto-play · pick one")
        }
    }

    /** The best good link; for a show, the same release group as the last episode when it is as sharp as the best. */
    private fun autoplayPick(search: LinkSearch): SourceItem? {
        val top = search.good.firstOrNull() ?: return null
        if (search.identity.type != "series") return top
        val group = getSharedPreferences("pov_last_group", 0).getString(search.identity.imdbId, null) ?: return top
        return search.good.firstOrNull { LinkRules.releaseGroup(it.title).equals(group, ignoreCase = true) && (it.qualityHeight ?: 0) >= (top.qualityHeight ?: 0) } ?: top
    }

    private fun startAutoplayCountdown(search: LinkSearch) {
        search.countdownEndsAt = android.os.SystemClock.elapsedRealtime() + search.countdownSeconds * 1000L
        val tick = object : Runnable {
            override fun run() {
                if (autoplayCountdown !== this) return
                if (isDestroyed || generation != search.generation) { autoplayCountdown = null; return }
                val left = search.countdownEndsAt - android.os.SystemClock.elapsedRealtime()
                if (left > 0) { updateCountdownLine(search); countdownHandler.postDelayed(this, minOf(left, 1_000L)); return }
                autoplayCountdown = null; search.autoplayDone = true; search.banner?.visibility = View.GONE
                val pick = autoplayPick(search) ?: return
                toast("Playing the top result · ${pick.title.take(60)}"); resolve(pick, search.identity)
            }
        }
        autoplayCountdown = tick
        // No countdown (the player's Next episode): still posted, not run here. This is called while the link list is
        // being drawn, before renderSearch() records the new page's generation, so running now would see a "different
        // page" and silently drop the auto-play.
        if (search.countdownSeconds <= 0) { countdownHandler.post(tick); return }
        updateCountdownLine(search); countdownHandler.postDelayed(tick, 1_000L)
    }

    private fun updateCountdownLine(search: LinkSearch) {
        val pick = autoplayPick(search) ?: return
        val left = ((search.countdownEndsAt - android.os.SystemClock.elapsedRealtime() + 999) / 1000).coerceAtLeast(1)
        search.banner?.apply {
            visibility = View.VISIBLE
            text = "▶  Playing ${SourceBadges.name(pick).lineSequence().firstOrNull().orEmpty().take(70)} in $left · ${if (DeviceLayout.tv) "press any key" else "tap anywhere"} to choose yourself"
        }
    }

    /** Stops a running auto-play countdown; true when one was running. */
    private fun cancelAutoplayCountdown(): Boolean {
        val running = autoplayCountdown ?: return false
        autoplayCountdown = null; countdownHandler.removeCallbacks(running)
        activeSearch?.let { it.autoplayDone = true; it.banner?.visibility = View.GONE }
        return true
    }

    private fun sourceResults(identity: PlaybackIdentity, sources: List<SourceItem>) = sourceResults(identity, sources, emptyList())

    /** Changes when providers, Easynews or the debrid services in use change, so a kept link list is searched again. */
    private fun sourceRevision() = listOf(store.get(SourceProviders.KEY), store.get("sources.endpoint"), DebridServices.connected(store).joinToString(","),
        Easynews.active(store).toString(), Easynews.server(store)).joinToString("\u0000")

    private fun sourceResults(identity: PlaybackIdentity, sources: List<SourceItem>, unavailable: List<String>, animate: Boolean = false, search: LinkSearch? = null) {
        val providerRevision = sourceRevision()
        current = {
            if (providerRevision != sourceRevision()) showSources(identity)
            else if (search != null) renderSearch(search)
            else sourceResults(identity, sources, unavailable)
        }
        val settled = search == null || search.finished
        val connected = DebridServices.connected(store)
        val directOnly = connected.isEmpty()
        val checkOnPlay = connected.filter { it in LinkDebrids.CHECK_ON_PLAY }
        // Hide uncached links: only links known not to be cached go; Real-Debrid-style services can't tell, so theirs stay.
        val hideUncached = store.get(HIDE_UNCACHED) == "true" && checkOnPlay.isEmpty()
        // Cam, telesync and other theatre copies are left out unless the viewer turned that off.
        val hideLow = LowQualityReleases.hidden(store)
        val filtered = (if (directOnly) sources.filter { it.directUrl.isNotEmpty() } else sources)
            .let { all -> if (hideUncached) all.filter(::playsAtOnce) else all }
            .let { all -> if (hideLow) all.filterNot(LowQualityReleases::matches) else all }
        // Link filters (Settings → Playback → Links & sorting); the list can show what they hid.
        val rules = SourceSortPreferences(this).rules
        val ruleCaps = DeviceVideoSupport.caps()
        val ruleYear = movieYear(identity)
        val ruleHidden = filtered.count { hiddenByRules(it, rules, ruleCaps, identity, ruleYear) }
        val playable = if (search?.showHidden == true) filtered else filtered.filterNot { hiddenByRules(it, rules, ruleCaps, identity, ruleYear) }
        val lowOnly = hideLow && playable.isEmpty() && sources.any(LowQualityReleases::matches)
        val suffix = if (identity.type == "series") "  ·  S${identity.season} E${identity.episode}" else ""
        val artShown = flix && heroBackdrop.visibility == View.VISIBLE
        val id = page("")
        // The title, with the link counts in the top-right corner: each service's logo and its cached links, then the total.
        val counts = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL or Gravity.END }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(text(identity.title + suffix, if (DeviceLayout.phone) 24 else 29, ink, true).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val countedServices = connected.size + (if (Easynews.active(store)) 1 else 0)
        // A phone has room for two logos beside the title; more go on their own line, still on the right.
        val ownLine = DeviceLayout.phone && countedServices > 2
        if (!ownLine) header.addView(counts, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(12) })
        body.addView(header, margin(-1, -2, bottom = 5))
        if (ownLine) body.addView(counts, margin(-1, -2, bottom = 6))
        // Compact list: the service logos up top already say where links are cached, so the priority line goes.
        val compactList = LinkListPreferences.compact(this)
        if (directOnly || !compactList) body.addView(text(if (directOnly) "Direct links only. Torrent sources need a debrid service."
            else "Cached source priority: ${connected.joinToString(" → ") { DebridServices.name(it) }}.", 12, muted), margin(-1, -2, bottom = 18))
        else body.addView(View(this), margin(-1, 6))
        sourcesBackdrop(identity, keep = artShown)
        val sorting = SourceSortPreferences(this)
        val sortButton: View = button("Sort · ${sorting.label}  ›") {
            SourceSortPreferences.show(this, { if (search != null) renderSearch(search) else sourceResults(identity, sources, unavailable) }) {
                navigate { playbackPage("preferred") }
            }
        }
        // The list opens on its first link, not on Sort above it.
        sortButton.tag = "${TvFocusController.SKIP_FIRST}:sort"
        body.addView(sortButton, margin(-1, if (compactList) 36 else 42, bottom = if (compactList) 6 else 8))
        if (unavailable.isNotEmpty()) body.addView(text("Unavailable providers: ${unavailable.joinToString(", ")}. Other providers were searched.", 14, muted), margin(-1, -2, bottom = 8))
        if (search != null && !search.finished) body.addView(text(searchLine(search), 13, 0xFF46D369.toInt(), true), margin(-1, -2, bottom = 8))
        // Stop early is the viewer's choice, so it gets one short line; only a real time-out names the slow add-ons.
        if (search != null && search.stoppedEarly && search.bestFull) body.addView(text("Stopped: best per quality found · " +
            "${search.late.size} ${if (search.late.size == 1) "add-on" else "add-ons"} not waited for", 13, muted), margin(-1, -2, bottom = 8))
        else if (search != null && search.stoppedEarly) body.addView(text("Stopped: ${SourceSearchPreferences(this).goodLinksNeeded} good links found · " +
            "${search.late.size} ${if (search.late.size == 1) "add-on" else "add-ons"} not waited for", 13, muted), margin(-1, -2, bottom = 8))
        else if (search != null && search.late.isNotEmpty()) body.addView(text("No answer in time: ${search.late.joinToString(", ")}. " +
            "Give them longer in Settings → Playback → Links & sorting → Max search time.", 13, muted), margin(-1, -2, bottom = 8))
        if (search != null) {
            val banner = text("", 14, ink, true).apply { visibility = View.GONE; background = rounded(withAlpha(accent, 60), 8); setPadding(dp(12), dp(8), dp(12), dp(8)) }
            body.addView(banner, margin(-1, -2, bottom = 8)); search.banner = banner
        }
        if (!settled) { if (sources.isEmpty()) body.addView(text("Waiting for the first add-on to answer…", 15, muted)) }
        else if (sources.isEmpty()) body.addView(text("No sources were returned. Try again later or check the source provider in Settings.", 15, muted))
        else if (lowOnly) {
            body.addView(text("Only cam or other low-quality releases were found. Turn off Hide cam & low-quality releases in Settings → Playback to see them.", 15, muted))
        } else if (playable.isEmpty() && hideUncached) {
            body.addView(text("Only uncached links were found. Turn off Hide uncached links in Settings → Playback to see them.", 15, muted))
        } else if (playable.isEmpty()) {
            body.addView(text("Only torrent sources were found. Connect a debrid service to play them.", 15, muted))
            body.addView(button("Connect a debrid service in Settings") { navigate { debridServicesPage() } }, margin(-1, 44, top = 8))
        }
        val choices = TvChoiceList(this); body.addView(choices, margin(-1, -2))
        // The title's runtime (from details already loaded) turns each file size into an estimated bitrate.
        val minutes = AutoplayLimit.runtimeMinutes(cachedDetails(MediaItemData(identity.imdbId, identity.type, identity.title))?.runtime)
        // Links this device likely can't decode (too tall for its decoder, Dolby Vision without DV support, 4K 10-bit
        // on a chip without hardware Main 10, a codec it lacks) go last, are marked, and are never auto-played.
        val fit = DeviceVideoSupport.caps()
        val problems = HashMap<SourceItem, String?>()
        fun problem(source: SourceItem) = problems.getOrPut(source) { DeviceVideoSupport.problem(source, fit) }
        fun tooTall(source: SourceItem) = problem(source) != null
        val languages = sorting.languages
        // Auto-play on with a limit: links over it go below the ones it may play, under their own heading.
        val overLimit = overAutoplayLimit(identity)
        val fullOrder = playable.distinctBy(TvChoiceKeys::source).let { unique -> languages?.sorted(unique, sorting.order) ?: sorting.order.sorted(unique) }.let(::cachedFirst)
            .let { all -> overLimit?.let { over -> all.filterNot(over) + all.filter(over) } ?: all }
            .let { all -> all.filterNot(::tooTall) + all.filter(::tooTall) }
        // Best per quality: the first few of each quality in this order; More links shows the rest.
        val ordered = if (search?.showAll == true) fullOrder else SourceSortPreferences.bestPerQuality(fullOrder, sorting.bestPerQuality)
        val moreLinks = fullOrder.size - ordered.size
        search?.topKey = ordered.firstOrNull()?.let(TvChoiceKeys::source)
        // The player's Streams panel offers the same links in the same order.
        StreamChoices.publish(identity, ordered)
        // The logos and counts filter the list: landing on one (TV) or tapping it (phone) shows only that source's links;
        // Down goes into them, and Back or the total shows every link again.
        val rowSources = HashMap<View, SourceItem>()
        val chips = mutableListOf<View>()
        val chipKeys = HashMap<View, String?>()
        fun matchesSource(source: SourceItem, key: String?): Boolean = key == null || when (key) {
            PlaybackDebrid.EASYNEWS -> Easynews.isHost(source.directUrl)
            in DebridServices.IDS -> key in source.cachedServices || source.servedBy == key && source.directUrl.isNotEmpty()
            else -> PlaybackDebrid.addon(source) == key
        }
        val total = text("", 14, muted, true)
        fun paintChips() = chips.forEach { chip ->
            val chosen = chipKeys[chip] == search?.sourceFilter
            chip.background = if (chip.isFocused) rounded(Color.WHITE, 10) else if (chosen && search?.sourceFilter != null) rounded(withAlpha(Color.WHITE, 46), 10) else null
            (chip as? LinearLayout)?.let { (0 until it.childCount).map(it::getChildAt).filterIsInstance<TextView>().forEach { label -> label.setTextColor(if (chip.isFocused) Color.BLACK else ink) } }
        }
        fun applySourceFilter(key: String?) {
            search?.sourceFilter = key
            var shown = 0
            for (index in 0 until choices.childCount) {
                val row = choices.getChildAt(index); val source = rowSources[row] ?: continue
                val visible = matchesSource(source, key); row.visibility = if (visible) View.VISIBLE else View.GONE; if (visible) shown++
            }
            val all = ordered.size
            total.text = if (key == null) "$all ${if (all == 1) "link" else "links"}" else "${ordered.count { matchesSource(it, key) }} of $all links"
            paintChips()
        }
        fun chip(key: String?, description: String, filters: Boolean): LinearLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(4), dp(8), dp(4))
            contentDescription = description
            if (search != null && filters) {
                tag = "${TvFocusController.SKIP_SOURCE}${key ?: "all"}"; isFocusable = true; isFocusableInTouchMode = DeviceLayout.tv; isClickable = true
                chips += this; chipKeys[this] = key
                setOnFocusChangeListener { _, focused -> if (focused && DeviceLayout.tv) applySourceFilter(key) else paintChips() }
                setOnClickListener { if (!DeviceLayout.tv) applySourceFilter(if (key != null && search.sourceFilter == key) null else key) }
                setOnKeyListener { _, keyCode, event ->
                    if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                        (0 until choices.childCount).map(choices::getChildAt).firstOrNull { it.visibility == View.VISIBLE }?.requestFocus(); true
                    } else false
                }
            }
        }
        fun count(service: String, value: String, description: String) {
            val filters = value != "?" && value != "0"
            val holder = chip(service, description, filters)
            holder.addView(ImageView(this).apply {
                setImageResource(PlaybackDebrid.logo(service)); adjustViewBounds = true; maxWidth = dp(72)
                scaleType = ImageView.ScaleType.FIT_CENTER
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(20)))
            holder.addView(text(value, 16, ink, true), LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(6) })
            counts.addView(holder, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(6) })
        }
        // Ready-made links from add-ons that hold the viewer's debrid key ("[TB+] Torrentio") count under that service too,
        // also when the service isn't connected in POV itself.
        fun viaAddon(source: SourceItem, service: String) = source.directUrl.isNotEmpty() && source.servedBy == service && source.addonCached != false
        val addonServices = DebridServices.IDS.filter { service -> service !in connected && ordered.any { viaAddon(it, service) } }
        // A service with nothing to show is left out: e.g. every link came ready-made from add-ons holding their own
        // debrid key, so the services connected in POV had no torrents to check.
        val torrents = ordered.any { it.directUrl.isEmpty() && it.infoHash.isNotBlank() }
        (connected + addonServices).forEach { service ->
            val name = DebridServices.name(service)
            val fromAddons = ordered.count { viaAddon(it, service) }
            // Real-Debrid-style services can't say in advance what is cached (unless a debrid add-on already did).
            if (service in checkOnPlay && fromAddons == 0) { if (torrents) count(service, "?", "$name checks the cache when a link is played") }
            else ordered.count { service in it.cachedServices || viaAddon(it, service) }.takeIf { it > 0 }?.let { count(service, "$it", "$it links cached on $name") }
        }
        if (Easynews.active(store)) ordered.count { Easynews.isHost(it.directUrl) }.let { count(PlaybackDebrid.EASYNEWS, "$it", "$it Easynews links") }
        // Add-ons with their own logo show their count when they returned links (scrapers' logos stay on their links only).
        listOf(PlaybackDebrid.AIOSTREAMS to "AIOStreams", PlaybackDebrid.PENGUPLAY to "PenguPlay", PlaybackDebrid.CNCVERSE to "CNCVerse", PlaybackDebrid.PLAVIO to "PlavioHD", AddonBadges.ALTMOUNT to "AltMount", AddonBadges.STREAMNZB to "StreamNZB").forEach { (addon, name) ->
            ordered.count { PlaybackDebrid.addon(it) == addon }
                .takeIf { it > 0 }?.let { count(addon, "$it", "$it $name links") }
        }
        val totalChip = chip(null, "All links", filters = chips.isNotEmpty())
        totalChip.addView(total)
        applySourceFilter(search?.sourceFilter)
        counts.addView(totalChip, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(10) })
        // Up from Sort reaches the logos, on the one that is filtering.
        sortButton.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_UP && chips.isNotEmpty()) {
                (chips.firstOrNull { chipKeys[it] == search?.sourceFilter && search?.sourceFilter != null } ?: chips.first()).requestFocus(); true
            } else false
        }
        clearSourceFilter = if (search == null) null else ({ if (search.sourceFilter != null && generation == id) { applySourceFilter(null); true } else false })
        var limitHeading = false
        fun addRow(source: SourceItem, fadeIn: Boolean) {
            if (!limitHeading && overLimit?.invoke(source) == true && !tooTall(source) && choices.childCount > 0) {
                limitHeading = true
                choices.addView(text("Over your auto-play limit", 14, muted, true), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10); bottomMargin = dp(8) })
            }
            val limitNote = problem(source)?.let { " · $it" }.orEmpty()
            // Only the badges, the release name and the source's own name: the scrapers' extra lines are left out.
            choices.addChoice(TvChoiceRow(this, TvChoiceKeys.source(source), SourceBadges.name(source), SourceBadges.origin(source),
                limitNote.removePrefix(" · "), theme.palette, SourceBadges.forRow(source, languages, minutes, checkOnPlay, logos = true),
                logos = SourceBadges.cachedOn(source).map(PlaybackDebrid::logo), glass = flix) {
                if (!source.cached && source.directUrl.isEmpty()) {
                    if (checkOnPlay.isNotEmpty()) resolveFirstCached(ordered.dropWhile { it !== source }.filterNot(::tooTall), identity, source)
                    else offerTransfer(source, identity)
                }
                else resolve(source, identity)
            }.apply {
                setOnLongClickListener { debridMenu(source, identity, checkOnPlay) }
                rowSources[this] = source
                if (!matchesSource(source, search?.sourceFilter)) visibility = View.GONE
                if (fadeIn) { alpha = 0f; animate().alpha(1f).setDuration(180).start() }
            })
        }
        // The first screenful (and the row focus returns to) is built at once; the rest follow a batch per frame, so a
        // long list never freezes the TV and More links opens in place.
        val focusIndex = pendingFocus?.key?.let { key -> ordered.indexOfFirst { TvChoiceKeys.source(it) == key } } ?: -1
        val firstBatch = minOf(ordered.size, maxOf(14, focusIndex + 4))
        ordered.take(firstBatch).forEach { addRow(it, fadeIn = false) }
        var nextRow = firstBatch
        val rowsFor = id
        fun moreRows() {
            if (rowsFor != generation || isDestroyed) return
            // Paused behind the player: finish the list when it is seen again.
            if (!resumed && search != null) { search.needsRender = true; return }
            val end = minOf(nextRow + 12, ordered.size)
            for (index in nextRow until end) addRow(ordered[index], fadeIn = true)
            nextRow = end
            if (nextRow < ordered.size) body.post { moreRows() }
        }
        if (nextRow < ordered.size) body.post { moreRows() }
        if (search != null && (moreLinks > 0 || search.showAll) && sorting.bestPerQuality > 0) body.addView(button(
            if (search.showAll) "Best ${sorting.bestPerQuality} per quality only" else "More links ($moreLinks)") {
            // Opening the rest lands on the first link that was hidden, so the list grows in place below it.
            if (!search.showAll) ordered.toHashSet().let { shown -> fullOrder.firstOrNull { it !in shown } }?.let { pendingFocus = TvFocusSnapshot(TvChoiceKeys.source(it), scroller.scrollY) }
            search.showAll = !search.showAll; renderSearch(search)
        }, margin(-1, 40, top = 8))
        if (search != null && ruleHidden > 0) body.addView(button(if (search.showHidden) "Hide the $ruleHidden filtered ${if (ruleHidden == 1) "link" else "links"} again"
            else "Show $ruleHidden hidden ${if (ruleHidden == 1) "link" else "links"} (Link filters)") {
            search.showHidden = !search.showHidden; renderSearch(search)
        }, margin(-1, 40, top = 8))
        focusAfterRender(id)
        if (animate && flix) sourcesRiseIn(choices)
        // Only a fresh search auto-plays; returning to this list leaves the choice to the viewer.
        if (search != null) {
            // Auto-play skips links over the viewer's size limit; links of unknown size stay eligible.
            // Over the bitrate cap when the runtime is known, else over this kind of title's size cap.
            val limited = store.get(AUTOPLAY_LIMIT_ON) == "true"
            val maxBytes = autoplayLimitGb(episode = identity.type == "series") * 1_000_000_000L
            fun tooBig(source: SourceItem) = limited && AutoplayLimit.tooBig(source.sizeBytes, minutes, autoplayMaxMbps(), maxBytes)
            fun fits(source: SourceItem) = (source.cached || source.directUrl.isNotEmpty()) && source.addonCached != false && !tooTall(source) && !tooBig(source) &&
                !UsenetPlayback.failed(source.directUrl)
            // The short list (Best per quality) first; when none of it fits the Auto-play limit, every found link in the same order.
            search.good = ordered.filter(::fits).ifEmpty { fullOrder.filter(::fits) }
            search.tryOnPlay = ordered.filterNot { tooTall(it) || tooBig(it) }.ifEmpty { fullOrder.filterNot { tooTall(it) || tooBig(it) } }
            maybeAutoplay(search, checkOnPlay.isNotEmpty())
        }
    }

    /**
     * The link list's art: on TV the title's backdrop (carried over from the details page), on the phone its poster,
     * softened, filling the screen. [keep] skips the fade-in when artwork is already showing.
     */
    private fun sourcesBackdrop(identity: PlaybackIdentity, keep: Boolean) {
        if (!flix) return
        val base = MediaItemData(identity.imdbId, identity.type, identity.title)
        val item = cachedDetails(base) ?: base
        sourcesPage = true
        if (keep) heroBackdrop.visibility = View.VISIBLE
        placeBackdrop(home = false); heroBackdrop.visibility = View.VISIBLE
        sourcesLayers()
        if (DeviceLayout.phone) {
            val poster = item.poster.ifBlank { if (identity.imdbId.matches(Regex("tt\\d+"))) "https://images.metahub.space/poster/medium/${identity.imdbId}/img" else "" }
            if (poster.isNotBlank()) image(sourcesArt, thumbnail(poster, wide = false), feature = true)
        // TV: the plain backdrop over the details version (which is shaded nearly black on the left, under the list).
        } else image(sourcesArt, backdropFor(item), feature = true)
    }
    private fun sourcesLayers() {
        sourcesScrim.setBackgroundColor(withAlpha(bg, if (DeviceLayout.phone) 120 else 105))
        sourcesScrim.visibility = View.VISIBLE
        sourcesArt.visibility = View.VISIBLE
        if (DeviceLayout.phone) {
            if (android.os.Build.VERSION.SDK_INT >= 31) sourcesArt.setRenderEffect(
                android.graphics.RenderEffect.createBlurEffect(dp(6).toFloat(), dp(6).toFloat(), android.graphics.Shader.TileMode.CLAMP))
        }
    }
    private fun leaveSourcesBackdrop() {
        if (!sourcesPage) return
        sourcesPage = false; sourcesArt.visibility = View.GONE; sourcesScrim.visibility = View.GONE
    }
    /** The link list opens like a details page: the header, then the first rows, rise into place one after another. */
    private fun sourcesRiseIn(list: TvChoiceList) {
        val lift = dp(22).toFloat()
        val parts = (0 until body.childCount).map(body::getChildAt).filter { it !== list } + (0 until minOf(list.childCount, 10)).map(list::getChildAt)
        parts.forEachIndexed { index, part ->
            part.animate().cancel(); part.alpha = 0f; part.translationY = lift
            part.animate().alpha(1f).translationY(0f).setStartDelay(index * 35L).setDuration(260).setInterpolator(EASE_OUT).start()
        }
    }

    /**
     * Links that play at once (cached or direct) lead, and the rest follow in the same sort order. Real-Debrid-style
     * services can't tell in advance, so with only those connected nothing moves.
     */
    private fun cachedFirst(sorted: List<SourceItem>): List<SourceItem> {
        // Your own Preferred words can keep a not-yet-cached link among the ready ones, in its sort place.
        val sorting = SourceSortPreferences(this)
        val rules = sorting.rules.takeIf { sorting.wordsBeatCached && sorting.preferredFirst && it.preferWords.isNotEmpty() }
        val ready = sorted.partition { playsAtOnce(it) || rules?.matchesOwnWord(it.title) == true }.let { (ready, other) -> ready + other }
        // Library links now rank by their own row in Link sorting (SourceSortOrder.LIBRARY).
        migrateLibraryFirst()
        return ready
    }

    /** Library first moved from its own switch into Link sorting; a viewer who had switched it off keeps it off. */
    private fun migrateLibraryFirst() {
        if (store.get(LIBRARY_FIRST) == "false") { SourceSortPreferences(this).libraryFirst = false; store.put(LIBRARY_FIRST, "moved") }
    }

    /** Cached somewhere, or a direct link: plays at once. */
    private fun playsAtOnce(source: SourceItem) = source.addonCached != false &&
        (source.cached || source.cachedServices.isNotEmpty() || source.directUrl.isNotEmpty())

    /** The links of [sources] as the source list shows them: playable ones, in the viewer's order, too-tall last. */
    private fun orderForStreams(sources: List<SourceItem>, identity: PlaybackIdentity? = null): List<SourceItem> {
        val connected = DebridServices.connected(store)
        val hideUncached = store.get(HIDE_UNCACHED) == "true" && connected.none { it in LinkDebrids.CHECK_ON_PLAY }
        val sorting = SourceSortPreferences(this)
        val fit = DeviceVideoSupport.caps()
        val rules = sorting.rules
        val playable = (if (connected.isEmpty()) sources.filter { it.directUrl.isNotEmpty() } else sources)
            .let { all -> if (hideUncached) all.filter(::playsAtOnce) else all }
            .let { all -> if (LowQualityReleases.hidden(store)) all.filterNot(LowQualityReleases::matches) else all }
            .let { all -> val year = movieYear(identity); all.filterNot { hiddenByRules(it, rules, fit, identity, year) } }
        fun tooTall(source: SourceItem) = DeviceVideoSupport.problem(source, fit) != null
        return playable.distinctBy(TvChoiceKeys::source).let { unique -> sorting.languages?.sorted(unique, sorting.order) ?: sorting.order.sorted(unique) }
            .let(::cachedFirst)
            .let { all -> overAutoplayLimit(identity ?: return@let all)?.let { over -> all.filterNot(over) + all.filter(over) } ?: all }
            .let { all -> all.filterNot(::tooTall) + all.filter(::tooTall) }
            .let { all -> SourceSortPreferences.bestPerQuality(all, sorting.bestPerQuality) }
    }

    /**
     * Searches the playing title's links quietly for the player's Streams panel (playback from Continue Watching has
     * only its saved link). It waits a few seconds so it never competes with the video starting.
     */
    private fun searchStreamsInBackground(identity: PlaybackIdentity, delayMs: Long = 6_000) {
        if (publicPreview || StreamChoices.has(identity)) return
        StreamChoices.begin(identity)
        window.decorView.postDelayed({
            worker.execute {
                runCatching { resolver.search(identity).items }
                    .onSuccess { found -> runOnUiThread { if (!isDestroyed) StreamChoices.publish(identity, orderForStreams(found, identity)) } }
                    .onFailure { StreamChoices.fail(identity) }
            }
        }, delayMs)
    }

    /** A link picked in the player's Streams panel: prepared like a tap in the source list, continuing at [atMs]. */
    private fun switchStream(identity: PlaybackIdentity, source: SourceItem, atMs: Long) {
        switchResumeMs = atMs
        val checkOnPlay = DebridServices.connected(store).filter { it in LinkDebrids.CHECK_ON_PLAY }
        if (!source.cached && source.directUrl.isEmpty()) {
            val ordered = StreamChoices.items.orEmpty()
            if (checkOnPlay.isNotEmpty()) resolveFirstCached(ordered.dropWhile { it != source }.ifEmpty { listOf(source) }, identity, source)
            else offerTransfer(source, identity)
        } else resolve(source, identity)
    }

    /**
     * Hold OK on a torrent link: play it on another debrid service than the preferred one. Lists the services it is
     * cached on (the preferred one first), then the connected services that can only check when played.
     */
    private fun debridMenu(source: SourceItem, identity: PlaybackIdentity, checkOnPlay: List<String>): Boolean {
        val options = PlaybackDebrid.choices(source, checkOnPlay).map { (service, label) -> label to { resolve(source, identity, service) } }
        if (options.isEmpty()) return false
        holdMenu("Play with…", options)
        return true
    }

    /** Offcloud and TorBox can download a link that isn't cached; the other services cannot. */
    private fun offerTransfer(source: SourceItem, identity: PlaybackIdentity) {
        val service = DebridServices.connected(store).firstOrNull { it in DebridServices.TRANSFERS }
        if (service == null) { toast("This link isn't cached on your debrid services. Choose another link."); return }
        confirm("Add this source to your ${DebridServices.name(service)} account? A transfer may need time to finish.") {
            resolve(source.copy(downloadService = service), identity)
        }
    }

    /**
     * Real-Debrid, AllDebrid and Debrid-Link cannot say in advance what is cached, so the first few links are tried
     * in order and the first cached one plays. [picked] is the row the viewer chose, offered for download if none play.
     */
    private fun resolveFirstCached(candidates: List<SourceItem>, identity: PlaybackIdentity, picked: SourceItem?) {
        if (resolvingGeneration == generation || launchingPlayer) { toast("Preparing the selected source…"); return }
        cancelAutoplayCountdown()
        playingSourceText = ""; playingMedia = null
        val request = generation; resolvingGeneration = request
        val progress = text("Checking links…", 14, accent); body.addView(progress, margin(-1, -2, top = 14))
        worker.execute {
            try {
                val prepared = resolver.prepareFirstCached(candidates, identity) { number, total, service ->
                    runOnUiThread { if (request == generation) progress.text = "Checking link $number of $total on ${DebridServices.name(service)}…" }
                }
                runOnUiThread {
                    if (request == generation && !isFinishing && !isDestroyed) {
                        resolvingGeneration = null; body.removeView(progress)
                        when {
                            prepared == null -> {
                                val tried = minOf(candidates.count { it.infoHash.isNotBlank() }, LinkDebrids.CHECK_LIMIT)
                                toast("None of the first $tried links are cached. Pick another link.")
                                if (picked != null && DebridServices.connected(store).any { it in DebridServices.TRANSFERS }) offerTransfer(picked, identity)
                                focusAfterRender(request)
                            }
                            prepared.files.size == 1 -> playLinkFile(prepared, prepared.files.single())
                            else -> navigate { linkFiles(prepared) }
                        }
                    }
                }
            } catch (error: Exception) { runOnUiThread {
                if (request == generation && !isFinishing && !isDestroyed) { resolvingGeneration = null; body.removeView(progress); showError(error); focusAfterRender(request) }
            } }
        }
    }

    private fun linkFiles(prepared: PreparedSource.Links) {
        current = { linkFiles(prepared) }
        val identity = prepared.identity
        val name = DebridServices.name(prepared.service)
        val id = page("Choose a $name video", "Choose the correct file for ${identity.title}.")
        val choices = TvChoiceList(this); body.addView(choices, margin(-1, -2))
        prepared.files.forEach { file ->
            choices.addChoice(TvChoiceRow(this, "${prepared.service}:${identity.imdbId}:${file.id}", file.name,
                if (file.size > 0) "${file.size / (1024 * 1024)} MB" else "$name video", palette = theme.palette) { playLinkFile(prepared, file) })
        }
        if (prepared.files.isEmpty()) body.addView(text("No eligible video files are available. Go back and choose another source.", 15, muted))
        if (pendingFocus == null) prepared.preferredFileId?.let { pendingFocus = TvFocusSnapshot("${prepared.service}:${identity.imdbId}:$it", 0) }
        focusAfterRender(id)
    }

    private fun playLinkFile(prepared: PreparedSource.Links, file: DebridFile) {
        mediaFile(file.name, file.size)
        val identity = prepared.identity
        val request = generation
        worker.execute {
            try {
                val url = resolver.streamUrl(prepared, file)
                runOnUiThread { if (request == generation && !isFinishing && !isDestroyed)
                    play(url, identity.title, identity, "media:${identity.imdbId}:${identity.season}:${identity.episode}", prepared.service) }
            } catch (error: Exception) { runOnUiThread { if (request == generation && !isFinishing && !isDestroyed) showError(error) } }
        }
    }

    /** [service] plays the link on that debrid service instead of the preferred one (chosen by holding OK on a link). */
    private fun resolve(source: SourceItem, identity: PlaybackIdentity, service: String? = null) {
        if (resolvingGeneration == generation || launchingPlayer) { toast("Preparing the selected source…"); return }
        cancelAutoplayCountdown()
        // A show's next episode auto-plays from the same release group when it can, so quality stays the same.
        if (identity.type == "series") LinkRules.releaseGroup(source.title)?.let { getSharedPreferences("pov_last_group", 0).edit().putString(identity.imdbId, it).apply() }
        playingSourceText = "${source.title} · ${source.detail}"; playingMedia = playingSourceText to source.sizeBytes
        val request = generation; resolvingGeneration = request
        val progress = text("Preparing your video…", 14, accent); body.addView(progress, margin(-1, -2, top = 14))
        worker.execute {
            try {
                val prepared = resolver.prepare(source, identity, service)
                runOnUiThread {
                    if (request == generation && !isFinishing && !isDestroyed) {
                        resolvingGeneration = null; body.removeView(progress)
                        // A scraper's torrent played by POV's own debrid: the scraper's logo goes before the service's in the player.
                        playingAddon = if (prepared is PreparedSource.Direct) PlaybackDebrid.tag(source) else source.scraper
                        when (prepared) {
                            is PreparedSource.Direct -> play(prepared.url, prepared.identity.title, prepared.identity,
                                "media:${prepared.identity.imdbId}:${prepared.identity.season}:${prepared.identity.episode}",
                                headers = prepared.headers)
                            is PreparedSource.OffcloudFiles -> {
                                if (prepared.files.size == 1) playOffcloudFile(prepared, prepared.files.single())
                                else navigate { offcloudFiles(prepared) }
                            }
                            is PreparedSource.Files -> {
                                val only = prepared.automaticFile
                                if (only != null) playPreparedFile(prepared, only)
                                else navigate { preparedFiles(prepared) }
                            }
                            is PreparedSource.Links -> {
                                if (prepared.files.size == 1) playLinkFile(prepared, prepared.files.single())
                                else navigate { linkFiles(prepared) }
                            }
                        }
                    }
                }
            } catch (error: Exception) { runOnUiThread {
                if (request == generation && !isFinishing && !isDestroyed) { resolvingGeneration = null; body.removeView(progress); showError(error); focusAfterRender(request) }
            } }
        }
    }

    private fun offcloudFiles(prepared: PreparedSource.OffcloudFiles) {
        current = { offcloudFiles(prepared) }
        val id = page("Choose an Offcloud video", "Choose the correct file for ${prepared.identity.title}.")
        val choices = TvChoiceList(this); body.addView(choices, margin(-1, -2))
        val sourceKey = prepared.requestId ?: "cache:${prepared.identity.imdbId}:${prepared.identity.season}:${prepared.identity.episode}"
        prepared.files.forEach { file ->
            val focusKey = "offcloud:$sourceKey:${file.id}"
            choices.addChoice(TvChoiceRow(this, focusKey, file.name,
                if (file.size > 0) "${file.size / (1024 * 1024)} MB" else "Offcloud video",
                palette = theme.palette) { playOffcloudFile(prepared, file) })
        }
        if (pendingFocus == null) prepared.preferredFileId?.let {
            pendingFocus = TvFocusSnapshot("offcloud:$sourceKey:$it", 0)
        }
        focusAfterRender(id)
    }

    private fun playOffcloudFile(prepared: PreparedSource.OffcloudFiles, file: OffcloudFile) {
        if (!OffcloudApi(store).owns(prepared)) {
            toast("Connect Offcloud and select the source again"); return
        }
        val identity = prepared.identity
        mediaFile(file.name, file.size)
        play(file.url, identity.title, identity, "media:${identity.imdbId}:${identity.season}:${identity.episode}", "offcloud")
    }

    private fun preparedFiles(prepared: PreparedSource.Files) {
        current = { preparedFiles(prepared) }
        val identity = prepared.identity
        val suffix = if (identity.type == "series") " · S${identity.season} E${identity.episode}" else ""
        val id = page("Choose a video version", "Choose the correct file for ${identity.title}$suffix. Each row plays that exact file.")
        val choices = TvChoiceList(this); body.addView(choices, margin(-1, -2))
        prepared.files.forEach { file ->
            choices.addChoice(TvChoiceRow(this, TvChoiceKeys.file(prepared.folder.kind, prepared.folder.id, file.id),
                file.name, fileSize(file), palette = theme.palette) { playPreparedFile(prepared, file) })
        }
        if (prepared.files.isEmpty()) body.addView(text("No eligible video files are available. Go back and choose another source.", 15, muted))
        if (pendingFocus == null) prepared.preferredFileId?.let { fileId ->
            pendingFocus = TvFocusSnapshot(TvChoiceKeys.file(prepared.folder.kind, prepared.folder.id, fileId), 0)
        }
        focusAfterRender(id)
    }

    private fun playPreparedFile(prepared: PreparedSource.Files, file: CloudFile) {
        mediaFile(file.name, file.size)
        val identity = prepared.identity
        prepared.accountTag?.let { tag ->
            if (!torbox.ownsTag(tag)) {
                toast("Connect TorBox and select the source again"); return
            }
            val key = "media:${identity.imdbId}:${identity.season}:${identity.episode}"
            prepared.resolvedUrls[file.id]?.let { play(it, identity.title, identity, key, "torbox"); return }
            val hash = prepared.sourceInfoHash ?: run { toast("Select the source again"); return }
            streamFile(prepared.folder, file, identity.title, identity, key) {
                check(torbox.ownsTag(tag)) { "The TorBox account changed. Select the source again." }
                val resolved = torbox.prepareCached(SourceItem(file.name, "TorBox", hash, cached = true), identity, selectedFile = file)
                check(resolved.accountTag == tag && torbox.ownsTag(tag)) { "The TorBox account changed. Select the source again." }
                resolved.resolvedUrls.values.single()
            }
            return
        }
        streamFile(prepared.folder, file, identity.title, identity,
            "media:${identity.imdbId}:${identity.season}:${identity.episode}")
    }

    /**
     * The Library: each connected debrid cloud, and each AIOStreams library among the source providers (found from
     * their manifests). With only one of them, it opens straight away.
     */
    private fun selectedCloud() {
        val services = DebridServices.connected(store).filter { it in DebridServices.TRANSFERS }
        current = { selectedCloud() }; destination = "library"; refreshNavigation()
        val id = page("Library", "Your debrid clouds and add-on libraries.")
        val providers = runCatching { SourceProviders(store).load() }.getOrDefault(emptyList())
        val nzbLibraries = streamNzbLibraries(providers)
        val altLibraries = altMountLibraries(providers)
        read(id, { AioLibrary().sources(providers) }) { libraries ->
            if (services.size + libraries.size + nzbLibraries.size + altLibraries.size == 1) {
                // Only one place to browse: open it in place of this page.
                when {
                    libraries.isNotEmpty() -> aioLibraryPage(libraries.first())
                    nzbLibraries.isNotEmpty() -> openStreamNzbLibrary(nzbLibraries.first(), replace = true)
                    altLibraries.isNotEmpty() -> openAltMountLibrary(altLibraries.first(), replace = true)
                    services.first() == "offcloud" -> offcloudCloud()
                    else -> cloud("torrents")
                }
                return@read
            }
            if (services.isEmpty() && libraries.isEmpty() && nzbLibraries.isEmpty() && altLibraries.isEmpty()) body.addView(button("Connect a debrid service in Settings") { navigate { debridServicesPage() } }, margin(-1, 44))
            services.forEach { service ->
                body.addView(button("${DebridServices.name(service)} cloud  ›") {
                    navigate { if (service == "offcloud") offcloudCloud() else cloud("torrents") }
                }, margin(-1, 44, bottom = 8))
            }
            libraries.forEach { library ->
                body.addView(button("${library.provider} · AIOStreams library  ›") { navigate { aioLibraryPage(library) } }, margin(-1, 44, bottom = 8))
            }
            nzbLibraries.forEach { library ->
                val signedIn = streamNzbToken(library.origin) != null
                body.addView(button("${library.provider.name} · StreamNZB library${if (signedIn) "" else " · sign in"}  ›") { openStreamNzbLibrary(library) },
                    margin(-1, 44, bottom = 8))
            }
            altLibraries.forEach { library ->
                val signedIn = altMountLogin(library.origin) != null
                body.addView(button("${library.provider.name} · AltMount library${if (signedIn) "" else " · sign in"}  ›") { openAltMountLibrary(library) },
                    margin(-1, 44, bottom = 8))
            }
            focusAfterRender(id)
        }
    }

    /** A StreamNZB add-on among the source providers, and the server its dashboard (and library) runs on. */
    private data class StreamNzbSource(val provider: SourceProvider, val endpoint: String, val origin: String)

    private fun isStreamNzb(provider: SourceProvider) =
        AddonBadges.of(provider.address, provider.name) == AddonBadges.STREAMNZB || provider.address.contains("streamnzb", ignoreCase = true)

    private fun streamNzbLibraries(providers: List<SourceProvider>): List<StreamNzbSource> = providers.filter { it.enabled && isStreamNzb(it) }
        .mapNotNull { provider ->
            val endpoint = runCatching { SourceProviders.endpoint(provider.address) }.getOrNull() ?: return@mapNotNull null
            StreamNzbLibrary.origin(endpoint)?.let { StreamNzbSource(provider, endpoint, it) }
        }.distinctBy { it.origin }

    /** The saved library token, only for the server it was issued by. */
    private fun streamNzbToken(origin: String): String? =
        store.get(StreamNzbLibrary.TOKEN).takeIf { it.isNotBlank() && store.get(StreamNzbLibrary.ORIGIN) == origin }

    private fun openStreamNzbLibrary(library: StreamNzbSource, replace: Boolean = false) {
        val open = { if (replace) streamNzbLibraryPage(library) else navigate { streamNzbLibraryPage(library) } }
        if (streamNzbToken(library.origin) != null) open() else signInStreamNzb(library) { navigate { streamNzbLibraryPage(library) } }
    }

    /**
     * StreamNZB keeps its library behind the dashboard login (it is shared by every device on that server). The
     * viewer signs in once; only the token StreamNZB returns is saved, encrypted, never the password.
     */
    private fun signInStreamNzb(library: StreamNzbSource, then: () -> Unit = {}) {
        val content = column().apply { setPadding(dp(18), dp(8), dp(18), dp(8)) }
        content.addView(text("Sign in with your StreamNZB dashboard login (${library.origin.removePrefix("https://")}). Only the sign-in token is kept on this device.", 13, muted), margin(-1, -2, bottom = 8))
        val user = TvEditText(this).apply {
            hint = "StreamNZB username"; setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        val password = TvEditText(this).apply {
            hint = "StreamNZB password"; setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        val status = text("", 14, muted)
        content.addView(user, margin(-1, -2)); content.addView(password, margin(-1, -2, top = 8)); content.addView(status, margin(-1, -2, top = 8))
        val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
        val dialog = AlertDialog.Builder(this).setTitle("StreamNZB library").setView(content)
            .setPositiveButton("Sign in", null).setNegativeButton("Cancel", null).create()
        dialog.setOnDismissListener { cancelled.set(true); password.setText("") }
        dialog.show(); dialog.submitOnEnter(password)
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            val name = user.text.toString().trim(); val secret = password.text.toString()
            if (name.isBlank()) { user.error = "Enter your StreamNZB username"; return@setOnClickListener }
            if (secret.isEmpty()) { password.error = "Enter your StreamNZB password"; return@setOnClickListener }
            val request = generation
            val connect = dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE)
            connect.isEnabled = false; status.text = "Signing in to StreamNZB…"
            worker.execute {
                try {
                    val token = StreamNzbLibrary().login(library.origin, name, secret)
                    if (cancelled.get()) return@execute
                    store.putAll(mapOf(StreamNzbLibrary.ORIGIN to library.origin, StreamNzbLibrary.TOKEN to token))
                    runOnUiThread {
                        if (request == generation && !isFinishing && !isDestroyed) {
                            if (dialog.isShowing) dialog.dismiss()
                            toast("Signed in to the StreamNZB library"); then()
                        }
                    }
                } catch (error: Exception) {
                    runOnUiThread { if (dialog.isShowing) { connect.isEnabled = true; status.text = uiErrorMessage(error) } }
                }
            }
        }
    }

    /** The releases StreamNZB has kept, newest first; one plays through the StreamNZB add-on. */
    private fun streamNzbLibraryPage(library: StreamNzbSource, shown: Int = StreamNzbLibrary.PAGE) {
        current = { streamNzbLibraryPage(library, shown) }; destination = "library"; refreshNavigation()
        val id = page("${library.provider.name} library", "Releases StreamNZB has kept, newest first. Select one to play it.")
        val token = streamNzbToken(library.origin)
        if (token == null) {
            body.addView(button("Sign in to the StreamNZB library  ›") { signInStreamNzb(library) { streamNzbLibraryPage(library) } }, margin(-1, 44))
            focusAfterRender(id); return
        }
        val api = StreamNzbLibrary()
        // Null: StreamNZB no longer accepts the saved token (signed out there, or its password changed).
        read(id, {
            try {
                val all = mutableListOf<StreamNzbLibrary.Item>()
                var offset = 0; var total = Int.MAX_VALUE
                while (all.size < shown && offset < total) {
                    val next = api.page(library.origin, token, offset)
                    total = next.total; offset += StreamNzbLibrary.PAGE
                    all += next.items.filterNot { item -> all.any { it.id == item.id } }
                    if (next.total == 0) break
                }
                all to (offset < total)
            } catch (error: ApiException) { if (error.code == 401 || error.code == 403) null else throw error }
        }) { result ->
            if (result == null) {
                store.putAll(mapOf(StreamNzbLibrary.TOKEN to ""))
                body.addView(text("StreamNZB signed this device out. Sign in again to see the library.", 15, muted), margin(-1, -2, bottom = 10))
                body.addView(button("Sign in again  ›") { signInStreamNzb(library) { streamNzbLibraryPage(library) } }, margin(-1, 44))
                focusAfterRender(id); return@read
            }
            val (entries, more) = result
            if (entries.isEmpty()) body.addView(text("Nothing in this library yet. Releases you stream through StreamNZB appear here.", 15, muted))
            val open: (StreamNzbLibrary.Item) -> Unit = { item ->
                val request = generation
                toast("Opening ${item.release.take(60)}…")
                worker.execute {
                    val link = runCatching { api.link(library.endpoint, item) }.getOrNull()
                    runOnUiThread {
                        if (request != generation || isDestroyed) return@runOnUiThread
                        when {
                            link != null -> { playingAddon = AddonBadges.STREAMNZB; play(link.url, item.release, null, "", headers = link.headers) }
                            // Not offered on its own right now: the title's links, where StreamNZB lists it among the others.
                            item.contentId.startsWith("tt") -> {
                                toast("Pick ${item.release.take(50)} from this title's links")
                                navigate { showSources(PlaybackIdentity(item.contentId, if (item.type == "movie") "movie" else "series", item.release, item.season, item.episode)) }
                            }
                            else -> toast("StreamNZB has no playable link for this release right now.")
                        }
                    }
                }
            }
            fun caption(item: StreamNzbLibrary.Item) = listOfNotNull(
                if (item.type != "movie" && item.season > 0 && item.episode > 0) "S${item.season} E${item.episode}" else null,
                SourceMetadata.sizeLabel(item.sizeBytes).takeIf { it.isNotBlank() }, "StreamNZB library").joinToString(" · ")
            if (flix) libraryGrid(entries.map { item -> LibraryEntry("snzblib:${item.id}", item.release, caption(item)) { open(item) } })
            else {
                val choices = TvChoiceList(this); body.addView(choices, margin(-1, -2))
                entries.forEach { item -> choices.addChoice(cloudRow("snzblib:${item.id}", item.release, caption(item)) { open(item) }) }
            }
            if (more) body.addView(button("Show more") { streamNzbLibraryPage(library, shown + StreamNzbLibrary.PAGE) }.apply { gravity = Gravity.CENTER },
                margin(200, 44, top = 12))
            focusAfterRender(id)
        }
    }

    /** An AltMount add-on among the source providers, and the server its WebDAV library runs on. */
    private data class AltMountSource(val provider: SourceProvider, val origin: String)

    private fun isAltMount(provider: SourceProvider) =
        AddonBadges.of(provider.address, provider.name) == AddonBadges.ALTMOUNT || provider.address.contains("altmount", ignoreCase = true)

    private fun altMountLibraries(providers: List<SourceProvider>): List<AltMountSource> = providers.filter { it.enabled && isAltMount(it) }
        .mapNotNull { provider ->
            val endpoint = runCatching { SourceProviders.endpoint(provider.address) }.getOrNull() ?: return@mapNotNull null
            AltMountLibrary.origin(endpoint)?.let { AltMountSource(provider, it) }
        }.distinctBy { it.origin }

    /** The saved WebDAV login, only for the server it was entered for. */
    private fun altMountLogin(origin: String): Pair<String, String>? {
        if (store.get(AltMountLibrary.ORIGIN) != origin) return null
        val user = store.get(AltMountLibrary.USER); val password = store.get(AltMountLibrary.PASSWORD)
        return if (user.isBlank() || password.isEmpty()) null else user to password
    }

    private fun openAltMountLibrary(library: AltMountSource, replace: Boolean = false) {
        if (altMountLogin(library.origin) != null) { if (replace) altMountLibraryPage(library) else navigate { altMountLibraryPage(library) } }
        else signInAltMount(library) { navigate { altMountLibraryPage(library) } }
    }

    /** AltMount's library is its WebDAV folders: the viewer enters the WebDAV login once (it is needed to play files). */
    private fun signInAltMount(library: AltMountSource, then: () -> Unit = {}) {
        val content = column().apply { setPadding(dp(18), dp(8), dp(18), dp(8)) }
        content.addView(text("Enter AltMount's WebDAV login (${library.origin.removePrefix("https://")}${AltMountLibrary.ROOT}). It is kept encrypted on this device and used to play library files.", 13, muted), margin(-1, -2, bottom = 8))
        val user = TvEditText(this).apply {
            hint = "WebDAV username"; setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        val password = TvEditText(this).apply {
            hint = "WebDAV password"; setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        val status = text("", 14, muted)
        content.addView(user, margin(-1, -2)); content.addView(password, margin(-1, -2, top = 8)); content.addView(status, margin(-1, -2, top = 8))
        val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
        val dialog = AlertDialog.Builder(this).setTitle("AltMount library").setView(content)
            .setPositiveButton("Connect", null).setNegativeButton("Cancel", null).create()
        dialog.setOnDismissListener { cancelled.set(true); password.setText("") }
        dialog.show(); dialog.submitOnEnter(password)
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            val name = user.text.toString().trim(); val secret = password.text.toString()
            if (name.isBlank()) { user.error = "Enter the WebDAV username"; return@setOnClickListener }
            if (secret.isEmpty()) { password.error = "Enter the WebDAV password"; return@setOnClickListener }
            val request = generation
            val connect = dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE)
            connect.isEnabled = false; status.text = "Checking AltMount's WebDAV…"
            worker.execute {
                try {
                    AltMountLibrary().verify(library.origin, name, secret)
                    if (cancelled.get()) return@execute
                    store.putAll(mapOf(AltMountLibrary.ORIGIN to library.origin, AltMountLibrary.USER to name, AltMountLibrary.PASSWORD to secret))
                    runOnUiThread {
                        if (request == generation && !isFinishing && !isDestroyed) {
                            if (dialog.isShowing) dialog.dismiss()
                            toast("AltMount library connected"); then()
                        }
                    }
                } catch (error: Exception) {
                    runOnUiThread { if (dialog.isShowing) { connect.isEnabled = true; status.text = uiErrorMessage(error) } }
                }
            }
        }
    }

    /** AltMount's imported files, newest first; one plays straight from WebDAV. */
    private fun altMountLibraryPage(library: AltMountSource, shown: Int = AIO_LIBRARY_PAGE) {
        current = { altMountLibraryPage(library, shown) }; destination = "library"; refreshNavigation()
        val id = page("${library.provider.name} library", "Files AltMount has imported, newest first. Select one to play it.")
        val login = altMountLogin(library.origin)
        if (login == null) {
            body.addView(button("Connect the AltMount library  ›") { signInAltMount(library) { altMountLibraryPage(library) } }, margin(-1, 44))
            focusAfterRender(id); return
        }
        read(id, {
            try { AltMountLibrary().files(library.origin, login.first, login.second) }
            catch (error: ApiException) { if (error.code == 401 || error.code == 403) null else throw error }
        }) { files ->
            if (files == null) {
                store.putAll(mapOf(AltMountLibrary.PASSWORD to ""))
                body.addView(text("AltMount no longer accepts the saved WebDAV login. Connect again to see the library.", 15, muted), margin(-1, -2, bottom = 10))
                body.addView(button("Connect again  ›") { signInAltMount(library) { altMountLibraryPage(library) } }, margin(-1, 44))
                focusAfterRender(id); return@read
            }
            if (files.isEmpty()) body.addView(text("Nothing in this library yet. Files AltMount imports appear here.", 15, muted))
            val headers = mapOf("Authorization" to AltMountLibrary.authorization(login.first, login.second))
            val open: (AltMountLibrary.File) -> Unit = { file ->
                playingAddon = AddonBadges.ALTMOUNT
                play(AltMountLibrary.url(library.origin, file), file.name, null, "", headers = headers)
            }
            fun caption(file: AltMountLibrary.File) = listOfNotNull(file.folder.takeIf { it.isNotBlank() },
                SourceMetadata.sizeLabel(file.sizeBytes).takeIf { it.isNotBlank() }, "AltMount library").joinToString(" · ")
            val page = files.take(shown)
            if (flix) libraryGrid(page.map { file -> LibraryEntry("altlib:${file.path}", file.name, caption(file)) { open(file) } })
            else {
                val choices = TvChoiceList(this); body.addView(choices, margin(-1, -2))
                page.forEach { file -> choices.addChoice(cloudRow("altlib:${file.path}", file.name, caption(file)) { open(file) }) }
            }
            if (files.size > shown) body.addView(button("Show more") { altMountLibraryPage(library, shown + AIO_LIBRARY_PAGE) }.apply { gravity = Gravity.CENTER },
                margin(200, 44, top = 12))
            focusAfterRender(id)
        }
    }

    /** An AIOStreams library: the files streamed through it (Usenet and others), with posters found from the names. */
    private fun aioLibraryPage(library: AioLibrary.Source, shown: Int = AIO_LIBRARY_PAGE) {
        current = { aioLibraryPage(library, shown) }; destination = "library"; refreshNavigation()
        val id = page("${library.provider} library", "Files in your AIOStreams library, newest first. Select one to play it.")
        val api = AioLibrary()
        read(id, {
            // Pages of AIOStreams' size until [shown] files are loaded, or the library ends.
            val all = mutableListOf<AioLibrary.Entry>()
            while (all.size < shown) {
                val next = api.entries(library, all.size)
                all += next.filterNot { entry -> all.any { it.id == entry.id } }
                if (next.isEmpty() || next.size < AIO_LIBRARY_PAGE / 2) break
            }
            all
        }) { entries ->
            if (entries.isEmpty()) body.addView(text("Nothing in this library yet. Files you stream through AIOStreams appear here.", 15, muted))
            val open: (AioLibrary.Entry) -> Unit = { entry ->
                val request = generation
                toast("Opening ${entry.name.take(60)}…")
                worker.execute {
                    val link = runCatching { api.link(library, entry) }.getOrNull()
                    runOnUiThread {
                        if (request != generation || isDestroyed) return@runOnUiThread
                        if (link == null) toast("AIOStreams has no playable link for this file right now.")
                        else { playingAddon = AddonBadges.AIOSTREAMS; play(link.url, entry.name, null, "", headers = link.headers) }
                    }
                }
            }
            if (flix) libraryGrid(entries.map { entry -> LibraryEntry("aiolib:${entry.id}", entry.name, "AIOStreams library") { open(entry) } })
            else {
                val choices = TvChoiceList(this); body.addView(choices, margin(-1, -2))
                entries.forEach { entry -> choices.addChoice(cloudRow("aiolib:${entry.id}", entry.name, "AIOStreams library") { open(entry) }) }
            }
            if (entries.size >= shown) body.addView(button("Show more") { aioLibraryPage(library, shown + AIO_LIBRARY_PAGE) }.apply { gravity = Gravity.CENTER },
                margin(200, 44, top = 12))
            focusAfterRender(id)
        }
    }

    private fun offcloudCloud() {
        current = { offcloudCloud() }; destination = "library"; refreshNavigation()
        val id = page("My Offcloud", "Your Offcloud downloads. Open a completed download to choose a video.")
        val api = OffcloudApi(store)
        if (!api.connected()) {
            body.addView(button("Connect Offcloud in Settings") { navigate { debridServicesPage() } }, margin(260, 44)); return
        }
        body.addView(button("Refresh downloads") { offcloudCloud() }, margin(220, 44, bottom = 10))
        read(id, { api.cloud() }) { downloads ->
            if (downloads.isEmpty()) body.addView(text("No Offcloud downloads yet. Choose a source from a movie or episode.", 15, muted))
            val open: (OffcloudDownload) -> Unit = { download ->
                if (download.status == "downloaded") navigate { offcloudCloudFiles(download) }
                else toast("This download is ${download.status.ifBlank { "not ready" }}. Refresh after Offcloud finishes processing it.")
            }
            if (flix) libraryGrid(downloads.map { download ->
                LibraryEntry("offcloud:${download.id}", download.name, if (download.status == "downloaded") "Ready" else download.status.ifBlank { "Not ready" }) { open(download) }
            }) else {
                val choices = TvChoiceList(this); body.addView(choices, margin(-1, -2))
                downloads.forEach { download -> choices.addChoice(cloudRow("offcloud:${download.id}", download.name, download.status) { open(download) }) }
            }
            focusAfterRender(id)
        }
    }

    private fun offcloudCloudFiles(download: OffcloudDownload) {
        current = { offcloudCloudFiles(download) }
        val id = page(download.name, "Choose a video file from Offcloud.")
        val api = OffcloudApi(store)
        read(id, { api.cloudFiles(download) }) { files ->
            val choices = TvChoiceList(this); body.addView(choices, margin(-1, -2))
            if (files.isEmpty()) body.addView(text("No playable video files in this download.", 15, muted))
            files.forEach { file ->
                choices.addChoice(cloudRow("offcloud:${download.id}:${file.id}", file.name,
                    if (file.size > 0) "${file.size / (1024 * 1024)} MB" else "Offcloud video") {
                    if (api.ownsTag(download.accountTag)) play(file.url, file.name, null, "offcloud:${download.id}:${file.id}", "offcloud")
                    else toast("The Offcloud account changed. Refresh your library.")
                })
            }
            focusAfterRender(id)
        }
    }

    private fun cloud(kind: String) {
        current = { cloud(kind) }
        destination = "library"; refreshNavigation()
        val id = page("My TorBox", "Your library, ready for the big screen.")
        if (!store.has("torbox.token")) { body.addView(button("Connect TorBox") { startTorBox() }, margin(190, 44)); return }
        if (DeviceLayout.phone) phoneTabs(listOf("torrents" to "Torrents", "usenet" to "Usenet", "webdl" to "Web").map { (key, title) -> title to { cloud(key) } })
        else {
            val tabs = horizontalRow()
            listOf("torrents" to "Torrents", "usenet" to "Usenet", "webdl" to "Web downloads").forEach { (key, title) -> tabs.addView(button(title) { cloud(key) }, margin(150, 39, right = 8)) }
        }
        read(id, { torbox.cloud(kind) }) { folders ->
            if (folders.isEmpty()) body.addView(text("Nothing here yet. Your TorBox downloads will appear in this library.", 15, muted))
            if (flix) libraryGrid(folders.map { folder ->
                LibraryEntry(TvChoiceKeys.folder(folder.kind, folder.id), folder.name, if (folder.ready) "Ready" else "Not ready yet") { navigate { cloudFolder(folder) } }
            }) else {
                val choices = TvChoiceList(this); body.addView(choices, margin(-1, -2))
                folders.forEach { folder -> choices.addChoice(cloudRow(TvChoiceKeys.folder(folder.kind, folder.id),
                    folder.name, if (folder.ready) "Ready to browse" else "Not ready yet") {
                    navigate { cloudFolder(folder) }
                }) }
            }
        }
    }

    private fun cloudFolder(folder: CloudFolder) {
        current = { cloudFolder(folder) }
        val id = page(folder.name, "Choose a video file.")
        read(id, { torbox.folder(folder.kind, folder.id) }) { result ->
            val files = result.files.filter { Regex("\\.(mkv|mp4|m4v|avi|mov|webm|ts|m2ts|mpg|mpeg)$", RegexOption.IGNORE_CASE).containsMatchIn(it.name) }
            if (files.isEmpty()) body.addView(text("No playable video files are available yet.", 15, muted))
            val choices = TvChoiceList(this); body.addView(choices, margin(-1, -2))
            files.forEach { file -> choices.addChoice(cloudRow(TvChoiceKeys.file(result.kind, result.id, file.id),
                file.name, fileSize(file)) { cloudChoice(result, file) }) }
        }
    }

    /** Looks up catalog artwork for a download's filename; artwork hints never become playback or tracking identities. */
    private fun cloudArtworkFor(query: CloudMetadata.Query, done: (MediaItemData?) -> Unit) {
        val cacheKey = "${query.type}:${query.title}:${query.year}"
        val request = generation
        metadataWorker.execute {
            if (request != generation || Thread.currentThread().isInterrupted) return@execute
            val found = synchronized(cloudArtwork) {
                if (cloudArtwork.containsKey(cacheKey)) Result.success(cloudArtwork[cacheKey]) else runCatching {
                    val candidates = query.type?.let { catalog.search(query.title, it) } ?: catalog.searchAll(query.title).items
                    CloudMetadata.match(query, candidates)
                }.onSuccess { if (cloudArtwork.size > 200) cloudArtwork.clear(); cloudArtwork[cacheKey] = it }
            }
            runOnUiThread { if (request == generation && !isDestroyed) done(found.getOrNull()) }
        }
    }

    private class LibraryEntry(val key: String, val name: String, val caption: String, val action: () -> Unit)

    /** Flix library: downloads as landscape cards in the same four-column grid as "See all", with matched artwork. */
    private fun libraryGrid(entries: List<LibraryEntry>) {
        val width = DeviceLayout.canvasWidth - theme.layout.railWidthDp - theme.layout.contentInsetDp * 2
        val count = if (DeviceLayout.phone) DeviceLayout.phoneColumns(width) else 4
        val cardWidth = (width - 6 - (count - 1) * theme.layout.cardGapDp) / count
        entries.chunked(count).forEach { chunk ->
            val row = LinearLayout(this).apply { tag = "media-grid-row"; clipChildren = false }
            body.addView(row, margin(-1, -2, top = 14))
            chunk.forEach { entry ->
                val query = CloudMetadata.query(entry.name)
                val title = query?.let { listOf(it.title, it.year).filter(String::isNotBlank).joinToString(" ") } ?: entry.name
                val card = tile(MediaItemData("", query?.type ?: "movie", title), caption = entry.caption, action = entry.action) as FrameLayout
                card.tag = entry.key; card.contentDescription = "$title, ${entry.caption}"
                row.addView(card, margin(cardWidth, cardWidth * 9 / 16, right = theme.layout.cardGapDp))
                if (query != null) cloudArtworkFor(query) { match ->
                    val address = match?.background?.ifBlank { match.poster }.orEmpty()
                    val art = ((card.getChildAt(0) as? ViewGroup)?.getChildAt(0) as? ImageView)
                    if (art != null && card.isAttachedToWindow && address.isNotBlank()) image(art, thumbnail(address))
                }
            }
        }
    }

    private fun cloudRow(key: String, filename: String, detail: String, action: () -> Unit): TvChoiceRow {
        val row = TvChoiceRow(this, key, filename, detail, palette = theme.palette, withPoster = true, action = action)
        val query = CloudMetadata.query(filename) ?: return row
        fun loadPoster() = cloudArtworkFor(query) { found ->
            if (row.isAttachedToWindow) found?.poster?.takeIf(String::isNotBlank)?.let { image(row.poster, it) }
        }
        row.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) { if (row.poster.drawable == null) loadPoster() }
            override fun onViewDetachedFromWindow(view: View) = Unit
        })
        return row
    }
    private fun fileSize(file: CloudFile) = String.format(java.util.Locale.getDefault(), "%.2f GB", file.size.toDouble() / 1_000_000_000)

    /** Debug-only, offline fixture uses the same cards/navigation without loading any account or library. */
    private fun sourceChoiceAudit() {
        current = { sourceChoiceAudit() }
        val id = page("Source navigation check", "Seven synthetic source versions. No account or playback request is made.")
        val choices = TvChoiceList(this); body.addView(choices, margin(-1, -2))
        repeat(7) { index ->
            val source = SourceItem("Version ${index + 1} · Example Movie 2026 extended edition with a deliberately long title to verify every row remains independently selectable",
                "Public fixture", infoHash = String.format(java.util.Locale.ROOT, "%040x", index + 1), fileIndex = index, cached = true)
            choices.addChoice(TvChoiceRow(this, TvChoiceKeys.source(source), source.title,
                "1080p · synthetic file · no network", "READY IN TORBOX · Public fixture", theme.palette) {
                navigate { sourceChoiceAuditFiles(index + 1) }
            })
        }
        focusAfterRender(id)
    }

    private fun sourceChoiceAuditFiles(sourceVersion: Int) {
        current = { sourceChoiceAuditFiles(sourceVersion) }
        val id = page("Choose a video version", "Selected source version $sourceVersion. All filenames below are synthetic.")
        val selection = text("Choose a file version", 13, accent).apply { tag = "fixture:selection" }
        body.addView(selection, margin(-1, -2, bottom = 12))
        val choices = TvChoiceList(this); body.addView(choices, margin(-1, -2))
        repeat(7) { index ->
            choices.addChoice(TvChoiceRow(this, TvChoiceKeys.file("torrents", 1234, 101L + index),
                "File version ${index + 1} · Example Movie with a long synthetic filename.1080p.mkv", "${index + 1}.00 GB", palette = theme.palette) {
                selection.text = "Selected file version ${index + 1}"
            })
        }
        focusAfterRender(id)
    }

    private fun cloudChoice(folder: CloudFolder, file: CloudFile) {
        val choices = arrayOf("Play file", "Identify movie for ${wetrakr.name}", "Identify episode for ${wetrakr.name}")
        AlertDialog.Builder(this).setTitle(file.name).setItems(choices) { _, choice ->
            if (choice == 0) playCloud(folder, file, null)
            else identifyCloud(folder, file, if (choice == 1) "movie" else "series")
        }.setNegativeButton("Cancel", null).show()
    }

    private fun identifyCloud(folder: CloudFolder, file: CloudFile, type: String) {
        val input = TvEditText(this).apply { hint = "Search title"; setSingleLine() }
        AlertDialog.Builder(this).setTitle("Identify ${if (type == "movie") "movie" else "show"}").setView(input).setPositiveButton("Search") { _, _ ->
            val query = input.text.toString().trim()
            if (query.isBlank()) return@setPositiveButton
            load(generation, { catalog.search(query, type) }) { items ->
                if (items.isEmpty()) { toast("No titles found"); return@load }
                AlertDialog.Builder(this).setTitle("Choose the correct title").setItems(items.map { "${it.title} (${it.year})" }.toTypedArray()) { _, index ->
                    val item = items[index]; val identity = PlaybackIdentity(item.imdbId, type, item.title)
                    if (type == "movie") playCloud(folder, file, identity)
                    else load(generation, { catalog.details(item) }) { detail ->
                        chooseCloudEpisode(folder, file, detail)
                    }
                }.setNegativeButton("Cancel", null).show()
            }
        }.setNegativeButton("Cancel", null).show().submitOnEnter(input)
    }

    private fun chooseCloudEpisode(folder: CloudFolder, file: CloudFile, detail: MediaItemData) {
        val seasons = EpisodePresentation.seasons(detail.episodes)
        if (seasons.isEmpty()) { toast("Episode information unavailable"); return }
        AlertDialog.Builder(this).setTitle("Choose a season for ${detail.title}")
            .setItems(seasons.map { if (it == 0) "Specials & extras" else "Season $it" }.toTypedArray()) { _, index ->
                val season = seasons[index]
                val episodes = detail.episodes.filter { it.season == season }.sortedBy { it.episode }
                AlertDialog.Builder(this).setTitle(if (season == 0) "Choose a special" else "Choose a Season $season episode")
                    .setItems(episodes.map { "${if (season == 0) "Special" else "Episode"} ${it.episode}  ${it.title}" }.toTypedArray()) { _, e ->
                        val episode = episodes[e]
                        if (EpisodePresentation.future(episode.released)) toast("This episode has not been released yet")
                        else playCloud(folder, file, PlaybackIdentity(detail.imdbId, "series", detail.title, season, episode.episode))
                    }.setNeutralButton("Seasons") { _, _ -> chooseCloudEpisode(folder, file, detail) }
                    .setNegativeButton("Cancel", null).show()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun playCloud(folder: CloudFolder, file: CloudFile, identity: PlaybackIdentity?) {
        playingSourceText = ""; mediaFile(file.name, file.size)
        streamFile(folder, file, file.name, identity, "cloud:${folder.kind}:${folder.id}:${file.id}")
    }

    private fun streamFile(folder: CloudFolder, file: CloudFile, title: String, identity: PlaybackIdentity?, key: String, resolveUrl: (() -> String)? = null) {
        if (resolvingGeneration == generation || launchingPlayer) { toast("Preparing the selected file…"); return }
        val request = generation; resolvingGeneration = request
        val progress = text("Preparing your video…", 14, accent); body.addView(progress, margin(-1, -2, top = 14))
        worker.execute {
            try {
                val url = resolveUrl?.invoke() ?: torbox.streamUrl(folder.kind, folder.id, file.id)
                runOnUiThread {
                    if (request == generation && !isFinishing && !isDestroyed) {
                        resolvingGeneration = null; body.removeView(progress); play(url, title, identity, key, "torbox")
                    }
                }
            } catch (error: Exception) { runOnUiThread {
                if (request == generation && !isFinishing && !isDestroyed) {
                    resolvingGeneration = null; body.removeView(progress); showError(error); focusAfterRender(request)
                }
            } }
        }
    }

    /** Set while a link picked in the player's Streams panel is being prepared: the new link continues from here. */
    private var switchResumeMs: Long? = null

    private fun play(url: String, title: String, identity: PlaybackIdentity?, key: String, debridService: String? = null, resumeImmediately: Boolean = false,
                     headers: Map<String, String> = emptyMap()) {
        if (launchingPlayer) return
        if (Uri.parse(url).scheme !in setOf("https", "http")) { toast("This stream did not return a playable web link."); return }
        launchingPlayer = true; playerShown = true
        val remotePercent = identity?.let { remoteProgress[identityKey(it)] } ?: 0.0
        val start = {
            val intent = Intent(this, PlayerActivity::class.java).putExtra("url", url).putExtra("title", title)
                .putExtra("identity", identity?.toJson()?.toString()).putExtra("key", key).putExtra("resume", switchResumeMs ?: history.resume(key)).putExtra("resume_percent", remotePercent)
                .putExtra("debrid_service", PlaybackDebrid.normalize(debridService)).putExtra("cached_playback", resumeImmediately)
                .putExtra("playback_account", LastPlayedLinks(store).scope(PlaybackDebrid.normalize(debridService)))
                .putExtra("media_name", playingMedia?.first.orEmpty()).putExtra("media_size", playingMedia?.second ?: 0L)
                .putExtra("headers", org.json.JSONObject(headers).toString()).putExtra("source_addon", playingAddon)
            playingMedia = null; playingSourceText = ""; switchResumeMs = null; playingAddon = null
            @Suppress("DEPRECATION")
            startActivityForResult(intent, PLAYBACK_REQUEST)
            identity?.let { searchStreamsInBackground(it) }
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
        // Playback always continues where it left off; the timeline can still scrub back to the start.
        start()
    }

    private fun weTrakrPage() {
        current = { weTrakrPage() }
        destination = "wetrakr"; refreshNavigation()
        val tracker = weTrakrClient()
        val id = page("My ${tracker.name}", "Your watchlist. Your progress. Your next episode.")
        if (tracker.signedIn() && !tracker.enabled()) {
            body.addView(text("${tracker.name} is switched off. Turn it on in Settings → Tracking.", 15, muted))
            body.addView(button("Open Settings") { navigate { settings() } }, margin(210, 44, top = 10)); return
        }
        if (!tracker.connected()) { body.addView(button("Connect ${tracker.name}") { startWeTrakr() }, margin(210, 44)); return }
        if (flix) { flixWeTrakrPage(id); return }
        // Start with the visual library shelves; progress rows must not push them off-screen.
        read(id, { tracker.watchlist("movie") to tracker.watchlist("series") }) { (movies, series) ->
            mediaRow("Movie watchlist", movies); mediaRow("TV watchlist", series)
            likedLists(id, false)
            if (tracker.supportsLists) body.addView(button("Personal lists") { navigate { weTrakrLists() } }, margin(180, 42, top = 18))
            weTrakrProgress(id)
        }
    }

    /**
     * Flix WeTrakr: the same shelves as Home. Each section has a fixed slot so they keep their order while they
     * load independently. Playing from here always searches fresh links (and resumes the saved position).
     */
    private fun flixWeTrakrPage(id: Int) {
        fun slot() = column().also { body.addView(it, margin(-1, -2)) }
        val progressSlot = slot(); val nextSlot = slot(); val movieSlot = slot(); val showSlot = slot(); val personalSlot = slot()
        val likedAnchor = slot()
        fun heading(host: LinearLayout, title: String) = host.addView(text(title, theme.layout.sectionTitleSizeSp, ink, true), margin(-1, -2, top = 14, bottom = 6))
        read(id, { wetrakr.continueWatching() }) { progress ->
            if (progress.isEmpty()) return@read
            heading(progressSlot, "Continue Watching")
            val row = horizontalRow(progressSlot)
            progress.forEach { (identity, percent) ->
                remoteProgress[identityKey(identity)] = percent
                val caption = listOf(if (identity.type == "series") "S${identity.season} E${identity.episode}" else "", "${percent.toInt()}%").filter { it.isNotBlank() }.joinToString(" · ")
                val item = MediaItemData(identity.imdbId, identity.type, identity.title)
                val open: () -> Unit = { navigate { showSources(identity) } }
                row.addView((if (resumeCards()) resumeCard(item, percent / 100, episodeLine(identity), "${percent.toInt()}% watched", open) else tile(item, percent / 100, caption, open))
                    .apply { continueOptions(this, "", identity, identity.title) }, continueSlot())
            }
        }
        read(id, { wetrakr.nextEpisodes() }) { next ->
            if (next.isEmpty()) return@read
            heading(nextSlot, "Up Next")
            val row = horizontalRow(nextSlot)
            next.forEach { identity ->
                row.addView(tile(MediaItemData(identity.imdbId, "series", identity.title), caption = "S${identity.season} E${identity.episode}") { navigate { showSources(identity) } },
                    margin(cardWidth(), theme.layout.cardHeightDp, right = theme.layout.cardGapDp))
            }
        }
        read(id, { wetrakr.watchlist("movie") }) { mediaRow("Movie watchlist", it, movieSlot) }
        read(id, { wetrakr.watchlist("series") }) { mediaRow("TV watchlist", it, showSlot) }
        if (wetrakr.supportsLists) read(id, { wetrakr.lists() }) { lists ->
            lists.forEach { (listId, title) ->
                val listSlot = column().also { personalSlot.addView(it, margin(-1, -2)) }
                read(id, { wetrakr.listItems(listId) }) { items ->
                    listSlot.setTag(R.id.pov_list_scope, "playlist:$listId")
                    mediaRow(title, items, listSlot) { navigate { personalListGrid(listId, title) } }
                }
            }
        }
        flixLikedLists(id, likedAnchor)
    }

    private fun weTrakrProgress(id: Int) {
        val host = column(); body.addView(host, margin(-1, -2, top = 12))
        read(id, { wetrakr.continueWatching() }) { progress ->
            if (progress.isNotEmpty()) {
                host.addView(text("Continue watching", 20, ink, true), margin(-1, -2, bottom = 8))
                progress.forEach { (identity, percent) ->
                    remoteProgress[identityKey(identity)] = percent
                    host.addView(button("${identity.title}${if (identity.type == "series") "  ·  S${identity.season} E${identity.episode}" else ""}   ·   ${percent.toInt()}%") {
                        navigate { showSources(identity) }
                    }.apply { continueOptions(this, "", identity, identity.title) }, margin(-1, 44, bottom = 7))
                }
            }
        }
        val nextGroup = column(); body.addView(nextGroup, margin(-1, -2, top = 16))
        read(id, { wetrakr.nextEpisodes() }) { next ->
            if (next.isNotEmpty()) {
                nextGroup.addView(text("Up next", 20, ink, true), margin(-1, -2, bottom = 12))
                next.forEach { identity -> nextGroup.addView(button("${identity.title}   ·   S${identity.season} E${identity.episode}") {
                    navigate { showSources(identity) }
                }, margin(-1, 44, bottom = 7)) }
            }
        }
    }

    private fun weTrakrLists() {
        current = { weTrakrLists() }
        val tracker = weTrakrClient()
        val id = page("Your ${tracker.name} lists")
        read(id, { tracker.lists() }) { lists ->
            if (lists.isEmpty()) body.addView(text("No personal lists yet.", 15, muted))
            lists.forEach { (listId, title) -> body.addView(button(title) {
                navigate { personalListGrid(listId, title) }
            }, margin(-1, 44, bottom = 8)) }
        }
    }

    /** Settings → Debrid services: order, switch on or off, and connect or remove each account. */
    private fun debridServicesPage(focusTag: String? = null) {
        val keepScroll = scroller.scrollY
        current = { debridServicesPage() }
        destination = "settings"; refreshNavigation()
        page("Debrid services", "Cached links play from the first service that's on, in this order. Off keeps the account saved but skips it.")
        val order = DebridServices.priority(store)
        val list = settingsGroup("Services · 1 plays first", "library")
        order.forEachIndexed { index, id ->
            settingsDivider(list)
            val name = DebridServices.name(id)
            val linked = store.has(DebridServices.credential(id))
            val on = linked && DebridServices.enabled(store, id)
            val line = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(8), dp(8), dp(8)) }
            val copy = column()
            copy.addView(text("${index + 1}. $name", 14, if (on) ink else muted, true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
            copy.addView(text(if (!linked) "Not connected" else if (on) "On" else "Off · account kept", 11, if (on) 0xFF46D369.toInt() else muted), margin(-1, -2, top = 2))
            line.addView(copy, LinearLayout.LayoutParams(0, -2, 1f))
            fun control(label: String, tagName: String, description: String, width: Int, enabled: Boolean = true, action: () -> Unit) {
                line.addView(button(label) { if (enabled) action() }.apply {
                    gravity = Gravity.CENTER; tag = "$tagName:$id"; contentDescription = description
                    alpha = if (enabled) 1f else 0.3f; isFocusable = enabled
                }, margin(width, 38).apply { leftMargin = dp(6) })
            }
            control("▲", "debrid-up", "Move $name up", 46, index > 0) { DebridServices.move(store, index, -1); debridServicesPage("debrid-up:$id") }
            control("▼", "debrid-down", "Move $name down", 46, index < order.lastIndex) { DebridServices.move(store, index, 1); debridServicesPage("debrid-down:$id") }
            if (linked) control(if (on) "On" else "Off", "debrid-on", "$name · ${if (on) "On" else "Off"}", 56) {
                DebridServices.setEnabled(store, id, !on); debridServicesPage("debrid-on:$id")
            } else line.addView(View(this), margin(56, 38).apply { leftMargin = dp(6) })
            control(if (linked) "Manage" else "Connect", "debrid-manage", if (linked) "Manage $name" else "Connect $name", 84) { debridActions(id) }
            list.addView(line, margin(-1, -2))
        }
        body.addView(text("Real-Debrid, AllDebrid and Debrid-Link can't say in advance what's cached, so their links are checked when you play: " +
            "the first cached one of the top ${LinkDebrids.CHECK_LIMIT} plays. Premiumize, Offcloud and TorBox are checked while the link list loads.", 11, muted).apply {
            setLineSpacing(dp(1).toFloat(), 1f) }, margin(-1, -2, top = 12))
        body.addView(View(this), margin(-1, 24))
        if (focusTag != null) keepPlace(generation, focusTag, keepScroll) else focusAfterRender(generation)
    }

    /** Connect, replace the key of, or remove one debrid account. */
    private fun debridActions(id: String) {
        val name = DebridServices.name(id)
        val linked = store.has(DebridServices.credential(id))
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        when (id) {
            "offcloud" -> actions += (if (linked) "Replace API key" else "Connect with your API key") to { connectOffcloud() }
            "torbox" -> {
                actions += "Sign in with a code" to { startTorBox() }
                actions += (if (linked) "Replace with an API key" else "Paste an API key") to {
                    inputSetting("TorBox API key", "Paste your own TorBox API key", "", true) { key -> load(generation, { torbox.setApiKey(key) }) { toast("TorBox connected"); refreshSettings() } }
                }
            }
            else -> actions += (if (linked) "Replace API key" else "Connect with your API key") to {
                val links = LinkDebrids(store)
                apiKeyDialog?.dismiss()
                apiKeyDialog = ApiKeyDialog(this, name, worker,
                    save = { key, cancelled -> links.setKey(id, key, cancelled) },
                    onConnected = {
                        toast("$name connected")
                        if (destination == "settings") refreshSettings()
                    }).also { it.show() }
            }
        }
        if (linked) actions += "Disconnect" to {
            confirm("Disconnect $name on this device?") {
                when (id) { "offcloud" -> OffcloudApi(store).disconnect(); "torbox" -> torbox.disconnect(); else -> LinkDebrids(store).disconnect(id) }
                DebridServices.setEnabled(store, id, true)
                refreshSettings()
            }
        }
        if (!linked && actions.size == 1) { actions.single().second(); return }
        AlertDialog.Builder(this).setTitle(name).setItems(actions.map { it.first }.toTypedArray()) { _, index -> actions[index].second() }
            .setNegativeButton("Cancel", null).show()
    }

    private fun easynewsActions() {
        if (Easynews.credentials(store) == null) { connectEasynews(); return }
        val on = Easynews.enabled(store)
        val actions = listOf<Pair<String, () -> Unit>>(
            (if (on) "Turn off (keep the login)" else "Turn on") to { Easynews.setEnabled(store, !on); refreshSettings() },
            "Download server · ${Easynews.serverLabel(store)}" to { easynewsServer() },
            "Connections · ${Easynews.connections(store)}" to { easynewsConnections() },
            "Easynews NNTP streaming · ${if (EasynewsNntp.enabled(store)) "On" else "Off"}" to {
                val now = !EasynewsNntp.enabled(store)
                if (now) store.put(EasynewsNntp.ON, "true") else store.remove(EasynewsNntp.ON)
                toast(if (now) "Easynews links from your next search stream over NNTP when they can" else "Easynews links stream from the web servers"); refreshSettings()
            },
            "NNTP connections · ${EasynewsNntp.connections(store)}" to {
                NumberStepper.show(this, "NNTP connections", "Easynews allows 60 at once across all your devices and apps. Applies from the next video.",
                    EasynewsNntp.connections(store), 1..EasynewsNntp.MAX_CONNECTIONS, { "$it ${if (it == 1) "connection" else "connections"}" }) { count ->
                    store.put(EasynewsNntp.CONNECTIONS, count.toString()); refreshSettings()
                }
            },
            "NNTP speed test" to { nntpSpeedTest() },
            "NNTP pipeline · ${EasynewsNntp.pipeline(store)}" to {
                NumberStepper.show(this, "NNTP pipeline", "Requests kept in flight per connection (default ${EasynewsNntp.DEFAULT_PIPELINE}). Higher can stream faster on distant servers. Applies from the next video.",
                    EasynewsNntp.pipeline(store), 1..EasynewsNntp.MAX_PIPELINE, { "Pipeline $it" }) { depth ->
                    store.put(EasynewsNntp.PIPELINE, depth.toString()); refreshSettings()
                }
            },
            "Change login" to { connectEasynews() },
            "Disconnect" to { confirm("Disconnect Easynews on this device?") { Easynews.disconnect(store); Easynews.setEnabled(store, true); refreshSettings() } })
        AlertDialog.Builder(this).setTitle("Easynews · ${if (on) "On" else "Off"}").setItems(actions.map { it.first }.toTypedArray()) { _, index -> actions[index].second() }
            .setNegativeButton("Cancel", null).show()
    }

    /** Downloads from the Easynews news server at several connection counts and lists the speeds; takes about 90 seconds. */
    private fun nntpSpeedTest() {
        val lines = TextView(this).apply { setPadding(dp(18), dp(10), dp(18), dp(10)); textSize = 14f; setTextColor(Color.WHITE); text = "Starting…" }
        val dialog = AlertDialog.Builder(this).setTitle("NNTP speed test").setView(ScrollView(this).apply { addView(lines) })
            .setPositiveButton("Close", null).show()
        val shown = StringBuilder()
        Thread({
            EasynewsNntp.speedTest(store) { line -> runOnUiThread { if (dialog.isShowing) { shown.appendLine(line); lines.text = shown } } }
        }, "nntp-speedtest").start()
    }

    // ---- Settings → Sources → Usenet: news providers, indexers and the indexer headers ----

    private fun usenetPage(focusTag: String? = null) {
        val keepScroll = scroller.scrollY
        current = { usenetPage() }
        destination = "settings"; refreshNavigation()
        page("Usenet", "Search your indexers and stream straight from your news providers. Nothing is downloaded first; the article list is fetched only when you play a link.")
        val providers = try { NewsProviders(store).load() } catch (error: Exception) { body.addView(text(uiErrorMessage(error), 15, muted)); emptyList() }
        val providerCard = settingsGroup("News providers", "library")
        if (providers.isNotEmpty()) holdHint(providerCard, "a provider")
        linkColumns(providers.map { provider ->
            switchSettingsRow(providerCard, provider.name, if (provider.backup) "Backup" else "Main",
                "${provider.host}:${provider.port} · ${if (provider.tls) "SSL" else "No SSL"} · ${provider.connections} connections · pipeline ${provider.pipelineDepth}",
                provider.enabled, "provider-switch",
                toggle = { saveNewsProviders(providers.map { if (it.id == provider.id) it.copy(enabled = !provider.enabled) else it }, "provider-switch:${provider.name}") },
                menu = { newsProviderActions(provider) })
        })
        val warm = NewsProviders.warm { store.get(it) }
        settingsRow(providerCard, "Warm connections", if (warm == 0) "Off" else "$warm",
            "Signs in to your main provider while the link list is open, and keeps that many connections through a pause, so playing starts without the sign-in wait. Off frees those connection slots.") {
            val next = NewsProviders.WARM_CHOICES[(NewsProviders.WARM_CHOICES.indexOf(warm) + 1) % NewsProviders.WARM_CHOICES.size]
            store.put(NewsProviders.WARM_KEY, next.toString()); usenetPage("setting:Warm connections")
        }
        settingsRow(providerCard, "Add news provider", "", "Pick yours from the list or enter any server by hand. A backup provider is asked only for articles the main one is missing.") { chooseNewsProviderPreset() }

        val indexerConfig = NewznabIndexers(store)
        val indexers = try { indexerConfig.load() } catch (error: Exception) { body.addView(text(uiErrorMessage(error), 15, muted)); emptyList() }
        val indexerCard = settingsGroup("Indexers", "library")
        if (indexers.isNotEmpty()) holdHint(indexerCard, "an indexer")
        linkColumns(indexers.map { indexer ->
            val host = runCatching { java.net.URI(indexer.url).host }.getOrNull().orEmpty()
            switchSettingsRow(indexerCard, indexer.name, host, indexerConfig.usage(indexer.id).takeIf { it.isNotBlank() }?.let { "Today: $it" }.orEmpty(),
                indexer.enabled, "indexer-switch",
                toggle = { saveIndexers(indexers.map { if (it.id == indexer.id) it.copy(enabled = !indexer.enabled) else it }, "indexer-switch:${indexer.name}") },
                menu = { indexerActions(indexer) })
        })
        val fallbackOnly = dev.pov.tv.data.UsenetSearch.fallbackOnly(store)
        settingsRow(indexerCard, "Usenet Fallback", if (fallbackOnly) "On" else "Off",
            "On: your indexers are searched only when add-ons and debrid services find no links for a title. Off: searched together with everything else.",
            status = fallbackOnly) {
            if (fallbackOnly) store.remove(dev.pov.tv.data.UsenetSearch.FALLBACK_ONLY) else store.put(dev.pov.tv.data.UsenetSearch.FALLBACK_ONLY, "true")
            usenetPage("setting:Usenet Fallback")
        }
        settingsRow(indexerCard, "Add indexer", "", if (providers.none { it.enabled }) "Add a news provider first: indexer links need one to play."
            else "Pick a known indexer and enter your API key, or enter any Newznab address (NZBHydra2 and Prowlarr work too).") { chooseIndexerPreset() }

        val headerCard = settingsGroup("Indexer headers", "library")
        val checked = store.get(UsenetHeaders.CHECKED).toLongOrNull()
        settingsRow(headerCard, "Search header", UsenetHeaders.query { store.get(it) }, "The client name sent with indexer searches. Many indexers only answer clients they know.") {
            editUsenetHeader("Search header", UsenetHeaders.QUERY, UsenetHeaders.DEFAULT_QUERY) }
        settingsRow(headerCard, "Download header", UsenetHeaders.grab { store.get(it) }, "The client name sent when a link's article list is fetched.") {
            editUsenetHeader("Download header", UsenetHeaders.GRAB, UsenetHeaders.DEFAULT_GRAB) }
        settingsRow(headerCard, "Update now", if (checked == null) "Not checked yet" else "Checked " + android.text.format.DateUtils.getRelativeTimeSpanString(checked, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS),
            "Moves both headers to their tools' newest versions. This also happens by itself about once a day while you have an indexer.") { updateUsenetHeaders() }
        body.addView(View(this), margin(-1, 24))
        if (focusTag != null) keepPlace(generation, focusTag, keepScroll) else focusAfterRender(generation)
    }

    private fun saveNewsProviders(providers: List<NewsProvider>, focus: String? = null) {
        try { NewsProviders(store).save(providers); usenetPage(focus) } catch (error: Exception) { toast(uiErrorMessage(error)) }
    }

    private fun newsProviderActions(provider: NewsProvider) {
        val all = runCatching { NewsProviders(store).load() }.getOrDefault(emptyList())
        val index = all.indexOfFirst { it.id == provider.id }
        val focus = "setting:${provider.name}"
        val actions = mutableListOf<Pair<String, () -> Unit>>(
            "Edit" to { editNewsProvider(provider, null) },
            "Test the login" to { testNewsProvider(provider) },
            (if (provider.enabled) "Turn off" else "Turn on") to { saveNewsProviders(all.map { if (it.id == provider.id) it.copy(enabled = !provider.enabled) else it }, focus) },
            (if (provider.backup) "Use as a main provider" else "Use as a backup only") to { saveNewsProviders(all.map { if (it.id == provider.id) it.copy(backup = !provider.backup) else it }, focus) })
        if (index > 0) actions += "Move up (asked earlier)" to { saveNewsProviders(all.toMutableList().apply { add(index - 1, removeAt(index)) }, focus) }
        if (index in 0 until all.lastIndex) actions += "Move down (asked later)" to { saveNewsProviders(all.toMutableList().apply { add(index + 1, removeAt(index)) }, focus) }
        actions += "Remove" to { confirm("Remove ${provider.name} from this device?") { saveNewsProviders(all.filter { it.id != provider.id }) } }
        AlertDialog.Builder(this).setTitle(provider.name).setItems(actions.map { it.first }.toTypedArray()) { _, picked -> actions[picked].second() }
            .setNegativeButton("Cancel", null).show()
    }

    private fun testNewsProvider(provider: NewsProvider) {
        toast("Signing in to ${provider.name}…")
        worker.execute {
            val result = try {
                val took = dev.pov.tv.usenet.Nntp.check(dev.pov.tv.usenet.NntpServer(provider.host, provider.port, provider.tls, provider.user, provider.password))
                "${provider.name}: signed in ($took ms)"
            } catch (error: Exception) { "${provider.name}: ${error.message ?: "couldn't connect"}" }
            runOnUiThread { if (!isFinishing && !isDestroyed) toast(result) }
        }
    }

    private fun chooseNewsProviderPreset() {
        val presets = NewsProviders.PRESETS
        val labels = (listOf("Custom (any server)") + presets.map { it.name }).toTypedArray()
        AlertDialog.Builder(this).setTitle("Add news provider").setItems(labels) { _, picked ->
            editNewsProvider(null, presets.getOrNull(picked - 1))
        }.setNegativeButton("Cancel", null).show()
    }

    /**
     * A form that may scroll, with its status line (Test results, errors) pinned underneath it, just above the dialog's
     * buttons, so the answer to Test is always in view without scrolling.
     */
    private fun formWithStatus(form: View, status: TextView): View = column().apply {
        status.maxLines = 3
        // On a TV the keyboard slides over the dialog (the form keeps its size); a phone's form shrinks to stay above it.
        addView(CappedScroll(this@MainActivity, resources.displayMetrics.heightPixels * 58 / 100, reserve = dp(64), followKeyboard = DeviceLayout.phone)
            .apply { addView(form) }, margin(-1, -2))
        addView(status, margin(-1, -2).apply { setMargins(dp(18), dp(4), dp(18), dp(4)) })
    }

    /** TV: the window pans to the field being typed in instead of squeezing the form; phone: resize above the keyboard. */
    private fun formInputMode() = if (DeviceLayout.phone) android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        else android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN

    /** A form field with its name in small type above it: the hint inside disappears once the field holds text. */
    private fun captioned(field: View): View = column().apply {
        val caption = (field as? TextView)?.takeIf { it !is android.widget.CheckBox }?.hint?.toString()?.substringBefore(" (")
        // The usual dialog box keeps 24 dp clear on each side (meant for full-width fields); in a captioned form the
        // box fills its column so the text has room and every box lines up with its caption.
        if (field is android.widget.EditText) field.background = getDrawable(R.drawable.pov_dialog_field_tight)
        addView(text(caption ?: " ", 12, muted), margin(-1, -2).apply { leftMargin = dp(4) })
        addView(field, margin(-1, -2))
    }

    private fun usenetField(hint: String, value: String, type: Int) = TvEditText(this).apply {
        this.hint = hint; setSingleLine(); inputType = type or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS; setText(value)
    }

    /** Add or edit a news provider: any server, port, SSL on or off, and how many connections to use. */
    private fun editNewsProvider(provider: NewsProvider?, preset: NewsProviders.Preset?) {
        val content = column().apply { setPadding(dp(18), dp(8), dp(18), dp(8)) }
        val plain = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        // Easynews members already gave this app their login: offer it for the Easynews news server.
        val easynews = if (provider == null && preset?.name == "Easynews") Easynews.credentials(store) else null
        val name = usenetField("Name", provider?.name ?: preset?.name.orEmpty(), plain)
        val host = usenetField("Server address (news.example.com)", provider?.host ?: preset?.host.orEmpty(), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        val port = usenetField("Port", (provider?.port ?: preset?.port ?: 563).toString(), InputType.TYPE_CLASS_NUMBER)
        val user = usenetField("Username", provider?.user ?: easynews?.first.orEmpty(), plain)
        val password = usenetField("Password", provider?.password ?: easynews?.second.orEmpty(), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val suggested = minOf(NewsProviders.DEFAULT_CONNECTIONS, preset?.maxConnections ?: NewsProviders.DEFAULT_CONNECTIONS)
        val connections = usenetField("Connections", (provider?.connections ?: suggested).toString(), InputType.TYPE_CLASS_NUMBER)
        val pipeline = usenetField("Pipeline (default ${NewsProviders.DEFAULT_PIPELINE_DEPTH}, 1–${NewsProviders.MAX_PIPELINE_DEPTH})", (provider?.pipelineDepth ?: NewsProviders.DEFAULT_PIPELINE_DEPTH).toString(), InputType.TYPE_CLASS_NUMBER)
        val tls = android.widget.CheckBox(this).apply { text = "SSL"; isChecked = provider?.tls ?: true; setTextColor(ink) }
        val backup = android.widget.CheckBox(this).apply { text = "Backup only (fills in what the main provider is missing)"; isChecked = provider?.backup ?: false; setTextColor(ink) }
        // Switching SSL moves a standard port to its counterpart; a custom port is left alone.
        tls.setOnCheckedChangeListener { _, on -> when (port.text.toString()) { "563", "443" -> if (!on) port.setText("119"); "119", "23", "80" -> if (on) port.setText("563") } }
        val status = text((preset?.let { "${it.name} allows up to ${it.maxConnections} connections on most plans; 10–20 suit a TV." } ?: "Connections: 1–${NewsProviders.MAX_CONNECTIONS}.") +
            " SSL uses port 563 (119 without). Pipeline = requests ahead per connection; applies to the next video.", 13, muted)
        // Two fields to a row, so the whole form and its buttons fit a TV screen.
        fun row(vararg views: Pair<View, Float>) = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            views.forEachIndexed { index, (view, share) -> addView(view, LinearLayout.LayoutParams(0, -2, share).apply { if (index > 0) leftMargin = dp(8) }) }
        }
        // Wide screens (TV): one grid of three equal columns, so every box lines up and Pipeline shows without scrolling.
        //   Name     | Server address (two columns)
        //   Port     | Username | Password
        //   Connections | Pipeline | SSL
        if (resources.configuration.screenWidthDp >= 720) {
            val grid = android.widget.GridLayout(this).apply { columnCount = 3 }
            // Each box gets a small caption above it: a field's hint disappears once it holds text ("20", "2").
            fun cell(field: View, row: Int, column: Int, span: Int = 1) = grid.addView(captioned(field), android.widget.GridLayout.LayoutParams(
                android.widget.GridLayout.spec(row), android.widget.GridLayout.spec(column, span, 1f)).apply {
                width = 0; setMargins(dp(4), dp(3), dp(4), dp(3)); setGravity(Gravity.FILL_HORIZONTAL or Gravity.CENTER_VERTICAL) })
            cell(name, 0, 0); cell(host, 0, 1, 2)
            cell(port, 1, 0); cell(user, 1, 1); cell(password, 1, 2)
            cell(connections, 2, 0); cell(pipeline, 2, 1); cell(tls, 2, 2)
            tls.gravity = Gravity.CENTER_VERTICAL
            content.addView(grid, margin(-1, -2))
        } else {
            content.addView(name, margin(-1, -2))
            content.addView(row(host to 3f, port to 1f), margin(-1, -2, top = 4))
            content.addView(row(user to 1f, password to 1f), margin(-1, -2, top = 4))
            content.addView(row(connections to 1f, pipeline to 1f), margin(-1, -2, top = 4))
            content.addView(tls, margin(-1, -2))
        }
        content.addView(backup, margin(-1, -2))
        val dialog = AlertDialog.Builder(this).setTitle(if (provider == null) "Add news provider" else "Edit ${provider.name}")
            .setView(formWithStatus(content, status))
            .setPositiveButton("Save", null).setNegativeButton("Cancel", null).setNeutralButton("Test", null).create()
            .apply { window?.setSoftInputMode(formInputMode()) }
        dialog.setOnDismissListener { password.setText("") }
        dialog.show()
        // Three columns of boxes need more room than the usual dialog width on a TV.
        if (resources.configuration.screenWidthDp >= 720)
            dialog.window?.setLayout(resources.displayMetrics.widthPixels * 62 / 100, android.view.WindowManager.LayoutParams.WRAP_CONTENT)
        fun typed(): NewsProvider {
            val made = NewsProviders.create(name.text.toString(), host.text.toString(), port.text.toString().trim().toIntOrNull() ?: 0, tls.isChecked,
                user.text.toString(), password.text.toString(), connections.text.toString().trim().toIntOrNull() ?: 0, backup.isChecked, pipeline.text.toString().trim().toIntOrNull() ?: 0)
            return if (provider == null) made else made.copy(id = provider.id, enabled = provider.enabled)
        }
        dialog.getButton(android.content.DialogInterface.BUTTON_NEUTRAL).setOnClickListener {
            val candidate = try { typed() } catch (error: Exception) { status.text = uiErrorMessage(error); return@setOnClickListener }
            status.text = "Signing in…"
            worker.execute {
                val result = try {
                    "Signed in (${dev.pov.tv.usenet.Nntp.check(dev.pov.tv.usenet.NntpServer(candidate.host, candidate.port, candidate.tls, candidate.user, candidate.password))} ms). Save to keep it."
                } catch (error: Exception) { error.message ?: "Couldn't connect." }
                runOnUiThread { if (dialog.isShowing) status.text = result }
            }
        }
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            try {
                val saved = typed()
                val config = NewsProviders(store)
                val existing = config.load()
                config.save(if (provider == null) existing + saved else existing.map { if (it.id == provider.id) saved else it })
                dialog.dismiss(); usenetPage("setting:${saved.name}")
            } catch (error: Exception) { status.text = uiErrorMessage(error) }
        }
    }

    private fun saveIndexers(indexers: List<NewznabIndexer>, focus: String? = null) {
        try { NewznabIndexers(store).save(indexers); usenetPage(focus) } catch (error: Exception) { toast(uiErrorMessage(error)) }
    }

    private fun indexerActions(indexer: NewznabIndexer) {
        val all = runCatching { NewznabIndexers(store).load() }.getOrDefault(emptyList())
        val focus = "setting:${indexer.name}"
        val actions = listOf<Pair<String, () -> Unit>>(
            "Edit" to { editIndexer(indexer, null) },
            "Test" to {
                toast("Asking ${indexer.name}…")
                worker.execute {
                    val result = try { "${indexer.name}: " + Newznab().test(indexer, UsenetHeaders.query { store.get(it) }) } catch (error: Exception) { "${indexer.name}: ${error.message ?: "no answer"}" }
                    runOnUiThread { if (!isFinishing && !isDestroyed) toast(result) }
                }
            },
            (if (indexer.enabled) "Turn off" else "Turn on") to { saveIndexers(all.map { if (it.id == indexer.id) it.copy(enabled = !indexer.enabled) else it }, focus) },
            "Remove" to { confirm("Remove ${indexer.name} from this device?") { saveIndexers(all.filter { it.id != indexer.id }) } })
        AlertDialog.Builder(this).setTitle(indexer.name).setItems(actions.map { it.first }.toTypedArray()) { _, picked -> actions[picked].second() }
            .setNegativeButton("Cancel", null).show()
    }

    private fun chooseIndexerPreset() {
        val presets = NewznabIndexers.PRESETS
        val labels = (listOf("Generic Newznab (any address)") + presets.map { it.name }).toTypedArray()
        AlertDialog.Builder(this).setTitle("Add indexer").setItems(labels) { _, picked ->
            editIndexer(null, presets.getOrNull(picked - 1))
        }.setNegativeButton("Cancel", null).show()
    }

    /** Add or edit an indexer: a known one with its API key, or any Newznab address. */
    private fun editIndexer(indexer: NewznabIndexer?, preset: NewznabIndexers.Preset?) {
        val content = column().apply { setPadding(dp(18), dp(8), dp(18), dp(8)) }
        val plain = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        val name = usenetField("Name", indexer?.name ?: preset?.name?.substringBefore(" (").orEmpty(), plain)
        val address = usenetField("API address (https://api.example.com/api)", indexer?.url ?: preset?.url.orEmpty(), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        val key = usenetField(if (preset?.keyOptional == true) "API key (optional here)" else "API key", indexer?.apiKey.orEmpty(), plain)
        val status = text(when {
            preset == null -> if (indexer == null) "Your API key is on the indexer's website, usually under Profile or API." else ""
            preset.url.startsWith("http://") -> "Change the address to your own server's, then enter its API key."
            else -> "Enter your ${preset.name} API key. It is on their website, usually under Profile or API."
        }, 13, muted)
        content.addView(captioned(name), margin(-1, -2)); content.addView(captioned(address), margin(-1, -2, top = 4)); content.addView(captioned(key), margin(-1, -2, top = 4))
        val dialog = AlertDialog.Builder(this).setTitle(if (indexer == null) "Add indexer" else "Edit ${indexer.name}")
            .setView(formWithStatus(content, status))
            .setPositiveButton("Save", null).setNegativeButton("Cancel", null).setNeutralButton("Test", null).create()
            .apply { window?.setSoftInputMode(formInputMode()) }
        dialog.show()
        // A known indexer only needs its key: start there.
        if (preset != null && !preset.url.startsWith("http://")) key.requestFocus()
        fun typed(): NewznabIndexer {
            val made = NewznabIndexers.create(name.text.toString(), address.text.toString(), key.text.toString())
            return if (indexer == null) made else made.copy(id = indexer.id, enabled = indexer.enabled)
        }
        dialog.getButton(android.content.DialogInterface.BUTTON_NEUTRAL).setOnClickListener {
            val candidate = try { typed() } catch (error: Exception) { status.text = uiErrorMessage(error); return@setOnClickListener }
            status.text = "Asking the indexer…"
            worker.execute {
                val result = try { Newznab().test(candidate, UsenetHeaders.query { store.get(it) }) + ". Save to keep it." } catch (error: Exception) { error.message ?: "No answer." }
                runOnUiThread { if (dialog.isShowing) status.text = result }
            }
        }
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            try {
                val saved = typed()
                val config = NewznabIndexers(store)
                val existing = config.load()
                require(existing.none { it.url == saved.url && it.apiKey == saved.apiKey && it.id != saved.id }) { "This indexer is already saved." }
                config.save(if (indexer == null) existing + saved else existing.map { if (it.id == indexer.id) saved else it })
                dialog.dismiss(); usenetPage("setting:${saved.name}")
            } catch (error: Exception) { status.text = uiErrorMessage(error) }
        }
    }

    private fun editUsenetHeader(label: String, key: String, default: String) {
        val content = column().apply { setPadding(dp(18), dp(8), dp(18), dp(8)) }
        val field = usenetField(default, store.get(key).ifBlank { default }, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)
        content.addView(field, margin(-1, -2))
        content.addView(text("A tool name and version, like $default. Names this app knows (Prowlarr, Sonarr, Radarr, SABnzbd, nzbget) keep up with new versions by themselves; anything else stays as you wrote it. Leave it empty for the default.", 13, muted), margin(-1, -2, top = 8))
        val dialog = AlertDialog.Builder(this).setTitle(label).setView(content).setPositiveButton("Save", null).setNegativeButton("Cancel", null).create()
        dialog.show(); dialog.submitOnEnter(field)
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            val typed = UsenetHeaders.clean(field.text.toString())
            if (typed.isBlank() || typed == default) store.remove(key) else store.put(key, typed)
            dialog.dismiss(); usenetPage("setting:$label")
        }
    }

    private fun updateUsenetHeaders() {
        toast("Checking for newer versions…")
        worker.execute {
            val result = try {
                val changed = UsenetHeaders.refresh({ store.get(it) }, { key, value -> store.put(key, value) })
                if (changed.isEmpty()) "Both headers are already current" else "Updated to ${changed.joinToString(" and ")}"
            } catch (error: Exception) { "Couldn't check just now. Try again later." }
            runOnUiThread { if (!isFinishing && !isDestroyed) { toast(result); if (destination == "settings") usenetPage("setting:Update now") } }
        }
    }

    /** Which Easynews server links stream from. Easynews ignores the download port, so the server is the speed setting. */
    private fun easynewsServer() {
        val servers = Easynews.SERVERS
        AlertDialog.Builder(this).setTitle("Easynews download server")
            .setSingleChoiceItems(servers.map { it.second }.toTypedArray(), servers.indexOfFirst { it.first == Easynews.server(store) }) { dialog, index ->
                store.put(Easynews.SERVER, servers[index].first); dialog.dismiss()
                toast("Easynews links now stream from ${servers[index].second}. Search again to use it."); refreshSettings()
            }.setNegativeButton("Cancel", null).show()
    }

    /** Parallel connections per Easynews video: each connection is capped, so more connections stream faster. */
    private fun easynewsConnections() {
        NumberStepper.show(this, "Easynews connections", "More connections stream faster; 1 = a single connection. Applies from the next video.",
            Easynews.connections(store), 1..Easynews.MAX_CONNECTIONS, { "$it ${if (it == 1) "connection" else "connections"}" }) { count ->
            store.put(Easynews.CONNECTIONS, count.toString()); toast("Easynews uses $count ${if (count == 1) "connection" else "connections"} from the next video"); refreshSettings()
        }
    }

    /** Easynews username and password, checked against the members site before they are saved. */
    private fun connectEasynews() {
        val content = column().apply { setPadding(dp(18), dp(8), dp(18), dp(8)) }
        val user = TvEditText(this).apply {
            hint = "Easynews username"; setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        val password = TvEditText(this).apply {
            hint = "Easynews password"; setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        val status = text("", 14, muted)
        content.addView(user, margin(-1, -2)); content.addView(password, margin(-1, -2, top = 8)); content.addView(status, margin(-1, -2, top = 8))
        val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
        val dialog = AlertDialog.Builder(this).setTitle("Easynews account").setView(content)
            .setPositiveButton("Connect", null).setNegativeButton("Cancel", null).create()
        dialog.setOnDismissListener { cancelled.set(true); password.setText("") }
        dialog.show(); dialog.submitOnEnter(password)
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            val name = user.text.toString().trim(); val secret = password.text.toString()
            if (name.isBlank()) { user.error = "Enter your Easynews username"; return@setOnClickListener }
            if (secret.isEmpty()) { password.error = "Enter your Easynews password"; return@setOnClickListener }
            val request = generation
            val connect = dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE)
            connect.isEnabled = false; status.text = "Checking your Easynews account…"
            worker.execute {
                try {
                    EasynewsApi(store).verify(name, secret)
                    if (cancelled.get()) return@execute
                    Easynews.save(store, name, secret)
                    runOnUiThread {
                        if (request == generation && !isFinishing && !isDestroyed) {
                            if (dialog.isShowing) dialog.dismiss()
                            toast("Easynews connected"); refreshSettings()
                        }
                    }
                } catch (error: Exception) {
                    runOnUiThread { if (dialog.isShowing) { connect.isEnabled = true; status.text = uiErrorMessage(error) } }
                }
            }
        }
    }

    private fun subtitleLanguage(allLanguages: Boolean = false) {
        val preferences = SubtitlePreferences(this)
        val languages = if (allLanguages) SubtitlePreferences.LANGUAGES else
            SubtitlePreferences.COMMON_LANGUAGES.map { code -> SubtitlePreferences.LANGUAGES.first { it.first == code } }
        val labels = languages.map { it.second } + if (allLanguages) emptyList() else listOf("More languages…")
        AlertDialog.Builder(this).setTitle("Preferred subtitle language")
            .setSingleChoiceItems(labels.toTypedArray(), languages.indexOfFirst { it.first == preferences.language }) { dialog, index ->
                dialog.dismiss()
                if (index == languages.size) subtitleLanguage(true) else {
                    preferences.language = languages[index].first
                    InAppPlaybackSession.refreshSubtitleLanguage()
                    refreshSettings()
                }
            }.setNegativeButton("Cancel", null).show()
    }

    /** [second]: the second audio language, whose list starts with "None" instead of "Automatic" and leaves out the first. */
    private fun audioLanguage(allLanguages: Boolean = false, second: Boolean = false) {
        val preferences = AudioPreferences(this)
        val all = if (allLanguages) AudioPreferences.LANGUAGES else
            AudioPreferences.COMMON_LANGUAGES.map { code -> AudioPreferences.LANGUAGES.first { it.first == code } }
        val languages = if (!second) all else listOf("" to "None") + all.filter { it.first.isNotEmpty() && it.first != preferences.language }
        val labels = languages.map { it.second } + if (allLanguages) emptyList() else listOf("More languages…")
        val chosen = if (second) preferences.secondLanguage else preferences.language
        AlertDialog.Builder(this).setTitle(if (second) "Second audio language" else "Preferred audio language")
            .setSingleChoiceItems(labels.toTypedArray(), languages.indexOfFirst { it.first == chosen }) { dialog, index ->
                dialog.dismiss()
                if (index == languages.size) audioLanguage(true, second) else {
                    if (second) preferences.secondLanguage = languages[index].first else preferences.language = languages[index].first
                    InAppPlaybackSession.refreshAudioLanguage()
                    refreshSettings()
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun settings() {
        current = { settings() }
        destination = "settings"; refreshNavigation()
        page("Settings", "Make ${BuildConfig.APP_SHORT} yours.")

        val section = settingsSection
        settingsCards(section)
        val content = column()
        body.addView(content, margin(-1, -2))
        settingsContent = content
        settingsSectionContent(section)
        body.addView(View(this), margin(-1, 24))
    }

    /** The settings of one section, built into the area under the section cards. */
    private fun settingsSectionContent(section: String) {
        val content = settingsContent ?: return
        content.removeAllViews()
        settingsTarget = content
        try {
            when (section) {
                "account" -> {
            val card = settingsGroup("${BuildConfig.APP_SHORT} account & backup", "account")
            if (!cloud.signedIn) {
                settingsRow(card, "Create account", "", "Back up your debrid services, add-ons, Home rows and watch progress, encrypted on this device. " +
                    "Sign in on any TV or phone and everything comes back.") { accountDialogs.createAccount() }
                settingsRow(card, "Sign in", "", "Bring back your setup from your ${BuildConfig.APP_SHORT} account.") { accountDialogs.signIn() }
                settingsRow(card, "Backup server", java.net.URI(cloud.server).host.orEmpty(),
                    "Where backups are kept. Change it only if you run your own copy of the backup server.") { accountDialogs.backupServer() }
            } else {
                settingsRow(card, "Signed in", cloud.email, "Debrid keys, add-ons and logins are encrypted with your password before they leave this device.", status = true) {}
                settingsToggle(card, "Automatic backup", "Backs up when you leave the app, whenever something changed.", cloud.autoBackup) { on ->
                    cloud.autoBackup = on; if (on) accountDialogs.backupNow()
                }
                val last = cloud.lastBackup
                settingsRow(card, "Back up now", if (last > 0) "Last: ${android.text.format.DateUtils.getRelativeTimeSpanString(last, System.currentTimeMillis(), 60_000)}" else "Never") {
                    accountDialogs.backupNow()
                }
                settingsRow(card, "Change password") { accountDialogs.changePassword() }
                settingsRow(card, "Sign out", "", "This device keeps its settings and stops backing up.") { accountDialogs.signOut() }
                settingsRow(card, "Delete account", danger = true) { accountDialogs.deleteAccount() }
            }
                }
                "home" -> {
            val home = settingsGroup("Home & appearance", "home")
            if (DeviceLayout.tv) {
                settingsRow(home, "Trailers", if (!heroTrailersEnabled()) "Off" else listOf("On", if (heroTrailerSoundEnabled()) "sound on" else "muted", if (heroTrailerHighQuality()) "high quality" else "standard quality").joinToString(" · "),
                    "Trailers on details pages and focused Home posters: sound, quality and screen fill.") { navigate { playbackPage("trailers") } }
                settingsToggle(home, "TV home Continue Watching", "Show resume cards on supported TV launchers. Your TV controls whether the row appears.", TvContinueWatching.enabled(this)) { enabled ->
                    TvContinueWatching.setEnabled(this,enabled)
                }
            }
            settingsRow(home, "Cast & crew cards", if (roundCast()) "Round" else "Large portraits",
                "How the cast and crew look on details pages: large portrait photos, or the smaller round ones.") {
                val styles = listOf("large" to "Large portraits", "round" to "Round")
                AlertDialog.Builder(this).setTitle("Cast & crew cards")
                    .setSingleChoiceItems(styles.map { it.second }.toTypedArray(), if (roundCast()) 1 else 0) { dialog, index ->
                        store.put(CAST_STYLE, styles[index].first); dialog.dismiss(); refreshSettings()
                    }.setNegativeButton("Cancel", null).show()
            }
            val tmdbKey = store.get(TMDB_KEY)
            settingsRow(home, "TMDB", if (tmdbKey.isBlank()) "Not set" else "Connected",
                "Your own free TMDB key adds catalogs, cast, keywords and similar titles.",
                status = tmdbKey.isNotBlank()) {
                inputSetting("TMDB API key", "TMDB API key or API Read Access Token", "", secret = true) { key ->
                    load(generation, { TmdbCatalog(this, key, tmdbRegion()).validate() }) { store.put(TMDB_KEY, key); toast("TMDB connected. Home now uses TMDB catalogs."); refreshSettings() }
                }
            }
            val tvdbKey = store.get(TVDB_KEY)
            settingsRow(home, "TVDB", if (tvdbKey.isBlank()) "Not set" else "Connected",
                "Your own TVDB key gives shows TVDB's seasons and episodes: usually more complete, with specials, stills and synopses.",
                status = tvdbKey.isNotBlank()) {
                inputSetting("TVDB API key", "TVDB API key (thetvdb.com → Account → API Keys)", "", secret = true) { key ->
                    load(generation, { TvdbApi(this, key).validate() }) { store.put(TVDB_KEY, key); forgetDetails(); toast("TVDB connected. Show pages now use TVDB episodes."); refreshSettings() }
                }
            }
            if (tvdbKey.isNotBlank()) settingsToggle(home, "Use TVDB for shows", "Seasons, episode titles, stills and synopses from TVDB. Off uses Cinemeta's episode lists again.",
                store.get(TVDB_SHOWS) != "false", contentLabel = "Use TVDB for shows") { on -> store.put(TVDB_SHOWS, on.toString()); forgetDetails() }
            settingsRow(home, "Search and details", metadataSource?.name ?: MetadataSource.fromJson(store.get(MetadataSource.KEY))?.let { "Cinemeta · ${it.name} off" } ?: "Cinemeta",
                "Use one of your catalog add-ons, such as a self-hosted AIOMetadata, for search and title details (descriptions, episodes). Cinemeta stays the fallback.") { chooseMetadataSource() }
            settingsRow(home, "Cinema country", countryName(tmdbRegion()), "Used for Now Playing in Cinemas.") {
                val countries = java.util.Locale.getISOCountries().map { it to countryName(it) }.sortedBy { it.second }
                AlertDialog.Builder(this).setTitle("Now Playing in Cinemas · country")
                    .setSingleChoiceItems(countries.map { it.second }.toTypedArray(), countries.indexOfFirst { it.first == tmdbRegion() }) { dialog, index ->
                        store.put(TMDB_REGION, countries[index].first); dialog.dismiss(); refreshSettings()
                    }.setNegativeButton("Cancel", null).show()
            }
            settingsRow(home, "Home rows", "", "Reorder or hide the rows after Top 10, and add catalog add-ons such as AIOMetadata.") { navigate { homeRowsPage() } }
            settingsToggle(home, "Resume cards", "Continue Watching as wide cards with the episode, time left and progress beside the artwork.",
                store.get(RESUME_CARDS) != "false") { on -> store.put(RESUME_CARDS, on.toString()) }
            if (tmdbKey.isNotBlank()) settingsRow(home, "Remove TMDB key", danger = true) {
                confirm("Remove your TMDB key? Home will use Cinemeta catalogs.") { store.remove(TMDB_KEY); refreshSettings() }
            }
            if (tvdbKey.isNotBlank()) settingsRow(home, "Remove TVDB key", danger = true) {
                confirm("Remove your TVDB key? Shows will use Cinemeta's episode lists.") { store.remove(TVDB_KEY); forgetDetails(); refreshSettings() }
            }
                }
                "playback" -> {
            val playback = settingsGroup("Playback", "nowplaying")
            // Playback opens three short pages instead of one long list of switches.
            settingsRow(playback, "Links & sorting", SourceSortPreferences(this).label,
                "Sort order, badge style, compact list, and hiding uncached, cam and low-quality links.") { navigate { playbackPage("links") } }
            val autoplaySearch = SourceSearchPreferences(this)
            val autoplayMode = autoplaySearch.mode(store.get(AUTOPLAY_FIRST) == "true")
            val autoplayOn = autoplayMode != SourceSearchPreferences.OFF
            settingsRow(playback, "Auto-play", if (!autoplayOn) "Off" else if (store.get(AUTOPLAY_LIMIT_ON) == "true") "${autoplaySearch.modeLabel(autoplayMode)} · up to ${autoplayMaxMbps()} Mbps" else autoplaySearch.modeLabel(autoplayMode),
                "Auto-play the first link, its bitrate and size limits, and auto-play next episode.") { navigate { playbackPage("autoplay") } }
            settingsRow(playback, "Player", if (MiniPlayerPreferences(this).enabled) "Mini player on" else "Mini player off",
                "Mini player, skip buttons, seek previews, read-ahead and Dolby Vision.") { navigate { playbackPage("player") } }
                }
                "captions" -> {
            // Two cards: audio, then subtitles, each with its own English fallback.
            val sound = settingsGroup("Audio", "nowplaying")
            settingsRow(sound, "Audio language", AudioPreferences(this).languageLabel, "Chosen automatically when available, and links in this language are listed and auto-played first. You can pick another track in the player.") { audioLanguage() }
            if (AudioPreferences(this).language.isNotEmpty()) settingsRow(sound, "Second audio language", AudioPreferences(this).secondLanguageLabel,
                "Used when a video has no audio in your first language, before English. E.g. English first, Hindi second picks Hindi over Telugu or Tamil, and Hindi links are listed before other languages.") { audioLanguage(second = true) }
            settingsToggle(sound, "English audio fallback", "If a video has no audio in your language, English is chosen instead, and English links are listed before other languages.",
                AudioPreferences(this).englishFallback) { on ->
                AudioPreferences(this).englishFallback = on; InAppPlaybackSession.refreshAudioLanguage()
            }
            val boost = VolumeBoostPreferences(this)
            settingsRow(sound, "Volume boost by default", boost.label, "Every video starts with this boost. You can still change it for one video in the player's Audio & Subtitles menu.") {
                AlertDialog.Builder(this).setTitle("Volume boost by default")
                    .setSingleChoiceItems(SessionVolumeBoost.STEPS.map { if (it == 0) "Off · normal audio" else "+$it dB" }.toTypedArray(),
                        SessionVolumeBoost.STEPS.indexOf(boost.defaultDb).coerceAtLeast(0)) { dialog, index ->
                        VolumeBoostPreferences(this).defaultDb = SessionVolumeBoost.STEPS[index]; dialog.dismiss(); refreshSettings()
                    }.setNegativeButton("Cancel", null).show()
            }
            val captions = settingsGroup("Subtitles", "series")
            val subtitlePreferences = SubtitlePreferences(this)
            settingsRow(captions, "Subtitle language", subtitlePreferences.languageLabel, "Chosen automatically when available. You can pick another track in the player.") { subtitleLanguage() }
            settingsToggle(captions, "English subtitle fallback", "If a video has no subtitles in your language, English subtitles are chosen instead.",
                SubtitlePreferences(this).englishFallback) { on ->
                SubtitlePreferences(this).englishFallback = on; InAppPlaybackSession.refreshSubtitleLanguage()
            }
            settingsToggle(captions, "Find missing subtitles", "When a video has no subtitles in your language, the best matches from OpenSubtitles are added and the first one is turned on. Others are in the player's Subtitles list.",
                subtitlePreferences.autoFetch) { on -> SubtitlePreferences(this).autoFetch = on }
            settingsRow(captions, "Subtitle style", "${subtitlePreferences.fontLabel} · ${subtitlePreferences.sizeLabel}",
                "Font, size, text and outline colors, outline type and background, with a live preview and Netflix, Prime and YouTube-style presets.") {
                navigate { subtitleStylePage() }
            }
            val wyzie = store.get(InAppPlaybackSession.WYZIE_KEY)
            settingsRow(captions, "Wyzie Subs", if (wyzie.isBlank()) "Not set" else "Connected",
                "Optional: your own free Wyzie key (store.wyzie.io) adds more subtitle sources to Find missing subtitles.", status = wyzie.isNotBlank()) {
                inputSetting("Wyzie Subs API key", "Paste your free Wyzie key", "", secret = true) { key ->
                    store.put(InAppPlaybackSession.WYZIE_KEY, key.trim()); toast("Wyzie Subs added"); refreshSettings()
                }
            }
            if (wyzie.isNotBlank()) settingsRow(captions, "Remove Wyzie key", danger = true) {
                confirm("Remove your Wyzie Subs key?") { store.remove(InAppPlaybackSession.WYZIE_KEY); refreshSettings() }
            }
            val subdl = store.get(InAppPlaybackSession.SUBDL_KEY)
            settingsRow(captions, "SubDL", if (subdl.isBlank()) "Not set" else "Connected",
                "Optional: your own free SubDL key (subdl.com → Account → API) adds the large SubDL library (strong for Spanish, Arabic, Persian, Turkish…) to Find missing subtitles.",
                status = subdl.isNotBlank()) {
                inputSetting("SubDL API key", "Paste your free SubDL key", "", secret = true) { key ->
                    store.put(InAppPlaybackSession.SUBDL_KEY, key.trim()); toast("SubDL added"); refreshSettings()
                }
            }
            if (subdl.isNotBlank()) settingsRow(captions, "Remove SubDL key", danger = true) {
                confirm("Remove your SubDL key?") { store.remove(InAppPlaybackSession.SUBDL_KEY); refreshSettings() }
            }
            settingsRow(captions, "Subtitle position", subtitlePreferences.positionLabel) {
                SubtitlePositionPicker.show(this, liveVideo = false,
                    changed = { InAppPlaybackSession.refreshSubtitleStyle() }, dismissed = { refreshSettings() })
            }
                }
                "sources" -> {
            val sources = settingsGroup("Sources", "library")
            val linkedDebrids = DebridServices.linked(store); val activeDebrids = DebridServices.connected(store)
            settingsRow(sources, "Debrid services", if (linkedDebrids.isEmpty()) "None connected" else "${activeDebrids.size} of ${linkedDebrids.size} on",
                if (activeDebrids.isEmpty()) "Connect Offcloud, TorBox, Real-Debrid and more, switch each on or off, and choose which plays first."
                else "Cached links play from: ${activeDebrids.joinToString(" → ") { DebridServices.name(it) }}.", status = activeDebrids.isNotEmpty()) { navigate { debridServicesPage() } }
            val easynewsLinked = Easynews.credentials(store) != null
            settingsRow(sources, "Easynews", if (!easynewsLinked) "Not connected" else if (Easynews.enabled(store)) "On" else "Off",
                "Search Usenet with your Easynews account. Its results play directly, listed with your add-ons' links. Experimental: it uses the Easynews members website.",
                status = Easynews.active(store)) { easynewsActions() }
            val newsOn = runCatching { NewsProviders(store).load().count { it.enabled } }.getOrDefault(0)
            val indexersOn = runCatching { NewznabIndexers(store).load().count { it.enabled } }.getOrDefault(0)
            settingsRow(sources, "Usenet", if (newsOn == 0 && indexersOn == 0) "Not set up" else "$newsOn ${if (newsOn == 1) "provider" else "providers"} · $indexersOn ${if (indexersOn == 1) "indexer" else "indexers"}",
                "Stream straight from your news providers (Newshosting, Easynews, any server) with links from your own indexers. Experimental.",
                status = newsOn > 0 && indexersOn > 0) { navigate { usenetPage() } }
            val providerList = runCatching { SourceProviders(store).load() }.getOrDefault(emptyList())
            settingsRow(sources, "Source providers", if (providerList.isEmpty()) "None added" else "${providerList.count { it.enabled }} of ${providerList.size} on",
                "Your Stremio scraper add-ons, such as Torrentio. Switch each on or off, and add, edit or remove them.",
                status = providerList.any { it.enabled }) { navigate { sourceProvidersPage() } }
                }
                "tracking" -> {
            val tracking = settingsGroup("Tracking", "wetrakr")
            settingsRow(tracking, "Service", wetrakr.name, "WeTrakr or Simkl: watchlists, Continue Watching, Up Next and progress sync.") { chooseTracker() }
            settingsRow(tracking, "Account", if (wetrakr.signedIn()) "Connected" else "Not connected",
                if (wetrakr.signedIn()) "Select to sign in again." else "Sign in with a code to sync watchlists and progress.", status = wetrakr.signedIn()) { startWeTrakr() }
            if (wetrakr.signedIn()) settingsToggle(tracking, "Use ${wetrakr.name}", "Off keeps you signed in, but hides its Home rows, watched badges and lists, and sends no playback updates. Turn it back on any time.",
                wetrakr.enabled(), contentLabel = "Use ${wetrakr.name}") { on ->
                wetrakr.setEnabled(on); wetrakrOutbox.clearPending(); remoteProgress.clear(); retainedHome = null; refreshWatched(force = on)
            }
            settingsToggle(tracking, "Scrobbling", "Automatic playback updates. Your library, Continue Watching and manual actions still work when this is off.",
                wetrakr.scrobblingEnabled(), contentLabel = "${wetrakr.name} scrobbling") { on -> wetrakr.setScrobblingEnabled(on); wetrakrOutbox.clearPending() }
            if (wetrakr.supportsLists) settingsToggle(tracking, "Liked lists on Home", "Shows your liked ${wetrakr.name} lists as rows on Home.",
                store.get("wetrakr.home_liked_opt_in") == "true", contentLabel = "${wetrakr.name} liked lists on Home") { on -> store.put("wetrakr.home_liked_opt_in", on.toString()) }
            if (wetrakr.signedIn()) settingsRow(tracking, "Disconnect ${wetrakr.name}", danger = true) {
                confirm("Disconnect ${wetrakr.name} on this device?") { wetrakr.disconnect(); wetrakrOutbox.clearPending(); remoteProgress.clear(); refreshWatched(); refreshSettings() }
            }
                }
                "updates" -> {
            val updates = settingsGroup("Updates", "settings")
            settingsRow(updates, "Check for updates", "${BuildConfig.APP_NAME} ${packageManager.getPackageInfo(packageName, 0).versionName.orEmpty().removeSuffix("-rajput")}") { startActivity(Intent(this, UpdateActivity::class.java)) }
            settingsToggle(updates, "Check on startup", "Looks for a newer signed release each time the app opens.",
                store.get("updates.startup") != "false", contentLabel = "Check for updates on startup") { on -> store.put("updates.startup", on.toString()) }
                }
                "about" -> {
            val version = packageManager.getPackageInfo(packageName, 0).versionName.orEmpty().removeSuffix("-rajput")
            // The app: icon, name and version, then help and feedback, then the services it uses as a grid.
            val app = settingsGroup("App", null, heading = false)
            val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(12), dp(14), dp(12)) }
            header.addView(ImageView(this).apply { setImageResource(R.drawable.pov_logo); scaleType = ImageView.ScaleType.FIT_CENTER
                background = rounded(Color.TRANSPARENT, 10); clipToOutline = true }, margin(46, 46))
            val names = column()
            names.addView(text("${BuildConfig.APP_NAME}", 18, ink, true))
            names.addView(text("Version $version · ${if (DeviceLayout.tv) "Android TV" else "Phone"}", 12, muted), margin(-1, -2, top = 3))
            header.addView(names, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(14) })
            app.addView(header, margin(-1, -2))

            val about = settingsGroup("Help & feedback", null)
            // Only the app version and device are added to the bug link, shown on the form before sending.
            val appDetails = "${BuildConfig.APP_NAME} ${packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()} · ${if (DeviceLayout.tv) "TV" else "Phone"} · " +
                "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · Android ${android.os.Build.VERSION.RELEASE}"
            settingsRow(about, "Report a bug", BUG_PAGE.removePrefix("https://"), "Something not working? Tell us what happened. No account needed.") {
                openWebPage("Report a bug", android.net.Uri.parse(BUG_PAGE).buildUpon().appendQueryParameter("app", appDetails).build().toString(), BUG_PAGE)
            }
            settingsRow(about, "Request a feature", FEATURE_PAGE.removePrefix("https://"), "Have an idea that would make ${BuildConfig.APP_SHORT} better? Send it here.") {
                openWebPage("Request a feature", FEATURE_PAGE, FEATURE_PAGE)
            }
            settingsRow(about, "Website", WEBSITE.removePrefix("https://"), "Downloads, install guide and help for ${BuildConfig.APP_NAME}.") {
                openWebPage("${BuildConfig.APP_NAME} website", WEBSITE, WEBSITE)
            }
            settingsRow(about, "Open-source notices", "", "Licences and credits for the code and fonts this app builds on.") {
                AlertDialog.Builder(this).setTitle("Open-source notices").setMessage(assets.open("NOTICE.txt").bufferedReader().use { it.readText() }).setPositiveButton("Close", null).show()
            }

            // Services the app uses: logo, name and one short line each. Credits for code are in Open-source notices.
            val services = listOf(
                Triple(R.drawable.pov_about_tmdb, "TMDB", "Uses the TMDB API but is not endorsed or certified by TMDB."),
                Triple(R.drawable.pov_about_tvdb, "TheTVDB", "Show details with your TVDB key. Please add missing info or subscribe."),
                Triple(R.drawable.pov_about_cinemeta, "Cinemeta", "Catalogs and details."),
                Triple(0, "Kitsu", "Anime catalogs and IDs."),
                Triple(R.drawable.pov_about_queuedup, "Queued Up", "Catalogs."),
                Triple(R.drawable.wetrakr_logo, "WeTrakr", "Tracking and lists."),
                Triple(R.drawable.pov_about_simkl, "Simkl", "Tracking and lists."),
                Triple(R.drawable.pov_about_introdb, "TheIntroDB", "Skip intro, recap and credits times."),
                Triple(0, "AniSkip", "Anime skip times."),
                Triple(R.drawable.pov_about_opensubtitles, "OpenSubtitles", "Subtitles found online."),
                Triple(R.drawable.pov_about_subdl, "SubDL", "Subtitles found online."),
                Triple(R.drawable.pov_about_wyzie, "Wyzie Subs", "Subtitles with your key."),
                Triple(R.drawable.pov_about_seekr, "Seekr", "Seek previews with your key."),
            )
            val powered = settingsGroup("Powered by", null)
            gridRows(powered, services.map { (logo, name, line) ->
                val wordmark = logo == R.drawable.pov_about_tmdb || logo == R.drawable.pov_about_tvdb || logo == R.drawable.wetrakr_logo
                val tile = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(10), dp(12), dp(10)); minimumHeight = dp(74)
                    // Focusable only so the remote can scroll down to every tile; OK does nothing.
                    isFocusable = DeviceLayout.tv; isFocusableInTouchMode = DeviceLayout.tv; tag = "tile:about:$name"; contentDescription = name
                }
                val mark: View = if (logo == 0) text(name.take(1), 14, ink, true).apply {
                    gravity = Gravity.CENTER; background = rounded(withAlpha(Color.WHITE, 30), 7)
                } else ImageView(this).apply {
                    setImageResource(logo); adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_START
                    if (!wordmark) { background = rounded(Color.TRANSPARENT, 7); clipToOutline = true }
                }
                val slot = FrameLayout(this)
                slot.addView(mark, if (wordmark) FrameLayout.LayoutParams(-2, dp(when (logo) { R.drawable.pov_about_tvdb -> 26; R.drawable.wetrakr_logo -> 18; else -> 13 }), Gravity.CENTER_VERTICAL or Gravity.START)
                    else FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER_VERTICAL or Gravity.START))
                tile.addView(slot, margin(-1, 28))
                tile.addView(text(name, 13, ink, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, margin(-1, -2, top = 8))
                tileFocus(tile)
                tile
            }, if (DeviceLayout.phone) 3 else 5)
            // TMDB's terms ask for this notice wherever its data is used.
            powered.addView(text("This product uses the TMDB API but is not endorsed or certified by TMDB.", 11, muted).apply {
                setTypeface(typeface, android.graphics.Typeface.ITALIC) }, margin(-1, -2, bottom = 10).apply { leftMargin = dp(14); rightMargin = dp(14) })
                }
            }
        } finally { settingsTarget = null }
    }

    /**
     * After a settings sub-page redraws itself for a change made on it: the same scroll position, and focus back on
     * the control that was pressed ([tag]) on the TV. Runs once the new rows are laid out.
     */
    private fun keepPlace(id: Int, tag: String, scrollY: Int) {
        body.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (body.viewTreeObserver.isAlive) body.viewTreeObserver.removeOnPreDrawListener(this)
                if (id != generation || isDestroyed) return true
                if (DeviceLayout.tv) body.findViewWithTag<View>(tag)?.takeIf { it.isFocusable }?.requestFocus()
                scroller.scrollTo(0, scrollY)
                wantsContentFocus = false; pendingFocus = null
                return true
            }
        })
    }

    // Short names fit seven cards across the TV; each section's own headings keep the full names.
    private val settingsSections = listOf("account" to "Account", "home" to "Home", "playback" to "Playback", "captions" to "Audio & Subtitles",
        "sources" to "Sources", "tracking" to "Tracking", "updates" to "Updates", "about" to "About")
    /** The Settings section shown under the cards, remembered between visits. */
    private var settingsSection: String
        get() = store.get(SETTINGS_SECTION).takeIf { saved -> settingsSections.any { it.first == saved } } ?: "home"
        set(value) { store.put(SETTINGS_SECTION, value) }
    private var settingsContent: LinearLayout? = null
    /** Where settingsGroup adds its cards while a section is being built (null: the page body). */
    private var settingsTarget: LinearLayout? = null
    private var settingsCardsAt = 0L
    /** Switches the section shown under the cards (set by the current cards row). */
    private var settingsChoose: ((String) -> Unit)? = null
    private var refreshingSettings = false

    private fun settingsSummary(id: String): String = when (id) {
        "account" -> if (cloud.signedIn) cloud.email else "Back up your setup"
        "home" -> if (store.get(TMDB_KEY).isBlank()) "Cinemeta catalogs" else "TMDB connected"
        "playback" -> listOfNotNull(if (store.get(AUTOPLAY_FIRST) == "true") "Auto-play on" else "Auto-play off",
            if (SkipButtonPreferences(this).enabled) "skip buttons" else null).joinToString(" · ")
        "captions" -> "${SubtitlePreferences(this).languageLabel} subtitles"
        "sources" -> {
            val debrids = DebridServices.connected(store).size
            val addons = runCatching { SourceProviders(store).load().count { it.enabled } }.getOrDefault(0)
            listOfNotNull("$debrids debrid", "$addons add-on${if (addons == 1) "" else "s"}", if (Easynews.active(store)) "Easynews" else null).joinToString(" · ")
        }
        "tracking" -> "${wetrakr.name} · ${when { !wetrakr.signedIn() -> "not connected"; !wetrakr.enabled() -> "off"; else -> "connected" }}"
        "updates" -> "Version ${packageManager.getPackageInfo(packageName, 0).versionName.orEmpty().removeSuffix("-rajput")}"
        else -> "${WEBSITE.removePrefix("https://")} · credits"
    }

    private fun settingsIcon(id: String): String? = when (id) {
        "account" -> "account"; "home" -> "home"; "playback" -> "nowplaying"; "captions" -> "series"; "sources" -> "library"; "tracking" -> "wetrakr"
        "updates" -> "settings"; else -> null
    }

    /**
     * The Settings tab bar: one rounded bar of section names. On the TV a white highlight glides from tab to tab
     * (the player controls' [SlidingFocusPill]) and moving onto a tab shows that section below; on a phone it
     * scrolls sideways and a tap chooses. While focus is down in the settings, the shown section keeps a soft pill.
     */
    private fun settingsCards(selected: String) {
        // The tabs' own row carries the gliding highlight as its background; the rounded bar sits on a wrapper.
        val strip = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; clipToPadding = false; clipChildren = false }
        val bar = FrameLayout(this).apply {
            setPadding(dp(5), dp(5), dp(5), dp(5)); background = rounded(withAlpha(Color.WHITE, 20), 30)
            addView(strip, FrameLayout.LayoutParams(-2, -2))
        }
        val row: android.view.ViewGroup = if (DeviceLayout.tv) FrameLayout(this).apply {
            clipToPadding = false; clipChildren = false
            addView(bar, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_HORIZONTAL))
        } else android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false; clipToPadding = false; clipChildren = false; overScrollMode = View.OVER_SCROLL_NEVER
            addView(bar)
        }
        body.addView(row, margin(-1, -2, top = 2, bottom = 10))
        settingsCardsAt = android.os.SystemClock.elapsedRealtime()
        val tabs = LinkedHashMap<String, TextView>()
        val glide = SlidingFocusPill(strip, fadeText = false)
        // The shown section's soft pill, only while the highlight is elsewhere (it would cover the glide).
        fun mark() {
            val inBar = strip.hasFocus()
            tabs.forEach { (id, tab) ->
                val chosen = id == settingsSection
                tab.background = if (chosen && !inBar) rounded(withAlpha(Color.WHITE, 46), 24) else null
                if (!tab.isFocused) tab.setTextColor(if (chosen) ink else muted)
            }
        }
        fun choose(id: String) {
            if (id == settingsSection) return
            settingsSection = id
            mark()
            settingsSectionContent(id)
        }
        settingsChoose = ::choose
        settingsSections.forEach { (id, label) ->
            val tab = text(label, 15, muted, true).apply {
                gravity = Gravity.CENTER; maxLines = 1; setPadding(dp(18), dp(9), dp(18), dp(9))
                isFocusable = true; isFocusableInTouchMode = DeviceLayout.tv; isClickable = true
                tag = "settings-section:$id"; contentDescription = label
            }
            tabs[id] = tab
            tab.setOnClickListener { choose(id) }
            tab.setOnFocusChangeListener { _, focused ->
                glide.focusChanged(tab, focused)
                tab.setTextColor(if (focused) Color.BLACK else if (id == settingsSection) ink else muted)
                strip.post { if (!isDestroyed) mark() }
                // Moving along the tabs switches section; focus set by the page itself as it opens does not.
                if (focused && DeviceLayout.tv && android.os.SystemClock.elapsedRealtime() - settingsCardsAt > 500) {
                    tab.postDelayed({ if (tab.hasFocus() && !isDestroyed) choose(id) }, 180)
                }
            }
            strip.addView(tab, LinearLayout.LayoutParams(-2, -2))
        }
        mark()
        val chosenTab = tabs[selected]
        if (row is android.widget.HorizontalScrollView) row.post { chosenTab?.let { row.scrollTo((it.left - dp(24)).coerceAtLeast(0), 0) } }
        // Opening Settings puts focus on the chosen section's tab (a refresh or Back restores the row that was focused).
        if (!refreshingSettings && pendingFocus == null && DeviceLayout.tv) {
            val opened = generation
            body.postDelayed({ if (opened == generation && !isDestroyed && destination == "settings") chosenTab?.requestFocus() }, 60)
        }
    }

    /** Phone: opens [address] in the browser. TV: a QR code of it to scan, with [shown] written underneath. */
    private fun openWebPage(title: String, address: String, shown: String) {
        if (DeviceLayout.phone) { runCatching { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(address))) }; return }
        val code = ImageView(this).apply { setImageBitmap(qrBitmap(address, dp(200))); background = rounded(Color.WHITE, 8); setPadding(dp(8), dp(8), dp(8), dp(8)) }
        val box = column().apply { gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(20), dp(12), dp(20), dp(8)) }
        box.addView(code, LinearLayout.LayoutParams(dp(200), dp(200)))
        box.addView(text(shown.removePrefix("https://"), 16, ink, true).apply { gravity = Gravity.CENTER }, margin(-2, -2, top = 12))
        box.addView(text("Scan with your phone's camera.", 12, muted).apply { gravity = Gravity.CENTER }, margin(-2, -2, top = 4))
        AlertDialog.Builder(this).setTitle(title).setView(box).setPositiveButton("Close", null).show()
    }

    /** A titled card that holds one group of settings rows. */
    /** A settings card; [heading] off leaves out its small title (a page whose own title already names it). */
    private fun settingsGroup(title: String, icon: String?, heading: Boolean = true): LinearLayout {
        val parent = settingsTarget ?: body
        val first = if (settingsTarget != null) parent.childCount == 0 else body.childCount <= 2
        if (heading) {
            val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            if (icon != null) header.addView(NavigationIconView(this, icon, muted), margin(16, 16, right = 8))
            header.addView(text(title.uppercase(), 11, muted, true).apply { letterSpacing = 0.12f })
            parent.addView(header, margin(-1, -2, top = if (first) 6 else 22, bottom = 8))
        } else parent.addView(View(this), margin(-1, 6))
        val card = column().apply { background = rounded(if (flix) withAlpha(Color.WHITE, 13) else panel, 12); setPadding(dp(4), dp(4), dp(4), dp(4)) }
        parent.addView(card, margin(-1, -2))
        return card
    }

    private fun settingsDivider(card: LinearLayout) {
        if (card.childCount == 0) return
        card.addView(View(this).apply { setBackgroundColor(withAlpha(Color.WHITE, 16)) }, margin(-1, 1).apply { leftMargin = dp(12); rightMargin = dp(12) })
    }

    /** One settings row: label and help text on the left, the current value and a chevron on the right. */
    private fun settingsRow(card: LinearLayout, label: String, value: String = "", description: String = "",
                            status: Boolean? = null, danger: Boolean = false, action: () -> Unit) {
        settingsDivider(card)
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(11), dp(12), dp(11))
            isFocusable = true; isFocusableInTouchMode = DeviceLayout.tv; isClickable = true; tag = "setting:$label"
            contentDescription = if (value.isBlank()) label else "$label · $value"
        }
        val copy = column()
        copy.addView(text(label, 14, if (danger) 0xFFFF6B6B.toInt() else ink, true))
        if (description.isNotBlank()) copy.addView(text(description, 11, muted).apply { setLineSpacing(dp(1).toFloat(), 1f) }, margin(-1, -2, top = 4))
        row.addView(copy, LinearLayout.LayoutParams(0, -2, 1f))
        // A connection value is tinted: green when connected, muted otherwise.
        val tint = when (status) { true -> 0xFF46D369.toInt(); else -> muted }
        if (DeviceLayout.phone && value.length > PHONE_INLINE_VALUE) copy.addView(text(value, 12, tint, true), margin(-1, -2, top = 6))
        else if (value.isNotBlank()) {
            row.addView(text(value, 12, tint, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; gravity = Gravity.END }, margin(-2, -2).apply { leftMargin = dp(16) })
        }
        if (!danger) row.addView(text("›", 18, muted, true), margin(-2, -2).apply { leftMargin = dp(10) })
        row.setOnFocusChangeListener { view, focused ->
            view.background = if (focused) rounded(withAlpha(Color.WHITE, if (flix) 34 else 24), 9) else null
            view.foreground = if (focused) FocusRingDrawable(if (flix) Color.WHITE else accent, dp(9).toFloat(), true, dp(1).toFloat()) else null
        }
        row.setOnClickListener {
            settingsReturnIndex = focusController.focusedIndex(); settingsReturnScroll = scroller.scrollY
            action()
        }
        card.addView(row.pressable(9), margin(-1, -2))
    }

    /** A Playback settings page: links and sorting, auto-play, or the player. Back returns to Playback. */
    private fun playbackPage(section: String) {
        current = { playbackPage(section) }
        destination = "settings"; refreshNavigation()
        val (title, subtitle) = when (section) {
            "links" -> "Links & sorting" to "How links are ordered, and which are left out."
            "autoplay" -> "Auto-play" to "What Play starts on its own, and the next episode."
            "rules", "filters", "preferred", "qualities" -> "Link filters" to "Which links show, which are hidden and which move up. Each tile saves at once."
            "words:prefer" -> "Your words to prefer" to "Links with these words move up, the first word ranking highest."
            "words:hide" -> "Your words to hide" to "Links with any of these words are left out."
            "groups" -> "Trusted release groups" to "Lists of well-known release groups; links from groups in lists that are on move up, best first."
            "trailers" -> "Trailers" to "Trailers on details pages and when a Home shelf poster has focus."
            else -> if (section.startsWith("group:")) (LinkRules.groupSet(section.removePrefix("group:"))?.label ?: "Release groups") to
                (if (LinkRules.GROUP_SETS.any { it.id == section.removePrefix("group:") }) "Trusted release groups, best first. Links from groups that are on move up."
                else "Links from groups that are on are hidden.")
            else "Player" to "The player's extras: mini player, skip buttons, seek previews and picture."
        }
        page(title, subtitle)
        // The page title already names the card.
        val playback = settingsGroup(title, "nowplaying", heading = false)
        if (section.startsWith("group:")) { groupListPage(playback, section.removePrefix("group:")); return }
        when (section) {
            "trailers" -> {
            val prefs = getSharedPreferences("pov_hero_trailers", 0)
            val on = heroTrailersEnabled()
            fun save(key: String, value: Boolean, redraw: Boolean = false) { prefs.edit().putBoolean(key, value).apply(); heroTrailer?.stop(); if (redraw) refreshSettings() }
            tileGrid(playback, listOf(
                Tile("trailers", "Trailers", "Details pages and Home posters", on) { value -> save("enabled", value, redraw = true) },
                Tile("trailer_sound", "Trailer sound", "Muted when off", heroTrailerSoundEnabled(), enabled = on) { value -> save("sound", value) },
                Tile("trailer_hq", "High quality", "VP9 when the TV can", heroTrailerHighQuality(), enabled = on) { value -> save("high_quality", value) },
                Tile("trailer_fill", "Fill screen", "Zoom to remove bars", heroTrailerFillEnabled(), enabled = on) { value -> save("fill_screen", value) },
            ), columns = if (DeviceLayout.phone) 2 else 4)
            playback.addView(text("The rotating top recommendations show backdrop images only. Fill screen crops some picture edges.", 11, muted),
                margin(-1, -2, bottom = 8).apply { leftMargin = dp(10); rightMargin = dp(10) })
            }
            "links" -> {
            settingsRow(playback, "Link sorting", SourceSortPreferences(this).label,
                "Library links on top (or off), then what comes next (language, quality, Preferred or size) and each one's direction. Auto-play follows this order.") {
                SourceSortPreferences.show(this, { refreshSettings() }) { navigate { playbackPage("preferred") } }
            }
            val sortPreferences = SourceSortPreferences(this)
            settingsRow(playback, "Best per quality", if (sortPreferences.bestPerQuality == 0) "Off · all links" else "${sortPreferences.bestPerQuality} per quality",
                "Show only the best links of each quality (4K, 1080p, 720p, lower) in your sort order, with a More links row for the rest. Auto-play and the player's Streams use the same short list.") {
                NumberStepper.show(this, "Best per quality", "Links kept for each quality. 0 = off: show every link.", sortPreferences.bestPerQuality,
                    SourceSortPreferences.BEST_PER_QUALITY_RANGE, { if (it == 0) "Off" else "$it ${if (it == 1) "link" else "links"}" }) { count ->
                    sortPreferences.bestPerQuality = count; refreshSettings()
                }
            }
            val untilPrefs = SourceSearchPreferences(this)
            val until = untilPrefs.searchUntil(sortPreferences.keepSearching)
            settingsRow(playback, "Search goal", untilPrefs.untilLabel(until, sortPreferences.bestPerQuality),
                "When the search is done: when the time limit ends, once enough good links are in, once best per quality is full, or whichever of those two comes first. " +
                "Good links play at once, suit this device and fit your Auto-play limit. The max search time still ends it if the goal isn't reached. " +
                "Time limit ends is the default and finds the most links, so your preferred audio language is less likely to be missed. " +
                "The other goals stop early and can miss links, including ones in your preferred audio language.") {
                val modes = SourceSearchPreferences.UNTIL_MODES
                val labels = listOf("Time limit ends (default) · wait for every add-on, up to the time limit. Finds the most links, best for your preferred audio language",
                    "Good links needed · stop once this many good links are in. May miss links in your preferred audio language",
                    "Best per quality is full · stop once 4K, 1080p and 720p each have ${sortPreferences.bestPerQuality.takeIf { it > 0 } ?: "N"} good links. Doesn't find all links and can miss your preferred audio language",
                    "Either one · good links needed or best per quality full, whichever comes first. Can miss your preferred audio language")
                AlertDialog.Builder(this).setTitle("Search goal").setSingleChoiceItems(labels.toTypedArray(), modes.indexOf(until)) { dialog, index ->
                    dialog.dismiss()
                    val mode = modes[index]
                    untilPrefs.setSearchUntil(mode)
                    if (mode == SourceSearchPreferences.UNTIL_BEST || mode == SourceSearchPreferences.UNTIL_EITHER) {
                        if (sortPreferences.bestPerQuality == 0) toast("Set Best per quality first, or this never stops the search")
                    }
                    if (mode == SourceSearchPreferences.UNTIL_LINKS || mode == SourceSearchPreferences.UNTIL_EITHER) {
                        NumberStepper.show(this, "Good links needed", "The search stops once this many good links are in.", untilPrefs.goodLinksNeeded,
                            SourceSearchPreferences.GOOD_LINKS_RANGE, { "$it ${if (it == 1) "link" else "links"}" }) { count ->
                            untilPrefs.goodLinksNeeded = count; refreshSettings()
                        }
                    }
                    refreshSettings()
                }.setNegativeButton("Cancel", null).show()
            }
            val linkRules = SourceSortPreferences(this).rules
            val preferCount = linkRules.preferWords.size + linkRules.preferPresets.size + (if (linkRules.preferPattern.isNotBlank()) 1 else 0)
            val hideCount = linkRules.hideCount(LowQualityReleases.hidden(store))
            val hiddenQualityCount = linkRules.hiddenQualities.size
            settingsRow(playback, "Link filters", "$hideCount hide · $preferCount prefer" + if (hiddenQualityCount > 0) " · $hiddenQualityCount ${if (hiddenQualityCount == 1) "quality" else "qualities"} off" else "",
                "Qualities to show, what to hide and what to prefer, as tiles on one page, plus your own words, release groups and patterns.") {
                navigate { playbackPage("rules") }
            }
            val searchPreferences = SourceSearchPreferences(this)
            val ownLimits = (SourceProviders(store).load().map { it.id } + "easynews").count { searchPreferences.ownLimit(it) > 0 }
            settingsRow(playback, "Max search time", searchPreferences.timeLimitLabel + if (ownLimits > 0) " · $ownLimits add-on${if (ownLimits == 1) "" else "s"} own" else "",
                "How long the search waits for add-ons: one time for all of them, and any add-on can have its own, shorter or longer. Links show as they answer.") {
                navigate { maxSearchTimePage() }
            }
            val badgeStyle = BadgeStyle.current(this)
            settingsRow(playback, "Badge style", badgeStyle.label, "How the quality, HDR, audio and source tags on links look. ${badgeStyle.description}") { chooseBadgeStyle() }
            settingsToggle(playback, "Compact link list", "One-line link names (the selected link shows its full name), the source at the end of the badges, and smaller badges, so about twice as many links fit. On by default.",
                LinkListPreferences.compact(this)) { on -> LinkListPreferences.setCompact(this, on) }
            settingsToggle(playback, "Hide uncached links", "Show only links that play at once (cached or direct) in the link list and the player's Streams. Real-Debrid, AllDebrid and Debrid-Link can't tell in advance, so their links stay.",
                store.get(HIDE_UNCACHED) == "true") { on -> store.put(HIDE_UNCACHED, on.toString()) }
            }
            "rules", "filters", "preferred", "qualities" -> linkFilterTiles(playback)
            "words:prefer" -> {
                val sort = SourceSortPreferences(this)
                wordRows(playback, sort.rules.preferWords, "preferred word", "PSA") { list -> sort.rules = sort.rules.copy(preferWords = list) }
            }
            "words:hide" -> {
                val sort = SourceSortPreferences(this)
                wordRows(playback, sort.rules.hideWords, "hide word", "YIFY") { list -> sort.rules = sort.rules.copy(hideWords = list) }
            }
            "groups" -> {
                val sort = SourceSortPreferences(this)
                val rules = sort.rules
                LinkRules.GROUP_SETS.forEach { set ->
                    settingsToggle(playback, set.label, rules.activeGroups(set).take(8).joinToString(", ").ifEmpty { "Every group in this list is off" } +
                        if (rules.activeGroups(set).size > 8) "…" else "", set.id in rules.groupSets) { on ->
                        sort.rules = sort.rules.let { it.copy(groupSets = if (on) it.groupSets + set.id else it.groupSets - set.id) }
                    }
                }
                settingsRow(playback, "Edit a list", "", "Switch single groups on or off, reorder, add or remove them.") {
                    val labels = LinkRules.GROUP_SETS.map { "${it.label} · ${rules.activeGroups(it).size} of ${rules.groups(it).size} on" }
                    AlertDialog.Builder(this).setTitle("Edit which list?").setItems(labels.toTypedArray()) { _, which ->
                        navigate { playbackPage("group:${LinkRules.GROUP_SETS[which].id}") }
                    }.setNegativeButton("Cancel", null).show()
                }
            }
            "autoplay" -> {
            val searchPreferences = SourceSearchPreferences(this)
            val mode = searchPreferences.mode(store.get(AUTOPLAY_FIRST) == "true")
            settingsRow(playback, "Auto-play", searchPreferences.modeLabel(mode),
                "What Play starts on its own, in your sort order: the first good link as soon as one is in, the best after a few seconds, or the best once the search ends. " +
                "The link shows for ${SourceSearchPreferences.COUNTDOWN_SECONDS} seconds first: press any key (or tap) to choose yourself.") {
                val modes = SourceSearchPreferences.MODES
                val labels = listOf("Off · show the link list", "First good link · fastest", "Best link after a few seconds", "Best link when the search ends")
                AlertDialog.Builder(this).setTitle("Auto-play")
                    .setSingleChoiceItems(labels.toTypedArray(), modes.indexOf(mode)) { dialog, index ->
                        searchPreferences.setMode(modes[index]); store.put(AUTOPLAY_FIRST, (modes[index] != SourceSearchPreferences.OFF).toString())
                        dialog.dismiss(); refreshSettings()
                    }.setNegativeButton("Cancel", null).show()
            }
            if (mode == SourceSearchPreferences.WAIT) settingsRow(playback, "Wait before auto-play", "${searchPreferences.waitSeconds} seconds",
                "How long the search collects links before the best one starts.") {
                NumberStepper.show(this, "Wait before auto-play", "", searchPreferences.waitSeconds, SourceSearchPreferences.WAIT_RANGE, { "$it seconds" }) { seconds ->
                    searchPreferences.waitSeconds = seconds; refreshSettings()
                }
            }
            settingsToggle(playback, "Auto-play limit", "Auto-play skips links above your bitrate limit (file size over the title's runtime). When the runtime is unknown, the movie or episode size limit applies. Links of unknown size can still play, and you can still choose any link yourself.",
                store.get(AUTOPLAY_LIMIT_ON) == "true") { on -> store.put(AUTOPLAY_LIMIT_ON, on.toString()); refreshSettings() }
            if (store.get(AUTOPLAY_LIMIT_ON) == "true") {
                settingsRow(playback, "Highest bitrate", "${autoplayMaxMbps()} Mbps", "For movies and episodes. Pick it from your internet speed, e.g. 25 Mbps on a 40 Mbps connection.") {
                    NumberStepper.show(this, "Highest bitrate", "Auto-play picks links up to this bitrate. Hold ▲ or ▼ to go faster.", autoplayMaxMbps(), 1..500, { "$it Mbps" }) { mbps ->
                        store.put(AUTOPLAY_MAX_MBPS, mbps.toString()); refreshSettings()
                    }
                }
                listOf(false to "Movie", true to "Episode").forEach { (episode, kind) ->
                    settingsRow(playback, "$kind size fallback", "${autoplayLimitGb(episode)} GB", "Used when the ${kind.lowercase()}'s runtime is unknown.") {
                        NumberStepper.show(this, "$kind size fallback", "Largest ${kind.lowercase()} file when its runtime is unknown. Hold ▲ or ▼ to go faster.", autoplayLimitGb(episode), 1..1000, { "$it GB" }) { gb ->
                            store.put(if (episode) AUTOPLAY_MAX_GB_EPISODE else AUTOPLAY_MAX_GB, gb.toString()); refreshSettings()
                        }
                    }
                }
            }
            settingsToggle(playback, "Auto-play next episode", "When an episode's credits start, Next Episode counts down from 10 and then plays the next episode " +
                "(in the last 12 seconds when the credits aren't known). Press OK on it to start at once, or rewind to cancel.",
                SkipButtonPreferences(this).autoNext) { on -> SkipButtonPreferences(this).autoNext = on }
            }
            else -> {
            val readAhead = ReadAheadPreferences(this)
            settingsRow(playback, "Read-ahead", readAhead.mode.label,
                "How far ahead the player downloads, to ride out slow connections. Applies to the next video.") {
                AlertDialog.Builder(this).setTitle("Read-ahead")
                    .setSingleChoiceItems(ReadAhead.entries.map { it.label }.toTypedArray(), readAhead.mode.ordinal) { dialog, index ->
                        readAhead.mode = ReadAhead.entries[index]; dialog.dismiss(); refreshSettings()
                    }.setNegativeButton("Cancel", null).show()
            }
            val videoPreferences = VideoCompatibilityPreferences(this)
            settingsRow(playback, "Dolby Vision", videoPreferences.mode.label,
                "Applies to the next video. Uses a verified Profile 7/8 HDR10 or HLG base picture when supported. Profile 5 needs compatible Dolby Vision decoding.") {
                AlertDialog.Builder(this).setTitle("Dolby Vision compatibility")
                    .setSingleChoiceItems(DolbyVisionMode.entries.map { it.label }.toTypedArray(), videoPreferences.mode.ordinal) { dialog, index ->
                        videoPreferences.mode = DolbyVisionMode.entries[index]; dialog.dismiss(); refreshSettings()
                    }.setNegativeButton("Cancel", null).show()
            }

            settingsToggle(playback, "Mini player", "Leaving the player keeps the video going in a small window while you browse. Turn it off to stop the video instead when you leave (your progress is saved).",
                MiniPlayerPreferences(this).enabled) { on -> MiniPlayerPreferences(this).enabled = on }
            settingsToggle(playback, "Skip buttons", "Skip intro, Skip recap and Skip credits buttons, and Next episode when an episode's credits start. " +
                "Times come from TheIntroDB, and AniSkip for anime; titles they don't know show no button.",
                SkipButtonPreferences(this).enabled) { on -> SkipButtonPreferences(this).enabled = on }
            settingsToggle(playback, "Seek previews (Seekr)", "Shows a thumbnail of the scene above the seek bar while you seek. Previews come from Seekr, which has them ready for many movies and shows. Needs your Seekr API key below.",
                SeekrPreferences(this).enabled) { on -> SeekrPreferences(this).enabled = on }
            seekrInfo(playback)
            val seekr = store.get(SeekrPreferences.KEY)
            val seekrBuilt = BuildConfig.SEEKR_KEY.isNotBlank()
            settingsRow(playback, "Seekr API key", if (seekr.isNotBlank()) "Connected" else if (seekrBuilt) "Built in" else "Not set",
                "Put your API key from seekr.tv here to get thumbnail previews while seeking. Sign up at seekr.tv, copy your key (it starts with sk_live_) and paste it here. Titles Seekr doesn't have simply show no preview.",
                status = seekr.isNotBlank() || seekrBuilt) {
                inputSetting("Seekr API key", "Paste your key from seekr.tv (sk_live_...)", "", secret = true) { key ->
                    store.put(SeekrPreferences.KEY, key.trim()); refreshSettings()
                    // Ask Seekr whether it accepts the key, so a typo is caught here rather than as missing previews later.
                    Thread {
                        val ok = runCatching { kotlinx.coroutines.runBlocking { tv.seekr.previews.android.Seekr.create(key.trim()).validateKey() } }.getOrNull()
                        runOnUiThread { toast(when (ok) { true -> "Seekr key saved · ✓ Seekr accepts the key"; false -> "Seekr key saved, but Seekr doesn't accept it. Check the key."; null -> "Seekr key saved (couldn't reach Seekr to check it)" }) }
                    }.start()
                }
            }
            if (seekr.isNotBlank()) settingsRow(playback, "Remove Seekr key", danger = true) {
                confirm("Remove your Seekr key?") { store.remove(SeekrPreferences.KEY); refreshSettings() }
            }
            }
        }
        body.addView(View(this), margin(-1, 24))
        focusAfterRender(generation)
    }

    /** Each add-on's own time limit (0 = follow the max search time), with its average answer time to go by. */
    private fun maxSearchTimePage() {
        current = { maxSearchTimePage() }
        destination = "settings"; refreshNavigation()
        val search = SourceSearchPreferences(this)
        val global = if (search.timeLimitSeconds == 0) "no limit" else "${search.timeLimitSeconds} s"
        page("Max search time", "How long the search waits for add-ons. Links show as they answer; add-ons that haven't answered in time are left out of that search.")
        val overall = settingsGroup("All add-ons", "library", heading = false)
        settingsRow(overall, "All add-ons", search.timeLimitLabel, "The time for every add-on without its own. 0 = no limit: wait for every add-on.") {
            NumberStepper.show(this, "Max search time", "0 = no limit: wait for every add-on.", search.timeLimitSeconds,
                SourceSearchPreferences.TIME_LIMIT_RANGE, { if (it == 0) "No limit" else "$it seconds" }) { seconds -> search.timeLimitSeconds = seconds; refreshSettings() }
        }
        val card = settingsGroup("Per add-on · shorter or longer than all add-ons ($global)", null)
        val providers = SourceProviders(store).load().map { it.id to it.name } + (if (Easynews.active(store)) listOf("easynews" to "Easynews") else emptyList())
        providers.forEach { (id, name) ->
            val own = search.ownLimit(id)
            settingsRow(card, name, if (own == 0) "Follows max search time" else "$own seconds", search.speedLabel(id).ifBlank { "No searches timed yet" }) {
                NumberStepper.show(this, "$name time limit", "0 = follow the max search time ($global).", own, SourceSearchPreferences.OWN_LIMIT_RANGE,
                    { if (it == 0) "Follow" else "$it seconds" }) { seconds -> search.setOwnLimit(id, seconds); refreshSettings() }
            }
        }
    }

    /** Subtitle style: a live preview line, presets, and each part of the look on its own row. */
    private fun subtitleStylePage() {
        current = { subtitleStylePage() }
        destination = "settings"; refreshNavigation()
        page("Subtitle style", "How subtitles look in the player. The sample below changes as you go.")
        val preferences = SubtitlePreferences(this)
        body.addView(preferences.preview(), margin(-1, if (DeviceLayout.phone) 110 else 130, bottom = 12))
        fun changed() { InAppPlaybackSession.refreshSubtitleStyle(); refreshSettings() }
        fun choose(title: String, labels: List<String>, selected: Int, picked: (Int) -> Unit) {
            AlertDialog.Builder(this).setTitle(title).setSingleChoiceItems(labels.toTypedArray(), selected) { dialog, index ->
                picked(index); dialog.dismiss(); changed()
            }.setNegativeButton("Cancel", null).show()
        }
        fun sample(view: TextView, font: String, bold: Boolean, color: Int, edge: Int, edgeColor: Int, box: Int) {
            view.typeface = if (font.isEmpty()) android.graphics.Typeface.DEFAULT_BOLD else SubtitlePreferences.typeface(this, font, bold)
            view.setTextColor(color)
            when (edge) {
                androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_NONE -> view.setShadowLayer(0f, 0f, 0f, 0)
                androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW -> view.setShadowLayer(dp(2).toFloat(), dp(1).toFloat(), dp(1).toFloat(), edgeColor)
                else -> view.setShadowLayer(dp(2).toFloat(), 0f, 0f, edgeColor)
            }
            if (box != 0) { view.background = rounded(box, 3); view.setPadding(dp(5), dp(1), dp(5), dp(1)) }
        }
        val presets = settingsGroup("Presets · one tap sets font, colors and outline", null)
        data class Look(val id: String, val name: String, val font: String, val bold: Boolean, val color: Int, val edge: Int, val edgeColor: Int, val box: Int)
        val looks = listOf(
            Look("default", "App default", "lato", true, Color.WHITE, androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW, Color.BLACK, 0),
            Look("netflix", "Netflix-style", "inter", false, Color.WHITE, androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW, Color.BLACK, 0),
            Look("prime", "Prime-style", "arimo", true, Color.WHITE, androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_OUTLINE, 0xFF1A1A1A.toInt(), 0),
            Look("youtube", "YouTube-style", "roboto", false, Color.WHITE, androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_NONE, Color.BLACK, 0xBF000000.toInt()),
            Look("classic", "Classic yellow", "open-sans", true, 0xFFFFE600.toInt(), androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_OUTLINE, Color.BLACK, 0),
        )
        gridRows(presets, looks.map { look ->
            choiceTile("preset:${look.id}", look.name, false, 74, { box ->
                val stage = FrameLayout(this).apply { background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF3A5A78.toInt(), 0xFF8C7A5B.toInt())).apply { cornerRadius = dp(6).toFloat() } }
                stage.addView(text("Sample", 14, Color.WHITE).apply { sample(this, look.font, look.bold, look.color, look.edge, look.edgeColor, look.box) },
                    FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
                box.addView(stage, margin(-1, 36))
                box.addView(text(look.name, 12, ink, true).apply { gravity = Gravity.CENTER; maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, margin(-1, -2, top = 6))
            }) { preferences.applyPreset(look.id); changed() }
        }, if (DeviceLayout.phone) 3 else 5)

        val fontCard = settingsGroup("Font", null)
        val fonts = listOf(SubtitlePreferences.Companion.Font("", "Device default", "Your TV's caption font")) + SubtitlePreferences.FONTS
        gridRows(fontCard, fonts.map { font ->
            choiceTile("font:${font.id}", font.label, font.id == preferences.font, 52, { box ->
                box.addView(text(font.label, 15, ink).apply {
                    gravity = Gravity.CENTER; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                    typeface = if (font.id.isEmpty()) android.graphics.Typeface.DEFAULT_BOLD else SubtitlePreferences.typeface(this@MainActivity, font.id, bold = true)
                })
            }) { preferences.font = font.id; changed() }
        }, tileColumns())

        fun swatches(title: String, colors: List<Pair<Int?, String>>, selected: Int?, pick: (Int?) -> Unit) {
            val swatchCard = settingsGroup(title, null)
            gridRows(swatchCard, colors.map { (color, name) ->
                choiceTile("color:$title:$name", "$title $name", color == selected, 44, { box ->
                    box.addView(View(this).apply {
                        background = if (color == null) rounded(withAlpha(Color.WHITE, 30), 6) else rounded(color, 6).apply { setStroke(dp(1), withAlpha(Color.WHITE, 60)) }
                    }, margin(-1, 22))
                    box.addView(text(name, 10, muted).apply { gravity = Gravity.CENTER; maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, margin(-1, -2, top = 4))
                }) { pick(color); changed() }
            }, if (DeviceLayout.phone) 4 else 10, gap = 6)
        }
        val palette: List<Pair<Int?, String>> = listOf<Pair<Int?, String>>(null to "Device") + SubtitlePreferences.COLORS.map { it.first to it.second }
        swatches("Text color", palette, preferences.textColor) { preferences.textColor = it }
        val outline = settingsGroup("Outline", null)
        val edges = listOf(-1 to "Device default") + SubtitlePreferences.EDGE_TYPES
        gridRows(outline, edges.map { (type, name) ->
            choiceTile("edge:$type", name, type == preferences.edgeType, 44, { box ->
                box.addView(text(name, 13, ink, true).apply { gravity = Gravity.CENTER; maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
            }) { preferences.edgeType = type; changed() }
        }, tileColumns())
        if (preferences.edgeType != androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_NONE) swatches("Outline color", palette, preferences.edgeColor) { preferences.edgeColor = it }
        val boxCard = settingsGroup("Box behind text", null)
        val opacities = listOf(-1) + SubtitlePreferences.OPACITIES
        gridRows(boxCard, opacities.map { value ->
            val name = when (value) { -1 -> "Device default"; 0 -> "None"; 100 -> "Solid"; else -> "$value%" }
            choiceTile("box:$value", "Box $name", value == preferences.backgroundOpacity, 44, { box ->
                box.addView(text(name, 13, ink, true).apply { gravity = Gravity.CENTER; maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
            }) { preferences.backgroundOpacity = value; changed() }
        }, tileColumns())
        if (preferences.backgroundOpacity > 0) swatches("Box color", SubtitlePreferences.COLORS.map { it.first to it.second }, preferences.backgroundColor) { preferences.backgroundColor = it ?: Color.BLACK }
        val sizeCard = settingsGroup("Size", null)
        gridRows(sizeCard, SubtitlePreferences.SIZES.mapIndexed { index, size ->
            choiceTile("size:$size", SubtitlePreferences.SIZE_LABELS[index], size == preferences.sizePercent, 56, { box ->
                box.addView(text("Aa", (12 * size / 100f).toInt().coerceAtLeast(10) + 4, ink, true).apply { gravity = Gravity.CENTER })
                box.addView(text(SubtitlePreferences.SIZE_LABELS[index], 11, muted).apply { gravity = Gravity.CENTER }, margin(-1, -2, top = 2))
            }) { preferences.sizePercent = size; changed() }
        } + switchTile(Tile("subtitle_bold", "Bold text", "Reads better from the sofa", preferences.boldText) { on ->
            preferences.boldText = on; InAppPlaybackSession.refreshSubtitleStyle(); refreshSettings()
        }), tileColumns())
        val looks2 = settingsGroup("Position", null)
        settingsRow(looks2, "Position", preferences.positionLabel) {
            SubtitlePositionPicker.show(this, liveVideo = false, changed = { InAppPlaybackSession.refreshSubtitleStyle() }, dismissed = { refreshSettings() })
        }
        settingsRow(looks2, "Reset subtitle style", "", "Back to the app's default: bold white Lato, medium, black drop shadow, no box.", danger = true) {
            preferences.applyPreset("default"); changed()
        }
    }

    /**
     * The subtitle font list: each name written in its own font, in a list that scrolls on its own so Cancel always
     * stays in view below it.
     */
    private fun subtitleFontPicker(preferences: SubtitlePreferences, fonts: List<SubtitlePreferences.Companion.Font>, changed: () -> Unit) {
        var dialog: AlertDialog? = null
        val list = column().apply { setPadding(dp(14), dp(6), dp(14), dp(6)) }
        var selectedRow: View? = null
        fonts.forEach { font ->
            val selected = font.id == preferences.font
            val row = column().apply {
                isFocusable = true; isFocusableInTouchMode = DeviceLayout.tv; isClickable = true; tag = "font:${font.id}"
                setPadding(dp(14), dp(9), dp(14), dp(9))
                background = if (selected) rounded(withAlpha(Color.WHITE, 30), 9) else null
            }
            row.addView(text((if (selected) "✓  " else "") + font.label, 19, Color.WHITE).apply {
                typeface = if (font.id.isEmpty()) android.graphics.Typeface.DEFAULT_BOLD else SubtitlePreferences.typeface(this@MainActivity, font.id, bold = true)
            })
            row.addView(text(font.description, 12, muted), margin(-1, -2, top = 2))
            row.setOnFocusChangeListener { view, focused ->
                view.background = if (focused) rounded(withAlpha(Color.WHITE, 60), 9) else if (selected) rounded(withAlpha(Color.WHITE, 30), 9) else null
            }
            row.setOnClickListener { preferences.font = font.id; dialog?.dismiss(); changed() }
            list.addView(row, margin(-1, -2, bottom = 4))
            if (selected) selectedRow = row
        }
        val scroll = ScrollView(this).apply { addView(list); scrollBarStyle = View.SCROLLBARS_OUTSIDE_OVERLAY }
        val shown = AlertDialog.Builder(this).setTitle("Subtitle font").setView(scroll).setNegativeButton("Cancel", null).show()
        dialog = shown
        // A fixed height: the list scrolls inside it, the title and Cancel never move.
        shown.window?.setLayout(minOf(dp(620), resources.displayMetrics.widthPixels - dp(32)), (resources.displayMetrics.heightPixels * 0.82).toInt())
        scroll.post { (selectedRow ?: list.getChildAt(0))?.requestFocus() }
    }

    /** Runs [loads] once [view] is within about a screen of the visible area (on scroll, or at once if it already is). */
    private fun loadWhenNear(view: View, loads: List<() -> Unit>) {
        if (loads.isEmpty()) return
        var done = false
        lateinit var listener: android.view.ViewTreeObserver.OnScrollChangedListener
        fun check() {
            if (done || !view.isAttachedToWindow) return
            val spot = IntArray(2); view.getLocationOnScreen(spot)
            if (spot[1] < resources.displayMetrics.heightPixels + dp(260)) {
                done = true; loads.forEach { it() }
                view.viewTreeObserver.takeIf { it.isAlive }?.removeOnScrollChangedListener(listener)
            }
        }
        listener = android.view.ViewTreeObserver.OnScrollChangedListener { check() }
        view.viewTreeObserver.addOnScrollChangedListener(listener)
        view.post { check() }
    }

    /** One group list (trusted or hidden): a switch per group, hold OK to move or remove, add, and reset. */
    private fun groupListPage(card: LinearLayout, id: String) {
        val set = LinkRules.groupSet(id) ?: return
        val sort = SourceSortPreferences(this)
        val rules = sort.rules
        val trusted = LinkRules.GROUP_SETS.any { it.id == id }
        fun change(edit: (LinkRules.Rules) -> LinkRules.Rules) { sort.rules = edit(sort.rules) }
        fun saveList(list: List<String>) { change { it.copy(customGroups = it.customGroups + (id to list.map(String::trim).filter(String::isNotBlank).distinctBy { name -> name.lowercase() }.take(80))) }; refreshSettings() }
        val groups = rules.groups(set)
        val hold = if (DeviceLayout.tv) "Hold OK" else "Press and hold"
        // Hidden groups have no order, so they are tiles: a name and a switch each; hold for Remove.
        if (!trusted) {
            val tiles = groups.map { group ->
                switchTile(Tile("group:$group", group, "", rules.groupOn(set, group)) { on ->
                    change { current ->
                        val off = current.disabledGroups[id].orEmpty().let { names -> if (on) names - group.lowercase() else names + group.lowercase() }
                        current.copy(disabledGroups = current.disabledGroups + (id to off))
                    }
                }).apply {
                    setOnLongClickListener {
                        AlertDialog.Builder(this@MainActivity).setTitle(group).setItems(arrayOf("Remove")) { _, _ -> saveList(groups.filterNot { it == group }) }
                            .setNegativeButton("Cancel", null).show()
                        true
                    }
                }
            }
            card.addView(text("${rules.activeGroups(set).size} of ${groups.size} on · links from groups that are on are hidden · $hold a tile to remove it.", 11, muted),
                margin(-1, -2, top = 6).apply { leftMargin = dp(10); rightMargin = dp(10) })
            gridRows(card, tiles, tileColumns())
        }
        if (trusted) groups.forEachIndexed { index, group ->
            settingsDivider(card)
            val row = toggleRow(if (trusted) "${index + 1}.  $group" else group, "$hold to move or remove.", rules.groupOn(set, group)) { on ->
                change { current ->
                    val off = current.disabledGroups[id].orEmpty().let { names -> if (on) names - group.lowercase() else names + group.lowercase() }
                    current.copy(disabledGroups = current.disabledGroups + (id to off))
                }
            }
            row.setOnLongClickListener {
                val actions = listOfNotNull(
                    ("Move up" to { saveList(groups.toMutableList().apply { add(index - 1, removeAt(index)) }) }).takeIf { index > 0 },
                    ("Move down" to { saveList(groups.toMutableList().apply { add(index + 1, removeAt(index)) }) }).takeIf { index < groups.lastIndex },
                    ("Remove" to { saveList(groups.filterIndexed { position, _ -> position != index }) }),
                )
                AlertDialog.Builder(this).setTitle(group).setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }
                    .setNegativeButton("Cancel", null).show()
                true
            }
            card.addView(row, margin(-1, -2))
        }
        settingsRow(card, "Add a group", "", "One release group name, as it appears at the end of a link name (…-FLUX.mkv → FLUX).") {
            inputSetting("Add a group to ${set.label}", "e.g. FLUX", "") { value -> saveList(groups + value) }
        }
        if (id in rules.customGroups || rules.disabledGroups[id].orEmpty().isNotEmpty()) settingsRow(card, "Reset to the built-in list", "", "", danger = true) {
            change { it.copy(customGroups = it.customGroups - id, disabledGroups = it.disabledGroups - id) }; refreshSettings()
        }
    }

    /**
     * Link filters, one page of tiles: qualities to show, what to hide and what to prefer, then the rules that are lists
     * (words, release groups, patterns) as rows. Each tile saves at once.
     */
    private fun linkFilterTiles(card: LinearLayout) {
        val sort = SourceSortPreferences(this)
        val rules = sort.rules
        fun change(edit: (LinkRules.Rules) -> LinkRules.Rules) { sort.rules = edit(sort.rules) }
        fun hidePreset(id: String, on: Boolean) = change { it.copy(hidePresets = if (on) it.hidePresets + id else it.hidePresets - id, touched = it.touched + "hide:$id") }
        fun preferPreset(id: String, on: Boolean) = change { it.copy(preferPresets = if (on) it.preferPresets + id else it.preferPresets - id, touched = it.touched + "prefer:$id") }
        val device = if (DeviceLayout.tv) "TV" else "device"

        val qualities = settingsGroup("Qualities to show · off = those links are hidden", null)
        tileGrid(qualities, LinkRules.QUALITIES.map { (key, label) ->
            val short = when (key) { "4k" -> "4K"; "sd" -> "SD"; "unknown" -> "Unknown"; else -> label }
            val hint = when (key) { "4k" -> "2160p"; "1080" -> "Full HD"; "720" -> "HD"; "sd" -> "576p, 480p, lower"; else -> "No quality in name" }
            Tile("quality:$key", short, hint, key !in rules.hiddenQualities) { on ->
                change { it.copy(hiddenQualities = if (on) it.hiddenQualities - key else it.hiddenQualities + key) }
            }
        })

        val hide = settingsGroup("Hide · on = links with these are left out", null)
        val hideLabels = mapOf("sample" to "Samples & trailers", "threed" to "3D", "upscale" to "Upscales", "hardsub" to "Hardcoded subs",
            "dubbed" to "Dubbed", "hdtv" to "TV recordings", "remux" to "Remux", "av1" to "AV1", "x264" to "x264 / AVC")
        val hideHints = mapOf("sample" to "Sample, Trailer, Extras", "remux" to "Very large files", "av1" to "Older TVs can't decode", "x264" to "Bigger than HEVC")
        val hideTiles = mutableListOf(
            Tile("hide:badcopies", "Bad copies", "CAM, TS, screeners", LowQualityReleases.hidden(store)) { on -> store.put(LowQualityReleases.KEY, on.toString()) })
        hideTiles += hideLabels.keys.mapNotNull { id -> LinkRules.HIDE.firstOrNull { it.id == id } }.map { preset ->
            Tile("hide:${preset.id}", hideLabels[preset.id] ?: preset.label, hideHints[preset.id] ?: preset.example, preset.id in rules.hidePresets) { on -> hidePreset(preset.id, on) }
        }
        hideTiles += Tile("hide:${LinkRules.HIDE_DV_ONLY}", "Dolby Vision only", "When this $device can't play DV", LinkRules.HIDE_DV_ONLY in rules.hidePresets) { on -> hidePreset(LinkRules.HIDE_DV_ONLY, on) }
        hideTiles += Tile("hide:years", "Other years", "Movies · another film, same name", rules.hideOtherYears) { on -> change { it.copy(hideOtherYears = on) } }
        hideTiles += Tile("hide:${LinkRules.HIDE_GROUPS}", "Release groups", "Low-quality encoders", LinkRules.HIDE_GROUPS in rules.hidePresets) { on -> hidePreset(LinkRules.HIDE_GROUPS, on) }
        tileGrid(hide, hideTiles)

        val prefer = settingsGroup("Prefer · moves links up in Link sorting and auto-play", null)
        val preferLabels = mapOf("dv" to "Dolby Vision", "hdr" to "HDR", "atmos" to "Atmos", "lossless" to "Lossless audio", "remux" to "Remux / Blu-ray",
            "webdl" to "WEB-DL", "hevc" to "HEVC / x265", "tenbit" to "10-bit", "proper" to "Proper / Repack")
        val preferTiles = preferLabels.keys.mapNotNull { id -> LinkRules.PREFER.firstOrNull { it.id == id } }.map { preset ->
            val hint = if (preset.id in LinkRules.unsupportedPrefer) "Not on this $device · ignored" else preset.example
            Tile("prefer:${preset.id}", preferLabels[preset.id] ?: preset.label, hint, preset.id in rules.preferPresets) { on -> preferPreset(preset.id, on) }
        } + Tile("prefer:${LinkRules.GROUPS}", "Trusted groups", "FLUX, NTb, PSA…", LinkRules.GROUPS in rules.preferPresets) { on -> preferPreset(LinkRules.GROUPS, on) }
        tileGrid(prefer, preferTiles)

        val more = settingsGroup("More rules", null)
        settingsToggle(more, "Use Prefer", "Switch off to keep the Prefer rules but stop them moving links.", sort.preferredFirst) { on -> sort.preferredFirst = on }
        settingsRow(more, "Link sorting", sort.label, "Where Preferred sits among Library, Language, Quality and File size. Above Quality, a preferred 1080p link beats a 4K one.") {
            SourceSortPreferences.show(this, { refreshSettings() })
        }
        settingsRow(more, "Your words to prefer", rules.preferWords.take(3).joinToString(", ").ifEmpty { "None" } + if (rules.preferWords.size > 3) "…" else "",
            "Your own keywords, ranked first in their order, e.g. PSA, FLUX.") { navigate { playbackPage("words:prefer") } }
        settingsToggle(more, "Your words beat cached links", "A link with one of your words keeps its place among the links that play at once, even when it isn't cached (it may need a transfer). Auto-play still only picks links that play at once.",
            sort.wordsBeatCached) { on -> sort.wordsBeatCached = on }
        settingsRow(more, "Your words to hide", rules.hideWords.take(3).joinToString(", ").ifEmpty { "None" } + if (rules.hideWords.size > 3) "…" else "",
            "Links with any of these words are left out, e.g. YIFY.") { navigate { playbackPage("words:hide") } }
        settingsRow(more, "Trusted release groups", "${LinkRules.GROUP_SETS.count { it.id in rules.groupSets }} of ${LinkRules.GROUP_SETS.size} lists on",
            "Well-known groups, best first. Only a name's release tag counts (…x265-PSA.mkv, [SubsPlease] …).") { navigate { playbackPage("groups") } }
        LinkRules.HIDE_GROUP_SETS.forEach { set ->
            settingsRow(more, "Hide release groups", "${rules.activeGroups(set).size} of ${rules.groups(set).size} on", "${set.label}: switch single groups on or off, add or remove them.") {
                navigate { playbackPage("group:${set.id}") }
            }
        }
        patternRow(more, "Prefer pattern", rules.preferPattern) { value -> change { it.copy(preferPattern = value) } }
        patternRow(more, "Hide pattern", rules.hidePattern) { value -> change { it.copy(hidePattern = value) } }
    }

    /** One row per word (OK opens Edit / Move up / Move down / Remove), then "Add a …". */
    private fun wordRows(card: LinearLayout, words: List<String>, noun: String, example: String, save: (List<String>) -> Unit) {
        fun clean(list: List<String>) = list.map(String::trim).filter(String::isNotBlank).distinctBy { it.lowercase() }.take(LinkRules.MAX_WORDS)
        words.forEachIndexed { index, word ->
            settingsRow(card, "${index + 1}.  $word", "", "") {
                val actions = listOfNotNull(
                    ("Edit" to { inputSetting("Edit $noun", "e.g. $example", word) { value -> save(clean(words.toMutableList().apply { set(index, value) })); refreshSettings() } }),
                    ("Move up" to { save(words.toMutableList().apply { add(index - 1, removeAt(index)) }); refreshSettings() }).takeIf { index > 0 },
                    ("Move down" to { save(words.toMutableList().apply { add(index + 1, removeAt(index)) }); refreshSettings() }).takeIf { index < words.lastIndex },
                    ("Remove" to { save(words.filterIndexed { position, _ -> position != index }); refreshSettings() }),
                )
                AlertDialog.Builder(this).setTitle(word).setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }
                    .setNegativeButton("Cancel", null).show()
            }
        }
        if (words.size < LinkRules.MAX_WORDS) settingsRow(card, "Add a $noun", "", "One word or phrase per field, e.g. $example. Matches whole words in any case: $example matches “x265-$example” but not longer words.") {
            inputSetting("Add a $noun", "e.g. $example", "") { value -> save(clean(words + value)); refreshSettings() }
        }
    }

    /** A regular-expression setting: checked as it is typed, with how many of the last link list's links it matches. */
    private fun patternRow(card: LinearLayout, label: String, currentValue: String, save: (String) -> Unit) {
        settingsRow(card, label, currentValue.ifBlank { "Not set" }, "For people who know regular expressions, e.g. x265.*10bit (any case). While you type it shows how many links of the last title you opened match.") {
            val sample = StreamChoices.items.orEmpty()
            val input = TvEditText(this).apply { setText(currentValue); setSingleLine(); hint = "e.g. x265.*10bit"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD }
            val result = text("", 12, muted)
            val box = column().apply { setPadding(dp(20), dp(8), dp(20), 0); addView(input); addView(result, margin(-1, -2, top = 8)) }
            fun check(): Boolean {
                val parsed = LinkRules.pattern(input.text.toString())
                parsed.exceptionOrNull()?.let { result.text = it.message; result.setTextColor(0xFFFF8A80.toInt()); return false }
                val regex = parsed.getOrNull(); result.setTextColor(muted)
                result.text = when {
                    regex == null -> "Empty: no pattern."
                    sample.isEmpty() -> "Valid. Open a title's links to see how many would match."
                    else -> "Matches ${sample.count { regex.containsMatchIn(it.title) }} of ${sample.size} links for the last title you opened."
                }
                return true
            }
            input.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) { check() }
            })
            check()
            val dialog = AlertDialog.Builder(this).setTitle(label).setView(box)
                .setPositiveButton("Save", null).setNeutralButton("Clear", null).setNegativeButton("Cancel", null).show()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener { if (check()) { save(input.text.toString().trim()); dialog.dismiss(); refreshSettings() } }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener { save(""); dialog.dismiss(); refreshSettings() }
        }
    }

    private fun settingsToggle(card: LinearLayout, label: String, description: String, initial: Boolean,
                               contentLabel: String = label, changed: (Boolean) -> Unit) {
        settingsDivider(card)
        card.addView(toggleRow(label, description, initial, contentLabel, changed), margin(-1, -2))
    }

    /**
     * About Seekr, with QR codes to get a key and to support the developer who runs it. On a phone the codes also open
     * the links when tapped.
     */
    private fun seekrInfo(card: LinearLayout) {
        settingsDivider(card)
        val box = column().apply { setPadding(dp(14), dp(11), dp(12), dp(11)) }
        box.addView(text("Seekr (seekr.tv) serves ready-made seek-preview thumbnails for movies and shows, so the player downloads nothing extra for them. " +
            "Get your own free key at seekr.tv/join and paste it below. Seekr is run by an independent developer: if it works well for you, " +
            "consider supporting its running costs at buymeacoffee.com/seekrtvdev.", 11, muted).apply { setLineSpacing(dp(1).toFloat(), 1f) })
        val codes = LinearLayout(this).apply { gravity = Gravity.START }
        listOf(Triple("Get a key", "seekr.tv/join", "https://seekr.tv/join"),
            Triple("Support Seekr", "buymeacoffee.com/seekrtvdev", "https://buymeacoffee.com/seekrtvdev")).forEach { (title, shown, link) ->
            val tile = column().apply {
                gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(10), dp(10), dp(10), dp(8))
                background = rounded(withAlpha(Color.WHITE, 14), 12)
                val qr = ImageView(this@MainActivity).apply {
                    setImageBitmap(qrBitmap(link, dp(QR_DP))); background = rounded(Color.WHITE, 8); setPadding(dp(6), dp(6), dp(6), dp(6))
                }
                addView(qr, LinearLayout.LayoutParams(dp(QR_DP), dp(QR_DP)))
                addView(text(title, 13, ink, true).apply { gravity = Gravity.CENTER }, margin(-2, -2, top = 8))
                addView(text(shown, 10, muted).apply { gravity = Gravity.CENTER }, margin(-2, -2, top = 3))
                if (DeviceLayout.phone) {
                    isClickable = true
                    setOnClickListener { runCatching { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(link))) }.onFailure { toast(shown) } }
                }
            }
            codes.addView(tile, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(14) })
        }
        box.addView(codes, margin(-1, -2, top = 12))
        card.addView(box, margin(-1, -2))
    }

    private fun qrBitmap(text: String, size: Int): android.graphics.Bitmap {
        val matrix = com.google.zxing.qrcode.QRCodeWriter().encode(text, com.google.zxing.BarcodeFormat.QR_CODE, size, size,
            mapOf(com.google.zxing.EncodeHintType.MARGIN to 1))
        val width = matrix.width; val height = matrix.height
        val pixels = IntArray(width * height) { index -> if (matrix[index % width, index / width]) Color.BLACK else Color.WHITE }
        return android.graphics.Bitmap.createBitmap(pixels, width, height, android.graphics.Bitmap.Config.ARGB_8888)
    }

    /** The Settings row (and scroll) that opened a dialog, so the page comes back there afterwards. */
    private var settingsReturnIndex = 0
    private var settingsReturnScroll = 0

    /** Rebuilds Settings after a change, returning focus and scroll to the row the viewer was on. */
    private fun refreshSettings() {
        // While a dialog is open the page has no focused row, so fall back to the row that opened it.
        val focused = focusController.focusedIndex()
        val index = if (focused >= 0) focused else settingsReturnIndex
        val scroll = if (focused >= 0) scroller.scrollY else settingsReturnScroll
        // Sub-pages (Debrid services, Home rows) redraw themselves; anywhere else Settings is shown.
        refreshingSettings = true
        try { if (destination == "settings") current() else settings() } finally { refreshingSettings = false }
        body.post { if (!isDestroyed && destination == "settings") focusController.restoreIndex(index, scroll) }
    }

    /** A settings row with a sliding switch that changes in place; the page never reloads. */
    /** One switch in a tile grid: a short name, a hint line and a switch below; off tiles are dimmed and struck through. */
    private class Tile(val key: String, val label: String, val hint: String, val on: Boolean, val enabled: Boolean = true, val change: (Boolean) -> Unit)

    /** Tiles per row: six on a TV, three on a phone. */
    private fun tileColumns(tv: Int = 6) = if (DeviceLayout.phone) 3 else tv

    /** Lays [views] out in rows of [columns] equal cells inside a settings card. */
    private fun gridRows(card: LinearLayout, views: List<View>, columns: Int, gap: Int = 8) {
        card.clipChildren = false; card.clipToPadding = false
        views.chunked(columns).forEachIndexed { rowIndex, chunk ->
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false; clipToPadding = false; setPadding(dp(4), 0, dp(4), 0) }
            chunk.forEachIndexed { index, view -> line.addView(view, LinearLayout.LayoutParams(0, -2, 1f).apply { if (index > 0) leftMargin = dp(gap) }) }
            repeat(columns - chunk.size) { line.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f).apply { leftMargin = dp(gap) }) }
            card.addView(line, margin(-1, -2, top = if (rowIndex == 0) 6 else gap, bottom = 0))
        }
        card.addView(View(this), margin(-1, 6))
    }

    private fun tileGrid(card: LinearLayout, tiles: List<Tile>, columns: Int = tileColumns()) = gridRows(card, tiles.map(::switchTile), columns)

    /** A focusable grid cell: brighter and outlined with focus, slightly larger on TV. */
    private fun tileFocus(view: View, selected: () -> Boolean = { false }, filled: () -> Boolean = { true }) {
        fun paint(focused: Boolean) {
            view.background = rounded(withAlpha(Color.WHITE, when { focused -> 40; filled() -> 22; else -> 9 }), 10).apply {
                if (selected()) setStroke(dp(2), accent)
            }
            view.foreground = if (focused) FocusRingDrawable(Color.WHITE, dp(10).toFloat(), true, dp(1).toFloat()) else null
            if (DeviceLayout.tv) view.animate().scaleX(if (focused) 1.04f else 1f).scaleY(if (focused) 1.04f else 1f).setDuration(120).start()
        }
        view.setOnFocusChangeListener { _, focused -> paint(focused) }
        paint(false)

    }

    private fun switchTile(tile: Tile): View {
        var on = tile.on
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(8), dp(10), dp(9)); minimumHeight = dp(66)
            isFocusable = tile.enabled; isFocusableInTouchMode = DeviceLayout.tv && tile.enabled; isClickable = tile.enabled; tag = "tile:${tile.key}"
            if (!tile.enabled) alpha = 0.4f
        }
        val name = text(tile.label, 13, ink, true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        box.addView(name)
        if (tile.hint.isNotBlank()) box.addView(text(tile.hint, 10, muted).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, margin(-1, -2, top = 2))
        box.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        val track = FrameLayout(this)
        val knob = View(this).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) } }
        track.addView(knob, FrameLayout.LayoutParams(dp(14), dp(14), Gravity.CENTER_VERTICAL).apply { leftMargin = dp(3) })
        box.addView(track, margin(36, 20, top = 7))
        fun paint(animate: Boolean) {
            name.paintFlags = if (on) name.paintFlags and android.graphics.Paint.STRIKE_THRU_TEXT_FLAG.inv() else name.paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
            name.setTextColor(if (on) ink else withAlpha(muted, 200))
            track.background = rounded(if (on) accent else withAlpha(Color.WHITE, 50), 10)
            (knob.background as? GradientDrawable)?.setColor(if (on) Color.WHITE else withAlpha(Color.WHITE, 170))
            val offset = if (on) dp(16).toFloat() else 0f
            if (animate) knob.animate().translationX(offset).setDuration(140).start() else knob.translationX = offset
            box.contentDescription = "${tile.label} · ${if (on) "On" else "Off"}"
        }
        tileFocus(box, filled = { on })
        box.setOnClickListener { on = !on; tile.change(on); paint(true); box.onFocusChangeListener?.onFocusChange(box, box.isFocused) }
        paint(false)
        return box
    }

    /** A pick-one grid cell (subtitle preset, font, color, size): the chosen one has an accent outline. */
    private fun choiceTile(key: String, description: String, selected: Boolean, height: Int, fill: (LinearLayout) -> Unit, action: () -> Unit): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(8), dp(6), dp(8), dp(6)); minimumHeight = dp(height)
            isFocusable = true; isFocusableInTouchMode = DeviceLayout.tv; isClickable = true; tag = "tile:$key"
            contentDescription = description + if (selected) " · selected" else ""
        }
        fill(box)
        tileFocus(box, selected = { selected })
        box.setOnClickListener { action() }
        return box
    }

    private fun toggleRow(label: String, description: String, initial: Boolean, contentLabel: String = label, changed: (Boolean) -> Unit): View {
        var on = initial
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(11), dp(12), dp(11))
            isFocusable = true; isFocusableInTouchMode = DeviceLayout.tv; isClickable = true; tag = "toggle:$label"
        }
        val copy = column()
        val title = text(label, 14, ink, true)
        copy.addView(title)
        copy.addView(text(description, 11, muted).apply { setLineSpacing(dp(1).toFloat(), 1f) }, margin(-1, -2, top = 4))
        row.addView(copy, LinearLayout.LayoutParams(0, -2, 1f))
        val state = text("", 12, muted, true).apply { gravity = Gravity.END }
        row.addView(state, margin(40, -2, right = 10))
        val track = FrameLayout(this)
        val knob = View(this).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) } }
        track.addView(knob, FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER_VERTICAL).apply { leftMargin = dp(3) })
        row.addView(track, margin(44, 24))
        fun paint(animate: Boolean) {
            state.text = if (on) "On" else "Off"; state.setTextColor(if (on) ink else muted)
            track.background = rounded(if (on) accent else withAlpha(Color.WHITE, 60), 12)
            val offset = if (on) dp(20).toFloat() else 0f
            if (animate) knob.animate().translationX(offset).setDuration(140).start() else knob.translationX = offset
            row.contentDescription = "$contentLabel · ${if (on) "On" else "Off"}"
        }
        fun focus(focused: Boolean) {
            row.background = if (focused) rounded(withAlpha(Color.WHITE, if (flix) 34 else 24), 9) else null
            row.foreground = if (focused) FocusRingDrawable(if (flix) Color.WHITE else accent, dp(9).toFloat(), true, dp(1).toFloat()) else null
        }
        row.setOnFocusChangeListener { _, focused -> focus(focused) }
        row.setOnClickListener { on = !on; changed(on); paint(true) }
        paint(false); focus(false)
        return row
    }

    /** Settings → Source providers: order, switch on or off, edit and remove the scraper add-ons. */
    /**
     * Up/Down between rows stays in the same column: from one row's On/Off to the next row's On/Off (not to whatever
     * button happens to sit nearest, such as a move arrow). [rows] holds each row's controls left to right; null or
     * unfocusable entries are skipped, so the nearest row below or above that has that control is used.
     */
    private fun linkColumns(rows: List<List<View?>>) {
        rows.flatten().filterNotNull().forEach { if (it.id == View.NO_ID) it.id = View.generateViewId() }
        val columns = rows.maxOfOrNull { it.size } ?: 0
        for (column in 0 until columns) {
            val cells = rows.map { row -> row.getOrNull(column)?.takeIf { it.isFocusable } }
            cells.forEachIndexed { index, cell ->
                if (cell == null) return@forEachIndexed
                cells.drop(index + 1).firstOrNull { it != null }?.let { cell.nextFocusDownId = it.id }
                cells.take(index).lastOrNull { it != null }?.let { cell.nextFocusUpId = it.id }
            }
        }
    }

    /**
     * A settings row that is a switch: OK turns it on or off at once; holding OK opens [menu] (edit, test, remove...).
     * The On/Off value sits at the end in green or grey. The list shows one hint line about holding OK ([holdHint]).
     */
    private fun switchSettingsRow(card: LinearLayout, label: String, value: String, description: String, on: Boolean,
                                  tagName: String, toggle: () -> Unit, menu: () -> Unit): List<View> {
        settingsDivider(card)
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(11), dp(12), dp(11))
            isFocusable = true; isFocusableInTouchMode = DeviceLayout.tv; isClickable = true; isLongClickable = true
            tag = "$tagName:$label"
            contentDescription = "$label, ${if (on) "On" else "Off"}. Press to turn ${if (on) "off" else "on"}; hold for more."
        }
        val copy = column()
        copy.addView(text(label, 14, if (on) ink else muted, true))
        val detail = listOf(value, description).filter { it.isNotBlank() }.joinToString(" \u00b7 ")
        if (detail.isNotBlank()) copy.addView(text(detail, 11, muted).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }, margin(-1, -2, top = 4))
        row.addView(copy, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(text(if (on) "On" else "Off", 13, if (on) 0xFF46D369.toInt() else muted, true), margin(-2, -2).apply { leftMargin = dp(16) })
        row.setOnFocusChangeListener { view, focused ->
            view.background = if (focused) rounded(withAlpha(Color.WHITE, if (flix) 34 else 24), 9) else null
            view.foreground = if (focused) FocusRingDrawable(if (flix) Color.WHITE else accent, dp(9).toFloat(), true, dp(1).toFloat()) else null
        }
        fun remember() { settingsReturnIndex = focusController.focusedIndex(); settingsReturnScroll = scroller.scrollY }
        row.setOnClickListener { remember(); toggle() }
        row.setOnLongClickListener { remember(); menu(); true }
        card.addView(row.pressable(9), margin(-1, -2))
        return listOf(row)
    }

    /** The one line that tells a list of [switchSettingsRow]s how they work. */
    private fun holdHint(card: LinearLayout, what: String) {
        card.addView(text(if (DeviceLayout.tv) "OK turns $what on or off \u00b7 Hold OK for Edit, Test, Remove"
            else "Tap turns $what on or off \u00b7 Press and hold for Edit, Test, Remove", 11, muted),
            margin(-1, -2).apply { setMargins(dp(14), dp(2), dp(14), dp(6)) })
    }

    private fun sourceProvidersPage(focusTag: String? = null) {
        val keepScroll = scroller.scrollY
        current = { sourceProvidersPage() }
        destination = "settings"; refreshNavigation()
        page("Source providers", "Stremio add-ons such as Torrentio. Links from every provider that's on are merged into one list.")
        val config = SourceProviders(store)
        val providers = try { config.load() } catch (error: Exception) { body.addView(text(uiErrorMessage(error), 15, muted)); emptyList() }
        val list = settingsGroup("Providers", "library")
        if (providers.size > 1) {
            // Every add-on on or off in one press, instead of one by one.
            val all = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(8), dp(8), dp(8)) }
            val onCount = providers.count { it.enabled }
            all.addView(text("$onCount of ${providers.size} on", 13, muted, true), LinearLayout.LayoutParams(0, -2, 1f))
            fun setAll(enabled: Boolean, tagName: String) {
                try {
                    config.save(providers.map { it.copy(enabled = enabled) })
                    toast(if (enabled) "All source providers on" else "All source providers off")
                    sourceProvidersPage(tagName)
                } catch (error: Exception) { toast(uiErrorMessage(error)) }
            }
            all.addView(button("Turn all on") { setAll(true, "providers-all-on") }.apply {
                gravity = Gravity.CENTER; tag = "providers-all-on"; alpha = if (onCount == providers.size) 0.4f else 1f
            }, margin(-2, 38).apply { leftMargin = dp(6) })
            all.addView(button("Turn all off") { setAll(false, "providers-all-off") }.apply {
                gravity = Gravity.CENTER; tag = "providers-all-off"; alpha = if (onCount == 0) 0.4f else 1f
            }, margin(-2, 38).apply { leftMargin = dp(6) })
            list.addView(all, margin(-1, -2))
        }
        val columns = mutableListOf<List<View?>>()
        providers.forEachIndexed { index, provider ->
            settingsDivider(list)
            val controls = arrayOfNulls<View>(4)
            val on = provider.enabled
            val line = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(8), dp(8), dp(8)) }
            val copy = column()
            copy.addView(text(provider.name, 14, if (on) ink else muted, true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
            val host = runCatching { java.net.URI(provider.address).host }.getOrNull().orEmpty()
            val speed = SourceSearchPreferences(this).speedLabel(provider.id)
            copy.addView(text(listOf(if (on) "On" else "Off", host, speed).filter(String::isNotBlank).joinToString(" · "), 11, if (on) 0xFF46D369.toInt() else muted).apply {
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, margin(-1, -2, top = 2))
            line.addView(copy, LinearLayout.LayoutParams(0, -2, 1f))
            var slot = 0
            fun control(label: String, tagName: String, description: String, width: Int, enabled: Boolean = true, action: () -> Unit) {
                val view = button(label) { if (enabled) action() }.apply {
                    gravity = Gravity.CENTER; tag = "$tagName:${provider.id}"; contentDescription = description
                    alpha = if (enabled) 1f else 0.3f; isFocusable = enabled
                }
                controls[slot++] = view
                line.addView(view, margin(width, 38).apply { leftMargin = dp(6) })
            }
            fun save(updated: List<SourceProvider>, tagName: String) {
                try { config.save(updated); sourceProvidersPage("$tagName:${provider.id}") } catch (error: Exception) { toast(uiErrorMessage(error)) }
            }
            control("▲", "provider-up", "Move ${provider.name} up", 46, index > 0) {
                save(providers.toMutableList().apply { add(index - 1, removeAt(index)) }, "provider-up")
            }
            control("▼", "provider-down", "Move ${provider.name} down", 46, index < providers.lastIndex) {
                save(providers.toMutableList().apply { add(index + 1, removeAt(index)) }, "provider-down")
            }
            control(if (on) "On" else "Off", "provider-on", "${provider.name} · ${if (on) "On" else "Off"}", 56) {
                save(providers.map { if (it.id == provider.id) it.copy(enabled = !on) else it }, "provider-on")
            }
            control("Manage", "provider-manage", "Manage ${provider.name}", 84) { sourceProviderActions(provider) }
            list.addView(line, margin(-1, -2))
            columns += controls.toList()
        }
        linkColumns(columns)
        settingsRow(list, "Add source provider", "", "Paste an HTTPS Stremio add-on or manifest URL, for example your Torrentio or Deepbrid add-on.") { editSourceProvider(null) }
        body.addView(View(this), margin(-1, 24))
        if (focusTag != null) keepPlace(generation, focusTag, keepScroll) else focusAfterRender(generation)
    }

    /**
     * Search and details: Cinemeta, the saved add-on (on or off), the viewer's catalog add-ons as quick picks, a pasted
     * link, or removing the saved add-on. Choosing Cinemeta only switches the add-on off, so it comes back in one press.
     */
    private fun chooseMetadataSource() {
        val saved = MetadataSource.fromJson(store.get(MetadataSource.KEY))
        val on = saved != null && store.get(METADATA_ON) != "false"
        val others = catalogAddons.load().filter { it.address != saved?.address }
        data class Choice(val label: String, val action: () -> Unit)
        fun use(name: String?, address: String) = load(generation, {
            val base = SourceProviders.endpoint(address)
            val manifest = Net.json("$base/manifest.json")
            MetadataSource.inspect(name ?: manifest.optString("name").take(50).ifBlank { "Add-on" }, base, manifest)
        }) { source ->
            if (source == null) { toast("That add-on offers no search or title details."); return@load }
            store.put(MetadataSource.KEY, source.toJson()); store.put(METADATA_ON, "true"); metadataSource = source; forgetDetails()
            toast("Search ${if (source.searches.isEmpty()) "stays on Cinemeta" else "from ${source.name}"}${if (source.details) ", title details from ${source.name}" else ""}")
            refreshSettings()
        }
        val choices = buildList {
            add(Choice("Cinemeta (default)") {
                store.put(METADATA_ON, "false"); metadataSource = null; forgetDetails()
                toast(if (saved != null) "Search and details from Cinemeta. ${saved.name} is kept: pick it to turn it back on." else "Search and details from Cinemeta")
                refreshSettings()
            })
            if (saved != null) add(Choice(saved.name) {
                store.put(METADATA_ON, "true"); metadataSource = saved; forgetDetails(); toast("Search and details from ${saved.name}"); refreshSettings()
            })
            others.forEach { addon -> add(Choice(addon.name) { use(addon.name, addon.address) }) }
            add(Choice("Paste an add-on link…") {
                inputSetting("Search and details add-on", "Add-on manifest link", saved?.address?.let { "$it/manifest.json" }.orEmpty()) { link -> use(null, link) }
            })
            if (saved != null) add(Choice("Remove ${saved.name}") {
                confirm("Remove ${saved.name} from search and details? Cinemeta is used instead.") {
                    store.remove(MetadataSource.KEY); store.remove(METADATA_ON); metadataSource = null; forgetDetails(); refreshSettings()
                }
            })
        }
        AlertDialog.Builder(this).setTitle("Search and details")
            .setSingleChoiceItems(choices.map { it.label }.toTypedArray(), if (on) 1 else 0) { dialog, index ->
                dialog.dismiss(); choices[index].action()
            }.setNegativeButton("Cancel", null).show()
    }

    /**
     * Color by type, Aurora or Sunset badges on the links page and the player's Streams panel. A wide window with each
     * style's name, a short line and a sample of its badges (language flags included), so all fit without scrolling.
     */
    private fun chooseBadgeStyle() {
        val current = BadgeStyle.current(this)
        val list = column().apply { setPadding(dp(18), dp(6), dp(18), dp(6)) }
        val sample = SourceItem("Sample.2160p.BluRay.REMUX.DV.HDR10+.HEVC.TrueHD.7.1.Atmos.English.Hindi.mkv", "sample",
            sizeBytes = 40_000_000_000L, qualityHeight = 2160)
        val dialog = AlertDialog.Builder(this).setTitle("Badge style").setView(list).setNegativeButton("Cancel", null).create()
        BadgeStyle.entries.forEach { style ->
            val row = column().apply {
                setPadding(dp(14), dp(7), dp(14), dp(7)); isFocusable = true; isFocusableInTouchMode = DeviceLayout.tv; isClickable = true
                tag = "badge-style:${style.id}"; contentDescription = "${style.label}. ${style.description}"
                background = rounded(if (style == current) withAlpha(Color.WHITE, 26) else Color.TRANSPARENT, 10)
            }
            row.addView(text(if (style == current) "${style.label}  ✓" else style.label, 16, ink, true))
            row.addView(text(style.description, 12, muted).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, margin(-1, -2, top = 2))
            row.addView(SourceBadgeStrip(this, SourceBadges.from(sample, runtimeMinutes = 140).filter { it.tone != SourceBadgeTone.SOURCE }, style), margin(-1, -2, top = 6))
            row.setOnFocusChangeListener { view, focused ->
                view.background = rounded(when { focused -> withAlpha(Color.WHITE, 46); style == current -> withAlpha(Color.WHITE, 26); else -> Color.TRANSPARENT }, 10)
            }
            row.setOnClickListener { BadgeStyle.save(this, style); dialog.dismiss(); refreshSettings() }
            list.addView(row, margin(-1, -2, bottom = 4))
        }
        dialog.show()
        dialog.window?.setLayout(minOf(dp(760), resources.displayMetrics.widthPixels - dp(40)), ViewGroup.LayoutParams.WRAP_CONTENT)
        list.post { list.findViewWithTag<View>("badge-style:${current.id}")?.requestFocus() }
    }

    private fun sourceProviderActions(provider: SourceProvider) {
        val library = if (isStreamNzb(provider)) streamNzbLibraries(listOf(provider.copy(enabled = true))).firstOrNull() else null
        val signedIn = library != null && streamNzbToken(library.origin) != null
        val altLibrary = if (isAltMount(provider)) altMountLibraries(listOf(provider.copy(enabled = true))).firstOrNull() else null
        val altConnected = altLibrary != null && altMountLogin(altLibrary.origin) != null
        val options = listOfNotNull("Edit name and address", "Usenet news provider",
            library?.let { if (signedIn) "Sign out of the StreamNZB library" else "Sign in to the StreamNZB library" },
            altLibrary?.let { if (altConnected) "Disconnect the AltMount library" else "Connect the AltMount library" }, "Remove")
        AlertDialog.Builder(this).setTitle(provider.name)
            .setItems(options.toTypedArray()) { _, index ->
                when (options[index]) {
                    "Edit name and address" -> editSourceProvider(provider)
                    "Usenet news provider" -> chooseNewsProvider(provider)
                    "Sign in to the StreamNZB library" -> library?.let { signInStreamNzb(it) { sourceProvidersPage() } }
                    "Connect the AltMount library" -> altLibrary?.let { signInAltMount(it) { sourceProvidersPage() } }
                    "Disconnect the AltMount library" -> {
                        store.putAll(mapOf(AltMountLibrary.ORIGIN to "", AltMountLibrary.USER to "", AltMountLibrary.PASSWORD to ""))
                        toast("AltMount library disconnected on this device"); sourceProvidersPage()
                    }
                    "Sign out of the StreamNZB library" -> {
                        store.putAll(mapOf(StreamNzbLibrary.TOKEN to "", StreamNzbLibrary.ORIGIN to ""))
                        toast("Signed out of the StreamNZB library on this device"); sourceProvidersPage()
                    }
                    "Remove" -> confirm("Remove ${provider.name} from source providers?") {
                        try {
                            val config = SourceProviders(store); config.save(config.load().filterNot { it.id == provider.id }); sourceProvidersPage()
                        } catch (error: Exception) { toast(uiErrorMessage(error)) }
                    }
                }
            }.setNegativeButton("Cancel", null).show()
    }

    /**
     * The Usenet news provider an AIOStreams add-on downloads through: AIOStreams doesn't say which, so the viewer
     * picks it here and its Usenet links show that provider's logo.
     */
    private fun chooseNewsProvider(provider: SourceProvider) {
        val choices = AddonBadges.NEWS_PROVIDERS
        AlertDialog.Builder(this).setTitle("${provider.name} · Usenet news provider")
            .setSingleChoiceItems(choices.map { it.second }.toTypedArray(), choices.indexOfFirst { it.first == provider.newsProvider }.coerceAtLeast(0)) { dialog, index ->
                try {
                    val config = SourceProviders(store)
                    config.save(config.load().map { if (it.id == provider.id) it.copy(newsProvider = choices[index].first) else it })
                    dialog.dismiss(); toast(if (index > 0) "${provider.name}: Usenet links show the ${choices[index].second} logo." else "${provider.name}: no news-provider logo."); sourceProvidersPage()
                } catch (error: Exception) { toast(uiErrorMessage(error)) }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun editSourceProvider(provider: SourceProvider?, prefill: String? = null) {
        val content = column().apply { setPadding(dp(18), dp(8), dp(18), dp(8)) }
        val name = TvEditText(this).apply { hint = "Provider name"; setSingleLine(); setText(provider?.name.orEmpty()) }
        val address = TvEditText(this).apply {
            hint = "HTTPS Stremio-compatible add-on URL"; setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setText(provider?.address.orEmpty())
        }
        val status = text("", 14, muted)
        content.addView(name, margin(-1, -2)); content.addView(address, margin(-1, -2, top = 8)); content.addView(status, margin(-1, -2, top = 8))
        val builder = AlertDialog.Builder(this).setTitle(if (provider == null) "Add source provider" else "Edit source provider")
            .setView(content).setPositiveButton("Save", null).setNegativeButton("Cancel", null)
        // Long add-on links are hard to type with a remote: paste them on the phone instead.
        if (DeviceLayout.tv && !publicPreview) builder.setNeutralButton("Use phone", null)
        val dialog = builder.create()
        dialog.show(); dialog.submitOnEnter(name, address)
        val save = dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE)
        /** Fills in a link from the phone or from a stremio:// / shared link, and the add-on's own name when none was given. */
        fun fill(link: String, sentName: String) {
            if (!dialog.isShowing) return
            val typed = AddonLinks.https(link)
            address.setText(typed)
            if (sentName.isNotBlank()) name.setText(sentName)
            if (!name.text.isNullOrBlank()) { status.text = "Check it, then Save."; save.requestFocus(); return }
            status.text = "Reading the add-on's name…"
            worker.execute {
                val found = runCatching { Net.json(AddonLinks.manifest(typed)).optString("name").trim().take(60) }.getOrDefault("")
                runOnUiThread {
                    if (!dialog.isShowing) return@runOnUiThread
                    if (found.isNotBlank() && name.text.isNullOrBlank()) name.setText(found)
                    status.text = if (name.text.isNullOrBlank()) "Give it a name, then Save." else "Check it, then Save."
                    save.requestFocus()
                }
            }
        }
        dialog.getButton(android.content.DialogInterface.BUTTON_NEUTRAL)?.setOnClickListener { accountDialogs.phoneAddon(::fill) }
        prefill?.let { fill(it, "") }
        save.setOnClickListener {
            val typedName = name.text.toString(); val typedAddress = address.text.toString()
            try { SourceProviders.name(typedName); SourceProviders.endpoint(typedAddress) }
            catch (error: Exception) { status.text = uiErrorMessage(error); return@setOnClickListener }
            // A bare StremThru server address becomes its Torz torrent search; this needs a quick look at the server.
            status.text = "Checking the address…"; save.isEnabled = false
            worker.execute {
                val resolved = SourceProviders.resolveServer(typedAddress) { Net.json(it) }
                runOnUiThread {
                    if (isFinishing || isDestroyed || !dialog.isShowing) return@runOnUiThread
                    save.isEnabled = true
                    try {
                        val config = SourceProviders(store)
                        val updated = if (provider == null) SourceProviders.create(typedName, resolved)
                            else provider.copy(name = SourceProviders.name(typedName), address = SourceProviders.endpoint(resolved))
                        val existing = config.load()
                        config.save(if (provider == null) existing + updated else existing.map { if (it.id == provider.id) updated else it })
                        if (resolved.contains("/stremio/torz/")) toast("StremThru found · set up automatically")
                        dialog.dismiss(); sourceProvidersPage()
                    } catch (error: Exception) { status.text = uiErrorMessage(error) }
                }
            }
        }
    }

    private fun connectOffcloud() {
        val content = column().apply { setPadding(dp(18), dp(8), dp(18), dp(8)) }
        val input = TvEditText(this).apply {
            hint = "Paste your own Offcloud API key"; setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        val status = text("", 14, muted)
        content.addView(input, margin(-1, -2)); content.addView(status, margin(-1, -2, top = 8))
        val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
        val dialog = AlertDialog.Builder(this).setTitle("Offcloud API key").setView(content)
            .setPositiveButton("Connect", null).setNegativeButton("Cancel", null).create()
        dialog.setOnDismissListener { cancelled.set(true); input.setText("") }
        dialog.show(); dialog.submitOnEnter(input)
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            val key = input.text.toString().trim()
            if (key.isBlank()) { input.error = "Enter your Offcloud API key"; return@setOnClickListener }
            val request = generation
            val connect = dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE)
            connect.isEnabled = false; status.text = "Checking your Offcloud account…"
            worker.execute {
                try {
                    OffcloudApi(store).setApiKey(key) { cancelled.get() }
                    runOnUiThread {
                        if (dialog.isShowing && request == generation && !isFinishing && !isDestroyed) {
                            if (store.get("playback.priority").isBlank() && !store.has("torbox.token")) store.put("playback.service", "offcloud")
                            dialog.dismiss()
                            toast("Offcloud connected"); refreshSettings()
                        }
                    }
                } catch (error: Exception) {
                    runOnUiThread { if (dialog.isShowing) {
                        connect.isEnabled = true; status.text = uiErrorMessage(error)
                    } }
                }
            }
        }
    }

    private fun weTrakrClient() = wetrakr
    /** Sign-in to the chosen tracker with an on-screen code and QR; both apps' public Client IDs are built in. */
    private fun startWeTrakr() {
        val tracker = wetrakr
        authorize(tracker.name, { tracker.startDeviceAuth() }, { tracker.pollDeviceAuth(it) },
            cancel = { tracker.cancelDeviceAuth(it) })
    }

    /** WeTrakr or Simkl. The menu, Home rows and Settings follow the choice; each keeps its own sign-in. */
    private fun chooseTracker() {
        val ids = listOf(Trackers.WETRAKR, Trackers.SIMKL)
        AlertDialog.Builder(this).setTitle("Tracking service")
            .setSingleChoiceItems(arrayOf("WeTrakr", "Simkl"), ids.indexOf(Trackers.selected(store))) { dialog, index ->
                dialog.dismiss()
                if (ids[index] == Trackers.selected(store)) return@setSingleChoiceItems
                Trackers.select(store, ids[index]); simklChosen = ids[index] == Trackers.SIMKL; remoteProgress.clear(); retainedHome = null
                watched = WatchedState.EMPTY; refreshWatched()
                // The menu's tracker entry is renamed, so the shell is rebuilt around Settings,
                // returning to the same row and scroll position.
                val index = focusController.focusedIndex(); val scroll = scroller.scrollY
                destination = "settings"; backStack.clear(); current = { settings() }; shell(); settings()
                body.post { if (!isDestroyed && destination == "settings") focusController.restoreIndex(index, scroll) }
                if (!wetrakr.signedIn()) startWeTrakr()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun startTorBox() { authorize("TorBox", { torbox.startDeviceAuth() }, { torbox.pollDeviceAuth(it) }, cancel = { torbox.cancelDeviceAuth(it) }) }
    private fun authorize(name: String, start: () -> DeviceAuth, poll: (DeviceAuth) -> Boolean,
                          cancel: (DeviceAuth?) -> Unit = {}, configure: (() -> Unit)? = null) {
        if (authorization?.isShowing == true) return
        authorization = DeviceAuthorizationDialog(this, name, authWorker, start, poll, onConnected = {
            if (name == wetrakr.name) { wetrakrOutbox.clearPending(); remoteProgress.clear(); refreshWatched(force = true) }
            toast("$name connected"); if (destination == "settings") refreshSettings() else current()
        }, onCancel = cancel, configure = configure).also { it.show() }
    }

    private fun retrySync() {
        worker.execute {
            try {
                val revision = wetrakr.accountRevision()
                val scrobbleRevision = wetrakr.scrobbleRevision()
                if (wetrakr.connected() && wetrakr.scrobblingEnabled()) wetrakrOutbox.pending().take(10).forEach { event ->
                    if (event.accountRevision != revision || event.timestamp.isBlank() || event.scrobbleRevision != scrobbleRevision || history.isHidden(identity = event.identity)) wetrakrOutbox.acknowledge(event.identity, event.revision)
                    else try {
                        if (wetrakr.restoreProgress(event.identity, event.percent, event.action, event.timestamp, revision, scrobbleRevision))
                            wetrakrOutbox.acknowledge(event.identity, event.revision)
                    } catch (_: Exception) { /* Retain this account's final state for a future launch. */ }
                }
            } catch (_: Exception) { /* Keep playback usable when tracking is unavailable. */ }
        }
    }

    private fun identityKey(identity: PlaybackIdentity) = "${identity.imdbId}:${identity.season}:${identity.episode}"

    private fun inputSetting(title: String, hintText: String, initial: String, secret: Boolean = false, done: (String) -> Unit) {
        val input = TvEditText(this).apply { hint = hintText; setText(initial); setSingleLine(); inputType = InputType.TYPE_CLASS_TEXT or if (secret) InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD }
        AlertDialog.Builder(this).setTitle(title).setView(input).setPositiveButton("Save") { _, _ -> val value = input.text.toString().trim(); if (value.isNotBlank()) done(value) }.setNegativeButton("Cancel", null).show().submitOnEnter(input)
    }

    private fun hideKeyboard(field: View) =
        (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(field.windowToken, 0)

    private fun <T> read(id: Int, work: () -> T, done: (T) -> Unit) = load(id, work, readOnly = true, done = done)

    private fun <T> load(id: Int, work: () -> T, readOnly: Boolean = false, quiet: Boolean = false, done: (T) -> Unit) {
        pendingLoads[id] = pendingLoads[id].orZero() + 1
        val progress = text("Loading…", 13, muted); body.addView(progress, margin(-1, -2, top = 12, bottom = 12))
        // Flix Home loads behind its splash and backdrop; a text label there only flashes in a corner.
        if (quiet || (flix && isHome)) progress.visibility = View.INVISIBLE
        worker.execute {
            if (readOnly && id != generation) { runOnUiThread { pendingLoads[id] = (pendingLoads[id].orZero() - 1).coerceAtLeast(0) }; return@execute }
            try { val result = work(); runOnUiThread { pendingLoads[id] = (pendingLoads[id].orZero() - 1).coerceAtLeast(0); if (id == generation && !isFinishing && !isDestroyed) { body.removeView(progress); done(result); focusAfterRender(id) } } }
            catch (error: Exception) { runOnUiThread { pendingLoads[id] = (pendingLoads[id].orZero() - 1).coerceAtLeast(0); if (id == generation && !isFinishing && !isDestroyed) { body.removeView(progress); showError(error); focusAfterRender(id); dismissSplash() } } }
        }
    }

    private fun showError(error: Exception) {
        val message = uiErrorMessage(error)
        body.addView(text(message, 14, Color.rgb(255, 185, 170)), margin(-1, -2, top = 12, bottom = 10))
        body.addView(button("Try again") { current() }, margin(150, 40, bottom = 12))
    }
    /** Flix option: Continue Watching as wide resume cards (artwork plus an info panel) instead of regular cards. */
    /** Size fallback in GB when the runtime is unknown: 20 for movies (the older single limit), 5 for episodes. */
    private fun autoplayLimitGb(episode: Boolean) = store.get(if (episode) AUTOPLAY_MAX_GB_EPISODE else AUTOPLAY_MAX_GB)
        .toIntOrNull()?.takeIf { it in 1..1000 } ?: if (episode) 5 else 20
    /** Highest estimated bitrate Auto-play may pick, for movies and episodes alike. */
    private fun autoplayMaxMbps() = store.get(AUTOPLAY_MAX_MBPS).toIntOrNull()?.takeIf { it in 1..500 } ?: 25
    /**
     * Prefer switches that can't help on this device are ignored when ranking: Dolby Vision without a DV decoder, HDR
     * without 10-bit HEVC, Atmos when the audio can't be passed through (decoded here, Atmos is lost anyway).
     */
    private fun markUnsupportedPrefer() {
        val app = applicationContext
        Thread {
            val caps = DeviceVideoSupport.caps()
            @Suppress("DEPRECATION")
            val audio = runCatching { androidx.media3.exoplayer.audio.AudioCapabilities.getCapabilities(app) }.getOrNull()
            val atmos = audio != null && (audio.supportsEncoding(androidx.media3.common.C.ENCODING_E_AC3_JOC) || audio.supportsEncoding(androidx.media3.common.C.ENCODING_DOLBY_TRUEHD))
            LinkRules.unsupportedPrefer = setOfNotNull("dv".takeUnless { caps.dolbyVision }, "hdr".takeUnless { caps.hevc10 }, "atmos".takeUnless { atmos })
        }.start()
    }


    private fun resumeCards() = flix && store.get(RESUME_CARDS) != "false"  // on unless the viewer turns it off
    private fun continueSlot() = margin(if (resumeCards()) (if (DeviceLayout.phone) 260 else RESUME_CARD_WIDTH) else cardWidth(), theme.layout.cardHeightDp, right = theme.layout.cardGapDp)
    private fun episodeLine(identity: PlaybackIdentity) = if (identity.type == "series") "S${identity.season} E${identity.episode}" else "Movie"

    /** Same height as every shelf, wider: artwork on the left, then title, episode, time left with a play mark, and progress. */
    private fun resumeCard(item: MediaItemData, progress: Double, detail: String, timeLeft: String, action: () -> Unit): View {
        val radius = dp(6).toFloat()
        val idle = rounded(panel, 6); val lifted = rounded(0xFF3A3A40.toInt(), 6)
        val frame = FrameLayout(this).apply {
            background = idle; clipToOutline = true; isFocusable = true; isFocusableInTouchMode = DeviceLayout.tv; isClickable = true
            tag = "media:${item.type}:${item.imdbId.ifBlank { item.title }}"
            contentDescription = listOf(item.title, detail, timeLeft).filter(String::isNotBlank).joinToString(", ")
        }
        val artWidth = dp(if (DeviceLayout.phone) 120 else 170)
        val art = FixedSizeImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        frame.addView(art, FrameLayout.LayoutParams(artWidth, -1))
        image(art, thumbnail(backdropFor(item)))
        val fade = View(this)
        frame.addView(fade, FrameLayout.LayoutParams(dp(60), -1).apply { leftMargin = artWidth - dp(60) })
        fun paintFade(color: Int) { fade.background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(withAlpha(color, 0), color)) }
        paintFade(panel)
        val info = column().apply { setPadding(dp(8), dp(12), dp(12), dp(10)) }
        info.addView(text(item.title, 13, Color.WHITE, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        info.addView(text(detail, 10, muted), margin(-1, -2, top = 3))
        info.addView(View(this), LinearLayout.LayoutParams(-1, 0, 1f))
        val left = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        left.addView(text("▶", 7, Color.BLACK, true).apply {
            gravity = Gravity.CENTER; background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) }
        }, margin(16, 16, right = 6))
        left.addView(text(timeLeft, 10, 0xFFDDDDDD.toInt()))
        info.addView(left)
        val track = FrameLayout(this).apply { background = rounded(0xFF4A4A4A.toInt(), 2) }
        track.addView(View(this).apply { background = rounded(theme.palette.progress, 2) }, FrameLayout.LayoutParams(0, -1))
        track.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val fill = track.getChildAt(0); val width = (track.width * progress.coerceIn(0.0, 1.0)).roundToInt()
            if (fill.layoutParams.width != width) fill.layoutParams = fill.layoutParams.apply { this.width = width }
        }
        info.addView(track, margin(-1, 3, top = 6))
        frame.addView(info, FrameLayout.LayoutParams(-1, -1).apply { leftMargin = artWidth })
        frame.setOnFocusChangeListener { view, focused ->
            view.background = if (focused) lifted else idle
            paintFade(if (focused) 0xFF3A3A40.toInt() else panel)
            view.foreground = if (focused) FocusRingDrawable(Color.WHITE, radius, true, dp(1).toFloat()) else null
            view.animate().scaleX(if (focused) theme.layout.focusScale else 1f).scaleY(if (focused) theme.layout.focusScale else 1f).setDuration(130).start()
            if (focused) scheduleFeature(item)
        }
        frame.setOnClickListener { action() }
        return frame.pressable(6)
    }

    /**
     * Badges come from the saved history at once; the service is asked for its cheap change stamp, and the full
     * history is downloaded only when that moved (both services ask apps not to refetch whole libraries).
     */
    private fun refreshWatched(force: Boolean = false) {
        watchedCheckedAt = android.os.SystemClock.elapsedRealtime()
        val tracker = wetrakr; val service = trackerId()
        if (!tracker.connected()) { if (watched != WatchedState.EMPTY) { watched = WatchedState.EMPTY; refreshBadges() }; return }
        val revision = tracker.accountRevision()
        val saved = watchedCache.load(service, revision)
        if (saved != null && saved.state != watched) { watched = saved.state; refreshBadges() }
        if (watchedLoading) return
        watchedLoading = true
        worker.execute {
            try {
                val stamp = tracker.watchedStamp()
                if (!force && saved != null && saved.stamp == stamp) return@execute
                val state = tracker.watched()
                watchedCache.save(WatchedCache.Entry(service, revision, stamp, state))
                runOnUiThread { if (!isDestroyed && trackerId() == service && wetrakr.accountRevision() == revision) { watched = state; refreshBadges() } }
            } catch (_: Exception) { /* Badges are a convenience; the next check retries. */ } finally { watchedLoading = false }
        }
    }

    /**
     * After the player closes, Continue Watching is read again: once the pause or stop has had a moment to reach
     * the tracker, and once more in case the service is slow to record it. The row updates in place.
     */
    private fun scheduleContinueRefresh() {
        if (publicPreview || !::body.isInitialized) return
        if (!wetrakr.connected()) { body.post { refreshLocalContinue() }; return }
        listOf(2_500L, 9_000L).forEach { delay -> body.postDelayed({ if (!isDestroyed) refreshContinue?.invoke() }, delay) }
    }

    /**
     * Local progress (no tracker): the row on screen is refilled in place; otherwise Home is rebuilt, so a first
     * Continue Watching row appears, or an emptied one goes, without restarting the app.
     */
    private fun refreshLocalContinue() {
        if (isDestroyed || !isHome || publicPreview || wetrakr.connected() || homeRevision == pageRevision()) return
        val row = localContinueRow
        if (row != null && row.isAttachedToWindow && history.continueWatching().isNotEmpty()) {
            if (row.hasFocus()) return
            refreshContinue?.invoke(); homeRevision = pageRevision()
        } else home()
    }

    /** A manual mark shows its badge straight away; the next service check confirms it. */
    private fun markedWatched(identity: PlaybackIdentity) { watched = watched.with(identity); refreshBadges() }

    private fun watchedKey(key: String): Boolean {
        val parts = key.split(':')
        return when (parts.firstOrNull()) {
            "movie" -> watched.movie(parts.getOrElse(1) { "" })
            "series" -> watched.show(parts.getOrElse(1) { "" })
            "episode" -> parts.size == 4 && watched.episode(parts[1], parts[2].toIntOrNull() ?: -1, parts[3].toIntOrNull() ?: -1)
            else -> false
        }
    }

    /** A small ✓ on artwork, shown when the linked tracker says it was watched. Untrackable titles get none. */
    private fun watchedBadge(kind: String, imdb: String, season: Int = 0, episode: Int = 0): View? {
        if (!imdb.matches(Regex("tt\\d+")) || publicPreview) return null
        val key = if (kind == "episode") "episode:$imdb:$season:$episode" else "$kind:$imdb"
        return text("✓", 12, Color.BLACK, true).apply {
            gravity = Gravity.CENTER; tag = "watched-badge:$key"; contentDescription = "Watched"; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE); setStroke(dp(1), 0x33000000) }
            elevation = dp(2).toFloat(); visibility = if (watchedKey(key)) View.VISIBLE else View.GONE
        }
    }

    /** Updates every badge on screen and in the back stack after the history changes. */
    private fun refreshBadges() {
        if (!::body.isInitialized) return
        fun visit(view: View) {
            (view.tag as? String)?.takeIf { it.startsWith("watched-badge:") }?.let { tag ->
                view.visibility = if (watchedKey(tag.removePrefix("watched-badge:"))) View.VISIBLE else View.GONE
            }
            if (view is ViewGroup) for (index in 0 until view.childCount) visit(view.getChildAt(index))
        }
        retainedRoots().forEach(::visit)
    }

    private fun cardWidth(): Int {
        if (flix) return theme.layout.cardWidthDp
        val visible = 5
        val available = DeviceLayout.canvasWidth - theme.layout.railWidthDp - theme.layout.contentInsetDp * 2 - 13
        return minOf(theme.layout.cardWidthDp, (available - (visible - 1) * theme.layout.cardGapDp) / visible)
    }

    private fun section(title: String) {
        body.addView(text(title, theme.layout.sectionTitleSizeSp, ink, true), margin(-1, -2, top = if (isHome && body.childCount == 1) 3 else 11, bottom = 7))
    }
    private fun mediaRow(title: String, items: List<MediaItemData>, host: LinearLayout = body, seeAll: (() -> Unit)? = null) {
        if (items.isEmpty()) return
        if (flix) {
            // One press per shelf: the title is a label and "See all" closes the row.
            host.addView(text(title, theme.layout.sectionTitleSizeSp, ink, true), margin(-1, -2, top = 14, bottom = 6))
            val row = horizontalRow(host)
            if (title.contains("watchlist", ignoreCase = true)) row.setTag(R.id.pov_list_scope, "watchlist:${items.first().type}")
            items.take(20).forEach { row.addView(card(it).apply { tag = "row:$title:${it.type}:${it.imdbId}" }, margin(cardWidth(), theme.layout.cardHeightDp, right = theme.layout.cardGapDp)) }
            row.addView(text("See all  ›", 13, ink, true).apply {
                gravity = Gravity.CENTER; tag = "shelf:$title"; contentDescription = "See all $title"
                isFocusable = true; isFocusableInTouchMode = DeviceLayout.tv; isClickable = true; background = rounded(withAlpha(Color.WHITE, 22), theme.layout.cornerRadiusDp)
                setOnFocusChangeListener { view, focused ->
                    view.background = rounded(if (focused) Color.WHITE else withAlpha(Color.WHITE, 22), theme.layout.cornerRadiusDp)
                    setTextColor(if (focused) Color.BLACK else ink)
                }
                setOnClickListener { seeAll?.invoke() ?: navigate { shelfGrid(title, items) } }
            }, margin(110, theme.layout.cardHeightDp))
            return
        }
        host.addView(button("$title  ›") {
            navigate { shelfGrid(title, items) }
        }.apply { tag = "shelf:$title" }, margin(-1, 40, top = 10, bottom = 7))
        val row = horizontalRow(host)
        if (title.contains("watchlist", ignoreCase = true)) row.setTag(R.id.pov_list_scope, "watchlist:${items.first().type}")
        items.take(20).forEach { row.addView(card(it).apply { tag = "row:$title:${it.type}:${it.imdbId}" }, margin(cardWidth(), theme.layout.cardHeightDp, right = theme.layout.cardGapDp)) }
    }
    private fun removeFromListViews(scope: String, identity: PlaybackIdentity) {
        fun visit(root: View, inside: Boolean = false) {
            val selected = inside || root.getTag(R.id.pov_list_scope) == scope
            val tag = root.tag as? String
            if (selected && (tag == "media:${identity.type}:${identity.imdbId}" || tag?.endsWith(":${identity.type}:${identity.imdbId}") == true)) root.visibility = View.GONE
            if (root is ViewGroup) (0 until root.childCount).map(root::getChildAt).forEach { visit(it, selected) }
        }
        retainedRoots().forEach { visit(it) }
    }
    private fun personalListGrid(listId: String, title: String) {
        current = { personalListGrid(listId, title) }; val id = page(title, "Your ${wetrakr.name} playlist")
        val host = column().apply { setTag(R.id.pov_list_scope, "playlist:$listId") }; body.addView(host)
        read(id, { wetrakr.listItems(listId) }) { mediaGrid(it, host = host) }
    }
    private fun shelfGrid(title: String, items: List<MediaItemData>) {
        current = { shelfGrid(title, items) }; val id = page(title)
        val host = column().apply { if (title.contains("watchlist", ignoreCase = true) && items.isNotEmpty()) setTag(R.id.pov_list_scope, "watchlist:${items.first().type}") }
        body.addView(host); mediaGrid(items, host = host); focusAfterRender(id)
    }
    private fun mediaGrid(items: List<MediaItemData>, showType: Boolean = false, host: LinearLayout = body) {
        if (items.isEmpty()) { host.addView(text("No titles found. Try a different search.", 14, muted)); return }
        val width = DeviceLayout.canvasWidth - theme.layout.railWidthDp - theme.layout.contentInsetDp * 2
        val count = if (flix) (if (DeviceLayout.phone) DeviceLayout.phoneColumns(width) else 4) else (width / (cardWidth() + theme.layout.cardGapDp)).coerceAtLeast(1)
        // Streaming grids stretch their columns to the full content width.
        val gridWidth = if (flix) (width - 6 - (count - 1) * theme.layout.cardGapDp) / count else cardWidth()
        val gridHeight = if (flix) gridWidth * 9 / 16 else theme.layout.cardHeightDp
        items.chunked(count).forEach { chunk ->
            val row = LinearLayout(this).apply { tag = "media-grid-row" }; host.addView(row, margin(-1, -2, top = 14))
            chunk.forEach { item ->
                val view = if (showType) tile(item, caption = listOf(if (item.type == "movie") "Movie" else "TV show", item.year).filter { it.isNotBlank() }.joinToString(" · ")) { navigate { details(item) } } else card(item)
                row.addView(view, margin(gridWidth, gridHeight, right = theme.layout.cardGapDp))
            }
        }
    }
    private fun card(item: MediaItemData): View = tile(item) { navigate { details(item) } }

    /** Phones have no focus highlight, so tappable surfaces show a ripple instead. */
    private fun <T : View> T.pressable(radius: Int): T = apply {
        if (DeviceLayout.phone) foreground = android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(0x40FFFFFF), null, rounded(Color.WHITE, radius))
    }

    private fun tile(item: MediaItemData, progress: Double? = null, caption: String = "", action: () -> Unit): View {
        val shape = theme.layout.cards
        val frame = FrameLayout(this).apply {
            background = rounded(panel, theme.layout.cornerRadiusDp); isFocusable = true; isFocusableInTouchMode = DeviceLayout.tv; isClickable = true
            contentDescription = listOf(item.title, item.year, caption).filter(String::isNotBlank).joinToString(", ")
            tag = "media:${item.type}:${item.imdbId.ifBlank { item.title }}"
        }
        val artFrame = FrameLayout(this).apply { background = rounded(panel, theme.layout.cornerRadiusDp); clipToOutline = true }
        val art = FixedSizeImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        artFrame.addView(art, FrameLayout.LayoutParams(-1, -1))
        val fallback = if (item.imdbId.matches(Regex("tt\\d+"))) "https://images.metahub.space/${if (shape == CardFormat.POSTER) "poster" else "background"}/medium/${item.imdbId}/img" else ""
        val wideArt = shape != CardFormat.POSTER && item.background.isNotBlank()
        image(art, thumbnail((if (shape == CardFormat.POSTER) item.poster else item.background).ifBlank { fallback.ifBlank { item.poster } }, wide = wideArt || (shape != CardFormat.POSTER && fallback.isNotBlank())))
        artFrame.addView(View(this).apply { background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
            intArrayOf(0xE8000000.toInt(), 0x08000000, Color.TRANSPARENT)) }, FrameLayout.LayoutParams(-1, -1))
        val labels = column().apply { setPadding(dp(10), dp(5), dp(9), dp(if (progress != null) 17 else 9)) }
        val name = text(item.title, 12, Color.WHITE, true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        if (flix && logoFor(item).isNotBlank()) {
            // Title artwork replaces the plain caption once it loads; both share one slot so the swap never re-lays out the row.
            val slot = FrameLayout(this).apply { minimumHeight = dp(30) }
            val logo = FixedSizeImageView(this).apply { scaleType = ImageView.ScaleType.FIT_START; visibility = View.INVISIBLE }
            slot.addView(logo, FrameLayout.LayoutParams(-1, dp(30), Gravity.BOTTOM))
            slot.addView(name, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
            labels.addView(slot, margin(-1, -2))
            // A card's logo is drawn about 60 px tall, so Metahub's 300 px logo is plenty at a quarter of the bytes.
            image(logo, logoFor(item).replace("/logo/medium/", "/logo/small/")) { loaded -> if (loaded) { logo.visibility = View.VISIBLE; name.visibility = View.INVISIBLE } }
        } else labels.addView(name)
        val secondary = caption.ifBlank { item.year }
        if (secondary.isNotBlank() && (caption.isNotBlank() || shape == CardFormat.LANDSCAPE)) labels.addView(text(secondary, 10, 0xFFCFD8E4.toInt()), margin(-1, -2, top = 2))
        artFrame.addView(labels, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        frame.addView(artFrame, FrameLayout.LayoutParams(-1, -1))
        watchedBadge(if (item.type == "movie") "movie" else "series", item.imdbId)?.let {
            frame.addView(it, FrameLayout.LayoutParams(dp(20), dp(20), Gravity.TOP or Gravity.END).apply { topMargin = dp(6); rightMargin = dp(6) })
        }
        if (progress != null) {
            val track = FrameLayout(this).apply { background = rounded(0xFF34424D.toInt(), 3) }
            track.addView(View(this).apply { background = rounded(theme.palette.progress, 3) }, FrameLayout.LayoutParams(0, -1))
            frame.addView(track, FrameLayout.LayoutParams(-1, dp(4), Gravity.BOTTOM).apply { leftMargin = dp(9); rightMargin = dp(9); bottomMargin = dp(6) })
            track.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                val targetWidth = (track.width * progress.coerceIn(0.0, 1.0)).roundToInt()
                val fill = track.getChildAt(0)
                if (fill.layoutParams.width != targetWidth) fill.layoutParams = fill.layoutParams.apply { width = targetWidth }
            }
        }
        frame.foreground = if (flix) null else FocusRingDrawable(theme.palette.border, dp(theme.layout.cornerRadiusDp).toFloat(), false, dp(1).toFloat())
        frame.setOnFocusChangeListener { view, focused ->
            // In a sideways row one white outline glides from poster to poster; elsewhere the outline cross-fades.
            val glide = if (flix) posterGlide(view) else null
            if (glide != null) glide.focusChanged(view, focused)
            else if (flix) FadeDrawable.foreground(view, if (focused) FocusRingDrawable(Color.WHITE, dp(theme.layout.cornerRadiusDp).toFloat(), true, dp(1).toFloat()) else null)
            else view.foreground = FocusRingDrawable(if (focused) accent else theme.palette.border, dp(theme.layout.cornerRadiusDp).toFloat(), focused, dp(1).toFloat())
            view.animate().scaleX(if (focused) theme.layout.focusScale else 1f).scaleY(if (focused) theme.layout.focusScale else 1f).setDuration(130).start()
            if (focused) scheduleFeature(item)
        }
        frame.setOnClickListener { action() }
        // Holding OK gives the same choices everywhere: play, pick a stream by hand, details, mark watched.
        frame.setOnLongClickListener { titleMenu(item, action); true }
        return frame.pressable(theme.layout.cornerRadiusDp)
    }

    private val listGlides = java.util.WeakHashMap<ViewGroup, SlidingFocusPill>()
    /** The gliding outline of the sideways poster row holding [tile] (Home and catalog rows), or null outside one. */
    private fun posterGlide(tile: View, radius: Int = theme.layout.cornerRadiusDp, scale: Float = theme.layout.focusScale,
                            part: ((View) -> View?)? = null): SlidingFocusPill? {
        val row = tile.parent as? ViewGroup ?: return null
        if (row.parent !is HorizontalScrollView) return null
        return listGlides.getOrPut(row) {
            row.clipChildren = false; row.clipToPadding = false
            SlidingFocusPill(row, fill = Color.WHITE, radius = dp(radius).toFloat(), fadeText = false,
                ring = maxOf(2f, dp(1) * 1.5f), scale = scale, part = part)
        }
    }
    /** The gliding highlight of the list holding [row] (episodes), created the first time one of its rows takes focus. */
    private fun listGlide(row: View): SlidingFocusPill? {
        val list = row.parent as? ViewGroup ?: return null
        return listGlides.getOrPut(list) { SlidingFocusPill(list, fill = withAlpha(Color.WHITE, 40), radius = dp(6).toFloat(), fadeText = false) }
    }

    /** One hold-OK menu for a title anywhere (rows, search, anime): [open] is what a normal press does. */
    private fun titleMenu(item: MediaItemData, open: () -> Unit) {
        val options = mutableListOf<Pair<String, () -> Unit>>()
        val saved = savedFor(item)?.identity
        val playable = item.imdbId.matches(Regex("tt\\d+"))
        when {
            playable && item.type == "movie" -> {
                val target = saved ?: PlaybackIdentity(item.imdbId, item.type, item.title)
                options += "▶  Play" to { navigate { showSources(target) } }
                options += "☰  Streams" to { navigate { showSources(target, manual = true) } }
            }
            playable && saved != null && saved.type == "series" -> {
                val number = "S${saved.season} E${saved.episode}"
                options += "▶  Play $number" to { navigate { showSources(saved) } }
                options += "☰  Streams · $number" to { navigate { showSources(saved, manual = true) } }
            }
        }
        options += "ⓘ  More Info" to open
        val tracker = weTrakrClient()
        if (playable && item.type == "movie" && tracker.connected()) {
            val identity = PlaybackIdentity(item.imdbId, item.type, item.title)
            options += "Mark as watched" to { load(generation, { tracker.markWatched(identity) }) { markedWatched(identity); toast("${item.title} marked watched") } }
        }
        holdMenu(item.title, options)
    }

    /** One hold-OK menu for an episode: play, pick a stream by hand, mark watched. */
    private fun episodeMenu(identity: PlaybackIdentity, number: String) {
        val tracker = weTrakrClient()
        val options = mutableListOf<Pair<String, () -> Unit>>(
            "▶  Play" to { navigate { showSources(identity) } },
            "☰  Streams" to { navigate { showSources(identity, manual = true) } })
        if (tracker.connected()) options += "Mark as watched" to {
            load(generation, { tracker.markWatched(identity) }) { markedWatched(identity); toast("Episode marked watched") }
        }
        holdMenu("${identity.title} · $number", options)
    }

    private fun holdMenu(title: String, options: List<Pair<String, () -> Unit>>) { HoldMenu.show(this, title, options) }

    private fun image(view: ImageView, address: String, keep: Boolean = false, feature: Boolean = false, loaded: ((Boolean) -> Unit)? = null) {
        if (!address.startsWith("https://")) { loaded?.invoke(false); return }
        val backdrop = flix && view === backdropArt
        // The backdrop is never blanked: the current picture stays until the next one is ready.
        if (view.tag != address && !keep && !backdrop) view.setImageDrawable(null)
        view.tag = address
        // Home's corner backdrop only needs soft edges; full-screen (details) art needs heavier shading under its text.
        val corner = backdrop && heroBackdrop.layoutParams.width > 0
        val key = if (corner) "backdrop-corner:$address" else if (backdrop) "backdrop:$address" else address
        fun show(bitmap: Bitmap) {
            val previous = (view.drawable as? android.graphics.drawable.TransitionDrawable)?.let { it.getDrawable(it.numberOfLayers - 1) } ?: view.drawable
            if (backdrop && feature && previous != null) {
                // Home's backdrop: the old picture fades out while the new one fades in, quickly.
                val fade = android.graphics.drawable.TransitionDrawable(arrayOf(previous, android.graphics.drawable.BitmapDrawable(resources, bitmap)))
                    .apply { isCrossFadeEnabled = true }
                view.setImageDrawable(fade)
                fade.startTransition(BACKDROP_FADE_MS)
            } else view.setImageBitmap(bitmap)
            if (backdrop) {
                view.setTag(R.id.pov_backdrop_key, key)
                // The details art fades up from the dimmed Home version instead of switching at once.
                if (view.alpha < 1f) view.animate().alpha(1f).setDuration(300).setInterpolator(EASE_OUT).start() else view.alpha = 1f
            }
        }
        bitmapCache.get(key)?.let { show(it); loaded?.invoke(true); return }
        // Home's corner art has lighter shading than a details page needs; dim it until the right version is ready.
        if (backdrop && !corner && (view.getTag(R.id.pov_backdrop_key) as? String)?.startsWith("backdrop-corner:") == true) view.alpha = 0.4f
        // Decode only as large as the slot: cards ~200dp, feature art ~360dp, the backdrop at 1280px.
        val target = when { backdrop -> BACKDROP_WIDTH; feature || view.width >= dp(350) -> dp(360); else -> dp(200) }
        val load = Runnable {
            try {
                if (view.tag != address) return@Runnable
                bitmapCache.get(key)?.let { cached -> runOnUiThread { if (!isDestroyed && view.tag == address) { show(cached); loaded?.invoke(true) } }; return@Runnable }
                val bitmap = fetchBitmap(address, target)?.let { if (backdrop) shadedBackdrop(it, corner) else it }
                if (bitmap != null) { bitmapCache.put(key, bitmap); runOnUiThread { if (!isDestroyed && view.tag == address) { show(bitmap); loaded?.invoke(true) } } }
                else if (loaded != null) runOnUiThread { if (!isDestroyed && view.tag == address) loaded(false) }
            } catch (_: Exception) {
                /* The title remains usable when artwork is unavailable. */
                if (loaded != null) runOnUiThread { if (!isDestroyed && view.tag == address) loaded(false) }
            }
        }
        if (feature || backdrop) featureImages.execute(load) else images.execute(ImageTask(generation, imageOrder++, load))
    }

    private fun fetchBitmap(address: String, targetWidth: Int): Bitmap? {
        val bytes = ArtworkClient.bytes(address)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetWidth) sample *= 2
        // Opaque photos decode at 16 bits per pixel: half the memory and half the texture upload of a card.
        // (Hardware bitmaps would avoid the upload, but this TV's GPU cannot draw them inside rounded clips.)
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
            inSampleSize = sample; if (bounds.outMimeType == "image/jpeg") inPreferredConfig = Bitmap.Config.RGB_565
        })
        // Starts the texture upload on a render worker so the first draw does not stall a scroll frame.
        bitmap?.prepareToDraw()
        return bitmap
    }

    /** Bakes the flix backdrop's left and bottom shading into the artwork: one full-screen layer instead of three. */
    private fun shadedBackdrop(source: Bitmap, corner: Boolean = false): Bitmap {
        val width = BACKDROP_WIDTH; val height = BACKDROP_WIDTH * 9 / 16
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(out)
        canvas.drawColor(bg)
        val scale = maxOf(width.toFloat() / source.width, height.toFloat() / source.height)
        val drawnWidth = source.width * scale; val drawnHeight = source.height * scale
        canvas.drawBitmap(source, null, android.graphics.RectF((width - drawnWidth) / 2, (height - drawnHeight) / 2,
            (width + drawnWidth) / 2, (height + drawnHeight) / 2), android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
        val shade = android.graphics.Paint()
        // The phone banner has its copy below it, so only its foot fades into the page.
        if (DeviceLayout.tv) shade.shader = android.graphics.LinearGradient(0f, 0f, width.toFloat(), 0f,
            if (corner) intArrayOf(bg, withAlpha(bg, 120), withAlpha(bg, 0), withAlpha(bg, 0), withAlpha(bg, 0))
            else intArrayOf(withAlpha(bg, 250), withAlpha(bg, 170), withAlpha(bg, 0), withAlpha(bg, 0)), null, android.graphics.Shader.TileMode.CLAMP)
        if (DeviceLayout.tv) canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), shade)
        shade.shader = android.graphics.LinearGradient(0f, height.toFloat(), 0f, 0f,
            if (corner) intArrayOf(bg, withAlpha(bg, 70), withAlpha(bg, 0), withAlpha(bg, 0))
            else if (DeviceLayout.phone) intArrayOf(bg, withAlpha(bg, 150), withAlpha(bg, 0), withAlpha(bg, 0))
            else intArrayOf(bg, withAlpha(bg, 215), withAlpha(bg, 0)), null, android.graphics.Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), shade)
        source.recycle()
        return out.copy(Bitmap.Config.HARDWARE, false)?.also { out.recycle() } ?: out
    }

    /** Thumbnails come from the image service's small renditions (480 px) instead of 1920 px originals. */
    /**
     * Card artwork at the size a card needs. Catalog add-ons (AIOMetadata and others) often link full-size images, so
     * hosts with smaller versions are asked for those: TMDB (780 px wide cards, 500 px posters; never upsized),
     * Metahub's small size. Images from other hosts, e.g. rating posters, stay as they are.
     */
    private fun thumbnail(address: String, wide: Boolean = true): String = when {
        address.startsWith("https://images.metahub.space/") -> address.replace(Regex("/(background|poster|logo)/(medium|large)/"), "/$1/small/")
        address.startsWith("https://image.tmdb.org/t/p/") -> {
            val size = address.removePrefix("https://image.tmdb.org/t/p/").substringBefore('/')
            val width = if (size == "original") Int.MAX_VALUE else size.removePrefix("w").toIntOrNull()
            val target = if (wide) 780 else 500
            if (width != null && width > target) address.replaceFirst("/t/p/$size/", "/t/p/w$target/") else address
        }
        else -> address
    }

    /** Warms the cache so the next rotating feature appears without a wait. */
    private fun prefetch(address: String, backdrop: Boolean = false) {
        val corner = backdrop && flix && heroBackdrop.layoutParams.width > 0
        val key = if (corner) "backdrop-corner:$address" else if (backdrop && flix) "backdrop:$address" else address
        if (!address.startsWith("https://") || bitmapCache.get(key) != null) return
        featureImages.execute {
            try {
                val shaded = backdrop && flix
                fetchBitmap(address, if (backdrop) BACKDROP_WIDTH else dp(360))?.let { bitmapCache.put(key, if (shaded) shadedBackdrop(it, corner) else it) }
            }
            catch (_: Exception) { }
        }
    }

    /** True when [view] is one of the settings under the Settings section cards. */
    private fun inSettingsContent(view: View?): Boolean {
        val content = settingsContent ?: return false
        var current: android.view.ViewParent? = view?.parent
        while (current != null) { if (current === content) return true; current = current.parent }
        return false
    }

    /** True when [view] is (inside) the last focusable item of a sideways row such as a Home shelf. */
    private fun atRowEnd(view: View?): Boolean {
        // Only within the page, and only a sideways shelf: walking past the page found the app's outer layout and
        // swallowed Right on pages such as Debrid services.
        var child = view ?: return false
        var parent = child.parent
        while (parent is View && parent !== body && !(parent is CullingLinearLayout && parent.orientation == LinearLayout.HORIZONTAL)) {
            child = parent; parent = parent.parent
        }
        // The page itself is a vertical CullingLinearLayout: only a horizontal one is a shelf.
        val row = (parent as? CullingLinearLayout)?.takeIf { it.orientation == LinearLayout.HORIZONTAL } ?: return false
        val last = (row.childCount - 1 downTo 0).firstOrNull { index -> row.getChildAt(index).let { it.isShown && (it.isFocusable || it.hasFocusable()) } }
        return last != null && row.indexOfChild(child) == last
    }

    private fun horizontalRow(host: LinearLayout = body): LinearLayout {
        val scroller = TvHorizontalScrollView(this) { !(flix && isHome) }.apply { isHorizontalScrollBarEnabled = false; clipChildren = false; clipToPadding = false; isFocusable = false; isFocusableInTouchMode = false; descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS }
        val row = CullingLinearLayout(this, resources.displayMetrics.widthPixels / 2).apply { setPadding(dp(3), dp(4), dp(10), dp(4)); clipChildren = false; clipToPadding = false }
        scroller.setOnScrollChangeListener { _, _, _, _, _ -> row.invalidate() }
        // Titles scrolled past slide out at the content edge instead of beneath the menu.
        if (flix) { scroller.clipChildren = true; scroller.clipToPadding = true; row.setPadding(dp(9), dp(7), dp(10), dp(7)) }
        scroller.addView(row); host.addView(scroller, margin(-1, -2).apply { if (flix) leftMargin = -dp(9) }); return row
    }
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    /** Phone tab strip: equal-width buttons across the full width. */
    private fun phoneTabs(tabs: List<Pair<String, () -> Unit>>) {
        val row = LinearLayout(this)
        tabs.forEachIndexed { index, (label, action) ->
            row.addView(button(label) { action() }.apply { gravity = Gravity.CENTER; maxLines = 1 },
                LinearLayout.LayoutParams(0, dp(40), 1f).apply { if (index < tabs.lastIndex) rightMargin = dp(8) })
        }
        body.addView(row, margin(-1, -2, bottom = 4))
    }
    private fun text(value: String, size: Int, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value; setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(size).toFloat()); setTextColor(color)
        typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL); includeFontPadding = false
    }
    private fun button(label: String, action: () -> Unit): TextView = text(label, 13, ink, true).apply {
        gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(5), dp(12), dp(5)); background = rounded(panel, 7)
        isFocusable = true; isFocusableInTouchMode = DeviceLayout.tv; isClickable = true; contentDescription = label; tag = "action:$label"
        if (flix) {
            // Translucent until focused, then solid white with dark text.
            background = rounded(withAlpha(0xFF6D6D6E.toInt(), 150), 4)
            setOnFocusChangeListener { view, focus ->
                view.background = rounded(if (focus) Color.WHITE else withAlpha(0xFF6D6D6E.toInt(), 150), 4)
                setTextColor(if (focus) Color.BLACK else ink)
                view.animate().scaleX(if (focus) 1.05f else 1f).scaleY(if (focus) 1.05f else 1f).setDuration(120).start()
            }
            setOnClickListener { action() }
            pressable(4)
            return@apply
        }
        setOnFocusChangeListener { view, focus ->
            view.background = rounded(if (focus) accent else panel, 7)
            view.foreground = if (focus) FocusRingDrawable(accent, dp(7).toFloat(), true, dp(1).toFloat()) else null
            setTextColor(if (focus) Color.rgb(4, 15, 24) else ink)
        }
        setOnClickListener { action() }
    }
    private fun primaryButton(label: String, action: () -> Unit): TextView = text(label, 15, theme.palette.buttonText, true).apply {
        tag = "primary:$label"
        gravity = Gravity.CENTER; isFocusable = true; isFocusableInTouchMode = DeviceLayout.tv; isClickable = true
        if (flix) {
            background = rounded(withAlpha(Color.WHITE, 225), 4)
            setOnFocusChangeListener { view, focused ->
                view.background = rounded(if (focused) Color.WHITE else withAlpha(Color.WHITE, 225), 4)
                view.foreground = if (focused) FocusRingDrawable(Color.WHITE, dp(4).toFloat(), true, dp(1).toFloat()) else null
                view.animate().scaleX(if (focused) 1.06f else 1f).scaleY(if (focused) 1.06f else 1f).setDuration(120).start()
            }
            setOnClickListener { action() }
            pressable(4)
            return@apply
        }
        background = rounded(theme.palette.primaryButton, 6)
        setOnFocusChangeListener { view, focused ->
            view.foreground = if (focused) FocusRingDrawable(accent,
                dp(6).toFloat(), true, dp(1).toFloat()) else null
        }
        setOnClickListener { action() }
    }
    /** A sharp 2dp white outline: Flix's focus mark where a soft glow or zoom would blur text and video. */
    private fun crispOutline(radius: Int) = GradientDrawable().apply { setColor(Color.TRANSPARENT); cornerRadius = dp(radius).toFloat(); setStroke(dp(2), Color.WHITE) }
    private fun rounded(color: Int, radius: Int, stroke: Int = Color.TRANSPARENT) = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; setColor(color); cornerRadius = dp(radius).toFloat(); if (stroke != Color.TRANSPARENT) setStroke(dp(2), stroke) }
    private fun withAlpha(color: Int, alpha: Int) = Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
    private fun margin(width: Int, height: Int, top: Int = 0, bottom: Int = 0, right: Int = 0) = LinearLayout.LayoutParams(if (width < 0) width else dp(width), if (height < 0) height else dp(height)).apply { setMargins(0, dp(top), dp(right), dp(bottom)) }
    private fun dp(value: Int) = (value * resources.displayMetrics.widthPixels / DeviceLayout.canvasWidth.toFloat()).roundToInt()
    private var notice: TextView? = null
    private val hideNotice = Runnable { notice?.animate()?.alpha(0f)?.translationY(dp(12).toFloat())?.setDuration(220)?.withEndAction { notice?.visibility = View.GONE }?.start() }
    /** A short in-app notice styled like the app's dialogs, instead of the system toast. */
    private fun toast(message: String) {
        if (!::shellRoot.isInitialized || isDestroyed) { Toast.makeText(this, message, Toast.LENGTH_LONG).show(); return }
        val view = notice?.takeIf { it.parent === shellRoot } ?: text("", 14, Color.WHITE, true).apply {
            gravity = Gravity.CENTER; setPadding(dp(18), dp(11), dp(18), dp(11)); maxLines = 3; elevation = dp(6).toFloat()
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xF22A2A30.toInt(), 0xF217171A.toInt())).apply {
                cornerRadius = dp(12).toFloat(); setStroke(dp(1), 0x2EFFFFFF)
            }
            shellRoot.addView(this, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(if (DeviceLayout.phone) BOTTOM_BAR_HEIGHT + 16 else 28) })
            notice = this
        }
        view.text = message; view.bringToFront(); view.visibility = View.VISIBLE
        view.alpha = 0f; view.translationY = dp(12).toFloat()
        view.animate().alpha(1f).translationY(0f).setDuration(200).start()
        view.removeCallbacks(hideNotice); view.postDelayed(hideNotice, 3_500)
    }
    /**
     * A long synopsis in its own capped, scrollable area: a plain dialog message would grow past the screen
     * and squeeze the Close button into a second scrolling strip.
     */
    private fun synopsisDialog(title: String, synopsis: String) {
        val density = resources.displayMetrics.density
        val body = TextView(this).apply {
            text = synopsis; textSize = 16f; setTextColor(0xFFE6E6E6.toInt()); setLineSpacing(0f, 1.15f)
            setPadding((24 * density).toInt(), (4 * density).toInt(), (24 * density).toInt(), (8 * density).toInt())
        }
        val cap = (resources.displayMetrics.heightPixels * 0.6f).roundToInt()
        // Never taller than 60% of the screen. Focusable so the remote's Up/Down scrolls the text.
        val scroll = object : ScrollView(this) {
            override fun onMeasure(widthSpec: Int, heightSpec: Int) =
                super.onMeasure(widthSpec, View.MeasureSpec.makeMeasureSpec(cap, View.MeasureSpec.AT_MOST))
        }.apply { isFocusable = true; addView(body) }
        AlertDialog.Builder(this).setTitle(title).setView(scroll).setPositiveButton("Close", null).show()
    }

    private fun confirm(message: String, action: () -> Unit)
 { AlertDialog.Builder(this).setMessage(message).setPositiveButton("Continue") { _, _ -> action() }.setNegativeButton("Cancel", null).show() }
}

/**
 * A scroll area that never grows past [maxHeight], nor past what the on-screen keyboard leaves free, so a dialog's
 * title and buttons stay in view above a long form and above the keyboard.
 */
private class CappedScroll(context: android.content.Context, private val maxHeight: Int, private val reserve: Int = 0,
                           /** Shrink to what the on-screen keyboard leaves free (phones); off, only [maxHeight] applies. */
                           private val followKeyboard: Boolean = true) : android.widget.ScrollView(context) {
    private val frame = android.graphics.Rect()
    private var lastVisible = 0
    /** Room the dialog's title and button row need besides this form. */
    private val chrome = (190 * resources.displayMetrics.density).toInt()
    private val minimum = (90 * resources.displayMetrics.density).toInt()
    // The keyboard opening or closing changes the free space: measure again.
    private val watcher = android.view.ViewTreeObserver.OnGlobalLayoutListener {
        getWindowVisibleDisplayFrame(frame)
        if (frame.height() != lastVisible) { lastVisible = frame.height(); requestLayout() }
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); viewTreeObserver.addOnGlobalLayoutListener(watcher) }
    override fun onDetachedFromWindow() { viewTreeObserver.removeOnGlobalLayoutListener(watcher); super.onDetachedFromWindow() }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        getWindowVisibleDisplayFrame(frame)
        val free = if (followKeyboard && frame.height() > 0) frame.height() - chrome - reserve else maxHeight
        super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(minOf(maxHeight, free).coerceAtLeast(minimum), View.MeasureSpec.AT_MOST))
    }
}
