package com.stormvr.app

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.widget.LinearLayout
import android.widget.Toast

/** "Me ajude a continuar melhorando o StormVR": LivePix + redes sociais do criador. Cada botão abre o link no navegador/app. */
object SupportUi {
    const val LIVEPIX = "https://livepix.gg/oluke"
    const val INSTAGRAM = "https://www.instagram.com/oluke.skull/reels/"
    const val TIKTOK = "https://www.tiktok.com/@oluke28"
    const val YOUTUBE = "https://www.youtube.com/@OLUKE.F"

    fun open(c: Context, url: String) {
        try { c.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (_: Throwable) { Toast.makeText(c, "Não consegui abrir: $url", Toast.LENGTH_LONG).show() }
    }

    fun addTo(c: Context, parent: LinearLayout) {
        val help = Ui.section(c, "💜", "Me ajude a continuar melhorando o StormVR",
            "O StormVR é feito por uma pessoa só, no tempo livre. Se ele está te ajudando, qualquer valor no LivePix paga o tempo de desenvolvimento e garante mais novidades.")
        help.addView(Ui.button(c, "💜  Apoiar pelo LivePix", primary = true) { open(c, LIVEPIX) })
        parent.addView(help)
        val soc = Ui.section(c, "🌐", "Minhas redes", "Vídeos, novidades e bastidores do projeto.")
        soc.addView(Ui.button(c, "📸  Instagram  @oluke.skull") { open(c, INSTAGRAM) })
        soc.addView(Ui.button(c, "🎵  TikTok  @oluke28") { open(c, TIKTOK) })
        soc.addView(Ui.button(c, "▶️  YouTube  @OLUKE.F") { open(c, YOUTUBE) })
        parent.addView(soc)
        parent.addView(Ui.label(c, "Obrigado por usar o StormVR! 💜", 13f, Ui.MUTED))
    }
}
