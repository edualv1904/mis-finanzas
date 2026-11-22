package com.misfinanzas

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.misfinanzas.data.Deuda
import com.misfinanzas.data.DeudaMov
import com.misfinanzas.data.FinanzasDb
import com.misfinanzas.ui.dinero
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * "Debo": lo que le debes a alguien sin fecha fija (un préstamo, clases que pagas después). Una libreta por persona:
 * lo que se suma menos lo que abonas. Como "Me deben", no cambia tus movimientos ni tu gasto.
 */
object Deudas {
    const val CARGO = "cargo"
    const val ABONO = "abono"
    const val ABRIR_DEBO = "debo"

    private const val CANAL = "debo"
    private const val PREFS = "debo"
    private const val EXTRA_DEUDA = "deuda"
    private const val EXTRA_RESPUESTA = "respuesta"

    /** El sábado desde esta hora te pregunta si fuiste (y el domingo todo el día, si no contestaste). */
    const val HORA_PREGUNTA = 18

    // ---------- Cálculos (sin Android, con pruebas) ----------

    fun saldo(movs: List<DeudaMov>): Double =
        movs.sumOf { if (it.tipo == ABONO) -it.monto else it.monto }

    fun inicioSemana(dia: LocalDate): LocalDate = dia.minusDays((dia.dayOfWeek.value - 1).toLong())

    /** El viernes o sábado de la semana de [hoy] (lunes a domingo). */
    fun diaDeSesion(hoy: LocalDate, viernes: Boolean): LocalDate =
        inicioSemana(hoy).plusDays(if (viernes) 4 else 5)

    /**
     * ¿Toca preguntar si fuiste? Sábado desde las [HORA_PREGUNTA] o domingo, si esta semana no has
     * anotado sesión ni contestado ya.
     */
    fun tocaPreguntar(ahora: LocalDateTime, semanaPreguntada: Long?, huboSesion: Boolean): Boolean {
        val dia = ahora.dayOfWeek
        val esHora = (dia == DayOfWeek.SATURDAY && ahora.hour >= HORA_PREGUNTA) || dia == DayOfWeek.SUNDAY
        return esHora && !huboSesion && semanaPreguntada != inicioSemana(ahora.toLocalDate()).toEpochDay()
    }

    fun mediodia(dia: LocalDate, zona: ZoneId = ZoneId.systemDefault()): Long =
        dia.atTime(12, 0).atZone(zona).toInstant().toEpochMilli()

