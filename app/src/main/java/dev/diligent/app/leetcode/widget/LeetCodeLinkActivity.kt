package dev.diligent.app.leetcode.widget

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle

/**
 * Invisible hop from a widget tap to a leetcode.com page. Widgets need an explicit target
 * (a mutable PendingIntent with an implicit VIEW intent is rejected on Android 14+), so the
 * widget opens this activity, which opens the browser or LeetCode app and finishes at once.
 * Not exported, and it only opens https://leetcode.com links.
 */
class LeetCodeLinkActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent?.getStringExtra(EXTRA_URL)?.let(Uri::parse)
        if (uri != null && uri.scheme == "https" && uri.host == "leetcode.com") {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: ActivityNotFoundException) {
                // No browser installed
            }
        }
        finish()
    }

    companion object {
        private const val EXTRA_URL = "url"

        fun intent(context: Context, url: String): Intent =
            Intent(context, LeetCodeLinkActivity::class.java)
                .putExtra(EXTRA_URL, url)
                // Unique data so each link keeps its own PendingIntent.
                .setData(Uri.parse("diligent-link://leetcode/" + url.hashCode()))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
