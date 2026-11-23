package com.misfinanzas.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.misfinanzas.Tickets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.misfinanzas.Grupos
import com.misfinanzas.Grupos.YO
import com.misfinanzas.Personas
import com.misfinanzas.data.Grupo
import com.misfinanzas.data.GrupoGasto
import com.misfinanzas.data.GrupoMiembro
import com.misfinanzas.data.GrupoParte
import com.misfinanzas.data.Transaccion
import kotlin.math.abs

/** Todo lo de los grupos, ya agrupado por grupo. */
class DatosGrupos(
    val grupos: List<Grupo>,
    miembros: List<GrupoMiembro>,
    gastos: List<GrupoGasto>,
    partes: List<GrupoParte>,
) {
    val miembrosDe: Map<Long, List<String>> = miembros.groupBy { it.grupoId }.mapValues { e -> e.value.map { it.persona }.sortedBy { it.lowercase() } }
    val gastosDe: Map<Long, List<GrupoGasto>> = gastos.groupBy { it.grupoId }
    val partesDe: Map<Long, List<GrupoParte>> = partes.groupBy { it.gastoId }
    private val partesTodas = partes

    fun partesDelGrupo(id: Long): List<GrupoParte> {
        val ids = gastosDe[id].orEmpty().map { it.id }.toSet()
        return partesTodas.filter { it.gastoId in ids }
    }

    fun saldos(id: Long) = Grupos.saldos(gastosDe[id].orEmpty(), partesDelGrupo(id))
    fun transferencias(id: Long) = Grupos.simplificar(saldos(id))

    /** Para el perfil de cada persona: en qué grupos está y cómo queda contigo. */
    fun porPersona(): Map<String, List<Personas.EnGrupo>> {
        val r = HashMap<String, MutableList<Personas.EnGrupo>>()
        for (g in grupos) {
            val t = transferencias(g.id)
            for (m in miembrosDe[g.id].orEmpty()) {
                r.getOrPut(m) { ArrayList() } += Personas.EnGrupo(g.id, g.nombre, Grupos.conmigo(m, t), g.activo)
            }
        }
        return r
    }

    /** El gasto de grupo hecho con este movimiento de tu tarjeta, si lo compartiste. */
    fun deTransaccion(id: Long): GrupoGasto? = gastosDe.values.flatten().firstOrNull { it.transaccionId == id }
}

