package dev.pov.tv

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.app.Activity
import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.Window
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.pov.tv.data.EpisodeData
import dev.pov.tv.data.ImdbRatings
import dev.pov.tv.data.MediaItemData
import dev.pov.tv.data.PlaybackIdentity
import dev.pov.tv.data.TmdbCatalog
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * The player's two full-screen panels over the playing video:
 *  - Info slides up from the bottom: title logo, "S01E03 - Title", runtime · air date, synopsis and the episode's own
 *    cast and crew. Up or Back slides it down again.
 *  - Episodes: the seasons on the left, the chosen season's episodes as large cards on the right, starting on the
 *    playing one. OK on another episode closes the player and plays that episode (links searched as for Next episode).
 */
internal object PlayerPanels {
    private const val INK = Color.WHITE
    private const val MUTED = 0xFFB8C0CC.toInt()
    private const val DIM = 0xFF8A919C.toInt()
    private const val STAR = 0xFFF5C518.toInt()
    private const val CARD = 0xFF24262B.toInt()
    private const val OPEN_MS = 260L
    private const val CLOSE_MS = 200L
    private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US)

    private fun logoUrl(imdbId: String) = "https://images.metahub.space/logo/medium/$imdbId/img"

    /** Shared plumbing: a full-screen transparent dialog whose content slides up on open and down on close. */
    private class Panel(val activity: Activity) {
        val dialog = Dialog(activity)
        val density = activity.resources.displayMetrics.density
        val content = FrameLayout(activity).apply { setBackgroundColor(0xF2000000.toInt()) }
        val images: ExecutorService = Executors.newFixedThreadPool(3)
        @Volatile var closed = false
        private var closing = false

        fun dp(value: Int) = (value * density).roundToInt()

        fun text(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(activity).apply {
            text = value; setTextColor(color); setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
            includeFontPadding = false
        }

        fun rounded(color: Int, radius: Int, stroke: Int = 0) = GradientDrawable().apply {
            setColor(color); cornerRadius = dp(radius).toFloat(); if (stroke != 0) setStroke(dp(2), stroke)
        }

        /** Loads [address] off the main thread, scaled down near [width] px, then hands it over if still open. */
        fun image(address: String, width: Int, done: (Bitmap) -> Unit) {
            if (address.isBlank()) return
            images.execute {
                if (closed) return@execute
                try {
                    ArtworkClient.init(activity.applicationContext)
                    val bytes = ArtworkClient.bytes(address)
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    var sample = 1
                    while (bounds.outWidth / (sample * 2) >= width) sample *= 2
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                    if (bitmap != null) activity.runOnUiThread { if (!closed) done(bitmap) }
                } catch (_: Exception) { /* No picture: the placeholder stays. */ }
            }
        }

        fun worker(work: () -> Unit) = Thread { if (!closed) work() }.apply { isDaemon = true }.start()
        fun ui(work: () -> Unit) = activity.runOnUiThread { if (!closed) work() }

        fun close() {
            if (closing) return
            closing = true
            content.animate().translationY(content.height.toFloat().coerceAtLeast(dp(400).toFloat()))
                .setDuration(CLOSE_MS).setInterpolator(AccelerateInterpolator(1.4f))
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) { runCatching { dialog.dismiss() } }
                }).start()
        }

        fun build(firstFocus: () -> View?): Dialog {
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
            dialog.setContentView(content)
            dialog.window?.apply {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                setWindowAnimations(0)
            }
            // Not a dismiss listener: the player sets its own to bring the controls back.
            content.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {}
                override fun onViewDetachedFromWindow(v: View) { closed = true; images.shutdownNow() }
            })
            dialog.setOnShowListener {
                activity.window?.decorView?.systemUiVisibility?.let { flags -> dialog.window?.decorView?.systemUiVisibility = flags }
                content.translationY = activity.resources.displayMetrics.heightPixels.toFloat()
                content.animate().translationY(0f).setDuration(OPEN_MS).setInterpolator(DecelerateInterpolator(1.6f)).setListener(null).start()
                (firstFocus() ?: content).requestFocus()
            }
            return dialog
        }
    }

    private fun date(released: String): String =
        runCatching { LocalDate.parse(released.take(10)).format(DATE) }.getOrDefault("")

    private fun code(season: Int, episode: Int) = String.format(Locale.US, "S%02dE%02d", season, episode)

    private fun meta(vararg parts: String) = parts.filter(String::isNotBlank).joinToString(" · ")

    // ---------------------------------------------------------------------------------------------------- Info

    fun info(activity: Activity, identity: PlaybackIdentity): Dialog {
        val p = Panel(activity)
        val tv = DeviceLayout.tv
        val series = identity.type == "series" && identity.season > 0
        p.content.isFocusable = true; p.content.isFocusableInTouchMode = tv
        val column = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        p.content.addView(column, FrameLayout.LayoutParams(-1, -1))

        // The chevron says how it closes: Up (or Back) slides the panel down.
        column.addView(p.text("⌃", 30f, INK).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = p.dp(if (tv) 26 else 14) })

        val top = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        column.addView(top, LinearLayout.LayoutParams(-1, 0, 1f).apply {
            leftMargin = p.dp(if (tv) 48 else 20); rightMargin = p.dp(if (tv) 48 else 20)
        })
        val logoBox = FrameLayout(activity)
        val logo = ImageView(activity).apply { scaleType = ImageView.ScaleType.FIT_CENTER; adjustViewBounds = true }
        val logoText = p.text(identity.title, if (tv) 30f else 20f, INK, true).apply { gravity = Gravity.CENTER; maxLines = 3 }
        logoBox.addView(logoText, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        logoBox.addView(logo, FrameLayout.LayoutParams(-1, p.dp(if (tv) 150 else 80), Gravity.CENTER))
        top.addView(logoBox, LinearLayout.LayoutParams(p.dp(if (tv) 300 else 150), -2).apply { rightMargin = p.dp(if (tv) 40 else 16) })
        p.image(logoUrl(identity.imdbId), p.dp(300)) { logo.setImageBitmap(it); logoText.visibility = View.GONE }

        val facts = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        top.addView(facts, LinearLayout.LayoutParams(0, -2, 1f))
        val heading = p.text(if (series) code(identity.season, identity.episode) else identity.title, if (tv) 22f else 17f, INK)
            .apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        val details = p.text("", if (tv) 17f else 14f, MUTED, true).apply { visibility = View.GONE }
        val overview = p.text("", if (tv) 17f else 14f, 0xFFDDE1E6.toInt()).apply {
            maxLines = if (tv) 4 else 5; ellipsize = TextUtils.TruncateAt.END; setLineSpacing(0f, 1.25f)
        }
        facts.addView(heading, LinearLayout.LayoutParams(-1, -2))
        facts.addView(details, LinearLayout.LayoutParams(-1, -2).apply { topMargin = p.dp(14) })
        facts.addView(overview, LinearLayout.LayoutParams(-1, -2).apply { topMargin = p.dp(14) })

        val peopleTitle = p.text(if (series) "Episode Cast & Crew" else "Cast & Crew", if (tv) 19f else 16f, INK, true)
        column.addView(peopleTitle, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = p.dp(if (tv) 24 else 16); bottomMargin = p.dp(4) })
        val peopleNote = p.text("Loading…", 14f, DIM)
        column.addView(peopleNote, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = p.dp(if (tv) 24 else 16); topMargin = p.dp(8) })
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; setPadding(p.dp(if (tv) 16 else 10), 0, p.dp(16), 0) }
        val scroller = HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false; clipToPadding = false; clipChildren = false; addView(row)
        }
        column.addView(scroller, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = p.dp(if (tv) 26 else 14) })

        fun personCard(member: TmdbCatalog.CastMember): View {
            val width = p.dp(if (tv) 112 else 84); val height = p.dp(if (tv) 148 else 112)
            val card = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(0, p.dp(8), 0, p.dp(4))
                clipToPadding = false; clipChildren = false
                isFocusable = true; isFocusableInTouchMode = tv
                contentDescription = if (member.character.isBlank()) member.name else "${member.name}, ${member.character}"
            }
            val ring = FrameLayout(activity).apply { setPadding(p.dp(3), p.dp(3), p.dp(3), p.dp(3)) }
            val photo = ImageView(activity).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP; background = p.rounded(0xFF2A2D33.toInt(), 10)
                outlineProvider = ViewOutlineProvider.BACKGROUND; clipToOutline = true
            }
            ring.addView(photo, FrameLayout.LayoutParams(width, height))
            if (member.photo.isBlank()) ring.addView(p.text(member.name.split(' ').filter(String::isNotBlank).take(2)
                .joinToString("") { it.take(1).uppercase() }, 24f, DIM, true).apply { gravity = Gravity.CENTER },
                FrameLayout.LayoutParams(width, height))
            else p.image(member.photo, width) { photo.setImageBitmap(it) }
            card.addView(ring, LinearLayout.LayoutParams(-2, -2))
            card.addView(p.text(member.name, if (tv) 14f else 12f, INK, true).apply { gravity = Gravity.CENTER; maxLines = 1; ellipsize = TextUtils.TruncateAt.END },
                LinearLayout.LayoutParams(width + p.dp(16), -2).apply { topMargin = p.dp(6) })
            if (member.character.isNotBlank()) card.addView(p.text(member.character, if (tv) 13f else 11f, MUTED).apply {
                gravity = Gravity.CENTER; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(width + p.dp(16), -2).apply { topMargin = p.dp(3) })
            card.setOnFocusChangeListener { _, focused ->
                ring.background = if (focused) p.rounded(Color.WHITE, 13) else null
                ring.animate().scaleX(if (focused) 1.04f else 1f).scaleY(if (focused) 1.04f else 1f).setDuration(120).start()
            }
            return card
        }

        p.dialog.setOnKeyListener { _, keyCode, event ->
            val closeKey = keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_DPAD_UP
            if (closeKey && event.action == KeyEvent.ACTION_UP) { p.close(); true } else closeKey
        }
        if (!tv) p.content.setOnClickListener { p.close() }

        p.worker {
            val result = PlayerShowInfo.details?.invoke(identity)
            val episode = result?.episodes?.firstOrNull { it.season == identity.season && it.episode == identity.episode }
            p.ui {
                if (series) heading.text = code(identity.season, identity.episode) + (episode?.title?.takeIf { it.isNotBlank() }?.let { " - $it" } ?: "")
                else heading.text = result?.title?.ifBlank { null } ?: identity.title
                overview.text = (if (series) episode?.overview else result?.description).orEmpty()
                val line = if (series) meta(date(episode?.released.orEmpty())) else meta(result?.runtime.orEmpty(), result?.year.orEmpty())
                if (line.isNotBlank()) { details.text = line; details.visibility = View.VISIBLE }
            }
            val tmdb = PlayerShowInfo.tmdb?.invoke()
            if (tmdb == null) { p.ui { peopleNote.text = "Add a TMDB key in Settings to see the cast." }; return@worker }
            val people: List<TmdbCatalog.CastMember>
            var runtime = 0
            if (series) {
                val credits = runCatching { tmdb.episodeCredits(identity.imdbId, identity.season, identity.episode) }.getOrNull()
                people = credits?.people.orEmpty(); runtime = credits?.runtimeMinutes ?: 0
            } else people = runCatching { tmdb.cast(identity.imdbId, "movie", 30) }.getOrDefault(emptyList())
            p.ui {
                if (series && runtime > 0) {
                    details.text = meta("$runtime min", date(episode?.released.orEmpty())); details.visibility = View.VISIBLE
                }
                if (people.isEmpty()) { peopleNote.text = "No cast listed."; return@ui }
                peopleNote.visibility = View.GONE
                val hadFocus = p.content.isFocused
                people.forEach { row.addView(personCard(it), LinearLayout.LayoutParams(-2, -2).apply { rightMargin = p.dp(if (tv) 14 else 8) }) }
                if (hadFocus || p.dialog.currentFocus == null) row.getChildAt(0)?.requestFocus()
            }
        }
        return p.build { row.getChildAt(0) }
    }

    // ------------------------------------------------------------------------------------------------ Episodes

    fun episodes(activity: Activity, identity: PlaybackIdentity, pick: (EpisodeData) -> Unit): Dialog {
        val p = Panel(activity)
        val tv = DeviceLayout.tv
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(p.dp(if (tv) 32 else 14), p.dp(if (tv) 28 else 14), p.dp(if (tv) 32 else 14), 0)
        }
        p.content.addView(root, FrameLayout.LayoutParams(-1, -1))

        // Left: logo, the show's size, then one row per season.
        val left = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        root.addView(left, LinearLayout.LayoutParams(p.dp(if (tv) 260 else 170), -1).apply { rightMargin = p.dp(if (tv) 32 else 14) })
        val logoBox = FrameLayout(activity)
        val logo = ImageView(activity).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        val logoText = p.text(identity.title, if (tv) 24f else 17f, INK, true).apply { gravity = Gravity.CENTER; maxLines = 3 }
        logoBox.addView(logoText, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        logoBox.addView(logo, FrameLayout.LayoutParams(-1, -1))
        left.addView(logoBox, LinearLayout.LayoutParams(-1, p.dp(if (tv) 110 else 64)).apply { topMargin = p.dp(if (tv) 20 else 4) })
        p.image(logoUrl(identity.imdbId), p.dp(260)) { logo.setImageBitmap(it); logoText.visibility = View.GONE }
        val summary = p.text("", if (tv) 16f else 13f, MUTED).apply { gravity = Gravity.CENTER }
        left.addView(summary, LinearLayout.LayoutParams(-1, -2).apply { topMargin = p.dp(if (tv) 28 else 12); bottomMargin = p.dp(if (tv) 18 else 10) })
        val seasonList = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        left.addView(ScrollView(activity).apply { isVerticalScrollBarEnabled = false; addView(seasonList) }, LinearLayout.LayoutParams(-1, 0, 1f))

        // Right: "Season N  ★ x.x", then the episode cards.
        val right = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        root.addView(right, LinearLayout.LayoutParams(0, -1, 1f))
        val header = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        right.addView(header, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = p.dp(if (tv) 16 else 10); leftMargin = p.dp(6); rightMargin = p.dp(10) })
        val seasonTitle = p.text("Episodes", if (tv) 28f else 20f, INK, true)
        header.addView(seasonTitle, LinearLayout.LayoutParams(0, -2, 1f))
        val seasonScore = p.text("", if (tv) 17f else 14f, INK)
        header.addView(seasonScore, LinearLayout.LayoutParams(-2, -2))
        val cards = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(p.dp(4), p.dp(4), p.dp(4), p.dp(24)) }
        val cardScroll = ScrollView(activity).apply { isVerticalScrollBarEnabled = false; clipToPadding = false; addView(cards) }
        right.addView(cardScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val status = p.text("Loading episodes…", 16f, DIM)
        cards.addView(status)

        val today = LocalDate.now()
        var all: List<EpisodeData> = emptyList()
        var shownSeason = -1
        var generation = 0
        val seasonRows = mutableMapOf<Int, TextView>()
        val runtimes = mutableMapOf<Int, Map<Int, Int>>()
        val scores = mutableMapOf<Int, ImdbRatings.SeasonScores?>()
        val episodeViews = mutableListOf<Pair<EpisodeData, View>>()

        fun stars(score: ImdbRatings.Score?): CharSequence = if (score == null) "" else SpannableStringBuilder().apply {
            append("★", ForegroundColorSpan(STAR), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE); append(" ${score.label}")
        }

        fun paintSeasonRows() = seasonRows.forEach { (season, row) ->
            val selected = season == shownSeason
            row.background = when {
                row.isFocused -> p.rounded(Color.WHITE, 10)
                selected -> p.rounded(0xFF26282D.toInt(), 10)
                else -> null
            }
            row.setTextColor(if (row.isFocused) Color.BLACK else if (selected) INK else MUTED)
        }

        fun focusCard(): View? = episodeViews.firstOrNull { (e, _) -> e.season == identity.season && e.episode == identity.episode }?.second
            ?: episodeViews.firstOrNull()?.second

        fun card(episode: EpisodeData, metaOut: MutableMap<Int, Pair<TextView, TextView>>): View {
            val playing = episode.season == identity.season && episode.episode == identity.episode
            val stillWidth = p.dp(if (tv) 220 else 132)
            val card = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL; setPadding(p.dp(6), p.dp(6), p.dp(16), p.dp(6))
                isFocusable = true; isFocusableInTouchMode = tv; isClickable = true
                contentDescription = "Episode ${episode.episode}, ${episode.title}"
            }
            val still = FrameLayout(activity).apply { background = p.rounded(0xFF2A2D33.toInt(), 8); outlineProvider = ViewOutlineProvider.BACKGROUND; clipToOutline = true }
            val picture = ImageView(activity).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
            still.addView(picture, FrameLayout.LayoutParams(-1, -1))
            still.addView(p.text(String.format(Locale.US, "E%02d", episode.episode), if (tv) 14f else 11f, INK, true).apply {
                background = p.rounded(0xCC000000.toInt(), 6); setPadding(p.dp(7), p.dp(4), p.dp(7), p.dp(4))
            }, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { topMargin = p.dp(8); rightMargin = p.dp(8) })
            card.addView(still, LinearLayout.LayoutParams(stillWidth, stillWidth * 9 / 16))
            p.image(episode.thumbnail, stillWidth) { picture.setImageBitmap(it) }

            val textColumn = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            card.addView(textColumn, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = p.dp(if (tv) 22 else 12); gravity = Gravity.CENTER_VERTICAL })
            textColumn.addView(p.text(episode.title.ifBlank { "Episode ${episode.episode}" }, if (tv) 19f else 15f, INK, true)
                .apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(-1, -2))
            val metaRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            textColumn.addView(metaRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = p.dp(8) })
            val metaText = p.text("", if (tv) 16f else 13f, MUTED, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
            metaRow.addView(metaText, LinearLayout.LayoutParams(0, -2, 1f))
            val rating = p.text("", if (tv) 16f else 13f, INK, true)
            metaRow.addView(rating, LinearLayout.LayoutParams(-2, -2))
            metaOut[episode.episode] = metaText to rating
            if (episode.overview.isNotBlank()) textColumn.addView(p.text(episode.overview, if (tv) 15f else 12f, 0xFFC9CED6.toInt()).apply {
                maxLines = 3; ellipsize = TextUtils.TruncateAt.END; setLineSpacing(0f, 1.2f)
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = p.dp(8) })
            if (playing) metaText.setTextColor(INK)

            card.setOnFocusChangeListener { _, focused ->
                card.background = if (focused) p.rounded(CARD, 14, 0xFFC9CED6.toInt()) else null
            }
            card.setOnClickListener {
                if (playing) p.close() else { p.close(); pick(episode) }
            }
            card.setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && event.action == KeyEvent.ACTION_DOWN) { seasonRows[shownSeason]?.requestFocus(); true } else false
            }
            return card
        }

        fun paintMeta(season: Int, views: Map<Int, Pair<TextView, TextView>>) {
            val seasonRuntimes = runtimes[season].orEmpty(); val seasonScores = scores[season]
            all.filter { it.season == season }.forEach { episode ->
                val (metaText, rating) = views[episode.episode] ?: return@forEach
                val aired = seasonScores?.airDates?.get(episode.episode)
                    ?: runCatching { LocalDate.parse(episode.released.take(10)) }.getOrNull()
                val dateLabel = aired?.let { if (it.isAfter(today)) "Airs ${it.format(DATE)}" else it.format(DATE) }.orEmpty()
                val playing = episode.season == identity.season && episode.episode == identity.episode
                metaText.text = meta(if (playing) "Now playing" else "", seasonRuntimes[episode.episode]?.let { "$it min" }.orEmpty(), dateLabel)
                rating.text = stars(seasonScores?.episodes?.get(episode.episode))
            }
            val rated = seasonScores?.episodes?.values.orEmpty()
            seasonScore.text = if (rated.isEmpty()) "" else stars(ImdbRatings.Score(rated.map { it.rating }.average(), 0))
        }

        fun showSeason(season: Int, focus: Boolean) {
            if (season == shownSeason) return
            shownSeason = season; val token = ++generation
            seasonTitle.text = if (season == 0) "Specials" else "Season $season"
            cards.removeAllViews(); episodeViews.clear()
            val views = mutableMapOf<Int, Pair<TextView, TextView>>()
            all.filter { it.season == season }.forEach { episode ->
                val view = card(episode, views)
                cards.addView(view, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = p.dp(if (tv) 14 else 10) })
                episodeViews += episode to view
            }
            cardScroll.scrollTo(0, 0)
            paintMeta(season, views); paintSeasonRows()
            if (focus) focusCard()?.let { target -> target.post { target.requestFocus() } }
            if (season > 0 && (season !in runtimes || season !in scores)) p.worker {
                val times = runtimes[season] ?: runCatching { PlayerShowInfo.tmdb?.invoke()?.seasonRuntimes(identity.imdbId, season) }.getOrNull().orEmpty()
                val rated = if (season in scores) scores[season] else runCatching { ImdbRatings().season(identity.imdbId, season) }.getOrNull()
                p.ui { runtimes[season] = times; scores[season] = rated; if (token == generation) paintMeta(season, views) }
            }
        }

        p.dialog.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK) { if (event.action == KeyEvent.ACTION_UP) p.close(); true } else false
        }

        p.worker {
            val episodes = PlayerShowInfo.details?.invoke(identity)?.episodes.orEmpty()
                .sortedWith(compareBy({ if (it.season == 0) Int.MAX_VALUE else it.season }, { it.episode }))
            p.ui {
                all = episodes
                if (episodes.isEmpty()) { status.text = "Couldn't load the episode list."; return@ui }
                val seasons = episodes.map { it.season }.distinct()
                val regular = seasons.count { it > 0 }
                val count = episodes.count { it.season > 0 }
                summary.text = "$regular ${if (regular == 1) "Season" else "Seasons"} · $count ${if (count == 1) "Episode" else "Episodes"}"
                seasons.forEach { season ->
                    val size = episodes.count { it.season == season }
                    val row = p.text("", if (tv) 17f else 14f, MUTED, true).apply {
                        text = SpannableStringBuilder().apply {
                            append(if (season == 0) "Specials" else "Season $season")
                            append("   ")
                            val start = length; append("$size ${if (size == 1) "episode" else "episodes"}")
                            setSpan(ForegroundColorSpan(DIM), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        }
                        setPadding(p.dp(18), p.dp(14), p.dp(18), p.dp(14))
                        isFocusable = true; isFocusableInTouchMode = tv; isClickable = true
                        setOnFocusChangeListener { _, focused -> if (focused) showSeason(season, focus = false); paintSeasonRows() }
                        setOnClickListener { showSeason(season, focus = false); focusCard()?.requestFocus() }
                        setOnKeyListener { _, keyCode, event ->
                            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && event.action == KeyEvent.ACTION_DOWN) { focusCard()?.requestFocus(); true } else false
                        }
                    }
                    seasonRows[season] = row
                    seasonList.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = p.dp(6) })
                }
                showSeason(if (identity.season in seasons) identity.season else seasons.first(), focus = true)
            }
        }
        return p.build { null }
    }
}
