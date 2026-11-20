package com.stormvr.app

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.pow

/**
 * Extras do 3DoF (o celular não mede posição, mas dá para "enganar" com o que ele sente):
 *  - INCLINAR A CABEÇA (eixo da frente, "roll"): inclinou para a direita → o corpo vai um pouco para a direita (e para a esquerda).
 *  - BARÔMETRO: a pressão do ar muda ~0,12 hPa por metro; agachar faz a pressão subir um pouquinho → a cabeça desce.
 * Cada um liga/desliga separado e tem ganho próprio. Nada aqui existe no 6DoF (lá a posição é medida de verdade).
 * Saída: [lean] (m, + = direita da cabeça) e [drop] (m, − = agachou, + = subiu na ponta do pé).
 */
class HeadExtras(ctx: Context) : SensorEventListener {
    private val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val baro: Sensor? = sm.getDefaultSensor(Sensor.TYPE_PRESSURE)

    @Volatile var leanOn = false
    @Volatile var leanMaxM = 0.20f          // deslocamento lateral máximo (inclinando ~30°)
    @Volatile var baroOn = false
    @Volatile var baroGain = 1.0f           // multiplica o movimento vertical medido pelo barômetro

    @Volatile var lean = 0f
        private set
    @Volatile var drop = 0f
        private set

    private var pFilt = Float.NaN
    private var p0 = Float.NaN              // referência ("de pé") da pressão
    private var running = false

    fun hasBarometer(): Boolean = baro != null
    val active: Boolean get() = leanOn || baroOn

    fun apply(p: Prefs) {
        leanOn = p.leanOn; leanMaxM = p.leanCm / 100f
        baroOn = p.baroOn; baroGain = p.baroGain
        if (baroOn) start() else if (running) stop()
        if (!leanOn) lean = 0f
        if (!baroOn) drop = 0f
    }

    @Synchronized fun start() {
        val b = baro ?: return
        if (running) return
        running = sm.registerListener(this, b, SensorManager.SENSOR_DELAY_GAME)
    }

    @Synchronized fun stop() {
        if (running) sm.unregisterListener(this)
        running = false
    }

    /** "De pé agora": usa a pressão atual como altura zero (chamado ao recentrar). */
    fun recenter() { p0 = pFilt; drop = 0f }

    /** Calcula a inclinação lateral a partir da orientação da cabeça (w,x,y,z no referencial do SteamVR: x direita, y cima). */
    fun updateLean(q: FloatArray) {
        if (!leanOn) { lean = 0f; return }
        val w = q[0]; val x = q[1]; val y = q[2]; val z = q[3]
        val ry = 2f * (x * y + w * z)                        // componente "para cima" do eixo direito da cabeça
        val rollRight = Math.toDegrees(asin((-ry).coerceIn(-1f, 1f)).toDouble()).toFloat()   // + = inclinou para a direita
        val dead = 4f; val full = 30f
        val m = ((abs(rollRight) - dead) / (full - dead)).coerceIn(0f, 1f)
        val target = (if (rollRight < 0) -m else m) * leanMaxM
        lean += (target - lean) * 0.25f
    }

    private fun alt(p: Float): Float = 44330f * (1f - (p / 1013.25f).pow(0.1903f))

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onSensorChanged(e: SensorEvent) {
        val p = e.values[0]
        pFilt = if (pFilt.isNaN()) p else pFilt + (p - pFilt) * 0.2f
        if (p0.isNaN()) p0 = pFilt
        var d = alt(pFilt) - alt(p0)                        // metros em relação a "de pé" (− = mais baixo)
        // deriva lenta do tempo/clima: se ficou perto do zero por um tempo, a referência acompanha
        if (abs(d) < 0.07f) p0 += (pFilt - p0) * 0.0015f
        d *= baroGain
        val dead = 0.03f
        d = if (abs(d) < dead) 0f else (d - (if (d > 0) dead else -dead))
        val target = d.coerceIn(-0.9f, 0.3f)
        drop += (target - drop) * 0.35f
    }
}