private fun leer(t: String) = t.replace(",", "").replace("$", "").trim().toDoubleOrNull()
private fun quien(p: String) = if (p == YO) "Tú" else p

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PantallaGrupos(
    datos: DatosGrupos,
    conocidas: List<String>,
    abierto: Long?,
    onAbrir: (Long?) -> Unit,
    onCrear: (nombre: String, personas: List<String>) -> Unit,
    onEditar: (Grupo, personas: List<String>) -> Unit,
    onBorrar: (Grupo) -> Unit,
    onGuardarGasto: (GrupoGasto, Map<String, Double>, List<File>?) -> Unit,
    onBorrarGasto: (GrupoGasto) -> Unit,
    botonMenu: @Composable () -> Unit,
    /** Cambia cada vez que se guardan o borran fotos de tickets. */
    versionTickets: Int = 0,
) {
    val ctx = LocalContext.current
    val grupo = datos.grupos.firstOrNull { it.id == abierto }
    val conFoto = remember(grupo?.id, versionTickets, datos) { grupo?.let { Tickets.cuentas(ctx, it.id) } ?: emptyMap() }
    var nuevo by remember { mutableStateOf(false) }
    var editando by remember { mutableStateOf<Grupo?>(null) }
    var gastoAbierto by remember { mutableStateOf<GrupoGasto?>(null) }
    var gastoNuevo by remember { mutableStateOf(false) }
    var pagando by remember { mutableStateOf<Grupos.Transferencia?>(null) }
    var personaViendo by remember { mutableStateOf<String?>(null) }
    val apagado = MaterialTheme.colorScheme.onSurfaceVariant

    BackHandler(enabled = grupo != null) { onAbrir(null) }
    val estadoLista = remember(grupo?.id) { LazyListState() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(grupo?.nombre ?: "Split", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    if (grupo != null) IconButton(onClick = { onAbrir(null) }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver")
                    } else botonMenu()
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { if (grupo != null) gastoNuevo = true else nuevo = true }) {
                Icon(Icons.Default.Add, if (grupo != null) "Agregar gasto" else "Nuevo split")
            }
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            state = estadoLista,
            contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (grupo == null) {
                // ---------- Lista de grupos ----------
                item {
                    Text(
                        "Viajes y cuentas compartidas: anota quién pagó qué y la app te dice quién le paga a quién. " +
                            "De lo que pagas tú con tarjeta, en \"Gastado\" cuenta solo tu parte.",
                        Modifier.padding(vertical = 8.dp),
                        style = MaterialTheme.typography.bodySmall, color = apagado,
                    )
                }
                if (datos.grupos.isEmpty()) item {
                    Text("Toca + para crear el primero (un viaje, la casa, una cena…). Cada split guarda sus tickets en su propia carpeta de Drive.", Modifier.padding(vertical = 16.dp))
                }
                items(datos.grupos.filter { it.activo }, key = { "g${it.id}" }) { g -> TarjetaGrupo(g, datos) { onAbrir(g.id) } }
                val terminados = datos.grupos.filter { !it.activo }
                if (terminados.isNotEmpty()) item {
                    Text("Terminados", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                }
                items(terminados, key = { "t${it.id}" }) { g ->
                    Row(Modifier.fillMaxWidth().clickable { onAbrir(g.id) }.padding(vertical = 6.dp)) {
                        Text(g.nombre, Modifier.weight(1f))
                        Text("Tu gasto ${dinero(Grupos.miGasto(datos.gastosDe[g.id].orEmpty(), datos.partesDelGrupo(g.id)))}", color = apagado)
                    }
                }
            } else {
                // ---------- Detalle de un grupo ----------
                val g = grupo
                val gastos = datos.gastosDe[g.id].orEmpty()
                val mio = datos.saldos(g.id)[YO] ?: 0.0
                val transf = datos.transferencias(g.id)
                val total = gastos.filter { it.tipo == Grupos.GASTO }.sumOf { it.monto }
                item {
                    Column(Modifier.padding(vertical = 8.dp)) {
                        Text(
                            when {
                                mio > 0.005 -> "TE DEBEN"
                                mio < -0.005 -> "DEBES"
                                else -> "ESTÁS A MANO"
                            },
                            style = MaterialTheme.typography.labelMedium, color = apagado,
                        )
                        Text(dinero(abs(mio)), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                        Text(
                            "Gastaron ${dinero(total)} entre todos · tu parte ${dinero(Grupos.miGasto(gastos, datos.partesDelGrupo(g.id)))}",
                            style = MaterialTheme.typography.bodySmall, color = apagado,
                        )
                        Text(
                            "Con: " + datos.miembrosDe[g.id].orEmpty().joinToString(", ").ifEmpty { "nadie más todavía" },
                            style = MaterialTheme.typography.bodySmall, color = apagado,
                        )
                        Row(Modifier.padding(top = 4.dp)) {
                            TextButton(onClick = { editando = g }, contentPadding = PaddingValues(0.dp)) { Text("Editar split") }
                            Spacer16()
                            TextButton(onClick = {
                                val texto = com.misfinanzas.ResumenSplit.texto(g, datos, markdown = false).trim()
                                ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, texto), "Compartir cuentas"))
                            }, contentPadding = PaddingValues(0.dp)) { Text("Compartir cuentas") }
                        }
                    }
                }
                if (transf.isNotEmpty()) item { TituloSeccion("Para quedar a mano") }
                items(transf, key = { "q${it.de}>${it.a}" }) { t ->
                    Card(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    when {
                                        t.a == YO -> "${t.de} te paga"
                                        t.de == YO -> "Le pagas a ${t.a}"
                                        else -> "${t.de} le paga a ${t.a}"
                                    },
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text(dinero(t.monto), fontWeight = FontWeight.Bold)
                            }
                            OutlinedButton(onClick = { pagando = t }) { Text(if (t.de == YO) "Ya le pagué" else "Ya pagó") }
                        }
                    }
                }
                val porPersona = cuentas(g, datos)
                if (porPersona.any { it.gasto > 0 || it.pago > 0 }) {
                    item { TituloSeccion("Cada quien") }
                    item { GraficaCadaQuien(porPersona) { personaViendo = it } }
                }
                if (gastos.isNotEmpty()) item { TituloSeccion("Gastos") }
                items(gastos, key = { "x${it.id}" }) { x ->
                    val partes = datos.partesDe[x.id].orEmpty()
                    val miParte = partes.firstOrNull { it.persona == YO }?.monto ?: 0.0
                    Row(Modifier.fillMaxWidth().clickable { gastoAbierto = x }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            if (x.tipo == Grupos.PAGO) {
                                Text("${quien(x.pago)} le pagó a ${quien(partes.firstOrNull()?.persona ?: "")}")
                                Text(textoDia(diaDe(x.fecha)), style = MaterialTheme.typography.bodySmall, color = apagado)
                            } else {
                                Text(x.concepto, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    "${textoDia(diaDe(x.fecha))} · ${if (x.pago == YO) "pagaste tú" else "pagó ${x.pago}"}" +
                                        (if (x.transaccionId != null) " con tarjeta" else "") +
                                        (conFoto[x.id]?.let { n -> " · $n ${if (n == 1) "foto" else "fotos"}" } ?: ""),
                                    style = MaterialTheme.typography.bodySmall, color = apagado,
                                )
                            }
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(dinero(x.monto), fontWeight = if (x.tipo == Grupos.GASTO) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (x.tipo == Grupos.PAGO) apagado else MaterialTheme.colorScheme.onSurface)
                            if (x.tipo == Grupos.GASTO) Text(
                                if (miParte > 0) "tu parte ${dinero(miParte)}" else "no te toca",
                                style = MaterialTheme.typography.bodySmall, color = apagado,
                            )
                        }
                    }
                }
            }
        }
    }

    if (nuevo) DialogoGrupo(null, emptyList(), conocidas, onGuardar = { n, p -> onCrear(n, p); nuevo = false }, onCerrar = { nuevo = false })

    editando?.let { g ->
        DialogoGrupo(
            g, datos.miembrosDe[g.id].orEmpty(), conocidas,
            onGuardar = { n, p -> onEditar(g.copy(nombre = n), p); editando = null },
            onCerrar = { editando = null },
            onTerminar = { onEditar(g.copy(activo = !g.activo), datos.miembrosDe[g.id].orEmpty()); editando = null },
            onBorrar = { onBorrar(g); editando = null; onAbrir(null) },
            usados = (datos.gastosDe[g.id].orEmpty().map { it.pago } + datos.partesDelGrupo(g.id).map { it.persona }).toSet(),
        )
    }

    val g = grupo
    if (g != null && gastoNuevo) DialogoGastoGrupo(
        grupos = listOf(g), datos = datos, inicial = null, transaccion = null,
        onGuardar = { x, p, f -> onGuardarGasto(x, p, f); gastoNuevo = false },
        onCerrar = { gastoNuevo = false },
    )
    gastoAbierto?.let { x ->
        if (x.tipo == Grupos.PAGO) {
            val a = datos.partesDe[x.id].orEmpty().firstOrNull()?.persona ?: ""
            DialogoPago(Grupos.Transferencia(x.pago, a, x.monto), x, onGuardar = { onGuardarGasto(it, mapOf(a to it.monto), null); gastoAbierto = null },
                onBorrar = { onBorrarGasto(x); gastoAbierto = null }, onCerrar = { gastoAbierto = null })
        } else DialogoGastoGrupo(
            grupos = datos.grupos.filter { it.id == x.grupoId }, datos = datos, inicial = x, transaccion = null,
            onGuardar = { y, p, f -> onGuardarGasto(y, p, f); gastoAbierto = null },
            onBorrar = { onBorrarGasto(x); gastoAbierto = null },
            onCerrar = { gastoAbierto = null },
        )
    }
    if (g != null) personaViendo?.let { p -> DialogoPersonaSplit(p, g, datos) { personaViendo = null } }
    if (g != null) pagando?.let { t ->
        DialogoPago(t, null, onGuardar = { x ->
            onGuardarGasto(x.copy(grupoId = g.id), mapOf(t.a to x.monto), null); pagando = null
        }, onCerrar = { pagando = null })
    }
}

