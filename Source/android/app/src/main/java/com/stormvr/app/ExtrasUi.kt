package com.stormvr.app

import android.content.Context
import android.graphics.Color
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

/** Opções do 3DoF avançado (inclinar a cabeça, barômetro, StormSaber de cabeça fixa). Mesma tela nas Configurações e no painel da Sala VR. */
object ExtrasUi {
    fun build(ctx: Context): LinearLayout {
        val p = Prefs(ctx)
        val card = Ui.section(ctx, "🧍", "3DoF avançado", "Só valem no 3DoF (no 6DoF a posição é medida de verdade). Cada opção liga e desliga sozinha.")

        fun sw(text: String, on: Boolean, set: (Boolean) -> Unit) = Switch(ctx).apply {
            this.text = text; setTextColor(Color.WHITE); textSize = 15f; isChecked = on
            setPadding(0, Ui.dp(ctx, 10), 0, Ui.dp(ctx, 4))
            setOnCheckedChangeListener { _, v -> set(v) }
        }
        fun bar(label: (Int) -> String, max: Int, cur: Int, set: (Int) -> Unit): LinearLayout {
            val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            val tv = TextView(ctx).apply { setTextColor(Ui.MUTED); textSize = 13f; text = label(cur) }
            box.addView(tv)
            box.addView(SeekBar(ctx).apply {
                this.max = max; progress = cur
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, v: Int, fromUser: Boolean) { tv.text = label(v); if (fromUser) set(v) }
                    override fun onStartTrackingTouch(sb: SeekBar?) {}
                    override fun onStopTrackingTouch(sb: SeekBar?) {}
                })
            })
            return box
        }

        card.addView(sw("Inclinar a cabeça anda para o lado", p.leanOn) { p.leanOn = it })
        card.addView(bar({ "Quanto anda ao inclinar (até 30°): $it cm" }, 60, p.leanCm) { p.leanCm = it.coerceAtLeast(2) })

        val hasBaro = try { HeadExtras(ctx).hasBarometer() } catch (_: Throwable) { false }
        card.addView(sw(if (hasBaro) "Barômetro: detectar agachar e levantar" else "Barômetro (este aparelho não tem)", p.baroOn && hasBaro) { p.baroOn = it && hasBaro })
        card.addView(bar({ "Sensibilidade do barômetro: %.1f ×".format(0.3f + it / 10f) }, 27, ((p.baroGain - 0.3f) * 10f).toInt().coerceIn(0, 27)) { p.baroGain = 0.3f + it / 10f })
        card.addView(Ui.label(ctx, "Dica: fique de pé, recentre (botão Centrar) e então agache. Se descer pouco, aumente a sensibilidade.", 12f, Ui.MUTED))

        card.addView(sw("Enviar a inclinação e o barômetro ao SteamVR", p.extrasToSteam) { p.extrasToSteam = it })
        card.addView(sw("StormSaber: cabeça fixa (ignorar o 3DoF)", p.saberLookFixed) { p.saberLookFixed = it })
        card.addView(Ui.label(ctx, "Cabeça fixa deixa a tela do StormSaber parada à sua frente enquanto você joga. Padrão: desligado.", 12f, Ui.MUTED))
        return card
    }
}
