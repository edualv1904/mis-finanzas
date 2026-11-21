package com.misfinanzas

import com.misfinanzas.data.Transaccion
import java.text.Normalizer
import kotlin.math.abs

/** Tipos de movimiento. Solo los gastos cuentan en "Gastado este mes". */
object Tipos {
    const val GASTO = "gasto"
    const val INGRESO = "ingreso"
    /** Dinero que se mueve entre tus cuentas o pagos de tarjeta: no es gasto nuevo. */
    const val TRANSFERENCIA = "transferencia"
    /** Compra pasada a meses: no suma; lo que suma son sus mensualidades (ver Meses). */
    const val MSI = "msi"
}

/** Movimiento extraído de la notificación de una app (Wallet o banco). */
data class Pago(
    val comercio: String,
    val monto: Double,
    /** Nombre canónico de la tarjeta, sin dígitos (ver [Tarjetas]). */
    val tarjeta: String,
    val tipo: String = Tipos.GASTO,
    val moneda: String = "MXN",
)

/** Una app cuyas notificaciones sabemos leer. */
interface FuenteNotificacion {
    /** Se guarda en Transaccion.fuente. */
    val id: String
    fun esDeEsta(paquete: String): Boolean
    fun parse(titulo: String, texto: String): Pago?
}

object Fuentes {
    val todas: List<FuenteNotificacion> = listOf(
        WalletParser, BbvaParser, MercadoPagoParser, NuParser, RappiParser, BanamexParser,
    )

    fun para(paquete: String) = todas.firstOrNull { it.esDeEsta(paquete) }
}

/**
 * Apps de bancos que vigilamos. Lo que manden y no sepamos leer se guarda (sin registrarlo)
 * para que lo etiquetes y podamos escribir más parsers.
 */
object Recoleccion {
    fun vigila(paquete: String) = Fuentes.para(paquete) != null
}

/**
 * Un solo nombre por tarjeta, sin dígitos, venga de Wallet o del banco.
 * Así el resumen agrupa bien y es fácil reconocer el mismo pago en dos notificaciones.
 */
object Tarjetas {
    const val BBVA_CREDITO = "BBVA Crédito"
    const val BBVA_DEBITO = "BBVA Débito"
    const val RAPPICARD = "Rappicard"
    const val NU = "Nu"
    const val BANAMEX = "Banamex"
    const val MERCADO_PAGO = "Mercado Pago"

    private val digitos = Regex("""\s*[•·*]+\s*\d{3,4}\s*$""")

    fun canonica(cruda: String): String {
        val t = cruda.uppercase()
        return when {
            "RAPPI" in t -> RAPPICARD
            // Si en Wallet le pusiste un apodo a tu tarjeta, agrégalo aquí (p. ej. "MI APODO" in t || ...).
            ("BBVA" in t && ("TDC" in t || "CRÉDITO" in t || "CREDITO" in t)) -> BBVA_CREDITO
            "BBVA" in t || "BANCOMER" in t -> BBVA_DEBITO
            t == "NU" || t.startsWith("NU ") -> NU
            "BANAMEX" in t -> BANAMEX
            "MERCADO PAGO" in t -> MERCADO_PAGO
            else -> cruda.replace(digitos, "").trim()
        }
    }

    /** App del banco instalada en el teléfono; de ahí sale el logo de la tarjeta. */
    fun paquete(canonica: String): String? = when (canonica) {
        BBVA_CREDITO, BBVA_DEBITO -> "com.bancomer.mbanking"
        RAPPICARD -> "com.grability.rappi"
        NU -> "com.nu.production"
        BANAMEX -> "com.citibanamex.banamexmobile"
        MERCADO_PAGO -> "com.mercadopago.wallet"
        else -> null
    }

    /** Banco al que pertenece; null si no lo reconocemos. */
    fun banco(canonica: String): String? = when (canonica) {
        BBVA_CREDITO, BBVA_DEBITO -> "BBVA"
        RAPPICARD -> "Rappi"
        NU -> "Nu"
        BANAMEX -> "Banamex"
        MERCADO_PAGO -> "Mercado Pago"
        else -> null
    }
}

