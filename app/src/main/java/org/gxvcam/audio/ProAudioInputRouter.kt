package org.gxvcam.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.MicrophoneDirection
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.gxvcam.ui.state.AudioInputSource
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

@Immutable
data class AudioDeviceOption(
    val id: Int,
    val name: String,
    val type: Int,
    val typeLabel: String,
    val isExternal: Boolean
)

/**
 * 2nd-order Butterworth High-Pass Biquad Filter (80 Hz @ 48 kHz) used for wind-noise
 * rumble attenuation without coloring speech fundamentals.
 */
class WindCutBiquadFilter(
    sampleRateHz: Float = 48_000f,
    cutoffHz: Float = 80f
) {
    private val b0: Float
    private val b1: Float
    private val b2: Float
    private val a1: Float
    private val a2: Float

    // Per-channel state (L = 0, R = 1)
    private val z1 = FloatArray(2)
    private val z2 = FloatArray(2)

    init {
        val omega = (2.0 * PI * cutoffHz / sampleRateHz).toFloat()
        val sinW = sin(omega)
        val cosW = cos(omega)
        val q = 0.70710678f // Butterworth 1/sqrt(2)
        val alpha = sinW / (2.0f * q)
        val a0 = 1.0f + alpha

        b0 = ((1.0f + cosW) * 0.5f) / a0
        b1 = (-(1.0f + cosW)) / a0
        b2 = ((1.0f + cosW) * 0.5f) / a0
        a1 = (-2.0f * cosW) / a0
        a2 = (1.0f - alpha) / a0
    }

    fun reset() {
        z1[0] = 0f
        z1[1] = 0f
        z2[0] = 0f
        z2[1] = 0f
    }

    fun processSample(channel: Int, input: Float): Float {
        val ch = channel.coerceIn(0, 1)
        val out = b0 * input + z1[ch]
        z1[ch] = b1 * input - a1 * out + z2[ch]
        z2[ch] = b2 * input - a2 * out
        return out
    }

    fun processPcm16(samples: ShortArray, sampleCount: Int, isStereo: Boolean) {
        if (isStereo) {
            var i = 0
            while (i + 1 < sampleCount) {
                val l = processSample(0, samples[i].toFloat())
                val r = processSample(1, samples[i + 1].toFloat())
                samples[i] = l.coerceIn(-32768f, 32767f).toInt().toShort()
                samples[i + 1] = r.coerceIn(-32768f, 32767f).toInt().toShort()
                i += 2
            }
        } else {
            for (i in 0 until sampleCount) {
                val y = processSample(0, samples[i].toFloat())
                samples[i] = y.coerceIn(-32768f, 32767f).toInt().toShort()
            }
        }
    }
}

/**
 * Multi-mic cinema audio router and live stereo VU meter for Motorola Edge 50 Neo.
 *
 * Supports built-in dual capsules, USB-C audio interfaces, wired headsets, Bluetooth SCO/LE,
 * API 29+ Audio Zoom field dimension coupling, 80 Hz wind-cut filtering, and shared PCM fan-out
 * to the AAC encoder during active recording (avoiding dual-[AudioRecord] mic contention).
 */
class ProAudioInputRouter(private val context: Context) : AutoCloseable {

