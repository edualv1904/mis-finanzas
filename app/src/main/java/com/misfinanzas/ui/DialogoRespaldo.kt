package com.misfinanzas.ui

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.misfinanzas.Respaldo
import com.google.android.gms.auth.api.identity.Identity
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val fmtRespaldo = DateTimeFormatter.ofPattern("EEEE d 'de' MMMM, HH:mm", Locale("es", "MX"))

/** Texto corto para el menú: cuándo fue el último respaldo. */
fun resumenRespaldo(): String = when {
    !Respaldo.conectado -> "Sin conectar"
    Respaldo.error != null -> "Revisar: falló el último intento"
    Respaldo.ultimo == 0L -> "Aún no hay respaldo"
    else -> "Último: " + Instant.ofEpochMilli(Respaldo.ultimo).atZone(ZoneId.systemDefault()).format(fmtRespaldo)
}

@Composable
fun DialogoRespaldo(onCerrar: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var aviso by remember { mutableStateOf<String?>(null) }
    var buscandoRed by remember { mutableStateOf(false) }
    val apagado = MaterialTheme.colorScheme.onSurfaceVariant

    fun respaldarCon(token: String) {
        scope.launch {
            aviso = Respaldo.respaldar(ctx, token) ?: "Listo: respaldo subido a tu Drive."
        }
    }

    val permiso = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        if (r.resultCode != Activity.RESULT_OK) {
            aviso = "No se dio el permiso."
            return@rememberLauncherForActivityResult
        }
        val res = runCatching { Identity.getAuthorizationClient(ctx).getAuthorizationResultFromIntent(r.data) }.getOrNull()
        val token = res?.accessToken
        if (token == null) {
            aviso = "Google no entregó el permiso. Intenta de nuevo."
        } else {
            Respaldo.marcarConectado(ctx, true)
            respaldarCon(token)
        }
    }

    /** Pide el permiso (con ventana de Google si hace falta) y respalda en ese momento. */
    fun conectarYRespaldar() {
        aviso = null
        Respaldo.pedirPermiso(ctx) { r, e ->
            when {
                e != null -> aviso = "No se pudo conectar con Google: ${e.message}"
                r == null -> aviso = "No se pudo conectar con Google."
                r.hasResolution() -> permiso.launch(IntentSenderRequest.Builder(r.pendingIntent!!.intentSender).build())
                else -> {
                    Respaldo.marcarConectado(ctx, true)
                    r.accessToken?.let(::respaldarCon)
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = onCerrar,
        title = { Text("Respaldo en Drive") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    "Cada sábado, cuando estés en el WiFi de casa, se sube una copia de tus movimientos a la carpeta " +
                        "\"${Respaldo.NOMBRE_CARPETA}\" de tu Drive. Se guardan las últimas ${Respaldo.CONSERVAR} semanas.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Column {
                    Text("Último respaldo", style = MaterialTheme.typography.labelLarge)
                    Text(
                        if (Respaldo.trabajando) "Subiendo…" else resumenRespaldo(),
                        style = MaterialTheme.typography.bodyMedium, color = apagado,
                    )
                }
                Column {
                    Text("WiFi de casa", style = MaterialTheme.typography.labelLarge)
                    Text(
                        Respaldo.redCasa ?: "Sin elegir: conéctate al WiFi de tu casa y toca el botón",
                        style = MaterialTheme.typography.bodyMedium, color = apagado,
                    )
                    OutlinedButton(enabled = !buscandoRed, onClick = {
                        buscandoRed = true
                        aviso = null
                        scope.launch {
                            val red = Respaldo.redActual(ctx)
                            if (red == null) aviso = "No veo a qué WiFi estás conectado. Revisa que el WiFi y la ubicación estén encendidos."
                            else Respaldo.ponerRedCasa(ctx, red)
                            buscandoRed = false
                        }
                    }) { Text("Usar el WiFi actual") }
                }
                (aviso ?: Respaldo.error)?.let {
                    Text(
                        it, style = MaterialTheme.typography.bodySmall,
                        color = if (it.startsWith("Listo")) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            Button(enabled = !Respaldo.trabajando, onClick = ::conectarYRespaldar) {
                Text(if (Respaldo.conectado) "Respaldar ahora" else "Conectar Drive")
            }
        },
        dismissButton = { TextButton(onClick = onCerrar) { Text("Cerrar") } },
    )
}
