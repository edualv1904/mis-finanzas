package com.misfinanzas.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.misfinanzas.Personas

/** Si conoces a más de esta gente, sin escribir nada se ven solo las primeras. */
private const val MAX_SIN_BUSCAR = 12

/**
 * Para elegir a quién: la gente que ya tienes (Me deben, Debo, Grupos) como fichas que se tocan, igual
 * que las categorías. Al escribir se filtran y sugiere la parecida ("Anaa" → Ana), para no terminar
 * con cuatro Rebes; si es alguien nuevo, "Agregar «nombre»". Con [varias] se eligen varias personas.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CampoPersonas(
    elegidas: List<String>,
    onCambio: (List<String>) -> Unit,
    conocidas: List<String>,
    varias: Boolean,
    etiqueta: String = "Quién",
    ayuda: String? = null,
    /** Nombres que no se pueden quitar (ya tienen gastos en un grupo). */
    fijas: Set<String> = emptySet(),
    /** No ofrecer estos (p. ej. "Yo" en los grupos). */
    excluir: Set<String> = emptySet(),
    /** Lo que va escrito sin elegir todavía, para no perderlo si tocan Guardar directo. */
    onEscribiendo: (String) -> Unit = {},
) {
    var texto by remember { mutableStateOf("") }
    val clave = Personas::clave
    val excluidas = excluir.map(clave).toSet()
    val elegidasClaves = elegidas.map(clave).toSet()
    // Las elegidas siempre a la vista (primero), luego las que coinciden con lo escrito.
    val sugeridas = Personas.sugerir(texto, conocidas).filter { clave(it) !in excluidas && clave(it) !in elegidasClaves }
    val visibles = if (texto.isBlank()) sugeridas.take(MAX_SIN_BUSCAR) else sugeridas
    val nueva = texto.trim().takeIf { t -> t.isNotEmpty() && conocidas.none { clave(it) == clave(t) } && clave(t) !in excluidas && clave(t) !in elegidasClaves }

    val tocar = { nombre: String ->
        val ya = clave(nombre) in elegidasClaves
        when {
            ya && nombre in fijas -> Unit
            ya -> onCambio(elegidas.filter { clave(it) != clave(nombre) })
            varias -> onCambio(elegidas + nombre)
            else -> onCambio(listOf(nombre))
        }
        texto = ""
        onEscribiendo("")
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(etiqueta, style = MaterialTheme.typography.labelLarge)
        OutlinedTextField(
            texto,
            { texto = it; onEscribiendo(it) },
            Modifier.fillMaxWidth(),
            placeholder = { Text(if (conocidas.isEmpty()) "Nombre" else "Buscar o escribir alguien nuevo") },
            singleLine = true,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            (elegidas + visibles).forEach { p ->
                val elegida = clave(p) in elegidasClaves
                FilterChip(
                    selected = elegida,
                    onClick = { tocar(p) },
                    label = { Text(p) },
                    leadingIcon = if (elegida) {
                        { Icon(Icons.Default.Check, null, Modifier.size(FilterChipDefaults.IconSize)) }
                    } else null,
                )
            }
            if (nueva != null) AssistChip(
                onClick = { tocar(nueva) },
                label = { Text("Agregar «$nueva»") },
                leadingIcon = { Icon(Icons.Default.Add, null, Modifier.size(FilterChipDefaults.IconSize)) },
            )
        }
        if (texto.isBlank() && sugeridas.size > visibles.size) Text(
            "Escribe para ver a los demás (${sugeridas.size - visibles.size} más).",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (texto.isNotBlank() && visibles.isNotEmpty() && nueva != null) Text(
            "¿Es alguna de estas? Si no, agrégala como nueva.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ayuda?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

/** Las elegidas más lo que quedó escrito (con el nombre ya conocido si es la misma persona). */
fun conEscrito(elegidas: List<String>, escrito: String, conocidas: List<String>, varias: Boolean = true): List<String> {
    val t = escrito.trim()
    if (t.isEmpty()) return elegidas
    val nombre = conocidas.firstOrNull { Personas.clave(it) == Personas.clave(t) }?.trim() ?: t
    if (!varias) return listOf(nombre)
    return if (elegidas.any { Personas.clave(it) == Personas.clave(nombre) }) elegidas else elegidas + nombre
}
