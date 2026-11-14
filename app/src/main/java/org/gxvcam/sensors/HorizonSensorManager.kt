package org.gxvcam.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import kotlin.math.atan2

/**
 * Calibrated Gyro/Gravity horizon leveler based on Motorola Camera 5 (MotCamera5)
 * [MultiSensorOrientationEventListener] and [LevelerComponent] logic.
 *
 * Smooths the 3D gravity vector `(x, y, z)` prior to `atan2` so there is zero
 * wrap-around discontinuity at `±45°` or `0°/360°`, and preserves cardinal orientation
 * (`0, 90, 180, 270`) so portrait-locked viewfinders align the horizon bar in landscape.
 */
class HorizonSensorManager(
    context: Context,
    private val onTiltChanged: (Float) -> Unit,
    private val onOrientationChanged: ((cardinalAngle: Int, surfaceRotation: Int) -> Unit)? = null,
    private val onPlanarChanged: ((Boolean) -> Unit)? = null
) : SensorEventListener {

    constructor(context: Context, onTiltChanged: (Float) -> Unit) : this(context, onTiltChanged, null, null)

    constructor(
        context: Context,
        onTiltChanged: (Float) -> Unit,
        onOrientationChanged: ((cardinalAngle: Int, surfaceRotation: Int) -> Unit)?
    ) : this(context, onTiltChanged, onOrientationChanged, null)

    data class HorizonSample(
        val relativeRollDegrees: Float,
        val cardinalAngle: Int,
        val surfaceRotation: Int,
        val isPlanar: Boolean
    )

    companion object {
        private const val HYSTERESIS_DEG = 5.0f

        fun surfaceRotationFor(cardinalAngle: Int): Int = when (cardinalAngle) {
            90 -> Surface.ROTATION_270
            180 -> Surface.ROTATION_180
            270 -> Surface.ROTATION_90
            else -> Surface.ROTATION_0
        }

        fun computeHorizonSample(
            x: Float,
            y: Float,
            z: Float,
            previousCardinal: Int = -1
        ): HorizonSample {
            val xySquared = x * x + y * y
            val zSquared = z * z
            val isPlanar = 4.0f * xySquared < zSquared

            if (isPlanar) {
                val safeCardinal = if (previousCardinal in setOf(0, 90, 180, 270)) previousCardinal else 0
                return HorizonSample(
                    relativeRollDegrees = 0f,
                    cardinalAngle = safeCardinal,
                    surfaceRotation = surfaceRotationFor(safeCardinal),
                    isPlanar = true
                )
            }

            val angleRad = atan2(y.toDouble(), -x.toDouble())
            var deg = (90.0 - Math.toDegrees(angleRad)).toFloat()
            while (deg >= 360f) deg -= 360f
            while (deg < 0f) deg += 360f

            val cardinalAngle = when (previousCardinal) {
                0 -> if (deg < 45f + HYSTERESIS_DEG || deg >= 315f - HYSTERESIS_DEG) 0 else snapCardinal(deg)
                90 -> if (deg in (45f - HYSTERESIS_DEG)..(135f + HYSTERESIS_DEG)) 90 else snapCardinal(deg)
                180 -> if (deg in (135f - HYSTERESIS_DEG)..(225f + HYSTERESIS_DEG)) 180 else snapCardinal(deg)
                270 -> if (deg in (225f - HYSTERESIS_DEG)..(315f + HYSTERESIS_DEG)) 270 else snapCardinal(deg)
                else -> snapCardinal(deg)
            }

            var relativeRoll = deg - cardinalAngle.toFloat()
            while (relativeRoll > 180f) relativeRoll -= 360f
            while (relativeRoll <= -180f) relativeRoll += 360f

            return HorizonSample(
                relativeRollDegrees = relativeRoll,
                cardinalAngle = cardinalAngle,
                surfaceRotation = surfaceRotationFor(cardinalAngle),
                isPlanar = false
            )
        }

        private fun snapCardinal(deg: Float): Int = when {
            deg >= 45f && deg < 135f -> 90
            deg >= 135f && deg < 225f -> 180
            deg >= 225f && deg < 315f -> 270
            else -> 0
        }
    }

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    // Motorola Camera 5 prefers Sensor.TYPE_GRAVITY (ID 9), falls back to Accelerometer (ID 1)
    private val sensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
        ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    // 3D gravity vector rolling average pool (size = 10) prevents ±45° quadrant wrap discontinuities
    private val poolSize = 10
    private val poolX = FloatArray(poolSize)
    private val poolY = FloatArray(poolSize)
    private val poolZ = FloatArray(poolSize)
    private var poolIndex = 0
    private var poolCount = 0
    private var lastCardinalAngle = -1
    private var lastPlanar: Boolean? = null
    private var lastDispatchedRoll: Float = Float.NaN

    fun start() {
        val targetSensor = sensor ?: return
        sensorManager.registerListener(this, targetSensor, SensorManager.SENSOR_DELAY_UI)
    }

    fun stop() {
        sensorManager.unregisterListener(this)
        poolCount = 0
        poolIndex = 0
        lastPlanar = null
        lastDispatchedRoll = Float.NaN
    }

    override fun onSensorChanged(event: SensorEvent) {
        poolX[poolIndex] = event.values[0]
        poolY[poolIndex] = event.values[1]
        poolZ[poolIndex] = event.values[2]
        poolIndex = (poolIndex + 1) % poolSize
        if (poolCount < poolSize) poolCount++

        var sumX = 0f
        var sumY = 0f
        var sumZ = 0f
        for (i in 0 until poolCount) {
            sumX += poolX[i]
            sumY += poolY[i]
            sumZ += poolZ[i]
        }
        val inv = 1.0f / poolCount.toFloat()
        val sample = computeHorizonSample(
            x = sumX * inv,
            y = sumY * inv,
            z = sumZ * inv,
            previousCardinal = lastCardinalAngle
        )

        if (sample.isPlanar != lastPlanar) {
            lastPlanar = sample.isPlanar
            onPlanarChanged?.invoke(sample.isPlanar)
        }

        if (!sample.isPlanar && sample.cardinalAngle != lastCardinalAngle) {
            lastCardinalAngle = sample.cardinalAngle
            onOrientationChanged?.invoke(sample.cardinalAngle, sample.surfaceRotation)
        }

        // Deadband filter (0.15 deg) suppresses continuous 60 Hz StateFlow churn & UI recomposition when stationary
        if (lastDispatchedRoll.isNaN() || kotlin.math.abs(sample.relativeRollDegrees - lastDispatchedRoll) >= 0.15f) {
            lastDispatchedRoll = sample.relativeRollDegrees
            onTiltChanged(sample.relativeRollDegrees)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // No-op
    }
}

