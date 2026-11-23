package com.misfinanzas

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import android.media.ExifInterface
import java.io.File
import java.util.UUID

/**
 * Fotos de tickets de los gastos de un split. Se guardan comprimidas en blanco y negro (~100 KB) en
 * files/tickets/<grupoId>/gasto-<gastoId>-<n>.jpg; el respaldo semanal las sube a la carpeta
 * "Split - <nombre>" de Drive. No van en la base, así que el respaldo de la base no crece.
 */
object Tickets {
    const val MAXIMO = 3
    private const val LADO_MAX = 1400
    private const val CALIDAD = 70

    fun carpetaRaiz(ctx: Context) = File(ctx.filesDir, "tickets")
    fun carpetaGrupo(ctx: Context, grupoId: Long) = File(carpetaRaiz(ctx), grupoId.toString())

    /** Las fotos de un gasto, en orden. */
    fun de(ctx: Context, grupoId: Long, gastoId: Long): List<File> =
        carpetaGrupo(ctx, grupoId).listFiles { f -> f.name.startsWith("gasto-$gastoId-") && f.name.endsWith(".jpg") }
            .orEmpty().sortedBy { it.name }

    /** Cuántas fotos tiene cada gasto de un grupo (para mostrar el icono en la lista). */
    fun cuentas(ctx: Context, grupoId: Long): Map<Long, Int> =
        carpetaGrupo(ctx, grupoId).listFiles().orEmpty()
            .mapNotNull { Regex("""gasto-(\d+)-""").find(it.name)?.groupValues?.get(1)?.toLongOrNull() }
            .groupingBy { it }.eachCount()

    /**
     * Comprime una foto (de la cámara o la galería) a un archivo temporal, listo para adjuntarse al
     * guardar el gasto. Null si no se pudo leer.
     */
    fun comprimir(ctx: Context, uri: Uri): File? = runCatching {
        val opciones = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opciones) }
        var muestra = 1
        while (maxOf(opciones.outWidth, opciones.outHeight) / (muestra * 2) >= LADO_MAX) muestra *= 2
        val original = ctx.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = muestra })
        } ?: return null
        val giro = ctx.contentResolver.openInputStream(uri)?.use {
            when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } ?: 0f
        val escala = minOf(1f, LADO_MAX.toFloat() / maxOf(original.width, original.height))
        val m = Matrix().apply { postScale(escala, escala); postRotate(giro) }
        val ajustada = Bitmap.createBitmap(original, 0, 0, original.width, original.height, m, true)
        // Blanco y negro: un ticket es texto y así pesa mucho menos.
        val gris = Bitmap.createBitmap(ajustada.width, ajustada.height, Bitmap.Config.ARGB_8888)
        Canvas(gris).drawBitmap(ajustada, 0f, 0f, Paint().apply {
            colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
        })
        val destino = File(carpetaPendientes(ctx), "${UUID.randomUUID()}.jpg")
        destino.outputStream().use { gris.compress(Bitmap.CompressFormat.JPEG, CALIDAD, it) }
        original.recycle(); if (ajustada !== original) ajustada.recycle(); gris.recycle()
        destino
    }.getOrNull()

    /** Donde quedan las fotos recién tomadas mientras no guardas el gasto. */
    fun carpetaPendientes(ctx: Context) = File(ctx.cacheDir, "tickets-pendientes").apply { mkdirs() }

    /** Archivo vacío para que la cámara escriba la foto (se comprime después). */
    fun paraCamara(ctx: Context) = File(carpetaPendientes(ctx), "camara-${UUID.randomUUID()}.jpg")

    /**
     * Al guardar el gasto: deja exactamente [quedan] (fotos ya suyas que no quitaste y nuevas pendientes)
     * como sus fotos, renumeradas 1, 2, 3.
     */
    fun fijar(ctx: Context, grupoId: Long, gastoId: Long, quedan: List<File>) {
        val dir = carpetaGrupo(ctx, grupoId).apply { mkdirs() }
        val actuales = de(ctx, grupoId, gastoId)
        // Primero a nombres temporales para poder renumerar sin pisarse.
        val temporales = quedan.take(MAXIMO).mapIndexed { i, f ->
            val t = File(dir, "tmp-$gastoId-$i-${UUID.randomUUID()}.jpg")
            if (f in actuales) f.renameTo(t) else f.copyTo(t, overwrite = true).also { f.delete() }
            t
        }
        actuales.filter { it.exists() }.forEach { it.delete() }
        temporales.forEachIndexed { i, t -> t.renameTo(File(dir, "gasto-$gastoId-${i + 1}.jpg")) }
    }

    fun borrarDeGasto(ctx: Context, grupoId: Long, gastoId: Long) = de(ctx, grupoId, gastoId).forEach { it.delete() }

    fun borrarDeGrupo(ctx: Context, grupoId: Long) = carpetaGrupo(ctx, grupoId).deleteRecursively()

    /** Fotos tomadas que nunca se guardaron (cerraste el diálogo). */
    fun limpiarPendientes(ctx: Context) = carpetaPendientes(ctx).listFiles().orEmpty().forEach { it.delete() }
}
