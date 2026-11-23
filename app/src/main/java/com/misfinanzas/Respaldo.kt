package com.misfinanzas

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.misfinanzas.data.FinanzasDb
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Respaldo semanal de la base a Google Drive: cada sábado, solo en el WiFi de casa.
 * La app solo ve los archivos que ella misma crea (permiso drive.file), no el resto de tu Drive.
 */
object Respaldo {
    private const val TAG = "Respaldo"
    private const val ARCHIVO = "respaldo"
    private const val CONECTADO = "conectado"
    private const val RED_CASA = "red_casa"
    private const val ULTIMO = "ultimo"
    private const val ERROR = "error"
    private const val CARPETA = "carpeta"
    private const val TICKETS = "tickets"

    /** Nunca dos respaldos a la vez (el del sábado y el de un split): así no se crea una carpeta dos veces. */
    private val candado = kotlinx.coroutines.sync.Mutex()
    private const val SPLITS = "splits"

    const val NOMBRE_CARPETA = "Respaldos Mis Finanzas"
    /** Semanas de respaldo que se conservan en Drive. */
    const val CONSERVAR = 8
    private const val SCOPE = "https://www.googleapis.com/auth/drive.file"
    private const val CANAL = "respaldo"

    var conectado by mutableStateOf(false)
        private set
    var redCasa by mutableStateOf<String?>(null)
        private set
    /** Hora del último respaldo bueno (epoch ms); 0 = nunca. */
    var ultimo by mutableStateOf(0L)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var trabajando by mutableStateOf(false)
        private set

    private var cargado = false

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(ARCHIVO, Context.MODE_PRIVATE)

    @Synchronized
    fun cargar(ctx: Context) {
        if (cargado) return
        val p = prefs(ctx)
        conectado = p.getBoolean(CONECTADO, false)
        redCasa = p.getString(RED_CASA, null)
        ultimo = p.getLong(ULTIMO, 0L)
        error = p.getString(ERROR, null)
        cargado = true
    }

    fun ponerRedCasa(ctx: Context, red: String) {
        redCasa = red
        prefs(ctx).edit().putString(RED_CASA, red).apply()
    }

    fun marcarConectado(ctx: Context, valor: Boolean) {
        conectado = valor
        prefs(ctx).edit().putBoolean(CONECTADO, valor).apply()
    }

    private fun ponerError(ctx: Context, msg: String?) {
        error = msg
        prefs(ctx).edit().putString(ERROR, msg).apply()
    }

    /** ¿Ya toca? Sí si no hay respaldo desde el sábado más reciente (o nunca se ha hecho). */
    fun toca(ultimo: LocalDate?, hoy: LocalDate): Boolean {
        val sabado = hoy.with(TemporalAdjusters.previousOrSame(DayOfWeek.SATURDAY))
        return ultimo == null || ultimo < sabado
    }

    // ---------- Permiso de Google ----------

