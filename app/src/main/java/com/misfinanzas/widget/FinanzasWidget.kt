package com.misfinanzas.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import androidx.compose.ui.graphics.Color
import com.misfinanzas.CapturaRapidaActivity
import com.misfinanzas.MainActivity
import com.misfinanzas.data.FinanzasDb
import com.misfinanzas.ui.Iconos
import com.misfinanzas.ui.Tinta
import com.misfinanzas.ui.TintaSuave
import com.misfinanzas.ui.dinero
import com.misfinanzas.Ajustes
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import java.time.ZoneId

class FinanzasWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        Ajustes.cargar(context)
        val dao = FinanzasDb.get(context).dao()
        val zona = ZoneId.systemDefault()
        val hoy = LocalDate.now()
        val lunes = hoy.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        fun ms(d: LocalDate) = d.atStartOfDay(zona).toInstant().toEpochMilli()

        val rangoSemana = ms(lunes) to ms(lunes.plusWeeks(1))
        val rangoHoy = ms(hoy) to ms(hoy.plusDays(1))
        val semanaInicial = dao.sumaGastos(rangoSemana.first, rangoSemana.second)
        val hoyInicial = dao.sumaGastos(rangoHoy.first, rangoHoy.second)

        // provideGlance solo corre al "despertar" el widget; las actualizaciones posteriores solo
        // redibujan. Por eso los totales se escuchan de la base de datos en vez de leerse una vez.
        provideContent {
            val totalSemana by remember { dao.observarSumaGastos(rangoSemana.first, rangoSemana.second) }.collectAsState(semanaInicial)
            val totalHoy by remember { dao.observarSumaGastos(rangoHoy.first, rangoHoy.second) }.collectAsState(hoyInicial)
            Contenido(totalSemana, totalHoy)
        }
    }

    @Composable
    private fun Contenido(totalSemana: Double, totalHoy: Double) {
        val tinta = ColorProvider(Tinta)
        val suave = ColorProvider(TintaSuave)
        Column(GlanceModifier.fillMaxSize().background(Color.White).cornerRadius(20.dp).padding(14.dp)) {
            Column(GlanceModifier.fillMaxWidth().clickable(actionStartActivity<MainActivity>())) {
                Text("Gastado esta semana", style = TextStyle(color = suave, fontSize = 12.sp))
                Text(dinero(totalSemana), style = TextStyle(color = tinta, fontSize = 24.sp, fontWeight = FontWeight.Bold))
                Text("Hoy ${dinero(totalHoy)}", style = TextStyle(color = suave, fontSize = 12.sp))
            }
            Spacer(GlanceModifier.height(10.dp))
            Row(GlanceModifier.fillMaxWidth()) {
                ATAJOS.forEachIndexed { i, nombre ->
                    val estilo = Iconos.de(nombre)
                    if (i > 0) Spacer(GlanceModifier.width(6.dp))
                    Box(
                        GlanceModifier
                            .defaultWeight()
                            .height(40.dp)
                            .cornerRadius(12.dp)
                            .background(estilo.color.copy(alpha = 0.12f))
                            .clickable(
                                actionStartActivity<CapturaRapidaActivity>(
                                    actionParametersOf(KEY_CATEGORIA to nombre)
                                )
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Image(
                            provider = ImageProvider(estilo.icono),
                            contentDescription = nombre,
                            colorFilter = ColorFilter.tint(ColorProvider(estilo.color)),
                            modifier = GlanceModifier.size(20.dp),
                        )
                    }
                }
            }
            Spacer(GlanceModifier.height(6.dp))
            Box(
                GlanceModifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .cornerRadius(12.dp)
                    .background(Tinta)
                    .clickable(actionStartActivity<CapturaRapidaActivity>()),
                contentAlignment = Alignment.Center,
            ) {
                Text("Agregar movimiento", style = TextStyle(color = ColorProvider(Color.White), fontSize = 14.sp, fontWeight = FontWeight.Medium))
            }
        }
    }

    private companion object {
        val ATAJOS = listOf("Comida", "Súper", "Transporte", "Gasolina")
        val KEY_CATEGORIA = ActionParameters.Key<String>(CapturaRapidaActivity.EXTRA_CATEGORIA)
    }
}

class FinanzasWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = FinanzasWidget()
}
