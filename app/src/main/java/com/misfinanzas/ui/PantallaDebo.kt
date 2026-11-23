package com.misfinanzas.ui

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.misfinanzas.Deudas
import com.misfinanzas.data.Deuda
import com.misfinanzas.data.DeudaMov
import com.misfinanzas.data.Transaccion
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val fmtDia = DateTimeFormatter.ofPattern("EEE d MMM", Locale("es", "MX"))
private fun dia(millis: Long) = diaDe(millis).format(fmtDia).replace(".", "")
private fun leerMonto(t: String) = t.replace(",", "").replace("$", "").trim().toDoubleOrNull()?.takeIf { it > 0 }

/** Transferencias que ya dijiste que no son abonos (para no volver a preguntar). */
private fun descartadas(ctx: Context): Set<String> =
    ctx.getSharedPreferences("debo", Context.MODE_PRIVATE).getStringSet("no_son_abono", emptySet()) ?: emptySet()

private fun descartar(ctx: Context, transaccionId: Long) {
    val p = ctx.getSharedPreferences("debo", Context.MODE_PRIVATE)
    p.edit().putStringSet("no_son_abono", descartadas(ctx) + transaccionId.toString()).apply()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PantallaDebo(
    deudas: List<Deuda>,
    movs: List<DeudaMov>,
    salidas: List<Transaccion>,
    onCrear: (persona: String, debesAhora: Double?, tarifa: Double?, preguntar: Boolean) -> Unit,
    onEditar: (Deuda) -> Unit,
    onBorrar: (Deuda) -> Unit,
    onAnotar: (DeudaMov) -> Unit,
    onEditarMov: (DeudaMov) -> Unit,
    onBorrarMov: (DeudaMov) -> Unit,
    botonMenu: @Composable () -> Unit,
    /** Libreta que abrir de entrada (al venir del perfil de una persona). */
    inicial: Long? = null,
    /** Regresar al perfil de donde vino, en vez de a la lista de Debo. */
    onVolver: (() -> Unit)? = null,
    /** Gente que ya tienes, para elegirla de la lista. */
    conocidas: List<String> = emptyList(),
) {
    val ctx = LocalContext.current
    var abiertaId by rememberSaveable { mutableStateOf(inicial) }
    val abierta = deudas.firstOrNull { it.id == abiertaId }
    var nueva by remember { mutableStateOf(false) }
    var editando by remember { mutableStateOf<Deuda?>(null) }
    var anotando by remember { mutableStateOf<String?>(null) }   // Deudas.CARGO o Deudas.ABONO
    var movAbierto by remember { mutableStateOf<DeudaMov?>(null) }
    var noSon by remember { mutableStateOf(descartadas(ctx)) }
    val porDeuda = movs.groupBy { it.deudaId }
    val saldo = { d: Deuda -> Deudas.saldo(porDeuda[d.id].orEmpty()) }
    val apagado = MaterialTheme.colorScheme.onSurfaceVariant
    val ligadas = movs.mapNotNull { it.transaccionId }.toSet()

    val cerrar = { if (onVolver != null && abierta != null && abierta.id == inicial) onVolver() else abiertaId = null }
    BackHandler(enabled = abierta != null) { cerrar() }
    val estadoLista = remember(abierta?.id) { LazyListState() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(abierta?.persona ?: "Debo") },
                navigationIcon = {
                    if (abierta != null) IconButton(onClick = cerrar) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver")
                    } else botonMenu()
                },
            )
        },
        floatingActionButton = {
            if (abierta == null) FloatingActionButton(onClick = { nueva = true }) { Icon(Icons.Default.Add, "Nueva deuda") }
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            state = estadoLista,
            contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (abierta == null) {
                // ---------- Lista: una tarjeta por persona ----------
                val activas = deudas.filter { it.activa }
                item {
                    Column(Modifier.padding(vertical = 8.dp)) {
                        Text("DEBES", style = MaterialTheme.typography.labelMedium, color = apagado)
                        Text(dinero(activas.sumOf { saldo(it).coerceAtLeast(0.0) }), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                        Text(
                            "Es solo para llevar la cuenta: no cambia tus movimientos ni lo que llevas gastado.",
                            style = MaterialTheme.typography.bodySmall, color = apagado,
                        )
                    }
                }
                if (deudas.isEmpty()) item {
                    Text(
                        "Toca + para apuntar lo que le debes a alguien: un préstamo que vas pagando cuando puedes, " +
                            "o algo que se va acumulando, como clases o consultas que pagas después.",
                        Modifier.padding(vertical = 16.dp),
                    )
                }
                items(activas, key = { "d${it.id}" }) { d ->
                    val suyos = porDeuda[d.id].orEmpty()
                    val ultimoAbono = suyos.firstOrNull { it.tipo == Deudas.ABONO }
                    Card(Modifier.fillMaxWidth().clickable { abiertaId = d.id }) {
                        Column(Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(d.persona, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                                Text(dinero(saldo(d)), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            }
                            d.tarifa?.let { Text("${dinero(it)} por sesión", color = apagado) }
                            Text(
                                when {
                                    saldo(d) <= 0 -> "Al corriente"
                                    ultimoAbono != null -> "Último abono: ${dinero(ultimoAbono.monto)} el ${dia(ultimoAbono.fecha)}"
                                    else -> "Sin abonos todavía"
                                },
                                style = MaterialTheme.typography.bodySmall, color = apagado,
                            )
                        }
                    }
                }
                val terminadas = deudas.filter { !it.activa }
                if (terminadas.isNotEmpty()) item {
                    Text("Terminadas", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                }
                items(terminadas, key = { "t${it.id}" }) { d ->
                    Row(Modifier.fillMaxWidth().clickable { abiertaId = d.id }.padding(vertical = 4.dp)) {
                        Text(d.persona, Modifier.weight(1f))
                        Text(dinero(saldo(d)), color = apagado)
                    }
                }
            } else {
                // ---------- Detalle de una persona ----------
                val d = abierta
                val suyos = porDeuda[d.id].orEmpty()
                val debe = saldo(d)
                item {
                    Column(Modifier.padding(vertical = 8.dp)) {
                        Text("LE DEBES", style = MaterialTheme.typography.labelMedium, color = apagado)
                        Text(dinero(debe), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                        d.tarifa?.let {
                            Text(
                                "${dinero(it)} por sesión" + if (d.preguntarSabado) " · te pregunto cada sábado si fuiste" else "",
                                style = MaterialTheme.typography.bodySmall, color = apagado,
                            )
                        }
                        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { anotando = Deudas.ABONO }) { Text("Abonar") }
                            OutlinedButton(onClick = { anotando = Deudas.CARGO }) { Text(if (d.tarifa != null) "Fui a sesión" else "Sumar a la deuda") }
                        }
                        TextButton(onClick = { editando = d }) { Text("Editar") }
                    }
                }
                // Transferencias recientes que podrían ser un abono (como mucho lo que debes).
                val posibles = if (debe <= 0) emptyList() else salidas.filter {
                    it.id !in ligadas && it.id.toString() !in noSon && it.monto <= debe + 2 && it.fecha >= d.creada - 7L * 86_400_000L
                }.take(3)
                items(posibles, key = { "s${it.id}" }) { t ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text("Salió ${dinero(t.monto)} el ${dia(t.fecha)}", style = MaterialTheme.typography.titleMedium)
                            Text("${t.comercio}. ¿Fue un abono a ${d.persona}?", color = apagado)
                            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = {
                                    onAnotar(DeudaMov(deudaId = d.id, fecha = t.fecha, monto = t.monto, tipo = Deudas.ABONO, nota = "Transferencia", transaccionId = t.id))
                                }) { Text("Sí, fue abono") }
                                TextButton(onClick = { descartar(ctx, t.id); noSon = descartadas(ctx) }) { Text("No") }
                            }
                        }
                    }
                }
                if (suyos.isNotEmpty()) item {
                    Text("Historial", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                }
                items(suyos, key = { "m${it.id}" }) { m ->
                    Row(Modifier.fillMaxWidth().clickable { movAbierto = m }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(m.nota.ifBlank { if (m.tipo == Deudas.ABONO) "Abono" else "Se sumó" })
                            Text(dia(m.fecha), style = MaterialTheme.typography.bodySmall, color = apagado)
                        }
                        Text(
                            (if (m.tipo == Deudas.ABONO) "−" else "+") + dinero(m.monto),
                            fontWeight = if (m.tipo == Deudas.ABONO) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                }
            }
        }
    }

    if (nueva) DialogoDeuda(null, conocidas, onGuardar = { persona, debes, tarifa, preguntar ->
        onCrear(persona, debes, tarifa, preguntar); nueva = false
    }, onCerrar = { nueva = false })

    editando?.let { d ->
        DialogoDeuda(d, conocidas, onGuardar = { persona, _, tarifa, preguntar ->
            onEditar(d.copy(persona = persona, tarifa = tarifa, preguntarSabado = tarifa != null && preguntar)); editando = null
        }, onCerrar = { editando = null }, onTerminar = {
            onEditar(d.copy(activa = !d.activa)); editando = null
        }, onBorrar = {
            onBorrar(d); editando = null; abiertaId = null
        })
    }

    val d = abierta
    if (d != null) anotando?.let { tipo ->
        DialogoMovimiento(
            titulo = if (tipo == Deudas.ABONO) "Abono a ${d.persona}" else if (d.tarifa != null) "Sesión con ${d.persona}" else "Se suma a lo que debes",
            montoInicial = if (tipo == Deudas.CARGO) d.tarifa else null,
            conSesion = tipo == Deudas.CARGO && d.tarifa != null,
            notas = if (tipo == Deudas.ABONO) listOf("Efectivo", "Transferencia") else emptyList(),
            onGuardar = { monto, fecha, nota ->
                onAnotar(DeudaMov(deudaId = d.id, fecha = fecha, monto = monto, tipo = tipo, nota = nota ?: if (tipo == Deudas.CARGO && d.tarifa != null) "Sesión" else ""))
                anotando = null
            },
            onCerrar = { anotando = null },
        )
    }

    movAbierto?.let { m ->
        var monto by remember(m.id) { mutableStateOf("%.2f".format(m.monto)) }
        val n = leerMonto(monto)
        AlertDialog(
            onDismissRequest = { movAbierto = null },
            title = { Text("${m.nota.ifBlank { if (m.tipo == Deudas.ABONO) "Abono" else "Se sumó" }} · ${dia(m.fecha)}") },
            text = {
                OutlinedTextField(
                    monto, { monto = it }, Modifier.fillMaxWidth(), label = { Text("Monto") },
                    prefix = { Text("$") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
            },
            confirmButton = {
                Row {
                    TextButton(onClick = { onBorrarMov(m); movAbierto = null }) { Text("Borrar", color = MaterialTheme.colorScheme.error) }
                    TextButton(enabled = n != null && n != m.monto, onClick = { onEditarMov(m.copy(monto = n!!)); movAbierto = null }) { Text("Guardar") }
                }
            },
            dismissButton = { TextButton(onClick = { movAbierto = null }) { Text("Cerrar") } },
        )
    }
}

/** Alta o edición de a quién le debes. */
@Composable
private fun DialogoDeuda(
    inicial: Deuda?,
    conocidas: List<String>,
    onGuardar: (persona: String, debesAhora: Double?, tarifa: Double?, preguntar: Boolean) -> Unit,
    onCerrar: () -> Unit,
    onTerminar: (() -> Unit)? = null,
    onBorrar: (() -> Unit)? = null,
) {
    var elegidas by remember { mutableStateOf(listOfNotNull(inicial?.persona)) }
    var escrito by remember { mutableStateOf("") }
    val persona = conEscrito(elegidas, escrito, conocidas, varias = false).firstOrNull() ?: ""
    var debes by remember { mutableStateOf("") }
    var porSesion by remember { mutableStateOf(inicial?.tarifa != null) }
    var tarifa by remember { mutableStateOf(inicial?.tarifa?.let { "%.2f".format(it) } ?: "") }
    var preguntar by remember { mutableStateOf(inicial?.preguntarSabado ?: true) }
    val tarifaN = leerMonto(tarifa)
    val valido = persona.isNotBlank() && (!porSesion || tarifaN != null)
    val decimal = KeyboardOptions(keyboardType = KeyboardType.Decimal)

    AlertDialog(
        onDismissRequest = onCerrar,
        title = { Text(if (inicial == null) "Le debes a alguien" else "Editar") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                CampoPersonas(elegidas, { elegidas = it }, conocidas, varias = false, etiqueta = "A quién", onEscribiendo = { escrito = it })
                if (inicial == null) OutlinedTextField(
                    debes, { debes = it }, Modifier.fillMaxWidth(), label = { Text("Cuánto le debes ahorita") },
                    prefix = { Text("$") }, keyboardOptions = decimal,
                    supportingText = { Text("Déjalo vacío si todavía no le debes nada") },
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(porSesion, { porSesion = it })
                    Text("Se suma cada vez que voy (sesiones, clases…)")
                }
                if (porSesion) {
                    OutlinedTextField(
                        tarifa, { tarifa = it }, Modifier.fillMaxWidth(), label = { Text("Cuánto cuesta cada vez") },
                        prefix = { Text("$") }, keyboardOptions = decimal,
                        supportingText = { if (inicial != null) Text("Si sube, cámbiala aquí: lo ya anotado no cambia") },
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(preguntar, { preguntar = it })
                        Text("Preguntarme cada sábado si fui")
                    }
                }
                if (onTerminar != null && onBorrar != null && inicial != null) {
                    Row {
                        TextButton(onClick = onTerminar) { Text(if (inicial.activa) "Ya terminé de pagar" else "Volver a activar") }
                        TextButton(onClick = onBorrar) { Text("Borrar", color = MaterialTheme.colorScheme.error) }
                    }
                    Text("Borrar elimina también todo su historial.", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = valido, onClick = {
                onGuardar(persona.trim(), leerMonto(debes), if (porSesion) tarifaN else null, preguntar)
            }) { Text("Guardar") }
        },
        dismissButton = { TextButton(onClick = onCerrar) { Text("Cancelar") } },
    )
}

/** Abono o cargo: monto, día y una nota opcional. Para una sesión ofrece viernes y sábado de esta semana. */
@Composable
private fun DialogoMovimiento(
    titulo: String,
    montoInicial: Double?,
    conSesion: Boolean,
    notas: List<String>,
    onGuardar: (monto: Double, fecha: Long, nota: String?) -> Unit,
    onCerrar: () -> Unit,
) {
    val hoy = LocalDate.now()
    val dias = buildList {
        add("Hoy" to hoy)
        add("Ayer" to hoy.minusDays(1))
        if (conSesion) {
            Deudas.diaDeSesion(hoy, viernes = true).takeIf { !it.isAfter(hoy) && it != hoy && it != hoy.minusDays(1) }?.let { add("Viernes" to it) }
            Deudas.diaDeSesion(hoy, viernes = false).takeIf { !it.isAfter(hoy) && it != hoy && it != hoy.minusDays(1) }?.let { add("Sábado" to it) }
        }
    }
    var monto by remember { mutableStateOf(montoInicial?.let { "%.2f".format(it) } ?: "") }
    var elegido by remember { mutableStateOf(0) }
    var nota by remember { mutableStateOf<String?>(null) }
    val n = leerMonto(monto)

    AlertDialog(
        onDismissRequest = onCerrar,
        title = { Text(titulo) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    monto, { monto = it }, Modifier.fillMaxWidth(), label = { Text("Cuánto") },
                    prefix = { Text("$") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    dias.forEachIndexed { i, (t, _) -> FilterChip(elegido == i, { elegido = i }, label = { Text(t) }) }
                }
                if (notas.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    notas.forEach { t -> FilterChip(nota == t, { nota = if (nota == t) null else t }, label = { Text(t) }) }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = n != null, onClick = { onGuardar(n!!, Deudas.mediodia(dias[elegido].second), nota) }) { Text("Guardar") }
        },
        dismissButton = { TextButton(onClick = onCerrar) { Text("Cancelar") } },
    )
}
