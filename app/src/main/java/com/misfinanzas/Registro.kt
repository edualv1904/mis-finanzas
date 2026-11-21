package com.misfinanzas

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import androidx.glance.appwidget.updateAll
import com.misfinanzas.data.CategoriaComercio
import com.misfinanzas.data.ClaveDescartada
import com.misfinanzas.data.FinanzasDb
import com.misfinanzas.data.Transaccion
import com.misfinanzas.widget.FinanzasWidget
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.time.YearMonth

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
            ).firstOrNull { Gemelas.sonGemelas(it, pago.tarjeta, pago.comercio, fecha) }

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
    /** Las notificaciones que marcaste "Ignorar" se borran solas a los 30 días de haber llegado. */
    suspend fun limpiarIgnoradas(ctx: Context): Int {
        val limite = System.currentTimeMillis() - DIAS_IGNORADAS * 86_400_000L
        return FinanzasDb.get(ctx).dao().borrarIgnoradasViejas(limite).also {
            if (it > 0) Log.i("Registro", "Borradas $it notificaciones ignoradas de hace más de $DIAS_IGNORADAS días")
        }
    }

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
            val recategorizar = datos.optJSONArray("recategorizar")
            for (i in 0 until (recategorizar?.length() ?: 0)) {
                val r = recategorizar!!.getJSONObject(i)
                dao.recategorizar(r.getLong("id"), r.getString("categoria"))
            }
            // Arreglos preparados en la PC: {"conservar": id, "quitar": id}
            val duplicados = datos.optJSONArray("duplicados")
            for (i in 0 until (duplicados?.length() ?: 0)) {
                val d = duplicados!!.getJSONObject(i)
                val quitar = dao.porId(d.getLong("quitar")) ?: continue
                val conservar = dao.porId(d.getLong("conservar")) ?: continue
                marcarDuplicadoSinRefrescar(ctx, quitar, conservar)
            }
            // {"id": id, "meses": 3, "primerMes": "2027-03"}
            val aMeses = datos.optJSONArray("aMeses")
            for (i in 0 until (aMeses?.length() ?: 0)) {
                val m = aMeses!!.getJSONObject(i)
                val compra = dao.porId(m.getLong("id")) ?: continue
                pasarAMesesSinRefrescar(ctx, compra, m.getInt("meses"), YearMonth.parse(m.getString("primerMes")))
            }
        }
        archivo.delete()
        refrescarWidgets(ctx)
        Log.i("Registro", "Importados $cargados movimientos de $ARCHIVO_IMPORTAR")
        return cargados
    }

    private const val ARCHIVO_IMPORTAR = "importar.json"
    private const val DIAS_IGNORADAS = 30

    suspend fun guardar(ctx: Context, t: Transaccion, recordarCategoria: Boolean) {
        val dao = FinanzasDb.get(ctx).dao()
        if (t.id == 0L) dao.insertar(t) else dao.actualizar(t)
        // Si elegiste otra categoría, la recordamos para los siguientes pagos en ese comercio.
        if (recordarCategoria) dao.guardarCategoria(CategoriaComercio(t.comercio, t.categoria))
        refrescarWidgets(ctx)
    }

    suspend fun eliminar(ctx: Context, t: Transaccion) {
        val db = FinanzasDb.get(ctx)
        db.withTransaction {
            // Si borras una compra a meses, sus mensualidades se van con ella.
            if (t.tipo == Tipos.MSI) db.dao().borrarMensualidades(Meses.prefijo(t.id))
            db.dao().eliminar(t)
        }
        refrescarWidgets(ctx)
    }

    /**
     * [quitar] es el mismo movimiento que [conservar]: se borra y sus claves se recuerdan para que la
     * notificación (o el estado de cuenta) no lo vuelva a meter.
     */
    suspend fun marcarDuplicado(ctx: Context, quitar: Transaccion, conservar: Transaccion) {
        FinanzasDb.get(ctx).withTransaction { marcarDuplicadoSinRefrescar(ctx, quitar, conservar) }
        refrescarWidgets(ctx)
    }

    private suspend fun marcarDuplicadoSinRefrescar(ctx: Context, quitar: Transaccion, conservar: Transaccion) {
        val dao = FinanzasDb.get(ctx).dao()
        listOfNotNull(quitar.claveUnica, quitar.claveVinculada).forEach { dao.descartarClave(ClaveDescartada(it)) }
        if (conservar.categoria == "Otros" && quitar.categoria != "Otros") {
            dao.actualizar(conservar.copy(categoria = quitar.categoria))
        }
        if (quitar.tipo == Tipos.MSI) dao.borrarMensualidades(Meses.prefijo(quitar.id))
        dao.eliminar(quitar)
    }

    suspend fun posiblesDuplicados(ctx: Context, t: Transaccion): List<Transaccion> {
        val tresDias = 3 * 86_400_000L
        return FinanzasDb.get(ctx).dao()
            .posiblesDuplicados(t.id, t.monto, t.fecha, t.fecha - tresDias, t.fecha + tresDias)
            .filter { it.fuente != Meses.FUENTE }
    }

    /** Divide [compra] en [meses] mensualidades a partir de [primerMes]. Si ya estaba a meses, las rehace. */
    suspend fun pasarAMeses(ctx: Context, compra: Transaccion, meses: Int, primerMes: YearMonth) {
        FinanzasDb.get(ctx).withTransaction { pasarAMesesSinRefrescar(ctx, compra, meses, primerMes) }
        refrescarWidgets(ctx)
    }

    private suspend fun pasarAMesesSinRefrescar(ctx: Context, compra: Transaccion, meses: Int, primerMes: YearMonth) {
        require(meses >= 2 && compra.id != 0L)
        val dao = FinanzasDb.get(ctx).dao()
        val diaLimite = dao.tarjetasConfig().firstOrNull { it.nombre == compra.tarjeta }?.diaLimite ?: 1
        dao.borrarMensualidades(Meses.prefijo(compra.id))
        dao.actualizar(compra.copy(tipo = Tipos.MSI))
        Meses.mensualidades(compra, meses, primerMes, diaLimite).forEach { dao.insertar(it) }
    }

    /** Regresa la compra a un gasto normal y borra sus mensualidades. */
    suspend fun quitarMeses(ctx: Context, compra: Transaccion) {
        val db = FinanzasDb.get(ctx)
        db.withTransaction {
            db.dao().borrarMensualidades(Meses.prefijo(compra.id))
            db.dao().actualizar(compra.copy(tipo = Tipos.GASTO))
        }
        refrescarWidgets(ctx)
    }

    suspend fun refrescarWidgets(ctx: Context) = FinanzasWidget().updateAll(ctx)
}
