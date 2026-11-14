package org.gxvcam

import java.nio.ByteBuffer

object NativeEngineBridge {
    @Volatile
    var isNativeLoaded: Boolean = false
        private set

    init {
        isNativeLoaded = try {
            System.loadLibrary("gxvcam_engine")
            true
        } catch (_: UnsatisfiedLinkError) {
            false
        }
    }

    external fun unpackRaw16Native(
        directBuffer: ByteBuffer,
        bufferSize: Int,
        width: Int,
        height: Int,
        rowStrideBytes: Int,
        whiteLevel: Float,
        blackLevel: FloatArray
    ): Boolean

    external fun unpackRaw10Native(
        directBuffer: ByteBuffer,
        bufferSize: Int,
        width: Int,
        height: Int,
        rowStrideBytes: Int,
        whiteLevel: Float,
        blackLevel: FloatArray
    ): Boolean

    external fun processSignature27RgbaNative(
        directRgbaBuffer: ByteBuffer,
        width: Int,
        height: Int,
        lookProfileId: Int,
        shadowLift: Float,
        detailGain: Float,
        highlightKnee: Float,
        dehazeStrength: Float,
        skinSmoothing: Float
    ): Boolean

    external fun integrateGyroPsfNative(
        timestampsNs: LongArray,
        wx: FloatArray,
        wy: FloatArray,
        wz: FloatArray,
        intrinsics: FloatArray,
        outKernel: FloatArray
    ): Boolean

    external fun deblurRgbaWithGyroNative(
        directRgbaBuffer: ByteBuffer,
        width: Int,
        height: Int,
        kernel: FloatArray,
        kernelSize: Int,
        strength: Float
    ): Boolean

    external fun deblurRgbaWithGyroAndEventsNative(
        directRgbaBuffer: ByteBuffer,
        width: Int,
        height: Int,
        kernel: FloatArray,
        kernelSize: Int,
        eventMask: ByteArray?,
        maskWidth: Int,
        maskHeight: Int,
        strength: Float
    ): Boolean

    external fun fuseRaw16BurstToRgbaNative(
        directRawBuffers: Array<ByteBuffer>,
        width: Int,
        height: Int,
        rowStrideBytes: Int,
        whiteLevel: Float,
        blackLevel: FloatArray,
        evOffsets: FloatArray,
        cfaPattern: Int,
        lookProfileId: Int,
        highlightKnee: Float,
        outRgbaBuffer: ByteBuffer
    ): Boolean
}
