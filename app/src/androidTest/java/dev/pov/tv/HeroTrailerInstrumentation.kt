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
    override fun onCreate(arguments:Bundle?){super.onCreate(arguments);start()}
    override fun onStart(){
        val report=Bundle();var activity:MainActivity?=null;var stage="start"
        try {
            targetContext.getSharedPreferences("pov_hero_trailers",0).edit().putBoolean("enabled",true).remove("sound").commit()
            activity=startActivitySync(Intent(targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("public_preview",true)) as MainActivity
            val app=activity
            fun field(name:String)=MainActivity::class.java.getDeclaredField(name).apply{isAccessible=true}
            fun call(name:String,vararg args:Any?)=main {MainActivity::class.java.declaredMethods.first{it.name==name && it.parameterCount==args.size}.apply{isAccessible=true}.invoke(app,*args)}
            fun trailer()=field("heroTrailer").get(app) as? HeroTrailerPreview
            fun status()=main{trailer()?.status.orEmpty()}
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
            stage="movie";call("showFeature",movie)
            await {status() in setOf("playing","unavailable","ended")}
            report.putString("movie_state",status());report.putBoolean("actual_trailer_played",status()=="playing")
            main {
                trailer()?.frame?.let { area -> check(area.width()>=200 && area.height()>=200);report.putBoolean("visible_hero_viewport",true) }
                check(app.currentFocus !is TextureView);report.putBoolean("native_focus_retained",true)
            }
            await { audible() };report.putBoolean("home_audio_rendered",true)
            SystemClock.sleep(7000)
            uiAutomation.takeScreenshot().let { bitmap -> File(targetContext.externalCacheDir,"hero-trailer-movie.png").outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle() }
            val focusedBefore = main { app.currentFocus }
            sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_DOWN);waitForIdleSync();SystemClock.sleep(500)
            report.putBoolean("dpad_navigation",main { app.currentFocus !== focusedBefore && app.currentFocus !is TextureView })
            sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_RIGHT);waitForIdleSync()
            stage="switch"
            call("showFeature",MediaItemData("tt0903747","series","Breaking Bad"))
            check(main{trailer()?.playingId.orEmpty().isEmpty()});report.putBoolean("previous_trailer_stops_on_selection",true)
            await {status() in setOf("playing","unavailable","ended")}
            report.putString("series_state",status())
            report.putString("series_trailer_id",main{trailer()?.playingId.orEmpty()})
            report.putBoolean("actual_trailer_played",status()=="playing" || report.getBoolean("actual_trailer_played"))
            SystemClock.sleep(2000)
            uiAutomation.takeScreenshot().let { bitmap -> File(targetContext.externalCacheDir,"hero-trailer-series.png").outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle() }
            stage="details_sound_on"
            call("details",movie)
            await { status()=="playing" && audible() }
            check(main { field("isHome").get(app) == false })
            report.putBoolean("movie_details_video_and_audio",true)
            SystemClock.sleep(4000)
            uiAutomation.takeScreenshot().let { bitmap -> File(targetContext.externalCacheDir,"hero-trailer-details.png").outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle() }
            stage="sound_toggle_off"
            call("settings");check(status()=="idle");call("settingsSectionContent","home");toggleSound()
            check(call("heroTrailerSoundEnabled")==false)
            call("details",MediaItemData("tt0903747","series","Breaking Bad"))
            await { status()=="playing" }
            SystemClock.sleep(2500)
            check(main { player()?.volume==0f && player()?.audioFormat==null && player()?.trackSelectionParameters?.disabledTrackTypes?.contains(C.TRACK_TYPE_AUDIO)==true })
            report.putBoolean("series_details_muted",true)
            stage="sound_toggle_on"
            call("settings");check(status()=="idle");call("settingsSectionContent","home");toggleSound()
            check(call("heroTrailerSoundEnabled")==true)
            report.putBoolean("sound_toggle_roundtrip",true)
            report.putBoolean("page_exit_stops_trailer",true)
            call("home");await{main{(field("body").get(app) as ViewGroup).findViewWithTag<View>("featured-hero")!=null}}
            call("showFeature",movie);SystemClock.sleep(1000)
            main {callActivityOnPause(app)};check(status()=="idle");main{callActivityOnResume(app)};report.putBoolean("background_stops_trailer",true)
            stage="missing";call("showFeature",MediaItemData("tt0000000","movie","Missing trailer test"));await{status()=="unavailable"}
            check(main{trailer()?.frame==null});report.putBoolean("missing_trailer_keeps_artwork",true)
            main{targetContext.getSharedPreferences("pov_hero_trailers",0).edit().putBoolean("enabled",false).commit()};call("refreshHeroTrailer");check(status()=="idle")
            report.putBoolean("toggle_off_stops_preview",true)
            main{targetContext.getSharedPreferences("pov_hero_trailers",0).edit().putBoolean("enabled",true).commit()}
            report.putBoolean("passed",true)
        }catch(error:Throwable){report.putString("failure_stage",stage);report.putString("error_type",error.javaClass.simpleName);report.putBoolean("passed",false)}
        finally{activity?.let{main{it.finish()}};finish(-1,report)}
    }
}
