package com.misfinanzas.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.misfinanzas.Cobros
import com.misfinanzas.Deudas
import com.misfinanzas.Personas
import com.misfinanzas.Personas.Perfil
import java.time.format.DateTimeFormatter
import java.util.Locale

private val fmtDiaCorto = DateTimeFormatter.ofPattern("d MMM", Locale("es", "MX"))
private fun diaCorto(millis: Long) = diaDe(millis).format(fmtDiaCorto).replace(".", "")

/**
 * Personas: una tarjeta por cada quien con lo que te debe y lo que le debes, juntando "Me deben" y
 * "Debo". Al tocarla, su perfil con todo desglosado.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PantallaPersonas(
    perfiles: List<Perfil>,
    abierta: String?,
    onAbrir: (clave: String?) -> Unit,
    onAbrirConcepto: (concepto: String) -> Unit,
    onAbrirDeuda: (deudaId: Long) -> Unit,
    onAbrirGrupo: (grupoId: Long) -> Unit,
    onRenombrar: (Perfil, String) -> Unit,
    botonMenu: @Composable () -> Unit,
) {
    val ahora = System.currentTimeMillis()
    val perfil = perfiles.firstOrNull { it.clave == abierta }
    var renombrando by remember { mutableStateOf<Perfil?>(null) }
    val apagado = MaterialTheme.colorScheme.onSurfaceVariant

    BackHandler(enabled = perfil != null) { onAbrir(null) }
    val estadoLista = remember(perfil?.clave) { LazyListState() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(perfil?.nombre ?: "Personas") },
                navigationIcon = {
                    if (perfil != null) IconButton(onClick = { onAbrir(null) }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver")
                    } else botonMenu()
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            state = estadoLista,
            contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (perfil == null) {
                // ---------- Lista ----------
                val activas = perfiles.filter { it.activa }
                    .sortedWith(compareByDescending<Perfil> { kotlin.math.abs(it.neto(ahora)) > 0.005 }.thenBy { it.nombre.lowercase() })
                item {
                    Column(Modifier.padding(vertical = 8.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                            Cifra("TE DEBEN", perfiles.sumOf { it.teDebe(ahora) })
                            Cifra("DEBES", perfiles.sumOf { it.leDebes() })
                        }
                        Text(
                            "Junta lo de Me deben, Debo y Split por persona. Para agregar a alguien, dalo de alta en cualquiera de ellos.",
                            Modifier.padding(top = 8.dp),
                            style = MaterialTheme.typography.bodySmall, color = apagado,
                        )
                    }
                }
                if (perfiles.isEmpty()) item {
                    Text("Todavía no hay nadie. Cuando agregues a alguien en Me deben o en Debo, aparecerá aquí.", Modifier.padding(vertical = 16.dp))
                }
                items(activas, key = { "p${it.clave}" }) { p -> TarjetaPersona(p, ahora) { onAbrir(p.clave) } }
                val anteriores = perfiles.filter { !it.activa }.sortedBy { it.nombre.lowercase() }
                if (anteriores.isNotEmpty()) item {
                    Text("Ya sin pendientes", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                }
                items(anteriores, key = { "a${it.clave}" }) { p ->
                    Row(Modifier.fillMaxWidth().clickable { onAbrir(p.clave) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Inicial(p.nombre, chica = true)
                        Spacer(Modifier.width(12.dp))
                        Text(p.nombre, Modifier.weight(1f))
                        Text("Te pagó ${dinero(p.pagado())}", color = apagado, style = MaterialTheme.typography.bodySmall)
                    }
                }
            } else {
                // ---------- Perfil ----------
                val p = perfil
                val neto = p.neto(ahora)
                val atrasado = p.cobros.any { Cobros.atrasado(it, ahora) }
                item {
                    Column(Modifier.padding(vertical = 8.dp)) {
                        Text(
                            when {
                                neto > 0.005 -> "TE DEBE"
                                neto < -0.005 -> "LE DEBES"
                                else -> "ESTÁN A MANO"
                            },
                            style = MaterialTheme.typography.labelMedium, color = apagado,
                        )
                        Text(
                            dinero(kotlin.math.abs(neto)), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold,
                            color = if (neto > 0.005 && atrasado) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                        )
                        // Si hay de los dos lados, que se vea cómo sale la cuenta.
                        if (p.teDebe(ahora) > 0.005 && p.leDebes() > 0.005) Text(
                            "Te debe ${dinero(p.teDebe(ahora))} y le debes ${dinero(p.leDebes())}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (atrasado) Text("Tiene pagos atrasados", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        p.siguiente(ahora)?.let { c ->
                            val concepto = p.acuerdos.firstOrNull { it.id == c.acuerdoId }?.concepto.orEmpty()
                            Text("Siguiente: ${dinero(c.monto)} de $concepto el ${diaCorto(c.vence)}", style = MaterialTheme.typography.bodySmall, color = apagado)
                        }
                        if (p.acuerdos.isNotEmpty()) Text("Te ha pagado ${dinero(p.pagado())} en total", style = MaterialTheme.typography.bodySmall, color = apagado)
                        TextButton(onClick = { renombrando = p }, contentPadding = PaddingValues(0.dp)) { Text("Cambiar nombre") }
                    }
                }
                if (p.acuerdos.isNotEmpty()) item { Titulo("Me debe") }
                items(p.acuerdos.sortedByDescending { it.activo }, key = { "ac${it.id}" }) { a ->
                    val suyos = p.cobros.filter { it.acuerdoId == a.id }
                    val vencido = suyos.filter { it.pagado == null && it.vence <= ahora }.sumOf { it.monto }
                    val pagados = suyos.count { it.pagado != null }
                    val falta = a.meses?.let { total -> (total - pagados).coerceAtLeast(0) * a.monto }
                    Card(Modifier.fillMaxWidth().clickable { onAbrirConcepto(a.concepto) }) {
                        Column(Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(a.concepto, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                                if (vencido > 0.005) Text(
                                    dinero(vencido), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                                    color = if (suyos.any { Cobros.atrasado(it, ahora) }) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                                )
                            }
                            Text(plan(a, pagados), color = apagado)
                            Text(
                                when {
                                    !a.activo -> "Terminado"
                                    vencido > 0.005 -> "Pendiente de pagarte"
                                    falta != null && falta > 0.005 -> "Le faltan ${dinero(falta)} en total"
                                    else -> "Al corriente"
                                },
                                style = MaterialTheme.typography.bodySmall, color = apagado,
                            )
                        }
                    }
                }
                if (p.deudas.isNotEmpty()) item { Titulo("Le debo") }
                items(p.deudas, key = { "de${it.id}" }) { d ->
                    val saldo = Deudas.saldo(p.movs.filter { it.deudaId == d.id })
                    Card(Modifier.fillMaxWidth().clickable { onAbrirDeuda(d.id) }) {
                        Column(Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(d.tarifa?.let { "Sesiones" } ?: "Libreta", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                                Text(dinero(saldo.coerceAtLeast(0.0)), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            }
                            d.tarifa?.let { Text("${dinero(it)} por sesión", color = apagado) }
                            Text(if (!d.activa) "Terminada" else if (saldo <= 0.005) "Al corriente" else "Lo que le debes", style = MaterialTheme.typography.bodySmall, color = apagado)
                        }
                    }
                }
                if (p.grupos.isNotEmpty()) item { Titulo("En splits") }
                items(p.grupos.sortedByDescending { it.activo }, key = { "gr${it.grupoId}" }) { g ->
                    Card(Modifier.fillMaxWidth().clickable { onAbrirGrupo(g.grupoId) }) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(g.nombre, style = MaterialTheme.typography.titleMedium)
                                if (!g.activo) Text("Terminado", style = MaterialTheme.typography.bodySmall, color = apagado)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    if (kotlin.math.abs(g.conmigo) > 0.005) dinero(kotlin.math.abs(g.conmigo)) else "—",
                                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                                )
                                Text(
                                    when {
                                        g.conmigo > 0.005 -> "te debe"
                                        g.conmigo < -0.005 -> "le debes"
                                        else -> "a mano contigo"
                                    },
                                    style = MaterialTheme.typography.bodySmall, color = apagado,
                                )
                            }
                        }
                    }
                }
                val historial = p.historial()
                if (historial.isNotEmpty()) item { Titulo("Historial") }
                items(historial.take(60), key = { "h${it.fecha}${it.texto}${it.monto}" }) { r ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(r.texto)
                            Text(textoDia(diaDe(r.fecha)), style = MaterialTheme.typography.bodySmall, color = apagado)
                        }
                        Text(
                            dinero(r.monto),
                            color = if (r.recibido) Ingreso else MaterialTheme.colorScheme.onSurface,
                            fontWeight = if (r.recibido) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                }
            }
        }
    }

    renombrando?.let { p ->
        var nombre by remember(p.clave) { mutableStateOf(p.nombre) }
        val otra = perfiles.firstOrNull { it.clave != p.clave && it.clave == Personas.clave(nombre) }
        AlertDialog(
            onDismissRequest = { renombrando = null },
            title = { Text("Cambiar nombre") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(nombre, { nombre = it }, Modifier.fillMaxWidth(), singleLine = true)
                    Text(
                        if (otra != null) "Ya existe ${otra.nombre}: se juntarán en una sola persona."
                        else "Cambia el nombre en Me deben y en Debo.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                TextButton(enabled = nombre.isNotBlank() && nombre.trim() != p.nombre, onClick = {
                    val nuevo = otra?.nombre ?: nombre.trim()
                    onRenombrar(p, nuevo)
                    onAbrir(Personas.clave(nuevo))
                    renombrando = null
                }) { Text(if (otra != null) "Juntar" else "Guardar") }
            },
            dismissButton = { TextButton(onClick = { renombrando = null }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun TarjetaPersona(p: Perfil, ahora: Long, onClick: () -> Unit) {
    val apagado = MaterialTheme.colorScheme.onSurfaceVariant
    val neto = p.neto(ahora)
    val atrasado = p.cobros.any { Cobros.atrasado(it, ahora) }
    // Qué tiene con esta persona: "Spotify · Compu · Le debo"
    val que = (p.acuerdos.filter { it.activo }.map { it.concepto.trim() }.distinct() +
        (if (p.deudas.any { it.activa }) listOf("Le debo") else emptyList()) +
        p.grupos.filter { it.activo }.map { it.nombre }).joinToString(" · ")
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Inicial(p.nombre)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(p.nombre, style = MaterialTheme.typography.titleMedium)
                Text(que, style = MaterialTheme.typography.bodySmall, color = apagado)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    if (kotlin.math.abs(neto) > 0.005) dinero(kotlin.math.abs(neto)) else "—",
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                    color = if (neto > 0.005 && atrasado) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    when {
                        neto > 0.005 -> "te debe"
                        neto < -0.005 -> "le debes"
                        else -> "al corriente"
                    },
                    style = MaterialTheme.typography.bodySmall, color = apagado,
                )
            }
        }
    }
}

/** Círculo gris con la inicial del nombre. */
@Composable
private fun Inicial(nombre: String, chica: Boolean = false) {
    Box(
        Modifier.size(if (chica) 32.dp else 42.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            nombre.trim().take(1).uppercase(),
            style = if (chica) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Cifra(etiqueta: String, monto: Double) {
    Column {
        Text(etiqueta, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(dinero(monto), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun Titulo(texto: String) {
    Text(texto, Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
}
