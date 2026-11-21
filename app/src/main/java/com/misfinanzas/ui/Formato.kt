package com.misfinanzas.ui

import com.misfinanzas.Ajustes
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val mx = Locale("es", "MX")
private val moneda = NumberFormat.getCurrencyInstance(mx)
private val fmtDia = DateTimeFormatter.ofPattern("EEEE d 'de' MMMM", mx)
private val fmtHora = DateTimeFormatter.ofPattern("HH:mm", mx)
private val fmtMes = DateTimeFormatter.ofPattern("MMMM yyyy", mx)

/** Cantidad en pesos; con "ocultar cantidades" activo sale como "$ ••••" (salvo que se pida [mostrar]). */
fun dinero(monto: Double, mostrar: Boolean = !Ajustes.saldosOcultos): String =
    if (mostrar) moneda.format(monto) else OCULTO

const val OCULTO = "$ ••••"

fun diaDe(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()

fun textoDia(d: LocalDate): String = when (d) {
    LocalDate.now() -> "Hoy"
    LocalDate.now().minusDays(1) -> "Ayer"
    else -> d.format(fmtDia).replaceFirstChar { it.uppercase() }
}

fun hora(millis: Long): String = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(fmtHora)

fun textoMes(m: YearMonth): String = m.format(fmtMes).replaceFirstChar { it.uppercase() }
