package com.misfinanzas

import com.misfinanzas.data.Acuerdo
import com.misfinanzas.data.Cobro
import com.misfinanzas.data.Deuda
import com.misfinanzas.data.DeudaMov
import java.text.Normalizer
import java.time.YearMonth

/**
 * Personas: junta por nombre todo lo que tienes con alguien en "Me deben" y "Debo" (Ana: su parte
 * de Spotify y lo que sea después). No guarda nada propio: "Ana", "ana" y "Ána" son la misma.
 */
object Personas {

    /** Renglón del historial de una persona. [recibido]: dinero que te llegó a ti (se pinta verde). */
    data class Renglon(val fecha: Long, val texto: String, val monto: Double, val recibido: Boolean)

    data class Perfil(
        val nombre: String,
        val acuerdos: List<Acuerdo>,
        val cobros: List<Cobro>,
        val deudas: List<Deuda>,
        val movs: List<DeudaMov>,
        /** Grupos donde está: nombre del grupo, lo que te debe ahí (negativo: le debes) y si sigue activo. */
        val grupos: List<EnGrupo> = emptyList(),
    ) {
        val clave get() = clave(nombre)

        /** Lo que ya debió pagarte y no ha pagado. */
        fun teDebe(ahora: Long) = cobros.filter { it.pagado == null && it.vence <= ahora }.sumOf { it.monto } +
            grupos.sumOf { it.conmigo.coerceAtLeast(0.0) }

        /** Lo que le debes tú (saldo de sus libretas en "Debo"). */
        fun leDebes() = deudas.sumOf { d -> Deudas.saldo(movs.filter { it.deudaId == d.id }).coerceAtLeast(0.0) } +
            grupos.sumOf { (-it.conmigo).coerceAtLeast(0.0) }

        /** Positivo: te debe; negativo: le debes. */
        fun neto(ahora: Long) = teDebe(ahora) - leDebes()

        /** Lo que te ha pagado en total (sin contar lo que le perdonaste). */
        fun pagado() = cobros.filter { it.pagado != null && it.abonoId != Cobros.PERDONADO }.sumOf { it.monto }

        /** El siguiente pago que te toca recibir de ella (aún no vence). */
        fun siguiente(ahora: Long) = cobros.filter { it.pagado == null && it.vence > ahora }.minByOrNull { it.vence }

        /** ¿Sigue habiendo algo vivo con esta persona? */
        val activa get() = acuerdos.any { it.activo } || deudas.any { it.activa } || grupos.any { it.activo }

        fun historial(): List<Renglon> {
            val porAcuerdo = acuerdos.associateBy { it.id }
            val porDeuda = deudas.associateBy { it.id }
            val pagos = cobros.filter { it.pagado != null }.map { c ->
                val concepto = porAcuerdo[c.acuerdoId]?.concepto.orEmpty()
                val mes = mesCorto(c.mes)
                if (c.abonoId == Cobros.PERDONADO) Renglon(c.pagado!!, "$concepto $mes · ya no te lo debe", c.monto, recibido = false)
                else Renglon(c.pagado!!, "Te pagó $concepto $mes", c.monto, recibido = true)
            }
            val libreta = movs.map { m ->
                val quien = porDeuda[m.deudaId]?.let { if (deudas.size > 1) " (${it.persona})" else "" }.orEmpty()
                if (m.tipo == Deudas.ABONO) Renglon(m.fecha, "Le abonaste" + m.nota.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty() + quien, m.monto, recibido = false)
                else Renglon(m.fecha, (m.nota.ifBlank { "Se sumó a lo que le debes" }) + quien, m.monto, recibido = false)
            }
            return (pagos + libreta).sortedByDescending { it.fecha }
        }
    }

    data class EnGrupo(val grupoId: Long, val nombre: String, val conmigo: Double, val activo: Boolean)

    /** Nombre para comparar: sin acentos, sin mayúsculas, sin espacios de más. */
    fun clave(nombre: String): String =
        Normalizer.normalize(nombre.trim(), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .lowercase()
            .replace(Regex("\\s+"), " ")

    /**
     * [enGrupos]: por cada nombre (como está escrito en el grupo), en qué grupos está. Se junta con
     * Me deben y Debo por [clave].
     */
    fun perfiles(
        acuerdos: List<Acuerdo>,
        cobros: List<Cobro>,
        deudas: List<Deuda>,
        movs: List<DeudaMov>,
        enGrupos: Map<String, List<EnGrupo>> = emptyMap(),
    ): List<Perfil> {
        val cobrosDe = cobros.groupBy { it.acuerdoId }
        val movsDe = movs.groupBy { it.deudaId }
        val claves = (acuerdos.map { clave(it.persona) } + deudas.map { clave(it.persona) } + enGrupos.keys.map(::clave))
            .filter { it.isNotEmpty() }.distinct()
        return claves.map { k ->
            val a = acuerdos.filter { clave(it.persona) == k }
            val d = deudas.filter { clave(it.persona) == k }
            // Se muestra como lo escribiste más veces (el primero si empatan).
            val g = enGrupos.filterKeys { clave(it) == k }
            val nombre = (a.map { it.persona.trim() } + d.map { it.persona.trim() } + g.flatMap { (n, l) -> l.map { n.trim() } })
                .groupingBy { it }.eachCount().maxByOrNull { it.value }!!.key
            Perfil(nombre, a, a.flatMap { cobrosDe[it.id].orEmpty() }, d, d.flatMap { movsDe[it.id].orEmpty() }, g.values.flatten())
        }
    }

    /**
     * Sugerencias al escribir un nombre: primero las que empiezan igual, luego las que lo contienen y
     * luego las casi iguales ("Anaa" → Ana). Vacío = todas, en orden alfabético.
     */
    fun sugerir(texto: String, conocidas: List<String>): List<String> {
        val t = clave(texto)
        val unicas = conocidas.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy(::clave).sortedBy(::clave)
        if (t.isEmpty()) return unicas
        val empiezan = unicas.filter { clave(it).startsWith(t) }
        val contienen = unicas.filter { it !in empiezan && clave(it).contains(t) }
        val parecidas = unicas.filter { it !in empiezan && it !in contienen && parecido(t, clave(it)) }
        return empiezan + contienen + parecidas
    }

    /** ¿Casi iguales? Uno o dos cambios de letra según el largo (un dedazo, una letra de más). */
    fun parecido(a: String, b: String): Boolean {
        val tope = if (minOf(a.length, b.length) <= 4) 1 else 2
        if (kotlin.math.abs(a.length - b.length) > tope) return false
        var previa = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val fila = IntArray(b.length + 1)
            fila[0] = i
            for (j in 1..b.length) {
                fila[j] = minOf(previa[j] + 1, fila[j - 1] + 1, previa[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            previa = fila
        }
        return previa[b.length] <= tope
    }

    private val meses = listOf("ene", "feb", "mar", "abr", "may", "jun", "jul", "ago", "sep", "oct", "nov", "dic")

    fun mesCorto(mes: String): String = runCatching {
        val ym = YearMonth.parse(mes)
        val nombre = meses[ym.monthValue - 1]
        if (ym.year == YearMonth.now().year) nombre else "$nombre ${ym.year}"
    }.getOrDefault(mes)
}
