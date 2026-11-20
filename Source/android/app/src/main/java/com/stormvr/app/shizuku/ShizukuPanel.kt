package com.stormvr.app.shizuku

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.stormvr.app.Ui

/**
 * INTERFACE DE ATIVAÇÃO do Shizuku (aba "🔐 Shizuku" em Configurações). Só desenha o estado real vindo do [ShizukuManager] e chama os botões dele:
 * não fala com a API do Shizuku e não executa nada por conta própria. Autorizar exige o toque do usuário e a confirmação na caixa do Shizuku.
 */
object ShizukuPanel {
    fun build(ctx: Context): View = Panel(ctx)

    private class Panel(ctx: Context) : LinearLayout(ctx), ShizukuManager.Listener {
        private var testOut = ""
        private var testing = false

        init { orientation = VERTICAL; setPadding(Ui.dp(ctx, 4), Ui.dp(ctx, 6), Ui.dp(ctx, 4), Ui.dp(ctx, 24)) }

        override fun onAttachedToWindow() { super.onAttachedToWindow(); ShizukuManager.addListener(this); ShizukuManager.refresh() }
        override fun onDetachedFromWindow() { ShizukuManager.removeListener(this); super.onDetachedFromWindow() }
        override fun onShizukuChanged(info: ShizukuInfo) { render(info) }

        private fun dp(v: Int) = Ui.dp(context, v)

        private fun chip(text: String, color: Int) = TextView(context).apply {
            this.text = text; textSize = 15f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setPadding(dp(16), dp(9), dp(16), dp(9))
            background = GradientDrawable().apply { setColor(color); cornerRadius = dp(22).toFloat() }
            layoutParams = LayoutParams(-2, -2).apply { topMargin = dp(10); bottomMargin = dp(4) }
        }