@Composable
private fun Spacer16() = androidx.compose.foundation.layout.Spacer(Modifier.width(16.dp))

@Composable
private fun TituloSeccion(texto: String) {
    Text(texto, Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun TarjetaGrupo(g: Grupo, datos: DatosGrupos, onClick: () -> Unit) {
    val apagado = MaterialTheme.colorScheme.onSurfaceVariant
    val mio = datos.saldos(g.id)[YO] ?: 0.0
    val gastos = datos.gastosDe[g.id].orEmpty()
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(g.nombre, style = MaterialTheme.typography.titleMedium)
                Text(
                    datos.miembrosDe[g.id].orEmpty().joinToString(", ").ifEmpty { "Solo tú" },
                    style = MaterialTheme.typography.bodySmall, color = apagado, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${gastos.count { it.tipo == Grupos.GASTO }} gastos · tu parte ${dinero(Grupos.miGasto(gastos, datos.partesDelGrupo(g.id)))}",
                    style = MaterialTheme.typography.bodySmall, color = apagado,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(if (abs(mio) > 0.005) dinero(abs(mio)) else "—", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    when {
                        mio > 0.005 -> "te deben"
                        mio < -0.005 -> "debes"
                        else -> "a mano"
                    },
                    style = MaterialTheme.typography.bodySmall, color = apagado,
                )
            }
        }
    }
}