    fun pedirPermiso(ctx: Context, alTerminar: (AuthorizationResult?, Exception?) -> Unit) {
        Identity.getAuthorizationClient(ctx)
            .authorize(AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(SCOPE))).build())
            .addOnSuccessListener { alTerminar(it, null) }
            .addOnFailureListener { alTerminar(null, it) }
    }

    /** Token sin interfaz; null si hay que volver a dar permiso desde la app. */
    suspend fun tokenEnSilencio(ctx: Context): String? = suspendCancellableCoroutine { c ->
        pedirPermiso(ctx) { r, e ->
            when {
                e != null -> c.resumeWithException(e)
                r == null || r.hasResolution() -> c.resume(null)
                else -> c.resume(r.accessToken)
            }
        }
    }

    // ---------- Red de casa ----------

    /** Nombre del WiFi al que está conectado el teléfono; null si no hay WiFi o no hay permiso de ubicación. */
    suspend fun redActual(ctx: Context): String? {
        val crudo: String? = if (Build.VERSION.SDK_INT >= 31) {
            val cm = ctx.getSystemService(ConnectivityManager::class.java)
            var cb: ConnectivityManager.NetworkCallback? = null
            try {
                withTimeoutOrNull(4_000) {
                    suspendCancellableCoroutine<String?> { c ->
                        // Sin FLAG_INCLUDE_LOCATION_INFO Android esconde el nombre de la red.
                        val callback = object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
                            override fun onCapabilitiesChanged(n: Network, caps: NetworkCapabilities) {
                                val ssid = (caps.transportInfo as? WifiInfo)?.ssid
                                if (c.isActive) c.resume(ssid)
                            }
                        }
                        cb = callback
                        val req = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
                        cm.registerNetworkCallback(req, callback)
                    }
                }
            } finally {
                cb?.let { runCatching { cm.unregisterNetworkCallback(it) } }
            }
        } else {
            @Suppress("DEPRECATION")
            ctx.applicationContext.getSystemService(WifiManager::class.java).connectionInfo?.ssid
        }
        // Android devuelve "<unknown ssid>" cuando no deja ver el nombre.
        return crudo?.trim('"')?.takeIf { it.isNotBlank() && !it.startsWith("<") }
    }

    // ---------- Respaldo ----------

    /** Copia la base y la sube. Devuelve null si salió bien, o el mensaje de error. */
    suspend fun respaldar(ctx: Context, token: String): String? = withContext(Dispatchers.IO) { candado.withLock {
        trabajando = true
        try {
            val copia = copiaDeLaBase(ctx)
            val drive = Drive(token)
            val carpeta = carpeta(ctx, drive)
            val nombre = "finanzas-${LocalDate.now()}.db"
            // Si ya hay uno de hoy (respaldaste a mano), se reemplaza.
            drive.listar("'$carpeta' in parents and name = '$nombre' and trashed = false").forEach { drive.borrar(it.first) }
            drive.subir(nombre, carpeta, copia)
            copia.delete()
            // Solo los respaldos (finanzas-*.db): las carpetas "Split - …" de los tickets no se tocan.
            drive.listar("'$carpeta' in parents and trashed = false and mimeType != 'application/vnd.google-apps.folder'", "createdTime desc")
                .filter { it.second.startsWith("finanzas-") && it.second.endsWith(".db") }
                .drop(CONSERVAR).forEach { drive.borrar(it.first) }
            // Si fallan los tickets, el respaldo de la base ya quedó: se reintentan la próxima vez.
            try {
                sincronizarSplits(ctx, drive, carpeta)
            } catch (e: Exception) {
                Log.w(TAG, "No se pudieron subir los tickets", e)
            }
            ultimo = System.currentTimeMillis()
            prefs(ctx).edit().putLong(ULTIMO, ultimo).apply()
            ponerError(ctx, null)
            Log.i(TAG, "Respaldo subido: $nombre")
            null
        } catch (e: Exception) {
            Log.w(TAG, "Falló el respaldo", e)
            val msg = if (e is Drive.HttpError && e.codigo == 401) "Google pidió volver a conectar Drive."
            else "No se pudo subir el respaldo (${e.message ?: e.javaClass.simpleName})."
            ponerError(ctx, msg)
            msg
        } finally {
            trabajando = false
        }
    } }

    /**
     * Sube a la carpeta "Split - <nombre>" de cada split (dentro de la de respaldos) sus fotos de tickets
     * nuevas o cambiadas y "Resumen - <nombre>.md" si cambió; renombra la carpeta si cambiaste el nombre y
     * borra de Drive lo que borraste en la app. Con [soloGrupo], solo ese split (al terminarlo).
     * Recuerda lo subido en las preferencias: archivo → id en Drive + huella. Nunca crea una carpeta que
     * ya tiene: guarda su id y la reutiliza.
     */
    private suspend fun sincronizarSplits(ctx: Context, drive: Drive, raiz: String, soloGrupo: Long? = null) {
        val dao = FinanzasDb.get(ctx).dao()
        val grupos = dao.gruposTodos()
        val gastosLista = dao.grupoGastosTodos()
        val gastos = gastosLista.associateBy { it.id }
        val datos = com.misfinanzas.ui.DatosGrupos(grupos, dao.miembrosTodos(), gastosLista, dao.partesTodas())
        val p = prefs(ctx)
        val subidos = JSONObject(p.getString(TICKETS, null) ?: "{}")
        val carpetas = JSONObject(p.getString(SPLITS, null) ?: "{}")
        val vivos = HashSet<String>()
        val guardar = {
            p.edit().putString(TICKETS, subidos.toString()).putString(SPLITS, carpetas.toString()).apply()
        }
        val patron = Regex("""gasto-(\d+)-(\d+)\.jpg""")
        val prohibidos = Regex("""[\\/:*?"<>|]""")
        val nombreFoto = { f: File ->
            val m = patron.find(f.name)
            val gasto = m?.groupValues?.get(1)?.toLongOrNull()?.let { gastos[it] }
            val fecha = gasto?.let { diaDe(it.fecha).toString() } ?: ""
            val concepto = gasto?.concepto?.replace(prohibidos, " ")?.trim() ?: "ticket"
            "$fecha $concepto (${m?.groupValues?.get(2) ?: "1"}).jpg".trim()
        }
        for (g in grupos.filter { soloGrupo == null || it.id == soloGrupo }) {
            val clave = g.id.toString()
            val fotos = Tickets.carpetaGrupo(ctx, g.id).listFiles { f -> patron.matches(f.name) }.orEmpty().sortedBy { it.name }
            val previa = carpetas.optJSONObject(clave)
            // Un split sin gastos ni carpeta todavía no necesita nada en Drive.
            if (previa == null && datos.gastosDe[g.id].isNullOrEmpty()) continue
            val nombre = "Split - ${g.nombre}".replace(prohibidos, " ")
            val idCarpeta = previa?.optString("id")?.takeIf { it.isNotEmpty() && drive.existe(it) }
                ?: drive.crearCarpeta(nombre, raiz).also { nueva ->
                    // Carpeta nueva (o la borraron en Drive): se vuelve a subir todo lo de este split.
                    subidos.keys().asSequence().toList().filter { it.startsWith("$clave/") }.forEach { subidos.remove(it) }
                    carpetas.put(clave, JSONObject().put("id", nueva).put("nombre", nombre))
                    guardar()
                }
            val info = carpetas.getJSONObject(clave)
            if (info.optString("nombre") != nombre) {
                drive.renombrar(idCarpeta, nombre)
                info.put("nombre", nombre)
                // El resumen lleva el nombre del split: se vuelve a subir con el nuevo.
                info.remove("resumenHuella")
                guardar()
            }
            for (f in fotos) {
                val k = "$clave/${f.name}"
                val huella = "${f.length()}-${f.lastModified()}"
                vivos += k
                val antes = subidos.optString(k)
                if (antes.endsWith("|$huella")) continue
                if (antes.isNotEmpty()) runCatching { drive.borrar(antes.substringBefore("|")) }
                subidos.put(k, drive.subir(nombreFoto(f), idCarpeta, f, "image/jpeg") + "|$huella")
                guardar()
            }
            // Resumen en Markdown, solo si cambió algo.
            val fotosPorGasto = fotos.groupBy { patron.find(it.name)?.groupValues?.get(1)?.toLongOrNull() ?: -1L }
                .mapValues { e -> e.value.map(nombreFoto) }
            val texto = ResumenSplit.texto(g, datos, markdown = true, fotos = fotosPorGasto)
            val huella = texto.hashCode().toString()
            if (info.optString("resumenHuella") != huella) {
                info.optString("resumen").takeIf { it.isNotEmpty() }?.let { runCatching { drive.borrar(it) } }
                val tmp = File(ctx.cacheDir, "resumen-split.md").apply { writeText(texto) }
                info.put("resumen", drive.subir("Resumen - ${g.nombre}.md".replace(Regex("""[\\/:*?"<>|]"""), " "), idCarpeta, tmp, "text/markdown"))
                info.put("resumenHuella", huella)
                tmp.delete()
                guardar()
            }
        }
        // Lo que ya no está en la app: fotos borradas y splits borrados.
        for (k in subidos.keys().asSequence().toList().filter { k -> k !in vivos && (soloGrupo == null || k.startsWith("$soloGrupo/")) }) {
            runCatching { drive.borrar(subidos.getString(k).substringBefore("|")) }
            subidos.remove(k)
        }
        if (soloGrupo == null) {
            val existentes = grupos.map { it.id.toString() }.toSet()
            for (id in carpetas.keys().asSequence().toList().filter { it !in existentes }) {
                runCatching { drive.borrar(carpetas.getJSONObject(id).getString("id")) }
                carpetas.remove(id)
            }
        }
        guardar()
    }

    /** Respalda en Drive un solo split (al terminarlo): fotos y resumen. Null si salió bien; si no, el error. */
    suspend fun respaldarSplit(ctx: Context, token: String, grupoId: Long): String? = withContext(Dispatchers.IO) {
        candado.withLock {
            try {
                val drive = Drive(token)
                sincronizarSplits(ctx, drive, carpeta(ctx, drive), soloGrupo = grupoId)
                null
            } catch (e: Exception) {
                Log.w(TAG, "Falló el respaldo del split", e)
                if (e is Drive.HttpError && e.codigo == 401) "Google pidió volver a conectar Drive."
                else "No se pudo subir el split (${e.message ?: e.javaClass.simpleName})."
            }
        }
    }

    /** Al terminar un split: lo sube en cuanto haya internet (cualquier red; son pocos KB). */
    fun programarSplit(ctx: Context, grupoId: Long) {
        WorkManager.getInstance(ctx).enqueueUniqueWork(
            "split-$grupoId",
            androidx.work.ExistingWorkPolicy.REPLACE,
            androidx.work.OneTimeWorkRequestBuilder<SplitWorker>()
                .setInputData(androidx.work.workDataOf("grupo" to grupoId))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build(),
        )
    }

    /** Copia consistente de la base aunque la app la esté usando. */
    private fun copiaDeLaBase(ctx: Context): File {
        val destino = File(ctx.cacheDir, "respaldo.db").apply { delete() }
        val db = FinanzasDb.get(ctx).openHelper.writableDatabase
        try {
            db.execSQL("VACUUM INTO '${destino.absolutePath}'")
        } catch (e: Exception) {
            // SQLite viejo (Android < 11): vaciar el WAL al archivo principal y copiarlo.
            db.query("PRAGMA wal_checkpoint(FULL)").use { it.moveToFirst() }
            ctx.getDatabasePath("finanzas.db").copyTo(destino, overwrite = true)
        }
        return destino
    }

    private fun carpeta(ctx: Context, drive: Drive): String {
        prefs(ctx).getString(CARPETA, null)?.let { id ->
            if (drive.existe(id)) return id
        }
        val id = drive.listar(
            "name = '$NOMBRE_CARPETA' and mimeType = 'application/vnd.google-apps.folder' and trashed = false"
        ).firstOrNull()?.first ?: drive.crearCarpeta(NOMBRE_CARPETA)
        prefs(ctx).edit().putString(CARPETA, id).apply()
        return id
    }

    fun avisar(ctx: Context, texto: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CANAL, "Respaldo en Drive", NotificationManager.IMPORTANCE_DEFAULT)
        )
        val abrir = PendingIntent.getActivity(ctx, 1, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(ctx, CANAL)
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setContentTitle("Respaldo en Drive")
            .setContentText(texto)
            .setContentIntent(abrir)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(ctx).notify(CANAL.hashCode(), n)
    }

    /** Revisión cada 6 h; la tarea decide si ya toca. Seguro de llamar en cada arranque. */
    fun programar(ctx: Context) {
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
            "respaldo_drive",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<RespaldoWorker>(6, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).setRequiresBatteryNotLow(true).build())
                .build(),
        )
    }

    fun diaDe(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()
}

