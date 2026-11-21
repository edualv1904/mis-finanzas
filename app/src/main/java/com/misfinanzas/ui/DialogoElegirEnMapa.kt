package com.misfinanzas.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.misfinanzas.R
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.rememberCameraPositionState

/** Mueves el mapa hasta que el pin del centro quede sobre el lugar de la compra. */
@Composable
fun DialogoElegirEnMapa(inicial: LatLng?, onElegir: (LatLng) -> Unit, onCerrar: () -> Unit) {
    val camara = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(inicial ?: CENTRO_INICIAL, if (inicial != null) 16f else 11f)
    }
    Dialog(onDismissRequest = onCerrar, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    GoogleMap(
                        modifier = Modifier.fillMaxSize(),
                        cameraPositionState = camara,
                        uiSettings = MapUiSettings(zoomControlsEnabled = false, mapToolbarEnabled = false),
                    )
                    // La punta del pin marca el centro del mapa.
                    Icon(
                        painterResource(R.drawable.ic_location_on), contentDescription = null,
                        Modifier.align(Alignment.Center).size(44.dp).offset(y = (-20).dp),
                        tint = Tinta,
                    )
                }
                Column(Modifier.padding(16.dp)) {
                    Text("Mueve el mapa hasta dejar el pin donde pagaste.", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.size(12.dp))
                    Row {
                        OutlinedButton(onClick = onCerrar, Modifier.weight(1f)) { Text("Cancelar") }
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = { onElegir(camara.position.target) }, Modifier.weight(1f)) { Text("Usar este lugar") }
                    }
                }
            }
        }
    }
}
