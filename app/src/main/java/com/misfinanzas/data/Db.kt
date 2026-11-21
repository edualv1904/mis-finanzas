package com.misfinanzas.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.misfinanzas.Categorias
import com.misfinanzas.Claves
import com.misfinanzas.Tarjetas
import com.misfinanzas.Tipos
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "transacciones",
    indices = [
        Index(value = ["claveUnica"], unique = true),
        Index(value = ["claveVinculada"], unique = true),
        Index("fecha"),
    ],
)
data class Transaccion(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val monto: Double,
    val moneda: String = "MXN",
    val comercio: String,
    val tarjeta: String,
    val fecha: Long,
    val categoria: String,
    /** "wallet", "bbva", "mercadopago"... según la notificación de origen; "manual" si la capturaste tú. */
    val fuente: String,
    /** Evita registrar dos veces la misma notificación. Null en capturas manuales. */
    val claveUnica: String? = null,
    val nota: String = "",
    /** Clave de la otra notificación de la misma compra (p. ej. BBVA cuando ya llegó la de Wallet). */
    val claveVinculada: String? = null,
    /** Ver [com.misfinanzas.Tipos]: solo los gastos suman en "Gastado este mes". */
    val tipo: String = Tipos.GASTO,
    /** Dónde estabas al pagar (solo compras en persona). Null = sin ubicación. */
    val lat: Double? = null,
    val lon: Double? = null,
    /** Dirección legible de [lat]/[lon]; se llena cuando hay internet. */
    val lugar: String? = null,
)

/** Recuerda la categoría que elegiste para cada comercio. */
@Entity(tableName = "categoria_comercio")
data class CategoriaComercio(
    @PrimaryKey val comercio: String,
    val categoria: String,
)

/** Notificación de un banco que no supimos leer; sirve para escribir nuevos parsers. */
@Entity(
    tableName = "notificaciones_crudas",
    indices = [Index(value = ["paquete", "titulo", "texto"], unique = true)],
)
data class NotificacionCruda(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val paquete: String,
    val titulo: String,
    val texto: String,
    val fecha: Long,
    /** Lo que tú dijiste que es: "gasto", "ingreso", "transferencia", "ignorar", o null si aún no la revisas. */
    val etiqueta: String? = null,
)

/** Fechas de una tarjeta de crédito. [nombre] es el nombre canónico (ver Tarjetas). */
@Entity(tableName = "tarjetas")
data class TarjetaConfig(
    @PrimaryKey val nombre: String,
    val diaCorte: Int,
    val diaLimite: Int,
    /** Días antes de la fecha límite para avisarte; 0 = sin aviso. */
    val avisarDiasAntes: Int = 3,
    /** Día (epochDay) del último corte que ya pagaste; ese periodo ya no se recuerda. */
    val pagadoCorte: Long? = null,
    /** Color de la tarjeta (ARGB) para el borde de su ficha; null = gris neutro. */
    val color: Int? = null,
)

/**
 * Claves de notificaciones que marcaste como duplicado: si la app de origen vuelve a mandar la misma
 * notificación, no se registra otra vez.
 */
@Entity(tableName = "claves_descartadas")
data class ClaveDescartada(@PrimaryKey val clave: String)

/** Ideas que apuntas fuera de casa para platicarlas después. */
@Entity(tableName = "notas")
data class Nota(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val texto: String,
    val fecha: Long,
)

@Dao
interface TransaccionDao {
    @Query("SELECT * FROM transacciones WHERE fecha >= :desde AND fecha < :hasta ORDER BY fecha DESC")
    fun observarRango(desde: Long, hasta: Long): Flow<List<Transaccion>>

    @Query("SELECT COALESCE(SUM(monto), 0) FROM transacciones WHERE tipo = 'gasto' AND fecha >= :desde AND fecha < :hasta")
    suspend fun sumaGastos(desde: Long, hasta: Long): Double

    /** Igual que [sumaGastos] pero se vuelve a emitir cada vez que cambian los movimientos. */
    @Query("SELECT COALESCE(SUM(monto), 0) FROM transacciones WHERE tipo = 'gasto' AND fecha >= :desde AND fecha < :hasta")
    fun observarSumaGastos(desde: Long, hasta: Long): Flow<Double>

