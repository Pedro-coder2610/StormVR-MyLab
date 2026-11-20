package com.stormvr.app.applauncher

import android.graphics.SurfaceTexture
import android.view.Surface

/**
 * Superfície própria de uma janela, PREPARADA para virar textura externa (OES) no renderizador — AINDA NÃO LIGADA a ele.
 *
 * Usa o construtor SurfaceTexture(boolean) (API 26), que nasce "solta": pode ser criada na thread principal e passada a um
 * display virtual; só depois a thread GL a encaixa numa textura com [attachToGl]. Ligação ao VrRenderer = trabalho da próxima etapa.
 * Não validado em aparelho.
 */
class AppWindowSurface(val width: Int, val height: Int) {
    private val tex = SurfaceTexture(false)
    val surface: Surface
    @Volatile var frameAvailable = false
    private var released = false

    init {
        tex.setDefaultBufferSize(width, height)
        tex.setOnFrameAvailableListener { frameAvailable = true }
        surface = Surface(tex)
    }

    /** Thread GL: liga a SurfaceTexture a uma textura GL_TEXTURE_EXTERNAL_OES já criada. */
    fun attachToGl(texName: Int) { if (!released) tex.attachToGLContext(texName) }

    /** Thread GL: puxa o quadro mais novo e devolve a matriz de textura em [matrix] (16 floats). */
    fun updateTexImage(matrix: FloatArray) {
        if (released) return
        tex.updateTexImage()
        tex.getTransformMatrix(matrix)
        frameAvailable = false
    }

    fun detachFromGl() { if (!released) try { tex.detachFromGLContext() } catch (t: Throwable) { } }

    fun release() {
        if (released) return
        released = true
        try { surface.release() } catch (t: Throwable) { }
        try { tex.release() } catch (t: Throwable) { }
    }
}