private const val MONTO = """([\d,]+(?:\.\d{1,2})?)"""

private fun monto(txt: String) = txt.replace(",", "").toDoubleOrNull()

private fun limpiar(comercio: String) = comercio.replace(Regex("""\s+"""), " ").trim()

/**
 *   título: "TACOS EL GUERO"
 *   texto:  "MXN150.00 con Rappicard ••1234"
 */
object WalletParser : FuenteNotificacion {
    override val id = "wallet"
    override fun esDeEsta(paquete: String) = paquete == "com.google.android.apps.walletnfcrel"

    private val regex = Regex(
        """^\s*([A-Z]{3})?\s*\$?\s*([\d.,]+)\s+(?:con|with)\s+(.+?)\s*$"""
    )

    override fun parse(titulo: String, texto: String): Pago? {
        val comercio = limpiar(titulo)
        if (comercio.isEmpty()) return null
        val m = regex.find(texto) ?: return null
        val (moneda, montoTxt, tarjeta) = m.destructured
        return Pago(
            comercio = comercio,
            monto = monto(montoTxt) ?: return null,
            tarjeta = Tarjetas.canonica(tarjeta),
            moneda = moneda.ifEmpty { "MXN" },
        )
    }
}

/**
 *   "Compra con TDC" / "COMPRA TDC EN 7 ELEVEN T1234 $50.00 03 noviembre 20:34h"
 *   "Aviso de cargo en tu cuenta" / "Tienes un cargo a tu cuenta *1234 de $250.00. ..."
 *   "Recibiste un abono en tu cuenta" / "Tienes un abono a tu cuenta *1234 de $1,000.00. ..."
 *   "BBVA" / "Abono a tu cuenta *51234 de $10,000.00"
 */
object BbvaParser : FuenteNotificacion {
    override val id = "bbva"
    override fun esDeEsta(paquete: String) = "bancomer" in paquete || "bbva" in paquete

    private val compra = Regex("""COMPRA\s+(TDC|TDD)\s+EN\s+(.+?)\s+\$\s?$MONTO""", RegexOption.IGNORE_CASE)
    private val cargo = Regex("""cargo\s+a\s+tu\s+cuenta\s+\*?(\d+)\s+de\s+\$\s?$MONTO""", RegexOption.IGNORE_CASE)
    private val abono = Regex("""abono\s+a\s+tu\s+cuenta\s+\*?(\d+)\s+de\s+\$\s?$MONTO""", RegexOption.IGNORE_CASE)

    override fun parse(titulo: String, texto: String): Pago? {
        val todo = "$titulo\n$texto"
        compra.find(todo)?.let { m ->
            val (tipo, comercio, montoTxt) = m.destructured
            return Pago(
                comercio = limpiar(comercio),
                monto = monto(montoTxt) ?: return null,
                tarjeta = if (tipo.equals("TDC", ignoreCase = true)) Tarjetas.BBVA_CREDITO else Tarjetas.BBVA_DEBITO,
            )
        }
        // El aviso de cargo no dice en qué fue; casi siempre son transferencias que haces tú.
        cargo.find(todo)?.let { m ->
            return Pago(
                comercio = "Cargo a cuenta *${m.groupValues[1].takeLast(4)}",
                monto = monto(m.groupValues[2]) ?: return null,
                tarjeta = Tarjetas.BBVA_DEBITO,
                tipo = Tipos.TRANSFERENCIA,
            )
        }
        abono.find(todo)?.let { m ->
            return Pago(
                comercio = "Abono a cuenta *${m.groupValues[1].takeLast(4)}",
                monto = monto(m.groupValues[2]) ?: return null,
                tarjeta = Tarjetas.BBVA_DEBITO,
                tipo = Tipos.INGRESO,
            )
        }
        return null
    }
}

