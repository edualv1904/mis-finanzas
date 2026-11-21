package com.misfinanzas

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Preferencias del usuario (no van en la base: no son movimientos).
 * Son estado de Compose, así que cualquier pantalla o el widget que las lea se redibuja al cambiarlas.
 */
object Ajustes {
    private const val ARCHIVO = "ajustes"
    private const val PEDIR_HUELLA = "pedir_huella"
    private const val OCULTAR_SALDOS = "ocultar_saldos"
    private const val SIN_AVISO_UBICACION = "sin_aviso_ubicacion"

    private var cargado = false

    /** Pedir huella, rostro o PIN al abrir la app. Activado de fábrica. */
    var pedirHuella by mutableStateOf(true)
        private set

    /** Cambia todas las cantidades por "$ ••••" para poder enseñar la app. */
    var saldosOcultos by mutableStateOf(false)
        private set

    /** Dijiste "Ahora no" al aviso de ubicación. */
    var sinAvisoUbicacion by mutableStateOf(false)
        private set

    @Synchronized
    fun cargar(ctx: Context) {
        if (cargado) return
        val p = ctx.getSharedPreferences(ARCHIVO, Context.MODE_PRIVATE)
        pedirHuella = p.getBoolean(PEDIR_HUELLA, true)
        saldosOcultos = p.getBoolean(OCULTAR_SALDOS, false)
        sinAvisoUbicacion = p.getBoolean(SIN_AVISO_UBICACION, false)
        cargado = true
    }

    fun cambiarPedirHuella(ctx: Context, valor: Boolean) {
        pedirHuella = valor
        ctx.getSharedPreferences(ARCHIVO, Context.MODE_PRIVATE).edit().putBoolean(PEDIR_HUELLA, valor).apply()
    }

    fun cambiarSaldosOcultos(ctx: Context, valor: Boolean) {
        saldosOcultos = valor
        ctx.getSharedPreferences(ARCHIVO, Context.MODE_PRIVATE).edit().putBoolean(OCULTAR_SALDOS, valor).apply()
    }

    fun ocultarAvisoUbicacion(ctx: Context) {
        sinAvisoUbicacion = true
        ctx.getSharedPreferences(ARCHIVO, Context.MODE_PRIVATE).edit().putBoolean(SIN_AVISO_UBICACION, true).apply()
    }
}