    @Query(
        """SELECT EXISTS(SELECT 1 FROM transacciones WHERE claveUnica = :clave OR claveVinculada = :clave)
           OR EXISTS(SELECT 1 FROM claves_descartadas WHERE clave = :clave)"""
    )
    suspend fun claveVista(clave: String): Boolean

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun descartarClave(c: ClaveDescartada)

    @Query("SELECT * FROM transacciones WHERE id = :id")
    suspend fun porId(id: Long): Transaccion?

    @Query("SELECT * FROM transacciones WHERE claveUnica = :clave")
    suspend fun porClave(clave: String): Transaccion?

    @Query("UPDATE transacciones SET lat = :lat, lon = :lon, lugar = :lugar WHERE id = :id")
    suspend fun ponerUbicacion(id: Long, lat: Double?, lon: Double?, lugar: String?)

    /** Movimientos con ubicación a los que les falta la dirección (no había internet al guardarlos). */
    @Query("SELECT * FROM transacciones WHERE lat IS NOT NULL AND lugar IS NULL ORDER BY fecha DESC LIMIT 50")
    suspend fun sinDireccion(): List<Transaccion>

    @Query("SELECT * FROM transacciones WHERE lat IS NOT NULL AND fecha >= :desde AND fecha < :hasta ORDER BY fecha DESC")
    fun observarConUbicacion(desde: Long, hasta: Long): Flow<List<Transaccion>>

    /** Otros movimientos del mismo monto cerca de esa fecha, el más cercano primero. */
    @Query(
        """SELECT * FROM transacciones
           WHERE id != :id AND ABS(monto - :monto) < 0.015 AND fecha BETWEEN :desde AND :hasta
           ORDER BY ABS(fecha - :fecha)"""
    )
    suspend fun posiblesDuplicados(id: Long, monto: Double, fecha: Long, desde: Long, hasta: Long): List<Transaccion>

    /** Mensualidades de una compra a meses (claveUnica "msi|<id>|k"). */
    @Query("SELECT * FROM transacciones WHERE claveUnica LIKE :prefijo || '%' ORDER BY fecha")
    suspend fun mensualidadesDe(prefijo: String): List<Transaccion>

    @Query("DELETE FROM transacciones WHERE claveUnica LIKE :prefijo || '%'")
    suspend fun borrarMensualidades(prefijo: String)

    /** Compras a meses y sus mensualidades, de cualquier fecha. */
    @Query("SELECT * FROM transacciones WHERE tipo = 'msi' OR claveUnica LIKE 'msi|%' ORDER BY fecha")
    fun observarMeses(): Flow<List<Transaccion>>

    /** Pagos de otra fuente automática, mismo monto y hora cercana, que aún no tienen pareja. */
    @Query(
        """SELECT * FROM transacciones
           WHERE fuente != :fuente AND fuente NOT IN ('manual', 'msi') AND claveVinculada IS NULL
             AND ABS(monto - :monto) < 0.005 AND fecha BETWEEN :desde AND :hasta
           ORDER BY ABS(fecha - (:desde + :hasta) / 2)"""
    )
    suspend fun candidatasGemelas(monto: Double, desde: Long, hasta: Long, fuente: String): List<Transaccion>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertar(t: Transaccion): Long

    /** Solo cambia lo que sigue en "Otros": nunca pisa una categoría que hayas puesto tú. */
    @Query("UPDATE transacciones SET categoria = :categoria WHERE id = :id AND categoria = 'Otros'")
    suspend fun recategorizar(id: Long, categoria: String)

    @Update
    suspend fun actualizar(t: Transaccion)

    @Delete
    suspend fun eliminar(t: Transaccion)

    @Query("SELECT categoria FROM categoria_comercio WHERE comercio = :comercio")
    suspend fun categoriaDe(comercio: String): String?

    @Upsert
    suspend fun guardarCategoria(c: CategoriaComercio)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun guardarCruda(n: NotificacionCruda)

    /** Conserva solo las 300 más recientes sin revisar; las que ya etiquetaste no se borran. */
    @Query(
        """DELETE FROM notificaciones_crudas WHERE etiqueta IS NULL AND id NOT IN
           (SELECT id FROM notificaciones_crudas WHERE etiqueta IS NULL ORDER BY fecha DESC LIMIT 300)"""
    )
    suspend fun recortarCrudas()

