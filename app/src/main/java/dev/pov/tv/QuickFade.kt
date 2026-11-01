package dev.pov.tv

import android.app.Dialog
import android.view.KeyEvent
import android.view.View

/**
 * The app's pop-up fade: a quick fade in (0.12 s) when a menu or panel opens and out (0.1 s) when Back or Done closes
 * it. Faster than the system window fades, and the same everywhere (hold-OK menus, the player's panels).
 */
internal object QuickFade {
    private const val IN_MS = 120L
    private const val OUT_MS = 100L

    /** Call before showing [dialog]; [content] is its root view. */
    fun attach(dialog: Dialog, content: View) {
        dialog.window?.setWindowAnimations(0)
        content.alpha = 0f
        dialog.setOnKeyListener { _, keyCode, event ->
            if (keyCode != KeyEvent.KEYCODE_BACK) return@setOnKeyListener false
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) dismiss(dialog, content)
            true
        }
    }

    /** Call from the dialog's show listener. */
    fun fadeIn(content: View) { content.animate().alpha(1f).setDuration(IN_MS).start() }

    fun dismiss(dialog: Dialog, content: View) {
        if (!dialog.isShowing) return
        content.animate().alpha(0f).setDuration(OUT_MS).withEndAction { if (dialog.isShowing) dialog.dismiss() }.start()
    }
}
