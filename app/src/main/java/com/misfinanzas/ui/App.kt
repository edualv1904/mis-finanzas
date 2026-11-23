package com.misfinanzas.ui

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.glance.appwidget.updateAll
import com.misfinanzas.Ajustes
import com.misfinanzas.BuildConfig
import com.misfinanzas.FinanzasViewModel
import com.misfinanzas.Cobros
import com.misfinanzas.Personas
import androidx.compose.runtime.remember
import com.misfinanzas.Respaldo
import com.misfinanzas.widget.FinanzasWidget
import kotlinx.coroutines.CoroutineScope
import com.misfinanzas.R
import kotlinx.coroutines.launch

private enum class Seccion(val titulo: String, @DrawableRes val icono: Int) {
    MOVIMIENTOS("Movimientos", R.drawable.ic_receipt_long),
    TARJETAS("Tarjetas", R.drawable.ic_credit_card),
    PERSONAS("Personas", R.drawable.ic_person),
    GRUPOS("Split", R.drawable.ic_people),
    COBROS("Me deben", R.drawable.ic_payments),
    DEBO("Debo", R.drawable.ic_account_balance),
    MAPA("Mapa", R.drawable.ic_location_on),
    NOTIFICACIONES("Notificaciones", R.drawable.ic_notifications),
    NOTAS("Notas e ideas", R.drawable.ic_edit_note),
}

