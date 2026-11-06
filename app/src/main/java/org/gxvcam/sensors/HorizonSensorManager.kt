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
 */
class HorizonSensorManager(
    context: Context,
    private val onTiltChanged: (Float) -> Unit,
    private val onOrientationChanged: ((cardinalAngle: Int, surfaceRotation: Int) -> Unit)? = null
) : SensorEventListener {

    constructor(context: Context, onTiltChanged: (Float) -> Unit) : this(context, onTiltChanged, null)

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    // Motorola Camera 5 prefers Sensor.TYPE_GRAVITY (ID 9), falls back to Accelerometer (ID 1)
    private val sensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
        ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    // Motorola Cam 5 rolling average filter (pool size = 10)
    private val poolSize = 10
    private val orientationPool = FloatArray(poolSize)
    private var poolIndex = 0
    private var poolCount = 0
    private var lastCardinalAngle = -1

    fun start() {
        val targetSensor = sensor ?: return
        sensorManager.registerListener(this, targetSensor, SensorManager.SENSOR_DELAY_UI)
    }

    fun stop() {
        sensorManager.unregisterListener(this)
        poolCount = 0
        poolIndex = 0
    }

    override fun onSensorChanged(event: SensorEvent) {
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]

        // 1. Motorola Cam 5 Planar check: if phone is lying flat on table or tilted > 60° towards floor/ceiling
        // 4.0f * (x^2 + y^2) < z^2
        val xySquared = x * x + y * y
        val zSquared = z * z
        val isPlanar = 4.0f * xySquared < zSquared

        if (isPlanar) {
            // When tilted flat facing floor or ceiling, horizon line is centered / neutral
            onTiltChanged(0f)
            return
        }

        // 2. Motorola Cam 5 orientation angle calculation:
        // atan2(y, -x) * 57.29577951308232 (180/PI)
        val angleRad = atan2(y.toDouble(), -x.toDouble())
        var deg = Math.round(angleRad * 57.29577951308232).toInt()
        deg = 90 - deg
        while (deg >= 360) deg -= 360
        while (deg < 0) deg += 360

        // 3. Normalize relative to nearest 90-degree cardinal orientation
        val relativeRoll = when {
            deg in 0..45 -> deg.toFloat()
            deg in 315..360 -> (deg - 360).toFloat()
            deg in 46..135 -> (deg - 90).toFloat() // Landscape 90°
            deg in 225..314 -> (deg - 270).toFloat() // Landscape 270°
            else -> (deg - 180).toFloat() // Upside down 180°
        }

        // 4. Calculate cardinal orientation (0, 90, 180, 270) & Surface rotation
        val cardinalAngle = when {
            deg in 45..134 -> 90
            deg in 135..224 -> 180
            deg in 225..314 -> 270
            else -> 0
        }
        val surfaceRotation = when (cardinalAngle) {
            90 -> Surface.ROTATION_270
            180 -> Surface.ROTATION_180
            270 -> Surface.ROTATION_90
            else -> Surface.ROTATION_0
        }
        if (cardinalAngle != lastCardinalAngle) {
            lastCardinalAngle = cardinalAngle
            onOrientationChanged?.invoke(cardinalAngle, surfaceRotation)
        }

        // 5. Moving average smoothing pool (Motorola Cam 5 mOrientationPool)
        orientationPool[poolIndex] = relativeRoll
        poolIndex = (poolIndex + 1) % poolSize
        if (poolCount < poolSize) poolCount++

        var sum = 0f
        for (i in 0 until poolCount) {
            sum += orientationPool[i]
        }
        val smoothed = sum / poolCount

        onTiltChanged(smoothed)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // No-op
    }
}