/**
 *   "Compra por $ 500 fue aprobada" / "Ve los detalles de tu compra en FARMACIA CENTRO aquí."
 *   "MERPAGO*TELCEL te devolvió $25" / "Para más detalles, entra aquí."
 */
object MercadoPagoParser : FuenteNotificacion {
    override val id = "mercadopago"
    override fun esDeEsta(paquete: String) = "mercadopago" in paquete

    private val compra = Regex("""Compra\s+por\s+\$\s*$MONTO\s+fue\s+aprobada""", RegexOption.IGNORE_CASE)
    private val comercioCompra = Regex("""compra\s+en\s+(.+?)\s+aqu[ií]""", RegexOption.IGNORE_CASE)
    private val devolucion = Regex("""^(.+?)\s+te\s+devolvi[óo]\s+\$\s*$MONTO""", RegexOption.IGNORE_CASE)

    override fun parse(titulo: String, texto: String): Pago? {
        val todo = "$titulo\n$texto"
        compra.find(todo)?.let { m ->
            return Pago(
                comercio = comercioCompra.find(todo)?.groupValues?.get(1)?.let(::limpiar) ?: "Mercado Pago",
                monto = monto(m.groupValues[1]) ?: return null,
                tarjeta = Tarjetas.MERCADO_PAGO,
            )
        }
        devolucion.find(titulo)?.let { m ->
            return Pago(
                comercio = limpiar(m.groupValues[1]),
                monto = monto(m.groupValues[2]) ?: return null,
                tarjeta = Tarjetas.MERCADO_PAGO,
                tipo = Tipos.INGRESO,
            )
        }
        return null
    }
}

/**
 *   "Compra aprobada por $200.00" /
 *   "Compraste en CAFE CENTRO con tu tarjeta con terminación 1234 por $200.00."
 */
object NuParser : FuenteNotificacion {
    override val id = "nu"
    override fun esDeEsta(paquete: String) = paquete == "com.nu.production"

    private val regex = Regex(
        """Compraste\s+en\s+(.+?)\s+con\s+tu\s+tarjeta.*?\s+por\s+\$\s?$MONTO""", RegexOption.IGNORE_CASE
    )

    override fun parse(titulo: String, texto: String): Pago? {
        val m = regex.find(texto) ?: return null
        return Pago(
            comercio = limpiar(m.groupValues[1]),
            monto = monto(m.groupValues[2]) ?: return null,
            tarjeta = Tarjetas.NU,
        )
    }
}

/**
 *   "Realizaste una compra 🤑" / "Pagaste $50.00 en Oxxo con tu RappiCard digital."
 *   "Se realizó una compra 🤑" / "COMERCIO EJEMPLO usó $300.00 para pre-autorizar una compra con tu RappiCard digital."
 */
object RappiParser : FuenteNotificacion {
    override val id = "rappi"
    override fun esDeEsta(paquete: String) = paquete == "com.grability.rappi"

    private val pagaste = Regex("""Pagaste\s+\$\s?$MONTO\s+en\s+(.+?)\s+con\s+tu\s+RappiCard""", RegexOption.IGNORE_CASE)
    private val preautorizo = Regex("""^(.+?)\s+us[óo]\s+\$\s?$MONTO\s+para\s+pre-?autorizar""", RegexOption.IGNORE_CASE)

    override fun parse(titulo: String, texto: String): Pago? {
        pagaste.find(texto)?.let { m ->
            return Pago(limpiar(m.groupValues[2]), monto(m.groupValues[1]) ?: return null, Tarjetas.RAPPICARD)
        }
        preautorizo.find(texto)?.let { m ->
            return Pago(limpiar(m.groupValues[1]), monto(m.groupValues[2]) ?: return null, Tarjetas.RAPPICARD)
        }
        return null
    }
}

/**
 *   "Notificacion" /
 *   "Retiro/Compra TAQUERIA CENTRO  SN COSTCO BANAMEX123 monto $100.00 el 07/11/26 08:41:24 PM. Auto. 123456"
 */
