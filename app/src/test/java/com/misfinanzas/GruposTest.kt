package com.misfinanzas

import com.misfinanzas.Grupos.Transferencia
import com.misfinanzas.Grupos.YO
import com.misfinanzas.data.GrupoGasto
import com.misfinanzas.data.GrupoParte
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GruposTest {
    private var siguiente = 1L
    private val gastos = ArrayList<GrupoGasto>()
    private val partes = ArrayList<GrupoParte>()

    private fun gasto(pago: String, monto: Double, vararg reparto: Pair<String, Double>, tipo: String = Grupos.GASTO) {
        val id = siguiente++
        gastos += GrupoGasto(id = id, grupoId = 1, fecha = 0, concepto = "x", monto = monto, pago = pago, tipo = tipo)
        reparto.forEach { (p, m) -> partes += GrupoParte(gastoId = id, persona = p, monto = m) }
    }

    @Test
    fun repartirIgualSinPerderCentavos() {
        assertEquals(listOf(400.0, 400.0, 400.0), Grupos.repartirIgual(1200.0, 3))
        val r = Grupos.repartirIgual(100.0, 3)
        assertEquals(listOf(33.34, 33.33, 33.33), r)
        assertEquals(100.0, r.sum(), 0.001)
    }

    @Test
    fun cenaEntreTres() {
        // Pagas $1,200 de una cena entre tres: Ana y Luis te deben $400 cada una.
        gasto(YO, 1200.0, YO to 400.0, "Ana" to 400.0, "Luis" to 400.0)
        val s = Grupos.saldos(gastos, partes)
        assertEquals(800.0, s[YO]!!, 0.001)
        assertEquals(-400.0, s["Ana"]!!, 0.001)
        val t = Grupos.simplificar(s)
        assertEquals(2, t.size)
        assertTrue(t.all { it.a == YO && it.monto == 400.0 })
        assertEquals(400.0, Grupos.miGasto(gastos, partes), 0.001)
        assertEquals(400.0, Grupos.conmigo("Ana", t), 0.001)
    }

    @Test
    fun variosGastosSeCompensan() {
        // Tú pagas el hotel ($3,000 entre 3); Ana paga la gasolina ($900 entre 3).
        gasto(YO, 3000.0, YO to 1000.0, "Ana" to 1000.0, "Luis" to 1000.0)
        gasto("Ana", 900.0, YO to 300.0, "Ana" to 300.0, "Luis" to 300.0)
        val s = Grupos.saldos(gastos, partes)
        assertEquals(1700.0, s[YO]!!, 0.001)
        assertEquals(-400.0, s["Ana"]!!, 0.001)
        assertEquals(-1300.0, s["Luis"]!!, 0.001)
        val t = Grupos.simplificar(s)
        assertEquals(listOf(Transferencia("Luis", YO, 1300.0), Transferencia("Ana", YO, 400.0)), t)
        assertEquals(1300.0, Grupos.miGasto(gastos, partes), 0.001)
    }

    @Test
    fun unPagoDejaAMano() {
        gasto(YO, 1200.0, YO to 400.0, "Ana" to 400.0, "Luis" to 400.0)
        // Ana te transfiere sus $400.
        gasto("Ana", 400.0, YO to 400.0, tipo = Grupos.PAGO)
        val s = Grupos.saldos(gastos, partes)
        assertEquals(0.0, s["Ana"]!!, 0.001)
        assertEquals(listOf(Transferencia("Luis", YO, 400.0)), Grupos.simplificar(s))
        // El pago no es gasto tuyo.
        assertEquals(400.0, Grupos.miGasto(gastos, partes), 0.001)
    }

    @Test
    fun cuandoTuDebes() {
        gasto("Ana", 500.0, YO to 250.0, "Ana" to 250.0)
        val t = Grupos.simplificar(Grupos.saldos(gastos, partes))
        assertEquals(listOf(Transferencia(YO, "Ana", 250.0)), t)
        assertEquals(-250.0, Grupos.conmigo("Ana", t), 0.001)
    }
}
