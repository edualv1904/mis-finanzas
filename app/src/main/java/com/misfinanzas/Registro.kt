package com.misfinanzas

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import androidx.glance.appwidget.updateAll
import com.misfinanzas.data.CategoriaComercio
import com.misfinanzas.data.FinanzasDb
import com.misfinanzas.data.Transaccion
import com.misfinanzas.widget.FinanzasWidget
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File

/** Llaves que evitan registrar dos veces la misma notificación. */
object Claves {
    /**
     * Wallet reutiliza la misma notificación (misma key) y solo le cambia el nombre del comercio
     * y la hora, así que la key basta. Los bancos mandan una notificación por movimiento con
     * fecha/hora o monto en el texto, así que usamos el texto.
     */
    fun de(fuenteId: String, sbnKey: String, titulo: String, texto: String) =
        if (fuenteId == WalletParser.id) "${WalletParser.id}|$sbnKey" else "$fuenteId|$titulo|$texto"

    /** Convierte el formato viejo "fuente|<key de 5 partes>|fecha|titulo|texto" al actual. */
    fun migrar(vieja: String): String {
        val p = vieja.split("|")
        if (p.size < 9) return vieja
        return de(p[0], p.subList(1, 6).joinToString("|"), p[7], p.drop(8).joinToString("|"))
    }
}

/** Toda escritura pasa por aquí para que el widget siempre muestre los totales al día. */
object Registro {
    enum class Resultado { NUEVO, FUSIONADO, REPETIDO }

    // Wallet y el banco avisan casi al mismo tiempo: procesamos un pago a la vez para que no se cuelen ambos.
    private val candado = Mutex()

    suspend fun registrarPago(ctx: Context, pago: Pago, fuente: String, fecha: Long, clave: String): Resultado =
        candado.withLock {
            val dao = FinanzasDb.get(ctx).dao()
            if (dao.claveVista(clave)) return Resultado.REPETIDO

            val gemela = dao.candidatasGemelas(
                monto = pago.monto,
                desde = fecha - Gemelas.VENTANA_MS,
                hasta = fecha + Gemelas.VENTANA_MS,
                fuente = fuente,
            ).firstOrNull { Gemelas.mismoBanco(it.tarjeta, pago.tarjeta) }

            if (gemela != null) {
                dao.actualizar(
                    gemela.copy(
                        tarjeta = Gemelas.mejorTarjeta(gemela.tarjeta, pago.tarjeta),
                        tipo = Gemelas.mejorTipo(gemela.tipo, pago.tipo),
                        claveVinculada = clave,
                    )
                )
                refrescarWidgets(ctx)
                return Resultado.FUSIONADO
            }

            val categoria = dao.categoriaDe(pago.comercio) ?: Categorias.adivinar(pago.comercio)
            dao.insertar(
                Transaccion(
                    monto = pago.monto,
                    moneda = pago.moneda,
                    comercio = pago.comercio,
                    tarjeta = pago.tarjeta,
                    fecha = fecha,
                    categoria = categoria,
                    fuente = fuente,
                    claveUnica = clave,
                    tipo = pago.tipo,
                )
            )
            refrescarWidgets(ctx)
            Resultado.NUEVO
        }

    /**
     * Registra las notificaciones que etiquetaste en la pantalla de recolección y que ahora sí
     * sabemos leer. Tu etiqueta manda sobre el tipo que adivine el parser. Es seguro repetirlo.
     */
    suspend fun importarCrudasEtiquetadas(ctx: Context): Int {
        var nuevas = 0
        for (n in FinanzasDb.get(ctx).dao().crudasEtiquetadas()) {
            val fuente = Fuentes.para(n.paquete) ?: continue
            val pago = fuente.parse(n.titulo, n.texto)?.copy(tipo = n.etiqueta ?: continue) ?: continue
            val clave = Claves.de(fuente.id, sbnKey = "", n.titulo, n.texto)
            if (registrarPago(ctx, pago, fuente.id, n.fecha, clave) == Resultado.NUEVO) nuevas++
        }
        return nuevas
    }

    /**
     * Carga movimientos preparados en la PC (herramientas/importar_estados.py --exportar), p. ej. de
     * estados de cuenta. El archivo se deja en files/importar.json y se borra al terminar. Las llaves
     * únicas evitan duplicar si se carga dos veces.
     */
    suspend fun importarArchivo(ctx: Context): Int {
        val archivo = File(ctx.filesDir, ARCHIVO_IMPORTAR)
        if (!archivo.exists()) return 0
        val datos = JSONObject(archivo.readText())
        val db = FinanzasDb.get(ctx)
        val dao = db.dao()
        var cargados = 0
        db.withTransaction {
            val nuevos = datos.getJSONArray("nuevos")
            for (i in 0 until nuevos.length()) {
                val n = nuevos.getJSONObject(i)
                val id = dao.insertar(
                    Transaccion(
                        monto = n.getDouble("monto"),
                        moneda = n.getString("moneda"),
                        comercio = n.getString("comercio"),
                        tarjeta = n.getString("tarjeta"),
                        fecha = n.getLong("fecha"),
                        categoria = n.getString("categoria"),
                        fuente = n.getString("fuente"),
                        claveUnica = n.getString("clave"),
                        tipo = n.getString("tipo"),
                    )
                )
                if (id != -1L) cargados++
            }
            val recategorizar = datos.getJSONArray("recategorizar")
            for (i in 0 until recategorizar.length()) {
                val r = recategorizar.getJSONObject(i)
                dao.recategorizar(r.getLong("id"), r.getString("categoria"))
            }
        }
        archivo.delete()
        refrescarWidgets(ctx)
        Log.i("Registro", "Importados $cargados movimientos de $ARCHIVO_IMPORTAR")
        return cargados
    }

    private const val ARCHIVO_IMPORTAR = "importar.json"

    suspend fun guardar(ctx: Context, t: Transaccion, recordarCategoria: Boolean) {
        val dao = FinanzasDb.get(ctx).dao()
        if (t.id == 0L) dao.insertar(t) else dao.actualizar(t)
        // Si elegiste otra categoría, la recordamos para los siguientes pagos en ese comercio.
        if (recordarCategoria) dao.guardarCategoria(CategoriaComercio(t.comercio, t.categoria))
        refrescarWidgets(ctx)
    }

    suspend fun eliminar(ctx: Context, t: Transaccion) {
        FinanzasDb.get(ctx).dao().eliminar(t)
        refrescarWidgets(ctx)
    }

    suspend fun refrescarWidgets(ctx: Context) = FinanzasWidget().updateAll(ctx)
}
