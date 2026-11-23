package com.misfinanzas

import com.misfinanzas.data.Acuerdo
import com.misfinanzas.data.Cobro
import com.misfinanzas.data.Deuda
import com.misfinanzas.data.DeudaMov
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonasTest {
    @Test
    fun mismoNombreConAcentosYMayusculas() {
        assertEquals("ana", Personas.clave("  Ána "))
        assertEquals("papa", Personas.clave("Papá"))
        assertEquals("tia lupe", Personas.clave("Tía   Lupe"))
    }

    @Test
    fun juntaMeDebenYDeboDeLaMismaPersona() {
        val acuerdos = listOf(
            Acuerdo(id = 1, persona = "Ana", concepto = "Spotify", monto = 40.0, dia = 4, desde = "2026-12"),
            Acuerdo(id = 2, persona = "ana", concepto = "Cena", monto = 300.0, dia = 10, desde = "2026-11", meses = 1),
            Acuerdo(id = 3, persona = "Luis", concepto = "Spotify", monto = 40.0, dia = 4, desde = "2026-12"),
        )
        val cobros = listOf(
            Cobro(id = 1, acuerdoId = 1, mes = "2026-12", numero = 1, monto = 40.0, vence = 1_000),
            Cobro(id = 2, acuerdoId = 2, mes = "2026-11", numero = 1, monto = 300.0, vence = 500),
            Cobro(id = 3, acuerdoId = 3, mes = "2026-12", numero = 1, monto = 40.0, vence = 1_000, pagado = 900),
        )
        val deudas = listOf(Deuda(id = 7, persona = "Ana"))
        val movs = listOf(
            DeudaMov(deudaId = 7, fecha = 100, monto = 500.0, tipo = Deudas.CARGO),
            DeudaMov(deudaId = 7, fecha = 200, monto = 100.0, tipo = Deudas.ABONO),
        )
        val ps = Personas.perfiles(acuerdos, cobros, deudas, movs)
        assertEquals(2, ps.size)
        val ana = ps.first { it.clave == "ana" }
        assertEquals("Ana", ana.nombre)
        assertEquals(2, ana.acuerdos.size)
        // A las 600 ya venció la cena ($300) pero no Spotify: te debe 300, le debes 400.
        assertEquals(300.0, ana.teDebe(600), 0.001)
        assertEquals(400.0, ana.leDebes(), 0.001)
        assertEquals(-100.0, ana.neto(600), 0.001)
        assertEquals(1L, ana.siguiente(600)?.id)
        assertTrue(ana.activa)
        assertEquals(2, ana.historial().size)

        val ale = ps.first { it.clave == "luis" }
        assertEquals(40.0, ale.pagado(), 0.001)
        assertEquals(0.0, ale.teDebe(2_000), 0.001)
    }

    @Test
    fun loPerdonadoNoCuentaComoPagado() {
        val a = Acuerdo(id = 1, persona = "Jorge", concepto = "Compu", monto = 100.0, dia = 15, desde = "2026-03", meses = 2, activo = false)
        val cobros = listOf(
            Cobro(id = 1, acuerdoId = 1, mes = "2026-03", numero = 1, monto = 100.0, vence = 1, pagado = 2),
            Cobro(id = 2, acuerdoId = 1, mes = "2026-04", numero = 2, monto = 100.0, vence = 3, pagado = 4, abonoId = Cobros.PERDONADO),
        )
        val p = Personas.perfiles(listOf(a), cobros, emptyList(), emptyList()).single()
        assertEquals(100.0, p.pagado(), 0.001)
        assertFalse(p.activa)
        assertFalse(p.historial().first().recibido)
    }

    @Test
    fun sugiereNombresParecidos() {
        val gente = listOf("Ana", "Luis", "Carlos", "Mariana", "Papá", "ana")
        assertEquals(listOf("Ana", "Carlos", "Luis", "Mariana", "Papá"), Personas.sugerir("", gente))
        assertEquals(listOf("Ana", "Carlos"), Personas.sugerir("a", gente).take(2))
        assertEquals(listOf("Ana"), Personas.sugerir("Anaa", gente))
        assertEquals(listOf("Ana"), Personas.sugerir("ama", gente))
        assertEquals(listOf("Papá"), Personas.sugerir("papa", gente))
        assertEquals(emptyList<String>(), Personas.sugerir("Roberto", gente))
        assertTrue(Personas.parecido("mariane", "mariana"))
        assertFalse(Personas.parecido("luis", "ana"))
    }
}
