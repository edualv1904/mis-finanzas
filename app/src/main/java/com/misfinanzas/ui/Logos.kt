package com.misfinanzas.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.misfinanzas.Tarjetas

// El icono se le pide a Android una sola vez por app; null = esa app no está instalada.
private val cache = HashMap<String, ImageBitmap?>()

/** Icono de una app instalada en el teléfono, o [respaldo] si no está. */
@Composable
fun LogoPaquete(paquete: String?, tamano: Dp = 40.dp, respaldo: @Composable () -> Unit = {}) {
    val ctx = LocalContext.current
    val logo = remember(paquete) {
        paquete?.let {
            cache.getOrPut(it) {
                runCatching { ctx.packageManager.getApplicationIcon(it).toBitmap(144, 144).asImageBitmap() }.getOrNull()
            }
        }
    }
    if (logo != null) {
        Image(logo, contentDescription = null, modifier = Modifier.size(tamano).clip(RoundedCornerShape(tamano * 0.28f)))
    } else {
        respaldo()
    }
}

/** Logo del banco de una tarjeta (por su nombre canónico). */
@Composable
fun LogoTarjeta(tarjeta: String, tamano: Dp = 40.dp, respaldo: @Composable () -> Unit) {
    LogoPaquete(Tarjetas.paquete(tarjeta), tamano, respaldo)
}
