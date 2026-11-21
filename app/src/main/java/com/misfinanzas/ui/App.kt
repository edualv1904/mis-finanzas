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
import com.misfinanzas.widget.FinanzasWidget
import kotlinx.coroutines.CoroutineScope
import com.misfinanzas.R
import kotlinx.coroutines.launch

private enum class Seccion(val titulo: String, @DrawableRes val icono: Int) {
    MOVIMIENTOS("Movimientos", R.drawable.ic_receipt_long),
    TARJETAS("Tarjetas", R.drawable.ic_credit_card),
    MAPA("Mapa", R.drawable.ic_location_on),
    NOTIFICACIONES("Notificaciones", R.drawable.ic_notifications),
    NOTAS("Notas e ideas", R.drawable.ic_edit_note),
}

/** Contenedor con menú lateral para moverse entre las secciones de la app. */
@Composable
fun App(abrirAjustesNotificaciones: () -> Unit, puedeBloquear: Boolean, vm: FinanzasViewModel = viewModel()) {
    val ctx = LocalContext.current
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var seccion by rememberSaveable { mutableStateOf(Seccion.MOVIMIENTOS) }
    val sinRevisar by vm.crudasSinRevisar.collectAsStateWithLifecycle()

    val botonMenu: @Composable () -> Unit = {
        IconButton(onClick = { scope.launch { drawer.open() } }) {
            BadgedBox(badge = { if (sinRevisar > 0) Badge { Text("$sinRevisar") } }) {
                Icon(Icons.Default.Menu, "Menú")
            }
        }
    }

    BackHandler(enabled = drawer.isOpen) { scope.launch { drawer.close() } }
    BackHandler(enabled = !drawer.isOpen && seccion != Seccion.MOVIMIENTOS) { seccion = Seccion.MOVIMIENTOS }

    ModalNavigationDrawer(
        drawerState = drawer,
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
                            scope.launch { drawer.close() }
                        },
                        badge = { if (s == Seccion.NOTIFICACIONES && sinRevisar > 0) Text("$sinRevisar") },
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
                Spacer(Modifier.height(12.dp))
            }
        },
    ) {
        when (seccion) {
            Seccion.MOVIMIENTOS -> PantallaPrincipal(abrirAjustesNotificaciones, botonMenu, vm)
            Seccion.TARJETAS -> PantallaTarjetas(vm, botonMenu)
            Seccion.MAPA -> PantallaMapa(vm, botonMenu)
            Seccion.NOTIFICACIONES -> {
                val crudas by vm.crudas.collectAsStateWithLifecycle()
                PantallaRecolectadas(crudas, vm::etiquetar, vm::borrarIgnoradas, botonMenu)
            }
            Seccion.NOTAS -> {
                val notas by vm.notas.collectAsStateWithLifecycle()
                PantallaNotas(notas, vm::guardarNota, vm::eliminarNota, botonMenu)
            }
        }
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
