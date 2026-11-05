package dev.pov.tv

import android.content.*
import android.database.Cursor
import android.database.MatrixCursor
import android.media.tv.TvContract
import android.net.Uri
import android.os.Bundle
import dev.pov.tv.data.PlaybackIdentity
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class) @Config(sdk=[28])
class TvContinueWatchingTest {
    private val context get()=RuntimeEnvironment.getApplication()
    private lateinit var provider: FakeTvProvider
    private val movie=TvContinueWatching.Entry(PlaybackIdentity("tt1375666","movie","Inception"),32.0,600000,100000)
    @Before fun setup() { provider=FakeTvProvider(context.packageName);ShadowContentResolver.registerProviderInternal("android.media.tv",provider) }
    @Test fun `updates deduplicated own rows without touching another app and removes completed items`() {
        provider.add("watch_next_program","other.app","other:movie")
        val sync=TvContinueWatching(context)
        assertTrue(sync.publish(listOf(movie,movie.copy(percent=10.0))))
        assertEquals(1,provider.ours("watch_next_program").size);assertEquals(1,provider.ours("preview_program").size)
        val id=provider.ours("watch_next_program").single().getAsLong("_id")
        assertTrue(sync.publish(listOf(movie.copy(percent=58.0))))
        assertEquals(id,provider.ours("watch_next_program").single().getAsLong("_id"))
        assertTrue(sync.publish(listOf(movie.copy(percent=99.0),movie)))
        assertTrue(provider.ours("watch_next_program").isEmpty());assertEquals(1,provider.rows["watch_next_program"]!!.size)
    }
    @Test fun `launcher dismissal persists until deliberate new playback and disabling clears own channels`() {
        val sync=TvContinueWatching(context);assertTrue(sync.publish(listOf(movie)))
        val id=provider.ours("watch_next_program").single().getAsLong("_id")
        TvContinueWatching.dismiss(context,"watch_next_program",id)
        assertTrue(sync.publish(listOf(movie)));assertTrue(provider.ours("preview_program").isEmpty())
        TvContinueWatching.allow(context,movie.identity);assertTrue(sync.publish(listOf(movie)))
        assertEquals(1,provider.ours("preview_program").size)
        context.getSharedPreferences(TvContinueWatching.PREFS,0).edit().putBoolean("enabled",false).commit()
        assertTrue(sync.publish(listOf(movie)));assertTrue(provider.ours("watch_next_program").isEmpty());assertTrue(provider.ours("channel").isEmpty())
    }
    private class FakeTvProvider(val owner:String):ContentProvider() {
        val rows=mutableMapOf<String,MutableList<ContentValues>>()
        private var next=1L
        fun ours(table:String)=rows[table].orEmpty().filter { it.getAsString("package_name")==owner }
        fun add(table:String,packageName:String,key:String):Uri=insert(Uri.parse("content://android.media.tv/$table"),ContentValues().apply { put("package_name",packageName);put("internal_provider_id",key) })!!
        override fun onCreate()=true
        override fun getType(uri:Uri)="vnd.android.cursor.dir/test"
        override fun call(method:String,arg:String?,extras:Bundle?)=Bundle()
        override fun insert(uri:Uri,values:ContentValues?):Uri {
            val table=uri.pathSegments.first();val data=ContentValues(values ?: ContentValues()).apply { put("_id",next++);if(!containsKey("package_name")) put("package_name",owner) }
            rows.getOrPut(table){mutableListOf()}.add(data);return ContentUris.withAppendedId(uri,data.getAsLong("_id"))
        }
        override fun query(uri:Uri,projection:Array<out String>?,selection:String?,args:Array<out String>?,sort:String?):Cursor {
            check(selection==null);val columns=projection ?: arrayOf("_id","package_name","internal_provider_id")
            return MatrixCursor(columns).apply { rows[uri.pathSegments.first()].orEmpty().forEach { row -> addRow(columns.map { row.get(it) }.toTypedArray()) } }
        }
        override fun update(uri:Uri,values:ContentValues?,selection:String?,args:Array<out String>?):Int {
            check(selection==null);val row=rows[uri.pathSegments.first()]?.firstOrNull { it.getAsLong("_id")==ContentUris.parseId(uri) } ?: return 0
            row.putAll(values ?: ContentValues());return 1
        }
        override fun delete(uri:Uri,selection:String?,args:Array<out String>?):Int { check(selection==null);return if(rows[uri.pathSegments.first()]?.removeAll { it.getAsLong("_id")==ContentUris.parseId(uri) }==true) 1 else 0 }
    }
}
