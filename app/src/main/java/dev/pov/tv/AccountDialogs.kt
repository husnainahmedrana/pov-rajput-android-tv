package dev.pov.tv

import android.app.Activity
import android.app.AlertDialog
import android.content.DialogInterface
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import dev.pov.tv.data.BackupCrypto
import dev.pov.tv.data.CloudAccount
import java.util.concurrent.Executor

/**
 * Sign-up, sign-in, recovery key, password change and reset for the POV account. Network and key work runs on
 * [worker]; feedback stays inside each dialog until it succeeds. [restart] reopens the app after a restore.
 */
internal class AccountDialogs(
    private val activity: Activity,
    private val account: CloudAccount,
    private val worker: Executor,
    private val changed: () -> Unit,
    private val restart: (String) -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private fun alive() = !activity.isFinishing && !activity.isDestroyed

    // ---------- entry points ----------

    /** Shown once, on the first start that has no account. */
    fun welcome() {
        if (!alive()) return
        account.welcomeDone = true
        chooser()
    }

    /** Create account / Sign in / Not now. Back from either form returns here. */
    private fun chooser() {
        if (!alive()) return
        val content = column()
        content.addView(label("Create a free POV account and your debrid services, add-ons, Home rows and watch progress are backed up, " +
            "encrypted on this device. Sign in on any TV or phone and everything comes back.", 14, Color.WHITE))
        content.addView(label("You can do this later in Settings → Account.", 13, MUTED), params(8))
        val dialog = AlertDialog.Builder(activity).setCustomTitle(titleBar("Keep your setup safe")).setView(scroll(content)).create()
        val create = choice("Create account", primary = true) { dialog.dismiss(); createAccount(back = ::chooser) }
        content.addView(create, params(20))
        content.addView(choice("I have an account · Sign in") { dialog.dismiss(); signIn(back = ::chooser) }, params(12))
        content.addView(choice("Not now") { dialog.dismiss() }, params(12).apply { bottomMargin = dp(8) })
        dialog.show()
        create.requestFocus()
    }

    /**
     * A full-width or inline button that is plainly focused: white with dark text under the remote's focus, the
     * accent red for the main choice, a soft grey otherwise.
     */
    private fun choice(text: String, primary: Boolean = false, action: () -> Unit) = TextView(activity).apply {
        this.text = text; textSize = 15f; gravity = Gravity.CENTER; typeface = Typeface.DEFAULT_BOLD
        setPadding(dp(18), dp(12), dp(18), dp(12))
        isFocusable = true; isFocusableInTouchMode = DeviceLayout.tv; isClickable = true
        fun paint(focused: Boolean) {
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(when { focused -> Color.WHITE; primary -> 0xFFE50914.toInt(); else -> 0x2EFFFFFF })
                if (focused) setStroke(dp(2), 0xFFE50914.toInt())
            }
            setTextColor(if (focused) Color.BLACK else Color.WHITE)
        }
        paint(false)
        setOnFocusChangeListener { _, focused -> paint(focused) }
        setOnClickListener { if (isEnabled) action() }
    }

    fun createAccount(email: String = "", password: String = "", back: (() -> Unit)? = null) {
        val form = Form("Create your POV account",
            "Your setup is encrypted on this device before it is backed up. Only your password or recovery key can open your debrid keys and add-ons.")
        val emailField = form.field("Email", Kind.EMAIL, email)
        val passwordField = form.field("Password (at least 8 characters)", Kind.PASSWORD, password)
        val confirmField = form.field("Password again", Kind.PASSWORD, password)
        if (DeviceLayout.tv) form.extra("Use phone") {
            phone { e, p -> emailField.setText(e); passwordField.setText(p); confirmField.setText(p); form.focusPositive() }
        }
        form.extra("I have an account") { val e = emailField.value(); val p = passwordField.text.toString(); form.dismiss(); signIn(e) { createAccount(e, p, back) } }
        form.back = back
        form.show("Continue") {
            val e = emailField.value(); val p = passwordField.text.toString(); val again = confirmField.text.toString()
            when {
                !CloudAccount.validEmail(e) -> form.error("Enter a valid email address.", emailField)
                CloudAccount.passwordProblem(p) != null -> form.error(CloudAccount.passwordProblem(p)!!, passwordField)
                p != again -> form.error("The two passwords are not the same.", confirmField)
                else -> form.run("Sending a code to $e…", { account.sendSignupCode(e) }) { form.dismiss(); signupCode(e, p, back) }
            }
        }
    }

    fun signIn(email: String = "", back: (() -> Unit)? = null) {
        val form = Form("Sign in to POV", "Sign in to bring back your setup from your backup.")
        val emailField = form.field("Email", Kind.EMAIL, email)
        val passwordField = form.field("Password", Kind.PASSWORD)
        if (DeviceLayout.tv) form.extra("Use phone") {
            phone { e, p -> emailField.setText(e); passwordField.setText(p); form.focusPositive(); form.submit() }
        }
        form.extra("Forgot password?") { val e = emailField.value(); form.dismiss(); forgotPassword(e) { signIn(e, back) } }
        form.extra("Create account") { val e = emailField.value(); form.dismiss(); createAccount(e) { signIn(e, back) } }
        form.back = back
        form.show("Sign in") {
            val e = emailField.value(); val p = passwordField.text.toString()
            when {
                !CloudAccount.validEmail(e) -> form.error("Enter a valid email address.", emailField)
                p.isEmpty() -> form.error("Enter your password.", passwordField)
                else -> form.run("Signing in… this takes a few seconds.", {
                    val restored = account.signIn(e, p)
                    if (restored.hasData) account.apply(restored) else account.backup(force = true)
                    restored.hasData
                }) { restoredSomething ->
                    form.dismiss()
                    if (restoredSomething) restart("Welcome back. Your setup was restored from your POV account.")
                    else { toast("Signed in. This device is now backed up to your account."); changed() }
                }
            }
        }
    }

    fun changePassword() {
        val form = Form("Change password", "Your backup stays as it is. Other devices signed in to this account will need the new password.")
        val current = form.field("Current password", Kind.PASSWORD)
        val next = form.field("New password", Kind.PASSWORD)
        val again = form.field("New password again", Kind.PASSWORD)
        form.show("Change") {
            val p = next.text.toString()
            when {
                current.text.isEmpty() -> form.error("Enter your current password.", current)
                CloudAccount.passwordProblem(p) != null -> form.error(CloudAccount.passwordProblem(p)!!, next)
                p != again.text.toString() -> form.error("The two new passwords are not the same.", again)
                else -> form.run("Changing your password…", { account.changePassword(current.text.toString(), p) }) {
                    form.dismiss(); toast("Password changed"); changed()
                }
            }
        }
    }

    fun backupNow() {
        toast("Backing up…")
        worker.execute {
            val message = try { account.backup(force = true); "Backed up to your POV account" } catch (error: Exception) { uiErrorMessage(error) }
            handler.post { if (alive()) { toast(message); changed() } }
        }
    }

    fun signOut() {
        if (!alive()) return
        AlertDialog.Builder(activity).setCustomTitle(titleBar("Sign out of ${account.email}?"))
            .setMessage("This device keeps its settings and stops backing up. Your backup stays in your account.")
            .setPositiveButton("Sign out") { _, _ ->
                worker.execute {
                    // A last backup first, so nothing changed since the previous one is lost.
                    try { if (account.autoBackup) account.backup() } catch (_: Exception) {}
                    account.forget()
                    handler.post { if (alive()) { toast("Signed out"); changed() } }
                }
            }.setNegativeButton("Cancel", null).show()
    }

    fun deleteAccount() {
        if (!alive()) return
        AlertDialog.Builder(activity).setCustomTitle(titleBar("Delete your POV account?"))
            .setMessage("Your account ${account.email} and its backup are removed from the server for good. This device keeps its settings.")
            .setPositiveButton("Delete account") { _, _ ->
                worker.execute {
                    val message = try { account.deleteAccount(); "Your POV account was deleted" } catch (error: Exception) { uiErrorMessage(error) }
                    handler.post { if (alive()) { toast(message); changed() } }
                }
            }.setNegativeButton("Cancel", null).show()
    }

    fun backupServer() {
        val form = Form("Backup server", "Use your own copy of the POV backup server (server/backup in the app's source code). Leave it empty for the default.")
        val field = form.field(BuildConfig.BACKUP_SERVER, Kind.TEXT, account.server.takeIf { it != BuildConfig.BACKUP_SERVER }.orEmpty())
        form.show("Save") {
            val value = field.value()
            if (value.isNotBlank() && !Regex("^https://[A-Za-z0-9.-]+(:\\d+)?(/[A-Za-z0-9._~/-]*)?$").matches(value)) form.error("Enter an https:// address.", field)
            else { account.server = value; form.dismiss(); changed() }
        }
    }

    // ---------- sign-up steps ----------

    private fun signupCode(email: String, password: String, back: (() -> Unit)?) {
        val form = Form("Check your email", "We sent a 6-digit code to $email. It works for 15 minutes. Check the spam folder if you don't see it.")
        val code = form.field("6-digit code", Kind.CODE)
        form.extra("Send a new code") { form.run("Sending a new code…", { account.sendSignupCode(email) }) { form.note("A new code is on its way.") } }
        form.back = { createAccount(email, password, back) }
        form.show("Create account") {
            if (code.value().length != 6) form.error("Enter the 6-digit code from the email.", code)
            else form.run("Creating your account and backing up…", { account.signUp(email, password, code.value()) }) { recovery ->
                form.dismiss()
                recoveryKey(recovery) { toast("Your POV account is ready. This device is backed up."); changed() }
            }
        }
    }

    /** The recovery key, shown once. The QR lets a phone camera keep a copy. */
    private fun recoveryKey(key: String, done: () -> Unit) {
        if (!alive()) { done(); return }
        val content = column()
        content.addView(label("Save your recovery key. If you ever forget your password, it brings back everything, including your debrid services and add-ons. " +
            "Without it, a password reset brings back only your settings.", 14, Color.WHITE))
        val row = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL; orientation = if (DeviceLayout.phone) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL }
        row.addView(label(key, 22, Color.WHITE).apply { typeface = Typeface.MONOSPACE; setTextIsSelectable(DeviceLayout.phone); letterSpacing = 0.05f },
            LinearLayout.LayoutParams(if (DeviceLayout.phone) -1 else 0, -2, 1f))
        try {
            row.addView(ImageView(activity).apply {
                setImageBitmap(qr("POV recovery key: $key", dp(140))); setBackgroundColor(Color.WHITE); setPadding(dp(6), dp(6), dp(6), dp(6))
                contentDescription = "QR code of the recovery key"
            }, LinearLayout.LayoutParams(dp(140), dp(140)).apply { if (DeviceLayout.phone) topMargin = dp(12) else leftMargin = dp(16) })
        } catch (_: Exception) {}
        content.addView(row, params(14))
        content.addView(label(if (DeviceLayout.tv) "Take a photo of it, or scan the QR code with your phone and keep the text somewhere safe."
            else "Take a screenshot or write it down, and keep it somewhere safe.", 13, MUTED), params(14))
        val dialog = AlertDialog.Builder(activity).setCustomTitle(titleBar("Your recovery key")).setView(scroll(content))
            .setCancelable(false).setPositiveButton("I saved it") { _, _ -> done() }.show()
        // A moment to read it before it can be dismissed.
        val ok = dialog.getButton(DialogInterface.BUTTON_POSITIVE)
        ok.isEnabled = false
        handler.postDelayed({ if (dialog.isShowing) { ok.isEnabled = true; ok.requestFocus() } }, 3_000)
    }

    // ---------- password reset ----------

    private fun forgotPassword(email: String, back: (() -> Unit)? = null) {
        val form = Form("Reset your password", "We'll email you a code to set a new password.")
        val emailField = form.field("Email", Kind.EMAIL, email)
        form.back = back
        form.show("Send code") {
            val e = emailField.value()
            if (!CloudAccount.validEmail(e)) form.error("Enter a valid email address.", emailField)
            else form.run("Sending a code to $e…", { account.sendResetCode(e) }) { form.dismiss(); resetPassword(e) { forgotPassword(e, back) } }
        }
    }

    private fun resetPassword(email: String, back: (() -> Unit)? = null) {
        val form = Form("Set a new password", "Enter the code we sent to $email and a new password. If you have your recovery key, enter it too and " +
            "everything comes back. Without it your settings come back, and debrid services and add-ons need linking again.")
        val code = form.field("6-digit code", Kind.CODE)
        val next = form.field("New password", Kind.PASSWORD)
        val again = form.field("New password again", Kind.PASSWORD)
        val recovery = form.field("Recovery key (if you have it)", Kind.TEXT)
        var session: CloudAccount.ResetSession? = null
        form.extra("Send a new code") { form.run("Sending a new code…", { account.sendResetCode(email) }) { session = null; form.note("A new code is on its way.") } }
        form.back = back
        form.show("Reset password") {
            val p = next.text.toString(); val key = recovery.value()
            when {
                session == null && code.value().length != 6 -> form.error("Enter the 6-digit code from the email.", code)
                CloudAccount.passwordProblem(p) != null -> form.error(CloudAccount.passwordProblem(p)!!, next)
                p != again.text.toString() -> form.error("The two passwords are not the same.", again)
                key.isNotBlank() && !BackupCrypto.validRecoveryKey(key) -> form.error("A recovery key has 25 letters and numbers. Check it, or leave it empty.", recovery)
                else -> form.run("Setting your new password…", {
                    // A verified code is used once: keep the session if, say, the recovery key was mistyped.
                    val current = session ?: account.verifyReset(email, code.value()).also { session = it }
                    val result = account.finishReset(current, p, key.ifBlank { null })
                    account.apply(result.restored)
                    result
                }) { result ->
                    form.dismiss()
                    val message = if (key.isNotBlank()) "Password changed. Everything was restored from your POV account."
                        else "Password changed. Your settings were restored. Link your debrid services and add-ons again, and they'll be backed up."
                    val finish = { if (result.restored.hasData) restart(message) else { toast(message); changed() } }
                    result.newRecoveryKey?.let { recoveryKey(it, finish) } ?: finish()
                }
            }
        }
    }

    // ---------- phone sign-in ----------

    /** Shows a QR for the phone page; the phone's email and password arrive encrypted for this TV only. */
    private fun phone(filled: (String, String) -> Unit) {
        if (!alive()) return
        val link = account.PhoneLink()
        val content = column()
        val image = ImageView(activity).apply { visibility = View.GONE; setBackgroundColor(Color.WHITE); setPadding(dp(8), dp(8), dp(8), dp(8)); contentDescription = "QR code for the phone sign-in page" }
        val status = label("Preparing…", 14, MUTED)
        content.addView(label("Scan this with your phone's camera, then type your email and password there. They are encrypted on the phone for this TV only.", 14, Color.WHITE))
        content.addView(image, LinearLayout.LayoutParams(dp(190), dp(190)).apply { topMargin = dp(14); gravity = Gravity.CENTER_HORIZONTAL })
        content.addView(status, params(12))
        var open = true
        val dialog = AlertDialog.Builder(activity).setCustomTitle(titleBar("Use your phone")).setView(scroll(content))
            .setNegativeButton("Cancel", null).setOnDismissListener { open = false }.show()
        val started = System.currentTimeMillis()
        fun poll() {
            if (!open || !alive()) return
            if (System.currentTimeMillis() - started > 9 * 60_000) { status.text = "This code expired. Close and try again."; return }
            worker.execute {
                val outcome = runCatching { link.poll() }
                handler.post {
                    if (!open || !alive()) return@post
                    outcome.onSuccess { credentials ->
                        if (credentials == null) handler.postDelayed(::poll, 2_000)
                        else { dialog.dismiss(); filled(credentials.first, credentials.second) }
                    }.onFailure { error -> status.text = uiErrorMessage(error as? Exception ?: Exception()) }
                }
            }
        }
        worker.execute {
            val outcome = runCatching { link.start() }
            handler.post {
                if (!open || !alive()) return@post
                outcome.onSuccess { address ->
                    try { image.setImageBitmap(qr(address, dp(190))); image.visibility = View.VISIBLE } catch (_: Exception) {}
                    status.text = "Waiting for your phone…"
                    handler.postDelayed(::poll, 2_000)
                }.onFailure { error -> status.text = uiErrorMessage(error as? Exception ?: Exception()) }
            }
        }
    }

    // ---------- a small form dialog ----------

    private enum class Kind { EMAIL, PASSWORD, CODE, TEXT }

    private inner class Form(private val title: String, intro: String?) {
        private val content = column()
        private val fields = mutableListOf<TvEditText>()
        private val extras = LinearLayout(activity).apply { orientation = if (DeviceLayout.phone) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL }
        private val status = label("", 14, Color.WHITE).apply { visibility = View.GONE; accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        private var dialog: AlertDialog? = null
        private var busy = false
        private var submitAction: (() -> Unit)? = null
        /** The previous step: the Back button and the remote's Back key go there instead of closing. */
        var back: (() -> Unit)? = null

        init { intro?.let { content.addView(label(it, 13, MUTED), params(0)) } }

        fun field(hint: String, kind: Kind, initial: String = ""): TvEditText {
            val field = TvEditText(activity).apply {
                this.hint = hint; setSingleLine(); isSaveEnabled = false; setText(initial)
                inputType = when (kind) {
                    Kind.EMAIL -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                    Kind.PASSWORD -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                    Kind.CODE -> InputType.TYPE_CLASS_NUMBER
                    Kind.TEXT -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                }
            }
            // Our own box instead of the theme's inset one, so fields line up with the text and buttons.
            fun paint(focused: Boolean) {
                field.background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dp(10).toFloat(); setColor(0x1FFFFFFF)
                    setStroke(dp(if (focused) 2 else 1), if (focused) Color.WHITE else 0x40FFFFFF)
                }
            }
            paint(false)
            field.setOnFocusChangeListener { _, focused -> paint(focused) }
            field.setPadding(dp(14), dp(12), dp(14), dp(12)); field.textSize = 16f
            field.setTextColor(Color.WHITE); field.setHintTextColor(0x99FFFFFF.toInt())
            // Password fields default to a monospace font; keep the same type as the others.
            field.typeface = Typeface.DEFAULT
            fields += field
            content.addView(field, params(if (fields.size == 1) 16 else 10))
            return field
        }

        fun extra(text: String, action: () -> Unit) {
            val phone = DeviceLayout.phone
            // TV: the extra buttons share the row equally, one line each.
            if (!phone && extras.childCount > 0) extras.addView(View(activity), LinearLayout.LayoutParams(dp(10), 1))
            extras.addView(choice(text) { if (!busy) action() }.apply {
                textSize = 14f; setPadding(dp(10), dp(10), dp(10), dp(10)); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            }, if (phone) LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) } else LinearLayout.LayoutParams(0, -2, 1f))
        }

        fun show(positive: String, submit: () -> Unit) {
            if (!alive()) return
            submitAction = submit
            content.addView(status, params(10))
            if (extras.childCount > 0) content.addView(extras, params(14))
            val window = AlertDialog.Builder(activity).setCustomTitle(titleBar(title)).setView(scroll(content))
                .setPositiveButton(positive, null).setNegativeButton(if (back != null) "Back" else "Cancel", null).create()
            window.setCanceledOnTouchOutside(false)
            dialog = window
            window.show()
            window.submitOnEnter(*fields.toTypedArray())
            window.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener { submit() }
            window.getButton(DialogInterface.BUTTON_NEGATIVE).setOnClickListener { if (!busy) { window.dismiss(); back?.invoke() } }
            window.setOnCancelListener { back?.invoke() }
            fields.firstOrNull { it.text.isNullOrEmpty() }?.requestFocus()
        }

        fun submit() { if (!busy) submitAction?.invoke() }
        fun focusPositive() { dialog?.getButton(DialogInterface.BUTTON_POSITIVE)?.requestFocus() }
        fun dismiss() { dialog?.dismiss() }

        fun error(message: String, field: TvEditText? = null) {
            status.setTextColor(0xFFFF8A80.toInt()); status.text = message; status.visibility = View.VISIBLE
            field?.requestFocus()
        }
        fun note(message: String) { status.setTextColor(MUTED); status.text = message; status.visibility = View.VISIBLE }

        fun <T> run(working: String, work: () -> T, done: (T) -> Unit) {
            if (busy) return
            val window = dialog ?: return
            busy = true; setEnabled(false); note(working)
            worker.execute {
                val outcome = try { Result.success(work()) } catch (error: Exception) { Result.failure(error) }
                handler.post {
                    if (!window.isShowing || !alive()) return@post
                    busy = false; setEnabled(true)
                    outcome.onSuccess { status.visibility = View.GONE; done(it) }
                        .onFailure { error(uiErrorMessage(it as? Exception ?: Exception())); focusPositive() }
                }
            }
        }

        private fun setEnabled(enabled: Boolean) {
            fields.forEach { it.isEnabled = enabled }
            (0 until extras.childCount).forEach { extras.getChildAt(it).apply { isEnabled = enabled; alpha = if (enabled) 1f else 0.5f } }
            dialog?.getButton(DialogInterface.BUTTON_POSITIVE)?.isEnabled = enabled
        }
    }

    private fun TvEditText.value() = text.toString().trim()

    // ---------- views ----------

    private fun column() = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        val inset = dp(20); setPadding(inset, dp(8), inset, dp(8))
    }
    /** The dialog title with a small POV logo in the top-right corner. */
    private fun titleBar(title: String) = LinearLayout(activity).apply {
        gravity = Gravity.CENTER_VERTICAL; setPadding(dp(24), dp(20), dp(20), dp(4))
        addView(label(title, 22, Color.WHITE).apply { typeface = Typeface.DEFAULT_BOLD }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(ImageView(activity).apply { setImageResource(R.drawable.pov_icon); scaleType = ImageView.ScaleType.FIT_CENTER; contentDescription = "POV" },
            LinearLayout.LayoutParams(dp(40), dp(40)).apply { leftMargin = dp(16) })
    }
    private fun scroll(view: View) = ScrollView(activity).apply { addView(view) }
    private fun label(text: String, size: Int, color: Int) = TextView(activity).apply { this.text = text; textSize = size.toFloat(); setTextColor(color); setLineSpacing(dp(2).toFloat(), 1f) }
    private fun params(top: Int) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) }
    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
    private fun toast(message: String) { if (alive()) Toast.makeText(activity, message, Toast.LENGTH_LONG).show() }

    private fun qr(text: String, size: Int): Bitmap {
        val matrix = com.google.zxing.qrcode.QRCodeWriter().encode(text, com.google.zxing.BarcodeFormat.QR_CODE, size, size,
            mapOf(com.google.zxing.EncodeHintType.MARGIN to 1))
        val width = matrix.width; val height = matrix.height
        val pixels = IntArray(width * height) { index -> if (matrix[index % width, index / width]) Color.BLACK else Color.WHITE }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private companion object { const val MUTED = 0xFFB3B3B3.toInt() }
}
