package com.misfinanzas

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.misfinanzas.data.FinanzasDb
import com.misfinanzas.data.NotificacionCruda
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Escucha las notificaciones de Google Wallet y de los bancos para registrar cada pago. */
class WalletListener : NotificationListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onListenerConnected() {
        // Recoge pagos cuya notificación siga visible pero que llegaron mientras no escuchábamos.
        activeNotifications?.forEach(::procesar)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) = procesar(sbn)

    private fun procesar(sbn: StatusBarNotification) {
        if (!Recoleccion.vigila(sbn.packageName)) return
        val extras = sbn.notification.extras
        val titulo = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val texto = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        val fecha = sbn.notification.`when`.takeIf { it > 0 } ?: sbn.postTime
        val fuente = Fuentes.para(sbn.packageName)
        val pago = fuente?.parse(titulo, texto)
        if (fuente == null || pago == null) {
            Log.i(TAG, "Notificación de ${sbn.packageName} no reconocida: '$titulo' / '$texto'")
            if (titulo.isNotBlank() || texto.isNotBlank()) {
                scope.launch {
                    val dao = FinanzasDb.get(applicationContext).dao()
                    dao.guardarCruda(NotificacionCruda(paquete = sbn.packageName, titulo = titulo, texto = texto, fecha = fecha))
                    dao.recortarCrudas()
                }
            }
            return
        }
        val clave = Claves.de(fuente.id, sbn.key, titulo, texto)

        scope.launch {
            val resultado = Registro.registrarPago(applicationContext, pago, fuente.id, fecha, clave)
            Log.i(TAG, "$resultado: $pago")
            if (resultado == Registro.Resultado.NUEVO) Ubicacion.anotar(applicationContext, clave, fecha)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "WalletListener"
    }
}
