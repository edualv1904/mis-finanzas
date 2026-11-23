package com.misfinanzas.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.lifecycle.lifecycleScope
import com.misfinanzas.Grupos
import com.misfinanzas.MainActivity
import com.misfinanzas.data.FinanzasDb
import com.misfinanzas.ui.DatosGrupos
import com.misfinanzas.ui.Tema
import com.misfinanzas.ui.dinero
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Al poner un widget de split (o al tocar uno vacío): "¿De qué split es este widget?" con los splits
 * activos. Cada widget queda ligado a uno.
 */
class SplitWidgetConfigActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val widgetId = intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        // Si sales sin elegir, Android no pone el widget.
        setResult(Activity.RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }
        val dao = FinanzasDb.get(this).dao()
        val actual = SplitWidget.grupoDe(this, widgetId)

        setContent {
            Tema {
                val datos by produceState<DatosGrupos?>(null) {
                    value = DatosGrupos(dao.gruposTodos(), dao.miembrosTodos(), dao.grupoGastosTodos(), dao.partesTodas())
                }
                var elegido by remember { mutableStateOf(actual) }
                val activos = datos?.grupos.orEmpty().filter { it.activo }.sortedByDescending { it.creado }
                if (elegido == null && activos.size == 1) elegido = activos.first().id
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Spacer(Modifier.height(24.dp))
                        Text("¿De qué split es este widget?", style = MaterialTheme.typography.headlineSmall)
                        Text(
                            "Puedes poner otro widget para otro split. Cuando este termine, el widget se queda vacío.",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        val d = datos
                        if (d != null && activos.isEmpty()) {
                            Text("No tienes splits activos. Crea uno en la app (Menú → Split) y vuelve a poner el widget.")
                            Button(onClick = {
                                startActivity(Intent(this@SplitWidgetConfigActivity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                finish()
                            }) { Text("Abrir la app") }
                        }
                        if (d != null) LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(activos, key = { it.id }) { g ->
                                val mio = d.saldos(g.id)[Grupos.YO] ?: 0.0
                                val sel = elegido == g.id
                                Card(
                                    Modifier.fillMaxWidth().clickable { elegido = g.id },
                                    border = if (sel) BorderStroke(2.dp, MaterialTheme.colorScheme.onSurface) else null,
                                    colors = CardDefaults.cardColors(),
                                ) {
                                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                        RadioButton(sel, { elegido = g.id })
                                        Column(Modifier.weight(1f)) {
                                            Text(g.nombre, style = MaterialTheme.typography.titleMedium)
                                            Text(
                                                (d.miembrosDe[g.id].orEmpty().joinToString(", ").ifEmpty { "Solo tú" }) +
                                                    " · ${d.gastosDe[g.id].orEmpty().count { it.tipo == Grupos.GASTO }} gastos",
                                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        if (abs(mio) > 0.005) Text(dinero(abs(mio)), fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        } else Spacer(Modifier.weight(1f))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { finish() }) { Text("Cancelar") }
                            Button(enabled = elegido != null && activos.any { it.id == elegido }, onClick = { poner(widgetId, elegido!!) }) {
                                Text("Poner widget")
                            }
                        }
                    }
                }
            }
        }
    }

    private fun poner(widgetId: Int, grupoId: Long) {
        SplitWidget.elegir(this, widgetId, grupoId)
        lifecycleScope.launch {
            runCatching {
                val glanceId = GlanceAppWidgetManager(applicationContext).getGlanceIdBy(widgetId)
                androidx.glance.appwidget.state.updateAppWidgetState(applicationContext, glanceId) { it[SplitWidget.CLAVE] = grupoId }
                SplitWidget().update(applicationContext, glanceId)
            }.onFailure { SplitWidget.refrescar(applicationContext) }
            setResult(Activity.RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
            finish()
        }
    }
}
