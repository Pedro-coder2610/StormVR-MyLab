package com.stormvr.app

import android.content.Context

class Prefs(context: Context) {
    private val p = context.getSharedPreferences("stormvr", Context.MODE_PRIVATE)
    var host: String
        get() = p.getString("host", "") ?: ""
        set(v) { p.edit().putString("host", v).apply() }
    var port: Int
        get() = p.getInt("port", 5556)
        set(v) { p.edit().putInt("port", v).apply() }
    var rateHz: Int
        get() = p.getInt("rate", 120)
        set(v) { p.edit().putInt("rate", v).apply() }
    /** 0 = auto, 1 = topo a esquerda, 2 = topo a direita */
    var orientation: Int
        get() = p.getInt("orient", 0).let { if (it == 0) 2 else it.coerceIn(1, 2) }   // 1 = topo do celular à ESQUERDA (ROTATION_90), 2 = topo à DIREITA (ROTATION_270). Sem automático.
        set(v) { p.edit().putInt("orient", v).apply() }
    /** Reenviar ao PC os controles Bluetooth (ESP32) conectados a este celular. */
    /** false = 3DoF (so giroscopio; funciona em segundo plano) / true = 6DoF (camera + ARCore + maos; so com o app na tela). */
    var sixDof: Boolean
        get() = p.getBoolean("sixdof", false)
        set(v) { p.edit().putBoolean("sixdof", v).apply() }
    /** Quanto a rotação da cabeça é prevista à frente (ms): compensa a latência até o pixel/PC. 0 = sem previsão. Padrão 30 (como o CardboardXR). */
    var predictionMs: Int
        get() = p.getInt("predms", 30)
        set(v) { p.edit().putInt("predms", v).apply() }
    var btForward: Boolean
        get() = p.getBoolean("btfwd", true)
        set(v) { p.edit().putBoolean("btfwd", v).apply() }
    /** 3DoF avançado: inclinar a cabeça para os lados anda para o lado (máx. em cm). */
    var leanOn: Boolean
        get() = p.getBoolean("leanon", false)
        set(v) { p.edit().putBoolean("leanon", v).apply() }
    var leanCm: Int
        get() = p.getInt("leancm", 20).coerceIn(2, 60)
        set(v) { p.edit().putInt("leancm", v.coerceIn(2, 60)).apply() }
    /** 3DoF avançado: barômetro detecta agachar/levantar. Ganho = quanto do movimento medido vale (×). */
    var baroOn: Boolean
        get() = p.getBoolean("baroon", false)
        set(v) { p.edit().putBoolean("baroon", v).apply() }
    var baroGain: Float
        get() = p.getFloat("barogain", 1.0f).coerceIn(0.3f, 3.0f)
        set(v) { p.edit().putFloat("barogain", v.coerceIn(0.3f, 3.0f)).apply() }
    /** Enviar a inclinação lateral e o barômetro ao SteamVR (senão só valem dentro do StormVR). */
    var extrasToSteam: Boolean
        get() = p.getBoolean("extrassteam", true)
        set(v) { p.edit().putBoolean("extrassteam", v).apply() }
    /** StormSaber: ignorar o 3DoF (cabeça "olhando fixamente"). Padrão DESLIGADO. */
    var saberLookFixed: Boolean
        get() = p.getBoolean("saberfixed", false)
        set(v) { p.edit().putBoolean("saberfixed", v).apply() }
    val deviceId: Int
        get() {
            var id = p.getInt("deviceId", 0)
            if (id == 0) { id = (System.nanoTime() xor 0x5354524D).toInt(); if (id == 0) id = 1; p.edit().putInt("deviceId", id).apply() }
            return id
        }
}