        private fun render(i: ShizukuInfo) {
            removeAllViews()
            val c = context

            // ---------------- status
            val (emoji, name, color, why) = when (i.state) {
                ShizukuState.NOT_INSTALLED -> Quad("⚪", "Não instalado", Color.rgb(96, 84, 120), "O app Shizuku não está neste aparelho. Instale-o e volte aqui.")
                ShizukuState.NOT_RUNNING -> Quad("🟠", "Não ativado", Color.rgb(196, 120, 30), "O Shizuku está instalado, mas o serviço dele não está rodando. Inicie-o pela depuração sem fio (veja abaixo).")
                ShizukuState.OUTDATED -> Quad("🔴", "Shizuku desatualizado", Color.rgb(190, 50, 70), "O serviço é de uma versão antiga. Atualize o app Shizuku.")
                ShizukuState.WAITING_PERMISSION -> Quad("🟡", "Aguardando autorização", Color.rgb(170, 140, 20),
                    if (i.deniedPermanently) "A autorização foi negada com “não perguntar de novo”. Abra o Shizuku › Apps autorizados e permita o StormVR."
                    else if (i.denied) "A autorização foi negada. Toque em “Autorizar o StormVR” para pedir de novo."
                    else "O Shizuku está ativo. Falta você autorizar o StormVR (toque no botão abaixo e confirme).")
                ShizukuState.CONNECTED -> Quad("🟢", "Conectado", Color.rgb(46, 150, 90), "O StormVR está autorizado e pode usar operações privilegiadas.")
            }
            val st = Ui.section(c, "🔐", "Shizuku", "Operações privilegiadas sem root (Android 11 ou superior).")
            st.addView(chip("$emoji  $name", color))
            st.addView(Ui.label(c, why, 13.5f, Color.WHITE))
            if (i.state == ShizukuState.CONNECTED) {
                st.addView(Ui.label(c, "✅  O StormVR recebeu autorização do Shizuku.", 14f, Ui.OK))
                st.addView(Ui.label(c, "Modo do serviço: ${i.modeName}   ·   versão ${if (i.serverVersion >= 0) i.serverVersion else "?"}   ·   canal de comandos: ${if (i.shellReady) "aberto" else "abre no primeiro comando"}", 12.5f, Ui.MUTED))
            } else if (i.serviceRunning) {
                st.addView(Ui.label(c, "Serviço ativo (versão ${if (i.serverVersion >= 0) i.serverVersion else "?"}, modo ${i.modeName}).", 12.5f, Ui.MUTED))
            }
            addView(st)

            // ---------------- ações
            val act = Ui.card(c)
            act.addView(Ui.button(c, if (i.installed || i.serviceRunning) "Abrir o Shizuku" else "Instalar o Shizuku (site oficial)", primary = i.state == ShizukuState.NOT_INSTALLED || i.state == ShizukuState.NOT_RUNNING) {
                val opened = ShizukuManager.openShizuku(c)
                if (!opened && (i.installed || i.serviceRunning)) Toast.makeText(c, "Não foi possível abrir o Shizuku.", Toast.LENGTH_SHORT).show()
            })
            if (i.state == ShizukuState.WAITING_PERMISSION) {
                act.addView(Ui.button(c, "Autorizar o StormVR", primary = true) {
                    if (!ShizukuManager.requestPermission()) {
                        Toast.makeText(c, if (ShizukuManager.info.deniedPermanently) "Permita o StormVR em Shizuku › Apps autorizados." else "Não foi possível pedir a autorização agora.", Toast.LENGTH_LONG).show()
                    }
                })
            }
            act.addView(Ui.button(c, "Verificar novamente") {
                val n = ShizukuManager.refresh()
                Toast.makeText(c, when (n.state) {
                    ShizukuState.CONNECTED -> "Shizuku conectado e StormVR autorizado."
                    ShizukuState.WAITING_PERMISSION -> "Shizuku ativo; falta autorizar o StormVR."
                    ShizukuState.NOT_RUNNING -> "Shizuku instalado, mas não ativado."
                    ShizukuState.OUTDATED -> "Shizuku desatualizado."
                    ShizukuState.NOT_INSTALLED -> "Shizuku não instalado."
                }, Toast.LENGTH_SHORT).show()
            })
            if (i.state == ShizukuState.CONNECTED) {
                act.addView(Ui.button(c, if (testing) "Executando…" else "Executar teste (comando “id”)") {
                    if (testing) return@button
                    testing = true; testOut = ""; render(ShizukuManager.info)
                    ShizukuManager.exec("id") { r ->
                        testing = false
                        testOut = if (r.ok) "✔ " + r.text() else "✖ " + r.text().ifEmpty { "código ${r.exitCode}" }
                        render(ShizukuManager.info)
                    }
                })
                if (testOut.isNotEmpty()) act.addView(Ui.label(c, testOut, 12.5f, if (testOut.startsWith("✔")) Ui.OK else Color.rgb(255, 150, 160)))
            }
            addView(act)

            // ---------------- instruções
            val sdk = Build.VERSION.SDK_INT
            val how = Ui.section(c, "📶", "Ativar por depuração sem fio", if (sdk >= 30) "Sem PC e sem root. Precisa de Wi-Fi conectado." else "Seu Android é anterior ao 11: não há depuração sem fio.")
            if (sdk >= 30) {
                how.addView(Ui.label(c, "1. Instale o Shizuku (Google Play ou shizuku.rikka.app) e conecte o celular a uma rede Wi-Fi.\n" +
                    "2. Configurações › Sobre o telefone › toque 7 vezes em “Número da versão” (ativa o modo desenvolvedor).\n" +
                    "3. Configurações › Opções do desenvolvedor › ative a “Depuração sem fio”.\n" +
                    "4. Abra o Shizuku › “Iniciar via depuração sem fio” e siga o pareamento (próximo cartão).\n" +
                    "5. Volte ao StormVR, toque em “Verificar novamente” e depois em “Autorizar o StormVR”.", 13.5f, Color.WHITE))
                how.addView(Ui.button(c, "Abrir as opções do desenvolvedor") {
                    if (!ShizukuManager.openDeveloperOptions(c)) Toast.makeText(c, "Abra Configurações › Opções do desenvolvedor.", Toast.LENGTH_LONG).show()
                })
            } else {
                how.addView(Ui.label(c, "No Android 10 ou anterior o Shizuku precisa ser iniciado por um computador (ADB, comando no app Shizuku) ou por root. O StormVR usa o Shizuku do mesmo jeito depois de ativo.", 13.5f, Color.WHITE))
            }
            addView(how)

            val pair = Ui.section(c, "🔗", "Pareamento inicial (só na primeira vez)", "Android 11 ou superior.")
            pair.addView(Ui.label(c, "• Em Opções do desenvolvedor › Depuração sem fio, toque em “Parear dispositivo com código de pareamento”: aparece um código de 6 dígitos.\n" +
                "• Sem fechar essa janela, puxe a barra de notificações e responda à notificação do Shizuku digitando o código. No Android 11 use o modo de janela dividida ou flutuante se a janela fechar.\n" +
                "• Depois de “Pareamento concluído”, abra o Shizuku e toque em “Iniciar”.\n" +
                "• Sem root, o Shizuku para quando o celular reinicia: basta abrir o Shizuku e tocar em “Iniciar” de novo (o pareamento não se repete; precisa de Wi-Fi no Android 11 e 12).\n" +
                "• Os nomes dos menus mudam um pouco entre fabricantes (Samsung, Xiaomi, Motorola...).", 13.5f, Color.WHITE))
            addView(pair)

            val note = Ui.section(c, "ℹ️", "O que o Shizuku permite", null)
            note.addView(Ui.label(c, "O StormVR só ganha o poder do serviço Shizuku (ADB/shell ou root, conforme você iniciou). Ele não dá acesso a tudo: o Android continua bloqueando o que o usuário shell também não pode fazer. A autorização é sua e pode ser retirada a qualquer momento no app Shizuku.", 13f, Ui.MUTED))
            addView(note)
        }
    }

    private data class Quad(val a: String, val b: String, val c: Int, val d: String)
}
