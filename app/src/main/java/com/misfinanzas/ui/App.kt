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
import com.misfinanzas.FinanzasViewModel
import com.misfinanzas.R
import kotlinx.coroutines.launch

private enum class Seccion(val titulo: String, @DrawableRes val icono: Int) {
    MOVIMIENTOS("Movimientos", R.drawable.ic_receipt_long),
    TARJETAS("Tarjetas", R.drawable.ic_credit_card),
    NOTIFICACIONES("Notificaciones", R.drawable.ic_notifications),
    NOTAS("Notas e ideas", R.drawable.ic_edit_note),
}

/** Contenedor con menú lateral para moverse entre las secciones de la app. */
@Composable
fun App(abrirAjustesNotificaciones: () -> Unit, vm: FinanzasViewModel = viewModel()) {
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
                Seccion.entries.forEach { s ->
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
                Spacer(Modifier.height(12.dp))
            }
        },
    ) {
        when (seccion) {
            Seccion.MOVIMIENTOS -> PantallaPrincipal(abrirAjustesNotificaciones, botonMenu, vm)
            Seccion.TARJETAS -> PantallaTarjetas(vm, botonMenu)
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
