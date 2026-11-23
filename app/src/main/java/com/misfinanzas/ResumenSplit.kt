package com.misfinanzas

import com.misfinanzas.Grupos.YO
import com.misfinanzas.data.Grupo
import com.misfinanzas.ui.DatosGrupos
import com.misfinanzas.ui.diaDe
import com.misfinanzas.ui.dinero
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * El resumen de un split, para que todos entiendan de dónde sale cada cifra: cada gasto con quién pagó
 * y cómo se repartió, cómo va cada quien, los pagos hechos y quién le paga a quién.
 * [markdown] = el archivo que se guarda en Drive; si no, texto con *negritas* de WhatsApp.
 */
object ResumenSplit {
    private val fmt = DateTimeFormatter.ofPattern("d MMM yyyy", Locale("es", "MX"))
    private fun dia(millis: Long) = diaDe(millis).format(fmt).replace(".", "")
    private fun d(x: Double) = dinero(x, mostrar = true)
    private fun nombre(p: String) = if (p == YO) "Yo" else p

    fun texto(g: Grupo, datos: DatosGrupos, markdown: Boolean, fotos: Map<Long, List<String>> = emptyMap()): String = buildString {
        val neg = { s: String -> if (markdown) "**$s**" else "*$s*" }
        val seccion = { s: String -> appendLine(); appendLine(if (markdown) "## $s" else "*${s.uppercase()}*") }
        val sub = if (markdown) "   - " else "   "
        val punto = if (markdown) "- " else "• "

        val todos = datos.gastosDe[g.id].orEmpty().sortedBy { it.fecha }
        val gastos = todos.filter { it.tipo == Grupos.GASTO }
        val pagos = todos.filter { it.tipo == Grupos.PAGO }
        val gente = listOf(YO) + datos.miembrosDe[g.id].orEmpty()
        val saldos = datos.saldos(g.id)

        appendLine(if (markdown) "# ${g.nombre}" else neg(g.nombre))
        if (markdown) appendLine()
        appendLine("${gente.size} personas: ${gente.joinToString(", ") { nombre(it) }}" + if (markdown) "  " else "")
        if (gastos.isNotEmpty()) appendLine("Del ${dia(gastos.first().fecha)} al ${dia(gastos.last().fecha)}" + if (!g.activo) " · terminado" else "")

        seccion("Gastos")
        if (gastos.isEmpty()) appendLine("Todavía no hay gastos.")
        gastos.forEachIndexed { i, x ->
            val partes = datos.partesDe[x.id].orEmpty().filter { it.monto > 0 }
            appendLine("${i + 1}. ${neg(x.concepto)} · ${d(x.monto)}")
            appendLine("${sub}${dia(x.fecha)} · pagó ${nombre(x.pago)}")
            val iguales = partes.isNotEmpty() && partes.maxOf { it.monto } - partes.minOf { it.monto } < 0.015
            appendLine(sub + when {
                partes.size == gente.size && iguales -> "Entre todos por igual: ${d(partes.first().monto)} cada quien"
                iguales -> "Entre ${partes.joinToString(", ") { nombre(it.persona) }}: ${d(partes.first().monto)} cada quien"
                else -> partes.joinToString(" · ") { "${nombre(it.persona)} ${d(it.monto)}" }
            })
            fotos[x.id]?.takeIf { it.isNotEmpty() }?.let { appendLine("${sub}Ticket: ${it.joinToString(", ")}") }
        }
        appendLine()
        appendLine(neg("Total gastado: ${d(gastos.sumOf { it.monto })}"))

        if (gastos.isNotEmpty()) {
            seccion("Cómo va cada quien")
            val idsGastos = gastos.map { it.id }.toSet()
            val partesTodas = datos.partesDelGrupo(g.id)
            for (p in gente.sortedByDescending { saldos[it] ?: 0.0 }) {
                val puso = gastos.filter { it.pago == p }.sumOf { it.monto }
                val toca = partesTodas.filter { it.gastoId in idsGastos && it.persona == p }.sumOf { it.monto }
                val dio = pagos.filter { it.pago == p }.sumOf { it.monto }
                val recibio = pagos.flatMap { x -> datos.partesDe[x.id].orEmpty() }.filter { it.persona == p }.sumOf { it.monto }
                val saldo = saldos[p] ?: 0.0
                val detalle = buildList {
                    add(if (puso > 0) "pagó ${d(puso)}" else "no ha pagado gastos")
                    add("le toca ${d(toca)}")
                    if (dio > 0) add("ya transfirió ${d(dio)}")
                    if (recibio > 0) add("ya recibió ${d(recibio)}")
                }.joinToString(", ")
                val resultado = when {
                    saldo > 0.005 -> "le deben ${d(saldo)}"
                    saldo < -0.005 -> "debe ${d(-saldo)}"
                    else -> "a mano"
                }
                appendLine("${if (markdown) punto else ""}${nombre(p)}: $detalle → ${neg(resultado)}")
            }
        }

        if (pagos.isNotEmpty()) {
            seccion("Pagos ya hechos")
            for (x in pagos) {
                val a = datos.partesDe[x.id].orEmpty().firstOrNull()?.persona ?: ""
                appendLine("${if (markdown) punto else ""}${dia(x.fecha)} · ${nombre(x.pago)} le pagó ${d(x.monto)} a ${nombre(a)}")
            }
        }

        val t = datos.transferencias(g.id)
        if (t.isEmpty()) { appendLine(); appendLine(neg("Todos estamos a mano.")) }
        else {
            seccion("Para quedar a mano")
            for (x in t) appendLine("$punto${nombre(x.de)} le paga ${d(x.monto)} a ${nombre(x.a)}")
        }
    }.trim() + "\n"
}
