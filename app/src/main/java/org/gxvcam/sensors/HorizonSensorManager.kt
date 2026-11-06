package org.gxvcam.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.atan2

class HorizonSensorManager(
    context: Context,
    private val onTiltChanged: (Float) -> Unit
) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
        ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private var smoothedAngle: Float = 0f
    private var isFirstSample: Boolean = true
    private val alpha: Float = 0.15f

    fun start() {
        val targetSensor = sensor ?: return
        sensorManager.registerListener(this, targetSensor, SensorManager.SENSOR_DELAY_UI)
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val x = event.values[0]
        val y = event.values[1]

        val rawDegrees = atan2(x.toDouble(), y.toDouble()).toFloat() * (180f / Math.PI.toFloat())

        if (isFirstSample) {
            smoothedAngle = rawDegrees
            isFirstSample = false
        } else {
            smoothedAngle += alpha * (rawDegrees - smoothedAngle)
        }

        onTiltChanged(smoothedAngle)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // No-op
    }
}
