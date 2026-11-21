package com.misfinanzas.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.misfinanzas.R
import com.misfinanzas.Ciclo
import com.misfinanzas.Ciclos
import com.misfinanzas.FinanzasViewModel
import com.misfinanzas.Meses
import com.misfinanzas.Tipos
import com.misfinanzas.data.TarjetaConfig
import com.misfinanzas.data.Transaccion
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val fmtCorto = DateTimeFormatter.ofPattern("d MMM", Locale("es", "MX"))
private fun corta(d: LocalDate) = d.format(fmtCorto).removeSuffix(".")

private fun cuandoFalta(dias: Long) = when {
    dias == 0L -> "hoy"
    dias == 1L -> "mañana"
    else -> "en $dias días"
}

/** Gastos de [tarjeta] cuyas fechas caen dentro de [dias] (ambos extremos incluidos). */
private fun List<Transaccion>.gastoEn(tarjeta: String, dias: ClosedRange<LocalDate>) =
    filter { it.tipo == Tipos.GASTO && it.tarjeta == tarjeta && diaDe(it.fecha) in dias }.sumOf { it.monto }

/** Fechas de corte y pago de cada tarjeta de crédito, con lo gastado en cada periodo. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PantallaTarjetas(vm: FinanzasViewModel, botonMenu: @Composable () -> Unit) {
    val tarjetas by vm.tarjetasConfig.collectAsStateWithLifecycle()
    val movimientos by vm.recientes.collectAsStateWithLifecycle()
    val primeraFecha by vm.primeraFecha.collectAsStateWithLifecycle()
    val planes by vm.meses.collectAsStateWithLifecycle()
    val primerDia = primeraFecha?.let(::diaDe)
    val hoy = LocalDate.now()
    var editando by remember { mutableStateOf<TarjetaConfig?>(null) }

    var detalle by remember { mutableStateOf<String?>(null) }
    val enDetalle = tarjetas.firstOrNull { it.nombre == detalle }

    PedirPermisoNotificaciones()

    if (enDetalle != null) {
        DetalleTarjeta(enDetalle, movimientos, planes.filter { it.tarjeta == enDetalle.nombre }, primerDia, hoy, onEditar = { editando = enDetalle }, onVolver = { detalle = null })
    } else Scaffold(
        topBar = { TopAppBar(title = { Text("Tarjetas") }, navigationIcon = botonMenu) },
        floatingActionButton = {
            FloatingActionButton(onClick = { editando = TarjetaConfig(nombre = "", diaCorte = 1, diaLimite = 20) }) {
                Icon(Icons.Default.Add, "Agregar tarjeta")
            }
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 88.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(tarjetas, key = { it.nombre }) { t ->
                val ciclo = Ciclos.para(t.diaCorte, t.diaLimite, hoy)
                TarjetaCard(
                    t = t,
                    ciclo = ciclo,
                    hoy = hoy,
                    gastoActual = movimientos.gastoEn(t.nombre, ciclo.periodoActual),
                    gastoCerrado = movimientos.gastoEn(t.nombre, ciclo.periodoCerrado),
                    // Si el periodo empezó antes del primer movimiento registrado, el monto está incompleto.
                    cerradoIncompleto = primerDia == null || ciclo.periodoCerrado.start < primerDia,
                    // El periodo cerró antes del primer movimiento registrado: no hay nada que mostrar.
                    cerradoSinDatos = primerDia == null || ciclo.periodoCerrado.endInclusive < primerDia,
                    actualIncompleto = primerDia == null || ciclo.periodoActual.start < primerDia,
                    onAbrir = { detalle = t.nombre },
                    onPagada = { pagada -> vm.guardarTarjeta(t.copy(pagadoCorte = if (pagada) ciclo.ultimoCorte.toEpochDay() else null)) },
                )
            }
        }
    }

    editando?.let { t ->
        DialogoTarjeta(
            inicial = t,
            onGuardar = { vm.guardarTarjeta(it); editando = null },
            onEliminar = { vm.eliminarTarjeta(t); editando = null },
            onCerrar = { editando = null },
        )
    }
}

@Composable
private fun TarjetaCard(
    t: TarjetaConfig,
    ciclo: Ciclo,
    hoy: LocalDate,
    gastoActual: Double,
    gastoCerrado: Double,
    cerradoIncompleto: Boolean,
    cerradoSinDatos: Boolean,
    actualIncompleto: Boolean,
    onAbrir: () -> Unit,
    onPagada: (Boolean) -> Unit,
) {
    val pagada = t.pagadoCorte == ciclo.ultimoCorte.toEpochDay()
    val diasParaPagar = Ciclos.diasEntre(hoy, ciclo.limitePago)
    val apagado = MaterialTheme.colorScheme.onSurfaceVariant

    val colorTarjeta = t.color?.let { Color(it) } ?: MaterialTheme.colorScheme.outline
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onAbrir),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.5.dp, colorTarjeta),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    LogoTarjeta(t.nombre, tamano = 36.dp) {
                        // Recuadro relleno con el color de la tarjeta; el icono va en blanco o en tinta según contraste.
                        val relleno = t.color?.let { Color(it) } ?: Tinta
                        Box(Modifier.size(36.dp).background(relleno, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                            Icon(
                                painterResource(R.drawable.ic_credit_card),
                                contentDescription = null,
                                tint = if (relleno.luminance() > 0.6f) Tinta else Color.White,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                    Text(t.nombre, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
                Text("Corte ${t.diaCorte} · Pago ${t.diaLimite}", style = MaterialTheme.typography.labelMedium, color = apagado)
            }

            // Lo que ya cerró y toca pagar.
            Text("Último corte (${corta(ciclo.ultimoCorte)})", style = MaterialTheme.typography.labelLarge)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    when {
                        pagada -> "Pagada"
                        diasParaPagar < 0 -> "Vencía el ${corta(ciclo.limitePago)}"
                        else -> "Pagar antes del ${corta(ciclo.limitePago)} · ${cuandoFalta(diasParaPagar)}"
                    },
                    color = when {
                        pagada -> Ingreso
                        diasParaPagar < 0 -> apagado
                        diasParaPagar <= t.avisarDiasAntes -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                if (cerradoSinDatos) Text("Sin datos", style = MaterialTheme.typography.bodyMedium, color = apagado)
                else Text("~${dinero(gastoCerrado)}", fontWeight = FontWeight.SemiBold)
            }
            if (cerradoIncompleto && !cerradoSinDatos) {
                Text("Monto incompleto: la app no tiene datos de todo ese periodo.", style = MaterialTheme.typography.bodySmall, color = apagado)
            }
            if (diasParaPagar >= 0) {
                if (pagada) TextButton(onClick = { onPagada(false) }) { Text("Deshacer") }
                else FilledTonalButton(onClick = { onPagada(true) }) { Text("Ya la pagué") }
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            // Lo que llevas en el periodo abierto.
            Text("Periodo actual", style = MaterialTheme.typography.labelLarge)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Corta el ${corta(ciclo.proximoCorte)} · ${cuandoFalta(Ciclos.diasEntre(hoy, ciclo.proximoCorte))}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "Se paga antes del ${corta(ciclo.limiteProximo)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = apagado,
                    )
                }
                Text(dinero(gastoActual), fontWeight = FontWeight.SemiBold)
            }
            if (actualIncompleto) {
                Text("Incluye solo lo registrado desde que instalaste la app.", style = MaterialTheme.typography.bodySmall, color = apagado)
            }
        }
    }
}

/** Periodos de la tarjeta, del actual hacia atrás, mientras haya movimientos registrados. */
private fun periodosDe(t: TarjetaConfig, hoy: LocalDate, primerDia: LocalDate?): List<ClosedRange<LocalDate>> {
    val periodos = ArrayList<ClosedRange<LocalDate>>()
    var referencia = hoy
    repeat(6) {
        val ciclo = Ciclos.para(t.diaCorte, t.diaLimite, referencia)
        if (periodos.isNotEmpty() && (primerDia == null || ciclo.periodoActual.endInclusive < primerDia)) return periodos
        periodos += ciclo.periodoActual
        referencia = ciclo.ultimoCorte
    }
    return periodos
}

