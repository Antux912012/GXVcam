package org.gxvcam.camera

import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.util.Log
import java.nio.ByteBuffer
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Thread-safe 14-slot RAW16 Zero-Shutter-Lag (ZSL) ring buffer tailored for MT6878 Imagiq 950.
 *
 * Immediately copies incoming ImageReader RAW16 bytes into pooled direct ByteBuffers and closes
 * the HAL [Image] in a `use {}` block so the Camera2 ImageReader pipeline never stalls.
 */
class ZslRawRingBuffer(
    val maxSlots: Int = DEFAULT_MAX_SLOTS
) : AutoCloseable {

    companion object {
        private const val TAG = "ZslRawRingBuffer"
        const val DEFAULT_MAX_SLOTS = 14
    }

    data class RawFrameMetadata(
        val timestampNs: Long,
        val width: Int,
        val height: Int,
        val rowStrideBytes: Int,
        val exposureNs: Long,
        val iso: Int,
        val whiteLevel: Float,
        val blackLevel: FloatArray,
        val cfaPattern: Int,
        val evOffset: Float
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is RawFrameMetadata) return false
            return timestampNs == other.timestampNs &&
                width == other.width &&
                height == other.height &&
                rowStrideBytes == other.rowStrideBytes &&
                exposureNs == other.exposureNs &&
                iso == other.iso &&
                whiteLevel == other.whiteLevel &&
                blackLevel.contentEquals(other.blackLevel) &&
                cfaPattern == other.cfaPattern &&
                evOffset == other.evOffset
        }

        override fun hashCode(): Int {
            var result = timestampNs.hashCode()
            result = 31 * result + width
            result = 31 * result + height
            result = 31 * result + rowStrideBytes
            result = 31 * result + exposureNs.hashCode()
            result = 31 * result + iso
            result = 31 * result + whiteLevel.hashCode()
            result = 31 * result + blackLevel.contentHashCode()
            result = 31 * result + cfaPattern
            result = 31 * result + evOffset.hashCode()
            return result
        }
    }

    data class RawSlot(
        val buffer: ByteBuffer,
        val meta: RawFrameMetadata
    )

    data class LockedRawBurst(
        val ev0Frames: List<RawSlot>,
        val evMinusFrames: List<RawSlot>,
        val lockedAtMs: Long = System.currentTimeMillis()
    ) {
        val totalFrames: Int get() = ev0Frames.size + evMinusFrames.size
    }

    data class ActionBracketPlan(
        val shortShutterNs: Long,
        val evMinusTarget: Float,
        val ev0Count: Int,
        val evMinusCount: Int
    )

    private val lock = ReentrantLock()
    private val slots = ArrayDeque<RawSlot>(maxSlots.coerceIn(2, 16))
    private val pendingResults = LinkedHashMap<Long, TotalCaptureResult>()
    private var droppedCount: Int = 0
    private var isClosed: Boolean = false

    val slotCount: Int
        get() = lock.withLock { slots.size }

    val totalDropped: Int
        get() = lock.withLock { droppedCount }

    /**
     * Records [TotalCaptureResult] by `SENSOR_TIMESTAMP` and pairs it with any already-arrived slot.
     */
    fun attachCaptureResult(result: TotalCaptureResult) {
        val ts = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: return
        lock.withLock {
            if (isClosed) return
            val existingIdx = slots.indexOfFirst { it.meta.timestampNs == ts }
            if (existingIdx >= 0) {
                val old = slots[existingIdx]
                slots[existingIdx] = old.copy(meta = enrichMetadata(old.meta, result))
            } else {
                pendingResults[ts] = result
                while (pendingResults.size > maxSlots * 2) {
                    val firstKey = pendingResults.keys.firstOrNull() ?: break
                    pendingResults.remove(firstKey)
                }
            }
        }
    }

    /**
     * Drains a Camera2 [Image] immediately into a direct [ByteBuffer] and closes [image]
     * inside a `use {}` block so the HAL surface never blocks.
     */
    fun offerImage(
        image: Image,
        defaultWhiteLevel: Float = 1023f,
        defaultBlackLevel: FloatArray = floatArrayOf(64f, 64f, 64f, 64f),
        cfaPattern: Int = 3,
        evOffset: Float = 0f
    ): Boolean {
        return try {
            image.use { img ->
                val plane = img.planes.firstOrNull() ?: return false
                val src = plane.buffer
                val size = src.remaining()
                if (size <= 0) return false

                val copy = ByteBuffer.allocateDirect(size)
                val dup = src.duplicate()
                copy.put(dup)
                copy.flip()

                val ts = img.timestamp
                val baseMeta = RawFrameMetadata(
                    timestampNs = ts,
                    width = img.width,
                    height = img.height,
                    rowStrideBytes = plane.rowStride,
                    exposureNs = 8_000_000L,
                    iso = 100,
                    whiteLevel = defaultWhiteLevel,
                    blackLevel = defaultBlackLevel.copyOf(4),
                    cfaPattern = cfaPattern,
                    evOffset = evOffset
                )

                offerDirectSlot(copy, baseMeta)
            }
        } catch (e: Exception) {
            Log.w(TAG, "offerImage failed safely: ${e.message}")
            false
        }
    }

    /**
     * Inserts a pre-copied direct [ByteBuffer] slot into the ring buffer (also used by unit tests).
     */
    fun offerDirectSlot(buffer: ByteBuffer, baseMeta: RawFrameMetadata): Boolean {
        lock.withLock {
            if (isClosed) return false
            val matchedResult = pendingResults.remove(baseMeta.timestampNs)
            val finalMeta = if (matchedResult != null) {
                enrichMetadata(baseMeta, matchedResult)
            } else {
                baseMeta
            }
            if (slots.size >= maxSlots) {
                slots.removeFirst()
                droppedCount++
            }
            slots.addLast(RawSlot(buffer, finalMeta))
            return true
        }
    }

    /**
     * Locks the most recent EV0 + EV-underexposed frames for Signature 27 LOFIC HDR fusion.
     */
    fun lockBurstPair(ev0Count: Int = 8, evMinusCount: Int = 2): LockedRawBurst {
        lock.withLock {
            val evMinus = slots.filter { it.meta.evOffset <= -0.5f }.takeLast(evMinusCount)
            val ev0 = slots.filter { it.meta.evOffset > -0.5f }.takeLast(ev0Count)
            return LockedRawBurst(
                ev0Frames = ev0,
                evMinusFrames = evMinus
            )
        }
    }

    /**
     * Computes interleaved short-exposure action bracket parameters for Synthetic EVS Deblur.
     */
    fun interleaveActionFrames(shortShutterNs: Long = 2_000_000L): ActionBracketPlan {
        val clampedShutter = shortShutterNs.coerceIn(100_000L, 10_000_000L)
        return ActionBracketPlan(
            shortShutterNs = clampedShutter,
            evMinusTarget = -2.5f,
            ev0Count = 8,
            evMinusCount = 2
        )
    }

    fun clear() {
        lock.withLock {
            slots.clear()
            pendingResults.clear()
        }
    }

    override fun close() {
        lock.withLock {
            isClosed = true
            slots.clear()
            pendingResults.clear()
        }
    }

    private fun enrichMetadata(base: RawFrameMetadata, result: TotalCaptureResult): RawFrameMetadata {
        val exp = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: base.exposureNs
        val iso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: base.iso
        val dynBl = result.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL)
        val bl = if (dynBl != null && dynBl.size >= 4) {
            floatArrayOf(dynBl[0], dynBl[1], dynBl[2], dynBl[3])
        } else {
            base.blackLevel
        }
        val wl = result.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL)?.toFloat() ?: base.whiteLevel
        return base.copy(
            exposureNs = exp,
            iso = iso,
            whiteLevel = wl,
            blackLevel = bl
        )
    }
}