/** Alta o edición de un grupo: nombre y con quién. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DialogoGrupo(
    inicial: Grupo?,
    miembros: List<String>,
    conocidas: List<String>,
    onGuardar: (String, List<String>) -> Unit,
    onCerrar: () -> Unit,
    onTerminar: (() -> Unit)? = null,
    onBorrar: (() -> Unit)? = null,
    /** Quienes ya tienen gastos en el grupo: no se pueden quitar. */
    usados: Set<String> = emptySet(),
) {
    var nombre by remember { mutableStateOf(inicial?.nombre ?: "") }
    var elegidas by remember { mutableStateOf(miembros) }
    var escrito by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onCerrar,
        title = { Text(if (inicial == null) "Nuevo split" else "Editar split") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(nombre, { nombre = it }, Modifier.fillMaxWidth(), label = { Text("Nombre") },
                    placeholder = { Text("Viaje a la playa, Casa…") }, singleLine = true)
                CampoPersonas(
                    elegidas, { elegidas = it }, conocidas, varias = true, etiqueta = "¿Con quién? (tú ya estás)",
                    fijas = usados, excluir = setOf(YO), onEscribiendo = { escrito = it },
                )
                if (usados.isNotEmpty()) Text(
                    "Quien ya tiene gastos en el split no se puede quitar.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (inicial != null && onTerminar != null && onBorrar != null) {
                    Row {
                        TextButton(onClick = onTerminar) { Text(if (inicial.activo) "Terminar split" else "Volver a abrir") }
                        TextButton(onClick = onBorrar) { Text("Borrar", color = MaterialTheme.colorScheme.error) }
                    }
                    Text("Borrar elimina sus gastos y sus fotos (también en Drive); tus movimientos de tarjeta vuelven a contar completos.", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = nombre.isNotBlank(), onClick = {
                onGuardar(nombre.trim(), conEscrito(elegidas, escrito, conocidas).filter { Personas.clave(it) != Personas.clave(YO) })
            }) { Text("Guardar") }
        },
        dismissButton = { TextButton(onClick = onCerrar) { Text("Cancelar") } },
    )
}