/** Cada periodo de la tarjeta con su total y las compras que lo forman. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetalleTarjeta(
    t: TarjetaConfig,
    movimientos: List<Transaccion>,
    planes: List<Meses.Plan>,
    primerDia: LocalDate?,
    hoy: LocalDate,
    onEditar: () -> Unit,
    onVolver: () -> Unit,
) {
    BackHandler(onBack = onVolver)
    val periodos = periodosDe(t, hoy, primerDia)
    val gastos = movimientos.filter { it.tipo == Tipos.GASTO && it.tarjeta == t.nombre }
    val apagado = MaterialTheme.colorScheme.onSurfaceVariant

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(t.nombre) },
                navigationIcon = {
                    IconButton(onClick = onVolver) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver") }
                },
                actions = { TextButton(onClick = onEditar) { Text("Editar fechas") } },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            if (planes.isNotEmpty()) item(key = "meses") { SeccionMeses(planes, hoy) }
            periodos.forEachIndexed { i, dias ->
                val compras = gastos.filter { diaDe(it.fecha) in dias }
                item(key = "periodo-$i") {
                    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(
                                when (i) {
                                    0 -> "Periodo actual"
                                    1 -> "Último corte"
                                    else -> "Periodo anterior"
                                },
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(dinero(compras.sumOf { it.monto }), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        }
                        Text(
                            "${corta(dias.start)} – ${corta(dias.endInclusive)} · ${compras.size} compras",
                            style = MaterialTheme.typography.bodySmall,
                            color = apagado,
                        )
                        if (primerDia == null || dias.start < primerDia) {
                            Text("Incompleto: la app no tiene datos de todo el periodo.", style = MaterialTheme.typography.bodySmall, color = apagado)
                        }
                    }
                }
                items(compras, key = { it.id }) { c ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconoMovimiento(c.categoria, tamano = 36.dp)
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(c.comercio, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${corta(diaDe(c.fecha))} · ${hora(c.fecha)}", style = MaterialTheme.typography.bodySmall, color = apagado)
                        }
                        Text(dinero(c.monto))
                    }
                }
            }
        }
    }
}

@Composable
private fun DialogoTarjeta(
    inicial: TarjetaConfig,
    onGuardar: (TarjetaConfig) -> Unit,
    onEliminar: () -> Unit,
    onCerrar: () -> Unit,
) {
    val esNueva = inicial.nombre.isEmpty()
    var nombre by remember { mutableStateOf(inicial.nombre) }
    var corte by remember { mutableStateOf(inicial.diaCorte.toString()) }
    var limite by remember { mutableStateOf(inicial.diaLimite.toString()) }
    var aviso by remember { mutableStateOf(inicial.avisarDiasAntes.toString()) }
    var hex by remember { mutableStateOf(inicial.color?.let { "#%06X".format(it and 0xFFFFFF) } ?: "") }
    // Vacío = sin color; si no, seis dígitos hexadecimales con o sin "#".
    val colorNuevo = hex.trim().removePrefix("#").takeIf { it.length == 6 }?.toIntOrNull(16)?.let { it or 0xFF000000.toInt() }
    val hexValido = hex.isBlank() || colorNuevo != null

    fun dia(txt: String) = txt.toIntOrNull()?.takeIf { it in 1..31 }
    val valido = nombre.isNotBlank() && dia(corte) != null && dia(limite) != null && (aviso.toIntOrNull() ?: -1) in 0..30 && hexValido

    AlertDialog(
        onDismissRequest = onCerrar,
        title = { Text(if (esNueva) "Nueva tarjeta" else inicial.nombre) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (esNueva) {
                    OutlinedTextField(nombre, { nombre = it }, Modifier.fillMaxWidth(), label = { Text("Nombre (igual que en tus movimientos)") }, singleLine = true)
                }
                val numerico = KeyboardOptions(keyboardType = KeyboardType.Number)
                OutlinedTextField(corte, { corte = it }, Modifier.fillMaxWidth(), label = { Text("Día de corte") }, singleLine = true, keyboardOptions = numerico)
                OutlinedTextField(limite, { limite = it }, Modifier.fillMaxWidth(), label = { Text("Día límite de pago") }, singleLine = true, keyboardOptions = numerico)
                OutlinedTextField(
                    aviso, { aviso = it }, Modifier.fillMaxWidth(),
                    label = { Text("Avisarme días antes (0 = no avisar)") }, singleLine = true, keyboardOptions = numerico,
                )
                OutlinedTextField(
                    hex, { hex = it }, Modifier.fillMaxWidth(),
                    label = { Text("Color (código, ej. #820AD1)") }, singleLine = true, isError = !hexValido,
                    trailingIcon = {
                        Box(Modifier.size(24.dp).background(colorNuevo?.let { Color(it) } ?: Color.Transparent, CircleShape))
                    },
                )
            }
        },
        confirmButton = {
            TextButton(enabled = valido, onClick = {
                onGuardar(inicial.copy(nombre = nombre.trim(), diaCorte = dia(corte)!!, diaLimite = dia(limite)!!, avisarDiasAntes = aviso.toInt(), color = colorNuevo))
            }) { Text("Guardar") }
        },
        dismissButton = {
            Row {
                if (!esNueva) TextButton(onClick = onEliminar) { Text("Eliminar", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onCerrar) { Text("Cancelar") }
            }
        },
    )
}

/** Desde Android 13 hay que pedir permiso para mostrar los recordatorios de pago. */
@Composable
private fun PedirPermisoNotificaciones() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val ctx = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        val permiso = Manifest.permission.POST_NOTIFICATIONS
        if (ContextCompat.checkSelfPermission(ctx, permiso) != PackageManager.PERMISSION_GRANTED) launcher.launch(permiso)
    }
}

