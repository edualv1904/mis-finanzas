package com.misfinanzas

import com.misfinanzas.data.Transaccion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

class MesesTest {
    private val zona = ZoneId.of("America/Mexico_City")
    private fun dia(millis: Long) = Instant.ofEpochMilli(millis).atZone(zona).toLocalDate()

    private val compra = Transaccion(
        id = 1034, monto = 2000.00, comercio = "CLINICA EJEMPLO", tarjeta = Tarjetas.BBVA_CREDITO,
        fecha = 0L, categoria = "Salud", fuente = "bbva", claveUnica = "bbva|x",
    )

    @Test
    fun repartoEnCentavos() {
        assertEquals(listOf(666.67, 666.67, 666.66), Meses.montos(2000.00, 3))
        assertEquals(listOf(50.0, 50.0), Meses.montos(100.0, 2))
        assertEquals(1000.0, Meses.montos(1000.0, 12).sum(), 0.001)
    }

    @Test
    fun fechasEnDiaLimite() {
        val f = Meses.fechas(YearMonth.of(2027, 3), 3, diaLimite = 2, zona).map(::dia)
        assertEquals(listOf(LocalDate.of(2027, 3, 2), LocalDate.of(2027, 4, 2), LocalDate.of(2027, 5, 2)), f)
        // Día 31 en meses cortos y cruce de año.
        val g = Meses.fechas(YearMonth.of(2026, 12), 3, diaLimite = 31, zona).map(::dia)
        assertEquals(listOf(LocalDate.of(2026, 12, 31), LocalDate.of(2027, 1, 31), LocalDate.of(2027, 2, 28)), g)
    }

    @Test
    fun mensualidades() {
        val m = Meses.mensualidades(compra, 3, YearMonth.of(2027, 3), 2, zona)
        assertEquals(listOf("msi|1034|1", "msi|1034|2", "msi|1034|3"), m.map { it.claveUnica })
        assertEquals(listOf("1 de 3", "2 de 3", "3 de 3"), m.map { it.nota })
        m.forEach {
            assertEquals(Tipos.GASTO, it.tipo)
            assertEquals(Meses.FUENTE, it.fuente)
            assertEquals("Salud", it.categoria)
            assertEquals(Tarjetas.BBVA_CREDITO, it.tarjeta)
        }
        assertEquals(1034L, Meses.idCompra("msi|1034|2"))
        assertNull(Meses.idCompra("bbva|x"))
    }

    @Test
    fun agrupar() {
        val m = Meses.mensualidades(compra, 3, YearMonth.of(2027, 3), 2, zona)
        val planes = Meses.agrupar(listOf(compra.copy(tipo = Tipos.MSI)) + m.mapIndexed { i, t -> t.copy(id = 2000L + i) })
        assertEquals(1, planes.size)
        assertEquals(2000.00, planes[0].total, 0.001)
        assertEquals(3, planes[0].mensualidades.size)
    }
}
