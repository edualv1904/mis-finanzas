package com.misfinanzas.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.misfinanzas.R
import com.misfinanzas.Meses
import com.misfinanzas.Tipos
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.res.painterResource
import com.misfinanzas.Ajustes
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.misfinanzas.FinanzasViewModel
import com.misfinanzas.data.Transaccion

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PantallaPrincipal(
    abrirAjustesNotificaciones: () -> Unit,
    botonMenu: @Composable () -> Unit,
    vm: FinanzasViewModel = viewModel(),
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val mes by vm.mes.collectAsStateWithLifecycle()
    val lista by vm.transacciones.collectAsStateWithLifecycle()
    val tarjetas by vm.tarjetas.collectAsStateWithLifecycle()
    val credito by vm.tarjetasConfig.collectAsStateWithLifecycle()

    var tieneAcceso by remember { mutableStateOf(true) }
    var ubicacion by remember { mutableStateOf(true) }
    var ubicacionSiempre by remember { mutableStateOf(true) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        tieneAcceso = ctx.packageName in NotificationManagerCompat.getEnabledListenerPackages(ctx)
        ubicacion = com.misfinanzas.Ubicacion.hayPermiso(ctx)
        ubicacionSiempre = com.misfinanzas.Ubicacion.hayPermisoSiempre(ctx)
    }

    var pestana by remember { mutableIntStateOf(0) }
    var editando by remember { mutableStateOf<Transaccion?>(null) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { vm.cambiarMes(-1) }) {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Mes anterior")
                        }
                        Text(textoMes(mes))
                        IconButton(onClick = { vm.cambiarMes(1) }) {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Mes siguiente")
                        }
                    }
                },
                navigationIcon = botonMenu,
                actions = {
                    val ocultos = Ajustes.saldosOcultos
                    IconButton(onClick = { cambiarOcultos(ctx, scope, !ocultos) }) {
                        Icon(
                            painterResource(if (ocultos) R.drawable.ic_visibility_off else R.drawable.ic_visibility),
                            if (ocultos) "Mostrar cantidades" else "Ocultar cantidades",
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { editando = nuevaTransaccion() }) {
                Icon(Icons.Default.Add, "Agregar gasto")
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (!tieneAcceso) AvisoPermiso(abrirAjustesNotificaciones)
            else if (!ubicacionSiempre && !Ajustes.sinAvisoUbicacion) AvisoUbicacion(ubicacionConcedida = ubicacion) { ubicacionSiempre = it }

            Total(lista)

            TabRow(selectedTabIndex = pestana) {
                Tab(pestana == 0, { pestana = 0 }, text = { Text("Movimientos") })
                Tab(pestana == 1, { pestana = 1 }, text = { Text("Resumen") })
            }
            when (pestana) {
                0 -> Movimientos(lista, onClick = { editando = it })
                else -> Resumen(lista)
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
            onPasarAMeses = { meses: Int, primerMes: java.time.YearMonth -> vm.pasarAMeses(t, meses, primerMes); editando = null }
                .takeIf { t.id != 0L && t.tipo == Tipos.GASTO && t.fuente != Meses.FUENTE && credito.any { it.nombre == t.tarjeta } },
            onQuitarMeses = { vm.quitarMeses(t); editando = null },
            buscarDuplicados = (suspend { vm.posiblesDuplicados(t) }).takeIf { t.id != 0L && t.fuente != Meses.FUENTE },
            onDuplicadoDe = { conservar -> vm.marcarDuplicado(t, conservar); editando = null },
        )
    }
}

@Composable
private fun AvisoPermiso(abrir: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("Falta el acceso a notificaciones", fontWeight = FontWeight.Bold)
            Text("Sin él no puedo registrar tus pagos de Google Wallet automáticamente.")
            Spacer(Modifier.height(8.dp))
            Button(onClick = abrir) { Text("Dar acceso") }
        }
    }
}

/**
 * Pide la ubicación en dos pasos, como exige Android: primero "mientras se usa la app" y luego
 * "todo el tiempo", que es la que necesita el lector de notificaciones con la app cerrada.
 */
@Composable
private fun AvisoUbicacion(ubicacionConcedida: Boolean, onListo: (Boolean) -> Unit) {
    val ctx = LocalContext.current
    val segundoPaso = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        onListo(com.misfinanzas.Ubicacion.hayPermisoSiempre(ctx))
    }
    val primerPaso = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (com.misfinanzas.Ubicacion.hayPermiso(ctx) && android.os.Build.VERSION.SDK_INT >= 29) {
            segundoPaso.launch(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
    }
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text("¿Dónde gastas?", fontWeight = FontWeight.Bold)
            Text(
                if (ubicacionConcedida)
                    "Falta un paso: en la siguiente pantalla elige \"Permitir todo el tiempo\". Así se guarda el lugar aunque la app esté cerrada."
                else
                    "Guardo el lugar de cada compra en persona para que la veas en el mapa. Solo leo tu ubicación al momento de pagar.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(8.dp))
            Row {
                Button(onClick = {
                    if (ubicacionConcedida && android.os.Build.VERSION.SDK_INT >= 29) {
                        segundoPaso.launch(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    } else {
                        primerPaso.launch(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION))
                    }
                }) { Text(if (ubicacionConcedida) "Permitir todo el tiempo" else "Activar") }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = { Ajustes.ocultarAvisoUbicacion(ctx) }) { Text("Ahora no") }
            }
        }
    }
}

private fun List<Transaccion>.total(tipo: String) = filter { it.tipo == tipo }.sumOf { it.monto }

