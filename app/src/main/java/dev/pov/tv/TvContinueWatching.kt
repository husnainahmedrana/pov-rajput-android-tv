package dev.pov.tv

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.*
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.tv.TvContract
import android.net.Uri
import dev.pov.tv.data.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Reconciles only this package's own Continue Watching channel and Watch Next entries. */
internal class TvContinueWatching(private val context: Context) {
    data class Entry(val identity: PlaybackIdentity,val percent: Double,val duration: Long=0,val playedAt: Long=0) {
        val key get()="${identity.type}:${identity.imdbId}"
    }
    private val resolver=context.contentResolver
    private val prefs=context.getSharedPreferences(PREFS,0)
    private data class Row(val id:Long,val key:String)
    private fun ownRows(table:Uri): List<Row> = resolver.query(table,arrayOf("_id","package_name","internal_provider_id"),null,null,null)?.use { cursor ->
        buildList { while(cursor.moveToNext()) if(cursor.getString(1)==context.packageName && cursor.getString(2).orEmpty().startsWith(PREFIX)) add(Row(cursor.getLong(0),cursor.getString(2))) }
    }.orEmpty()
    private fun values(entry:Entry): ContentValues = ContentValues().apply {
        val identity=entry.identity
        put("internal_provider_id",PREFIX+entry.key)
        put("content_id",PREFIX+entry.key)
        put("type",if(identity.type=="series") TvContract.PreviewPrograms.TYPE_TV_EPISODE else TvContract.PreviewPrograms.TYPE_MOVIE)
        put("title",identity.title)
        put("short_description",listOf(if(identity.type=="series") "S${identity.season} E${identity.episode}" else "", "${entry.percent.toInt()}% watched").filter(String::isNotBlank).joinToString(" · "))
        put("intent_uri",LauncherResumeLink.intent(context,identity).toUri(Intent.URI_INTENT_SCHEME))
        put("poster_art_uri","https://images.metahub.space/background/medium/${identity.imdbId}/img")
        put("poster_art_aspect_ratio",TvContract.PreviewPrograms.ASPECT_RATIO_16_9)
        if(identity.type=="series") { put("season_display_number",identity.season.toString());put("episode_display_number",identity.episode.toString());put("series_id",identity.imdbId) }
        if(entry.duration>0) { put("duration_millis",entry.duration.coerceAtMost(Int.MAX_VALUE.toLong()).toInt());put("last_playback_position_millis",(entry.duration*entry.percent/100).toLong().coerceAtMost(Int.MAX_VALUE.toLong()).toInt()) }
    }
    private fun channel():Long {
        ownRows(TvContract.Channels.CONTENT_URI).firstOrNull { it.key==PREFIX+"channel" }?.let { return it.id }
        val values=ContentValues().apply {
            put("type",TvContract.Channels.TYPE_PREVIEW);put("display_name","Continue Watching")
            put("internal_provider_id",PREFIX+"channel")
            put("app_link_intent_uri",Intent(context,MainActivity::class.java).toUri(Intent.URI_INTENT_SCHEME))
        }
        val uri=resolver.insert(TvContract.Channels.CONTENT_URI,values) ?: error("Channel unavailable")
        val id=ContentUris.parseId(uri)
        runCatching {
            val bitmap=Bitmap.createBitmap(128,128,Bitmap.Config.ARGB_8888)
            context.getDrawable(R.drawable.pov_icon)?.apply { setBounds(0,0,128,128);draw(Canvas(bitmap)) }
            resolver.openOutputStream(TvContract.buildChannelLogoUri(id))?.use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
        }
        TvContract.requestChannelBrowsable(context,id)
        return id
    }
    /** An unavailable/locked provider never interrupts playback or clears other apps' rows. */
    fun publish(entries:List<Entry>) = synchronized(SYNC_LOCK) { runCatching {
        val wanted=entries.filter(::valid).distinctBy { it.key }.filter { it.percent<95 && !prefs.getStringSet("dismissed",emptySet()).orEmpty().contains(PREFIX+it.key) }
            .take(20)
        val channelId=if(enabled(context) && wanted.isNotEmpty()) channel() else null
        val map=JSONObject()
        for(table in listOf(TvContract.PreviewPrograms.CONTENT_URI,TvContract.WatchNextPrograms.CONTENT_URI)) {
            val old=ownRows(table).toMutableList()
            if(enabled(context)) wanted.forEachIndexed { index,entry ->
                val internal=PREFIX+entry.key
                val previous=old.firstOrNull { it.key==internal };if(previous!=null) old.remove(previous)
                val values=values(entry)
                if(table==TvContract.PreviewPrograms.CONTENT_URI) {
                    if(channelId==null) return@forEachIndexed
                    values.put("channel_id",channelId);values.put("weight",wanted.size-index)
                } else {
                    values.put("watch_next_type",if(entry.percent<=0 && entry.identity.type=="series") TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_NEXT else TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE)
                    val fingerprint="${entry.identity.season}:${entry.identity.episode}:${entry.percent.toInt()}"
                    val stampKey="engagement:${entry.key}"
                    val stamp=if(prefs.getString("fingerprint:${entry.key}","")==fingerprint) prefs.getLong(stampKey,System.currentTimeMillis()-index*1000L)
                        else entry.playedAt.takeIf { it>0 } ?: (System.currentTimeMillis()-index*1000L)
                    prefs.edit().putString("fingerprint:${entry.key}",fingerprint).putLong(stampKey,stamp).apply()
                    values.put("last_engagement_time_utc_millis",stamp)
                }
                val uri=previous?.let { ContentUris.withAppendedId(table,it.id) }
                val id=if(uri!=null && resolver.update(uri,values,null,null)>0) previous!!.id
                    else resolver.insert(table,values)?.let(ContentUris::parseId) ?: return@forEachIndexed
                map.put("${table.lastPathSegment}:$id",internal)
            }
            old.forEach { resolver.delete(ContentUris.withAppendedId(table,it.id),null,null) }
        }
        if(!enabled(context)) ownRows(TvContract.Channels.CONTENT_URI).forEach { resolver.delete(TvContract.buildChannelUri(it.id),null,null) }
        prefs.edit().putString("published",map.toString()).putString("status","Synced ${if(enabled(context)) wanted.size else 0} titles").apply()
    }.onFailure { prefs.edit().putString("status","TV launcher integration unavailable").apply() }.isSuccess }
    fun sync(refreshRemote:Boolean=false) = synchronized(SYNC_LOCK) { syncInternal(refreshRemote) }
    private fun syncInternal(refreshRemote:Boolean) {
        if(!supported(context)) return
        val store=SecureStore(context)
        val selected=Trackers.selected(store)
        val tracker:Tracker=if(Trackers.selected(store)==Trackers.SIMKL) SimklApi(store) else WeTrakrApi(store)
        val scope=if(tracker.connected()) "${Trackers.selected(store)}:${tracker.accountRevision()}" else "local"
        if(prefs.getString("scope","")!=scope) prefs.edit().putString("scope",scope).remove("dismissed").remove("published").apply()
        if(refreshRemote && tracker.connected() && enabled(context)) {
            val revision=tracker.accountRevision()
            runCatching { tracker.continueWatching() }.getOrNull()?.let { progress ->
                if(tracker.accountRevision()==revision && tracker.connected()) rememberRemote(context,revision,progress,notify=false)
            }
        }
        val history=PlaybackStore(context)
        val recent=history.recent()
        val local=recent.mapNotNull { saved -> saved.identity?.let { Entry(it,saved.position*100.0/saved.duration,saved.duration,history.launcherPlayedAt(it)) } }
        val entries=if(tracker.connected()) {
            val cached=runCatching { JSONObject(store.get(CACHE)) }.getOrNull()
            if(cached?.optString("revision")!=tracker.accountRevision()) emptyList() else {
                val remote=cached.optJSONArray("items") ?: JSONArray()
                val fetched=cached.optLong("fetchedAt",System.currentTimeMillis())
                val list=(0 until remote.length()).mapNotNull { i -> runCatching {
                    val value=remote.getJSONObject(i);val identity=PlaybackIdentity.fromJson(value.getJSONObject("id"))
                    val match=local.firstOrNull { it.identity==identity || (it.identity.imdbId==identity.imdbId && it.identity.type==identity.type && it.identity.season==identity.season && it.identity.episode==identity.episode) }
                    Entry(identity,value.getDouble("percent"),match?.duration ?: 0,0)
                }.getOrNull() }
                local.filter { it.playedAt>fetched }+list
            }
        } else local
        if(selected!=Trackers.selected(store)) return
        publish(entries.filterNot { history.isHidden(identity=it.identity) })
    }
    companion object {
        const val PREFS="tv_continue_watching"
        const val CACHE="wetrakr.continue_cache"
        const val PREFIX="pov-continue:"
        private const val JOB_ID=7341
        private val SYNC_LOCK=Any()
        private val worker=Executors.newSingleThreadScheduledExecutor { runnable -> Thread(runnable,"pov-tv-home").apply { isDaemon=true } }
        private var queued=false
        fun supported(context:Context)=!BuildConfig.MOBILE && context.packageManager.hasSystemFeature("android.software.leanback")
        fun enabled(context:Context)=context.getSharedPreferences(PREFS,0).getBoolean("enabled",true)
        fun valid(entry:Entry)=entry.identity.type in listOf("movie","series") && entry.identity.imdbId.matches(Regex("tt[0-9]{5,12}")) && entry.percent.isFinite() && entry.percent in 0.0..100.0 && (entry.identity.type!="series" || entry.identity.episode>0)
        @Synchronized fun request(context:Context) {
            if(!supported(context) || queued) return
            queued=true
            val app=context.applicationContext
            worker.schedule({ synchronized(this) { queued=false };TvContinueWatching(app).sync() },500,TimeUnit.MILLISECONDS)
        }
        fun setEnabled(context:Context,on:Boolean) { context.getSharedPreferences(PREFS,0).edit().putBoolean("enabled",on).apply();schedule(context);request(context) }
        fun schedule(context:Context) {
            if(!supported(context)) return
            val scheduler=context.getSystemService(JobScheduler::class.java)
            if(!enabled(context)) scheduler.cancel(JOB_ID)
            else if(scheduler.getPendingJob(JOB_ID)==null) runCatching { scheduler.schedule(JobInfo.Builder(JOB_ID,ComponentName(context,TvContinueJob::class.java))
                .setPeriodic(15*60_000L).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true).build()) }
        }
        fun rememberRemote(context:Context,revision:String,items:List<Pair<PlaybackIdentity,Double>>,notify:Boolean=true) {
            SecureStore(context).put(CACHE,JSONObject().put("revision",revision).put("fetchedAt",System.currentTimeMillis())
                .put("items",JSONArray(items.map { (identity,percent) -> JSONObject().put("id",identity.toJson()).put("percent",percent) })).toString())
            if(notify) request(context)
        }
        fun dismiss(context:Context,table:String,id:Long) {
            val prefs=context.getSharedPreferences(PREFS,0)
            val internal=runCatching { JSONObject(prefs.getString("published","{}")!!).optString("$table:$id") }.getOrNull()?.takeIf { it.startsWith(PREFIX) } ?: return
            val hidden=prefs.getStringSet("dismissed",emptySet()).orEmpty().toMutableSet();hidden+=internal
            prefs.edit().putStringSet("dismissed",hidden).apply();request(context)
        }
        fun allow(context:Context,identity:PlaybackIdentity) {
            val prefs=context.getSharedPreferences(PREFS,0)
            val hidden=prefs.getStringSet("dismissed",emptySet()).orEmpty().toMutableSet()
            if(hidden.remove(PREFIX+"${identity.type}:${identity.imdbId}")) prefs.edit().putStringSet("dismissed",hidden).apply()
        }
    }
}