/**
 * Un gasto del grupo: qué, cuánto, quién pagó y entre quiénes (igual o por cantidades). Desde un
 * movimiento de tarjeta llega con [transaccion]: pagaste tú, y si hay varios grupos eliges cuál.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DialogoGastoGrupo(
    grupos: List<Grupo>,
    datos: DatosGrupos,
    inicial: GrupoGasto?,
    transaccion: Transaccion?,
    onGuardar: (GrupoGasto, Map<String, Double>, fotos: List<File>?) -> Unit,
    onCerrar: () -> Unit,
    onBorrar: (() -> Unit)? = null,
) {
    val ctx = LocalContext.current
    if (grupos.isEmpty()) {
        AlertDialog(
            onDismissRequest = onCerrar,
            title = { Text("Agregar a un split") },
            text = { Text("Primero crea un split en Menú → Split (por ejemplo, el viaje o la cena).") },
            confirmButton = { TextButton(onClick = onCerrar) { Text("Entendido") } },
        )
        return
    }
    var grupoId by remember { mutableStateOf(inicial?.grupoId ?: grupos.first().id) }
    val gente = listOf(YO) + datos.miembrosDe[grupoId].orEmpty()
    val partesIniciales = inicial?.let { x -> datos.partesDe[x.id].orEmpty().associate { it.persona to it.monto } }
    var concepto by remember { mutableStateOf(inicial?.concepto ?: transaccion?.comercio ?: "") }
    var monto by remember { mutableStateOf((inicial?.monto ?: transaccion?.monto)?.let { "%.2f".format(it) } ?: "") }
    var pago by remember { mutableStateOf(inicial?.pago ?: YO) }
    val entre = remember(grupoId) { mutableStateListOf<String>().apply { addAll(partesIniciales?.keys?.filter { it in gente }?.takeIf { it.isNotEmpty() } ?: gente) } }
    // Si las partes guardadas no son iguales entre sí, se abre en "por cantidades".
    val eranIguales = partesIniciales.isNullOrEmpty() || partesIniciales.values.let { v -> v.max() - v.min() < 0.015 }
    var porCantidades by remember { mutableStateOf(!eranIguales) }
    val cantidades = remember(grupoId) {
        mutableStateMapOf<String, String>().apply { partesIniciales?.forEach { (p, m) -> put(p, "%.2f".format(m)) } }
    }
    val total = leer(monto)?.takeIf { it > 0 }
    val partes: Map<String, Double>? = when {
        total == null || entre.isEmpty() -> null
        !porCantidades -> entre.zip(Grupos.repartirIgual(total, entre.size)).toMap()
        else -> entre.associateWith { leer(cantidades[it] ?: "") ?: 0.0 }.takeIf { Grupos.cuadra(total, it.values) }
    }
    val desdeTarjeta = transaccion != null || inicial?.transaccionId != null
    val fotos = remember { mutableStateListOf<File>().apply { inicial?.let { addAll(Tickets.de(ctx, it.grupoId, it.id)) } } }
    var fotosCambiaron by remember { mutableStateOf(false) }
    val decimal = KeyboardOptions(keyboardType = KeyboardType.Decimal)

    AlertDialog(
        onDismissRequest = onCerrar,
        title = { Text(if (inicial == null) "Gasto compartido" else "Editar gasto") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (grupos.size > 1) {
                    Text("Split", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        grupos.forEach { g -> FilterChip(grupoId == g.id, { grupoId = g.id }, label = { Text(g.nombre) }) }
                    }
                }
                OutlinedTextField(concepto, { concepto = it }, Modifier.fillMaxWidth(), label = { Text("Qué fue") }, singleLine = true)
                OutlinedTextField(
                    monto, { monto = it }, Modifier.fillMaxWidth(), label = { Text("Cuánto") }, prefix = { Text("$") },
                    keyboardOptions = decimal, singleLine = true, enabled = !desdeTarjeta,
                    supportingText = { if (desdeTarjeta) Text("Lo pagaste con tu tarjeta") },
                )
                if (!desdeTarjeta) {
                    Text("¿Quién pagó?", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        gente.forEach { p -> FilterChip(pago == p, { pago = p }, label = { Text(quien(p)) }) }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("¿Entre quiénes?", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                    FilterChip(!porCantidades, { porCantidades = false }, label = { Text("Igual") })
                    androidx.compose.foundation.layout.Spacer(Modifier.width(6.dp))
                    FilterChip(porCantidades, {
                        // Al pasar a cantidades, arranca con el reparto igual para solo ajustar.
                        if (!porCantidades && total != null && entre.isNotEmpty()) {
                            entre.zip(Grupos.repartirIgual(total, entre.size)).forEach { (p, m) -> cantidades[p] = "%.2f".format(m) }
                        }
                        porCantidades = true
                    }, label = { Text("Cantidades") })
                }
                gente.forEach { p ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(p in entre, { if (it) entre += p else entre.remove(p) })
                        Text(quien(p), Modifier.weight(1f))
                        if (p in entre) {
                            if (porCantidades) OutlinedTextField(
                                cantidades[p] ?: "", { cantidades[p] = it }, Modifier.width(120.dp),
                                prefix = { Text("$") }, keyboardOptions = decimal, singleLine = true,
                            ) else partes?.get(p)?.let { Text(dinero(it), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                }
                if (porCantidades && total != null && partes == null && entre.isNotEmpty()) {
                    val suma = entre.sumOf { leer(cantidades[it] ?: "") ?: 0.0 }
                    Text(
                        "Las partes suman ${dinero(suma)}; deben sumar ${dinero(total)} (faltan ${dinero(total - suma)}).",
                        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                    )
                }
                partes?.let { Text("Te toca ${dinero(it[YO] ?: 0.0)}", fontWeight = FontWeight.SemiBold) }
                SeccionTickets(fotos) { fotosCambiaron = true }
                if (onBorrar != null) TextButton(onClick = onBorrar, contentPadding = PaddingValues(0.dp)) {
                    Text("Borrar este gasto", color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = partes != null && concepto.isNotBlank(), onClick = {
                val base = inicial ?: GrupoGasto(
                    grupoId = grupoId, fecha = transaccion?.fecha ?: System.currentTimeMillis(),
                    concepto = "", monto = 0.0, pago = YO, tipo = Grupos.GASTO, transaccionId = transaccion?.id,
                )
                onGuardar(
                    base.copy(grupoId = grupoId, concepto = concepto.trim(), monto = total!!, pago = if (desdeTarjeta) YO else pago),
                    partes!!,
                    if (fotosCambiaron) fotos.toList() else null,
                )
            }) { Text("Guardar") }
        },
        dismissButton = { TextButton(onClick = onCerrar) { Text("Cancelar") } },
    )
}

/**
 * Fotos del ticket de un gasto: miniaturas, "Tomar foto" y "Galería" (máximo [Tickets.MAXIMO]).
 * Las nuevas se comprimen en blanco y negro al momento; se quedan en [fotos] hasta que guardas.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SeccionTickets(fotos: MutableList<File>, onCambio: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var paraCamara by remember { mutableStateOf<File?>(null) }
    var viendo by remember { mutableStateOf<File?>(null) }
    var trabajando by remember { mutableStateOf(false) }
    val agregar = { uri: Uri, borrarDespues: File? ->
        trabajando = true
        scope.launch {
            val f = withContext(Dispatchers.IO) { Tickets.comprimir(ctx, uri).also { borrarDespues?.delete() } }
            if (f != null && fotos.size < Tickets.MAXIMO) { fotos += f; onCambio() }
            trabajando = false
        }
    }
    val camara = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = paraCamara
        if (ok && f != null) agregar(Uri.fromFile(f), f) else f?.delete()
        paraCamara = null
    }
    val galeria = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) agregar(uri, null)
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Ticket", style = MaterialTheme.typography.labelLarge)
        if (fotos.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            fotos.forEach { f -> Miniatura(f, 72.dp) { viendo = f } }
        }
        if (fotos.size < Tickets.MAXIMO) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(enabled = !trabajando, onClick = {
                val f = Tickets.paraCamara(ctx)
                paraCamara = f
                camara.launch(FileProvider.getUriForFile(ctx, ctx.packageName + ".archivos", f))
            }) { Text("Tomar foto") }
            OutlinedButton(enabled = !trabajando, onClick = {
                galeria.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }) { Text("Galería") }
            if (trabajando) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        }
    }

    viendo?.let { f ->
        Dialog(onDismissRequest = { viendo = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxWidth().padding(16.dp), shape = MaterialTheme.shapes.large) {
                Column(Modifier.padding(12.dp)) {
                    val imagen by produceState<ImageBitmap?>(null, f) {
                        value = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(f.absolutePath)?.asImageBitmap() }
                    }
                    imagen?.let {
                        Image(it, "Ticket", Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState()), contentScale = ContentScale.FillWidth)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { fotos.remove(f); onCambio(); viendo = null }) {
                            Text("Quitar foto", color = MaterialTheme.colorScheme.error)
                        }
                        TextButton(onClick = { viendo = null }) { Text("Cerrar") }
                    }
                }
            }
        }
    }
}

/** Miniatura cuadrada de una foto de ticket (se lee chiquita para no gastar memoria). */
@Composable
private fun Miniatura(f: File, lado: androidx.compose.ui.unit.Dp, onClick: () -> Unit) {
    val imagen by produceState<ImageBitmap?>(null, f.absolutePath, f.lastModified()) {
        value = withContext(Dispatchers.IO) {
            BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = 8 })?.asImageBitmap()
        }
    }
    Box(
        Modifier.size(lado).clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.surfaceVariant).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        imagen?.let { Image(it, "Ticket", Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
    }
}

