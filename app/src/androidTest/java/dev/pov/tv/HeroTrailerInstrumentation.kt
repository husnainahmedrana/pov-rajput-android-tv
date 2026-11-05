package dev.pov.tv
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.TextureView
import dev.pov.tv.data.*
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.common.C
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.util.concurrent.atomic.AtomicReference

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class HeroTrailerInstrumentation : Instrumentation() {
    private fun <T> main(work:()->T):T {val result=AtomicReference<Result<T>>();runOnMainSync{result.set(runCatching(work))};return result.get().getOrThrow()}
    private fun await(work:()->Boolean) {val until=SystemClock.elapsedRealtime()+100000;while(SystemClock.elapsedRealtime()<until){if(work())return;SystemClock.sleep(250)};error("Preview timeout")}
    private var launcherOnly=false
    override fun onCreate(arguments:Bundle?){super.onCreate(arguments);launcherOnly=arguments?.getString("launcher_only")=="true";start()}
    override fun onStart(){
        if(launcherOnly) { auditLauncher(); return }
        val report=Bundle();var activity:MainActivity?=null;var stage="start"
        try {
            targetContext.getSharedPreferences("pov_hero_trailers",0).edit().putBoolean("enabled",true).remove("sound").commit()
            activity=startActivitySync(Intent(targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("public_preview",true)) as MainActivity
            val app=activity
            fun field(name:String)=MainActivity::class.java.getDeclaredField(name).apply{isAccessible=true}
            fun call(name:String,vararg args:Any?)=main {MainActivity::class.java.declaredMethods.first{it.name==name && it.parameterCount==args.size}.apply{isAccessible=true}.invoke(app,*args)}
            fun trailer()=field("heroTrailer").get(app) as? HeroTrailerPreview
            fun status()=main{trailer()?.status.orEmpty()}
            fun chrome()=field("detailsTrailerChrome\$delegate").get(app).let { (it as Lazy<*>).value as DetailsTrailerChrome }
            fun player()=trailer()?.let { HeroTrailerPreview::class.java.getDeclaredField("player").apply{isAccessible=true}.get(it) as? ExoPlayer }
            fun audible()=main { player()?.let { it.isPlaying && it.volume > 0f && (it.audioDecoderCounters?.renderedOutputBufferCount ?: 0) > 0 } == true }
            fun toggleSound()=main {
                (field("body").get(app) as ViewGroup).findViewWithTag<View>("toggle:Trailer sound").performClick()
            }
            check(call("heroTrailerSoundEnabled") == true);report.putBoolean("sound_default_on",true)
            val catalog=CatalogApi(publicJson={url ->
                if(url.contains("/meta/")) Net.publicJson(url) else JSONObject().put("metas",JSONArray()
                    .put(JSONObject().put("id","tt1375666").put("type","movie").put("name","Inception").put("releaseInfo","2010").put("background","https://images.metahub.space/background/medium/tt1375666/img").put("poster","https://images.metahub.space/poster/medium/tt1375666/img"))
                    .put(JSONObject().put("id","tt0468569").put("type","movie").put("name","The Dark Knight").put("releaseInfo","2008").put("poster","https://images.metahub.space/poster/medium/tt0468569/img")))
            })
            main {field("catalog").set(app,catalog)};call("home")
            await{main{(field("body").get(app) as ViewGroup).findViewWithTag<View>("featured-hero")!=null}}
            val movie=CatalogApi().details(MediaItemData("tt1375666","movie","Inception"))
            stage="home_backdrops"
            SystemClock.sleep(2200)
            check(main { trailer()?.frame==null && player()==null })
            report.putBoolean("home_backdrops_only",true)
            val hero=main { (field("body").get(app) as ViewGroup).findViewWithTag<View>("featured-hero") }
            val rotation=main { field("heroRotation").get(app) as Runnable }
            val first=main { (hero.getTag(R.id.pov_feature_item) as MediaItemData).imdbId }
            main { hero.findViewWithTag<ViewGroup>("feature-actions").getChildAt(0).requestFocus() }
            repeat(3) { main { rotation.run() };SystemClock.sleep(450) }
            check(main { (hero.getTag(R.id.pov_feature_item) as MediaItemData).imdbId!=first })
            report.putBoolean("home_rotation_with_trailers_enabled",true)
            stage="home_poster_trailer"
            val focusedPoster=call("card",movie) as View
            main {
                (field("body").get(app) as ViewGroup).addView(focusedPoster,1,android.widget.LinearLayout.LayoutParams(500,260))
                focusedPoster.requestFocus()
            }
            await { status()=="playing" && audible() }
            report.putBoolean("home_focused_poster_trailer",true)
            main {
                val frame=checkNotNull(trailer()?.frame)
                val backdrop=field("heroBackdrop").get(app) as View
                check(frame.top==0 && frame.right==backdrop.width && frame.width()<backdrop.width && frame.height()<backdrop.height)
            }
            report.putBoolean("home_trailer_top_right",true)
            main { player()?.seekTo(30_000) };SystemClock.sleep(3000)
            uiAutomation.takeScreenshot().let { bitmap -> File(targetContext.externalCacheDir,"home-trailer-top-right.png").outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle() }
            main { hero.findViewWithTag<ViewGroup>("feature-actions").getChildAt(0).requestFocus() }
            check(main { player()==null });report.putBoolean("rotating_hero_stays_backdrop_only",true)
            stage="details_sound_on"
            call("details",movie)
            await { status()=="playing" && audible() }
            check(main { field("isHome").get(app) == false })
            report.putBoolean("movie_details_video_and_audio",true)
            SystemClock.sleep(4000)
            uiAutomation.takeScreenshot().let { bitmap -> File(targetContext.externalCacheDir,"hero-trailer-details.png").outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle() }
            stage="framing_comparison"
            main { player()?.seekTo(30_000) };SystemClock.sleep(2500)
            main { player()?.pause() }
            main { targetContext.getSharedPreferences("pov_hero_trailers",0).edit().putBoolean("fill_screen",false).commit();trailer()?.updateFraming() }
            SystemClock.sleep(350)
            uiAutomation.takeScreenshot().let { bitmap -> File(targetContext.externalCacheDir,"trailer-original.png").outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle() }
            main { targetContext.getSharedPreferences("pov_hero_trailers",0).edit().putBoolean("fill_screen",true).commit();trailer()?.updateFraming() }
            SystemClock.sleep(350)
            uiAutomation.takeScreenshot().let { bitmap -> File(targetContext.externalCacheDir,"trailer-fill.png").outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle() }
            main { player()?.play() };report.putBoolean("fill_screen_comparison",true)
            stage="details_idle_fade"
            await { main { chrome().hidden && (field("scroller").get(app) as View).alpha < 0.01f } }
            report.putBoolean("details_hide_after_idle",true)
            val detailGeneration=main { field("generation").getInt(app) }
            sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
            SystemClock.sleep(300)
            check(main { !chrome().hidden && field("generation").getInt(app)==detailGeneration && (field("scroller").get(app) as View).alpha > 0.99f })
            report.putBoolean("first_ok_reveals_without_playing",true)
            await { main { chrome().hidden } };report.putBoolean("details_hide_again_after_idle",true)
            stage="sound_toggle_off"
            call("settings");check(status()=="idle");call("settingsSectionContent","home");toggleSound()
            check(call("heroTrailerSoundEnabled")==false)
            call("details",MediaItemData("tt0903747","series","Breaking Bad"))
            await { status()=="playing" }
            SystemClock.sleep(2500)
            check(main { player()?.volume==0f && player()?.audioFormat==null && player()?.trackSelectionParameters?.disabledTrackTypes?.contains(C.TRACK_TYPE_AUDIO)==true })
            report.putBoolean("series_details_muted",true)
            stage="saved_tmdb_cast"
            main { field("publicPreview").setBoolean(app,false) }
            val tmdbConfigured=main { (field("store").get(app) as SecureStore).get("tmdb.key").isNotBlank() }
            report.putBoolean("saved_tmdb_key_configured",tmdbConfigured)
            if (tmdbConfigured) {
                call("details",movie)
                fun castCount(view:View):Int = (if (view.tag?.toString()?.startsWith("cast:")==true) 1 else 0) +
                    (if (view is ViewGroup) (0 until view.childCount).sumOf { castCount(view.getChildAt(it)) } else 0)
                await { main { castCount(field("body").get(app) as View)>0 } }
                report.putBoolean("saved_tmdb_cast_loaded",true)
            }
            main { field("publicPreview").setBoolean(app,true) }
            stage="sound_toggle_on"
            call("settings");check(status()=="idle");call("settingsSectionContent","home");toggleSound()
            check(call("heroTrailerSoundEnabled")==true)
            report.putBoolean("sound_toggle_roundtrip",true)
            report.putBoolean("page_exit_stops_trailer",true)
            call("home");await{main{(field("body").get(app) as ViewGroup).findViewWithTag<View>("featured-hero")!=null}}
            call("details",movie);SystemClock.sleep(1000)
            main {callActivityOnPause(app)};check(status()=="idle");main{callActivityOnResume(app)};report.putBoolean("background_stops_trailer",true)
            stage="missing"
            main { (field("body").get(app) as ViewGroup).findViewWithTag<View>("featured-hero").setTag(R.id.pov_feature_item,MediaItemData("tt0000000","movie","Missing trailer test")) }
            call("refreshHeroTrailer");await{status()=="unavailable"}
            check(main{trailer()?.frame==null});report.putBoolean("missing_trailer_keeps_artwork",true)
            main{targetContext.getSharedPreferences("pov_hero_trailers",0).edit().putBoolean("enabled",false).commit()};call("refreshHeroTrailer");check(status()=="idle")
            report.putBoolean("toggle_off_stops_preview",true)
            main{targetContext.getSharedPreferences("pov_hero_trailers",0).edit().putBoolean("enabled",true).commit()}
            stage="launcher_sync"
            val homeSync=TvContinueWatching(targetContext)
            homeSync.sync()
            check(targetContext.getSharedPreferences(TvContinueWatching.PREFS,0).getString("status","").orEmpty().startsWith("Synced"))
            report.putString("launcher_status",targetContext.getSharedPreferences(TvContinueWatching.PREFS,0).getString("status",""))
            report.putBoolean("passed",true)
        }catch(error:Throwable){report.putString("failure_stage",stage);report.putString("error_type",error.javaClass.simpleName);report.putBoolean("passed",false)}
        finally{activity?.let{main{it.finish()}};finish(-1,report)}
    }
    private fun auditLauncher() {
        val report=Bundle();val publisher=TvContinueWatching(targetContext)
        var diagnostic=false
        try {
            publisher.sync(refreshRemote=true)
            fun rows(table:android.net.Uri):List<Pair<Long,String>> = targetContext.contentResolver.query(table,arrayOf("_id","package_name","internal_provider_id","intent_uri"),null,null,null)?.use { cursor ->
                buildList { while(cursor.moveToNext()) if(cursor.getString(1)==targetContext.packageName && cursor.getString(2).orEmpty().startsWith(TvContinueWatching.PREFIX)) add(cursor.getLong(0) to cursor.getString(3)) }
            }.orEmpty()
            val before=rows(android.media.tv.TvContract.PreviewPrograms.CONTENT_URI)
            report.putInt("real_continue_count",before.size)
            if(before.isEmpty()) {
                diagnostic=true
                check(publisher.publish(listOf(TvContinueWatching.Entry(PlaybackIdentity("tt1375666","movie","POV launcher test · Inception"),32.0,8_880_000))))
            }
            val preview=rows(android.media.tv.TvContract.PreviewPrograms.CONTENT_URI)
            val watch=rows(android.media.tv.TvContract.WatchNextPrograms.CONTENT_URI)
            check(preview.isNotEmpty() && watch.isNotEmpty())
            report.putInt("launcher_channel_count",preview.size);report.putInt("watch_next_count",watch.size)
            preview.forEach { (_,uri) -> check(LauncherResumeLink.parse(targetContext,Intent.parseUri(uri,Intent.URI_INTENT_SCHEME))!=null) }
            report.putBoolean("resume_intents_valid",true)
            targetContext.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            SystemClock.sleep(7000)
            uiAutomation.takeScreenshot().let { bitmap -> File(targetContext.externalCacheDir,"launcher-continue.png").outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle() }
            report.putBoolean("passed",true)
        }catch(error:Throwable){report.putString("error_type",error.javaClass.simpleName);report.putBoolean("passed",false)}
        finally { if(diagnostic) publisher.sync();finish(-1,report) }
    }
}
