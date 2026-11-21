package com.misfinanzas

import com.misfinanzas.data.Transaccion
import java.time.YearMonth
import java.time.ZoneId

/**
 * Compras a meses sin intereses. La compra original se queda con tipo [Tipos.MSI] (no suma, pero
 * conserva sus claves para que ni la notificación ni el estado de cuenta la vuelvan a meter) y cada
 * mensualidad es un gasto normal con claveUnica "msi|<id de la compra>|<k>", fechado el día límite de
 * pago de la tarjeta en ese mes.
 */
object Meses {
    const val FUENTE = "msi"

    fun prefijo(idCompra: Long) = "msi|$idCompra|"

    fun idCompra(claveUnica: String?): Long? =
        claveUnica?.takeIf { it.startsWith("msi|") }?.split("|")?.getOrNull(1)?.toLongOrNull()

    /** Reparte en centavos: 5,528.30 / 3 = 1,842.77 + 1,842.77 + 1,842.76. */
    fun montos(total: Double, meses: Int): List<Double> {
        val centavos = Math.round(total * 100)
        val base = centavos / meses
        val sobran = (centavos % meses).toInt()
        return List(meses) { k -> (base + if (k < sobran) 1 else 0) / 100.0 }
    }

    /** Mediodía del día límite de pago en cada mes (o el último día si el mes es más corto). */
    fun fechas(primerMes: YearMonth, meses: Int, diaLimite: Int, zona: ZoneId = ZoneId.systemDefault()): List<Long> =
        List(meses) { k ->
            val mes = primerMes.plusMonths(k.toLong())
            mes.atDay(diaLimite.coerceIn(1, mes.lengthOfMonth())).atTime(12, 0).atZone(zona).toInstant().toEpochMilli()
        }

    fun mensualidades(
        compra: Transaccion,
        meses: Int,
        primerMes: YearMonth,
        diaLimite: Int,
        zona: ZoneId = ZoneId.systemDefault(),
    ): List<Transaccion> {
        val fechas = fechas(primerMes, meses, diaLimite, zona)
        return montos(compra.monto, meses).mapIndexed { k, monto ->
            Transaccion(
                monto = monto,
                moneda = compra.moneda,
                comercio = compra.comercio,
                tarjeta = compra.tarjeta,
                fecha = fechas[k],
                categoria = compra.categoria,
                fuente = FUENTE,
                claveUnica = prefijo(compra.id) + (k + 1),
                nota = "${k + 1} de $meses",
                tipo = Tipos.GASTO,
            )
        }
    }

    /** Una compra a meses con sus mensualidades en orden. */
    data class Plan(val compra: Transaccion?, val mensualidades: List<Transaccion>) {
        val total get() = compra?.monto ?: mensualidades.sumOf { it.monto }
        val tarjeta get() = compra?.tarjeta ?: mensualidades.first().tarjeta
        val comercio get() = compra?.comercio ?: mensualidades.first().comercio
    }

    /** Agrupa lo que regresa [com.misfinanzas.data.TransaccionDao.observarMeses]. */
    fun agrupar(lista: List<Transaccion>): List<Plan> {
        val compras = lista.filter { it.tipo == Tipos.MSI }.associateBy { it.id }
        val porCompra = lista.filter { it.fuente == FUENTE }.groupBy { idCompra(it.claveUnica) }
        val ids = (compras.keys + porCompra.keys.filterNotNull()).distinct()
        return ids.map { id -> Plan(compras[id], porCompra[id].orEmpty().sortedBy { it.fecha }) }
            .filter { it.mensualidades.isNotEmpty() }
            .sortedBy { it.mensualidades.first().fecha }
    }
}