/** Sube un split que acabas de terminar (ver [Respaldo.programarSplit]). */
class SplitWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val grupoId = inputData.getLong("grupo", -1)
        Respaldo.cargar(ctx)
        if (grupoId < 0 || !Respaldo.conectado) return Result.success()
        val token = try {
            Respaldo.tokenEnSilencio(ctx)
        } catch (e: Exception) {
            return if (runAttemptCount < 3) Result.retry() else Result.success()
        }
        if (token == null) {
            Respaldo.avisar(ctx, "Abre Mis Finanzas y vuelve a conectar Drive para guardar el split.")
            return Result.success()
        }
        val nombre = FinanzasDb.get(ctx).dao().gruposTodos().firstOrNull { it.id == grupoId }?.nombre ?: return Result.success()
        val error = Respaldo.respaldarSplit(ctx, token, grupoId)
        return when {
            error == null -> { Respaldo.avisar(ctx, "Split \"$nombre\" guardado en Drive con su resumen y sus tickets."); Result.success() }
            runAttemptCount < 3 -> Result.retry()
            else -> { Respaldo.avisar(ctx, error); Result.success() }
        }
    }
}

class RespaldoWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        Respaldo.cargar(ctx)
        if (!Respaldo.conectado) return Result.success()
        val ultimo = Respaldo.ultimo.takeIf { it > 0 }?.let(Respaldo::diaDe)
        if (!Respaldo.toca(ultimo, LocalDate.now())) return Result.success()
        val casa = Respaldo.redCasa ?: return Result.success()
        if (Respaldo.redActual(ctx) != casa) return Result.success()   // se intenta en la próxima revisión

        val token = try {
            Respaldo.tokenEnSilencio(ctx)
        } catch (e: Exception) {
            Log.w("Respaldo", "Sin token", e)
            return Result.success()
        }
        if (token == null) {
            Respaldo.avisar(ctx, "Abre Mis Finanzas y vuelve a conectar Drive para seguir respaldando.")
            return Result.success()
        }
        Respaldo.respaldar(ctx, token)?.let { Respaldo.avisar(ctx, it) }
        return Result.success()
    }
}

