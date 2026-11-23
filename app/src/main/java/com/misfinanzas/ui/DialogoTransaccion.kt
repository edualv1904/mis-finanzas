package com.misfinanzas.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.misfinanzas.Categorias
import com.misfinanzas.Tipos
import com.misfinanzas.data.Transaccion
import kotlinx.coroutines.delay
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.misfinanzas.Meses
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

fun nuevaTransaccion(categoria: String = "Otros") = Transaccion(
    monto = 0.0,
    comercio = "",
    tarjeta = "Efectivo",
    fecha = System.currentTimeMillis(),
    categoria = categoria,
    fuente = "manual",
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DialogoTransaccion(
    inicial: Transaccion,
    tarjetasConocidas: List<String>,
    onGuardar: (Transaccion) -> Unit,
    onEliminar: () -> Unit,
    onCerrar: () -> Unit,
    /** Solo en gastos de tarjeta de crédito; null = no se ofrece. */
    onPasarAMeses: ((meses: Int, primerMes: YearMonth) -> Unit)? = null,
    onQuitarMeses: (() -> Unit)? = null,
    /** Busca movimientos del mismo monto en días cercanos; null = no se ofrece "Es duplicado". */
    buscarDuplicados: (suspend () -> List<Transaccion>)? = null,
    onDuplicadoDe: (conservar: Transaccion) -> Unit = {},
    /** Solo en gastos ya guardados: compartirlo en un grupo (o cambiar cómo se reparte). */
    onCompartir: (() -> Unit)? = null,
    /** "En Viaje · tu parte $400" si ya está en un grupo. */
    enGrupo: String? = null,
) {
    val esNueva = inicial.id == 0L
    var modo by remember { mutableStateOf(Modo.EDITAR) }
    when (modo) {
        Modo.MESES -> return DialogoMeses(inicial.monto, onAceptar = { m, p -> onPasarAMeses?.invoke(m, p) }, onCerrar = { modo = Modo.EDITAR })
        Modo.DUPLICADO -> return DialogoDuplicado(inicial, buscarDuplicados!!, onDuplicadoDe, onCerrar = { modo = Modo.EDITAR })
        Modo.EDITAR -> Unit
    }
    var monto by remember { mutableStateOf(if (esNueva) "" else "%.2f".format(inicial.monto)) }
    var comercio by remember { mutableStateOf(inicial.comercio) }
    var tarjeta by remember { mutableStateOf(inicial.tarjeta) }
    var categoria by remember { mutableStateOf(inicial.categoria) }
    var tipo by remember { mutableStateOf(inicial.tipo) }
    var nota by remember { mutableStateOf(inicial.nota) }
    var lat by remember { mutableStateOf(inicial.lat) }
    var lon by remember { mutableStateOf(inicial.lon) }
    var lugar by remember { mutableStateOf(inicial.lugar) }
    val ctx = androidx.compose.ui.platform.LocalContext.current

    // Un gasto que capturas a mano normalmente lo registras donde estás: se propone tu ubicación.
    LaunchedEffect(Unit) {
        if (esNueva && com.misfinanzas.Ubicacion.hayPermiso(ctx)) {
            com.misfinanzas.Ubicacion.actual(ctx)?.let { p ->
                if (lat == null) {
                    lat = p.latitude; lon = p.longitude
                    lugar = com.misfinanzas.Ubicacion.direccion(ctx, p.latitude, p.longitude)
                }
            }
        }
    }

    // En un gasto nuevo, el cursor arranca en el monto con el teclado abierto.
    val focoMonto = remember { FocusRequester() }
    val teclado = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        if (esNueva) {
            delay(150)
            focoMonto.requestFocus()
            teclado?.show()
        }
    }

    val montoValido = monto.replace(",", "").toDoubleOrNull()?.takeIf { it > 0 }
    val sugerenciasTarjeta = (listOf("Efectivo") + tarjetasConocidas).distinct()

    AlertDialog(
        onDismissRequest = onCerrar,
        title = { Text(if (esNueva) "Nuevo movimiento" else "Editar movimiento") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(Tipos.GASTO to "Gasto", Tipos.INGRESO to "Ingreso", Tipos.TRANSFERENCIA to "Transferencia")
                        .forEach { (valor, texto) -> FilterChip(tipo == valor, { tipo = valor }, label = { Text(texto) }) }
                }
                OutlinedTextField(
                    monto, { monto = it }, Modifier.fillMaxWidth().focusRequester(focoMonto),
                    label = { Text("Monto") }, prefix = { Text("$") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                OutlinedTextField(comercio, { comercio = it }, Modifier.fillMaxWidth(), label = { Text("Concepto / comercio") }, singleLine = true)
                OutlinedTextField(tarjeta, { tarjeta = it }, Modifier.fillMaxWidth(), label = { Text("Tarjeta / método") }, singleLine = true)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    sugerenciasTarjeta.forEach { s ->
                        FilterChip(tarjeta == s, { tarjeta = s }, label = { Text(s) })
                    }
                }
                Text("Categoría", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Categorias.todas.forEach { c ->
                        FilterChip(categoria == c, { categoria = c }, label = { Text(c) })
                    }
                }
                OutlinedTextField(nota, { nota = it }, Modifier.fillMaxWidth(), label = { Text("Nota") })
                if (tipo == Tipos.GASTO || lat != null) {
                    SeccionUbicacion(lat, lon, lugar, etiqueta = comercio.ifBlank { "Gasto" }, onCambio = { a, b, c ->
                        lat = a; lon = b; lugar = c
                    })
                }
                if (inicial.tipo == Tipos.MSI) {
                    Text(
                        "Compra a meses: lo que suma son sus mensualidades. El desglose está en Tarjetas.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (inicial.fuente == Meses.FUENTE) {
                    Text(
                        "Mensualidad ${inicial.nota} de una compra a meses.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (inicial.tipo == Tipos.MSI && onQuitarMeses != null) {
                        OutlinedButton(onClick = onQuitarMeses) { Text("Quitar meses") }
                    } else if (onPasarAMeses != null) {
                        OutlinedButton(onClick = { modo = Modo.MESES }) { Text("Pasar a meses") }
                    }
                    if (buscarDuplicados != null) {
                        OutlinedButton(onClick = { modo = Modo.DUPLICADO }) { Text("Es duplicado") }
                    }
                    if (onCompartir != null) {
                        OutlinedButton(onClick = onCompartir) { Text(if (enGrupo != null) "Cambiar reparto" else "Agregar a un split") }
                    }
                }
                if (enGrupo != null) Text(enGrupo, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(
                enabled = montoValido != null && comercio.isNotBlank(),
                onClick = {
                    onGuardar(
                        inicial.copy(
                            monto = montoValido!!,
                            comercio = comercio.trim(),
                            tarjeta = tarjeta.trim().ifEmpty { "Efectivo" },
                            categoria = categoria,
                            nota = nota.trim(),
                            tipo = tipo,
                            lat = lat,
                            lon = lon,
                            lugar = lugar,
                        )
                    )
                },
            ) { Text("Guardar") }
        },
        dismissButton = {
            Row {
                if (!esNueva) TextButton(onClick = onEliminar) { Text("Eliminar", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onCerrar) { Text("Cancelar") }
            }
        },
    )
}

private enum class Modo { EDITAR, MESES, DUPLICADO }

private val fmtMesAnio = DateTimeFormatter.ofPattern("MMMM yyyy", Locale("es", "MX"))
private val fmtDiaHora = DateTimeFormatter.ofPattern("d MMM · HH:mm", Locale("es", "MX"))

/** Número de mensualidades y el mes en que pagas la primera. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DialogoMeses(total: Double, onAceptar: (Int, YearMonth) -> Unit, onCerrar: () -> Unit) {
    var meses by remember { mutableIntStateOf(3) }
    var primerMes by remember { mutableStateOf(YearMonth.now().plusMonths(1)) }
    val montos = Meses.montos(total, meses)
    AlertDialog(
        onDismissRequest = onCerrar,
        title = { Text("Pasar a meses") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Mensualidades", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(2, 3, 6, 9, 12, 18, 24).forEach { n ->
                        FilterChip(meses == n, { meses = n }, label = { Text("$n") })
                    }
                }
                Text("Primera mensualidad", style = MaterialTheme.typography.labelLarge)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { primerMes = primerMes.minusMonths(1) }) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Mes anterior")
                    }
                    Text(primerMes.format(fmtMesAnio).replaceFirstChar { it.uppercase() }, Modifier.weight(1f), textAlign = TextAlign.Center)
                    IconButton(onClick = { primerMes = primerMes.plusMonths(1) }) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Mes siguiente")
                    }
                }
                Text(
                    "$meses pagos de ${dinero(montos.first())}, de ${primerMes.format(fmtMesAnio)} a " +
                        "${primerMes.plusMonths(meses - 1L).format(fmtMesAnio)}. Cada uno se cuenta como gasto " +
                        "de ese mes, en tu fecha límite de pago.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onAceptar(meses, primerMes) }) { Text("Pasar a meses") } },
        dismissButton = { TextButton(onClick = onCerrar) { Text("Cancelar") } },
    )
}

/** Eliges cuál es el movimiento bueno; el que estás viendo se borra y no vuelve a entrar. */
@Composable
private fun DialogoDuplicado(
    t: Transaccion,
    buscar: suspend () -> List<Transaccion>,
    onElegir: (Transaccion) -> Unit,
    onCerrar: () -> Unit,
) {
    val candidatos by produceState<List<Transaccion>?>(null) { value = buscar() }
    var elegido by remember { mutableStateOf<Transaccion?>(null) }
    val apagado = MaterialTheme.colorScheme.onSurfaceVariant
    AlertDialog(
        onDismissRequest = onCerrar,
        title = { Text("¿Duplicado de cuál?") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                when {
                    candidatos == null -> Text("Buscando…", color = apagado)
                    candidatos!!.isEmpty() -> Text("No hay otro movimiento de ${dinero(t.monto)} tres días antes o después.")
                    else -> {
                        Text(
                            "Elige el que se queda. Este (${t.comercio}) se borra y, si la notificación vuelve a llegar, no se registra otra vez.",
                            style = MaterialTheme.typography.bodySmall,
                            color = apagado,
                        )
                        candidatos!!.forEach { c ->
                            Row(
                                Modifier.fillMaxWidth().clickable { elegido = c }.padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = elegido == c, onClick = { elegido = c })
                                Column(Modifier.weight(1f)) {
                                    Text(c.comercio, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        "${c.tarjeta} · ${diaHora(c.fecha)} · ${origen(c.fuente)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = apagado,
                                    )
                                }
                                Text(dinero(c.monto))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = elegido != null, onClick = { onElegir(elegido!!) }) { Text("Quitar duplicado") }
        },
        dismissButton = { TextButton(onClick = onCerrar) { Text("Cancelar") } },
    )
}

private fun diaHora(millis: Long) =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(fmtDiaHora).replace(".", "")

private fun origen(fuente: String) = when (fuente) {
    "wallet" -> "Wallet"
    "manual" -> "manual"
    "estado" -> "estado de cuenta"
    else -> "notificación del banco"
}
