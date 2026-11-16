package com.misfinanzas

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.misfinanzas.data.FinanzasDb
import com.misfinanzas.ui.DialogoTransaccion
import com.misfinanzas.ui.Tema
import com.misfinanzas.ui.dinero
import com.misfinanzas.ui.nuevaTransaccion
import kotlinx.coroutines.launch

/** Ventana flotante para capturar un gasto desde el widget, sin abrir la app completa. */
class CapturaRapidaActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val inicial = nuevaTransaccion(intent.getStringExtra(EXTRA_CATEGORIA) ?: "Otros")
        val dao = FinanzasDb.get(this).dao()

        setContent {
            Tema {
                val tarjetas by dao.observarTarjetas().collectAsStateWithLifecycle(emptyList())
                DialogoTransaccion(
                    inicial = inicial,
                    tarjetasConocidas = tarjetas,
                    onGuardar = { t ->
                        lifecycleScope.launch {
                            Registro.guardar(applicationContext, t, recordarCategoria = t.categoria != "Otros")
                            Toast.makeText(applicationContext, "Guardado: ${dinero(t.monto)} en ${t.comercio}", Toast.LENGTH_SHORT).show()
                            finish()
                        }
                    },
                    onEliminar = {},
                    onCerrar = { finish() },
                )
            }
        }
    }

    companion object {
        const val EXTRA_CATEGORIA = "categoria"
    }
}