/** Lo mínimo de la API REST de Drive v3, sin librerías. */
private class Drive(private val token: String) {
    class HttpError(val codigo: Int, cuerpo: String) : Exception("HTTP $codigo: ${cuerpo.take(200)}")

    private fun pedir(metodo: String, url: String, tipo: String? = null, cuerpo: ByteArray? = null): String {
        val c = URL(url).openConnection() as HttpURLConnection
        // HttpURLConnection no sabe hacer PATCH: Google acepta un POST con el método en este encabezado.
        if (metodo == "PATCH") {
            c.requestMethod = "POST"
            c.setRequestProperty("X-HTTP-Method-Override", "PATCH")
        } else c.requestMethod = metodo
        c.connectTimeout = 20_000
        c.readTimeout = 60_000
        c.setRequestProperty("Authorization", "Bearer $token")
        if (cuerpo != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", tipo)
            c.setFixedLengthStreamingMode(cuerpo.size)
            c.outputStream.use { it.write(cuerpo) }
        }
        val codigo = c.responseCode
        val texto = (if (codigo in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        c.disconnect()
        if (codigo !in 200..299) throw HttpError(codigo, texto)
        return texto
    }

    /** (id, nombre) de los archivos que cumplen la búsqueda. */
    fun listar(q: String, orden: String? = null): List<Pair<String, String>> {
        val url = "$API/files?pageSize=100&fields=files(id,name)&q=" + URLEncoder.encode(q, "UTF-8") +
            (orden?.let { "&orderBy=" + URLEncoder.encode(it, "UTF-8") } ?: "")
        val files: JSONArray = JSONObject(pedir("GET", url)).getJSONArray("files")
        return (0 until files.length()).map { files.getJSONObject(it).let { f -> f.getString("id") to f.getString("name") } }
    }

    fun existe(id: String): Boolean = try {
        !JSONObject(pedir("GET", "$API/files/$id?fields=id,trashed")).optBoolean("trashed")
    } catch (e: HttpError) {
        if (e.codigo == 404) false else throw e
    }

    fun crearCarpeta(nombre: String, dentroDe: String? = null): String {
        val meta = JSONObject().put("name", nombre).put("mimeType", "application/vnd.google-apps.folder")
        dentroDe?.let { meta.put("parents", JSONArray().put(it)) }
        return JSONObject(pedir("POST", "$API/files?fields=id", "application/json; charset=UTF-8", meta.toString().toByteArray())).getString("id")
    }

    /** Sube [archivo] a [carpeta] y devuelve su id en Drive. */
    fun subir(nombre: String, carpeta: String, archivo: File, tipo: String = "application/x-sqlite3"): String {
        val limite = "finanzas${System.nanoTime()}"
        val meta = JSONObject().put("name", nombre).put("parents", JSONArray().put(carpeta)).put("mimeType", tipo)
        val cuerpo = buildList<ByteArray> {
            add("--$limite\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$meta\r\n--$limite\r\nContent-Type: $tipo\r\n\r\n".toByteArray())
            add(archivo.readBytes())
            add("\r\n--$limite--\r\n".toByteArray())
        }.reduce { a, b -> a + b }
        return JSONObject(pedir("POST", "$SUBIR/files?uploadType=multipart&fields=id", "multipart/related; boundary=$limite", cuerpo)).getString("id")
    }

    fun renombrar(id: String, nombre: String) {
        pedir("PATCH", "$API/files/$id", "application/json; charset=UTF-8", JSONObject().put("name", nombre).toString().toByteArray())
    }

    fun borrar(id: String) {
        pedir("DELETE", "$API/files/$id")
    }

    companion object {
        const val API = "https://www.googleapis.com/drive/v3"
        const val SUBIR = "https://www.googleapis.com/upload/drive/v3"
    }
}
