package dev.diligent.app.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.diligent.app.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.UnknownHostException
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val checkedAt: Long) : UpdateState
    data class Available(val info: UpdateInfo) : UpdateState
    data class Downloading(val info: UpdateInfo, val progress: Float?) : UpdateState
    data class Installing(val info: UpdateInfo) : UpdateState
    /** Android's own install screen couldn't open by itself (the app was in the background). */
    data class NeedsConfirmation(val info: UpdateInfo) : UpdateState
    /** [info] is set when an install attempt failed, so the UI says so in a dialog. */
    data class Failed(val message: String, val info: UpdateInfo? = null) : UpdateState
}

/**
 * Updates the app from GitHub releases. CI publishes a signed APK and update.json for every
 * commit on main; this reads update.json when the app is opened (at most every
 * [UpdateFeed.CHECK_EVERY_MS]) or from Settings — never in the background — and asks before
 * anything is downloaded. Android then shows its own install confirmation, and only accepts
 * an APK signed with the same key as the installed app.
 *
 * Debug builds (dev.diligent.app.debug) are a separate app, so they never update themselves.
 */
@Singleton
class AppUpdater @Inject constructor(@ApplicationContext private val context: Context) {

    val enabled: Boolean = BuildConfig.UPDATES_ENABLED
    val installedVersionName: String = BuildConfig.VERSION_NAME
    private val installedVersionCode: Int = BuildConfig.VERSION_CODE

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** One check or download at a time. */
    private val busy = Mutex()

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    /** The "Update found" question, when one should be shown. */
    private val _prompt = MutableStateFlow<UpdateInfo?>(null)
    val prompt: StateFlow<UpdateInfo?> = _prompt.asStateFlow()

    @Volatile
    private var pendingConfirmation: Intent? = null

    /** Set by MainActivity; Android only lets the install screen open while the app is on screen. */
    @Volatile
    var inForeground: Boolean = false

    /** On app open: one small request, only if the last check is old enough. */
    fun checkIfDue() {
        if (!enabled || !UpdateFeed.autoCheckDue(prefs.getLong(KEY_LAST_CHECK, 0L), System.currentTimeMillis())) return
        scope.launch { check(manual = false) }
    }

    /** "Check for updates" in Settings. */
    fun checkNow() {
        if (!enabled) {
            _state.value = UpdateState.Failed("This is a debug build. Updates install into the release app from GitHub.")
            return
        }
        scope.launch { check(manual = true) }
    }

    /** Shows the "Update found" question again (Settings, after "Later"). */
    fun offer(info: UpdateInfo) {
        _prompt.value = info
    }

    fun later(info: UpdateInfo) {
        prefs.edit()
            .putInt(KEY_SNOOZED_VERSION, info.versionCode)
            .putLong(KEY_SNOOZED_UNTIL, System.currentTimeMillis() + UpdateFeed.SNOOZE_MS)
            .apply()
        _prompt.value = null
    }

    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    /** Android's "Install unknown apps" switch for Diligent. */
    fun unknownSourcesIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

    /** The user said yes: download, verify, and hand the APK to Android's installer. */
    fun startUpdate(info: UpdateInfo) {
        _prompt.value = null
        scope.launch {
            if (!busy.tryLock()) return@launch
            try {
                _state.value = UpdateState.Downloading(info, null)
                val apk = download(info) { progress -> _state.value = UpdateState.Downloading(info, progress) }
                _state.value = UpdateState.Installing(info)
                install(apk)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Update failed", e)
                _state.value = UpdateState.Failed(e.message ?: "The update failed", info)
            } finally {
                busy.unlock()
            }
        }
    }

    /** Opens Android's install confirmation (again, if it was closed or couldn't open). */
    fun confirm(activityContext: Context) {
        val intent = pendingConfirmation ?: return
        activityContext.startActivity(intent)
    }

    fun dismissError() {
        if (_state.value is UpdateState.Failed) _state.value = UpdateState.Idle
    }

    /** Keeps Android's confirmation screen for the UI; true when it can be opened right now. */
    internal fun onConfirmationNeeded(confirm: Intent): Boolean {
        val info = currentInfo() ?: return false
        pendingConfirmation = confirm
        _state.value = UpdateState.NeedsConfirmation(info)
        return inForeground
    }

    internal fun onInstallResult(status: Int, message: String?) {
        val info = currentInfo()
        pendingConfirmation = null
        _state.value = when (status) {
            // The new version replaces this process right after.
            PackageInstaller.STATUS_SUCCESS -> UpdateState.Idle
            PackageInstaller.STATUS_FAILURE_ABORTED -> UpdateState.Failed("Update cancelled", info)
            PackageInstaller.STATUS_FAILURE_CONFLICT ->
                UpdateState.Failed("Android refused the update: it is signed with a different key than the installed app", info)
            PackageInstaller.STATUS_FAILURE_STORAGE -> UpdateState.Failed("Not enough storage for the update", info)
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> UpdateState.Failed("This update isn't compatible with this phone", info)
            else -> UpdateState.Failed(message ?: "Android couldn't install the update", info)
        }
    }

    private fun currentInfo(): UpdateInfo? = when (val s = state.value) {
        is UpdateState.Installing -> s.info
        is UpdateState.NeedsConfirmation -> s.info
        is UpdateState.Downloading -> s.info
        else -> null
    }

