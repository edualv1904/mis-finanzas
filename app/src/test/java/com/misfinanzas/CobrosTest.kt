package com.misfinanzas

import com.misfinanzas.data.Cobro
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class CobrosTest {
    private val nov = YearMonth.of(2026, 11)

    @Test
    fun compraAMesesQueVaEnLaNueve() {
        // 18 meses; este mes (noviembre) va en la 9 → empezó en marzo.
        val desde = Cobros.primerMes(nov, 9)
        assertEquals(YearMonth.of(2026, 3), desde)
        val meses = Cobros.mesesHasta(desde, 18, nov)
        assertEquals(9, meses.size)
        assertEquals(nov to 9, meses.last())
        // Se detiene en el total aunque pase el tiempo.
        assertEquals(18, Cobros.mesesHasta(desde, 18, YearMonth.of(2028, 1)).size)
    }

    @Test
    fun parejaPagaDosPartesJuntas() {
        val spotify = (1L..4L).map { Cobro(id = it, acuerdoId = it, mes = "2026-11", numero = 1, monto = 39.83, vence = 0) }
        val compu = Cobro(id = 9, acuerdoId = 9, mes = "2026-11", numero = 9, monto = 1000.00, vence = 0)
        val concepto = { c: Cobro -> if (c.acuerdoId == 9L) "Compu" else "Spotify" }
        // $80 = dos partes de Spotify: sale el grupo de Spotify (tú eliges quiénes), no la compu.
        val grupos = Cobros.gruposParaDeposito(80.0, spotify + compu, concepto)
        assertEquals(listOf(spotify), grupos)
        assertTrue(Cobros.cubreVarios(80.0, spotify.take(2)))
        assertFalse(Cobros.cubreVarios(80.0, spotify.take(3)))
        assertFalse(Cobros.cubreVarios(40.0, spotify.take(1)))  // uno solo no es "varios"
        // $120 = tres; $55 no cuadra con nada.
        assertEquals(1, Cobros.gruposParaDeposito(120.0, spotify, concepto).size)
        assertTrue(Cobros.gruposParaDeposito(55.0, spotify, concepto).isEmpty())
    }

    @Test
    fun cadaMesSinFin() {
        assertEquals(1, Cobros.mesesHasta(nov, null, nov).size)
        assertEquals(14, Cobros.mesesHasta(nov, null, YearMonth.of(2027, 12)).size)
        assertTrue(Cobros.mesesHasta(nov, null, YearMonth.of(2026, 10)).isEmpty())
    }

    @Test
    fun unaVez() {
        val hoy = LocalDate.of(2026, 11, 21)
        assertEquals(nov, Cobros.mesDeUnaVez(hoy, 30))
        assertEquals(nov, Cobros.mesDeUnaVez(hoy, 21))
        assertEquals(YearMonth.of(2026, 12), Cobros.mesDeUnaVez(hoy, 5))
    }

    @Test
    fun transferenciaQueCoincide() {
        assertTrue(Cobros.coincide(1001.0, 1000.00))
        assertTrue(Cobros.coincide(40.0, 39.83))
        assertFalse(Cobros.coincide(100.0, 39.83))
        assertFalse(Cobros.coincide(1050.0, 1000.00))
    }

    @Test
    fun avisos() {
        val dia = 86_400_000L
        val c = Cobro(acuerdoId = 1, mes = "2026-11", numero = 1, monto = 40.0, vence = 0L)
        assertFalse("en la gracia", Cobros.atrasado(c, 2 * dia))
        assertTrue(Cobros.toca(c, 4 * dia))
        assertFalse("ya avisó hace poco", Cobros.toca(c.copy(ultimoAviso = 4 * dia), 6 * dia))
        assertTrue("repite a la semana", Cobros.toca(c.copy(ultimoAviso = 4 * dia), 11 * dia))
        assertFalse("pagado", Cobros.atrasado(c.copy(pagado = dia), 30 * dia))
    }
}
