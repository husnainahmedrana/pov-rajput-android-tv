package dev.pov.tv

import android.content.Intent
import android.content.Context
import android.net.Uri
import dev.pov.tv.data.PlaybackIdentity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class) @Config(sdk=[28])
class LauncherResumeLinkTest {
    private val context:Context get()=RuntimeEnvironment.getApplication()
    @Test fun `episode resume survives launcher Intent serialization and preserves unicode title`() {
        val identity=PlaybackIdentity("tt0903747","series","Breaking Bad · S1",1,3)
        val serialized=LauncherResumeLink.intent(context,identity).toUri(Intent.URI_INTENT_SCHEME)
        val parsed=Intent.parseUri(serialized,Intent.URI_INTENT_SCHEME)
        assertEquals(identity,LauncherResumeLink.parse(context,parsed))
        assertEquals(context.packageName,parsed.component!!.packageName)
    }
    @Test fun `other app links and malformed identities are rejected`() {
        for(address in listOf("other://continue/movie/tt1375666","${context.packageName}://continue/movie/private","${context.packageName}://continue/series/tt0903747?episode=0","${context.packageName}://continue/cloud/tt1375666"))
            assertNull(LauncherResumeLink.parse(context,Intent(Intent.ACTION_VIEW,Uri.parse(address))))
        assertNull(LauncherResumeLink.parse(context,Intent(Intent.ACTION_SEND,LauncherResumeLink.uri(context,PlaybackIdentity("tt1375666","movie","Inception")))))
    }
}
