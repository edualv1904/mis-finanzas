package com.misfinanzas

import com.misfinanzas.data.Transaccion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Textos con el formato exacto de cada app (comercios, montos y dígitos inventados). */
class ParsersTest {
    private fun assertPago(p: Pago?, comercio: String, monto: Double, tarjeta: String, tipo: String = Tipos.GASTO) {
        requireNotNull(p) { "no se reconoció" }
        assertEquals(comercio, p.comercio)
        assertEquals(monto, p.monto, 0.001)
        assertEquals(tarjeta, p.tarjeta)
        assertEquals(tipo, p.tipo)
    }

    @Test
    fun wallet() {
        assertPago(WalletParser.parse("SUSHI CENTRO", "MXN450.50 con Rappicard ••1234"), "SUSHI CENTRO", 450.50, "Rappicard")
        assertPago(WalletParser.parse("OXXO CENTRO", "MXN120.00 con Nu Mastercard Gold ••5678"), "OXXO CENTRO", 120.0, "Nu")
        assertPago(WalletParser.parse("7 ELEVEN T1234        CEN", "MXN50.00 con BBVA TDC ••4321"), "7 ELEVEN T1234 CEN", 50.0, "BBVA Crédito")
        assertPago(WalletParser.parse("LIVERPOOL", "MXN1,234.50 con Visa ••1111"), "LIVERPOOL", 1234.50, "Visa")
        assertNull(WalletParser.parse("Preparando tu recibo", "Agregaremos la ubicación a tu recibo"))
    }

    @Test
    fun bbva() {
        assertPago(
            BbvaParser.parse("Compra con TDC", "COMPRA TDC EN 7 ELEVEN T1234 \$50.00 03 noviembre 20:34h"),
            "7 ELEVEN T1234", 50.0, "BBVA Crédito",
        )
        assertPago(
            BbvaParser.parse(
                "Aviso de cargo en tu cuenta",
                "Tienes un cargo a tu cuenta *1234 de \$3,000.00. Esta información te ayuda a tener control en tus finanzas. Abre para más detalles. 13 de noviembre de 2026, 07:57 h",
            ),
            "Cargo a cuenta *1234", 3000.0, "BBVA Débito", Tipos.TRANSFERENCIA,
        )
        assertPago(
            BbvaParser.parse(
                "Recibiste un abono en tu cuenta",
                "Tienes un abono a tu cuenta *1234 de \$1,000.00. Esta información te ayuda a tener control en tus finanzas. Abre para más detalles. 05 de noviembre de 2026, 10:21 h",
            ),
            "Abono a cuenta *1234", 1000.0, "BBVA Débito", Tipos.INGRESO,
        )
        assertPago(BbvaParser.parse("BBVA", "Abono a tu cuenta *51234 de \$10,000.00 "), "Abono a cuenta *1234", 10000.0, "BBVA Débito", Tipos.INGRESO)
        assertNull(BbvaParser.parse("Sí a avanzar en tus finanzas 🎉", "¡Enhorabuena por tu incremento de línea en tu TDC BBVA! Y para celebrar, tienes un 13% de descuento disponible."))
        assertNull(BbvaParser.parse("Hasta \$9,000 bonificados 🎉", "Este Buen Fin, compra en línea y recibe de cada tienda hasta el 25% de vuelta."))
    }

    @Test
    fun mercadoPago() {
        assertPago(
            MercadoPagoParser.parse("Compra por \$ 1,500 fue aprobada", "Ve los detalles de tu compra en CHOPO 1234 HEB CENTRO aquí."),
            "CHOPO 1234 HEB CENTRO", 1500.0, "Mercado Pago",
        )
        assertPago(MercadoPagoParser.parse("MERPAGO*TELCEL te devolvió \$25", "Para más detalles, entra aquí."), "MERPAGO*TELCEL", 25.0, "Mercado Pago", Tipos.INGRESO)
        assertNull(MercadoPagoParser.parse("¡No te olvides de tus \$ 10,000!💸", "Recuerda que tú eliges en cuántos meses quieres pagar"))
        assertNull(MercadoPagoParser.parse("Llegó un recibo que vence el 22 de noviembre", "Ya puedes programar o hacer tu pago de \$ 300 a Telcel."))
    }