/** Tonos de la gráfica "Cada quien": validados (contraste ≥ 3:1, distinguibles con daltonismo). */
private val BarraGasto = Color(0xFF111418)
private val BarraPago = Color(0xFF8A919E)

/** Lo de una persona en un split: su parte de los gastos, lo que pagó y cómo queda. */
private data class Cuenta(val persona: String, val gasto: Double, val pago: Double, val saldo: Double)

private fun cuentas(g: Grupo, datos: DatosGrupos): List<Cuenta> {
    val gastos = datos.gastosDe[g.id].orEmpty().filter { it.tipo == Grupos.GASTO }
    val ids = gastos.map { it.id }.toSet()
    val partes = datos.partesDelGrupo(g.id).filter { it.gastoId in ids }
    val saldos = datos.saldos(g.id)
    return (listOf(YO) + datos.miembrosDe[g.id].orEmpty()).map { p ->
        Cuenta(p, partes.filter { it.persona == p }.sumOf { it.monto }, gastos.filter { it.pago == p }.sumOf { it.monto }, saldos[p] ?: 0.0)
    }.sortedWith(compareByDescending<Cuenta> { it.gasto }.thenBy { quien(it.persona).lowercase() })
}

/**
 * "Cada quien": por persona, una barra con lo que gastó (su parte) y otra con lo que pagó, en la
 * misma escala, con la cifra en la punta. Tocar a alguien abre su desglose.
 */