    private fun diaDe(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()

    // ---------- Base de datos ----------

    suspend fun crear(ctx: Context, persona: String, debesAhora: Double?, tarifa: Double?, preguntar: Boolean) {
        val dao = FinanzasDb.get(ctx).dao()
        val id = dao.insertarDeuda(Deuda(persona = persona, tarifa = tarifa, preguntarSabado = tarifa != null && preguntar))
        if (debesAhora != null && debesAhora > 0) {
            dao.insertarDeudaMov(DeudaMov(deudaId = id, fecha = System.currentTimeMillis(), monto = debesAhora, tipo = CARGO, nota = "Lo que debías al empezar"))
        }
    }

    suspend fun anotarSesion(ctx: Context, deuda: Deuda, dia: LocalDate, monto: Double? = null): DeudaMov? {
        val costo = monto ?: deuda.tarifa ?: return null
        val m = DeudaMov(deudaId = deuda.id, fecha = mediodia(dia), monto = costo, tipo = CARGO, nota = "Sesión")
        return m.copy(id = FinanzasDb.get(ctx).dao().insertarDeudaMov(m))
    }

    // ---------- Pregunta del sábado ----------

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun marcarPreguntada(ctx: Context, deudaId: Long, dia: LocalDate) =
        prefs(ctx).edit().putLong("semana_$deudaId", inicioSemana(dia).toEpochDay()).apply()

    suspend fun preguntarSiFuiste(ctx: Context) {
        val dao = FinanzasDb.get(ctx).dao()
        val ahora = LocalDateTime.now()
        val semana = inicioSemana(ahora.toLocalDate())
        for (d in dao.deudasQuePreguntan()) {
            val movs = dao.movsDe(d.id)
            val huboSesion = movs.any { it.tipo == CARGO && it.nota == "Sesión" && !diaDe(it.fecha).isBefore(semana) }
            val preguntada = prefs(ctx).getLong("semana_${d.id}", -1).takeIf { it >= 0 }
            if (!tocaPreguntar(ahora, preguntada, huboSesion)) continue
            val debes = saldo(movs)
            notificar(
                ctx, d,
                texto = "¿Fuiste esta semana?" + if (debes > 0) " Llevas ${dinero(debes, mostrar = true)} sin pagar." else "",
                conBotones = true,
            )
            marcarPreguntada(ctx, d.id, ahora.toLocalDate())
        }
    }

    /** Contestaste desde la notificación: anota la sesión (o no) y te dice cuánto debes. */
    suspend fun contestar(ctx: Context, deudaId: Long, respuesta: String) {
        val dao = FinanzasDb.get(ctx).dao()
        val d = dao.deudaPorId(deudaId) ?: return
        val hoy = LocalDate.now()
        marcarPreguntada(ctx, d.id, hoy)
        val texto = when (respuesta) {
            "no" -> "Va, esta semana no fuiste."
            else -> {
                val dia = diaDeSesion(hoy, viernes = respuesta == "vie")
                val m = anotarSesion(ctx, d, dia)
                "Anotada la sesión del ${if (respuesta == "vie") "viernes" else "sábado"} (${dinero(m?.monto ?: 0.0, mostrar = true)})."
            }
        }
        val debes = saldo(dao.movsDe(d.id))
        notificar(ctx, d, "$texto Le debes ${dinero(debes, mostrar = true)}.", conBotones = false)
    }

    private fun notificar(ctx: Context, d: Deuda, texto: String, conBotones: Boolean) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CANAL, "Debo", NotificationManager.IMPORTANCE_DEFAULT)
        )
        val abrir = PendingIntent.getActivity(
            ctx, 300 + d.id.toInt(),
            Intent(ctx, MainActivity::class.java)
                .putExtra(Cobros.EXTRA_ABRIR, ABRIR_DEBO)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val b = NotificationCompat.Builder(ctx, CANAL)
            .setSmallIcon(R.drawable.ic_notificacion)
            .setContentTitle(d.persona)
            .setContentText(texto)
            .setStyle(NotificationCompat.BigTextStyle().bigText(texto))
            .setContentIntent(abrir)
            .setAutoCancel(true)
        if (conBotones) {
            listOf("vie" to "Sí, el viernes", "sab" to "Sí, el sábado", "no" to "No fui").forEachIndexed { i, (r, etiqueta) ->
                val accion = PendingIntent.getBroadcast(
                    ctx, 400 + d.id.toInt() * 10 + i,
                    Intent(ctx, RespuestaDeudaReceiver::class.java).putExtra(EXTRA_DEUDA, d.id).putExtra(EXTRA_RESPUESTA, r),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                b.addAction(0, etiqueta, accion)
            }
        }
        NotificationManagerCompat.from(ctx).notify(CANAL.hashCode() + d.id.toInt(), b.build())
    }

    fun programar(ctx: Context) {
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
            "debo",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<DeudasWorker>(2, TimeUnit.HOURS).build(),
        )
    }

    internal fun leer(intent: Intent) = intent.getLongExtra(EXTRA_DEUDA, -1) to intent.getStringExtra(EXTRA_RESPUESTA)
}

class DeudasWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        Deudas.preguntarSiFuiste(applicationContext)
        return Result.success()
    }
}

/** Los botones "Sí, el viernes / Sí, el sábado / No fui" de la notificación del sábado. */
class RespuestaDeudaReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val (deudaId, respuesta) = Deudas.leer(intent)
        if (deudaId < 0 || respuesta == null) return
        val pendiente = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Deudas.contestar(ctx.applicationContext, deudaId, respuesta)
            } finally {
                pendiente.finish()
            }
        }
    }
}
