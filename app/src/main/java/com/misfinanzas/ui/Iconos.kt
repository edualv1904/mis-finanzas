package com.misfinanzas.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.misfinanzas.Categorias
import com.misfinanzas.R
import com.misfinanzas.Tipos

/** Icono y color de cada categoría; el color solo se usa en su recuadro. */
object Iconos {
    class Estilo(@DrawableRes val icono: Int, val color: Color)

    private val porCategoria = mapOf(
        "Comida" to Estilo(R.drawable.ic_restaurant, Color(0xFFD97706)),
        "Súper" to Estilo(R.drawable.ic_shopping_cart, Color(0xFF0E8F9C)),
        "Transporte" to Estilo(R.drawable.ic_directions_car, Color(0xFF3B5BDB)),
        "Gasolina" to Estilo(R.drawable.ic_local_gas_station, Color(0xFFD9480F)),
        "Entretenimiento" to Estilo(R.drawable.ic_movie, Color(0xFF7C3AED)),
        "Compras" to Estilo(R.drawable.ic_shopping_bag, Color(0xFFC2255C)),
        "Salud" to Estilo(R.drawable.ic_local_pharmacy, Color(0xFF2F9E44)),
        "Servicios" to Estilo(R.drawable.ic_bolt, Color(0xFFB7791F)),
        Categorias.INTERESES to Estilo(R.drawable.ic_percent, Color(0xFFC62F3B)),
    )
    private val otros = Estilo(R.drawable.ic_category, TintaSuave)
    private val ingreso = Estilo(R.drawable.ic_south_west, Ingreso)
    private val transferencia = Estilo(R.drawable.ic_swap_horiz, TintaSuave)

    fun de(categoria: String, tipo: String = Tipos.GASTO) = when (tipo) {
        Tipos.INGRESO -> ingreso
        Tipos.TRANSFERENCIA -> transferencia
        else -> porCategoria[categoria] ?: otros
    }
}

/** Icono de línea dentro de un recuadro con su color muy suave. */
@Composable
fun IconoCaja(@DrawableRes icono: Int, color: Color, tamano: Dp = 40.dp) {
    Box(
        Modifier.size(tamano).background(color.copy(alpha = 0.12f), RoundedCornerShape(tamano * 0.3f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icono), contentDescription = null, tint = color, modifier = Modifier.size(tamano * 0.5f))
    }
}

@Composable
fun IconoMovimiento(categoria: String, tipo: String = Tipos.GASTO, tamano: Dp = 40.dp) {
    val estilo = Iconos.de(categoria, tipo)
    IconoCaja(estilo.icono, estilo.color, tamano)
}
