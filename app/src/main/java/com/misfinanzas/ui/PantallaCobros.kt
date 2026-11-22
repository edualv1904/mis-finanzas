package com.misfinanzas.ui

import androidx.compose.foundation.clickable
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.IconButton
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.misfinanzas.Cobros
import com.misfinanzas.data.Acuerdo
import com.misfinanzas.data.Cobro
import com.misfinanzas.data.Transaccion
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

private val fmtCorto = DateTimeFormatter.ofPattern("d MMM", Locale("es", "MX"))
private fun corto(millis: Long) = diaDe(millis).format(fmtCorto).replace(".", "")

/** Cuál mensualidad es: "9 de 18" en un acuerdo a meses, o el mes ("Noviembre 2026") si es cada mes. */
private fun etiqueta(c: Cobro, a: Acuerdo?) =
    if (a?.meses != null && a.meses > 1) "${c.numero} de ${a.meses}" else textoMes(YearMonth.parse(c.mes))

/** Los acuerdos se agrupan por concepto ("Spotify", "Compu"): uno por cosa en la página principal. */
private fun grupoDe(a: Acuerdo) = a.concepto.trim().lowercase()

private const val DIA_MS = 86_400_000L

/** El depósito que parece ser el pago de [c] (mismo monto, llegó cerca o después de la fecha). */
private fun sugerencia(c: Cobro, ingresos: List<Transaccion>, usados: Set<Long>) =
    ingresos.firstOrNull { it.id !in usados && Cobros.coincide(it.monto, c.monto) && it.fecha >= c.vence - 25L * DIA_MS }

