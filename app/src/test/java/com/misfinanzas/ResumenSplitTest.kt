package com.misfinanzas

import com.misfinanzas.Grupos.YO
import com.misfinanzas.data.Grupo
import com.misfinanzas.data.GrupoGasto
import com.misfinanzas.data.GrupoMiembro
import com.misfinanzas.data.GrupoParte
import com.misfinanzas.ui.DatosGrupos
import org.junit.Assert.assertTrue
import org.junit.Test

class ResumenSplitTest {
    @Test
    fun resumenTraeGastosCadaQuienYQuienPaga() {
        val g = Grupo(id = 1, nombre = "Viaje a la playa", creado = 0)
        val gente = listOf(YO, "Luis", "Carlos", "Mamá", "Ana")
        val miembros = gente.drop(1).mapIndexed { i, p -> GrupoMiembro(i + 1L, 1, p) }
        val dia = 1795403287686L
        val gastos = listOf(
            GrupoGasto(1, 1, dia, "cena 1", 354.0, YO, Grupos.GASTO),
            GrupoGasto(2, 1, dia, "cena 2 - test", 800.0, "Ana", Grupos.GASTO),
            GrupoGasto(3, 1, dia, "cena - test3", 5000.0, "Mamá", Grupos.GASTO),
        )
        var id = 0L
        val partes = gastos.flatMap { x -> gente.map { GrupoParte(++id, x.id, it, x.monto / 5) } }
        val datos = DatosGrupos(listOf(g), miembros, gastos, partes)
        val md = ResumenSplit.texto(g, datos, markdown = true, fotos = mapOf(3L to listOf("2026-11-22 cena - test3 (1).jpg")))
        println(md)
        assertTrue(md.startsWith("# Viaje a la playa"))
        assertTrue(md.contains("## Para quedar a mano"))
        assertTrue(md.contains("- Luis le paga \$1,230.80 a Mamá"))
        assertTrue(md.contains("Ticket: 2026-11-22 cena - test3 (1).jpg"))
        val wa = ResumenSplit.texto(g, datos, markdown = false)
        assertTrue(wa.contains("*PARA QUEDAR A MANO*"))
    }
}