/** Contenedor con menú lateral para moverse entre las secciones de la app. */
@Composable
fun App(abrirAjustesNotificaciones: () -> Unit, puedeBloquear: Boolean, abrir: String? = null, vm: FinanzasViewModel = viewModel()) {
    val ctx = LocalContext.current
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var seccion by rememberSaveable { mutableStateOf(
            when (abrir) {
                Cobros.ABRIR_COBROS -> Seccion.COBROS
                com.misfinanzas.Deudas.ABRIR_DEBO -> Seccion.DEBO
                else -> if (abrir?.startsWith("split:") == true) Seccion.GRUPOS else Seccion.MOVIMIENTOS
            }
        ) }
    val cobros by vm.cobros.collectAsStateWithLifecycle()
    val atrasados = cobros.count { Cobros.atrasado(it, System.currentTimeMillis()) }
    val sinRevisar by vm.crudasSinRevisar.collectAsStateWithLifecycle()
    var verRespaldo by rememberSaveable { mutableStateOf(false) }
    // Perfil abierto en Personas, y lo que se abrió desde él en Me deben / Debo (para regresar al perfil).
    var personaAbierta by rememberSaveable { mutableStateOf<String?>(null) }
    var conceptoDesdePersona by rememberSaveable { mutableStateOf<String?>(null) }
    var deudaDesdePersona by rememberSaveable { mutableStateOf<Long?>(null) }
    // Desde el widget de split se abre directo ese split.
    var grupoAbierto by rememberSaveable { mutableStateOf(abrir?.takeIf { it.startsWith("split:") }?.removePrefix("split:")?.toLongOrNull()) }
    var grupoDesdePersona by rememberSaveable { mutableStateOf(false) }
    val grupos by vm.grupos.collectAsStateWithLifecycle()
    val miembros by vm.miembros.collectAsStateWithLifecycle()
    val grupoGastos by vm.grupoGastos.collectAsStateWithLifecycle()
    val grupoPartes by vm.grupoPartes.collectAsStateWithLifecycle()
    val datosGrupos = remember(grupos, miembros, grupoGastos, grupoPartes) { DatosGrupos(grupos, miembros, grupoGastos, grupoPartes) }
    val todosAcuerdos by vm.acuerdos.collectAsStateWithLifecycle()
    val todasDeudas by vm.deudas.collectAsStateWithLifecycle()
    // La gente que ya conoces (Me deben, Debo, Grupos), para elegirla de una lista y no escribirla distinto.
    val conocidas = remember(todosAcuerdos, todasDeudas, miembros) {
        (todosAcuerdos.map { it.persona } + todasDeudas.map { it.persona } + miembros.map { it.persona })
            .map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { Personas.clave(it) }
    }
    val volverAPersona = {
        conceptoDesdePersona = null
        deudaDesdePersona = null
        grupoDesdePersona = false
        seccion = Seccion.PERSONAS
    }

    val botonMenu: @Composable () -> Unit = {
        IconButton(onClick = { scope.launch { drawer.open() } }) {
            BadgedBox(badge = { if (sinRevisar > 0) Badge { Text("$sinRevisar") } }) {
                Icon(Icons.Default.Menu, "Menú")
            }
        }
    }

    BackHandler(enabled = drawer.isOpen) { scope.launch { drawer.close() } }
    BackHandler(enabled = !drawer.isOpen && seccion != Seccion.MOVIMIENTOS) {
        if (conceptoDesdePersona != null || deudaDesdePersona != null || grupoDesdePersona) volverAPersona() else seccion = Seccion.MOVIMIENTOS
    }

    ModalNavigationDrawer(
        drawerState = drawer,
        // En el mapa, deslizar el dedo debe mover el mapa y no abrir el menú (se abre con el botón).
        gesturesEnabled = seccion != Seccion.MAPA || drawer.isOpen,
        drawerContent = {
            ModalDrawerSheet {
                Text(
                    "Mis Finanzas",
                    Modifier.padding(horizontal = 28.dp, vertical = 20.dp),
                    style = MaterialTheme.typography.titleLarge,
                )
                Seccion.entries.filter { it != Seccion.MAPA || BuildConfig.HAY_MAPA }.forEach { s ->
                    NavigationDrawerItem(
                        icon = { Icon(painterResource(s.icono), contentDescription = null) },
                        label = { Text(s.titulo) },
                        selected = seccion == s,
                        onClick = {
                            seccion = s
                            conceptoDesdePersona = null
                            deudaDesdePersona = null
                            grupoDesdePersona = false
                            if (s == Seccion.PERSONAS) personaAbierta = null
                            if (s == Seccion.GRUPOS) grupoAbierto = null
                            scope.launch { drawer.close() }
                        },
                        badge = {
                            if (s == Seccion.NOTIFICACIONES && sinRevisar > 0) Text("$sinRevisar")
                            if (s == Seccion.COBROS && atrasados > 0) Text("$atrasados", color = MaterialTheme.colorScheme.error)
                        },
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
                HorizontalDivider(Modifier.padding(horizontal = 28.dp, vertical = 12.dp))
                Interruptor(
                    "Pedir huella al abrir",
                    if (puedeBloquear) "También sirve tu rostro o PIN" else "Primero pon un bloqueo de pantalla en el teléfono",
                    R.drawable.ic_lock,
                    Ajustes.pedirHuella && puedeBloquear,
                    habilitado = puedeBloquear,
                ) { Ajustes.cambiarPedirHuella(ctx, it) }
                Interruptor(
                    "Ocultar cantidades",
                    "Para enseñar la app sin que se vean tus saldos",
                    R.drawable.ic_visibility_off,
                    Ajustes.saldosOcultos,
                ) { cambiarOcultos(ctx, scope, it) }
                HorizontalDivider(Modifier.padding(horizontal = 28.dp, vertical = 12.dp))
                NavigationDrawerItem(
                    icon = { Icon(painterResource(R.drawable.ic_cloud_upload), contentDescription = null) },
                    label = {
                        Column {
                            Text("Respaldo en Drive")
                            Text(
                                resumenRespaldo(),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (Respaldo.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    selected = false,
                    onClick = {
                        verRespaldo = true
                        scope.launch { drawer.close() }
                    },
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
                Spacer(Modifier.height(12.dp))
            }
        },
    ) {
        when (seccion) {
            Seccion.MOVIMIENTOS -> PantallaPrincipal(abrirAjustesNotificaciones, botonMenu, vm, datosGrupos)
            Seccion.TARJETAS -> PantallaTarjetas(vm, botonMenu)
            Seccion.MAPA -> PantallaMapa(vm, botonMenu)
            Seccion.PERSONAS -> {
                val acuerdos by vm.acuerdos.collectAsStateWithLifecycle()
                val deudas by vm.deudas.collectAsStateWithLifecycle()
                val movs by vm.deudaMovs.collectAsStateWithLifecycle()
                PantallaPersonas(
                    remember(acuerdos, cobros, deudas, movs, datosGrupos) { Personas.perfiles(acuerdos, cobros, deudas, movs, datosGrupos.porPersona()) },
                    abierta = personaAbierta,
                    onAbrir = { personaAbierta = it },
                    onAbrirConcepto = { conceptoDesdePersona = it; seccion = Seccion.COBROS },
                    onAbrirDeuda = { deudaDesdePersona = it; seccion = Seccion.DEBO },
                    onAbrirGrupo = { grupoAbierto = it; grupoDesdePersona = true; seccion = Seccion.GRUPOS },
                    onRenombrar = vm::renombrarPersona,
                    botonMenu = botonMenu,
                )
            }
            Seccion.GRUPOS -> {
                PantallaGrupos(
                    datosGrupos, conocidas,
                    abierto = grupoAbierto,
                    onAbrir = { id -> if (id == null && grupoDesdePersona) volverAPersona() else grupoAbierto = id },
                    onCrear = vm::crearGrupo,
                    onEditar = vm::editarGrupo,
                    onBorrar = vm::borrarGrupo,
                    onGuardarGasto = vm::guardarGrupoGasto,
                    onBorrarGasto = vm::borrarGrupoGasto,
                    botonMenu = botonMenu,
                    versionTickets = vm.ticketsCambiados.collectAsStateWithLifecycle().value,
                )
            }
            Seccion.COBROS -> {
                val acuerdos by vm.acuerdos.collectAsStateWithLifecycle()
                val ingresos by vm.ingresosRecientes.collectAsStateWithLifecycle()
                PantallaCobros(
                    acuerdos, cobros, ingresos,
                    onCrear = vm::crearAcuerdo,
                    onEditar = vm::editarAcuerdo,
                    onMarcar = vm::marcarCobro,
                    onCambiarMonto = vm::cambiarMontoCobro,
                    onTerminar = vm::terminarAcuerdo,
                    onBorrar = vm::borrarAcuerdo,
                    botonMenu = botonMenu,
                    inicial = conceptoDesdePersona,
                    conocidas = conocidas,
                    onVolver = if (conceptoDesdePersona != null) volverAPersona else null,
                )
            }
            Seccion.DEBO -> {
                val deudas by vm.deudas.collectAsStateWithLifecycle()
                val movs by vm.deudaMovs.collectAsStateWithLifecycle()
                val salidas by vm.transferenciasRecientes.collectAsStateWithLifecycle()
                PantallaDebo(
                    deudas, movs, salidas,
                    onCrear = vm::crearDeuda,
                    onEditar = vm::editarDeuda,
                    onBorrar = vm::borrarDeuda,
                    onAnotar = vm::anotarEnDeuda,
                    onEditarMov = vm::editarDeudaMov,
                    onBorrarMov = vm::borrarDeudaMov,
                    botonMenu = botonMenu,
                    inicial = deudaDesdePersona,
                    conocidas = conocidas,
                    onVolver = if (deudaDesdePersona != null) volverAPersona else null,
                )
            }
            Seccion.NOTIFICACIONES -> {
                val crudas by vm.crudas.collectAsStateWithLifecycle()
                PantallaRecolectadas(crudas, vm::etiquetar, vm::borrarIgnoradas, botonMenu)
            }
            Seccion.NOTAS -> {
                val notas by vm.notas.collectAsStateWithLifecycle()
                PantallaNotas(notas, vm::guardarNota, vm::eliminarNota, botonMenu)
            }
        }
        if (verRespaldo) DialogoRespaldo(onCerrar = { verRespaldo = false })
    }
}

/** Cambia "ocultar cantidades" en la app y también en el widget de la pantalla de inicio. */
fun cambiarOcultos(ctx: Context, scope: CoroutineScope, valor: Boolean) {
    Ajustes.cambiarSaldosOcultos(ctx, valor)
    scope.launch { FinanzasWidget().updateAll(ctx.applicationContext) }
}

@Composable
private fun Interruptor(
    titulo: String,
    detalle: String,
    @DrawableRes icono: Int,
    activo: Boolean,
    habilitado: Boolean = true,
    onCambio: (Boolean) -> Unit,
) {
    Row(
        Modifier.padding(start = 28.dp, end = 20.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(icono), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(titulo, style = MaterialTheme.typography.bodyLarge)
            Text(detalle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = activo, onCheckedChange = onCambio, enabled = habilitado)
    }
}
