package org.gxvcam.video

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.camera.core.CameraEffect
import androidx.camera.core.SurfaceOutput
import androidx.camera.core.SurfaceProcessor
import androidx.camera.core.SurfaceRequest
import androidx.core.util.Consumer
import org.gxvcam.camera.PantoneLutGenerator
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.pow

/**
 * OpenGL ES 3.0 CameraX [SurfaceProcessor] running `video_npu_lut_vibrance.frag` at 60 fps
 * on the ARM Mali-G615 GPU.
 *
 * Outputs graded frames simultaneously to:
 * 1) CameraX [SurfaceOutput] (`PreviewView` live viewfinder)
 * 2) Optional active [MediaCodec] encoder input [Surface] via `EGLExt.eglPresentationTimeANDROID`
 */
class GxvVideoGlRenderer(
    private val zoomController: SeamlessLensZoomController,
    private val onColorStateStepped: ((CrossLensColorState) -> Unit)? = null
) : SurfaceProcessor, AutoCloseable {

    companion object {
        private const val TAG = "GxvVideoGlRenderer"
        private const val LUT_SIZE = 33

        private const val VERTEX_SHADER = """#version 300 es
layout(location = 0) in vec4 aPosition;
layout(location = 1) in vec4 aTexCoord;
uniform mat4 uTexMatrix;
out vec2 vTexCoord;
void main() {
    gl_Position = aPosition;
    vTexCoord = (uTexMatrix * aTexCoord).xy;
}
"""

        // Keep synchronized with app/src/main/shaders/video_npu_lut_vibrance.frag
        private const val FRAGMENT_SHADER = """#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require

precision highp float;
precision highp sampler3D;

in vec2 vTexCoord;
out vec4 fragColor;

uniform samplerExternalOES uCameraTex;
uniform sampler3D uLut0;
uniform sampler3D uLut1;
uniform sampler3D uLut2;
uniform sampler3D uLut3;

uniform vec4 uLutWeights;
uniform vec3 uUnifiedRggbGains;
uniform float uTransitionBlend;
uniform float uLutMix;
uniform float uVibrance;
uniform float uLogCurveEnabled;

vec3 srgbToLinear(vec3 c) {
    vec3 cl = clamp(c, vec3(0.0), vec3(1.0));
    vec3 lo = cl / 12.92;
    vec3 hi = pow((cl + 0.055) / 1.055, vec3(2.4));
    return mix(lo, hi, step(vec3(0.04045), cl));
}

vec3 linearToSrgb(vec3 c) {
    vec3 cl = clamp(c, vec3(0.0), vec3(1.0));
    vec3 lo = 12.92 * cl;
    vec3 hi = 1.055 * pow(cl, vec3(1.0 / 2.4)) - 0.055;
    return mix(lo, hi, step(vec3(0.0031308), cl));
}

vec3 linearToGxvLog(vec3 lin) {
    const float cut = 0.0078125;
    const float a = 10.5402377416545;
    const float b = 0.0729055341958355;
    vec3 x = max(lin, vec3(0.0));
    vec3 logPart = (log2(x) + 9.72) / 17.52;
    vec3 linPart = a * x + b;
    return clamp(mix(linPart, logPart, step(vec3(cut), x)), vec3(0.0), vec3(1.0));
}

vec3 applySkinProtectedVibrance(vec3 rgb, float vibrance) {
    float luma = dot(rgb, vec3(0.2126, 0.7152, 0.0722));
    vec3 chroma = rgb - vec3(luma);
    float rgDiff = rgb.r - rgb.g;
    float gbDiff = rgb.g - rgb.b;
    float skinMask = clamp(1.0 - abs((rgDiff - 0.14) * 3.5), 0.0, 1.0) *
                     clamp(gbDiff * 4.0, 0.0, 1.0);
    float sat = max(max(rgb.r, rgb.g), rgb.b) - min(min(rgb.r, rgb.g), rgb.b);
    float boost = vibrance * (1.0 - sat) * (1.0 - 0.82 * skinMask);
    return clamp(vec3(luma) + chroma * (1.0 + boost), vec3(0.0), vec3(1.0));
}

void main() {
    vec3 src = texture(uCameraTex, vTexCoord).rgb;
    vec3 lin = srgbToLinear(src) * max(uUnifiedRggbGains, vec3(0.25));
    lin *= mix(0.96, 1.0, clamp(uTransitionBlend, 0.0, 1.0));
    vec3 gradedInput = linearToSrgb(lin);

    vec3 coord = clamp(gradedInput, vec3(0.0), vec3(1.0));
    vec3 lutColor =
        texture(uLut0, coord).rgb * uLutWeights.x +
        texture(uLut1, coord).rgb * uLutWeights.y +
        texture(uLut2, coord).rgb * uLutWeights.z +
        texture(uLut3, coord).rgb * uLutWeights.w;

    vec3 blended = mix(gradedInput, lutColor, clamp(uLutMix, 0.0, 1.0));
    vec3 finalRgb = (uLogCurveEnabled > 0.5)
        ? linearToGxvLog(srgbToLinear(blended))
        : applySkinProtectedVibrance(blended, uVibrance);

    fragColor = vec4(clamp(finalRgb, vec3(0.0), vec3(1.0)), 1.0);
}
"""

        private val QUAD_COORDS = floatArrayOf(
            -1f, -1f, 0f, 1f,
             1f, -1f, 0f, 1f,
            -1f,  1f, 0f, 1f,
             1f,  1f, 0f, 1f
        )

        private val TEX_COORDS = floatArrayOf(
            0f, 0f, 0f, 1f,
            1f, 0f, 0f, 1f,
            0f, 1f, 0f, 1f,
            1f, 1f, 0f, 1f
        )
    }

    private val glThread = HandlerThread("GxvVideoGL").apply { start() }
    val glHandler = Handler(glThread.looper)
    val glExecutor = Executor { runnable ->
        if (!released.get()) {
            glHandler.post(runnable)
        }
    }

    private val released = AtomicBoolean(false)

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglConfig: EGLConfig? = null
    private var pbufferSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    private var program = 0
    private var oesTexId = 0
    private val lutTexIds = IntArray(4)

    private var uTexMatrixLoc = -1
    private var uCameraTexLoc = -1
    private val uLutLocs = IntArray(4) { -1 }
    private var uLutWeightsLoc = -1
    private var uUnifiedRggbGainsLoc = -1
    private var uTransitionBlendLoc = -1
    private var uLutMixLoc = -1
    private var uVibranceLoc = -1
    private var uLogCurveEnabledLoc = -1

    private val quadBuffer: FloatBuffer = ByteBuffer.allocateDirect(QUAD_COORDS.size * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(QUAD_COORDS); position(0) }
    private val texBuffer: FloatBuffer = ByteBuffer.allocateDirect(TEX_COORDS.size * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(TEX_COORDS); position(0) }

    private var activeInputTexture: SurfaceTexture? = null
    private var activeInputSurface: Surface? = null

    private var activeOutputSurfaceObj: SurfaceOutput? = null
    private var outputEglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var outputSize: Size = Size(1920, 1080)
    private var directSensorOrientation: Int = 90

    private var encoderSurface: Surface? = null
    private var encoderEglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var encoderSize: Size = Size(1920, 1080)
    private var encoderStartTimeNs: Long = -1L
    private var inputResolution: Size = Size(1920, 1080)

    private val rawMatrix = FloatArray(16)
    private val outputMatrix = FloatArray(16)
    private val encoderMatrix = FloatArray(16)
    private val cropMatrix = FloatArray(16)

    @Volatile var lutWeights = floatArrayOf(0f, 0f, 1f, 0f)
    @Volatile var lutMix = 0.72f
    @Volatile var vibrance = 0.18f
    @Volatile var isLogCurveEnabled = false
    @Volatile var isAttached: Boolean = false
        private set

    fun createCameraEffect(onError: (Throwable) -> Unit): CameraEffect {
        return object : CameraEffect(
            PREVIEW,
            glExecutor,
            this@GxvVideoGlRenderer,
            Consumer { err -> onError(err) }
        ) {}
    }

    fun attachEncoderSurface(surface: Surface?, width: Int, height: Int) {
        glHandler.post {
            if (encoderEglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, encoderEglSurface)
                encoderEglSurface = EGL14.EGL_NO_SURFACE
            }
            encoderSurface = surface
            encoderSize = Size(width, height)
            encoderStartTimeNs = -1L
            if (surface != null && eglDisplay != EGL14.EGL_NO_DISPLAY && eglConfig != null) {
                val attribs = intArrayOf(EGL14.EGL_NONE)
                encoderEglSurface = runCatching {
                    EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, surface, attribs, 0)
                }.getOrDefault(EGL14.EGL_NO_SURFACE)
            }
        }
    }

    fun attachDirectDisplaySurface(surface: Surface?, width: Int, height: Int, sensorOrientation: Int = 90) {
        glHandler.post {
            if (released.get()) return@post
            ensureEglInitialized()
            if (outputEglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, outputEglSurface)
                outputEglSurface = EGL14.EGL_NO_SURFACE
            }
            activeOutputSurfaceObj?.close()
            activeOutputSurfaceObj = null
            outputSize = Size(width.coerceAtLeast(1), height.coerceAtLeast(1))
            directSensorOrientation = sensorOrientation
            if (surface != null && surface.isValid && eglDisplay != EGL14.EGL_NO_DISPLAY && eglConfig != null) {
                val attribs = intArrayOf(EGL14.EGL_NONE)
                outputEglSurface = runCatching {
                    EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, surface, attribs, 0)
                }.getOrDefault(EGL14.EGL_NO_SURFACE)
            }
        }
    }

    fun createDirectInputSurface(width: Int, height: Int, onReady: (Surface) -> Unit) {
        glHandler.post {
            if (released.get()) return@post
            ensureEglInitialized()
            inputResolution = Size(width, height)
            val tex = SurfaceTexture(oesTexId).apply {
                setDefaultBufferSize(width, height)
            }
            val surface = Surface(tex)

            activeInputTexture?.setOnFrameAvailableListener(null)
            activeInputTexture?.release()
            activeInputSurface?.release()

            activeInputTexture = tex
            activeInputSurface = surface
            isAttached = true

            tex.setOnFrameAvailableListener({ st ->
                drawFrame(st)
            }, glHandler)
            onReady(surface)
        }
    }

    fun updateDirectInputResolution(width: Int, height: Int, sensorOrientation: Int) {
        glHandler.post {
            if (released.get()) return@post
            inputResolution = Size(width.coerceAtLeast(1), height.coerceAtLeast(1))
            directSensorOrientation = sensorOrientation
            activeInputTexture?.setDefaultBufferSize(inputResolution.width, inputResolution.height)
        }
    }

    override fun onInputSurface(request: SurfaceRequest) {
        if (released.get()) {
            request.willNotProvideSurface()
            return
        }
        ensureEglInitialized()

        val resolution = request.resolution
        inputResolution = resolution
        val tex = SurfaceTexture(oesTexId).apply {
            setDefaultBufferSize(resolution.width, resolution.height)
        }
        val surface = Surface(tex)

        activeInputTexture?.setOnFrameAvailableListener(null)
        activeInputTexture?.release()
        activeInputSurface?.release()

        activeInputTexture = tex
        activeInputSurface = surface
        isAttached = true

        tex.setOnFrameAvailableListener({ st ->
            drawFrame(st)
        }, glHandler)

        request.provideSurface(surface, glExecutor) {
            tex.setOnFrameAvailableListener(null)
            tex.release()
            surface.release()
            if (activeInputTexture === tex) {
                activeInputTexture = null
                activeInputSurface = null
                isAttached = false
            }
        }
    }

    override fun onOutputSurface(surfaceOutput: SurfaceOutput) {
        if (released.get()) {
            surfaceOutput.close()
            return
        }
        ensureEglInitialized()

        if (outputEglSurface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglDestroySurface(eglDisplay, outputEglSurface)
            outputEglSurface = EGL14.EGL_NO_SURFACE
        }
        activeOutputSurfaceObj?.close()
        activeOutputSurfaceObj = surfaceOutput
        outputSize = surfaceOutput.size

        val surface = surfaceOutput.getSurface(glExecutor) {
            surfaceOutput.close()
            if (activeOutputSurfaceObj === surfaceOutput) {
                if (outputEglSurface != EGL14.EGL_NO_SURFACE && eglDisplay != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglDestroySurface(eglDisplay, outputEglSurface)
                    outputEglSurface = EGL14.EGL_NO_SURFACE
                }
                activeOutputSurfaceObj = null
            }
        }

        val attribs = intArrayOf(EGL14.EGL_NONE)
        outputEglSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, surface, attribs, 0)
    }

    private fun ensureEglInitialized() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) return

        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        EGL14.eglInitialize(eglDisplay, version, 0, version, 1)

        val configAttribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
            EGLExt.EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0)
        eglConfig = configs[0]

        val ctxAttribs = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 3,
            EGL14.EGL_NONE
        )
        eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)

        val pbAttribs = intArrayOf(
            EGL14.EGL_WIDTH, 1,
            EGL14.EGL_HEIGHT, 1,
            EGL14.EGL_NONE
        )
        pbufferSurface = EGL14.eglCreatePbufferSurface(eglDisplay, eglConfig, pbAttribs, 0)
        EGL14.eglMakeCurrent(eglDisplay, pbufferSurface, pbufferSurface, eglContext)

        program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        uTexMatrixLoc = GLES30.glGetUniformLocation(program, "uTexMatrix")
        uCameraTexLoc = GLES30.glGetUniformLocation(program, "uCameraTex")
        for (i in 0 until 4) {
            uLutLocs[i] = GLES30.glGetUniformLocation(program, "uLut$i")
        }
        uLutWeightsLoc = GLES30.glGetUniformLocation(program, "uLutWeights")
        uUnifiedRggbGainsLoc = GLES30.glGetUniformLocation(program, "uUnifiedRggbGains")
        uTransitionBlendLoc = GLES30.glGetUniformLocation(program, "uTransitionBlend")
        uLutMixLoc = GLES30.glGetUniformLocation(program, "uLutMix")
        uVibranceLoc = GLES30.glGetUniformLocation(program, "uVibrance")
        uLogCurveEnabledLoc = GLES30.glGetUniformLocation(program, "uLogCurveEnabled")

        val tex = IntArray(1)
        GLES30.glGenTextures(1, tex, 0)
        oesTexId = tex[0]
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexId)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

        uploadCinema3DLuts()
    }

    private fun uploadCinema3DLuts() {
        GLES30.glGenTextures(4, lutTexIds, 0)
        // 33x33x33 RGB8 has 99-byte rows (not divisible by 4); pack alignment 1 prevents row skew.
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        val pantoneData = PantoneLutGenerator.build33x33x33Lut()
        for (lutIdx in 0 until 4) {
            val buf = buildCinemaLutBytes(lutIdx, pantoneData)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTexIds[lutIdx])
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_R, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexImage3D(
                GLES30.GL_TEXTURE_3D,
                0,
                GLES30.GL_RGB8,
                LUT_SIZE,
                LUT_SIZE,
                LUT_SIZE,
                0,
                GLES30.GL_RGB,
                GLES30.GL_UNSIGNED_BYTE,
                buf
            )
        }
    }

    private fun buildCinemaLutBytes(styleIdx: Int, pantoneBase: FloatArray): ByteBuffer {
        val count = LUT_SIZE * LUT_SIZE * LUT_SIZE * 3
        val buffer = ByteBuffer.allocateDirect(count)
        for (i in 0 until count step 3) {
            val r = pantoneBase[i]
            val g = pantoneBase[i + 1]
            val b = pantoneBase[i + 2]
            val (outR, outG, outB) = when (styleIdx) {
                // 0: Vivo Natural (gentle contrast + crisp neutral highlights)
                0 -> Triple(
                    r.pow(0.98f),
                    g.pow(0.98f),
                    (b * 1.01f).coerceIn(0f, 1f)
                )
                // 1: Oppo Lumo Film (warm golden midtones, deep filmic blacks, teal shadows)
                1 -> {
                    val luma = 0.2126f * r + 0.7152f * g + 0.0722f * b
                    val shadowLift = (1f - luma).coerceIn(0f, 1f) * 0.018f
                    Triple(
                        (r * 1.02f).coerceIn(0f, 1f),
                        (g * 1.00f + shadowLift * 0.4f).coerceIn(0f, 1f),
                        (b * 0.96f + shadowLift).coerceIn(0f, 1f)
                    )
                }
                // 2: Moto Pantone Cinema (calibrated skin + sky fidelity)
                2 -> Triple(r, g, b)
                // 3: Apple ACES Log Neutral (soft shoulder roll-off)
                else -> Triple(
                    (r / (1f + 0.12f * r)) * 1.12f,
                    (g / (1f + 0.12f * g)) * 1.12f,
                    (b / (1f + 0.12f * b)) * 1.12f
                )
            }
            buffer.put((outR.coerceIn(0f, 1f) * 255f).toInt().toByte())
            buffer.put((outG.coerceIn(0f, 1f) * 255f).toInt().toByte())
            buffer.put((outB.coerceIn(0f, 1f) * 255f).toInt().toByte())
        }
        buffer.position(0)
        return buffer
    }

    private fun drawFrame(st: SurfaceTexture) {
        if (released.get() || eglDisplay == EGL14.EGL_NO_DISPLAY) return
        EGL14.eglMakeCurrent(eglDisplay, pbufferSurface, pbufferSurface, eglContext)
        runCatching { st.updateTexImage() }.onFailure { return }
        st.getTransformMatrix(rawMatrix)

        val colorState = zoomController.advanceFrame()
        onColorStateStepped?.invoke(colorState)
        val wbRgb = colorState.toRgbShaderMultipliers()
        val weights = lutWeights

        // 1. Render to CameraX Preview SurfaceOutput or direct Aux Camera2 TextureView Surface
        val outSurf = activeOutputSurfaceObj
        if (outSurf != null && outputEglSurface != EGL14.EGL_NO_SURFACE) {
            outSurf.updateTransformMatrix(outputMatrix, rawMatrix)
            renderQuadToSurface(
                eglSurface = outputEglSurface,
                width = outputSize.width,
                height = outputSize.height,
                texMatrix = outputMatrix,
                wbRgb = wbRgb,
                transitionBlend = colorState.transitionBlendFactor,
                weights = weights,
                presentationTimeNs = 0L
            )
        } else if (outputEglSurface != EGL14.EGL_NO_SURFACE) {
            Matrix.setIdentityM(cropMatrix, 0)
            val isPortraitSensor = (directSensorOrientation % 180) != 0
            val contentW = if (isPortraitSensor) inputResolution.height else inputResolution.width
            val contentH = if (isPortraitSensor) inputResolution.width else inputResolution.height
            val inAspect = contentW.toFloat() / contentH.coerceAtLeast(1)
            val outAspect = outputSize.width.toFloat() / outputSize.height.coerceAtLeast(1)
            Matrix.translateM(cropMatrix, 0, 0.5f, 0.5f, 0f)
            Matrix.rotateM(cropMatrix, 0, directSensorOrientation.toFloat(), 0f, 0f, 1f)
            if (kotlin.math.abs(inAspect - outAspect) > 0.01f) {
                val scaleX = if (inAspect > outAspect) outAspect / inAspect else 1f
                val scaleY = if (inAspect < outAspect) inAspect / outAspect else 1f
                Matrix.scaleM(cropMatrix, 0, scaleX, scaleY, 1f)
            }
            Matrix.translateM(cropMatrix, 0, -0.5f, -0.5f, 0f)
            Matrix.multiplyMM(outputMatrix, 0, rawMatrix, 0, cropMatrix, 0)
            renderQuadToSurface(
                eglSurface = outputEglSurface,
                width = outputSize.width,
                height = outputSize.height,
                texMatrix = outputMatrix,
                wbRgb = wbRgb,
                transitionBlend = colorState.transitionBlendFactor,
                weights = weights,
                presentationTimeNs = 0L
            )
        }

        // 2. Render to active MediaCodec encoder Surface (if recording)
        if (encoderEglSurface != EGL14.EGL_NO_SURFACE) {
            val frameTsNs = st.timestamp.let { ts ->
                val rawTs = if (ts > 0L) ts else System.nanoTime()
                if (encoderStartTimeNs > 0L && rawTs <= encoderStartTimeNs) {
                    encoderStartTimeNs + 1_000_000L
                } else {
                    rawTs
                }
            }
            encoderStartTimeNs = frameTsNs
            Matrix.setIdentityM(cropMatrix, 0)
            val inAspect = inputResolution.width.toFloat() / inputResolution.height.coerceAtLeast(1)
            val outAspect = encoderSize.width.toFloat() / encoderSize.height.coerceAtLeast(1)
            if (kotlin.math.abs(inAspect - outAspect) > 0.02f) {
                val scaleX = if (inAspect > outAspect) outAspect / inAspect else 1f
                val scaleY = if (inAspect < outAspect) inAspect / outAspect else 1f
                Matrix.translateM(cropMatrix, 0, 0.5f, 0.5f, 0f)
                Matrix.scaleM(cropMatrix, 0, scaleX, scaleY, 1f)
                Matrix.translateM(cropMatrix, 0, -0.5f, -0.5f, 0f)
            }
            Matrix.multiplyMM(encoderMatrix, 0, rawMatrix, 0, cropMatrix, 0)
            renderQuadToSurface(
                eglSurface = encoderEglSurface,
                width = encoderSize.width,
                height = encoderSize.height,
                texMatrix = encoderMatrix,
                wbRgb = wbRgb,
                transitionBlend = colorState.transitionBlendFactor,
                weights = weights,
                presentationTimeNs = frameTsNs
            )
        }
    }

    private fun renderQuadToSurface(
        eglSurface: EGLSurface,
        width: Int,
        height: Int,
        texMatrix: FloatArray,
        wbRgb: FloatArray,
        transitionBlend: Float,
        weights: FloatArray,
        presentationTimeNs: Long
    ) {
        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) return
        GLES30.glViewport(0, 0, width, height)
        GLES30.glUseProgram(program)

        GLES30.glUniformMatrix4fv(uTexMatrixLoc, 1, false, texMatrix, 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexId)
        GLES30.glUniform1i(uCameraTexLoc, 0)

        for (i in 0 until 4) {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE1 + i)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTexIds[i])
            GLES30.glUniform1i(uLutLocs[i], 1 + i)
        }

        GLES30.glUniform4f(
            uLutWeightsLoc,
            weights.getOrElse(0) { 0f },
            weights.getOrElse(1) { 0f },
            weights.getOrElse(2) { 1f },
            weights.getOrElse(3) { 0f }
        )
        GLES30.glUniform3f(uUnifiedRggbGainsLoc, wbRgb[0], wbRgb[1], wbRgb[2])
        GLES30.glUniform1f(uTransitionBlendLoc, transitionBlend)
        GLES30.glUniform1f(uLutMixLoc, lutMix)
        GLES30.glUniform1f(uVibranceLoc, vibrance)
        GLES30.glUniform1f(uLogCurveEnabledLoc, if (isLogCurveEnabled) 1f else 0f)

        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 4, GLES30.GL_FLOAT, false, 0, quadBuffer)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 4, GLES30.GL_FLOAT, false, 0, texBuffer)

        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)

        GLES30.glDisableVertexAttribArray(0)
        GLES30.glDisableVertexAttribArray(1)

        if (presentationTimeNs > 0L) {
            EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, presentationTimeNs)
        }
        EGL14.eglSwapBuffers(eglDisplay, eglSurface)
    }

    private fun createProgram(vertexSrc: String, fragmentSrc: String): Int {
        val vs = compileShader(GLES30.GL_VERTEX_SHADER, vertexSrc)
        val fs = compileShader(GLES30.GL_FRAGMENT_SHADER, fragmentSrc)
        val prog = GLES30.glCreateProgram()
        GLES30.glAttachShader(prog, vs)
        GLES30.glAttachShader(prog, fs)
        GLES30.glLinkProgram(prog)
        val linkStatus = IntArray(1)
        GLES30.glGetProgramiv(prog, GLES30.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] == 0) {
            val log = GLES30.glGetProgramInfoLog(prog)
            GLES30.glDeleteProgram(prog)
            throw IllegalStateException("GLES program link failed: $log")
        }
        GLES30.glDeleteShader(vs)
        GLES30.glDeleteShader(fs)
        return prog
    }

    private fun compileShader(type: Int, src: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, src)
        GLES30.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            val log = GLES30.glGetShaderInfoLog(shader)
            GLES30.glDeleteShader(shader)
            throw IllegalStateException("GLES shader compile failed: $log")
        }
        return shader
    }

    override fun close() {
        if (!released.compareAndSet(false, true)) return
        glHandler.post {
            activeInputTexture?.setOnFrameAvailableListener(null)
            activeInputTexture?.release()
            activeInputSurface?.release()
            activeOutputSurfaceObj?.close()

            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                if (outputEglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, outputEglSurface)
                if (encoderEglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, encoderEglSurface)
                if (pbufferSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, pbufferSurface)
                if (program != 0) GLES30.glDeleteProgram(program)
                if (oesTexId != 0) GLES30.glDeleteTextures(1, intArrayOf(oesTexId), 0)
                GLES30.glDeleteTextures(4, lutTexIds, 0)
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                EGL14.eglDestroyContext(eglDisplay, eglContext)
                EGL14.eglTerminate(eglDisplay)
            }
            eglDisplay = EGL14.EGL_NO_DISPLAY
            eglContext = EGL14.EGL_NO_CONTEXT
            glThread.quitSafely()
        }
    }
}
