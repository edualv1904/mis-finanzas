package com.misfinanzas

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.misfinanzas.data.FinanzasDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Dónde estabas al pagar. Se pide una sola vez por compra (no se sigue tu ubicación) y solo para
 * compras en persona: las compras en línea no tienen un lugar que valga la pena guardar.
 */
object Ubicacion {
    private const val TAG = "Ubicacion"

    /** Si la notificación llega más tarde que esto, ya no estás donde pagaste. */
    private const val MAX_RETRASO_MS = 15 * 60_000L
    /** Una ubicación guardada por el teléfono sirve si es así de reciente. */
    private const val MAX_EDAD_MS = 3 * 60_000L
    private const val ESPERA_MS = 30_000L

    /** Comercios en línea, suscripciones y recargas: no tienen lugar físico. */
    private val EN_LINEA = Regex(
        "AMAZON|PAYPAL|SPOTIFY|NETFLIX|GOOGLE|APPLE\\.COM|ITUNES|OPENAI|CHATGPT|NOTION|" +
            "MERCADO ?LIBRE|MERPAGO|TELCEL|RADIOMOVIL|AMERICAMOVI|RAPPI|UBER|DIDI|STEAM|DISNEY|HBO|" +
            "PRIME ?VIDEO|YOUTUBE|MICROSOFT|XBOX|PLAYSTATION|SHEIN|TEMU|ALIEXPRESS|SUPERBOLETOS|TICKETMASTER|" +
            "AIRBNB|BOOKING|^0{6,}",
        RegexOption.IGNORE_CASE,
    )

    fun esEnLinea(comercio: String) = EN_LINEA.containsMatchIn(comercio.trim())

    fun hayPermiso(ctx: Context) =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Hace falta para que el lector de notificaciones sepa dónde estás con la app cerrada. */
    fun hayPermisoSiempre(ctx: Context) = hayPermiso(ctx) && (Build.VERSION.SDK_INT < 29 ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED)

    /** Tras registrar un pago nuevo por notificación: le pone dónde estabas, si fue en persona. */
    suspend fun anotar(ctx: Context, clave: String, fechaPago: Long) {
        if (!hayPermiso(ctx)) return
        if (System.currentTimeMillis() - fechaPago > MAX_RETRASO_MS) return
        val dao = FinanzasDb.get(ctx).dao()
        val t = dao.porClave(clave) ?: return
        if (t.tipo != Tipos.GASTO || esEnLinea(t.comercio)) return
        val punto = actual(ctx)
        if (punto == null) {
            Log.i(TAG, "Sin ubicación para ${t.comercio}")
            return
        }
        Log.i(TAG, "${t.comercio}: precisión ${punto.accuracy.toInt()} m, de hace ${edadMs(punto) / 1000} s")
        dao.ponerUbicacion(t.id, punto.latitude, punto.longitude, direccion(ctx, punto.latitude, punto.longitude))
    }

    /** Llena la dirección de los movimientos que se guardaron sin internet. */
    suspend fun completarDirecciones(ctx: Context) {
        val dao = FinanzasDb.get(ctx).dao()
        for (t in dao.sinDireccion()) {
            val dir = direccion(ctx, t.lat ?: continue, t.lon ?: continue) ?: return // sigue sin internet
            dao.ponerUbicacion(t.id, t.lat, t.lon, dir)
        }
    }

    /** Ubicación actual, o null si no hay permiso, el GPS está apagado o no responde a tiempo. */
    suspend fun actual(ctx: Context): Location? {
        if (!hayPermiso(ctx)) return null
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return null
        val proveedores = buildList {
            if (Build.VERSION.SDK_INT >= 31) add(LocationManager.FUSED_PROVIDER)
            add(LocationManager.GPS_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
        }.filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }

        try {
            val reciente = proveedores.mapNotNull { lm.getLastKnownLocation(it) }
                .filter { edadMs(it) < MAX_EDAD_MS && it.accuracy < 100 }
                .minByOrNull { it.accuracy }
            if (Build.VERSION.SDK_INT < 30) return reciente
            val proveedor = proveedores.firstOrNull() ?: return reciente
            return withTimeoutOrNull(ESPERA_MS) {
                suspendCancellableCoroutine { cont ->
                    val cancelar = CancellationSignal()
                    cont.invokeOnCancellation { cancelar.cancel() }
                    lm.getCurrentLocation(proveedor, cancelar, ctx.mainExecutor) { cont.resume(it) }
                }
            } ?: reciente
        } catch (e: SecurityException) {
            return null
        }
    }

    /** "Av. Juárez 123, Centro", o null sin internet. */
    suspend fun direccion(ctx: Context, lat: Double, lon: Double): String? = withContext(Dispatchers.IO) {
        if (!Geocoder.isPresent()) return@withContext null
        val geo = Geocoder(ctx, Locale("es", "MX"))
        val dir = runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                withTimeoutOrNull(10_000) {
                    suspendCancellableCoroutine { cont ->
                        geo.getFromLocation(lat, lon, 1, object : Geocoder.GeocodeListener {
                            override fun onGeocode(l: MutableList<android.location.Address>) = cont.resume(l.firstOrNull())
                            override fun onError(msg: String?) = cont.resume(null)
                        })
                    }
                }
            } else {
                @Suppress("DEPRECATION") geo.getFromLocation(lat, lon, 1)?.firstOrNull()
            }
        }.getOrNull() ?: return@withContext null
        // Calle y número + municipio; sin código postal, estado ni país.
        val calle = listOfNotNull(dir.thoroughfare, dir.subThoroughfare).joinToString(" ").ifBlank { null }
        listOfNotNull(calle ?: dir.featureName, dir.subLocality, dir.locality).distinct().joinToString(", ").ifBlank { null }
    }

    private fun edadMs(l: Location) = (SystemClock.elapsedRealtimeNanos() - l.elapsedRealtimeNanos) / 1_000_000
}
