package com.misfinanzas

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/** Fechas de ejemplo, vistas el 13 de noviembre de 2026. */
class CiclosTest {
    private val hoy = LocalDate.of(2026, 11, 13)
    private fun d(mes: Int, dia: Int, anio: Int = 2026) = LocalDate.of(anio, mes, dia)

    @Test
    fun yaCorto() {
        val c = Ciclos.para(diaCorte = 12, diaLimite = 2, hoy)
        assertEquals(d(10, 12), c.corteAnterior)
        assertEquals(d(11, 12), c.ultimoCorte)
        assertEquals(d(12, 2), c.limitePago)
        assertEquals(d(12, 12), c.proximoCorte)
        assertEquals(d(1, 2, 2027), c.limiteProximo)
        assertEquals(d(10, 13)..d(11, 12), c.periodoCerrado)
    }

    @Test
    fun limiteEnElMismoMes() {
        val c = Ciclos.para(diaCorte = 14, diaLimite = 26, hoy)
        assertEquals(d(10, 14), c.ultimoCorte)
        assertEquals(d(10, 26), c.limitePago)
        assertEquals(d(11, 14), c.proximoCorte)
        assertEquals(d(11, 26), c.limiteProximo)
        assertEquals(d(10, 15)..d(11, 14), c.periodoActual)
    }

    @Test
    fun variasTarjetas() {
        Ciclos.para(15, 4, hoy).let {
            assertEquals(d(11, 15), it.proximoCorte)
            assertEquals(d(12, 4), it.limiteProximo)
        }
        Ciclos.para(21, 1, hoy).let {
            assertEquals(d(11, 21), it.proximoCorte)
            assertEquals(d(12, 1), it.limiteProximo)
        }
        Ciclos.para(16, 6, hoy).let {
            assertEquals(d(11, 16), it.proximoCorte)
            assertEquals(d(12, 6), it.limiteProximo)
        }
    }

    @Test
    fun elDiaDeCorteElPeriodoSigueAbierto() {
        val c = Ciclos.para(diaCorte = 13, diaLimite = 3, hoy)
        assertEquals(d(10, 13), c.ultimoCorte)
        assertEquals(d(11, 13), c.proximoCorte)
    }

    @Test
    fun corteEl31EnMesesCortos() {
        val c = Ciclos.para(diaCorte = 31, diaLimite = 20, d(2, 15, 2027))
        assertEquals(d(1, 31, 2027), c.ultimoCorte)
        assertEquals(d(2, 28, 2027), c.proximoCorte)
        assertEquals(d(2, 20, 2027), c.limitePago)
    }
}