/** Compras a meses de la tarjeta: cuánto falta y cuándo cae cada mensualidad. */
@Composable
private fun SeccionMeses(planes: List<Meses.Plan>, hoy: LocalDate) {
    val apagado = MaterialTheme.colorScheme.onSurfaceVariant
    val porPagar = planes.sumOf { p -> p.mensualidades.filter { diaDe(it.fecha) >= hoy }.sumOf { it.monto } }
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 20.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Meses sin intereses", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Text(dinero(porPagar), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        Text("Por pagar de ${planes.size} ${if (planes.size == 1) "compra" else "compras"}", style = MaterialTheme.typography.bodySmall, color = apagado)
        planes.forEach { p ->
            Card(
                Modifier.fillMaxWidth().padding(top = 12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(p.comercio, Modifier.weight(1f), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(dinero(p.total), fontWeight = FontWeight.SemiBold)
                    }
                    Text(
                        (p.compra?.let { "Compra del ${corta(diaDe(it.fecha))} · " } ?: "") + "${p.mensualidades.size} meses",
                        style = MaterialTheme.typography.bodySmall,
                        color = apagado,
                    )
                    HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    p.mensualidades.forEach { m ->
                        val pasada = diaDe(m.fecha) < hoy
                        val color = if (pasada) apagado else MaterialTheme.colorScheme.onSurface
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Text(m.nota, Modifier.weight(1f), color = color)
                            Text(diaDe(m.fecha).format(fmtMesCorto).replace(".", ""), Modifier.weight(1.4f), color = color)
                            Text(dinero(m.monto), color = color)
                        }
                    }
                }
            }
        }
    }
}

private val fmtMesCorto = DateTimeFormatter.ofPattern("d MMM yyyy", Locale("es", "MX"))