    @Test
    fun nu() {
        assertPago(
            NuParser.parse("Compra aprobada por \$200.00", "Compraste en CARLS JR CENTRO con tu tarjeta con terminación 5678 por \$200.00."),
            "CARLS JR CENTRO", 200.0, "Nu",
        )
        assertPago(
            NuParser.parse("Devolución por \$100.00", "Recibiste una devolución de RECARGAS CENTRO a tu tarjeta con terminación 1234 por \$100.00."),
            "RECARGAS CENTRO", 100.0, "Nu", Tipos.INGRESO,
        )
        assertNull(NuParser.parse("Con \$200 puedes tener más crédito", "Empieza a ahorrar en una Cajita, aumenta tu línea de crédito y gana más poder de compra."))
    }

    @Test
    fun rappi() {
        assertPago(RappiParser.parse("Realizaste una compra 🤑", "Pagaste \$50.00 en Oxxo con tu RappiCard digital."), "Oxxo", 50.0, "Rappicard")
        assertPago(
            RappiParser.parse("Se realizó una compra 🤑", "COMERCIO EJEMPLO usó \$300.00 para pre-autorizar una compra con tu RappiCard digital."),
            "COMERCIO EJEMPLO", 300.0, "Rappicard",
        )
        assertNull(RappiParser.parse("KFC 6 tiras \$229", "Pide ya"))
    }

    @Test
    fun banamex() {
        assertPago(
            BanamexParser.parse("Notificacion", "Retiro/Compra TAQUERIA CENTRO  SN COSTCO BANAMEX123 monto \$100.00 el 07/11/26 08:41:24 PM. Auto. 123456"),
            "TAQUERIA CENTRO SN", 100.0, "Banamex",
        )
        assertPago(
            BanamexParser.parse("Notificacion", "Retiro/Compra COSTCO CENTRO    GEN COSTCO BANAMEX123 monto \$800.50 el 08/11/26 07:37:45 PM. Auto. 654321"),
            "COSTCO CENTRO GEN", 800.50, "Banamex",
        )
    }

    @Test
    fun fuentesPorPaquete() {
        assertEquals(WalletParser, Fuentes.para("com.google.android.apps.walletnfcrel"))
        assertEquals(BbvaParser, Fuentes.para("com.bancomer.mbanking"))
        assertEquals(MercadoPagoParser, Fuentes.para("com.mercadopago.wallet"))
        assertEquals(NuParser, Fuentes.para("com.nu.production"))
        assertEquals(RappiParser, Fuentes.para("com.grability.rappi"))
        assertEquals(BanamexParser, Fuentes.para("com.citibanamex.banamexmobile"))
        assertNull(Fuentes.para("com.whatsapp"))
    }

    @Test
    fun clavesWalletIgnoranActualizaciones() {
        // La misma notificación de Wallet, antes y después de que le cambie el nombre al comercio.
        val a = "wallet|0|com.google.android.apps.walletnfcrel|1001|-100000000|10000|1790000000000|PIZZERIA CENTR|MXN300.00 con Rappicard ••1234"
        val b = "wallet|0|com.google.android.apps.walletnfcrel|1001|-100000000|10000|1790000300000|Pizzeria Centro|MXN300.00 con Rappicard ••1234"
        assertEquals(Claves.migrar(a), Claves.migrar(b))
        assertEquals("wallet|0|com.google.android.apps.walletnfcrel|1001|-100000000|10000", Claves.migrar(a))
        assertEquals(Claves.migrar(a), Claves.de("wallet", "0|com.google.android.apps.walletnfcrel|1001|-100000000|10000", "x", "y"))
        // Los bancos se distinguen por el texto.
        assertEquals(
            "bbva|Compra con TDC|COMPRA TDC EN X \$1.00",
            Claves.migrar("bbva|0|com.bancomer.mbanking|1|tag|10|123|Compra con TDC|COMPRA TDC EN X \$1.00"),
        )
    }

    @Test
    fun gemelas() {
        assertTrue(Gemelas.mismoBanco("Visa", "BBVA Débito"))
        assertTrue(Gemelas.mismoBanco("BBVA Crédito", "BBVA Débito"))
        assertFalse(Gemelas.mismoBanco("Nu", "BBVA Crédito"))
        assertFalse(Gemelas.mismoBanco("Rappicard", "Nu"))
        assertEquals("BBVA Débito", Gemelas.mejorTarjeta("Visa", "BBVA Débito"))
        assertEquals("Rappicard", Gemelas.mejorTarjeta("Rappicard", "Rappicard"))
        assertEquals(Tipos.GASTO, Gemelas.mejorTipo(Tipos.TRANSFERENCIA, Tipos.GASTO))
        assertEquals(Tipos.INGRESO, Gemelas.mejorTipo(Tipos.INGRESO, Tipos.INGRESO))
    }

