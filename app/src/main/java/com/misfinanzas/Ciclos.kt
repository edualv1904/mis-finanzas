package com.misfinanzas

import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import kotlin.math.min

/**
 * Fechas del ciclo de una tarjeta de crédito vistas desde [hoy].
 * Las compras del día de corte entran al periodo que cierra ese día.
 */
data class Ciclo(
    /** Corte que abrió el periodo ya cerrado. */
    val corteAnterior: LocalDate,
    /** Último corte: cerró el periodo que toca pagar. */
    val ultimoCorte: LocalDate,
    /** Corte que cerrará el periodo actual. */
    val proximoCorte: LocalDate,
    /** Fecha límite para pagar lo que cerró en [ultimoCorte]. */
    val limitePago: LocalDate,
    /** Fecha límite para pagar lo del periodo actual. */
    val limiteProximo: LocalDate,
) {
    /** Días: [corteAnterior + 1, ultimoCorte]. */
    val periodoCerrado get() = corteAnterior.plusDays(1)..ultimoCorte

    /** Días: [ultimoCorte + 1, proximoCorte]. */
    val periodoActual get() = ultimoCorte.plusDays(1)..proximoCorte
}

object Ciclos {
    /** El día [dia] de ese mes; si el mes es más corto (p. ej. 31 en noviembre), su último día. */
    private fun enMes(mes: YearMonth, dia: Int) = mes.atDay(min(dia, mes.lengthOfMonth()))

    /** Primera fecha con día [dia] estrictamente después de [despues]. */
    private fun siguiente(dia: Int, despues: LocalDate): LocalDate {
        val mes = YearMonth.from(despues)
        val esteMes = enMes(mes, dia)
        return if (esteMes > despues) esteMes else enMes(mes.plusMonths(1), dia)
    }

    fun para(diaCorte: Int, diaLimite: Int, hoy: LocalDate): Ciclo {
        val mes = YearMonth.from(hoy)
        val corteEsteMes = enMes(mes, diaCorte)
        // El día de corte el periodo sigue abierto hasta que termine el día.
        val ultimo = if (hoy > corteEsteMes) corteEsteMes else enMes(mes.minusMonths(1), diaCorte)
        val mesUltimo = YearMonth.from(ultimo)
        val proximo = enMes(mesUltimo.plusMonths(1), diaCorte)
        return Ciclo(
            corteAnterior = enMes(mesUltimo.minusMonths(1), diaCorte),
            ultimoCorte = ultimo,
            proximoCorte = proximo,
            limitePago = siguiente(diaLimite, ultimo),
            limiteProximo = siguiente(diaLimite, proximo),
        )
    }

    fun diasEntre(desde: LocalDate, hasta: LocalDate) = ChronoUnit.DAYS.between(desde, hasta)
}
