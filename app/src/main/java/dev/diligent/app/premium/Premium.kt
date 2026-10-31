package dev.diligent.app.premium

import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ColorMatrixColorFilter
import android.graphics.RenderEffect
import android.hardware.biometrics.BiometricPrompt
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.CancellationSignal
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.sin

/** Look & feel preferences, read synchronously (small SharedPreferences). */
object LookAndFeel {

    enum class Theme(val label: String) { DEFAULT("MONO"), PAPER("PAPER"), NIGHT("RED NIGHT"), AUTO_NIGHT("RED AFTER 10 PM") }

    enum class Icon(val label: String, val alias: String) {
        CLASSIC("CLASSIC", "dev.diligent.app.LauncherClassic"),
        DOTS("DOTS", "dev.diligent.app.LauncherDots"),
        RING("RING", "dev.diligent.app.LauncherRing"),
        PAPER("PAPER", "dev.diligent.app.LauncherPaper")
    }

    data class Prefs(
        val theme: Theme = Theme.DEFAULT,
        val icon: Icon = Icon.CLASSIC,
        val haptics: Boolean = true,
        val sounds: Boolean = false,
        val appLock: Boolean = false
    )

    private const val PREFS = "look_and_feel"

    fun read(context: Context): Prefs {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Prefs(
            theme = runCatching { Theme.valueOf(p.getString("theme", null) ?: "DEFAULT") }.getOrDefault(Theme.DEFAULT),
            icon = runCatching { Icon.valueOf(p.getString("icon", null) ?: "CLASSIC") }.getOrDefault(Icon.CLASSIC),
            haptics = p.getBoolean("haptics", true),
            sounds = p.getBoolean("sounds", false),
            appLock = p.getBoolean("app_lock", false)
        )
    }

    fun save(context: Context, prefs: Prefs) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("theme", prefs.theme.name)
            .putString("icon", prefs.icon.name)
            .putBoolean("haptics", prefs.haptics)
            .putBoolean("sounds", prefs.sounds)
            .putBoolean("app_lock", prefs.appLock)
            .apply()
    }

    /**
     * Switches the launcher icon by enabling one launcher alias and disabling the others
     * (Streaks, Things). Some launchers take a few seconds to show the new icon.
     */
    fun applyIcon(context: Context, icon: Icon) {
        val pm = context.packageManager
        Icon.entries.forEach { option ->
            val state = if (option == icon) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            runCatching { pm.setComponentEnabledSetting(ComponentName(context.packageName, option.alias), state, PackageManager.DONT_KILL_APP) }
        }
    }

    /** Whether the red night filter applies right now. */
    fun nightNow(theme: Theme, hour: Int): Boolean = theme == Theme.NIGHT || (theme == Theme.AUTO_NIGHT && (hour >= 22 || hour < 6))
}

/**
 * The whole app re-coloured without touching each screen: red night (multiply by red, so
 * whites turn red and black stays black, kind to dark-adapted eyes) or paper (lightness
 * inverted with hues kept, Android 12+; plain inversion before).
 */
fun Modifier.themeFilter(theme: LookAndFeel.Theme, hour: Int): Modifier = when {
    LookAndFeel.nightNow(theme, hour) -> drawWithContent {
        drawContent()
        drawRect(Color(0xFFFF2A1A), blendMode = BlendMode.Multiply)
    }
    theme == LookAndFeel.Theme.PAPER && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> graphicsLayer {
        renderEffect = RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(SMART_INVERT)).asComposeRenderEffect()
    }
    theme == LookAndFeel.Theme.PAPER -> drawWithContent {
        drawContent()
        drawRect(Color.White, blendMode = BlendMode.Difference)
    }
    else -> this
}

/** Invert, then rotate hue 180°: dark becomes light while green stays green. */
private val SMART_INVERT = floatArrayOf(
    0.574f, -1.43f, -0.144f, 0f, 255f,
    -0.426f, -0.43f, -0.144f, 0f, 255f,
    -0.426f, -1.43f, 0.856f, 0f, 255f,
    0f, 0f, 0f, 1f, 0f
)

/**
 * Haptics and tiny synthesized sounds (Things 3, Streaks): a tick for +1, a double click when
 * a goal is met, a thud-and-spin for a streak milestone. Sounds are generated, no files.
 */
object Feel {

