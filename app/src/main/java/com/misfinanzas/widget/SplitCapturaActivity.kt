package com.misfinanzas.widget

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.lifecycle.lifecycleScope
import com.misfinanzas.Grupos
import com.misfinanzas.data.FinanzasDb
import com.misfinanzas.ui.DatosGrupos
import com.misfinanzas.ui.DialogoGastoGrupo
import com.misfinanzas.ui.Tema
import com.misfinanzas.ui.dinero
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Ventana flotante del botón "+ Gasto" del widget de split: el mismo diálogo de gasto compartido que
 * en la app (cuánto, qué fue, quién pagó, entre quiénes, foto del ticket), sin abrir la app ni pedir huella.
 */
class SplitCapturaActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val grupoId = intent.getLongExtra(EXTRA_GRUPO, -1)
        val dao = FinanzasDb.get(this).dao()

        setContent {
            Tema {
                val datos by produceState<DatosGrupos?>(null) {
                    value = DatosGrupos(dao.gruposTodos(), dao.miembrosTodos(), dao.grupoGastosTodos(), dao.partesTodas())
                }
                val d = datos ?: return@Tema
                val g = d.grupos.firstOrNull { it.id == grupoId && it.activo }
                if (g == null) {
                    androidx.compose.runtime.LaunchedEffect(Unit) {
                        Toast.makeText(applicationContext, "Ese split ya no está activo.", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                    return@Tema
                }
                DialogoGastoGrupo(
                    grupos = listOf(g), datos = d, inicial = null, transaccion = null,
                    onGuardar = { x, partes, fotos ->
                        lifecycleScope.launch {
                            withContext(Dispatchers.IO) { Grupos.guardarGasto(applicationContext, x, partes, fotos) }
                            SplitWidget.refrescar(applicationContext)
                            Toast.makeText(applicationContext, "Guardado en ${g.nombre}: ${dinero(x.monto, mostrar = true)}", Toast.LENGTH_SHORT).show()
                            finish()
                        }
                    },
                    onCerrar = { finish() },
                )
            }
        }
    }

    companion object {
        const val EXTRA_GRUPO = "grupo"
    }
}
