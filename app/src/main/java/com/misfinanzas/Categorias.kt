package com.misfinanzas

object Categorias {
    const val INTERESES = "Intereses y comisiones"

    val todas = listOf(
        "Comida", "Súper", "Transporte", "Gasolina", "Entretenimiento", "Compras", "Salud", "Servicios",
        // Solo la usan los movimientos cargados de estados de cuenta (intereses, IVA y comisiones).
        INTERESES, "Otros",
    )

    // Agrega aquí los comercios que uses seguido (en MAYÚSCULAS, separados por |).
    // El orden importa: "OXXO GAS" va a Gasolina antes que OXXO a Súper, y "CHOPO ... HEB" a Salud antes que HEB a Súper.
    private val reglas = listOf(
        "Gasolina" to Regex("""GAS\b|GASOL\b|GASOLIN|PEMEX|SHELL|\bBP\b|MOBIL|G500|ARCO"""),
        "Comida" to Regex("""RAPPI|UBER ?EATS|TIM HORTONS|^TH |LITTLE CAES|HELADOS|POSTRERIA|DAIRY QUEEN|MARISCOS|RAMEN|SUSHI|TACO|TAQUER|REST|CAFE|COFFEE|CAFFENIO|STARBUCKS|PIZZ|CARLS|CARL'S|FAST FOOD|ENSALAD|CAKE|PASTEL|PANADER|VENDING|BURGER|POLLO|WINGS|KFC|MCDONALD|DOMINO|LITTLE CAESAR|TORTAS|COCINA|BAR\b|DELI"""),
        "Salud" to Regex("""CHOPO|FARMACIA|\bFARM\b|\bF AHORRO|SIMILARES|BENAVIDES|DEL AHORRO|SAN PABLO|HOSPITAL|DOCTOR|LABORATORIO"""),
        "Súper" to Regex("""ABARROTES|CARNES|OXXO|7.ELEVEN|WALMART|WAL-MART|SORIANA|CHEDRAUI|COSTCO|SAMS|SAM'S|\bHEB\b|H-E-B|LA COMER|BODEGA|SUPER|CITY MARKET|FRESKO|ALSUPER|CIRCLE K|EXTRA"""),
        "Transporte" to Regex("""UBR|AUTOPISTA|UBER|DIDI|CABIFY|METRO|ESTACIONAMIENTO|PARKING|CASETA|IAVE|TAG"""),
        "Entretenimiento" to Regex("""CRUNCHYROLL|TIKTOK|CINE|CINEPOLIS|CINEMEX|SPOTIFY|NETFLIX|DISNEY|HBO|MAX\b|YOUTUBE|STEAM|PLAYSTATION|XBOX|TICKETMASTER"""),
        "Servicios" to Regex("""PAGO DE SERVICIOS|TELCEL|RADIOMOVIL|DIPSA|AT&T|IZZI|TOTALPLAY|TELMEX|MEGACABLE|CFE|AGUA|GOOGLEONE|GOOGLE ONE|ICLOUD|APPLE\.COM"""),
        "Compras" to Regex("""FERRE|MINISO|LIVERPOOL|PALACIO|AMAZON|MERCADO ?LIBRE|SEARS|COPPEL|ZARA|H&M|NIKE|ADIDAS|SUBURBIA|HOME DEPOT"""),
    )

    fun adivinar(comercio: String): String {
        val c = comercio.uppercase()
        return reglas.firstOrNull { it.second.containsMatchIn(c) }?.first ?: "Otros"
    }
}