/** "$39.83 al mes · día 4", "$1,000.00 al mes · 9 de 18 · día 15", "$500.00 · una vez · día 3". */
private fun plan(a: Acuerdo, pagados: Int) = when (a.meses) {
    null -> "${dinero(a.monto)} al mes · día ${a.dia}"
    1 -> "${dinero(a.monto)} · una vez · día ${a.dia}"
    else -> "${dinero(a.monto)} al mes · $pagados de ${a.meses} · día ${a.dia}"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PantallaCobros(
    acuerdos: List<Acuerdo>,
    cobros: List<Cobro>,
    ingresos: List<Transaccion>,
    onCrear: (Datos) -> Unit,
    onEditar: (Acuerdo) -> Unit,
    onMarcar: (Cobro, pagado: Long?, abonoId: Long?) -> Unit,
    onCambiarMonto: (Cobro, Double) -> Unit,
    onTerminar: (Acuerdo) -> Unit,
    onBorrar: (Acuerdo) -> Unit,
    botonMenu: @Composable () -> Unit,
) {
    var grupoAbierto by rememberSaveable { mutableStateOf<String?>(null) }
    var nuevo by remember { mutableStateOf<Acuerdo?>(null) }      // plantilla al agregar a alguien a un grupo
    var nuevoVacio by remember { mutableStateOf(false) }
    var viendo by remember { mutableStateOf<Acuerdo?>(null) }
    var editando by remember { mutableStateOf<Acuerdo?>(null) }
    var editandoGrupo by remember { mutableStateOf<List<Acuerdo>?>(null) }
    var cobroAbierto by remember { mutableStateOf<Cobro?>(null) }
    val porId = acuerdos.associateBy { it.id }
    val ahora = System.currentTimeMillis()
    val pendientes = cobros.filter { it.pagado == null }
    val usados = cobros.mapNotNull { it.abonoId }.toSet()
    // Depósitos que no cuadran con un solo pendiente pero sí con varios del mismo concepto ($80 = dos de Spotify).
    val juntos = ingresos.filter { it.id !in usados }.mapNotNull { t ->
        val vigentes = pendientes.filter { t.fecha >= it.vence - 25L * DIA_MS }
        if (vigentes.any { Cobros.coincide(t.monto, it.monto) }) null
        else Cobros.gruposParaDeposito(t.monto, vigentes) { porId[it.acuerdoId]?.concepto }.firstOrNull()?.let { t to it }
    }
    val grupos = acuerdos.groupBy(::grupoDe)
    val enGrupo = { c: Cobro, g: String -> porId[c.acuerdoId]?.let(::grupoDe) == g }
    val alerta = MaterialTheme.colorScheme.error
    val apagado = MaterialTheme.colorScheme.onSurfaceVariant

    // El grupo abierto desapareció (lo borraste o le cambiaste el nombre): vuelve a la lista.
    val abierto = grupoAbierto?.takeIf { it in grupos }
    BackHandler(enabled = abierto != null) { grupoAbierto = null }
    // Cada vista (la lista y cada concepto) empieza desde arriba.
    val estadoLista = remember(abierto) { LazyListState() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(abierto?.let { grupos.getValue(it).first().concepto } ?: "Me deben") },
                navigationIcon = {
                    if (abierto != null) IconButton(onClick = { grupoAbierto = null }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver")
                    } else botonMenu()
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { if (abierto != null) nuevo = grupos.getValue(abierto).first() else nuevoVacio = true }) {
                Icon(Icons.Default.Add, if (abierto != null) "Agregar a alguien" else "Nuevo cobro")
            }
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            state = estadoLista,
            contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (abierto == null) {
                // ---------- Página principal: una tarjeta por concepto ----------
                item {
                    Column(Modifier.padding(vertical = 8.dp)) {
                        Text("TE DEBEN", style = MaterialTheme.typography.labelMedium, color = apagado)
                        Text(dinero(pendientes.sumOf { it.monto }), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                        val atrasados = pendientes.count { Cobros.atrasado(it, ahora) }
                        if (atrasados > 0) Text("$atrasados ${if (atrasados == 1) "pago atrasado" else "pagos atrasados"}", color = alerta)
                        Text(
                            "Es solo para cobrar: no cambia tus movimientos ni lo que llevas gastado.",
                            style = MaterialTheme.typography.bodySmall, color = apagado,
                        )
                    }
                }
                if (acuerdos.isEmpty()) item {
                    Text(
                        "Toca + para agregar a alguien que te paga cada mes: su parte de Spotify, las mensualidades " +
                            "de una compra que hiciste por él, etc.",
                        Modifier.padding(vertical = 16.dp),
                    )
                }
                val (activos, terminados) = grupos.entries.partition { (_, l) -> l.any { it.activo } }
                items(activos.sortedBy { (_, l) -> l.first().concepto.lowercase() }, key = { "g${it.key}" }) { (g, lista) ->
                    val suyos = pendientes.filter { enGrupo(it, g) }
                    val atrasados = suyos.count { Cobros.atrasado(it, ahora) }
                    val deposito = suyos.any { sugerencia(it, ingresos, usados) != null } || juntos.any { (_, gr) -> enGrupo(gr.first(), g) }
                    val muestra = lista.first()
                    Card(Modifier.fillMaxWidth().clickable { grupoAbierto = g }) {
                        Column(Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(muestra.concepto, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                                Text(dinero(suyos.sumOf { it.monto }), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            }
                            Text(lista.filter { it.activo }.joinToString(", ") { it.persona }, color = apagado)
                            Text(
                                if (lista.size > 1 && lista.all { it.monto == muestra.monto }) "${dinero(muestra.monto)} cada quien · día ${muestra.dia}"
                                else plan(muestra, cobros.count { it.acuerdoId == muestra.id && it.pagado != null }),
                                style = MaterialTheme.typography.bodySmall, color = apagado,
                            )
                            when {
                                atrasados > 0 -> Text("$atrasados ${if (atrasados == 1) "pago atrasado" else "pagos atrasados"}", color = alerta, style = MaterialTheme.typography.bodySmall)
                                suyos.isEmpty() -> Text("Al corriente", style = MaterialTheme.typography.bodySmall, color = apagado)
                            }
                            if (deposito) Text("Llegó un depósito que puede ser de aquí", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                if (terminados.isNotEmpty()) item { Titulo("Terminados") }
                items(terminados, key = { "t${it.key}" }) { (g, lista) ->
                    Row(Modifier.fillMaxWidth().clickable { grupoAbierto = g }.padding(vertical = 4.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(lista.first().concepto)
                            Text(lista.joinToString(", ") { it.persona }, style = MaterialTheme.typography.bodySmall, color = apagado)
                        }
                        val pagado = cobros.filter { c -> lista.any { it.id == c.acuerdoId } && c.pagado != null && c.abonoId != Cobros.PERDONADO }.sumOf { it.monto }
                        Text(dinero(pagado), color = apagado)
                    }
                }
            } else {
                // ---------- Detalle de un concepto ----------
                val lista = grupos.getValue(abierto)
                val suyos = pendientes.filter { enGrupo(it, abierto) }
                item {
                    Column(Modifier.padding(vertical = 8.dp)) {
                        Text("TE DEBEN", style = MaterialTheme.typography.labelMedium, color = apagado)
                        Text(dinero(suyos.sumOf { it.monto }), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                        val atrasados = suyos.count { Cobros.atrasado(it, ahora) }
                        if (atrasados > 0) Text("$atrasados ${if (atrasados == 1) "pago atrasado" else "pagos atrasados"}", color = alerta)
                        OutlinedButton(onClick = { editandoGrupo = lista }, Modifier.padding(top = 8.dp)) {
                            Text(if (lista.size > 1) "Cambiar monto, día o tipo a todos" else "Cambiar monto, día o tipo")
                        }
                    }
                }
                items(juntos.filter { (_, gr) -> enGrupo(gr.first(), abierto) }, key = { "j${it.first.id}" }) { (t, grupo) ->
                    TarjetaVarios(t, grupo, porId, onMarcar)
                }
                if (suyos.isNotEmpty()) item { Titulo("Pendientes") }
                items(suyos, key = { "p${it.id}" }) { c ->
                    TarjetaPendiente(c, porId[c.acuerdoId], sugerencia(c, ingresos, usados), ahora, onMarcar) { cobroAbierto = c }
                }
                item { Titulo(if (lista.size > 1) "Personas" else "Persona") }
                items(lista.sortedBy { it.persona.lowercase() }, key = { "a${it.id}" }) { a ->
                    val deEl = cobros.filter { it.acuerdoId == a.id }
                    val debe = deEl.filter { it.pagado == null }.sumOf { it.monto }
                    Card(
                        Modifier.fillMaxWidth().clickable { viendo = a },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Row {
                                Text(a.persona, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                                Text(if (debe > 0) "Debe ${dinero(debe)}" else "Al corriente", color = if (deEl.any { Cobros.atrasado(it, ahora) }) alerta else apagado)
                            }
                            Text(
                                plan(a, deEl.count { it.pagado != null }) + (if (!a.activo) " · terminado" else ""),
                                style = MaterialTheme.typography.bodySmall, color = apagado,
                            )
                            Text("Toca para ver sus pagos o editar", style = MaterialTheme.typography.bodySmall, color = apagado)
                        }
                    }
                }
                val pagados = cobros.filter { it.pagado != null && enGrupo(it, abierto) }.sortedByDescending { it.pagado }
                if (pagados.isNotEmpty()) item { Titulo("Pagos recibidos") }
                items(pagados, key = { "h${it.id}" }) { c ->
                    val a = porId[c.acuerdoId]
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${a?.persona ?: "—"} · ${etiqueta(c, a)}")
                            Text(
                                if (c.abonoId == Cobros.PERDONADO) "ya no te lo debe" else "pagado el ${corto(c.pagado!!)}",
                                style = MaterialTheme.typography.bodySmall, color = apagado,
                            )
                        }
                        Text(dinero(c.monto))
                        TextButton(onClick = { onMarcar(c, null, null) }) { Text("Deshacer") }
                    }
                }
            }
        }
    }

    if (nuevoVacio) DialogoAcuerdo(null, onGuardar = { onCrear(it); nuevoVacio = false }, onCerrar = { nuevoVacio = false })
    nuevo?.let { p ->
        DialogoAcuerdo(null, plantilla = p, onGuardar = { onCrear(it); nuevo = null }, onCerrar = { nuevo = null })
    }

    editando?.let { a ->
        DialogoAcuerdo(a, onGuardar = { d ->
            onEditar(a.copy(persona = d.personas.first(), concepto = d.concepto, monto = d.monto, dia = d.dia, meses = d.meses))
            if (grupoDe(a) == grupoAbierto) grupoAbierto = d.concepto.trim().lowercase()
            editando = null
        }, onCerrar = { editando = null })
    }

    editandoGrupo?.let { lista ->
        DialogoAcuerdo(lista.first(), grupo = lista, onGuardar = { d ->
            lista.forEach { onEditar(it.copy(concepto = d.concepto, monto = d.monto, dia = d.dia, meses = d.meses)) }
            grupoAbierto = d.concepto.trim().lowercase()
            editandoGrupo = null
        }, onCerrar = { editandoGrupo = null })
    }

    cobroAbierto?.let { c ->
        val a = porId[c.acuerdoId]
        var nuevoMonto by remember(c.id) { mutableStateOf("%.2f".format(c.monto)) }
        val m = nuevoMonto.replace(",", "").toDoubleOrNull()
        AlertDialog(
            onDismissRequest = { cobroAbierto = null },
            title = { Text("${a?.persona ?: ""} · ${etiqueta(c, a)}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(a?.concepto ?: "")
                    OutlinedTextField(
                        nuevoMonto, { nuevoMonto = it }, Modifier.fillMaxWidth(), label = { Text("Lo que te debe de este mes") },
                        prefix = { Text("$") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        supportingText = { Text("Si te pagó una parte, deja aquí lo que falta") },
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { onMarcar(c, System.currentTimeMillis(), null); cobroAbierto = null }) { Text("Ya me pagó") }
                        TextButton(onClick = { onMarcar(c, System.currentTimeMillis(), Cobros.PERDONADO); cobroAbierto = null }) { Text("Ya no me lo debe") }
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = m != null && m > 0 && m != c.monto, onClick = { onCambiarMonto(c, m!!); cobroAbierto = null }) { Text("Guardar monto") }
            },
            dismissButton = { TextButton(onClick = { cobroAbierto = null }) { Text("Cerrar") } },
        )
    }

    viendo?.let { a ->
        AlertDialog(
            onDismissRequest = { viendo = null },
            title = { Text("${a.persona} · ${a.concepto}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Historial(a, cobros.filter { it.acuerdoId == a.id })
                    OutlinedButton(onClick = { editando = a; viendo = null }, Modifier.fillMaxWidth()) { Text("Editar") }
                    Text(
                        "Terminar: ya no se generan cobros nuevos y se quitan los pendientes; lo pagado se queda en el historial.\n\n" +
                            "Borrar: elimina el acuerdo y todo su historial.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                Row {
                    TextButton(onClick = { onBorrar(a); viendo = null }) { Text("Borrar", color = MaterialTheme.colorScheme.error) }
                    if (a.activo) TextButton(onClick = { onTerminar(a); viendo = null }) { Text("Terminar") }
                }
            },
            dismissButton = { TextButton(onClick = { viendo = null }) { Text("Cerrar") } },
        )
    }
}

/** Un pago pendiente, con el depósito que parece ser suyo si llegó uno. */
@Composable
private fun TarjetaPendiente(
    c: Cobro,
    a: Acuerdo?,
    sugerido: Transaccion?,
    ahora: Long,
    onMarcar: (Cobro, pagado: Long?, abonoId: Long?) -> Unit,
    onAbrir: () -> Unit,
) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onAbrir)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(a?.persona ?: "—", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Text(dinero(c.monto), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            Text(etiqueta(c, a), color = MaterialTheme.colorScheme.onSurfaceVariant)
            val dias = ChronoUnit.DAYS.between(diaDe(c.vence), LocalDate.now())
            Text(
                when {
                    Cobros.atrasado(c, ahora) -> "Atrasado $dias días (era el ${corto(c.vence)})"
                    dias >= 0 -> "Tocaba el ${corto(c.vence)}"
                    else -> "Vence el ${corto(c.vence)}"
                },
                color = if (Cobros.atrasado(c, ahora)) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            sugerido?.let { t ->
                Spacer(Modifier.padding(top = 8.dp))
                Text("Llegó ${dinero(t.monto)} el ${corto(t.fecha)} (${t.comercio}). ¿Es este pago?", style = MaterialTheme.typography.bodyMedium)
            }
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                sugerido?.let { t -> OutlinedButton(onClick = { onMarcar(c, t.fecha, t.id) }) { Text("Sí, es este") } }
                OutlinedButton(onClick = { onMarcar(c, System.currentTimeMillis(), null) }) { Text("Ya me pagó") }
            }
        }
    }
}

/** Llegó un depósito que paga a varios a la vez: eliges quiénes y se marcan todos con ese depósito. */
@Composable
private fun TarjetaVarios(
    t: Transaccion,
    grupo: List<Cobro>,
    porId: Map<Long, Acuerdo>,
    onMarcar: (Cobro, pagado: Long?, abonoId: Long?) -> Unit,
) {
    var elegidos by remember(t.id) { mutableStateOf(setOf<Long>()) }
    val seleccion = grupo.filter { it.id in elegidos }
    val cuadra = Cobros.cubreVarios(t.monto, seleccion)
    val primero = porId[grupo.first().acuerdoId]
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Llegó ${dinero(t.monto)} el ${corto(t.fecha)}", style = MaterialTheme.typography.titleMedium)
            Text(
                "${t.comercio}. Puede ser de varios a la vez (${primero?.concepto ?: ""}, ${etiqueta(grupo.first(), primero)}). ¿De quiénes?",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            grupo.forEach { c ->
                Row(
                    Modifier.fillMaxWidth().clickable { elegidos = if (c.id in elegidos) elegidos - c.id else elegidos + c.id },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = c.id in elegidos, onCheckedChange = null)
                    Spacer(Modifier.width(8.dp))
                    Text(porId[c.acuerdoId]?.persona ?: "—", Modifier.weight(1f))
                    Text(dinero(c.monto))
                }
            }
            if (seleccion.size >= 2 && !cuadra) {
                Text(
                    "Suman ${dinero(seleccion.sumOf { it.monto })}; el depósito fue de ${dinero(t.monto)}.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                )
            }
            OutlinedButton(
                enabled = cuadra,
                onClick = { seleccion.forEach { onMarcar(it, t.fecha, t.id) } },
                modifier = Modifier.padding(top = 8.dp),
            ) { Text("Sí, me pagaron ellos") }
        }
    }
}

/** Todos los pagos de un acuerdo, del más nuevo al más viejo. */
@Composable
private fun Historial(a: Acuerdo, suyos: List<Cobro>) {
    if (suyos.isEmpty()) return
    val ahora = System.currentTimeMillis()
    val apagado = MaterialTheme.colorScheme.onSurfaceVariant
    val pagado = suyos.filter { it.pagado != null && it.abonoId != Cobros.PERDONADO }.sumOf { it.monto }
    Text("Te ha pagado ${dinero(pagado)}", style = MaterialTheme.typography.titleSmall)
    Column(
        Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        suyos.sortedByDescending { it.mes }.forEach { c ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    val mes = textoMes(YearMonth.parse(c.mes))
                    Text(if (a.meses != null && a.meses > 1) "${c.numero} de ${a.meses} · $mes" else mes)
                    Text(
                        when {
                            c.abonoId == Cobros.PERDONADO -> "Ya no te lo debe"
                            c.pagado != null -> "Pagado el ${corto(c.pagado)}" + if (c.abonoId != null) " (depósito)" else ""
                            Cobros.atrasado(c, ahora) -> "Atrasado"
                            else -> "Pendiente"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (Cobros.atrasado(c, ahora)) MaterialTheme.colorScheme.error else apagado,
                    )
                }
                Text(dinero(c.monto))
            }
        }
    }
}

@Composable
private fun Titulo(t: String) {
    Text(t, Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
}

/** Lo que capturas al dar de alta un cobro. */
data class Datos(
    val personas: List<String>,
    val concepto: String,
    val monto: Double,
    val dia: Int,
    /** null = cada mes, 1 = una vez, N = N meses. */
    val meses: Int?,
    val actual: Int,
    val esteMesPagado: Boolean,
)

private enum class Tipo(val texto: String) { UNA_VEZ("Una vez"), CADA_MES("Cada mes"), MESES("Varios meses") }

@Composable
/**
 * Alta ([inicial] null; con [plantilla] llega lleno con lo de ese grupo, solo falta quién), edición de una
 * persona ([inicial]) o de todo un grupo ([grupo]: cambia concepto, monto, día y tipo a todos).
 */
private fun DialogoAcuerdo(
    inicial: Acuerdo?,
    onGuardar: (Datos) -> Unit,
    onCerrar: () -> Unit,
    plantilla: Acuerdo? = null,
    grupo: List<Acuerdo>? = null,
) {
    val editar = inicial != null
    val base = inicial ?: plantilla
    var personas by remember { mutableStateOf(if (grupo == null) inicial?.persona ?: "" else "") }
    var concepto by remember { mutableStateOf(base?.concepto ?: "") }
    var monto by remember { mutableStateOf(base?.monto?.let { "%.2f".format(it) } ?: "") }
    var dia by remember { mutableStateOf((base?.dia ?: LocalDate.now().dayOfMonth).toString()) }
    var tipo by remember {
        mutableStateOf(when (base?.meses) { null -> if (base == null) Tipo.UNA_VEZ else Tipo.CADA_MES; 1 -> Tipo.UNA_VEZ; else -> Tipo.MESES })
    }
    var total by remember { mutableStateOf(base?.meses?.takeIf { it > 1 }?.toString() ?: "") }
    var actual by remember { mutableStateOf("1") }
    var esteMesPagado by remember { mutableStateOf(false) }

    val lista = personas.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    val montoN = monto.replace(",", "").replace("$", "").trim().toDoubleOrNull()
    val diaN = dia.toIntOrNull()?.takeIf { it in 1..31 }
    val totalN = total.toIntOrNull()?.takeIf { it >= 2 }
    val actualN = if (tipo == Tipo.MESES && !editar) actual.toIntOrNull()?.takeIf { totalN != null && it in 1..totalN } else 1
    val valido = (grupo != null || (lista.isNotEmpty() && (!editar || lista.size == 1))) && concepto.isNotBlank() && montoN != null && montoN > 0 &&
        diaN != null && (tipo != Tipo.MESES || (totalN != null && actualN != null))
    val numero = KeyboardOptions(keyboardType = KeyboardType.Number)

    AlertDialog(
        onDismissRequest = onCerrar,
        title = { Text(when { grupo != null -> "Cambiar a todos"; editar -> "Editar"; plantilla != null -> "Agregar a ${plantilla.concepto}"; else -> "Te deben dinero" }) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Tipo.entries.forEach { t -> FilterChip(tipo == t, { tipo = t }, label = { Text(t.texto) }) }
                }
                if (grupo != null) Text("Se aplica a: ${grupo.joinToString(", ") { it.persona }}", style = MaterialTheme.typography.bodySmall)
                else OutlinedTextField(
                    personas, { personas = it }, Modifier.fillMaxWidth(), label = { Text("Quién") },
                    supportingText = { if (!editar) Text("Varias personas con lo mismo: sepáralas con comas") },
                )
                OutlinedTextField(
                    concepto, { concepto = it }, Modifier.fillMaxWidth(), label = { Text("De qué") },
                    placeholder = { Text(if (tipo == Tipo.UNA_VEZ) "Préstamo, la cena…" else "Spotify, compra de…") },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        monto, { monto = it }, Modifier.weight(1f), label = { Text(if (tipo == Tipo.UNA_VEZ) "Cuánto" else "Cada mes") },
                        prefix = { Text("$") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                    OutlinedTextField(
                        dia, { dia = it }, Modifier.width(110.dp), label = { Text(if (tipo == Tipo.UNA_VEZ) "Paga el día" else "Día") },
                        keyboardOptions = numero,
                    )
                }
                if (tipo == Tipo.UNA_VEZ && diaN != null && !editar) {
                    val mes = Cobros.mesDeUnaVez(LocalDate.now(), diaN)
                    Text("Te recuerdo si no te paga para el $diaN de ${textoMes(mes).substringBefore(" ").lowercase()}.", style = MaterialTheme.typography.bodySmall)
                }
                if (tipo == Tipo.MESES) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(total, { total = it }, Modifier.weight(1f), label = { Text("Total de meses") }, keyboardOptions = numero)
                        if (!editar) OutlinedTextField(actual, { actual = it }, Modifier.weight(1f), label = { Text("Este mes va en") }, keyboardOptions = numero)
                    }
                    if (!editar && totalN != null && actualN != null && actualN > 1) {
                        Text("Las ${actualN - 1} anteriores se marcan como pagadas.", style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (!editar && tipo != Tipo.UNA_VEZ) Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(esteMesPagado, { esteMesPagado = it })
                    Text("Este mes ya me pagó")
                }
                if (editar) Text("Los meses ya pagados no cambian; lo pendiente toma el monto y día nuevos.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(enabled = valido, onClick = {
                val meses = when (tipo) { Tipo.UNA_VEZ -> 1; Tipo.CADA_MES -> null; Tipo.MESES -> totalN }
                onGuardar(Datos(lista, concepto.trim(), montoN!!, diaN!!, meses, actualN ?: 1, esteMesPagado))
            }) { Text("Guardar") }
        },
        dismissButton = { TextButton(onClick = onCerrar) { Text("Cancelar") } },
    )
}
