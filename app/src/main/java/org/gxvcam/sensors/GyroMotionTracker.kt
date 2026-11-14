package org.gxvcam.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 400 Hz Gyroscope motion tracker for Signature 27 Synthetic EVS Action Deblur
 * and Bayer pyramid alignment seeding.
 */
class GyroMotionTracker(
    context: Context,
    private val capacity: Int = 512
) : SensorEventListener {

    data class Sample(
        val timestampNs: Long,
        val wx: Float,
        val wy: Float,
        val wz: Float
    )

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gyroSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val lock = ReentrantLock()
    private val buffer = ArrayDeque<Sample>(capacity)

    fun start() {
        val sensor = gyroSensor ?: return
        // SENSOR_DELAY_FASTEST targets ~400 Hz on Edge 50 Neo IMU (requires HIGH_SAMPLING_RATE_SENSORS on API 31+)
        try {
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_FASTEST)
        } catch (_: SecurityException) {
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
        lock.withLock {
            buffer.clear()
        }
    }

    fun recordSyntheticSample(timestampNs: Long, wx: Float, wy: Float, wz: Float) {
        lock.withLock {
            if (buffer.size >= capacity) {
                buffer.removeFirst()
            }
            buffer.addLast(Sample(timestampNs, wx, wy, wz))
        }
    }

    fun snapshotRecent(count: Int = 16): List<Sample> {
        lock.withLock {
            return buffer.takeLast(count.coerceAtLeast(1))
        }
    }

    fun snapshotWindow(startNs: Long, endNs: Long): List<Sample> {
        lock.withLock {
            return buffer.filter { it.timestampNs in startNs..endNs }
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_GYROSCOPE || event.values.size < 3) return
        recordSyntheticSample(
            timestampNs = event.timestamp,
            wx = event.values[0],
            wy = event.values[1],
            wz = event.values[2]
        )
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // No-op
    }
}
