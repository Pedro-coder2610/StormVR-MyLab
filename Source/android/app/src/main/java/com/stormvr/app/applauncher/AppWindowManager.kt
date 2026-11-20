package com.stormvr.app.applauncher

import kotlin.math.cos
import kotlin.math.sin

/**
 * Gerenciador das janelas 3D de aplicativos: cria, posiciona, dá foco, oculta e fecha. Só dados e regras — não desenha nada
 * e não conhece Android. Thread principal apenas.
 */
class AppWindowManager(val maxWindows: Int = 4) {
    companion object {
        private const val RADIUS_M = 2.2f
        private const val EYE_Z_M = 1.6f
        private const val SLOT_STEP_DEG = 38f
    }

    private val list = ArrayList<AppWindow>()
    private var nextId = 1

    /** Chamado após qualquer mudança (criar, fechar, foco, visibilidade, estado). */
    var onChanged: (() -> Unit)? = null

    fun all(): List<AppWindow> = ArrayList(list)
    fun find(id: Int): AppWindow? = list.firstOrNull { it.id == id }
    fun findByPackage(pkg: String): AppWindow? = list.firstOrNull { it.packageName == pkg }

    /** Janela do app [pkg]: reaproveita a existente (reiniciando o estado) ou cria outra. null = limite de janelas atingido. */
    fun openFor(pkg: String, name: String): AppWindow? {
        val existing = findByPackage(pkg)
        if (existing != null) {
            existing.state = AppRunState.CREATED
            existing.status = ""
            focusInternal(existing)
            changed()
            return existing
        }
        if (list.size >= maxWindows) return null
        val w = AppWindow(nextId++, pkg, name)
        w.slot = firstFreeSlot()
        place(w)
        list.add(w)
        focusInternal(w)
        changed()
        return w
    }

    fun focus(id: Int) {
        val w = find(id) ?: return
        focusInternal(w)
        changed()
    }

    fun setVisible(id: Int, visible: Boolean) {
        val w = find(id) ?: return
        w.visible = visible
        if (!visible) w.focused = false
        changed()
    }

    fun setState(id: Int, state: AppRunState, status: String) {
        val w = find(id) ?: return
        w.state = state
        w.status = status
        changed()
    }

    fun close(id: Int) {
        val w = find(id) ?: return
        list.remove(w)
        w.state = AppRunState.CLOSED
        w.focused = false
        w.releaseSurface()
        if (list.none { it.focused }) list.lastOrNull { it.visible }?.focused = true
        changed()
    }

    fun closeAll() {
        if (list.isEmpty()) return
        for (w in list) { w.state = AppRunState.CLOSED; w.focused = false; w.releaseSurface() }
        list.clear()
        changed()
    }

    private fun focusInternal(w: AppWindow) {
        for (o in list) o.focused = false
        w.visible = true
        w.focused = true
    }

    private fun firstFreeSlot(): Int {
        var s = 0
        while (list.any { it.slot == s }) s++
        return s
    }

    /** Posição inicial em arco ao redor do usuário: frente, direita, esquerda, mais à direita... todas voltadas para ele. */
    private fun place(w: AppWindow) {
        val k = (w.slot + 1) / 2
        val sign = if (w.slot % 2 == 1) 1f else -1f
        val az = Math.toRadians((k * SLOT_STEP_DEG * sign).toDouble())
        w.x = (RADIUS_M * sin(az)).toFloat()
        w.y = (RADIUS_M * cos(az)).toFloat()
        w.z = EYE_Z_M
        w.yawDeg = (k * SLOT_STEP_DEG * sign)
    }

    private fun changed() { onChanged?.invoke() }
}
