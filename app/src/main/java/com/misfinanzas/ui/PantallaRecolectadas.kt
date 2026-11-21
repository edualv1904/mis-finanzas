package com.misfinanzas.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.misfinanzas.data.NotificacionCruda

private enum class Filtro(val texto: String, val etiqueta: String?) {
    SIN_REVISAR("Sin revisar", null),
    GASTOS("Gastos", "gasto"),
    INGRESOS("Ingresos", "ingreso"),
    TRANSFERENCIAS("Transferencias", "transferencia"),
    IGNORADAS("Ignoradas", "ignorar"),
}

private val nombresApps = mapOf(
    "com.nu.production" to "Nu",
    "com.grability.rappi" to "Rappi",
    "com.citibanamex.banamexmobile" to "Banamex",
    "com.bancomer.mbanking" to "BBVA",
    "com.mercadopago.wallet" to "Mercado Pago",
    "com.google.android.apps.walletnfcrel" to "Google Wallet",
)

private fun nombreApp(paquete: String) = nombresApps[paquete] ?: paquete

/** Notificaciones de bancos que la app no supo leer; tú marcas cuáles son pagos. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PantallaRecolectadas(
    crudas: List<NotificacionCruda>,
    onEtiquetar: (NotificacionCruda, String?) -> Unit,
    onBorrarIgnoradas: () -> Unit,
    botonMenu: @Composable () -> Unit,
) {
    var filtro by remember { mutableStateOf(Filtro.SIN_REVISAR) }
    var app by remember { mutableStateOf<String?>(null) } // null = todas
    val apps = crudas.map { it.paquete }.distinct().sortedBy { nombreApp(it) }
    val deLaApp = crudas.filter { app == null || it.paquete == app }
    val visibles = deLaApp.filter { it.etiqueta == filtro.etiqueta }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Notificaciones recolectadas") },
                navigationIcon = botonMenu,
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(app == null, { app = null }, label = { Text("Todas (${crudas.size})") })
                apps.forEach { p ->
                    FilterChip(app == p, { app = p }, label = { Text("${nombreApp(p)} (${crudas.count { it.paquete == p }})") })
                }
            }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Filtro.entries.forEach { f ->
                    val n = deLaApp.count { it.etiqueta == f.etiqueta }
                    FilterChip(filtro == f, { filtro = f }, label = { Text("${f.texto} ($n)") })
                }
            }
            if (filtro == Filtro.IGNORADAS && visibles.isNotEmpty()) {
                Text(
                    "Se borran solas 30 días después de llegar.",
                    Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onBorrarIgnoradas, Modifier.padding(horizontal = 8.dp)) {
                    Text("Borrar todas las ignoradas")
                }
            }
            if (visibles.isEmpty()) {
                Text(
                    when (filtro) {
                        Filtro.SIN_REVISAR -> "Nada pendiente. Aquí aparecen las notificaciones de tus bancos que aún no sé leer."
                        else -> "Aún no marcas ninguna en \"${filtro.texto}\"."
                    },
                    Modifier.padding(32.dp),
                )
            }
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(visibles, key = { it.id }) { n -> Tarjeta(n, onEtiquetar) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Tarjeta(n: NotificacionCruda, onEtiquetar: (NotificacionCruda, String?) -> Unit) {
    val color = when (n.etiqueta) {
        null, "ignorar" -> MaterialTheme.colorScheme.surfaceVariant
        else -> MaterialTheme.colorScheme.secondaryContainer
    }
    Card(colors = CardDefaults.cardColors(containerColor = color)) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LogoPaquete(n.paquete, tamano = 22.dp)
                    Text(nombreApp(n.paquete), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
                Text("${textoDia(diaDe(n.fecha))} ${hora(n.fecha)}", style = MaterialTheme.typography.labelSmall)
            }
            if (n.titulo.isNotBlank()) Text(n.titulo, fontWeight = FontWeight.SemiBold)
            if (n.texto.isNotBlank()) Text(n.texto, style = MaterialTheme.typography.bodyMedium)
            if (n.etiqueta == null) {
                FlowRow(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Filtro.entries.drop(1).forEach { f ->
                        if (f == Filtro.IGNORADAS) OutlinedButton(onClick = { onEtiquetar(n, f.etiqueta) }) { Text("Ignorar") }
                        else FilledTonalButton(onClick = { onEtiquetar(n, f.etiqueta) }) { Text(f.texto.removeSuffix("s")) }
                    }
                }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { onEtiquetar(n, null) }) { Text("Deshacer") }
                }
            }
        }
    }
}
