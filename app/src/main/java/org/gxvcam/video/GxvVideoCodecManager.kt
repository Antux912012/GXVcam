package org.gxvcam.video

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import android.view.Surface
import androidx.compose.runtime.Immutable
import org.gxvcam.audio.ProAudioInputRouter
import org.gxvcam.ui.state.VideoCodecProfile
import org.gxvcam.ui.state.VideoColorProfile
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@Immutable
data class VideoCodecCapabilities(
    val hasHardwareAvc: Boolean = true,
    val hasHardwareHevc: Boolean = true,
    val hasHardwareHevc10Bit: Boolean = true,
    val hasHardwareAv1Encoder: Boolean = false,
    val hasHardwareAv1Decoder: Boolean = false,
    val activeAv1EncoderName: String? = null,
    val activeHevcEncoderName: String? = null,
    val activeAvcEncoderName: String? = null
)

/**
 * Multi-Codec Video Engine for Motorola Edge 50 Neo (`vienna` / MediaTek Dimensity 7300).
 *
 * Probes hardware vs. software codec blocks via [MediaCodecList], configures 8-bit H.264,
 * 10-bit HEVC (`HEVCProfileMain10` + `BT2020`), and tuned SW/HW AV1 encoding, and manages
 * an asynchronous dual-track Video + 48kHz AAC [MediaMuxer] session writing to MediaStore.
 */
object GxvVideoCodecManager {

    private const val TAG = "GxvVideoCodecManager"
    private const val AUDIO_MIME = MediaFormat.MIMETYPE_AUDIO_AAC
    private const val AUDIO_BITRATE = 192_000

