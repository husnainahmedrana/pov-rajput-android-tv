package dev.pov.tv

import android.content.Context
import android.content.Intent
import android.net.Uri
import dev.pov.tv.data.PlaybackIdentity

/** Launcher links contain identity only; never stream URLs, source headers or account keys. */
internal object LauncherResumeLink {
    fun uri(context: Context, identity: PlaybackIdentity): Uri = Uri.Builder().scheme(context.packageName).authority("continue")
        .appendPath(identity.type).appendPath(identity.imdbId).appendQueryParameter("title",identity.title.take(500))
        .appendQueryParameter("season",identity.season.toString()).appendQueryParameter("episode",identity.episode.toString()).build()
    fun intent(context: Context, identity: PlaybackIdentity) = Intent(Intent.ACTION_VIEW,uri(context,identity),context,MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    fun parse(context: Context, intent: Intent): PlaybackIdentity? {
        val uri=intent.data ?: return null
        if (intent.action!=Intent.ACTION_VIEW || uri.scheme!=context.packageName || uri.host!="continue" || uri.userInfo!=null) return null
        val path=uri.pathSegments
        if (path.size!=2 || path[0] !in listOf("movie","series") || !path[1].matches(Regex("tt[0-9]{5,12}"))) return null
        val season=uri.getQueryParameter("season")?.toIntOrNull() ?: 0
        val episode=uri.getQueryParameter("episode")?.toIntOrNull() ?: 0
        if (season !in 0..1000 || episode !in 0..10000 || (path[0]=="series" && episode==0)) return null
        val title=uri.getQueryParameter("title").orEmpty().take(500).ifBlank { path[1] }
        return PlaybackIdentity(path[1],path[0],title,if(path[0]=="series") season else 0,if(path[0]=="series") episode else 0)
    }
}