    private suspend fun check(manual: Boolean) {
        if (!busy.tryLock()) return
        try {
            _state.value = UpdateState.Checking
            val now = System.currentTimeMillis()
            val feed = fetchFeed()
            prefs.edit().putLong(KEY_LAST_CHECK, now).apply()
            _state.value = when (feed) {
                is Feed.Found -> if (UpdateFeed.isNewer(feed.info, installedVersionCode)) {
                    val ask = manual || UpdateFeed.shouldPrompt(
                        feed.info, installedVersionCode,
                        prefs.getInt(KEY_SNOOZED_VERSION, 0), prefs.getLong(KEY_SNOOZED_UNTIL, 0L), now
                    )
                    if (ask) _prompt.value = feed.info
                    UpdateState.Available(feed.info)
                } else {
                    UpdateState.UpToDate(now)
                }
                // An automatic check stays quiet when it can't tell; a manual one says why.
                Feed.NotPublished -> if (manual) UpdateState.Failed("No update has been published yet") else UpdateState.Idle
                is Feed.Error -> if (manual) UpdateState.Failed(feed.message) else UpdateState.Idle
            }
        } finally {
            busy.unlock()
        }
    }

    private sealed interface Feed {
        data class Found(val info: UpdateInfo) : Feed
        data object NotPublished : Feed
        data class Error(val message: String) : Feed
    }

    private fun fetchFeed(): Feed {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(BuildConfig.UPDATE_FEED_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 15_000
                setRequestProperty("User-Agent", "Diligent/${BuildConfig.VERSION_NAME}")
            }
            when (val code = connection.responseCode) {
                200 -> UpdateFeed.parse(connection.inputStream.bufferedReader().use { it.readText() })
                    ?.let { Feed.Found(it) } ?: Feed.Error("The update feed on GitHub is malformed")
                404 -> Feed.NotPublished
                else -> Feed.Error("GitHub returned HTTP $code")
            }
        } catch (e: UnknownHostException) {
            Feed.Error("Offline — couldn't reach github.com")
        } catch (e: IOException) {
            Feed.Error("Network error: ${e.javaClass.simpleName}")
        } finally {
            connection?.disconnect()
        }
    }

    private fun download(info: UpdateInfo, onProgress: (Float?) -> Unit): File {
        val dir = File(context.cacheDir, "updates").apply {
            mkdirs()
            listFiles()?.forEach { it.delete() }
        }
        val file = File(dir, "Diligent-${info.versionCode}.apk")
        val connection = (URL(info.apkUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", "Diligent/${BuildConfig.VERSION_NAME}")
        }
        try {
            if (connection.responseCode != 200) throw IOException("Download failed (HTTP ${connection.responseCode})")
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: info.sizeBytes.takeIf { it > 0 }
            val digest = MessageDigest.getInstance("SHA-256")
            connection.inputStream.use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    var lastPercent = -1
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        done += read
                        if (total != null) {
                            val percent = (done * 100 / total).toInt()
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress((done.toFloat() / total).coerceAtMost(1f))
                            }
                        }
                    }
                }
            }
            if (UpdateFeed.toHex(digest.digest()) != info.sha256) {
                file.delete()
                throw IOException("The download doesn't match its checksum, so it wasn't installed")
            }
            return file
        } finally {
            connection.disconnect()
        }
    }

    private fun install(apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("diligent.apk", 0, apk.length()).use { output ->
                    input.copyTo(output)
                    session.fsync(output)
                }
            }
            session.commit(UpdateInstallReceiver.statusSender(context, sessionId))
        }
        apk.delete()
    }

    companion object {
        private const val TAG = "AppUpdater"
        private const val PREFS_NAME = "app_updates"
        private const val KEY_LAST_CHECK = "last_check_at"
        private const val KEY_SNOOZED_VERSION = "snoozed_version"
        private const val KEY_SNOOZED_UNTIL = "snoozed_until"
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface UpdateEntryPoint {
    fun appUpdater(): AppUpdater
}

/**
 * Results from Android's installer for an update session. Not exported: only the system,
 * through this app's own PendingIntent, can reach it.
 */
class UpdateInstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val updater = EntryPointAccessors.fromApplication(context.applicationContext, UpdateEntryPoint::class.java).appUpdater()
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_INTENT)
            } ?: return
            confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            // Opens straight away while the app is on screen; otherwise the app offers it on return.
            if (updater.onConfirmationNeeded(confirm)) {
                try {
                    context.startActivity(confirm)
                } catch (e: Exception) {
                    Log.w("UpdateInstallReceiver", "Couldn't open the install screen", e)
                }
            }
            return
        }
        updater.onInstallResult(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
    }

    companion object {
        private const val ACTION_STATUS = "dev.diligent.app.update.INSTALL_STATUS"

        fun statusSender(context: Context, sessionId: Int): IntentSender {
            val intent = Intent(context, UpdateInstallReceiver::class.java).setAction(ACTION_STATUS)
            // Mutable so the installer can add its status extras; explicit, so that is safe.
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
            return PendingIntent.getBroadcast(context, sessionId, intent, flags).intentSender
        }
    }
}
