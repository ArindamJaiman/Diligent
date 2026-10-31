package dev.diligent.app.automation

import android.app.Activity
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable

/**
 * Writes a diligent:// link to an NFC tag, so tapping the tag later runs it (a sticker on the
 * desk that toggles the Quantum timer, one on the gym bag that logs a session). Uses reader
 * mode while the "hold a tag" dialog is open; any blank NTAG / MIFARE Ultralight works.
 */
object NfcTagWriter {

    fun isAvailable(activity: Activity): Boolean = NfcAdapter.getDefaultAdapter(activity) != null

    fun isEnabled(activity: Activity): Boolean = NfcAdapter.getDefaultAdapter(activity)?.isEnabled == true

    /** Starts listening; [onResult] gets null on success or an error message. Called off the main thread. */
    fun start(activity: Activity, uri: String, onResult: (String?) -> Unit) {
        val adapter = NfcAdapter.getDefaultAdapter(activity) ?: return onResult("This phone has no NFC")
        val flags = NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or
            NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_NFC_V or NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS
        adapter.enableReaderMode(activity, { tag -> onResult(write(tag, uri)) }, flags, null)
    }

    fun stop(activity: Activity) {
        NfcAdapter.getDefaultAdapter(activity)?.disableReaderMode(activity)
    }

    private fun write(tag: Tag, uri: String): String? {
        val message = NdefMessage(arrayOf(NdefRecord.createUri(uri)))
        return try {
            val ndef = Ndef.get(tag)
            if (ndef != null) {
                ndef.connect()
                try {
                    if (!ndef.isWritable) return "This tag is read-only"
                    if (ndef.maxSize < message.toByteArray().size) return "This tag is too small"
                    ndef.writeNdefMessage(message)
                } finally {
                    ndef.close()
                }
                null
            } else {
                val formatable = NdefFormatable.get(tag) ?: return "This tag can't hold a link"
                formatable.connect()
                try {
                    formatable.format(message)
                } finally {
                    formatable.close()
                }
                null
            }
        } catch (e: Exception) {
            e.message ?: "Couldn't write the tag"
        }
    }
}