    fun isSoftwareCodec(info: MediaCodecInfo): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (info.isSoftwareOnly) return true
            if (info.isHardwareAccelerated) return false
        }
        val name = info.name.lowercase()
        return name.startsWith("c2.android.") || name.startsWith("omx.google.")
    }

    fun probeCapabilities(
        codecInfos: Array<MediaCodecInfo> = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
    ): VideoCodecCapabilities {
        var hwAvc = false
        var hwHevc = false
        var hwHevc10 = false
        var hwAv1Enc = false
        var hwAv1Dec = false
        var avcName: String? = null
        var hevcName: String? = null
        var av1EncName: String? = null

        for (info in codecInfos) {
            val isSw = isSoftwareCodec(info)
            val types = info.supportedTypes
            if (info.isEncoder) {
                for (type in types) {
                    when {
                        type.equals(MediaFormat.MIMETYPE_VIDEO_AVC, ignoreCase = true) -> {
                            if (!isSw) {
                                hwAvc = true
                                if (avcName == null) avcName = info.name
                            } else if (avcName == null) {
                                avcName = info.name
                            }
                        }
                        type.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, ignoreCase = true) -> {
                            if (!isSw) {
                                hwHevc = true
                                if (hevcName == null) hevcName = info.name
                                val caps = runCatching { info.getCapabilitiesForType(type) }.getOrNull()
                                val supportsMain10 = caps?.profileLevels?.any {
                                    it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 ||
                                        it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10
                                } == true
                                if (supportsMain10) hwHevc10 = true
                            } else if (hevcName == null) {
                                hevcName = info.name
                            }
                        }
                        type.equals(MediaFormat.MIMETYPE_VIDEO_AV1, ignoreCase = true) -> {
                            if (!isSw) {
                                hwAv1Enc = true
                                av1EncName = info.name
                            } else if (av1EncName == null) {
                                av1EncName = info.name
                            }
                        }
                    }
                }
            } else {
                if (types.any { it.equals(MediaFormat.MIMETYPE_VIDEO_AV1, ignoreCase = true) } && !isSw) {
                    hwAv1Dec = true
                }
            }
        }

        return VideoCodecCapabilities(
            hasHardwareAvc = hwAvc,
            hasHardwareHevc = hwHevc,
            hasHardwareHevc10Bit = hwHevc10,
            hasHardwareAv1Encoder = hwAv1Enc,
            hasHardwareAv1Decoder = hwAv1Dec,
            activeAv1EncoderName = av1EncName,
            activeHevcEncoderName = hevcName,
            activeAvcEncoderName = avcName
        )
    }

    fun hasHardwareAv1Decoder(
        codecInfos: Array<MediaCodecInfo> = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
    ): Boolean {
        return probeCapabilities(codecInfos).hasHardwareAv1Decoder
    }

    fun computeTargetBitrate(
        codecProfile: VideoCodecProfile,
        width: Int,
        height: Int,
        fps: Int,
        hasHwAv1: Boolean = false
    ): Int {
        val pixels = width.toLong() * height.toLong()
        val is4K = pixels >= 3840L * 2160L
        return when (codecProfile) {
            VideoCodecProfile.H264_AVC -> when {
                is4K -> 65_000_000
                fps >= 60 -> 35_000_000
                else -> 24_000_000
            }
            VideoCodecProfile.H265_HEVC -> when {
                is4K -> 55_000_000
                fps >= 60 -> 32_000_000
                else -> 20_000_000
            }
            VideoCodecProfile.AV1 -> when {
                hasHwAv1 && is4K -> 42_000_000
                hasHwAv1 && fps >= 60 -> 24_000_000
                fps >= 60 -> 14_000_000 // Bounded for real-time SW libaom/c2.android.av1.encoder at 1080p60
                else -> 12_000_000
            }
        }
    }

    fun buildVideoMediaFormat(
        codecProfile: VideoCodecProfile,
        colorProfile: VideoColorProfile,
        width: Int,
        height: Int,
        fps: Int,
        capabilities: VideoCodecCapabilities = VideoCodecCapabilities()
    ): MediaFormat {
        val bitrate = computeTargetBitrate(
            codecProfile = codecProfile,
            width = width,
            height = height,
            fps = fps,
            hasHwAv1 = capabilities.hasHardwareAv1Encoder
        )
        return MediaFormat.createVideoFormat(codecProfile.mimeType, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)

            if (codecProfile == VideoCodecProfile.H265_HEVC && colorProfile.is10Bit && capabilities.hasHardwareHevc10Bit) {
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10)
                setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
                setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
                setInteger(
                    MediaFormat.KEY_COLOR_TRANSFER,
                    if (colorProfile == VideoColorProfile.HLG_HDR10) {
                        MediaFormat.COLOR_TRANSFER_HLG
                    } else {
                        MediaFormat.COLOR_TRANSFER_ST2084
                    }
                )
            } else {
                setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
                setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
                setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
            }

            if (codecProfile == VideoCodecProfile.AV1 && !capabilities.hasHardwareAv1Encoder) {
                setInteger(MediaFormat.KEY_OPERATING_RATE, fps)
                setInteger(MediaFormat.KEY_PRIORITY, 0)
                setInteger(
                    MediaFormat.KEY_BITRATE_MODE,
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
                )
            }
        }
    }

    /**
     * Active MediaCodec + MediaMuxer cinema video/audio recording session.
     */
    class VideoRecordingSession(
        private val context: Context,
        val codecProfile: VideoCodecProfile,
        val colorProfile: VideoColorProfile,
        val width: Int,
        val height: Int,
        val fps: Int,
        val isAudioEnabled: Boolean,
        val isStereoAudio: Boolean,
        val orientationHintDegrees: Int = 0
    ) {
        private data class AudioChunk(val data: ByteArray, val size: Int, val ptsUs: Long)

        private var videoEncoder: MediaCodec? = null
        private var audioEncoder: MediaCodec? = null
        private var muxer: MediaMuxer? = null
        private var tempFile: File? = null
        var inputSurface: Surface? = null
            private set

        private val muxerLock = Object()
        private var videoTrackIndex = -1
        private var audioTrackIndex = -1
        private var muxerStarted = false

        private val running = AtomicBoolean(false)
        private val audioQueue = ArrayBlockingQueue<AudioChunk>(64)
        private var videoDrainThread: Thread? = null
        private var audioFeedThread: Thread? = null
        private var audioDrainThread: Thread? = null
        private var firstAudioPtsUs: Long = -1L

        fun start(): Boolean {
            val capabilities = probeCapabilities()
            val videoFormat = buildVideoMediaFormat(
                codecProfile = codecProfile,
                colorProfile = colorProfile,
                width = width,
                height = height,
                fps = fps,
                capabilities = capabilities
            )

            val outputFile = File.createTempFile("gxv_rec_", ".mp4", context.cacheDir)
            tempFile = outputFile

            return try {
                val codecName = when (codecProfile) {
                    VideoCodecProfile.H264_AVC -> capabilities.activeAvcEncoderName
                    VideoCodecProfile.H265_HEVC -> capabilities.activeHevcEncoderName
                    VideoCodecProfile.AV1 -> capabilities.activeAv1EncoderName
                }
                val vCodec = if (codecName != null) {
                    runCatching { MediaCodec.createByCodecName(codecName) }
                        .getOrElse { MediaCodec.createEncoderByType(codecProfile.mimeType) }
                } else {
                    MediaCodec.createEncoderByType(codecProfile.mimeType)
                }
                vCodec.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                inputSurface = vCodec.createInputSurface()
                vCodec.start()
                videoEncoder = vCodec

                if (isAudioEnabled) {
                    val channelCount = if (isStereoAudio) 2 else 1
                    val audioFormat = MediaFormat.createAudioFormat(
                        AUDIO_MIME,
                        ProAudioInputRouter.SAMPLE_RATE_HZ,
                        channelCount
                    ).apply {
                        setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                        setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BITRATE)
                        setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
                    }
                    val aCodec = MediaCodec.createEncoderByType(AUDIO_MIME)
                    aCodec.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    aCodec.start()
                    audioEncoder = aCodec
                }

                muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).apply {
                    setOrientationHint(orientationHintDegrees)
                }

                running.set(true)
                videoDrainThread = Thread({ drainVideoLoop() }, "GxvVideoDrain").apply {
                    isDaemon = true
                    start()
                }
                if (isAudioEnabled) {
                    audioFeedThread = Thread({ feedAudioLoop() }, "GxvAudioFeed").apply {
                        isDaemon = true
                        start()
                    }
                    audioDrainThread = Thread({ drainAudioLoop() }, "GxvAudioDrain").apply {
                        isDaemon = true
                        start()
                    }
                }
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start GxvVideoCodecManager session: ${e.message}", e)
                cleanupResources()
                outputFile.delete()
                false
            }
        }

        fun submitAudioPcm(data: ByteArray, size: Int, ptsUs: Long) {
            if (!running.get() || !isAudioEnabled || size <= 0) return
            val copy = data.copyOf(size)
            audioQueue.offer(AudioChunk(copy, size, ptsUs))
        }

        private fun tryStartMuxerLocked() {
            val needAudio = isAudioEnabled && audioEncoder != null
            if (!muxerStarted && videoTrackIndex >= 0 && (!needAudio || audioTrackIndex >= 0)) {
                muxer?.start()
                muxerStarted = true
                muxerLock.notifyAll()
            }
        }

        private fun waitForMuxerStartLocked() {
            val deadline = System.currentTimeMillis() + 1500L
            while (!muxerStarted && running.get()) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0L) break
                runCatching { muxerLock.wait(remaining) }
            }
        }

        private fun drainVideoLoop() {
            val codec = videoEncoder ?: return
            val info = MediaCodec.BufferInfo()
            var firstVideoPtsUs = -1L
            var lastVideoPtsUs = -1L
            var stopDeadlineMs = -1L
            while (true) {
                val idx = runCatching { codec.dequeueOutputBuffer(info, 10_000L) }.getOrDefault(-1)
                when {
                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        synchronized(muxerLock) {
                            if (videoTrackIndex < 0) {
                                videoTrackIndex = muxer?.addTrack(codec.outputFormat) ?: -1
                                tryStartMuxerLocked()
                            }
                        }
                    }
                    idx >= 0 -> {
                        val buf = codec.getOutputBuffer(idx)
                        if (buf != null && info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                            if (firstVideoPtsUs < 0L) firstVideoPtsUs = info.presentationTimeUs
                            val normalizedPts = (info.presentationTimeUs - firstVideoPtsUs).coerceAtLeast(0L)
                            info.presentationTimeUs = if (lastVideoPtsUs >= 0L && normalizedPts <= lastVideoPtsUs) {
                                lastVideoPtsUs + 1L
                            } else {
                                normalizedPts
                            }
                            lastVideoPtsUs = info.presentationTimeUs
                            synchronized(muxerLock) {
                                if (!muxerStarted) waitForMuxerStartLocked()
                                if (muxerStarted && videoTrackIndex >= 0) {
                                    buf.position(info.offset)
                                    buf.limit(info.offset + info.size)
                                    runCatching { muxer?.writeSampleData(videoTrackIndex, buf, info) }
                                }
                            }
                        }
                        runCatching { codec.releaseOutputBuffer(idx, false) }
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                    }
                    !running.get() -> {
                        if (stopDeadlineMs < 0L) {
                            stopDeadlineMs = System.currentTimeMillis() + 1000L
                        } else if (System.currentTimeMillis() >= stopDeadlineMs) {
                            break
                        }
                    }
                }
            }
        }

        private fun feedAudioLoop() {
            val codec = audioEncoder ?: return
            var eosSent = false
            while (running.get() || audioQueue.isNotEmpty()) {
                val chunk = audioQueue.poll(15L, TimeUnit.MILLISECONDS) ?: continue
                if (firstAudioPtsUs < 0L) firstAudioPtsUs = chunk.ptsUs
                val relPtsUs = (chunk.ptsUs - firstAudioPtsUs).coerceAtLeast(0L)
                val inIdx = runCatching { codec.dequeueInputBuffer(10_000L) }.getOrDefault(-1)
                if (inIdx >= 0) {
                    val inBuf: ByteBuffer? = codec.getInputBuffer(inIdx)
                    if (inBuf != null) {
                        inBuf.clear()
                        val toCopy = chunk.size.coerceAtMost(inBuf.remaining())
                        inBuf.put(chunk.data, 0, toCopy)
                        codec.queueInputBuffer(inIdx, 0, toCopy, relPtsUs, 0)
                    }
                }
            }
            if (!eosSent) {
                val inIdx = runCatching { codec.dequeueInputBuffer(50_000L) }.getOrDefault(-1)
                if (inIdx >= 0) {
                    runCatching {
                        codec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    }
                }
                eosSent = true
            }
        }

        private fun drainAudioLoop() {
            val codec = audioEncoder ?: return
            val info = MediaCodec.BufferInfo()
            var lastAudioPtsUs = -1L
            var stopDeadlineMs = -1L
            while (true) {
                val idx = runCatching { codec.dequeueOutputBuffer(info, 10_000L) }.getOrDefault(-1)
                when {
                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        synchronized(muxerLock) {
                            if (audioTrackIndex < 0) {
                                audioTrackIndex = muxer?.addTrack(codec.outputFormat) ?: -1
                                tryStartMuxerLocked()
                            }
                        }
                    }
                    idx >= 0 -> {
                        val buf = codec.getOutputBuffer(idx)
                        if (buf != null && info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                            val clamped = info.presentationTimeUs.coerceAtLeast(0L)
                            info.presentationTimeUs = if (lastAudioPtsUs >= 0L && clamped <= lastAudioPtsUs) {
                                lastAudioPtsUs + 1L
                            } else {
                                clamped
                            }
                            lastAudioPtsUs = info.presentationTimeUs
                            synchronized(muxerLock) {
                                if (!muxerStarted) waitForMuxerStartLocked()
                                if (muxerStarted && audioTrackIndex >= 0) {
                                    buf.position(info.offset)
                                    buf.limit(info.offset + info.size)
                                    runCatching { muxer?.writeSampleData(audioTrackIndex, buf, info) }
                                }
                            }
                        }
                        runCatching { codec.releaseOutputBuffer(idx, false) }
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                    }
                    !running.get() && audioQueue.isEmpty() -> {
                        if (stopDeadlineMs < 0L) {
                            stopDeadlineMs = System.currentTimeMillis() + 800L
                        } else if (System.currentTimeMillis() >= stopDeadlineMs) {
                            break
                        }
                    }
                }
            }
        }

        fun stopAndSaveToMediaStore(displayName: String = "GXV_VID_${System.currentTimeMillis()}.mp4"): Uri? {
            running.set(false)
            runCatching { videoEncoder?.signalEndOfInputStream() }
            synchronized(muxerLock) { muxerLock.notifyAll() }

            runCatching { videoDrainThread?.join(1500L) }
            runCatching { audioFeedThread?.join(1500L) }
            runCatching { audioDrainThread?.join(1500L) }

            cleanupResources()

            val file = tempFile ?: return null
            if (!file.exists() || file.length() <= 0L) {
                file.delete()
                return null
            }

            return try {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.Video.Media.RELATIVE_PATH, "DCIM/GXVcam")
                        put(MediaStore.Video.Media.IS_PENDING, 1)
                    }
                }
                val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                    ?: return null
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    FileInputStream(file).use { input ->
                        input.copyTo(out)
                    }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val done = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
                    context.contentResolver.update(uri, done, null, null)
                }
                uri
            } catch (e: Exception) {
                Log.e(TAG, "Failed saving MP4 to MediaStore: ${e.message}", e)
                null
            } finally {
                file.delete()
            }
        }

        private fun cleanupResources() {
            runCatching { videoEncoder?.stop() }
            runCatching { videoEncoder?.release() }
            videoEncoder = null

            runCatching { audioEncoder?.stop() }
            runCatching { audioEncoder?.release() }
            audioEncoder = null

            runCatching { inputSurface?.release() }
            inputSurface = null

            synchronized(muxerLock) {
                if (muxerStarted) {
                    runCatching { muxer?.stop() }
                }
                muxerStarted = false
                runCatching { muxer?.release() }
                muxer = null
            }
        }
    }
}