    fun tick(view: View) {
        if (!LookAndFeel.read(view.context).haptics) return
        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    fun goalMet(context: Context) {
        val prefs = LookAndFeel.read(context)
        if (prefs.haptics) vibrate(context, milestone = false)
        if (prefs.sounds) chime(listOf(660.0, 990.0))
    }

    fun milestone(context: Context) {
        val prefs = LookAndFeel.read(context)
        if (prefs.haptics) vibrate(context, milestone = true)
        if (prefs.sounds) chime(listOf(523.0, 659.0, 784.0, 1046.0))
    }

    private fun vibrator(context: Context): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        else @Suppress("DEPRECATION") context.getSystemService(Vibrator::class.java)

    private fun vibrate(context: Context, milestone: Boolean) {
        val vibrator = vibrator(context)?.takeIf { it.hasVibrator() } ?: return
        val effect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val primitives = if (milestone) {
                intArrayOf(VibrationEffect.Composition.PRIMITIVE_THUD, VibrationEffect.Composition.PRIMITIVE_SPIN)
            } else {
                intArrayOf(VibrationEffect.Composition.PRIMITIVE_CLICK, VibrationEffect.Composition.PRIMITIVE_CLICK)
            }
            if (vibrator.areAllPrimitivesSupported(*primitives)) {
                VibrationEffect.startComposition().apply {
                    primitives.forEachIndexed { i, p -> addPrimitive(p, if (milestone) 1f else 0.7f, if (i == 0) 0 else 70) }
                }.compose()
            } else null
        } else null
        val fallback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            VibrationEffect.createPredefined(if (milestone) VibrationEffect.EFFECT_HEAVY_CLICK else VibrationEffect.EFFECT_DOUBLE_CLICK)
        } else {
            VibrationEffect.createWaveform(if (milestone) longArrayOf(0, 40, 60, 80) else longArrayOf(0, 20, 60, 20), -1)
        }
        runCatching { vibrator.vibrate(effect ?: fallback) }
    }

    /** A short sine arpeggio, 50 ms a note, quiet. */
    private fun chime(frequencies: List<Double>) = thread(name = "diligent-chime", isDaemon = true) {
        val rate = 44_100
        val noteSamples = rate / 20
        val pcm = ShortArray(noteSamples * frequencies.size)
        frequencies.forEachIndexed { n, f ->
            for (i in 0 until noteSamples) {
                val envelope = (1.0 - i.toDouble() / noteSamples).let { it * it }
                pcm[n * noteSamples + i] = (sin(2 * PI * f * i / rate) * envelope * 0.18 * Short.MAX_VALUE).toInt().toShort()
            }
        }
        runCatching {
            val track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm.size * 2)
                .build()
            track.write(pcm, 0, pcm.size)
            track.play()
            Thread.sleep(60L * frequencies.size + 100)
            track.release()
        }
    }
}

/**
 * App lock (Daylio's PIN lock): the fingerprint / face / screen lock when Diligent opens after
 * a minute away. Falls back to the device credential, and to no lock when the phone has none.
 */
object AppLock {

    private const val GRACE_MS = 60_000L

    @Volatile
    private var lastBackground = 0L

    @Volatile
    private var unlocked = false

    fun onBackground() {
        lastBackground = System.currentTimeMillis()
    }

    fun needsUnlock(context: Context): Boolean {
        if (!LookAndFeel.read(context).appLock) return false
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        if (keyguard?.isDeviceSecure != true) return false
        if (unlocked && System.currentTimeMillis() - lastBackground < GRACE_MS) return false
        if (unlocked && lastBackground == 0L) return false
        return true
    }

    fun markUnlocked() {
        unlocked = true
        lastBackground = 0L
    }

    /** Shows the system prompt on Android 9+; on 8.x [fallback] is asked to confirm the credential. */
    fun authenticate(context: Context, onResult: (Boolean) -> Unit, fallback: () -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return fallback()
        val builder = BiometricPrompt.Builder(context)
            .setTitle("Unlock Diligent")
            .setSubtitle("Your activities stay private")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setAllowedAuthenticators(android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_WEAK or
                android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL)
        } else {
            @Suppress("DEPRECATION")
            builder.setDeviceCredentialAllowed(true)
        }
        builder.build().authenticate(CancellationSignal(), context.mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                markUnlocked()
                onResult(true)
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) = onResult(false)
        })
    }
}
