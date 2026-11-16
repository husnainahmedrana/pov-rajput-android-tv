package dev.pov.tv

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.widget.Button

/**
 * A player row action (Audio & Subtitles, Speed, Screen, Streams, Info, Episodes). On TV it shows only its icon until it
 * has focus, then its label too, so the row fits on screen in every language (Netflix / Google TV style). The phone
 * keeps its labels. The label is always the spoken description.
 */
internal class PlayerActionButton @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = android.R.attr.buttonStyle
) : Button(context, attrs, defStyleAttr) {
    // Set while the superclass constructor runs (its setText), so no initializers here: they would run afterwards.
    private var label: CharSequence? = null
    private var gap = 0
    private var ready = false

    init {
        label = super.getText()
        gap = compoundDrawablePadding
        ready = true
        show()
    }

    override fun setText(text: CharSequence?, type: BufferType?) {
        if (!ready) { super.setText(text, type); return }
        label = text
        show()
    }

    override fun onFocusChanged(focused: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(focused, direction, previouslyFocusedRect)
        show()
    }

    /** The full label, whether or not it is shown right now. */
    val fullLabel: CharSequence get() = label ?: ""

    private fun show() {
        val open = !DeviceLayout.tv || isFocused || label.isNullOrEmpty()
        super.setText(if (open) label else "", BufferType.NORMAL)
        compoundDrawablePadding = if (open) gap else 0
        contentDescription = label
    }
}