@Composable
private fun GraficaCadaQuien(lista: List<Cuenta>, onPersona: (String) -> Unit) {
    val tope = lista.maxOfOrNull { maxOf(it.gasto, it.pago) }?.takeIf { it > 0 } ?: 1.0
    val apagado = MaterialTheme.colorScheme.onSurfaceVariant
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // Leyenda: siempre, porque son dos series.
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Muestra(BarraGasto, "Gastó (su parte)")
            Muestra(BarraPago, "Pagó")
        }
        lista.forEach { c ->
            Column(
                Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable { onPersona(c.persona) }.padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(quien(c.persona), Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                    val tu = c.persona == YO
                    Text(
                        when {
                            c.saldo > 0.005 -> (if (tu) "te deben " else "le deben ") + dinero(c.saldo)
                            c.saldo < -0.005 -> (if (tu) "debes " else "debe ") + dinero(-c.saldo)
                            else -> if (tu) "estás a mano" else "a mano"
                        },
                        style = MaterialTheme.typography.bodySmall, color = apagado,
                    )
                }
                Barra(c.gasto / tope, BarraGasto, dinero(c.gasto))
                Barra(c.pago / tope, BarraPago, dinero(c.pago))
            }
        }
    }
}

@Composable
private fun Muestra(color: Color, texto: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(color))
        androidx.compose.foundation.layout.Spacer(Modifier.width(6.dp))
        Text(texto, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Barra horizontal delgada desde la izquierda, punta redondeada de 4 dp, cifra a la derecha. */
@Composable
private fun Barra(fraccion: Double, color: Color, etiqueta: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).height(10.dp)) {
            if (fraccion > 0.0) Box(
                Modifier.fillMaxWidth(fraccion.toFloat().coerceIn(0.01f, 1f)).fillMaxHeight()
                    .clip(RoundedCornerShape(topEnd = 4.dp, bottomEnd = 4.dp)).background(color)
            )
        }
        Text(
            etiqueta, Modifier.width(96.dp), textAlign = TextAlign.End,
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** Desglose de una persona: cada gasto con lo que le tocó y si lo pagó ella. */
@Composable
private fun DialogoPersonaSplit(persona: String, g: Grupo, datos: DatosGrupos, onCerrar: () -> Unit) {
    val gastos = datos.gastosDe[g.id].orEmpty().sortedBy { it.fecha }
    val c = cuentas(g, datos).firstOrNull { it.persona == persona } ?: return
    val apagado = MaterialTheme.colorScheme.onSurfaceVariant
    AlertDialog(
        onDismissRequest = onCerrar,
        title = { Text(quien(persona)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (persona == YO) "Gastaste ${dinero(c.gasto)} · pagaste ${dinero(c.pago)}" else "Gastó ${dinero(c.gasto)} · pagó ${dinero(c.pago)}", fontWeight = FontWeight.SemiBold)
                gastos.filter { it.tipo == Grupos.GASTO }.forEach { x ->
                    val parte = datos.partesDe[x.id].orEmpty().firstOrNull { it.persona == persona }?.monto ?: 0.0
                    if (parte > 0 || x.pago == persona) Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(x.concepto)
                            Text(
                                textoDia(diaDe(x.fecha)) + if (x.pago == persona) " · lo pagó (${dinero(x.monto)})" else "",
                                style = MaterialTheme.typography.bodySmall, color = apagado,
                            )
                        }
                        Text(if (parte > 0) dinero(parte) else "no le toca", color = if (parte > 0) MaterialTheme.colorScheme.onSurface else apagado)
                    }
                }
                gastos.filter { it.tipo == Grupos.PAGO }.forEach { x ->
                    val a = datos.partesDe[x.id].orEmpty().firstOrNull()?.persona
                    if (x.pago == persona || a == persona) Text(
                        "${textoDia(diaDe(x.fecha))}: ${quien(x.pago)} le pagó ${dinero(x.monto)} a ${quien(a ?: "")}",
                        style = MaterialTheme.typography.bodySmall, color = apagado,
                    )
                }
                val tu = persona == YO
                Text(
                    when {
                        c.saldo > 0.005 -> (if (tu) "Te deben " else "Le deben ") + dinero(c.saldo)
                        c.saldo < -0.005 -> (if (tu) "Debes " else "Debe ") + dinero(-c.saldo)
                        else -> if (tu) "Estás a mano" else "Está a mano"
                    },
                    fontWeight = FontWeight.SemiBold,
                )
            }
        },
        confirmButton = { TextButton(onClick = onCerrar) { Text("Cerrar") } },
    )
}

