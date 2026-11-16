package com.misfinanzas.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp

/** Texto y botones principales: casi negro sobre blanco. */
val Tinta = Color(0xFF111418)
val TintaSuave = Color(0xFF6B7280)
/** Fondo de tarjetas y recuadros sobre el blanco. */
val Niebla = Color(0xFFF4F5F7)
/** El único color con significado: dinero que entra. */
val Ingreso = Color(0xFF1E8E5A)

// La app siempre es blanca, sin importar el modo oscuro del sistema ni el fondo de pantalla.
private val Colores = lightColorScheme(
    primary = Tinta,
    onPrimary = Color.White,
    primaryContainer = Tinta,
    onPrimaryContainer = Color.White,
    secondary = Tinta,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE6E8EC),
    onSecondaryContainer = Tinta,
    tertiary = Tinta,
    onTertiary = Color.White,
    background = Color.White,
    onBackground = Tinta,
    surface = Color.White,
    onSurface = Tinta,
    // Distinto de Niebla a propósito: si coinciden, Material pinta el texto de las tarjetas en gris.
    surfaceVariant = Color(0xFFECEEF1),
    onSurfaceVariant = TintaSuave,
    surfaceTint = Color.Transparent,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainer = Niebla,
    surfaceContainerHigh = Color.White,
    surfaceContainerHighest = Niebla,
    outline = Color(0xFFC9CED6),
    outlineVariant = Color(0xFFE5E7EB),
    error = Color(0xFFC62F3B),
    onError = Color.White,
    errorContainer = Color(0xFFFDECEC),
    onErrorContainer = Color(0xFF7A1620),
)

// Cifras de ancho fijo para que los montos queden alineados en las listas.
private fun TextStyle.cifras() = copy(fontFeatureSettings = "tnum")

private val Tipografia = Typography().run {
    copy(
        displaySmall = displaySmall.cifras(),
        headlineSmall = headlineSmall.cifras(),
        titleLarge = titleLarge.cifras(),
        titleMedium = titleMedium.cifras(),
        titleSmall = titleSmall.cifras(),
        bodyLarge = bodyLarge.cifras(),
        bodyMedium = bodyMedium.cifras(),
        bodySmall = bodySmall.cifras(),
        labelLarge = labelLarge.cifras(),
        labelMedium = labelMedium.cifras(),
        labelSmall = labelSmall.cifras(),
    )
}

private val Formas = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

@Composable
fun Tema(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colores, typography = Tipografia, shapes = Formas, content = content)
}
