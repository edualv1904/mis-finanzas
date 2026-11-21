package com.misfinanzas

import android.app.KeyguardManager
import android.content.Intent
import android.graphics.Color
import android.hardware.biometrics.BiometricManager.Authenticators
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.misfinanzas.ui.App
import com.misfinanzas.ui.PantallaBloqueo
import com.misfinanzas.ui.Tema

class MainActivity : ComponentActivity() {

    /** Mientras sea true no se dibuja nada de la app, solo la pantalla de bloqueo. */
    private var bloqueada by mutableStateOf(false)
    private var autenticando = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ajustes.cargar(applicationContext)
        val barras = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = barras, navigationBarStyle = barras)
        // Girar la pantalla recrea la actividad: no debe volver a pedir la huella.
        bloqueada = debeBloquear() && !desbloqueada
        // Lo que etiquetaste en "Notificaciones" y ya sabemos leer pasa a tus movimientos.
        lifecycleScope.launch(Dispatchers.IO) {
            Registro.importarArchivo(applicationContext)
            Registro.importarCrudasEtiquetadas(applicationContext)
            Ubicacion.completarDirecciones(applicationContext)
        }
        RecordatorioPagoWorker.programar(applicationContext)
        setContent {
            Tema {
                if (bloqueada) {
                    PantallaBloqueo(onDesbloquear = ::pedirIdentidad)
                } else {
                    App(
                        abrirAjustesNotificaciones = {
                            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                        },
                        puedeBloquear = puedeBloquear(),
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Si saliste un rato de la app, al volver hay que identificarse otra vez.
        if (!bloqueada && salida != 0L && SystemClock.elapsedRealtime() - salida > GRACIA_MS && debeBloquear()) {
            bloqueada = true
            desbloqueada = false
        }
        if (bloqueada) pedirIdentidad()
    }

    override fun onResume() {
        super.onResume()
        // Que la vista previa de "recientes" no enseñe tus cantidades.
        if (Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(!Ajustes.pedirHuella)
    }

    override fun onStop() {
        super.onStop()
        // La pantalla del PIN es otra actividad: no cuenta como haber salido.
        if (!autenticando && !isChangingConfigurations) salida = SystemClock.elapsedRealtime()
    }

    private fun puedeBloquear(): Boolean =
        Build.VERSION.SDK_INT >= 28 && getSystemService(KeyguardManager::class.java).isDeviceSecure

    private fun debeBloquear() = Ajustes.pedirHuella && puedeBloquear()

    private fun pedirIdentidad() {
        if (autenticando) return
        if (!puedeBloquear()) { bloqueada = false; return }
        autenticando = true
        val b = BiometricPrompt.Builder(this)
            .setTitle("Mis Finanzas")
            .setSubtitle("Confirma que eres tú")
        when {
            Build.VERSION.SDK_INT >= 30 -> b.setAllowedAuthenticators(Authenticators.BIOMETRIC_WEAK or Authenticators.DEVICE_CREDENTIAL)
            Build.VERSION.SDK_INT >= 29 -> @Suppress("DEPRECATION") b.setDeviceCredentialAllowed(true)
            else -> b.setNegativeButton("Cancelar", mainExecutor) { _, _ -> autenticando = false }
        }
        b.build().authenticate(CancellationSignal(), mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                autenticando = false
                bloqueada = false
                desbloqueada = true
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                // Cancelado o demasiados intentos: se queda bloqueada con el botón para reintentar.
                autenticando = false
            }
        })
    }

    private companion object {
        /** Salir menos de esto (contestar un mensaje, copiar algo) no vuelve a pedir la huella. */
        const val GRACIA_MS = 60_000L
        /** Vive con el proceso: si Android cierra la app, al abrirla se pide de nuevo. */
        var salida = 0L
        var desbloqueada = false
    }
}
