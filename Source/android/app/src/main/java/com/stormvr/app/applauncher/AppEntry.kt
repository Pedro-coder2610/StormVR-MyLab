package com.stormvr.app.applauncher

import android.content.ComponentName
import android.graphics.Bitmap

/**
 * Um aplicativo inicializável instalado no aparelho. A identidade é o [packageName].
 * [icon] nunca é nulo: se o app não tem um ícone utilizável, o repositório gera um com a inicial do nome ([hasOwnIcon] = false).
 */
class AppEntry(
    val packageName: String,
    val label: String,
    val component: ComponentName,
    val isSystem: Boolean,
    val icon: Bitmap,
    val hasOwnIcon: Boolean
) {
    override fun equals(other: Any?): Boolean = other is AppEntry && other.packageName == packageName
    override fun hashCode(): Int = packageName.hashCode()
}
