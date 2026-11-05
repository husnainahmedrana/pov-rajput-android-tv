package dev.pov.tv

import android.app.job.JobService
import android.app.job.JobParameters
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class TvContinueJob: JobService() {
    @Volatile private var cancelled=false
    override fun onStartJob(params:JobParameters):Boolean {
        cancelled=false
        Thread({ try { if(!cancelled) TvContinueWatching(applicationContext).sync(refreshRemote=true) } finally { if(!cancelled) jobFinished(params,false) } },"pov-tv-home-job").start()
        return true
    }
    override fun onStopJob(params:JobParameters):Boolean { cancelled=true;return true }
}
class TvContinueReceiver: BroadcastReceiver() {
    override fun onReceive(context:Context,intent:Intent) {
        val id=intent.getLongExtra("android.media.tv.extra.WATCH_NEXT_PROGRAM_ID",-1)
        val preview=intent.getLongExtra("android.media.tv.extra.PREVIEW_PROGRAM_ID",-1)
        when(intent.action) {
            "android.media.tv.action.INITIALIZE_PROGRAMS" -> { TvContinueWatching.schedule(context);TvContinueWatching.request(context) }
            "android.media.tv.action.WATCH_NEXT_PROGRAM_BROWSABLE_DISABLED" -> if(id>=0) TvContinueWatching.dismiss(context,"watch_next_program",id)
            "android.media.tv.action.PREVIEW_PROGRAM_BROWSABLE_DISABLED" -> if(preview>=0) TvContinueWatching.dismiss(context,"preview_program",preview)
        }
    }
}
