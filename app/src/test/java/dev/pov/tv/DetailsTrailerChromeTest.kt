package dev.pov.tv

import android.os.Looper
import android.view.KeyEvent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class) @Config(sdk=[28])
class DetailsTrailerChromeTest {
    private fun waitMs(ms:Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun key(action:Int,code:Int=KeyEvent.KEYCODE_DPAD_CENTER,repeat:Int=0) = KeyEvent(0,0,action,code,repeat)
    @Test fun `only playing details fade after two seconds`() {
        var eligible=false;var hides=0
        val chrome=DetailsTrailerChrome({eligible},{hides++},{})
        chrome.playbackChanged(true);waitMs(3000);assertEquals(0,hides)
        chrome.playbackChanged(false);eligible=true;chrome.playbackChanged(true)
        waitMs(1999);assertFalse(chrome.hidden);waitMs(1);assertTrue(chrome.hidden);assertEquals(1,hides)
        chrome.stop()
    }
    @Test fun `first OK reveals without clicking and second OK works normally`() {
        var shows=0
        val chrome=DetailsTrailerChrome({true},{},{shows++})
        chrome.playbackChanged(true);waitMs(2000)
        assertTrue(chrome.key(key(KeyEvent.ACTION_DOWN)))
        assertFalse(chrome.hidden);assertEquals(1,shows)
        assertTrue(chrome.key(key(KeyEvent.ACTION_UP)))
        assertFalse(chrome.key(key(KeyEvent.ACTION_DOWN)))
        assertFalse(chrome.key(key(KeyEvent.ACTION_UP)))
        waitMs(2000);assertTrue(chrome.hidden);chrome.stop()
    }
    @Test fun `holding the reveal key does not click or hide until released`() {
        val chrome=DetailsTrailerChrome({true},{},{})
        chrome.playbackChanged(true);waitMs(2000)
        assertTrue(chrome.key(key(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_BACK)))
        waitMs(3000);assertFalse(chrome.hidden)
        assertTrue(chrome.key(key(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_BACK,4)))
        assertTrue(chrome.key(key(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_BACK)))
        waitMs(1999);assertFalse(chrome.hidden);waitMs(1);assertTrue(chrome.hidden);chrome.stop()
    }
    @Test fun `ending trailer restores chrome and cancels idle work`() {
        var shows=0
        val chrome=DetailsTrailerChrome({true},{},{shows++})
        chrome.playbackChanged(true);waitMs(2000)
        chrome.playbackChanged(false);assertFalse(chrome.hidden);assertEquals(1,shows)
        waitMs(5000);assertFalse(chrome.hidden)
        chrome.playbackChanged(true);chrome.stop();waitMs(5000);assertFalse(chrome.hidden)
    }
}