    @Query("SELECT * FROM notificaciones_crudas ORDER BY fecha DESC")
    fun observarCrudas(): Flow<List<NotificacionCruda>>

    @Query("SELECT COUNT(*) FROM notificaciones_crudas WHERE etiqueta IS NULL")
    fun observarCrudasSinRevisar(): Flow<Int>

    @Query("UPDATE notificaciones_crudas SET etiqueta = :etiqueta WHERE id = :id")
    suspend fun etiquetarCruda(id: Long, etiqueta: String?)

    @Query("SELECT * FROM notificaciones_crudas WHERE etiqueta IN ('gasto', 'ingreso', 'transferencia') ORDER BY fecha")
    suspend fun crudasEtiquetadas(): List<NotificacionCruda>

    @Query("DELETE FROM notificaciones_crudas WHERE etiqueta = 'ignorar'")
    suspend fun borrarCrudasIgnoradas()

    @Query("SELECT * FROM notas ORDER BY fecha DESC")
    fun observarNotas(): Flow<List<Nota>>

    @Upsert
    suspend fun guardarNota(n: Nota)

    @Delete
    suspend fun eliminarNota(n: Nota)

    @Query("SELECT * FROM tarjetas ORDER BY nombre")
    fun observarTarjetasConfig(): Flow<List<TarjetaConfig>>

    @Query("SELECT * FROM tarjetas")
    suspend fun tarjetasConfig(): List<TarjetaConfig>

    @Upsert
    suspend fun guardarTarjeta(t: TarjetaConfig)

    @Delete
    suspend fun eliminarTarjeta(t: TarjetaConfig)

    @Query("SELECT MIN(fecha) FROM transacciones")
    fun observarPrimeraFecha(): Flow<Long?>

    @Query("SELECT COALESCE(SUM(monto), 0) FROM transacciones WHERE tipo = 'gasto' AND tarjeta = :tarjeta AND fecha >= :desde AND fecha < :hasta")
    suspend fun sumaGastosTarjeta(tarjeta: String, desde: Long, hasta: Long): Double

    @Query("SELECT DISTINCT tarjeta FROM transacciones ORDER BY tarjeta")
    fun observarTarjetas(): Flow<List<String>>
}

@Database(entities = [Transaccion::class, CategoriaComercio::class, NotificacionCruda::class, Nota::class, TarjetaConfig::class, ClaveDescartada::class], version = 11, exportSchema = false)
abstract class FinanzasDb : RoomDatabase() {
    abstract fun dao(): TransaccionDao

    companion object {
        @Volatile private var instancia: FinanzasDb? = null

        fun get(context: Context): FinanzasDb =
            instancia ?: synchronized(this) {
                instancia ?: Room.databaseBuilder(
                    context.applicationContext, FinanzasDb::class.java, "finanzas.db"
                ).addMigrations(MIGRACION_1_2, MIGRACION_2_3, MIGRACION_3_4, MIGRACION_4_5, MIGRACION_5_6, MIGRACION_6_7, MIGRACION_7_8, MIGRACION_8_9, MIGRACION_9_10, MIGRACION_10_11).build().also { instancia = it }
            }
    }
}

private val MIGRACION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE transacciones ADD COLUMN claveVinculada TEXT")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_transacciones_claveVinculada ON transacciones(claveVinculada)")
    }
}

private val MIGRACION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS notificaciones_crudas (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, paquete TEXT NOT NULL, " +
                "titulo TEXT NOT NULL, texto TEXT NOT NULL, fecha INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS index_notificaciones_crudas_paquete_titulo_texto " +
                "ON notificaciones_crudas(paquete, titulo, texto)"
        )
    }
}

private val MIGRACION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE notificaciones_crudas ADD COLUMN etiqueta TEXT")
    }
}

private val MIGRACION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("UPDATE notificaciones_crudas SET etiqueta = 'gasto' WHERE etiqueta = 'pago'")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS notas (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, texto TEXT NOT NULL, fecha INTEGER NOT NULL)"
        )
    }
}

