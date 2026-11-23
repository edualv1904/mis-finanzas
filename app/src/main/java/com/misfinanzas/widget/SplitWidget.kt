package com.misfinanzas.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.currentState
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
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
import com.misfinanzas.Ajustes
import com.misfinanzas.Cobros
import com.misfinanzas.Grupos
import com.misfinanzas.Grupos.YO
import com.misfinanzas.MainActivity
import com.misfinanzas.R
import com.misfinanzas.data.FinanzasDb
import com.misfinanzas.ui.DatosGrupos
import com.misfinanzas.ui.Tinta
import com.misfinanzas.ui.TintaSuave
import com.misfinanzas.ui.dinero
import kotlinx.coroutines.flow.combine
import kotlin.math.abs

/**
 * Widget de un split: el que eliges al ponerlo ([SplitWidgetConfigActivity]). Muestra cuánto debes o te
 * deben, lo gastado y (si cabe) quién le paga a quién; "+ Gasto" abre [SplitCapturaActivity]. Si ese
 * split se termina o se borra, se queda vacío.
 */
class SplitWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(CHICO, GRANDE))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        Ajustes.cargar(context)
        val dao = FinanzasDb.get(context).dao()
        val widgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        // Respaldo por si el estado del widget aún no lo tiene (widgets puestos antes de esta versión).
        val guardado = grupoDe(context, widgetId)
        val inicial = DatosGrupos(dao.gruposTodos(), dao.miembrosTodos(), dao.grupoGastosTodos(), dao.partesTodas())
        provideContent {
            val datos by remember {
                combine(dao.observarGrupos(), dao.observarMiembros(), dao.observarGrupoGastos(), dao.observarGrupoPartes()) { g, m, x, p ->
                    DatosGrupos(g, m, x, p)
                }
            }.collectAsState(inicial)
            // El split se lee del estado del widget: así, al elegirlo después de ponerlo, se redibuja.
            val grupoId = currentState<Preferences>()[CLAVE] ?: guardado
            Contenido(context, widgetId, grupoId, datos)
        }
    }

    @Composable
    private fun Contenido(ctx: Context, widgetId: Int, grupoId: Long?, datos: DatosGrupos) {
        val g = datos.grupos.firstOrNull { it.id == grupoId && it.activo }
        val tinta = ColorProvider(Tinta)
        val suave = ColorProvider(TintaSuave)
        val grande = LocalSize.current.height >= GRANDE.height
        val fondo = GlanceModifier.fillMaxSize().background(Color.White).cornerRadius(24.dp)

        if (g == null) {
            // Vacío: el split terminó o se borró. Tocarlo deja elegir otro.
            Column(
                fondo.padding(16.dp).clickable(actionStartActivity(configurar(ctx, widgetId))),
                verticalAlignment = Alignment.CenterVertically,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Sin split activo", style = TextStyle(color = suave, fontSize = 14.sp, fontWeight = FontWeight.Medium))
                Text("Toca para elegir otro", style = TextStyle(color = suave, fontSize = 12.sp))
            }
            return
        }

        val mio = datos.saldos(g.id)[YO] ?: 0.0
        val etiqueta = when {
            mio > 0.005 -> "TE DEBEN"
            mio < -0.005 -> "DEBES"
            else -> "ESTÁS A MANO"
        }
        val gastos = datos.gastosDe[g.id].orEmpty()
        val total = gastos.filter { it.tipo == Grupos.GASTO }.sumOf { it.monto }
        val miParte = Grupos.miGasto(gastos, datos.partesDelGrupo(g.id))
        val abrirSplit = actionStartActivity(
            Intent(ctx, MainActivity::class.java)
                .putExtra(Cobros.EXTRA_ABRIR, "split:${g.id}")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        )
        val agregar = actionStartActivity(
            Intent(ctx, SplitCapturaActivity::class.java)
                .putExtra(SplitCapturaActivity.EXTRA_GRUPO, g.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )

        if (!grande) {
            // 4×1: nombre, saldo y un "+" redondo.
            Row(fondo.padding(start = 18.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(GlanceModifier.defaultWeight().clickable(abrirSplit)) {
                    Text(g.nombre, maxLines = 1, style = TextStyle(color = tinta, fontSize = 13.sp, fontWeight = FontWeight.Bold))
                    Text(
                        if (abs(mio) > 0.005) "${etiqueta.lowercase().replaceFirstChar { it.uppercase() }} ${dinero(abs(mio))}" else "Estás a mano",
                        maxLines = 1, style = TextStyle(color = tinta, fontSize = 19.sp, fontWeight = FontWeight.Bold),
                    )
                }
                Box(
                    GlanceModifier.size(48.dp).cornerRadius(24.dp).background(Tinta).clickable(agregar),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(ImageProvider(R.drawable.ic_agregar), "Agregar gasto", GlanceModifier.size(22.dp), colorFilter = ColorFilter.tint(ColorProvider(Color.White)))
                }
            }
            return
        }

        // 4×3: todo.
        Column(fondo.padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 14.dp)) {
            Row(GlanceModifier.fillMaxWidth().clickable(abrirSplit), verticalAlignment = Alignment.CenterVertically) {
                Image(ImageProvider(R.drawable.ic_people), null, GlanceModifier.size(18.dp), colorFilter = ColorFilter.tint(suave))
                Spacer(GlanceModifier.width(8.dp))
                Text(g.nombre, GlanceModifier.defaultWeight(), maxLines = 1, style = TextStyle(color = tinta, fontSize = 15.sp, fontWeight = FontWeight.Bold))
                Text("Split", style = TextStyle(color = suave, fontSize = 12.sp))
            }
            Spacer(GlanceModifier.height(8.dp))
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Column(GlanceModifier.defaultWeight().clickable(abrirSplit)) {
                    Text(etiqueta, style = TextStyle(color = suave, fontSize = 11.sp, fontWeight = FontWeight.Medium))
                    Text(dinero(abs(mio)), style = TextStyle(color = tinta, fontSize = 28.sp, fontWeight = FontWeight.Bold))
                }
                Row(
                    GlanceModifier.height(44.dp).cornerRadius(22.dp).background(Tinta).padding(horizontal = 16.dp).clickable(agregar),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Image(ImageProvider(R.drawable.ic_agregar), null, GlanceModifier.size(18.dp), colorFilter = ColorFilter.tint(ColorProvider(Color.White)))
                    Spacer(GlanceModifier.width(6.dp))
                    Text("Gasto", style = TextStyle(color = ColorProvider(Color.White), fontSize = 15.sp, fontWeight = FontWeight.Medium))
                }
            }
            Text("Gastaron ${dinero(total)} · tu parte ${dinero(miParte)}", maxLines = 1, style = TextStyle(color = suave, fontSize = 12.sp))
            val transf = datos.transferencias(g.id)
            // Glance dibuja máximo 10 hijos por columna: "Para quedar a mano" va en su propio bloque.
            if (transf.isNotEmpty()) Column(GlanceModifier.fillMaxWidth()) {
                Spacer(GlanceModifier.height(8.dp))
                Box(GlanceModifier.fillMaxWidth().height(1.dp).background(Color(0xFFE5E7EB))) {}
                Spacer(GlanceModifier.height(8.dp))
                Text("Para quedar a mano", style = TextStyle(color = suave, fontSize = 12.sp, fontWeight = FontWeight.Medium))
                // Tus renglones primero (son los que te importan), luego los demás.
                transf.sortedByDescending { it.de == YO || it.a == YO }.take(4).forEach { t ->
                    val tuyo = t.de == YO || t.a == YO
                    val estilo = TextStyle(color = tinta, fontSize = 13.sp, fontWeight = if (tuyo) FontWeight.Bold else FontWeight.Normal)
                    Row(GlanceModifier.fillMaxWidth().padding(top = 4.dp).clickable(abrirSplit)) {
                        Text("${quien(t.de)} → ${quien(t.a)}", GlanceModifier.defaultWeight(), maxLines = 1, style = estilo)
                        Text(dinero(t.monto), style = estilo)
                    }
                }
                if (transf.size > 4) Text("y ${transf.size - 4} más", style = TextStyle(color = suave, fontSize = 12.sp))
            }
        }
    }

    private fun quien(p: String) = if (p == YO) "Tú" else p

    private fun configurar(ctx: Context, widgetId: Int) =
        Intent(ctx, SplitWidgetConfigActivity::class.java)
            .putExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)

    companion object {
        private val CHICO = DpSize(250.dp, 60.dp)
        private val GRANDE = DpSize(250.dp, 170.dp)
        private const val PREFS = "split_widget"
        val CLAVE = longPreferencesKey("grupo")

        fun grupoDe(ctx: Context, widgetId: Int): Long? =
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("w$widgetId", -1).takeIf { it >= 0 }

        fun elegir(ctx: Context, widgetId: Int, grupoId: Long) =
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong("w$widgetId", grupoId).apply()

        fun olvidar(ctx: Context, widgetIds: IntArray) =
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply { widgetIds.forEach { remove("w$it") } }.apply()

        /** Redibuja todos los widgets de split (después de guardar un gasto, terminar un split…). */
        suspend fun refrescar(ctx: Context) = runCatching { SplitWidget().updateAll(ctx.applicationContext) }
    }
}

class SplitWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = SplitWidget()

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        SplitWidget.olvidar(context, appWidgetIds)
    }
}
