package com.misfinanzas.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.misfinanzas.BuildConfig
import com.misfinanzas.R
import com.misfinanzas.Ubicacion
import kotlinx.coroutines.launch

/** Dónde fue un movimiento: verlo en Google Maps, ponerlo con tu ubicación actual o quitarlo. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SeccionUbicacion(
    lat: Double?,
    lon: Double?,
    lugar: String?,
    etiqueta: String,
    onCambio: (lat: Double?, lon: Double?, lugar: String?) -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var buscando by remember { mutableStateOf(false) }
    var aviso by remember { mutableStateOf<String?>(null) }
    var eligiendo by remember { mutableStateOf(false) }
    val apagado = MaterialTheme.colorScheme.onSurfaceVariant

    fun usarActual() {
        buscando = true
        aviso = null
        scope.launch {
            val p = Ubicacion.actual(ctx)
            if (p == null) {
                aviso = "No pude obtener tu ubicación. Revisa que el GPS esté encendido."
            } else {
                onCambio(p.latitude, p.longitude, null)
                onCambio(p.latitude, p.longitude, Ubicacion.direccion(ctx, p.latitude, p.longitude))
            }
            buscando = false
        }
    }

    val pedirPermiso = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r[Manifest.permission.ACCESS_FINE_LOCATION] == true) usarActual()
        else aviso = "Sin permiso de ubicación no puedo saber dónde estás."
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Ubicación", style = MaterialTheme.typography.labelLarge)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_location_on), contentDescription = null, Modifier.size(18.dp), tint = apagado)
            Spacer(Modifier.width(6.dp))
            Text(
                when {
                    buscando -> "Buscando dónde estás…"
                    lat == null || lon == null -> "Sin ubicación"
                    else -> lugar ?: "%.5f, %.5f".format(lat, lon)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (lat == null) apagado else MaterialTheme.colorScheme.onSurface,
            )
        }
        aviso?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (lat != null && lon != null) {
                OutlinedButton(onClick = {
                    val q = Uri.encode("$lat,$lon($etiqueta)")
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:$lat,$lon?q=$q")))
                }) { Text("Ver en Google Maps") }
            }
            OutlinedButton(enabled = !buscando, onClick = {
                if (Ubicacion.hayPermiso(ctx)) usarActual()
                else pedirPermiso.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            }) { Text(if (lat == null) "Usar dónde estoy" else "Cambiar a dónde estoy") }
            if (BuildConfig.HAY_MAPA) OutlinedButton(onClick = { eligiendo = true }) { Text("Elegir en el mapa") }
            if (lat != null) OutlinedButton(onClick = { onCambio(null, null, null) }) { Text("Quitar") }
        }
    }

    if (eligiendo) {
        DialogoElegirEnMapa(
            inicial = if (lat != null && lon != null) com.google.android.gms.maps.model.LatLng(lat, lon) else null,
            onElegir = { p ->
                eligiendo = false
                onCambio(p.latitude, p.longitude, null)
                scope.launch { onCambio(p.latitude, p.longitude, Ubicacion.direccion(ctx, p.latitude, p.longitude)) }
            },
            onCerrar = { eligiendo = false },
        )
    }
}
