package dev.pov.tv

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import dev.pov.tv.data.MediaItemData
import dev.pov.tv.data.PlaybackIdentity
import dev.pov.tv.data.SecureStore
import dev.pov.tv.data.SourceItem
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * Public feature screenshots for the website and README, on TV and phone. Run it on a separate throwaway install
 * (-PpovAppIdSuffix=.shots): it saves placeholder service logins so the link list shows each service's logo, and
 * nothing it saves is a real account. Home and details use public catalogs in preview mode, every link is a
 * synthetic example, and the player plays the open film Big Buck Bunny (Blender Foundation, CC BY 3.0).
 *
 *   adb shell am instrument -w dev.pov.tv.rajput.shots.test/dev.pov.tv.PublicFeatureScreenshotsInstrumentation
 *   adb pull /sdcard/Android/data/dev.pov.tv.rajput.shots/cache/  (feature-*.png)
 */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class PublicFeatureScreenshotsInstrumentation : Instrumentation() {
    private fun <T> main(task: () -> T): T {
        val result = AtomicReference<Result<T>>()
        runOnMainSync { result.set(runCatching(task)) }; return result.get().getOrThrow()
    }
    private fun views(root: View): List<View> = buildList { add(root); if (root is ViewGroup) for (i in 0 until root.childCount) addAll(views(root.getChildAt(i))) }
    private fun await(seconds: Int = 30, test: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + seconds * 1000L
        while (SystemClock.elapsedRealtime() < until) { if (test()) return; SystemClock.sleep(150) }
        error("Screenshot content not ready")
    }
    /** Waits for content up to [seconds], then carries on either way (some pages draw art without bitmaps). */
    private fun settle(seconds: Int, test: () -> Boolean) = runCatching { await(seconds, test) }
    /** The Home feature has its title text and Play button laid out (its art can arrive before them). */
    private fun featureReady(activity: Activity) = main {
        views(activity.window.decorView).filterIsInstance<android.widget.TextView>().any { it.isShown && it.text.toString().contains("Play") && it.width > 0 }
    }
    private fun images(activity: Activity) = main { views(activity.window.decorView).filterIsInstance<ImageView>().count { it.isShown && it.drawable is BitmapDrawable } }
    private fun snap(name: String, settle: Long = 1200) {
        waitForIdleSync(); SystemClock.sleep(settle)
        val shot = checkNotNull(uiAutomation.takeScreenshot())
        File(targetContext.externalCacheDir, "feature-$name.png").outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        shot.recycle()
    }

    /** Optional TMDB key for the cast row on the details shots, passed with `-e tmdbKey …` (never kept in the repo). */
    private var tmdbKey = ""
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); tmdbKey = arguments?.getString("tmdbKey").orEmpty(); start() }

    override fun onStart() {
        val report = Bundle(); var passed = false; var stage = "setup"
        var app: MainActivity? = null; var player: PlayerActivity? = null
        try {
            check(targetContext.packageName.endsWith(".shots")) { "Run on the throwaway .shots install only" }
            File(targetContext.externalCacheDir!!.path).listFiles { f -> f.name.startsWith("feature-") }?.forEach { it.delete() }
            // Placeholder logins (not accounts) so the link list counts and shows each service's logo.
            SecureStore(targetContext).putAll(mapOf("torbox.token" to "example", "offcloud.key" to "example",
                "easynews.username" to "example", "easynews.password" to "example"))
            if (tmdbKey.isNotBlank()) SecureStore(targetContext).put("tmdb.key", tmdbKey)
            main { ThemePreferences(targetContext).select(ThemeSpec.FLIX) }
            // Sorted like an everyday list: highest quality, then largest file.
            targetContext.getSharedPreferences("pov_sources", 0).edit().putBoolean("quality_descending", true).putBoolean("size_ascending", false)
                .putBoolean("library_first", false).putBoolean("language_first", false).commit()
            // Still backdrops on details pages: a trailer frame mid-play makes a muddy screenshot.
            targetContext.getSharedPreferences("pov_hero_trailers", 0).edit().putBoolean("enabled", false).commit()

            stage = "home"
            val activity = startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("public_preview", true)) as MainActivity
            app = activity
            fun field(name: String) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
            fun call(name: String) = MainActivity::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(activity)
            // Keeps the TV screensaver away while the shots are taken (this window only).
            main { activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            main { activity.window.decorView.rootWindowInsets?.let { report.putInt("inset_top", it.systemWindowInsetTop); report.putInt("inset_bottom", it.systemWindowInsetBottom) } }
            settle(60) { images(activity) >= 6 }
            settle(30) { featureReady(activity) }
            snap("01-home", 7000)

            // Continue Watching: example progress for public titles, shown on Home as resume cards (preview mode hides it).
            stage = "home_resume"
            PlaybackStore(targetContext).apply {
                save("example:oppenheimer", "Oppenheimer", PlaybackIdentity("tt15398776", "movie", "Oppenheimer"), 31 * 60_000L, 180 * 60_000L)
                save("example:bb", "Breaking Bad", PlaybackIdentity("tt0903747", "series", "Breaking Bad", 2, 5), 28 * 60_000L, 47 * 60_000L)
                save("example:interstellar", "Interstellar", PlaybackIdentity("tt0816692", "movie", "Interstellar"), 74 * 60_000L, 169 * 60_000L)
                save("example:dune", "Dune: Part Two", PlaybackIdentity("tt15239678", "movie", "Dune: Part Two"), 96 * 60_000L, 167 * 60_000L)
            }
            main { field("publicPreview").setBoolean(activity, false); call("home") }
            SystemClock.sleep(4000); settle(40) { images(activity) >= 8 }
            settle(30) { featureReady(activity) }
            snap("01b-home-resume", 7000)
            main { field("publicPreview").setBoolean(activity, true) }

            val details = MainActivity::class.java.getDeclaredMethod("details", MediaItemData::class.java).apply { isAccessible = true }
            // Details leave preview mode only when a TMDB key was passed, so the cast row (a TMDB feature) shows.
            fun detailsShot(item: MediaItemData, name: String) {
                main { if (tmdbKey.isNotBlank()) field("publicPreview").setBoolean(activity, false); details.invoke(activity, item) }
                SystemClock.sleep(2500)
                settle(20) { images(activity) >= (if (item.type == "series") 4 else 2) }
                // The title's logo replaces its plain-text name once it has downloaded.
                settle(20) { main { views(activity.window.decorView).none { it is android.widget.TextView && it.isShown && it.text.toString() == item.title && it.textSize > 40f } } }
                snap(name, 3500)
                main { field("publicPreview").setBoolean(activity, true) }
            }
            stage = "details_movie"; detailsShot(MediaItemData("tt15239678", "movie", "Dune: Part Two"), "02-details-movie")
            stage = "details_series"; detailsShot(MediaItemData("tt0903747", "series", "Breaking Bad"), "03-details-series")

            stage = "search"
            val searchResults = MainActivity::class.java.getDeclaredMethod("searchResults", String::class.java, String::class.java,
                Class.forName("dev.pov.tv.data.CatalogApi\$SearchResults")).apply { isAccessible = true }
            main { searchResults.invoke(activity, "Star Wars", "all", null) }
            SystemClock.sleep(4000); settle(30) { images(activity) >= 12 }
            snap("04-search", 5000)

            stage = "sources"
            val example = "https://example.invalid/"
            fun link(title: String, quality: Int, gb: Double, detail: String = "", hash: Int = 0, cachedOn: List<String> = emptyList(), direct: String = "",
                     addon: String? = null, usenet: Boolean = false, servedBy: String? = null, news: String = "", indexer: String = "", library: Boolean = false) =
                SourceItem(title, when (addon) { null -> "Example"; "aiostreams" -> "AIOStreams"; "streamnzb" -> "StreamNZB"; "altmount" -> "AltMount"
                    "penguplay" -> "PenguPlay"; "cncverse" -> "CNCVerse"; "plavio" -> "PlavioHD"; else -> addon }, infoHash = if (direct.isEmpty()) String.format("%040x", hash) else "",
                    directUrl = direct, cached = cachedOn.isNotEmpty() || direct.isNotEmpty(), detail = detail, cachedServices = cachedOn,
                    sizeBytes = (gb * 1_000_000_000).toLong(), qualityHeight = quality, addon = addon, usenet = usenet, servedBy = servedBy,
                    newsProvider = news, indexer = indexer, inLibrary = library)
            // Big Buck Bunny runs ten minutes, so the example sizes are scaled to give everyday bitrates.
            val tvItems = listOf(
                link("Big.Buck.Bunny.2008.2160p.UHD.BluRay.Remux.HDR.HEVC.DTS-HD.MA.5.1.mkv", 2160, 6.61, direct = "${example}am-1", addon = "altmount", news = "easynews", indexer = "NZBgeek"),
                link("Big.Buck.Bunny.2008.2160p.UHD.BluRay.REMUX.HDR10.HEVC.DTS-HD.MA.5.1.mkv", 2160, 6.56, direct = "${example}am-2", addon = "altmount", news = "easynews", indexer = "NZBFinder"),
                link("Big.Buck.Bunny.2008.2160p.UHD.BluRay.REMUX.DV.HDR10.HEVC.TrueHD.Atmos.7.1.mkv", 2160, 6.42, hash = 1, cachedOn = listOf("torbox")),
                link("Big.Buck.Bunny.2008.2160p.UHD.BluRay.REMUX.HDR.HEVC.mkv", 2160, 6.0, direct = "${example}snzb-1", addon = "streamnzb", indexer = "Usenet-Crawler"),
                link("Big.Buck.Bunny.2008.2160p.WEB-DL.DV.HDR10Plus.DDP5.1.Atmos.HEVC.mkv", 2160, 3.9, "Source: 4KHDHub · R2", direct = "${example}pengu-1", addon = "penguplay"),
                link("Big.Buck.Bunny.2008.2160p.WEB-DL.HDR10.DDP5.1.HEVC.mkv", 2160, 3.4, hash = 2, cachedOn = listOf("offcloud")),
                link("Big.Buck.Bunny.2008.2160p.WEB-DL.DDP5.1.HEVC.mkv", 2160, 2.8, direct = "${example}aio-1", addon = "aiostreams", servedBy = "torbox"),
                link("V-Cloud[FSL Server] Big.Buck.Bunny.2008.1080p.BluRay.DTS-HD.MA.5.1.x264.mkv[2.6 GB]", 1080, 2.6, "Source: VegaMovies · V-Cloud FSL Server", direct = "${example}cnc-1", addon = "cncverse"),
                link("Big.Buck.Bunny.2008.1080p.BluRay.DTS.5.1.x264.mkv", 1080, 2.2, direct = "https://members.easynews.com/dl/example.mkv"),
                link("Big.Buck.Bunny.2008.1080p.WEB-DL.DDP5.1.H.264.mkv", 1080, 1.1, direct = "${example}plavio-1", addon = "plavio"),
            )
            // A phone row has room for one logo beside its badges, as in everyday use: one service per link there.
            val phoneItems = listOf(
                link("Big.Buck.Bunny.2008.2160p.UHD.BluRay.REMUX.HDR10Plus.HEVC.DTS-HD.MA.5.1.mkv", 2160, 4.6, hash = 11, cachedOn = listOf("torbox")),
                link("Big.Buck.Bunny.2008.2160p.WEB-DL.DV.HDR10.HEVC.DDP5.1.Atmos.mkv", 2160, 1.9, hash = 12, cachedOn = listOf("offcloud")),
                link("Big.Buck.Bunny.2008.2160p.HDR.HEVC.DTS-HD.MA.5.1.mkv", 2160, 1.4, direct = "https://members.easynews.com/dl/example-4k.mkv"),
                link("Big.Buck.Bunny.2008.1080p.BluRay.REMUX.AVC.DTS-HD.MA.5.1.mkv", 1080, 2.6, hash = 13, cachedOn = listOf("torbox")),
                link("Big.Buck.Bunny.2008.1080p.WEB-DL.DDP5.1.H.264.mkv", 1080, 0.6, hash = 14, cachedOn = listOf("offcloud")),
                link("Big.Buck.Bunny.2008.1080p.BluRay.x265.10bit.AAC5.1.mkv", 1080, 0.35, direct = "https://members.easynews.com/dl/example-1080.mkv"),
            )
            val items = if (DeviceLayout.phone) phoneItems else tvItems
            val identity = PlaybackIdentity("tt1254207", "movie", "Big Buck Bunny")
            // The link list keeps the backdrop of the page before it, so open the film's own details first.
            main { details.invoke(activity, MediaItemData("tt1254207", "movie", "Big Buck Bunny")) }; SystemClock.sleep(4000)
            val results = MainActivity::class.java.getDeclaredMethod("sourceResults", PlaybackIdentity::class.java, List::class.java).apply { isAccessible = true }
            main { results.invoke(activity, identity, items) }
            SystemClock.sleep(2500)
            snap("05-links-logos", 2500)

            stage = "settings"
            runCatching { MainActivity::class.java.getDeclaredMethod("setSettingsSection", String::class.java).apply { isAccessible = true }.let { set -> main { set.invoke(activity, "playback") } } }
            main { call("settings") }; snap("06-settings", 2000)

            stage = "player"
            val started = startActivitySync(Intent(targetContext, PlayerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("url", PUBLIC_FILM).putExtra("title", "Big Buck Bunny").putExtra("identity", identity.toJson().toString())
                .putExtra("key", "").putExtra("playWhenReady", true).putExtra("source_addon", "aiostreams|altmount,newshosting,nzbgeek")
                .putExtra("media_name", "Big.Buck.Bunny.2008.2160p.WEB-DL.DDP5.1.Atmos.DV.HDR10Plus.HEVC.mkv").putExtra("media_size", 1_900_000_000L)) as PlayerActivity
            player = started
            main { started.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            await(150) { main { InAppPlaybackSession.playerOrNull?.playbackState == Player.STATE_READY } }
            SystemClock.sleep(4000)
            main { InAppPlaybackSession.playerOrNull?.pause() }
            // Screenshots cannot capture hardware video layers, so the film's own public backdrop stands in for the frame.
            val still = java.net.URL("https://images.metahub.space/background/large/tt1254207/img").openStream().use { android.graphics.BitmapFactory.decodeStream(it) }
            main {
                val playerView = views(started.window.decorView).filterIsInstance<androidx.media3.ui.PlayerView>().first()
                checkNotNull(playerView.overlayFrameLayout).addView(ImageView(started).apply {
                    setImageBitmap(still); scaleType = ImageView.ScaleType.CENTER_CROP; tag = "screenshot-still"
                }, 0, android.widget.FrameLayout.LayoutParams(-1, -1))
            }
            fun pfield(name: String) = PlayerActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
            main { (pfield("controls").get(started) as PlayerControlVisibility.Binding).show() }
            await(20) { main { (pfield("pauseLogo").get(started) as View).let { it.visibility == View.VISIBLE && it.alpha > 0.95f } } }
            // Example facts for a 4K Dolby Vision + Atmos file: the badge row is drawn by the app itself, from these facts.
            val facts = PlayerMediaFacts(name = "Big.Buck.Bunny.2008.2160p.WEB-DL.DDP5.1.Atmos.DV.HDR10Plus.HEVC.mkv", sizeBytes = 1_900_000_000L,
                width = 3840, height = 2160, videoMime = MimeTypes.VIDEO_DOLBY_VISION, videoCodecs = "dvhe.08.06", colorTransfer = androidx.media3.common.C.COLOR_TRANSFER_ST2084,
                audioMime = MimeTypes.AUDIO_E_AC3_JOC, channels = 6)
            main { started.findViewById<PlayerBadgeRow>(R.id.pov_media_badges)?.show(PlayerMediaBadges.from(facts)) }
            snap("07-player", 400)
            // The row drops badges that don't fit (a phone has room for four), so two more example files show the rest:
            // the HDR badges, then the audio badges.
            fun badgesShot(facts: PlayerMediaFacts, name: String) {
                main { started.findViewById<PlayerBadgeRow>(R.id.pov_media_badges)?.show(PlayerMediaBadges.from(facts)) }
                snap(name, 400)
            }
            badgesShot(PlayerMediaFacts(name = "Big.Buck.Bunny.2008.DV.HDR10Plus.HEVC.mkv", videoMime = MimeTypes.VIDEO_DOLBY_VISION, videoCodecs = "dvhe.08.06"), "08-player-hdr")
            badgesShot(PlayerMediaFacts(name = "Big.Buck.Bunny.2008.TrueHD.Atmos.7.1.mkv", audioMime = MimeTypes.AUDIO_TRUEHD, channels = 8), "09-player-audio")
            passed = true
        } catch (failure: Throwable) {
            report.putString("error_stage", stage); report.putString("error", "${failure.javaClass.simpleName}: ${failure.message}".take(300))
        } finally {
            try {
                main {
                    InAppPlaybackSession.stop()
                    player?.takeUnless { it.isFinishing }?.finish(); (app as Activity?)?.takeUnless { it.isFinishing }?.finish()
                }
            } catch (cleanup: Throwable) { report.putString("cleanup_error", cleanup.javaClass.simpleName) }
            report.putBoolean("passed", passed)
            finish(if (passed) Activity.RESULT_OK else Activity.RESULT_CANCELED, report)
        }
    }

    private companion object {
        /** Big Buck Bunny (c) Blender Foundation, CC BY 3.0, served from Akamai's public DASH test stream. */
        const val PUBLIC_FILM = "https://dash.akamaized.net/akamai/bbb_30fps/bbb_30fps.mpd"
    }
}
