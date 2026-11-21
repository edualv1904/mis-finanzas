package com.misfinanzas

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class RespaldoTest {
    private val sab = LocalDate.of(2026, 11, 21)   // sábado

    @Test
    fun tocaCadaSabado() {
        assertTrue("nunca se ha hecho", Respaldo.toca(null, LocalDate.of(2026, 11, 18)))
        assertTrue("sábado, el último fue la semana pasada", Respaldo.toca(sab.minusDays(7), sab))
        assertFalse("ya se hizo hoy sábado", Respaldo.toca(sab, sab))
        assertFalse("domingo, ya se hizo el sábado", Respaldo.toca(sab, sab.plusDays(1)))
        assertFalse("viernes, se hizo el sábado pasado", Respaldo.toca(sab.minusDays(7), sab.minusDays(1)))
        // No estabas en casa el sábado: se hace en cuanto vuelvas.
        assertTrue("lunes sin respaldo del sábado", Respaldo.toca(sab.minusDays(7), sab.plusDays(2)))
        assertFalse("el siguiente viernes ya no", Respaldo.toca(sab.plusDays(2), sab.plusDays(6)))
    }
}
