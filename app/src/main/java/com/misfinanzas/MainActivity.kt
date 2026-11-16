package com.misfinanzas

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.misfinanzas.ui.App
import com.misfinanzas.ui.Tema

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val barras = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = barras, navigationBarStyle = barras)
        // Lo que etiquetaste en "Notificaciones" y ya sabemos leer pasa a tus movimientos.
        lifecycleScope.launch(Dispatchers.IO) {
            Registro.importarArchivo(applicationContext)
            Registro.importarCrudasEtiquetadas(applicationContext)
        }
        RecordatorioPagoWorker.programar(applicationContext)
        setContent {
            Tema {
                App(
                    abrirAjustesNotificaciones = {
                        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    }
                )
            }
        }
    }
}
