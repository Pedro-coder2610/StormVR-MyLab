package com.stormvr.app.applauncher

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.stormvr.app.Ui

/**
 * Interface de seleção de apps: grade com ícone e nome, aba de janelas 3D e aba com o relatório do teste de execução externa.
 *
 * É uma View comum, então funciona dentro da "TV" da Sala 3D (que já converte laser/pinça em toque) e também numa Activity 2D
 * ([AppLauncherActivity]). Tamanhos são proporcionais ao menor lado da tela, como o resto da "TV".
 * [homeInset] = true reserva o canto superior esquerdo para o botão "⌂ Home" que a sala desenha por cima.
 */
class AppLauncherPane(
    ctx: Context,
    private val launcher: AppLauncher,
    homeInset: Boolean = false
) : FrameLayout(ctx), AppLauncher.Listener {

    private enum class Tab { APPS, WINDOWS, LIVE, REPORT }

    private val intro = "Nenhum teste executado ainda.\n\n" +
        "“Abrir em janela 3D” TENTA abrir o app num display virtual do StormVR usando só APIs públicas do Android (sem root).\n" +
        "“Abrir com root” usa um helper root (su): o app abre no display virtual, aparece na aba “Ao vivo” e aceita toque.\n" +
        "O relatório mostra, passo a passo, o que o sistema respondeu. Nada é simulado: se o Android bloquear, o motivo aparece aqui."

    private val unit: Float = minOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels).toFloat()
    private var tab = Tab.APPS
    private var selected: AppEntry? = null
    private var ready = false
    private var cols = 4

    private val adapter = AppAdapter()
    private val grid = GridView(ctx)
    private val windowsList = LinearLayout(ctx)
    private val windowsScroll = ScrollView(ctx)
    private val liveBox = LinearLayout(ctx)
    private val col = LinearLayout(ctx)
    private var colPad = 0
    private var chrome: List<View> = emptyList()           // partes da pane escondidas no modo TELA CHEIA do app
    private var liveFull = false
    private val liveTitle = TextView(ctx)
    private val liveHead = LinearLayout(ctx)
    private val liveOverlay = LinearLayout(ctx)           // ─ □ ✕ flutuantes (só no modo TELA CHEIA)
    private val liveFrame = FrameLayout(ctx)
    private val liveSv = SurfaceView(ctx)
    private val liveMsg = TextView(ctx)
    private val reportBox = LinearLayout(ctx)
    private val reportScroll = ScrollView(ctx)
    private val reportPreview = ImageView(ctx)
    private val reportText = TextView(ctx)
    private val title = TextView(ctx)
    private val tabViews = ArrayList<TextView>()
    private val selBar = LinearLayout(ctx)
    private val selIcon = ImageView(ctx)
    private val selInfo = TextView(ctx)
    private val statusLine = TextView(ctx)

    private fun px(f: Float): Int = (unit * f).toInt()

    private fun rounded(color: Int, r: Float = 0.04f): GradientDrawable =
        GradientDrawable().apply { setColor(color); cornerRadius = unit * r }

    private fun label(text: String, size: Float, color: Int = Color.WHITE, bold: Boolean = false): TextView {
        val t = TextView(context)
        t.text = text
        t.setTextColor(color)
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, unit * size)
        if (bold) t.typeface = Typeface.DEFAULT_BOLD
        return t
    }

    private fun pill(text: String, bg: Int, size: Float = 0.034f, padH: Float = 0.03f, onClick: () -> Unit): TextView {
        val t = label(text, size)
        t.gravity = Gravity.CENTER
        t.setPadding(px(padH), px(0.014f), px(padH), px(0.014f))
        t.background = rounded(bg, 0.05f)
        t.isClickable = true
        t.setOnClickListener { onClick() }
        return t
    }

    init {
        setBackgroundColor(Ui.BG)
        col.orientation = LinearLayout.VERTICAL
        val pad = px(0.025f); colPad = pad
        col.setPadding(pad, pad, pad, pad)

        // linha 1: título + rolagem (o laser arrasta mal; os botões ▲ ▼ rolam a página inteira)
        val row1 = LinearLayout(ctx)
        row1.orientation = LinearLayout.HORIZONTAL
        row1.gravity = Gravity.CENTER_VERTICAL
        title.setTextColor(Color.WHITE)
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, unit * 0.052f)
        title.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        title.setSingleLine(true)
        title.ellipsize = TextUtils.TruncateAt.END
        title.setPadding(if (homeInset) px(0.02f) else 0, 0, 0, 0)
        row1.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row1.addView(pill("▲", Ui.CARD2) { scrollPage(-1) })
        row1.addView(pill("▼", Ui.CARD2) { scrollPage(1) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = px(0.015f) })
        col.addView(row1, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // linha 2: abas
        val row2 = LinearLayout(ctx)
        row2.orientation = LinearLayout.HORIZONTAL
        val names = arrayOf("▦  Apps", "◫  Janelas", "●  Ao vivo", "☰  Relatório")
        val tabs = Tab.values()
        for (i in tabs.indices) {
            val t = tabs[i]
            val v = pill(names[i], Ui.CARD2, 0.03f, 0.008f) { setTab(t) }
            tabViews.add(v)
            row2.addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(px(0.005f), px(0.012f), px(0.005f), px(0.012f))
            })
        }
        col.addView(row2, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // conteúdo
        val content = FrameLayout(ctx)
        grid.setNumColumns(cols)
        grid.horizontalSpacing = px(0.012f); grid.verticalSpacing = px(0.012f)
        grid.adapter = adapter
        grid.setSelector(ColorDrawable(Color.TRANSPARENT))
        grid.isVerticalScrollBarEnabled = false
        grid.setOnItemClickListener { _, _, pos, _ ->
            val e = adapter.items.getOrNull(pos)
            if (e != null) select(e)
        }
        content.addView(grid, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        windowsList.orientation = LinearLayout.VERTICAL
        windowsScroll.isVerticalScrollBarEnabled = false
        windowsScroll.addView(windowsList, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        content.addView(windowsScroll, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // aba "Ao vivo": o display virtual do app (root) desenha direto neste SurfaceView; o toque vai ao helper root
        liveBox.orientation = LinearLayout.VERTICAL
        liveSv.holder.setFixedSize(ExternalAppSession.W, ExternalAppSession.H)
        liveSv.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) { launcher.attachLive(holder.surface) }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) { launcher.detachLive() }
        })
        liveSv.setOnTouchListener { v, ev -> liveTouch(v, ev) }
        liveFrame.setBackgroundColor(Color.BLACK)
        liveFrame.addView(liveSv, LayoutParams(1, 1))
        liveMsg.setTextColor(Color.WHITE)
        liveMsg.setTextSize(TypedValue.COMPLEX_UNIT_PX, unit * 0.032f)
        liveMsg.gravity = Gravity.CENTER
        liveFrame.addView(liveMsg, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        liveFrame.addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ -> fitLive(r - l, b - t) }
        // CABEÇALHO DA JANELA (como as abas do Windows/Mac): nome do app + minimizar ─, maximizar/restaurar □ (tela cheia na TV) e fechar ✕
        liveHead.orientation = LinearLayout.HORIZONTAL
        liveHead.gravity = Gravity.CENTER_VERTICAL
        liveHead.background = rounded(Ui.CARD, 0.025f)
        liveHead.setPadding(px(0.02f), px(0.006f), px(0.01f), px(0.006f))
        liveTitle.setTextColor(Color.WHITE)
        liveTitle.setTextSize(TypedValue.COMPLEX_UNIT_PX, unit * 0.03f)
        liveTitle.setSingleLine(true); liveTitle.ellipsize = TextUtils.TruncateAt.END
        liveTitle.text = "App Android"
        liveHead.addView(liveTitle, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        liveHead.addView(pill("─", Ui.CARD2, 0.03f, 0.012f) { minimizeLive() })
        liveHead.addView(pill("□", Ui.CARD2, 0.03f, 0.012f) { toggleLiveFull() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = px(0.01f) })
        liveHead.addView(pill("✕", Color.rgb(200, 40, 80), 0.03f, 0.012f) { closeLive() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = px(0.01f) })
        liveBox.addView(liveHead, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = px(0.008f) })
        liveBox.addView(liveFrame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        liveOverlay.orientation = LinearLayout.HORIZONTAL
        liveOverlay.visibility = View.GONE
        liveOverlay.addView(pill("─", Color.argb(190, 38, 22, 66), 0.03f, 0.012f) { minimizeLive() })
        liveOverlay.addView(pill("⤡", Color.argb(190, 38, 22, 66), 0.03f, 0.012f) { toggleLiveFull() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = px(0.008f) })
        liveOverlay.addView(pill("✕", Color.argb(210, 200, 40, 80), 0.03f, 0.012f) { closeLive() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = px(0.008f) })
        liveFrame.addView(liveOverlay, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply { topMargin = px(0.008f); rightMargin = px(0.008f) })
        // (removidos "◀ Voltar" e "Fechar app": o Voltar já existe na barra da telona e o ✕ do cabeçalho fecha o app)
        content.addView(liveBox, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        reportBox.orientation = LinearLayout.VERTICAL
        reportPreview.adjustViewBounds = true
        reportPreview.scaleType = ImageView.ScaleType.FIT_START
        reportPreview.visibility = View.GONE
        reportBox.addView(reportPreview, LinearLayout.LayoutParams(px(0.5f), ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = px(0.015f) })
        reportText.setTextColor(Color.WHITE)
        reportText.setTextSize(TypedValue.COMPLEX_UNIT_PX, unit * 0.03f)
        reportText.typeface = Typeface.MONOSPACE
        reportBox.addView(reportText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        reportScroll.isVerticalScrollBarEnabled = false
        reportScroll.addView(reportBox, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        content.addView(reportScroll, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        col.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // barra do app selecionado: linha de cima (ícone, nome, ✕) e linha de baixo (dois modos de abrir)
        selBar.orientation = LinearLayout.VERTICAL
        selBar.background = rounded(Ui.CARD)
        selBar.setPadding(px(0.02f), px(0.012f), px(0.02f), px(0.012f))
        selBar.visibility = View.GONE
        val selTop = LinearLayout(ctx)
        selTop.orientation = LinearLayout.HORIZONTAL
        selTop.gravity = Gravity.CENTER_VERTICAL
        selTop.addView(selIcon, LinearLayout.LayoutParams(px(0.085f), px(0.085f)))
        selInfo.setTextColor(Color.WHITE)
        selInfo.setTextSize(TypedValue.COMPLEX_UNIT_PX, unit * 0.032f)
        selInfo.maxLines = 2
        selInfo.ellipsize = TextUtils.TruncateAt.END
        selTop.addView(selInfo, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(px(0.02f), 0, px(0.02f), 0) })
        selTop.addView(pill("✕", Ui.CARD2) { select(null) })
        selBar.addView(selTop, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val selBtns = LinearLayout(ctx)
        selBtns.orientation = LinearLayout.HORIZONTAL
        selBtns.addView(pill("Janela 3D", Ui.CARD2, 0.032f, 0.01f) { val a = selected; if (a != null) openApp(a, false) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, px(0.01f), px(0.01f), 0) })
        selBtns.addView(pill("▶  Abrir (tela cheia)", Ui.ACCENT, 0.034f, 0.01f) { val a = selected; if (a != null) openApp(a, true) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(px(0.01f), px(0.01f), 0, 0) })
        selBar.addView(selBtns, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        col.addView(selBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(0.01f) })

        statusLine.setTextColor(Ui.MUTED)
        statusLine.setTextSize(TypedValue.COMPLEX_UNIT_PX, unit * 0.026f)
        statusLine.maxLines = 2
        statusLine.ellipsize = TextUtils.TruncateAt.END
        col.addView(statusLine, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(0.008f) })

        chrome = listOf(row1, row2, statusLine, title)
        addView(col, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        ready = true
        applyTab()
        syncApps()
        refreshReport()
    }

    // ------------------------------------------------------------------ ciclo de vida

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        launcher.addListener(this)
        syncApps()
        rebuildWindows()
        refreshReport()
        syncWatching()
    }

    override fun onDetachedFromWindow() {
        launcher.removeListener(this)
        launcher.setWatching(false)
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (ready) syncWatching()
    }

    /** Só observa instalações e varre apps enquanto a tela está realmente visível. */
    private fun syncWatching() { launcher.setWatching(isAttachedToWindow && isShown) }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        if (!ready) return
        val c = maxOf(2, w / px(0.26f))
        if (c != cols) { cols = c; grid.setNumColumns(c) }
    }

    // ------------------------------------------------------------------ abas e atualização

    private fun setTab(t: Tab) { tab = t; applyTab() }

    private fun applyTab() {
        grid.visibility = if (tab == Tab.APPS) View.VISIBLE else View.GONE
        windowsScroll.visibility = if (tab == Tab.WINDOWS) View.VISIBLE else View.GONE
        liveBox.visibility = if (tab == Tab.LIVE) View.VISIBLE else View.GONE
        reportScroll.visibility = if (tab == Tab.REPORT) View.VISIBLE else View.GONE
        val tabs = Tab.values()
        for (i in tabs.indices) tabViews[i].background = if (tabs[i] == tab)
            GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Ui.ACCENT_DK, Ui.ACCENT)).apply { cornerRadius = unit * 0.05f }
        else rounded(Ui.CARD2, 0.05f)
    }

    private fun scrollPage(dir: Int) {
        val d = dir * (height * 0.6f).toInt()
        when (tab) {
            Tab.APPS -> grid.smoothScrollBy(d, 220)
            Tab.WINDOWS -> windowsScroll.smoothScrollBy(0, d)
            Tab.LIVE -> {}
            Tab.REPORT -> reportScroll.smoothScrollBy(0, d)
        }
    }

    private fun select(app: AppEntry?) {
        selected = app
        if (app == null) {
            selBar.visibility = View.GONE
        } else {
            selIcon.setImageBitmap(app.icon)
            selInfo.text = app.label + "\n" + app.packageName
            selBar.visibility = View.VISIBLE
        }
        adapter.notifyDataSetChanged()
    }

    /** TELA CHEIA do app na TV: esconde título, abas e barras; só sobra o cabeçalho (─ □ ✕) e a imagem do app. */
    private fun toggleLiveFull() { liveFull = !liveFull; applyFull() }
    private fun applyFull() {
        val v = if (liveFull) View.GONE else View.VISIBLE
        for (c in chrome) c.visibility = v
        selBar.visibility = if (liveFull || selected == null) View.GONE else View.VISIBLE
        liveHead.visibility = v                                  // tela cheia: some o cabeçalho (os botões ficam flutuando no canto)
        liveOverlay.visibility = if (liveFull) View.VISIBLE else View.GONE
        val p = if (liveFull) 0 else colPad                      // sem margem nenhuma: o app toma conta da TV inteira
        col.setPadding(p, p, p, p)
        col.requestLayout()
        liveFrame.post { fitLive(liveFrame.width, liveFrame.height) }
    }
    /** Minimizar: volta para a lista de apps; o app continua rodando e volta pela aba "Ao vivo". */
    private fun minimizeLive() { if (liveFull) { liveFull = false; applyFull() }; setTab(Tab.APPS) }
    private fun closeLive() { launcher.closeActive(); if (liveFull) { liveFull = false; applyFull() }; setTab(Tab.APPS) }

    private fun openApp(app: AppEntry, root: Boolean) {
        liveTitle.text = app.label
        if (root) {
            launcher.openRoot(app)
            if (!liveFull) { liveFull = true; applyFull() }      // o app toma a telona inteira; ─ minimiza, ⤡ volta ao modo com cabeçalho, ✕ fecha
            setTab(Tab.LIVE)                 // a aba visível cria a superfície; só então o helper abre o app
        } else {
            launcher.open(app)
            setTab(Tab.WINDOWS)
        }
    }

    /** Mantém a proporção 16:9 do display virtual dentro do quadro. */
    private fun fitLive(cw: Int, ch: Int) {
        if (cw <= 0 || ch <= 0) return
        if (liveFull) {                                                       // TELA CHEIA: preenche toda a TV (sem faixas pretas)
            val lpf = liveSv.layoutParams as LayoutParams
            if (lpf.width != ViewGroup.LayoutParams.MATCH_PARENT || lpf.height != ViewGroup.LayoutParams.MATCH_PARENT) {
                lpf.width = ViewGroup.LayoutParams.MATCH_PARENT; lpf.height = ViewGroup.LayoutParams.MATCH_PARENT; lpf.gravity = Gravity.CENTER; liveSv.layoutParams = lpf
            }
            return
        }
        var w = cw
        var h = cw * ExternalAppSession.H / ExternalAppSession.W
        if (h > ch) { h = ch; w = ch * ExternalAppSession.W / ExternalAppSession.H }
        val lp = liveSv.layoutParams as LayoutParams
        if (lp.width != w || lp.height != h) { lp.width = w; lp.height = h; lp.gravity = Gravity.CENTER; liveSv.layoutParams = lp }
    }

    /** Toque no quadro ao vivo → pixels do display virtual → helper root. */
    private fun liveTouch(v: View, ev: MotionEvent): Boolean {
        val action = when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> 0
            MotionEvent.ACTION_UP -> 1
            MotionEvent.ACTION_MOVE -> 2
            MotionEvent.ACTION_CANCEL -> 3
            else -> return true
        }
        val x = (ev.x / v.width.coerceAtLeast(1) * ExternalAppSession.W).toInt().coerceIn(0, ExternalAppSession.W - 1)
        val y = (ev.y / v.height.coerceAtLeast(1) * ExternalAppSession.H).toInt().coerceIn(0, ExternalAppSession.H - 1)
        launcher.sendTouch(action, x, y)
        return true
    }

    private fun syncApps() {
        val repo = launcher.repository
        val list = repo.apps
        adapter.items = list
        adapter.notifyDataSetChanged()
        val sel = selected
        if (sel != null && list.none { it.packageName == sel.packageName }) select(null)
        val err = repo.error
        title.text = when {
            repo.loaded -> "Aplicativos (${list.size})"
            err != null -> "Aplicativos — erro: $err"
            else -> "Aplicativos — carregando…"
        }
    }

    private fun refreshReport() {
        val p = launcher.preview
        if (p != null) { reportPreview.setImageBitmap(p); reportPreview.visibility = View.VISIBLE } else reportPreview.visibility = View.GONE
        val r = launcher.lastReport
        reportText.text = if (r != null) r.text() else intro
        statusLine.text = launcher.status
        liveMsg.visibility = if (launcher.rootActive) View.GONE else View.VISIBLE
        liveMsg.text = if (launcher.status.isEmpty()) "Nenhum app aberto com root.\nEscolha um app em “Apps” e toque em “Abrir com root”." else launcher.status
    }

    override fun onAppsChanged() { if (ready) syncApps() }
    override fun onWindowsChanged() { if (ready) rebuildWindows() }
    override fun onReportChanged() { if (ready) refreshReport() }

    // ------------------------------------------------------------------ janelas 3D

    private fun rebuildWindows() {
        windowsList.removeAllViews()
        val ws = launcher.windows.all()
        tabViews[1].text = "◫  Janelas (${ws.size})"
        if (ws.isEmpty()) {
            windowsList.addView(label("Nenhuma janela. Em “Apps”, escolha um aplicativo e toque em “Abrir em janela 3D”.", 0.032f, Ui.MUTED))
            return
        }
        for (w in ws) windowsList.addView(windowRow(w), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = px(0.015f) })
    }

    private fun windowRow(w: AppWindow): View {
        val box = LinearLayout(context)
        box.orientation = LinearLayout.VERTICAL
        box.background = rounded(Ui.CARD)
        box.setPadding(px(0.025f), px(0.018f), px(0.025f), px(0.018f))
        box.addView(label((if (w.focused) "● " else "○ ") + w.appName + (if (w.visible) "" else "  (oculta)"), 0.04f, Color.WHITE, true))
        val st = if (w.status.isEmpty()) w.state.label else w.state.label + " — " + w.status
        box.addView(label(w.packageName + "\nEstado: " + st, 0.029f, Ui.MUTED))
        box.addView(label(
            "Pose (m): x=%.2f y=%.2f z=%.2f · giro %.0f° · tamanho %.1f×%.1f m · superfície %d×%d".format(
                w.x, w.y, w.z, w.yawDeg, w.widthM, w.heightM, w.pixelWidth, w.pixelHeight), 0.026f, Ui.MUTED))
        val btns = LinearLayout(context)
        btns.orientation = LinearLayout.HORIZONTAL
        val id = w.id
        btns.addView(pill(if (w.visible) "Ocultar" else "Mostrar", Ui.CARD2) { launcher.windows.setVisible(id, !w.visible) })
        btns.addView(pill("Foco", Ui.CARD2) { launcher.windows.focus(id) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = px(0.015f) })
        btns.addView(pill("Fechar", Ui.ACCENT_DK) { launcher.closeWindow(id) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = px(0.015f) })
        box.addView(btns, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(0.012f) })
        return box
    }

    // ------------------------------------------------------------------ grade de apps

    private inner class AppAdapter : BaseAdapter() {
        var items: List<AppEntry> = emptyList()
        override fun getCount(): Int = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int): Long = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val cell = (convertView as? Cell) ?: Cell(context)
            val e = items[position]
            cell.bind(e, e.packageName == selected?.packageName)
            return cell
        }
    }

    private inner class Cell(c: Context) : LinearLayout(c) {
        private val img = ImageView(c)
        private val name = label("", 0.03f)

        init {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(px(0.008f), px(0.016f), px(0.008f), px(0.010f))
            layoutParams = AbsListView.LayoutParams(AbsListView.LayoutParams.MATCH_PARENT, px(0.275f))
            img.scaleType = ImageView.ScaleType.FIT_CENTER
            name.gravity = Gravity.CENTER
            name.maxLines = 2
            name.ellipsize = TextUtils.TruncateAt.END
            addView(img, LinearLayout.LayoutParams(px(0.135f), px(0.135f)))
            addView(name, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(0.008f) })
        }

        fun bind(e: AppEntry, sel: Boolean) {
            img.setImageBitmap(e.icon)
            name.text = e.label
            background = if (sel) GradientDrawable().apply { setColor(Ui.CARD2); cornerRadius = unit * 0.045f; setStroke(px(0.006f).coerceAtLeast(2), Ui.ACCENT) }
                         else GradientDrawable().apply { setColor(Color.argb(70, 36, 20, 64)); cornerRadius = unit * 0.045f }
        }
    }
}
