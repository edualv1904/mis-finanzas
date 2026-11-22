package com.misfinanzas

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
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
import com.misfinanzas.data.Acuerdo
import com.misfinanzas.data.Cobro
import com.misfinanzas.data.FinanzasDb
import com.misfinanzas.ui.dinero
import java.time.YearMonth
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max

/**
 * "Me deben": cobros mensuales a otras personas. Es una lista de recordatorios aparte: no cambia
 * tus movimientos ni lo que llevas gastado.
 */
object Cobros {
    private const val CANAL = "cobros"
    const val EXTRA_ABRIR = "abrir"
    const val ABRIR_COBROS = "cobros"

    /** Días de gracia después de la fecha antes de avisarte, y cada cuánto repetir el aviso. */
    const val GRACIA_DIAS = 3
    const val REPETIR_DIAS = 7
    private const val DIA_MS = 86_400_000L

    /** abonoId de un cobro que marcaste "ya no me lo debe" (sin pago de por medio). */
    const val PERDONADO = -1L

    // ---------- Cálculos (sin Android, con pruebas) ----------

    /** Meses (y su número) que ya deberían existir hasta [hasta], respetando el total del acuerdo. */
    fun mesesHasta(desde: YearMonth, meses: Int?, hasta: YearMonth): List<Pair<YearMonth, Int>> {
        if (hasta < desde) return emptyList()
        val n = (hasta.year - desde.year) * 12 + (hasta.monthValue - desde.monthValue) + 1
        val tope = if (meses == null) n else minOf(n, meses)
        return (0 until tope).map { k -> desde.plusMonths(k.toLong()) to (k + 1) }
    }

    /** "Una vez": el mes en que cae ese día (este mes si no ha pasado, si no el siguiente). */
    fun mesDeUnaVez(hoy: java.time.LocalDate, dia: Int): YearMonth =
        if (dia >= hoy.dayOfMonth) YearMonth.from(hoy) else YearMonth.from(hoy).plusMonths(1)

    /** Si este mes va en la mensualidad [actual], el acuerdo empezó [actual - 1] meses antes. */
    fun primerMes(hoy: YearMonth, actual: Int): YearMonth = hoy.minusMonths((actual - 1).coerceAtLeast(0).toLong())

    fun vence(mes: YearMonth, dia: Int, zona: ZoneId = ZoneId.systemDefault()): Long =
        mes.atDay(dia.coerceIn(1, mes.lengthOfMonth())).atTime(12, 0).atZone(zona).toInstant().toEpochMilli()

    /** Una transferencia "es" el pago si el monto casi coincide (redondeos, comisión de $1–2). */
    fun coincide(abono: Double, cobro: Double): Boolean = abs(abono - cobro) <= max(2.0, cobro * 0.02)

    /** Varios pagos en un solo depósito: al menos dos y que la suma casi coincida. */
    fun cubreVarios(abono: Double, seleccion: Collection<Cobro>): Boolean =
        seleccion.size >= 2 && coincide(abono, seleccion.sumOf { it.monto })

    /**
     * Una pareja te manda $80 por sus dos partes de Spotify: pendientes del mismo concepto y mes entre
     * los que alguna combinación de dos o más suma el depósito. No adivina quiénes (con montos iguales
     * cualquier par sirve): devuelve el grupo completo y tú eliges.
     */
    fun gruposParaDeposito(abono: Double, pendientes: List<Cobro>, conceptoDe: (Cobro) -> String?): List<List<Cobro>> =
        pendientes
            .groupBy { (conceptoDe(it) ?: "").trim().lowercase() to it.mes }
            .values
            .filter { g -> g.size in 2..12 && hayCombinacion(abono, g) }

    private fun hayCombinacion(abono: Double, g: List<Cobro>): Boolean =
        (1 until (1 shl g.size)).any { bits ->
            cubreVarios(abono, g.filterIndexed { i, _ -> bits and (1 shl i) != 0 })
        }

    fun atrasado(c: Cobro, ahora: Long): Boolean = c.pagado == null && ahora - c.vence > GRACIA_DIAS * DIA_MS

    fun toca(c: Cobro, ahora: Long): Boolean =
        atrasado(c, ahora) && (c.ultimoAviso == null || ahora - c.ultimoAviso >= REPETIR_DIAS * DIA_MS)

    // ---------- Base de datos ----------

    /** Crea los cobros que falten hasta este mes para cada acuerdo activo. Seguro de repetir. */
    suspend fun generar(ctx: Context) {
        val dao = FinanzasDb.get(ctx).dao()
        val hoy = YearMonth.now()
        for (a in dao.acuerdosActivos()) {
            val desde = YearMonth.parse(a.desde)
            // Un acuerdo que empieza en un mes futuro (un préstamo que te pagan el mes que entra) ya se ve.
            val nuevos = mesesHasta(desde, a.meses, maxOf(hoy, desde)).map { (mes, k) ->
                Cobro(acuerdoId = a.id, mes = mes.toString(), numero = k, monto = a.monto, vence = vence(mes, a.dia))
            }
            dao.insertarCobros(nuevos)
            // Acuerdo a meses ya completo y pagado: se archiva solo.
            if (a.meses != null && dao.cobrosDe(a.id).let { c -> c.size >= a.meses && c.all { it.pagado != null } }) {
                dao.actualizarAcuerdo(a.copy(activo = false))
            }
        }
    }