    /** Wallet y el banco casi nunca escriben igual el nombre del comercio. */
    @Test
    fun mismoComercio() {
        listOf(
            "OXXO CENTRO MTZ" to "Oxxo",
            "PASTELERIA LUNA" to "REST PASTELERIALUNA",
            "H-E-B" to "HEB",
            "7 ELEVEN T1234" to "7 Eleven",
            "CLINICA EJEMPLO DE" to "CLINICA EJEMPLO DE MEXICO",
            "CARLS JR CENTRO I" to "Carls Jr",
            "WUZIPAY*TACOS LUNA" to "WUZIPAY TACOS LUNA",
            "HEB CENTRO" to "H-E-B",
        ).forEach { (a, b) -> assertTrue("$a ≈ $b", Gemelas.mismoComercio(a, b)) }
        listOf(
            "OXXO CENTRO" to "7 Eleven",
            "REST CAFE SOL" to "REST PASTELERIALUNA",
            "CLIP MX*REST LUNA" to "CLIP MX*GAS SERVICIO T",
            "CAFFENIO" to "Starbucks",
        ).forEach { (a, b) -> assertFalse("$a ≠ $b", Gemelas.mismoComercio(a, b)) }
    }

    @Test
    fun gemelasConRetraso() {
        val hora = 3_600_000L
        val bbva = Transaccion(
            monto = 2000.00, comercio = "CLINICA EJEMPLO DE", tarjeta = Tarjetas.BBVA_CREDITO,
            fecha = 0L, categoria = "Otros", fuente = "bbva",
        )
        // Wallet avisó 12 h (y hasta un día) después: es la misma compra.
        assertTrue(Gemelas.sonGemelas(bbva, "BBVA TDC", "CLINICA EJEMPLO DE MEXICO", 12 * hora))
        assertTrue(Gemelas.sonGemelas(bbva, "BBVA TDC", "Clinica Ejemplo", 30 * hora))
        // Mismo monto, otro comercio, horas después: son dos compras.
        assertFalse(Gemelas.sonGemelas(bbva, "BBVA TDC", "Liverpool", 3 * hora))
        // Casi al mismo tiempo basta con monto y banco (Wallet a veces pone otro nombre).
        assertTrue(Gemelas.sonGemelas(bbva, "BBVA TDC", "Liverpool", 5 * 60_000L))
        assertTrue(Gemelas.VENTANA_MS < 49 * hora)
    }

    @Test
    fun categorias() {
        val esperadas = mapOf(
            "SUSHI CENTRO" to "Comida",
            "TAQUERIA CENTRO" to "Comida",
            "PIZZERIA CENTRO" to "Comida",
            "CARLS JR CENTRO" to "Comida",
            "STARBUCKS COFFEE" to "Comida",
            "CLIP MX*GAS SERVICIO T" to "Gasolina",
            "OXXO CENTRO" to "Súper",
            "7 ELEVEN T1234" to "Súper",
            "CHOPO 1234 HEB CENTRO" to "Salud",
            "FARMACIA GUADALAJARA" to "Salud",
            "F AHORRO SUC 12" to "Salud",
            "RADIOMOVIL DIPSA S.A. DE C.V." to "Servicios",
            "PAYPAL SPOTIFY P D" to "Entretenimiento",
            // Nombres tal como vienen en los estados de cuenta.
            "RAPPI" to "Comida",
            "DLO*SERV UBER EATS" to "Comida",
            "DLO*UBER" to "Transporte",
            "PAYPAL *UBRPAGOSMEX" to "Transporte",
            "TH CENTRO" to "Comida",
            "GASOL SERVICIO CENTRO" to "Gasolina",
            "AUTOPISTA CENTRO" to "Transporte",
            "Mercado Pago (comercio no especificado)" to "Otros",
            "COMERCIO DESCONOCIDO" to "Otros",
        )
        esperadas.forEach { (comercio, cat) -> assertEquals(comercio, cat, Categorias.adivinar(comercio)) }
    }
}