    companion object {
        private const val TAG = "ProAudioInputRouter"
        const val SAMPLE_RATE_HZ = 48_000
        const val MIN_DB_FS = -60.0f
        const val MAX_DB_FS = 0.0f

        fun computeStereoDbfs(samples: ShortArray, sampleCount: Int, isStereo: Boolean): Pair<Float, Float> {
            if (sampleCount <= 0) return MIN_DB_FS to MIN_DB_FS
            var sumSqL = 0.0
            var sumSqR = 0.0
            var countL = 0
            var countR = 0

            if (isStereo) {
                var i = 0
                while (i + 1 < sampleCount) {
                    val normL = samples[i].toDouble() / 32768.0
                    val normR = samples[i + 1].toDouble() / 32768.0
                    sumSqL += normL * normL
                    sumSqR += normR * normR
                    countL++
                    countR++
                    i += 2
                }
            } else {
                for (i in 0 until sampleCount) {
                    val norm = samples[i].toDouble() / 32768.0
                    sumSqL += norm * norm
                    countL++
                }
                sumSqR = sumSqL
                countR = countL
            }

            fun rmsToDb(sumSq: Double, count: Int): Float {
                if (count <= 0 || sumSq <= 0.0) return MIN_DB_FS
                val rms = sqrt(sumSq / count)
                if (rms <= 1e-6) return MIN_DB_FS
                return (20.0 * log10(rms)).toFloat().coerceIn(MIN_DB_FS, MAX_DB_FS)
            }

            return rmsToDb(sumSqL, countL) to rmsToDb(sumSqR, countR)
        }
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _stereoDbFs = MutableStateFlow(MIN_DB_FS to MIN_DB_FS)
    val stereoDbFs: StateFlow<Pair<Float, Float>> = _stereoDbFs.asStateFlow()

    private val _availableDevices = MutableStateFlow<List<AudioDeviceOption>>(emptyList())
    val availableDevices: StateFlow<List<AudioDeviceOption>> = _availableDevices.asStateFlow()

    private val windCutFilter = WindCutBiquadFilter(sampleRateHz = SAMPLE_RATE_HZ.toFloat(), cutoffHz = 80f)
    private val isMonitoring = AtomicBoolean(false)

    @Volatile
    private var audioRecord: AudioRecord? = null
    @Volatile
    private var readerThread: Thread? = null
    @Volatile
    private var isCallbackRegistered: Boolean = false
    @Volatile
    private var activeSource: AudioInputSource = AudioInputSource.REAR_SUBJECT_FOCUS
    @Volatile
    private var preferredDeviceId: Int? = null
    @Volatile
    private var isWindCutEnabled: Boolean = true
    @Volatile
    private var currentZoomRatio: Float = 1.0f
    @Volatile
    private var pcmConsumer: ((ByteArray, Int, Long) -> Unit)? = null
    @Volatile
    private var activeIsStereo: Boolean = true

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            refreshInputDevices()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            refreshInputDevices()
        }
    }

    fun refreshInputDevices(): List<AudioDeviceOption> {
        val inputs = audioManager?.getDevices(AudioManager.GET_DEVICES_INPUTS) ?: emptyArray()
        val mapped = inputs.mapNotNull { info ->
            val (typeLabel, isExternal) = when (info.type) {
                AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Built-in Capsule" to false
                AudioDeviceInfo.TYPE_USB_DEVICE,
                AudioDeviceInfo.TYPE_USB_HEADSET,
                AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB-C Mic" to true
                AudioDeviceInfo.TYPE_BLE_HEADSET,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth Mic" to true
                AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired Mic" to true
                else -> return@mapNotNull null
            }
            val product = info.productName?.toString()?.trim().orEmpty()
            val displayName = if (product.isNotEmpty() && !product.equals("motorola edge 50 neo", true)) {
                "$typeLabel ($product)"
            } else {
                typeLabel
            }
            AudioDeviceOption(
                id = info.id,
                name = displayName,
                type = info.type,
                typeLabel = typeLabel,
                isExternal = isExternal
            )
        }.distinctBy { it.id }

        _availableDevices.value = mapped
        return mapped
    }

    fun configureRouting(
        source: AudioInputSource,
        deviceId: Int? = null,
        windCutEnabled: Boolean = isWindCutEnabled,
        zoomRatio: Float = currentZoomRatio
    ) {
        this.activeSource = source
        this.preferredDeviceId = deviceId
        this.isWindCutEnabled = windCutEnabled
        this.currentZoomRatio = zoomRatio

        val rec = audioRecord ?: return
        applyDeviceAndDirection(rec)
    }

    fun updateAudioZoom(zoomRatio: Float) {
        this.currentZoomRatio = zoomRatio
        val rec = audioRecord ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                val zoomNormalized = ((zoomRatio - 1.0f) / 9.0f).coerceIn(0f, 1f)
                when (activeSource) {
                    AudioInputSource.REAR_SUBJECT_FOCUS -> {
                        rec.setPreferredMicrophoneDirection(MicrophoneDirection.MIC_DIRECTION_AWAY_FROM_USER)
                        rec.setPreferredMicrophoneFieldDimension(zoomNormalized)
                    }
                    AudioInputSource.CAMCORDER_STEREO -> {
                        rec.setPreferredMicrophoneDirection(MicrophoneDirection.MIC_DIRECTION_UNSPECIFIED)
                        rec.setPreferredMicrophoneFieldDimension(0f)
                    }
                    AudioInputSource.EXTERNAL_USBC_BLE -> {
                        rec.setPreferredMicrophoneDirection(MicrophoneDirection.MIC_DIRECTION_UNSPECIFIED)
                    }
                }
            }
        }
    }

    fun setPcmConsumer(consumer: ((ByteArray, Int, Long) -> Unit)?) {
        this.pcmConsumer = consumer
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    fun startMonitoring() {
        refreshInputDevices()
        if (!isCallbackRegistered) {
            runCatching {
                audioManager?.registerAudioDeviceCallback(deviceCallback, mainHandler)
                isCallbackRegistered = true
            }
        }

        if (isMonitoring.get()) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "RECORD_AUDIO permission missing; VU monitor stays idle")
            return
        }

        val stereoMinBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val useStereo = stereoMinBuf > 0
        val channelMask = if (useStereo) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
        val minBuf = if (useStereo) stereoMinBuf else AudioRecord.getMinBufferSize(
            SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) return

        val bufferSize = (minBuf * 2).coerceAtLeast(4096)
        val audioSource = if (activeSource == AudioInputSource.REAR_SUBJECT_FOCUS) {
            MediaRecorder.AudioSource.CAMCORDER
        } else {
            MediaRecorder.AudioSource.MIC
        }

        val rec = runCatching {
            AudioRecord(
                audioSource,
                SAMPLE_RATE_HZ,
                channelMask,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
        }.getOrNull()

        if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { rec?.release() }
            Log.w(TAG, "AudioRecord failed to initialize")
            return
        }

        activeIsStereo = useStereo
        audioRecord = rec
        windCutFilter.reset()
        applyDeviceAndDirection(rec)

        isMonitoring.set(true)
        rec.startRecording()

        val readThread = Thread({
            val shortBuf = ShortArray(2048)
            val byteBuf = ByteArray(shortBuf.size * 2)
            var lastMeterEmitMs = 0L
            while (isMonitoring.get()) {
                val readSamples = runCatching {
                    rec.read(shortBuf, 0, shortBuf.size)
                }.getOrDefault(-1)

                if (readSamples > 0) {
                    if (isWindCutEnabled) {
                        windCutFilter.processPcm16(shortBuf, readSamples, isStereo = activeIsStereo)
                    }
                    val nowMs = android.os.SystemClock.elapsedRealtime()
                    if (nowMs - lastMeterEmitMs >= 90L) {
                        val nextDb = computeStereoDbfs(shortBuf, readSamples, isStereo = activeIsStereo)
                        val prevDb = _stereoDbFs.value
                        if (kotlin.math.abs(nextDb.first - prevDb.first) >= 0.5f ||
                            kotlin.math.abs(nextDb.second - prevDb.second) >= 0.5f
                        ) {
                            _stereoDbFs.value = nextDb
                            lastMeterEmitMs = nowMs
                        }
                    }

                    val consumer = pcmConsumer
                    if (consumer != null) {
                        val byteCount = readSamples * 2
                        for (i in 0 until readSamples) {
                            val s = shortBuf[i].toInt()
                            byteBuf[i * 2] = (s and 0xFF).toByte()
                            byteBuf[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
                        }
                        consumer.invoke(byteBuf, byteCount, System.nanoTime() / 1000L)
                    }
                } else if (readSamples < 0) {
                    break
                }
            }
        }, "GxvAudioRouter")
        readThread.isDaemon = true
        readerThread = readThread
        readThread.start()
    }

    private fun applyDeviceAndDirection(rec: AudioRecord) {
        val inputs = audioManager?.getDevices(AudioManager.GET_DEVICES_INPUTS) ?: emptyArray()
        val targetDevice = when {
            preferredDeviceId != null -> inputs.firstOrNull { it.id == preferredDeviceId }
            activeSource == AudioInputSource.EXTERNAL_USBC_BLE -> inputs.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                    it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                    it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET
            }
            else -> inputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
        }
        runCatching {
            rec.preferredDevice = targetDevice
        }
        updateAudioZoom(currentZoomRatio)
    }

    @Synchronized
    fun stopMonitoring() {
        isMonitoring.set(false)
        pcmConsumer = null
        runCatching { audioRecord?.stop() }
        runCatching { readerThread?.join(250L) }
        readerThread = null
        runCatching { audioRecord?.release() }
        audioRecord = null
        _stereoDbFs.value = MIN_DB_FS to MIN_DB_FS
        if (isCallbackRegistered) {
            runCatching { audioManager?.unregisterAudioDeviceCallback(deviceCallback) }
            isCallbackRegistered = false
        }
    }

    fun isStereoChannel(): Boolean = activeIsStereo

    override fun close() {
        stopMonitoring()
    }
}
