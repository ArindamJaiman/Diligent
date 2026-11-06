package dev.diligent.app.cloud

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.launch
import java.security.MessageDigest

/**
 * Auto-send on copy (Hub → Clipboard): when Diligent comes to the front, whatever you copied
 * since goes to your clips. Android 10+ only lets the app in focus read the clipboard, so
 * this is the moment it can be done without a keyboard or accessibility hack.
 *
 * Never sent: Diligent's own copies (label "Diligent"), anything the copying app marked
 * sensitive (password managers on Android 13+), and the same text twice. Only a hash of the
 * last text seen is kept.
 */
object AutoSendClip {
    const val OWN_LABEL = "Diligent"
    private const val PREFS = "clipboard"
    private const val KEY_ON = "auto_send"
    private const val KEY_LAST = "auto_send_last"

    fun enabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ON, false)

    /** Turning it on marks what's on the clipboard now as seen, so it doesn't go out by surprise. */
    fun setEnabled(context: Context, on: Boolean) {
        val current = if (on) read(context)?.text else null
        val edit = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ON, on)
        if (current != null) edit.putString(KEY_LAST, hash(current))
        edit.apply()
    }

    fun hash(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).take(16).joinToString("") { "%02x".format(it) }

    /** Pure, so it's unit-tested. */
    fun shouldSend(enabled: Boolean, text: String?, label: String?, sensitive: Boolean, lastHash: String?): Boolean =
        enabled && !text.isNullOrBlank() && label != OWN_LABEL && !sensitive && hash(text) != lastHash

    private data class Copied(val text: String, val label: String?, val sensitive: Boolean)

    private fun read(context: Context): Copied? {
        val manager = context.getSystemService(ClipboardManager::class.java) ?: return null
        val clip = runCatching { manager.primaryClip }.getOrNull()?.takeIf { it.itemCount > 0 } ?: return null
        val text = clip.getItemAt(0).text?.toString() ?: return null // text only: no files or images
        val description = clip.description
        val sensitive = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            description?.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE, false) == true
        return Copied(text, description?.label?.toString(), sensitive)
    }

    /** From the main screen gaining focus. */
    fun onFocus(activity: ComponentActivity) {
        val app = activity.applicationContext
        if (!enabled(app)) return
        val service = EntryPointAccessors.fromApplication(app, ClipboardEntryPoint::class.java).clipboard()
        if (!service.ready) return
        val copied = read(activity) ?: return
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val send = shouldSend(true, copied.text, copied.label, copied.sensitive, prefs.getString(KEY_LAST, null))
        prefs.edit().putString(KEY_LAST, hash(copied.text)).apply()
        if (!send) return
        activity.lifecycleScope.launch {
            val result = runCatching { service.push(copied.text) }
            if (result.isSuccess) ClipCache.sent(app, copied.text)
            Toast.makeText(
                app,
                result.fold({ "Sent what you copied to your devices" }, { "Auto-send: " + (it.message ?: "couldn't send") }),
                Toast.LENGTH_SHORT
            ).show()
        }
    }
}
