package org.gxvcam

import java.nio.ByteBuffer

object NativeEngineBridge {
    init {
        try {
            System.loadLibrary("gxvcam_engine")
        } catch (e: UnsatisfiedLinkError) {
            e.printStackTrace()
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
}