/** "Ana te pagó $400": registra (o corrige) un pago entre dos del grupo. */
@Composable
private fun DialogoPago(
    t: Grupos.Transferencia,
    inicial: GrupoGasto?,
    onGuardar: (GrupoGasto) -> Unit,
    onCerrar: () -> Unit,
    onBorrar: (() -> Unit)? = null,
) {
    var monto by remember { mutableStateOf("%.2f".format(t.monto)) }
    val n = leer(monto)?.takeIf { it > 0 }
    AlertDialog(
        onDismissRequest = onCerrar,
        title = {
            Text(
                when {
                    t.a == YO -> "${t.de} te pagó"
                    t.de == YO -> "Le pagaste a ${t.a}"
                    else -> "${t.de} le pagó a ${t.a}"
                }
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    monto, { monto = it }, Modifier.fillMaxWidth(), label = { Text("Cuánto") }, prefix = { Text("$") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true,
                )
                Text(
                    "Solo queda anotado en el split: la transferencia en sí ya aparece en tus movimientos si llegó al banco.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (onBorrar != null) TextButton(onClick = onBorrar, contentPadding = PaddingValues(0.dp)) {
                    Text("Borrar este pago", color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = n != null, onClick = {
                val base = inicial ?: GrupoGasto(grupoId = 0, fecha = System.currentTimeMillis(), concepto = "Pago", monto = 0.0, pago = t.de, tipo = Grupos.PAGO)
                onGuardar(base.copy(monto = n!!))
            }) { Text("Guardar") }
        },
        dismissButton = { TextButton(onClick = onCerrar) { Text("Cancelar") } },
    )
}
