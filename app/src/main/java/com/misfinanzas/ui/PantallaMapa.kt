package com.misfinanzas.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.layout.Row
import com.misfinanzas.FinanzasViewModel
import com.misfinanzas.Tipos
import com.misfinanzas.data.Transaccion
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.rememberCameraPositionState

/** Centro de México, por si el mes aún no tiene gastos con ubicación. */
val CENTRO_INICIAL = LatLng(19.4326, -99.1332)

/** Todos los gastos del mes con ubicación, como pines. Tocar el globo abre el movimiento. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PantallaMapa(vm: FinanzasViewModel, botonMenu: @Composable () -> Unit) {
    val mes by vm.mes.collectAsStateWithLifecycle()
    val puntos by vm.conUbicacion.collectAsStateWithLifecycle()
    val tarjetas by vm.tarjetas.collectAsStateWithLifecycle()
    val gastos = puntos.filter { it.tipo == Tipos.GASTO || it.tipo == Tipos.MSI }
    var editando by remember { mutableStateOf<Transaccion?>(null) }
    val camara = rememberCameraPositionState {
        position = com.google.android.gms.maps.model.CameraPosition.fromLatLngZoom(CENTRO_INICIAL, 11f)
    }

    // Encuadra todos los pines del mes cada vez que cambian.
    LaunchedEffect(gastos.map { it.id }) {
        if (gastos.isEmpty()) return@LaunchedEffect
        val update = if (gastos.size == 1) {
            CameraUpdateFactory.newLatLngZoom(LatLng(gastos[0].lat!!, gastos[0].lon!!), 15f)
        } else {
            val b = LatLngBounds.builder()
            gastos.forEach { b.include(LatLng(it.lat!!, it.lon!!)) }
            CameraUpdateFactory.newLatLngBounds(b.build(), 120)
        }
        runCatching { camara.animate(update) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { vm.cambiarMes(-1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Mes anterior") }
                        Text(textoMes(mes))
                        IconButton(onClick = { vm.cambiarMes(1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Mes siguiente") }
                    }
                },
                navigationIcon = botonMenu,
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            GoogleMap(
                modifier = Modifier.fillMaxSize(),
                cameraPositionState = camara,
                uiSettings = MapUiSettings(zoomControlsEnabled = false, mapToolbarEnabled = false),
            ) {
                gastos.forEach { t ->
                    Marker(
                        state = MarkerState(LatLng(t.lat!!, t.lon!!)),
                        title = t.comercio,
                        snippet = "${dinero(t.monto)} · ${textoDia(diaDe(t.fecha))}",
                        icon = BitmapDescriptorFactory.defaultMarker(Iconos.de(t.categoria).tono()),
                        onInfoWindowClick = { editando = t },
                    )
                }
            }
            Card(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        if (gastos.isEmpty()) "Sin compras con ubicación este mes"
                        else "${gastos.size} compras en persona · ${dinero(gastos.sumOf { it.monto })}",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        "Las compras en línea no salen en el mapa.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    editando?.let { t ->
        DialogoTransaccion(
            inicial = t,
            tarjetasConocidas = tarjetas,
            onGuardar = { nueva -> vm.guardar(nueva, nueva.categoria != t.categoria); editando = null },
            onEliminar = { vm.eliminar(t); editando = null },
            onCerrar = { editando = null },
        )
    }
}

/** El color de la categoría convertido al tono (0–360) que usan los pines de Google Maps. */
private fun Iconos.Estilo.tono(): Float {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(android.graphics.Color.argb(255, (color.red * 255).toInt(), (color.green * 255).toInt(), (color.blue * 255).toInt()), hsv)
    return hsv[0]
}