object BanamexParser : FuenteNotificacion {
    override val id = "banamex"
    override fun esDeEsta(paquete: String) = "banamex" in paquete

    private val regex = Regex("""Retiro/Compra\s+(.+?)\s+monto\s+\$\s?$MONTO""", RegexOption.IGNORE_CASE)
    // Al final del comercio viene el nombre de la tarjeta, p. ej. "COSTCO BANAMEX123".
    private val nombreTarjeta = Regex("""\s+\S+\s+BANAMEX\d*\s*$""", RegexOption.IGNORE_CASE)

    override fun parse(titulo: String, texto: String): Pago? {
        val m = regex.find(texto) ?: return null
        return Pago(
            comercio = limpiar(m.groupValues[1].replace(nombreTarjeta, "")),
            monto = monto(m.groupValues[2]) ?: return null,
            tarjeta = Tarjetas.BANAMEX,
        )
    }
}

/** Reglas para detectar que dos notificaciones (p. ej. Wallet + banco) son el mismo movimiento. */
object Gemelas {
    /** Lo normal es que lleguen casi juntas; Wallet a veces avisa horas después (hasta un día). */
    const val VENTANA_MS = 48 * 60 * 60 * 1000L

    /** Dentro de este lapso basta con monto y banco; más allá, el comercio también debe parecerse. */
    const val CERCA_MS = 10 * 60 * 1000L

    fun sonGemelas(a: Transaccion, tarjeta: String, comercio: String, fecha: Long): Boolean =
        mismoBanco(a.tarjeta, tarjeta) &&
            (abs(a.fecha - fecha) <= CERCA_MS || mismoComercio(a.comercio, comercio))

    /** Prefijos de procesadores de pago que no dicen nada del comercio. */
    private val genericas = setOf(
        "rest", "clip", "wuzipay", "oplinea", "yolopag", "paypal", "mercadopago", "merpago", "stripe",
        "conekta", "openpay", "netpay", "billpocket", "evopay", "srpago", "getnet",
    )

    private fun normalizar(s: String) =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

    /** "PASTELERIA LUNA" ≈ "REST PASTELERIALUNA", "H-E-B" ≈ "HEB", "7 ELEVEN T1234" ≈ "7 Eleven". */
    fun mismoComercio(a: String, b: String): Boolean {
        val na = normalizar(a)
        val nb = normalizar(b)
        val juntoA = na.filter { it.isLetter() }
        val juntoB = nb.filter { it.isLetter() }
        if (juntoA.isEmpty() || juntoB.isEmpty()) return false
        if (juntoA == juntoB) return true
        // Nombres cortos o con guiones: "H-E-B" → "heb" es el inicio de "HEB CENTRO".
        val corto = minOf(juntoA, juntoB, compareBy { it.length })
        if (corto.length >= 3 && corto !in genericas && (juntoA.startsWith(corto) && juntoB.startsWith(corto))) return true
        fun palabras(s: String) = s.split(Regex("[^a-z]+")).filter { it.length >= 4 && it !in genericas }
        return palabras(na).any { it in juntoB } || palabras(nb).any { it in juntoA }
    }

    /** Si ambos bancos se conocen deben coincidir; si alguno no se sabe, se acepta. */
    fun mismoBanco(tarjetaA: String, tarjetaB: String): Boolean {
        val a = Tarjetas.banco(tarjetaA)
        val b = Tarjetas.banco(tarjetaB)
        return a == null || b == null || a == b
    }

    /** Nos quedamos con el nombre de tarjeta que sí reconocemos. */
    fun mejorTarjeta(existente: String, nueva: String) =
        if (Tarjetas.banco(existente) == null && Tarjetas.banco(nueva) != null) nueva else existente

    /** Si alguna de las dos dice que fue compra, es gasto (p. ej. cargo BBVA + tap en Wallet). */
    fun mejorTipo(existente: String, nuevo: String) =
        if (existente == Tipos.GASTO || nuevo == Tipos.GASTO) Tipos.GASTO else existente
}