/**
 * - Agrega el tipo (gasto/ingreso/transferencia).
 * - Wallet actualiza su notificación (nombre "bonito" del comercio) y cada versión se registraba
 *   como pago nuevo: nos quedamos con la primera.
 * - Tarjetas con un solo nombre, sin dígitos.
 * - Vuelve a adivinar la categoría de lo que quedó en "Otros" con las reglas nuevas.
 */
private val MIGRACION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE transacciones ADD COLUMN tipo TEXT NOT NULL DEFAULT '${Tipos.GASTO}'")

        val elegidas = HashSet<String>()
        db.query("SELECT comercio FROM categoria_comercio").use { c -> while (c.moveToNext()) elegidas += c.getString(0) }

        data class Fila(val id: Long, val clave: String?, val vinculada: String?, val tarjeta: String, val comercio: String, val categoria: String)
        val filas = ArrayList<Fila>()
        db.query("SELECT id, claveUnica, claveVinculada, tarjeta, comercio, categoria FROM transacciones ORDER BY fecha").use { c ->
            while (c.moveToNext()) {
                filas += Fila(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5))
            }
        }

        val vistas = HashSet<String>()
        for (f in filas) {
            val clave = f.clave?.let(Claves::migrar)
            if (clave != null && !vistas.add(clave)) {
                db.execSQL("DELETE FROM transacciones WHERE id = ?", arrayOf<Any>(f.id))
                continue
            }
            val vinculada = f.vinculada?.let(Claves::migrar)?.takeIf { vistas.add(it) }
            val categoria = if (f.categoria == "Otros" && f.comercio !in elegidas) Categorias.adivinar(f.comercio) else f.categoria
            db.execSQL(
                "UPDATE transacciones SET claveUnica = ?, claveVinculada = ?, tarjeta = ?, categoria = ? WHERE id = ?",
                arrayOf<Any?>(clave, vinculada, Tarjetas.canonica(f.tarjeta), categoria, f.id),
            )
        }
    }
}

/** Tabla de tarjetas de crédito. Las tarjetas y sus fechas se dan de alta desde la pantalla Tarjetas. */
private val MIGRACION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS tarjetas (nombre TEXT NOT NULL PRIMARY KEY, diaCorte INTEGER NOT NULL, " +
                "diaLimite INTEGER NOT NULL, avisarDiasAntes INTEGER NOT NULL, pagadoCorte INTEGER)"
        )
    }
}

/** Color por tarjeta. Arrancan con el color de cada marca; se cambian desde "Editar". */
private val MIGRACION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE tarjetas ADD COLUMN color INTEGER")
        mapOf(
            Tarjetas.BBVA_CREDITO to 0xFF1973B8,
            Tarjetas.RAPPICARD to 0xFFFF441F,
            Tarjetas.NU to 0xFF820AD1,
            Tarjetas.MERCADO_PAGO to 0xFF009EE3,
            Tarjetas.BANAMEX to 0xFFD81E05,
        ).forEach { (nombre, argb) ->
            db.execSQL("UPDATE tarjetas SET color = ? WHERE nombre = ?", arrayOf<Any>(argb.toInt(), nombre))
        }
    }
}

/** Cambia los colores iniciales por los del logo de cada app (solo si no los has cambiado tú). */
private val MIGRACION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        mapOf(
            0xFF1973B8 to 0xFF001391, // BBVA
            0xFFFF441F to 0xFFF6294F, // Rappi
            0xFF820AD1 to 0xFF7D09C9, // Nu
            0xFF009EE3 to 0xFFF6DF00, // Mercado Pago
            0xFFD81E05 to 0xFF003544, // Banamex
        ).forEach { (antes, ahora) ->
            db.execSQL("UPDATE tarjetas SET color = ? WHERE color = ?", arrayOf<Any>(ahora.toInt(), antes.toInt()))
        }
    }
}

/** Claves de notificaciones marcadas como duplicado (para que no regresen). */
private val MIGRACION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS claves_descartadas (clave TEXT NOT NULL PRIMARY KEY)")
    }
}

private val MIGRACION_10_11 = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE transacciones ADD COLUMN lat REAL")
        db.execSQL("ALTER TABLE transacciones ADD COLUMN lon REAL")
        db.execSQL("ALTER TABLE transacciones ADD COLUMN lugar TEXT")
    }
}
