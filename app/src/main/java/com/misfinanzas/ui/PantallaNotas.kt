package com.misfinanzas.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import com.misfinanzas.data.Nota
import kotlinx.coroutines.delay

/** Ideas que apuntas fuera de casa para platicarlas después. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PantallaNotas(
    notas: List<Nota>,
    onGuardar: (Nota) -> Unit,
    onEliminar: (Nota) -> Unit,
    botonMenu: @Composable () -> Unit,
) {
    var editando by remember { mutableStateOf<Nota?>(null) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Notas e ideas") }, navigationIcon = botonMenu) },
        floatingActionButton = {
            FloatingActionButton(onClick = { editando = Nota(texto = "", fecha = System.currentTimeMillis()) }) {
                Icon(Icons.Default.Add, "Nueva nota")
            }
        },
    ) { padding ->
        if (notas.isEmpty()) {
            Text(
                "Apunta aquí lo que se te ocurra para la app y lo revisamos cuando nos conectemos.",
                Modifier.padding(padding).padding(32.dp),
            )
        }
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 88.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(notas, key = { it.id }) { n ->
                Card(Modifier.fillMaxWidth().clickable { editando = n }) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            "${textoDia(diaDe(n.fecha))} ${hora(n.fecha)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(n.texto, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
    }

    editando?.let { n ->
        DialogoNota(
            inicial = n,
            onGuardar = { texto -> onGuardar(n.copy(texto = texto)); editando = null },
            onEliminar = { onEliminar(n); editando = null },
            onCerrar = { editando = null },
        )
    }
}

@Composable
private fun DialogoNota(inicial: Nota, onGuardar: (String) -> Unit, onEliminar: () -> Unit, onCerrar: () -> Unit) {
    val esNueva = inicial.id == 0L
    var texto by remember { mutableStateOf(inicial.texto) }
    val foco = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(150)
        foco.requestFocus()
    }

    AlertDialog(
        onDismissRequest = onCerrar,
        title = { Text(if (esNueva) "Nueva nota" else "Editar nota") },
        text = {
            OutlinedTextField(
                texto, { texto = it },
                Modifier.fillMaxWidth().heightIn(min = 140.dp).focusRequester(foco),
                placeholder = { Text("¿Qué se te ocurrió?") },
            )
        },
        confirmButton = {
            TextButton(enabled = texto.isNotBlank(), onClick = { onGuardar(texto.trim()) }) { Text("Guardar") }
        },
        dismissButton = {
            Row {
                if (!esNueva) TextButton(onClick = onEliminar) { Text("Eliminar", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onCerrar) { Text("Cancelar") }
            }
        },
    )
}
