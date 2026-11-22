package com.misfinanzas

import com.misfinanzas.data.DeudaMov
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class DeudasTest {
    private fun mov(monto: Double, tipo: String) = DeudaMov(deudaId = 1, fecha = 0, monto = monto, tipo = tipo)

    @Test
    fun saldoEsCargosMenosAbonos() {
        // Debías $2,000, fuiste a dos sesiones (una a $300 y otra ya a $500) y abonaste $1,000.
        val movs = listOf(mov(2000.0, Deudas.CARGO), mov(300.0, Deudas.CARGO), mov(500.0, Deudas.CARGO), mov(1000.0, Deudas.ABONO))
        assertEquals(1800.0, Deudas.saldo(movs), 0.001)
        assertEquals(0.0, Deudas.saldo(emptyList()), 0.001)
    }

    @Test
    fun viernesYSabadoDeLaSemana() {
        val domingo = LocalDate.of(2026, 11, 22)
        assertEquals(LocalDate.of(2026, 11, 16), Deudas.inicioSemana(domingo))
        assertEquals(LocalDate.of(2026, 11, 20), Deudas.diaDeSesion(domingo, viernes = true))
        assertEquals(LocalDate.of(2026, 11, 21), Deudas.diaDeSesion(domingo, viernes = false))
    }

    @Test
    fun preguntaElSabadoEnLaTardeOElDomingo() {
        val semana = LocalDate.of(2026, 11, 16).toEpochDay()
        assertFalse(Deudas.tocaPreguntar(LocalDateTime.of(2026, 11, 20, 20, 0), null, false))      // viernes
        assertFalse(Deudas.tocaPreguntar(LocalDateTime.of(2026, 11, 21, 10, 0), null, false))      // sábado temprano
        assertTrue(Deudas.tocaPreguntar(LocalDateTime.of(2026, 11, 21, 18, 30), null, false))      // sábado tarde
        assertTrue(Deudas.tocaPreguntar(LocalDateTime.of(2026, 11, 22, 9, 0), null, false))        // domingo
        assertFalse(Deudas.tocaPreguntar(LocalDateTime.of(2026, 11, 22, 9, 0), semana, false))     // ya contestó
        assertFalse(Deudas.tocaPreguntar(LocalDateTime.of(2026, 11, 21, 19, 0), null, true))       // ya anotó sesión
        // La semana siguiente vuelve a preguntar.
        assertTrue(Deudas.tocaPreguntar(LocalDateTime.of(2026, 11, 28, 19, 0), semana, false))
    }
}
