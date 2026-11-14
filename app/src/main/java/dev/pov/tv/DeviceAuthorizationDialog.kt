package dev.pov.tv

import android.app.Activity
import android.app.AlertDialog
import android.content.DialogInterface
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.content.Intent
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import dev.pov.tv.data.ApiException
import dev.pov.tv.data.DeviceAuth
import java.net.URI
import java.util.Locale
import java.util.concurrent.Executor

/** Owns the whole linking interaction, independent of the page's scrolling/loading state. */
internal class DeviceAuthorizationDialog(
    private val activity: Activity,
    private val service: String,
    private val executor: Executor,
    private val start: () -> DeviceAuth,
    private val poll: (DeviceAuth) -> Boolean,
    private val onConnected: () -> Unit,
    private val onCancel: (DeviceAuth?) -> Unit = {},
    private val configure: (() -> Unit)? = null
) {
    private val handler = Handler(Looper.getMainLooper())
    private var dialog: AlertDialog? = null
    @Volatile private var active = false
    @Volatile private var attempt = 0
    private var auth: DeviceAuth? = null
    private var deadline = 0L
    private var pollTask: Runnable? = null
    private var tickTask: Runnable? = null
    private lateinit var message: TextView
    private lateinit var address: TextView
    private lateinit var code: TextView
    private lateinit var status: TextView
    private lateinit var spinner: ProgressBar
    private lateinit var qr: ImageView

    val isShowing: Boolean get() = dialog?.isShowing == true

    fun show() {
        if (isShowing || activity.isFinishing || activity.isDestroyed) return
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(25), dp(18), dp(25), dp(18))
        }
        message = label(17)
        address = label(21).apply { setTextColor(Color.rgb(101, 226, 237)) }
        code = label(38).apply { typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); letterSpacing = 0.12f }
        status = label(15).apply { setTextColor(Color.LTGRAY) }
        spinner = ProgressBar(activity)
        qr = ImageView(activity).apply { visibility = View.GONE; contentDescription = activity.getString(R.string.t_qr_code_for_the_x_sign_in_page, service) }
        val details = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        details.addView(message)
        details.addView(address, params(12))
        details.addView(code, params(14))
        // The QR sits beside the code on a TV and above it on a phone.
        val row = LinearLayout(activity).apply {
            orientation = if (DeviceLayout.phone) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(qr, LinearLayout.LayoutParams(dp(QR_DP), dp(QR_DP)).apply {
            if (DeviceLayout.phone) { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(14) } else rightMargin = dp(24)
        })
        row.addView(details, if (DeviceLayout.phone) LinearLayout.LayoutParams(-1, -2) else LinearLayout.LayoutParams(0, -2, 1f))
        content.addView(row, LinearLayout.LayoutParams(-1, -2))
        content.addView(status, params(14))
        content.addView(spinner, LinearLayout.LayoutParams(dp(34), dp(34)).apply { topMargin = dp(14) })
        val scroll = ScrollView(activity).apply { addView(content); isFillViewport = true }
        val builder = AlertDialog.Builder(activity).setTitle(activity.getString(R.string.t_connect_x, service)).setView(scroll)
            .setPositiveButton(activity.getString(R.string.t_da_retry), null).setNegativeButton(activity.getString(R.string.t_da_cancel), null)
        if (configure != null) builder.setNeutralButton(activity.getString(R.string.t_set_client_id), null)
        dialog = builder.create().also { window ->
            window.setCanceledOnTouchOutside(false)
            window.setOnDismissListener {
                if (active) cancelAttempt()
                active = false
            }
            window.show()
            window.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener { begin() }
            window.getButton(DialogInterface.BUTTON_NEGATIVE).setOnClickListener { dismiss() }
            if (configure != null) window.getButton(DialogInterface.BUTTON_NEUTRAL).setOnClickListener {
                dismiss(); configure.invoke()
            }
        }
        active = true
        begin()
    }

    fun dismiss() {
        if (active) cancelAttempt()
        active = false
        dialog?.dismiss()
    }

    private fun begin() {
        if (!active) return
        cancelAttempt()
        val request = ++attempt
        message.text = activity.getString(R.string.t_requesting_a_x_activation_code, service)
        message.setTextColor(Color.WHITE)
        address.text = ""; code.text = ""
        address.visibility = View.GONE; code.visibility = View.GONE; qr.visibility = View.GONE
        status.text = activity.getString(R.string.t_keep_this_window_open_this_can)
        spinner.visibility = View.VISIBLE
        dialog?.getButton(DialogInterface.BUTTON_POSITIVE)?.visibility = View.GONE
        dialog?.getButton(DialogInterface.BUTTON_NEUTRAL)?.visibility = View.GONE
        submit(request, start) { value ->
            val uri = try { URI(value.verificationUrl) } catch (_: Exception) { null }
            if (value.deviceCode.isBlank() || value.userCode.isBlank() || value.expiresSeconds !in 1..86400 ||
                value.intervalSeconds !in 1..300 || uri?.scheme != "https" || uri.host.isNullOrBlank() || uri.userInfo != null) {
                fail(ApiException(0, activity.getString(R.string.t_x_did_not_return_a_usable_code, service)))
                return@submit
            }
            auth = value
            deadline = SystemClock.elapsedRealtime() + value.expiresSeconds * 1000L
            spinner.visibility = View.GONE
            message.text = if (DeviceLayout.phone) activity.getString(R.string.t_tap_the_address_the_code_is_copied)
                else activity.getString(R.string.t_scan_the_qr_code_with_your_phone)
            address.text = value.verificationUrl; address.visibility = View.VISIBLE
            // On a phone the code is copied and the sign-in page opens on the same device, ready to paste.
            if (DeviceLayout.phone) {
                address.setOnClickListener {
                    copyCode(value.userCode)
                    try { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(value.verificationUrl))) } catch (_: Exception) { }
                }
                code.setOnClickListener { copyCode(value.userCode) }
            }
            try { qr.setImageBitmap(qrBitmap(value.verificationUrl, dp(QR_DP))); qr.visibility = View.VISIBLE } catch (_: Exception) { qr.visibility = View.GONE }
            code.text = value.userCode; code.visibility = View.VISIBLE
            tick(request)
            schedulePoll(request, value.intervalSeconds * 1000L)
        }
    }

    private fun tick(request: Int) {
        if (!current(request)) return
        val remaining = deadline - SystemClock.elapsedRealtime()
        if (remaining <= 0) { fail(ApiException(410, activity.getString(R.string.t_this_x_activation_code_expired, service))); return }
        val seconds = (remaining + 999) / 1000
        status.text = activity.getString(R.string.t_waiting_for_approval_x_remaining, String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60))
        tickTask = Runnable { tick(request) }.also { handler.postDelayed(it, 1000) }
    }

    private fun schedulePoll(request: Int, delay: Long) {
        if (!current(request)) return
        pollTask = Runnable {
            if (!current(request)) return@Runnable
            if (SystemClock.elapsedRealtime() >= deadline) {
                fail(ApiException(410, activity.getString(R.string.t_this_x_activation_code_expired, service))); return@Runnable
            }
            val value = auth ?: return@Runnable
            submit(request, { poll(value) }, onFailure = { error ->
                if (error is ApiException && error.code == 429) {
                    status.text = activity.getString(R.string.t_x_asked_us_to_wait_code_valid, service)
                    schedulePoll(request, maxOf((value.intervalSeconds + 5) * 1000L, error.retryAfterSeconds * 1000L))
                } else if (transient(error)) {
                    // A phone may block network while the browser is open; keep checking until the code expires.
                    schedulePoll(request, value.intervalSeconds * 1000L)
                } else fail(error)
            }) { connected ->
                if (connected) {
                    stopTimers()
                    status.text = activity.getString(R.string.t_x_connected_2, service)
                    // Closing a successful dialog must not cancel a completed authorization.
                    active = false
                    auth = null
                    dialog?.dismiss()
                    onConnected()
                } else schedulePoll(request, value.intervalSeconds * 1000L)
            }
        }.also { handler.postDelayed(it, delay) }
    }

    /** No connection, a timeout or a server hiccup: the code is still valid, so checking continues. */
    private fun transient(error: Exception) = error !is ApiException || error.code == 0 || error.code >= 500

    /** Checks at once, e.g. when the viewer returns from approving the code in a browser on the same phone. */
    fun pollNow() {
        if (!active || auth == null) return
        pollTask?.let(handler::removeCallbacks)
        schedulePoll(attempt, 0)
    }

    private fun fail(error: Exception) {
        if (!active) return
        cancelAttempt()
        spinner.visibility = View.GONE
        address.visibility = View.GONE; code.visibility = View.GONE; qr.visibility = View.GONE
        message.text = activity.getString(R.string.t_x_could_not_connect, service)
        message.setTextColor(Color.rgb(255, 185, 170))
        // Only our API's sanitized errors are shown; arbitrary exception messages can contain data.
        status.text = when (error) {
            is ApiException -> error.message?.takeIf { it.isNotBlank() } ?: activity.getString(R.string.t_no_activation_code_was_received)
            is IllegalStateException -> activity.getString(R.string.t_saved_account_settings_could_not_be)
            else -> activity.getString(R.string.t_an_activation_code_could_not_be)
        }
        dialog?.getButton(DialogInterface.BUTTON_POSITIVE)?.visibility = View.VISIBLE
        dialog?.getButton(DialogInterface.BUTTON_NEUTRAL)?.visibility = if (configure != null) View.VISIBLE else View.GONE
        dialog?.getButton(DialogInterface.BUTTON_POSITIVE)?.requestFocus()
    }

    private fun <T> submit(request: Int, work: () -> T, onFailure: (Exception) -> Unit = ::fail, done: (T) -> Unit) {
        try {
            executor.execute {
                if (!current(request)) return@execute
                try {
                    val result = work()
                    handler.post { if (current(request)) done(result) }
                } catch (error: Exception) {
                    handler.post { if (current(request)) onFailure(error) }
                }
            }
        } catch (error: RuntimeException) { if (current(request)) onFailure(error) }
    }

    private fun cancelAttempt() {
        attempt++
        stopTimers()
        val previous = auth
        auth = null
        // Service cancellation uses a short state lock, never a network wait.
        onCancel(previous)
    }

    private fun stopTimers() {
        pollTask?.let(handler::removeCallbacks); pollTask = null
        tickTask?.let(handler::removeCallbacks); tickTask = null
    }
    private fun current(request: Int) = active && request == attempt && !activity.isFinishing && !activity.isDestroyed
    private fun label(size: Int) = TextView(activity).apply { textSize = size.toFloat(); setTextColor(Color.WHITE) }
    private fun params(top: Int) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) }
    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()

    private fun copyCode(value: String) {
        val clipboard = activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("$service code", value))
        // Android 13+ shows its own copy confirmation.
        if (android.os.Build.VERSION.SDK_INT < 33) android.widget.Toast.makeText(activity, activity.getString(R.string.t_code_copied), android.widget.Toast.LENGTH_SHORT).show()
    }

    /** Black-on-white QR of the sign-in address, with a one-module quiet zone. */
    private fun qrBitmap(text: String, size: Int): Bitmap {
        val matrix = com.google.zxing.qrcode.QRCodeWriter().encode(text, com.google.zxing.BarcodeFormat.QR_CODE, size, size,
            mapOf(com.google.zxing.EncodeHintType.MARGIN to 1))
        val width = matrix.width; val height = matrix.height
        val pixels = IntArray(width * height) { index -> if (matrix[index % width, index / width]) Color.BLACK else Color.WHITE }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private companion object { const val QR_DP = 150 }
}