    /**
     * Da de alta un acuerdo por persona. [actual] es en qué mensualidad va este mes (1 si empieza hoy);
     * las anteriores se marcan pagadas, y la de este mes también si [esteMesPagado].
     */
    suspend fun crear(
        ctx: Context,
        personas: List<String>,
        concepto: String,
        monto: Double,
        dia: Int,
        meses: Int?,
        actual: Int,
        esteMesPagado: Boolean,
        desdeFijo: YearMonth? = null,
    ) {
        val dao = FinanzasDb.get(ctx).dao()
        val desde = desdeFijo ?: primerMes(YearMonth.now(), actual)
        for (p in personas) {
            val id = dao.insertarAcuerdo(Acuerdo(persona = p, concepto = concepto, monto = monto, dia = dia, desde = desde.toString(), meses = meses))
            generar(ctx)
            for (c in dao.cobrosDe(id)) {
                if (c.numero < actual || (c.numero == actual && esteMesPagado)) dao.marcarCobro(c.id, c.vence, null)
            }
        }
        generar(ctx)
    }

    /** Cambiaste un acuerdo: lo pendiente toma el monto y día nuevos; lo pagado no se toca. */
    suspend fun editar(ctx: Context, a: Acuerdo) {
        val dao = FinanzasDb.get(ctx).dao()
        dao.actualizarAcuerdo(a)
        a.meses?.let { dao.borrarCobrosDespuesDe(a.id, it) }
        for (c in dao.cobrosDe(a.id).filter { it.pagado == null }) {
            dao.ajustarCobro(c.id, a.monto, vence(YearMonth.parse(c.mes), a.dia))
        }
        generar(ctx)
    }

    /** Llegó una transferencia: si coincide con algo pendiente, te pregunta si es ese pago. */
    suspend fun alRecibirAbono(ctx: Context, monto: Double) {
        val dao = FinanzasDb.get(ctx).dao()
        val acuerdos = dao.observarAcuerdosUnaVez()
        val pendientes = dao.cobrosPendientes()
        val candidatos = pendientes.filter { coincide(monto, it.monto) }
            .ifEmpty { gruposParaDeposito(monto, pendientes) { acuerdos[it.acuerdoId]?.concepto }.flatten() }
        if (candidatos.isEmpty()) return
        val nombres = candidatos.mapNotNull { c -> acuerdos[c.acuerdoId]?.let { "${it.persona} (${it.concepto})" } }.distinct()
        notificar(
            ctx, 2,
            "¿Te pagaron? Llegó ${dinero(monto, mostrar = true)}",
            "Puede ser de ${nombres.take(3).joinToString(", ")}. Tócalo para marcarlo en Me deben.",
        )
    }

    /** Aviso aparte (no es el recordatorio de tarjetas): quién lleva días sin pagarte. */
    suspend fun avisarAtrasados(ctx: Context) {
        val dao = FinanzasDb.get(ctx).dao()
        val ahora = System.currentTimeMillis()
        val pendientes = dao.cobrosPendientes()
        if (pendientes.none { toca(it, ahora) }) return
        val atrasados = pendientes.filter { atrasado(it, ahora) }
        val acuerdos = dao.observarAcuerdosUnaVez()
        val total = atrasados.sumOf { it.monto }
        val quienes = atrasados.mapNotNull { acuerdos[it.acuerdoId]?.persona }.distinct()
        notificar(
            ctx, 1,
            "Te deben ${dinero(total, mostrar = true)}",
            quienes.take(4).joinToString(", ") + if (quienes.size > 4) " y ${quienes.size - 4} más" else "",
        )
        dao.cobrosAvisados(atrasados.map { it.id }, ahora)
    }

    private suspend fun com.misfinanzas.data.TransaccionDao.observarAcuerdosUnaVez(): Map<Long, Acuerdo> =
        acuerdosTodos().associateBy { it.id }

    private fun notificar(ctx: Context, id: Int, titulo: String, texto: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CANAL, "Me deben", NotificationManager.IMPORTANCE_DEFAULT)
        )
        val abrir = PendingIntent.getActivity(
            ctx, 10 + id,
            Intent(ctx, MainActivity::class.java)
                .putExtra(EXTRA_ABRIR, ABRIR_COBROS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(ctx, CANAL)
            .setSmallIcon(R.drawable.ic_notificacion)
            .setContentTitle(titulo)
            .setContentText(texto)
            .setStyle(NotificationCompat.BigTextStyle().bigText(texto))
            .setContentIntent(abrir)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(ctx).notify(CANAL.hashCode() + id, n)
    }

    fun programar(ctx: Context) {
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
            "cobros",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<CobrosWorker>(12, TimeUnit.HOURS).build(),
        )
    }
}

class CobrosWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        Cobros.generar(applicationContext)
        Cobros.avisarAtrasados(applicationContext)
        return Result.success()
    }
}
