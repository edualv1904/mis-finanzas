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
) {
    val esNueva = inicial.id == 0L
    var monto by remember { mutableStateOf(if (esNueva) "" else "%.2f".format(inicial.monto)) }
    var comercio by remember { mutableStateOf(inicial.comercio) }
    var tarjeta by remember { mutableStateOf(inicial.tarjeta) }
    var categoria by remember { mutableStateOf(inicial.categoria) }
    var tipo by remember { mutableStateOf(inicial.tipo) }
    var nota by remember { mutableStateOf(inicial.nota) }

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
