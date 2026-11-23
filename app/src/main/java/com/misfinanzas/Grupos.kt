package com.misfinanzas

import android.content.Context
import com.misfinanzas.data.FinanzasDb
import com.misfinanzas.data.Grupo
import com.misfinanzas.data.GrupoGasto
import com.misfinanzas.data.GrupoMiembro
import com.misfinanzas.data.GrupoParte
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.round

/**
 * Grupos y viajes, estilo Splitwise: cada gasto dice quién pagó y cómo se reparte; la app saca cuánto
 * debe o le deben a cada quien y la forma de arreglarse con menos transferencias.
 *
 * A diferencia de "Me deben" y "Debo", sí toca tus movimientos: un gasto de tu tarjeta que compartes
 * guarda [com.misfinanzas.data.Transaccion.miParte], y en "Gastado" cuenta solo tu parte.
 */
object Grupos {
    const val YO = "Yo"
    const val GASTO = "gasto"
    const val PAGO = "pago"

    /** Quién le paga a quién para quedar a mano. */
    data class Transferencia(val de: String, val a: String, val monto: Double)

    // ---------- Cálculos (sin Android, con pruebas) ----------

    fun centavos(x: Double) = round(x * 100) / 100

    /** Reparte [monto] igual entre [n], sin perder centavos: los que sobran van a los primeros. */
    fun repartirIgual(monto: Double, n: Int): List<Double> {
        if (n <= 0) return emptyList()
        val total = Math.round(monto * 100)
        val base = total / n
        val sobra = (total - base * n).toInt()
        return List(n) { i -> (base + if (i < sobra) 1 else 0) / 100.0 }
    }

    /**
     * Saldo de cada quien: positivo = le deben, negativo = debe. Quien paga suma lo que pagó y a cada
     * uno se le resta su parte. Un pago entre ellos funciona igual (el que pagó suma, el que recibió resta).
     */
    fun saldos(gastos: List<GrupoGasto>, partes: List<GrupoParte>): Map<String, Double> {
        val porGasto = partes.groupBy { it.gastoId }
        val s = LinkedHashMap<String, Double>()
        for (g in gastos) {
            s[g.pago] = (s[g.pago] ?: 0.0) + g.monto
            for (p in porGasto[g.id].orEmpty()) s[p.persona] = (s[p.persona] ?: 0.0) - p.monto
        }
        return s.mapValues { centavos(it.value) }
    }

    /** La forma de quedar a mano con menos transferencias: el que más debe le paga al que más le deben. */
    fun simplificar(saldos: Map<String, Double>): List<Transferencia> {
        val deben = saldos.filter { it.value < -0.005 }.map { it.key to -it.value }.sortedByDescending { it.second }.toMutableList()
        val cobran = saldos.filter { it.value > 0.005 }.map { it.key to it.value }.sortedByDescending { it.second }.toMutableList()
        val r = ArrayList<Transferencia>()
        while (deben.isNotEmpty() && cobran.isNotEmpty()) {
            val (d, cuantoDebe) = deben[0]
            val (c, cuantoCobra) = cobran[0]
            val x = min(cuantoDebe, cuantoCobra)
            r += Transferencia(d, c, centavos(x))
            if (cuantoDebe - x > 0.005) deben[0] = d to cuantoDebe - x else deben.removeAt(0)
            if (cuantoCobra - x > 0.005) cobran[0] = c to cuantoCobra - x else cobran.removeAt(0)
            deben.sortByDescending { it.second }
            cobran.sortByDescending { it.second }
        }
        return r
    }

    /** Lo que tú gastaste de verdad en el grupo (tus partes de los gastos, no los pagos entre ustedes). */
    fun miGasto(gastos: List<GrupoGasto>, partes: List<GrupoParte>): Double {
        val ids = gastos.filter { it.tipo == GASTO }.map { it.id }.toSet()
        return centavos(partes.filter { it.gastoId in ids && it.persona == YO }.sumOf { it.monto })
    }

    /** Lo que te debe [persona] (negativo: lo que le debes) según cómo quedan a mano en el grupo. */
    fun conmigo(persona: String, transferencias: List<Transferencia>): Double =
        transferencias.filter { it.de == persona && it.a == YO }.sumOf { it.monto } -
            transferencias.filter { it.de == YO && it.a == persona }.sumOf { it.monto }

    fun cuadra(monto: Double, partes: Collection<Double>) = abs(partes.sum() - monto) < 0.015

    // ---------- Base de datos ----------

    suspend fun crear(ctx: Context, nombre: String, personas: List<String>): Long {
        val dao = FinanzasDb.get(ctx).dao()
        val id = dao.insertarGrupo(Grupo(nombre = nombre))
        dao.insertarMiembros(personas.map { GrupoMiembro(grupoId = id, persona = it) })
        return id
    }

    suspend fun editar(ctx: Context, g: Grupo, personas: List<String>) {
        val dao = FinanzasDb.get(ctx).dao()
        dao.actualizarGrupo(g)
        dao.quitarMiembros(g.id, personas.ifEmpty { listOf("") })
        dao.insertarMiembros(personas.map { GrupoMiembro(grupoId = g.id, persona = it) })
    }

    suspend fun borrar(ctx: Context, g: Grupo) {
        val dao = FinanzasDb.get(ctx).dao()
        for (gasto in dao.gastosDeGrupo(g.id)) borrarGasto(ctx, gasto)
        dao.borrarMiembros(g.id)
        dao.borrarGrupo(g.id)
        Tickets.borrarDeGrupo(ctx, g.id)
    }

    /**
     * Guarda (o reemplaza, si ya tiene id) un gasto con sus partes y tu parte en el movimiento de tarjeta.
     * [fotos]: las fotos de ticket que debe tener al final (null = no cambiar las que tiene).
     */
    suspend fun guardarGasto(ctx: Context, g: GrupoGasto, partes: Map<String, Double>, fotos: List<java.io.File>? = null) {
        val dao = FinanzasDb.get(ctx).dao()
        val id = if (g.id == 0L) dao.insertarGrupoGasto(g) else g.id.also {
            dao.actualizarGrupoGasto(g)
            dao.borrarPartes(it)
        }
        dao.insertarPartes(partes.filter { it.value > 0.0 }.map { (p, m) -> GrupoParte(gastoId = id, persona = p, monto = m) })
        g.transaccionId?.let { dao.ponerMiParte(it, if (g.tipo == GASTO) centavos(partes[YO] ?: 0.0) else null) }
        if (fotos != null) Tickets.fijar(ctx, g.grupoId, id, fotos)
    }

    suspend fun borrarGasto(ctx: Context, g: GrupoGasto) {
        val dao = FinanzasDb.get(ctx).dao()
        dao.borrarPartes(g.id)
        dao.borrarGrupoGasto(g.id)
        g.transaccionId?.let { dao.ponerMiParte(it, null) }
        Tickets.borrarDeGasto(ctx, g.grupoId, g.id)
    }
}
