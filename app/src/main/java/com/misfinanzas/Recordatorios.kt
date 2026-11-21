package com.misfinanzas

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.misfinanzas.data.FinanzasDb
import com.misfinanzas.ui.dinero
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/** Revisa una vez al día si alguna tarjeta está por llegar a su fecha límite de pago. */
class RecordatorioPagoWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val dao = FinanzasDb.get(applicationContext).dao()
        val hoy = LocalDate.now()
        val zona = ZoneId.systemDefault()
        fun ms(d: LocalDate) = d.atStartOfDay(zona).toInstant().toEpochMilli()

        for (t in dao.tarjetasConfig()) {
            if (t.avisarDiasAntes <= 0) continue
            val ciclo = Ciclos.para(t.diaCorte, t.diaLimite, hoy)
            if (t.pagadoCorte == ciclo.ultimoCorte.toEpochDay()) continue
            val dias = Ciclos.diasEntre(hoy, ciclo.limitePago)
            if (dias != t.avisarDiasAntes.toLong() && dias != 0L) continue

            val monto = dao.sumaGastosTarjeta(
                t.nombre,
                ms(ciclo.periodoCerrado.start),
                ms(ciclo.periodoCerrado.endInclusive.plusDays(1)),
            )
            val cuando = if (dias == 0L) "hoy" else "en $dias días (${ciclo.limitePago.dayOfMonth})"
            notificar(t.nombre.hashCode(), "Paga tu ${t.nombre}", "Vence $cuando · registrado ~${dinero(monto, mostrar = true)}")
        }
        return Result.success()
    }

    private fun notificar(id: Int, titulo: String, texto: String) {
        val ctx = applicationContext
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU
        ) return

        val abrir = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(ctx, CANAL)
            .setSmallIcon(android.R.drawable.ic_menu_agenda)
            .setContentTitle(titulo)
            .setContentText(texto)
            .setContentIntent(abrir)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(ctx).notify(id, n)
    }

    companion object {
        private const val CANAL = "recordatorios_pago"

        /** Crea el canal y agenda la revisión diaria; es seguro llamarlo en cada arranque. */
        fun programar(ctx: Context) {
            ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CANAL, "Recordatorios de pago", NotificationManager.IMPORTANCE_DEFAULT)
            )
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                "recordatorio_pago",
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<RecordatorioPagoWorker>(1, TimeUnit.DAYS).build(),
            )
        }
    }
}
