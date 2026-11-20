package com.stormvr.app

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt

/**
 * Orientacao da cabeca (3DoF) a partir do vetor de rotacao do Android (giroscopio + acelerometro).
 * Saida: quaternio (w,x,y,z) no referencial do SteamVR (x direita, y cima, -z frente),
 * com o celular em PAISAGEM dentro do Cardboard (tela voltada para os olhos).
 *
 * O yaw absoluto e arbitrario: o driver recentra (recenterCount / nova sessao).
 * Nota: usa os sensores do Android em vez do SDK nativo do Cardboard (exigiria NDK). O Cardboard SDK continua
 * previsto para ler o perfil da lente (QR) numa etapa futura.
 */
class HeadTracker(context: Context) : SensorEventListener {
    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor: Sensor? =
        sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR) ?: sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    /** 0 = automatico (usa a rotacao da tela), 1 = topo do celular a esquerda (ROTATION_90), 2 = topo a direita (ROTATION_270) */
    @Volatile var orientationMode = 0
    @Volatile var displayRotation = 1       // Surface.ROTATION_*, atualizado pelo servico

    private val r = FloatArray(9)
    private val quat = FloatArray(4)
    @Volatile private var w = 1f
    @Volatile private var x = 0f
    @Volatile private var y = 0f
    @Volatile private var z = 0f
    @Volatile var hasData = false
        private set
    @Volatile var sensorEvents = 0L
        private set

    fun available(): Boolean = sensor != null

    fun start(): Boolean {
        val s = sensor ?: return false
        return sm.registerListener(this, s, SensorManager.SENSOR_DELAY_FASTEST)
    }

    fun stop() {
        sm.unregisterListener(this)
    }

    /** Copia a orientacao atual para out[0..3] = w,x,y,z. */
    fun read(out: FloatArray) {
        out[0] = w; out[1] = x; out[2] = y; out[3] = z
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onSensorChanged(e: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(r, e.values)   // dispositivo -> mundo (ENU: x leste, y norte, z cima)
        // A = M * R, com M: ENU -> SteamVR  (x_v = x_w, y_v = z_w, z_v = -y_w)
        val a = FloatArray(9)
        a[0] = r[0];  a[1] = r[1];  a[2] = r[2]
        a[3] = r[6];  a[4] = r[7];  a[5] = r[8]
        a[6] = -r[3]; a[7] = -r[4]; a[8] = -r[5]
        // B = A * C : eixos da CABECA expressos no dispositivo (paisagem)
        val landscapeRight = when (orientationMode) {
            1 -> false                            // topo do celular à esquerda  (= Surface.ROTATION_90,  igual à Sala 3D)
            else -> true                          // topo do celular à direita   (= Surface.ROTATION_270, igual à Sala 3D)
            // Sem modo automático: girar a cabeça NÃO pode virar o mundo de cabeça pra baixo.
        }
        val b = FloatArray(9)
        for (i in 0 until 3) {
            val c0 = a[i * 3]; val c1 = a[i * 3 + 1]; val c2 = a[i * 3 + 2]
            if (!landscapeRight) { b[i * 3] = -c1; b[i * 3 + 1] = c0 } else { b[i * 3] = c1; b[i * 3 + 1] = -c0 }
            b[i * 3 + 2] = c2
        }
        matrixToQuat(b)
        w = quat[0]; x = quat[1]; y = quat[2]; z = quat[3]
        sensorEvents++
        hasData = true
    }

    private fun matrixToQuat(m: FloatArray) {
        val t = m[0] + m[4] + m[8]
        val qw: Float; val qx: Float; val qy: Float; val qz: Float
        if (t > 0f) {
            val s = sqrt(t + 1f) * 2f
            qw = 0.25f * s; qx = (m[7] - m[5]) / s; qy = (m[2] - m[6]) / s; qz = (m[3] - m[1]) / s
        } else if (m[0] > m[4] && m[0] > m[8]) {
            val s = sqrt(1f + m[0] - m[4] - m[8]) * 2f
            qw = (m[7] - m[5]) / s; qx = 0.25f * s; qy = (m[1] + m[3]) / s; qz = (m[2] + m[6]) / s
        } else if (m[4] > m[8]) {
            val s = sqrt(1f + m[4] - m[0] - m[8]) * 2f
            qw = (m[2] - m[6]) / s; qx = (m[1] + m[3]) / s; qy = 0.25f * s; qz = (m[5] + m[7]) / s
        } else {
            val s = sqrt(1f + m[8] - m[0] - m[4]) * 2f
            qw = (m[3] - m[1]) / s; qx = (m[2] + m[6]) / s; qy = (m[5] + m[7]) / s; qz = 0.25f * s
        }
        val n = sqrt(qw * qw + qx * qx + qy * qy + qz * qz)
        quat[0] = qw / n; quat[1] = qx / n; quat[2] = qy / n; quat[3] = qz / n
    }
}