/** Rótulo pequeño en mayúsculas para nombrar una cifra o una sección. */
@Composable
private fun Etiqueta(texto: String, modifier: Modifier = Modifier) {
    Text(
        texto.uppercase(),
        modifier,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        letterSpacing = 0.8.sp,
    )
}

@Composable
private fun Total(lista: List<Transaccion>) {
    val gastos = lista.total(Tipos.GASTO)
    val ingresos = lista.total(Tipos.INGRESO)
    val balance = ingresos - gastos
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(Modifier.padding(20.dp)) {
            Etiqueta("Gastado este mes")
            Text(dinero(gastos), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold)
            Text(
                "${lista.size} movimientos",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider(Modifier.padding(vertical = 16.dp))
            Row {
                Column(Modifier.weight(1f)) {
                    Etiqueta("Ingresos")
                    Text("+${dinero(ingresos)}", style = MaterialTheme.typography.titleMedium, color = Ingreso)
                }
                Column(Modifier.weight(1f)) {
                    Etiqueta("Balance")
                    Text(
                        dinero(balance),
                        style = MaterialTheme.typography.titleMedium,
                        color = if (balance >= 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun Movimientos(lista: List<Transaccion>, onClick: (Transaccion) -> Unit) {
    if (lista.isEmpty()) {
        Text(
            "Aún no hay movimientos este mes.\nPaga con Google Wallet o agrégalos con el botón +.",
            Modifier.fillMaxWidth().padding(32.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    val porDia = lista.groupBy { diaDe(it.fecha) }
    LazyColumn(contentPadding = PaddingValues(bottom = 88.dp)) {
        porDia.forEach { (dia, items) ->
            item(key = "dia-$dia") {
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Etiqueta(textoDia(dia))
                    Etiqueta(dinero(items.total(Tipos.GASTO)))
                }
            }
            items(items, key = { it.id }) { t -> FilaTransaccion(t) { onClick(t) } }
        }
    }
}

@Composable
private fun FilaTransaccion(t: Transaccion, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconoMovimiento(t.categoria, t.tipo)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(t.comercio, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
            Text(
                // Lo cargado de estados de cuenta no trae hora, solo el día.
                when {
                    t.tipo == Tipos.MSI -> "${t.tarjeta} · a meses"
                    t.fuente == Meses.FUENTE -> "${t.tarjeta} · mensualidad ${t.nota}"
                    t.fuente == "manual" -> "${t.tarjeta} · ${hora(t.fecha)} · manual"
                    t.fuente == "estado" -> "${t.tarjeta} · estado de cuenta"
                    else -> "${t.tarjeta} · ${hora(t.fecha)}"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(12.dp))
        when (t.tipo) {
            Tipos.INGRESO -> Text("+${dinero(t.monto)}", fontWeight = FontWeight.SemiBold, color = Ingreso)
            Tipos.TRANSFERENCIA, Tipos.MSI -> Text(dinero(t.monto), color = MaterialTheme.colorScheme.onSurfaceVariant)
            else -> Text(dinero(t.monto), fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun Resumen(todos: List<Transaccion>) {
    val lista = todos.filter { it.tipo == Tipos.GASTO }
    val total = lista.sumOf { it.monto }.takeIf { it > 0 } ?: 1.0
    val porCategoria = lista.groupBy { it.categoria }.mapValues { e -> e.value.sumOf { it.monto } }.toList().sortedByDescending { it.second }
    val porTarjeta = lista.groupBy { it.tarjeta }.mapValues { e -> e.value.sumOf { it.monto } }.toList().sortedByDescending { it.second }

    LazyColumn(contentPadding = PaddingValues(16.dp, 20.dp, 16.dp, 88.dp)) {
        item { Etiqueta("Por categoría", Modifier.padding(bottom = 4.dp)) }
        items(porCategoria, key = { "c-" + it.first }) { (cat, monto) ->
            val estilo = Iconos.de(cat)
            Barra(cat, monto, monto / total, estilo.color) { IconoCaja(estilo.icono, estilo.color) }
        }
        item { Etiqueta("Por tarjeta", Modifier.padding(top = 24.dp, bottom = 4.dp)) }
        items(porTarjeta, key = { "t-" + it.first }) { (tarjeta, monto) ->
            Barra(tarjeta, monto, monto / total, Tinta) {
                LogoTarjeta(tarjeta) { IconoCaja(R.drawable.ic_credit_card, Tinta) }
            }
        }
        item {
            Etiqueta("No cuentan como gasto", Modifier.padding(top = 24.dp, bottom = 4.dp))
            FilaOtros("Ingresos", "+${dinero(todos.total(Tipos.INGRESO))}", Tipos.INGRESO)
            FilaOtros("Transferencias", dinero(todos.total(Tipos.TRANSFERENCIA)), Tipos.TRANSFERENCIA)
        }
    }
}

@Composable
private fun FilaOtros(nombre: String, monto: String, tipo: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        IconoMovimiento("", tipo)
        Spacer(Modifier.width(12.dp))
        Text(nombre, Modifier.weight(1f), fontWeight = FontWeight.Medium)
        Text(monto, fontWeight = FontWeight.SemiBold, color = if (tipo == Tipos.INGRESO) Ingreso else MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun Barra(etiqueta: String, monto: Double, fraccion: Double, color: Color, icono: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        icono()
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(Modifier.fillMaxWidth()) {
                Text(etiqueta, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                Text(dinero(monto), fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                LinearProgressIndicator(
                    progress = { fraccion.toFloat() },
                    modifier = Modifier.weight(1f).height(6.dp).clip(CircleShape),
                    color = color,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    gapSize = 0.dp,
                    drawStopIndicator = {},
                )
                Text(
                    "${"%.0f".format(fraccion * 100)}%",
                    Modifier.width(44.dp),
                    textAlign = TextAlign.End,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
