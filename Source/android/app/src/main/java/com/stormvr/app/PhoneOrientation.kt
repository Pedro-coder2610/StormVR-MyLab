package com.stormvr.app

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Orientacao do celular usado como CONTROLE 3DoF (a "ponta" e a borda de cima do celular, como um bastao).
 * yaw: graus, + = para a esquerda, relativo ao recentrar; pitch: + = para cima; roll: + = lado direito para cima.
 * Mesma convencao dos angulos enviados pelo ESP32, entao o driver e a sala tratam os dois igual.
 * A posicao (6DoF) vem do ArPose6 (ARCore), que usa [psiRaw]/[psiRef]/[fHoriz] daqui para alinhar o referencial da camera ao do giroscopio.
 */
class PhoneOrientation(context: Context) : SensorEventListener {
    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor: Sensor? =
        sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR) ?: sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val r = FloatArray(9)
    private var psi0 = 0f
    private var haveRef = false
    @Volatile private var recenterReq = false

    @Volatile var yaw = 0f
        private set
    @Volatile var pitch = 0f
        private set
    @Volatile var roll = 0f
        private set
    @Volatile var hasData = false
        private set
    /** Rumo bruto (graus) da ponta do celular no referencial do giroscopio, o rumo do recentrar e o quanto a ponta esta na horizontal (0..1). */
    @Volatile var psiRaw = 0f
        private set
    @Volatile var psiRef = 0f
        private set
    @Volatile var fHoriz = 0f
        private set

    fun available(): Boolean = sensor != null
    fun start(): Boolean = sensor != null && sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_FASTEST)
    fun stop() { sm.unregisterListener(this) }
    fun recenter() { recenterReq = true }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onSensorChanged(e: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(r, e.values)       // dispositivo -> mundo (leste, norte, cima)
        val fx = r[1]; val fy = r[4]; val fz = r[7]                  // eixo Y do celular (ponta) no mundo
        val psi = Math.toDegrees(atan2(fy, fx).toDouble()).toFloat()
        if (!haveRef || recenterReq) { psi0 = psi; haveRef = true; recenterReq = false }
        psiRaw = psi; psiRef = psi0
        var y = psi - psi0
        while (y > 180f) y -= 360f
        while (y < -180f) y += 360f
        yaw = y
        pitch = Math.toDegrees(asin(fz.coerceIn(-1f, 1f)).toDouble()).toFloat()
        // roll: eixo X do celular em relacao ao "lado direito sem inclinacao" (frente x cima)
        val len = sqrt(fx * fx + fy * fy)
        fHoriz = len
        roll = if (len > 1e-3f) {
            val r0x = fy / len; val r0y = -fx / len
            Math.toDegrees(atan2(r[6], r[0] * r0x + r[3] * r0y).toDouble()).toFloat()
        } else 0f
        hasData = true
    }
}
