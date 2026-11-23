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
    /**
     * Gasto compartido en un grupo: lo que te toca a ti. Es lo que suma en "Gastado" (la tarjeta sigue
     * contando [monto] completo). Null = todo es tuyo.
     */
    val miParte: Double? = null,
) {
    /** Lo que de verdad gastaste tú. */
    val mio get() = miParte ?: monto
}

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

/**
 * "Me deben": alguien te paga cada mes una cantidad (su parte de Spotify, las mensualidades de una
 * compra que hiciste por él). No toca tus movimientos: solo sirve para cobrar y recordar.
 */
@Entity(tableName = "acuerdos")
data class Acuerdo(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val persona: String,
    val concepto: String,
    /** Lo que te paga cada mes. */
    val monto: Double,
    /** Día del mes en que te debe pagar. */
    val dia: Int,
    /** Primer mes del acuerdo, "AAAA-MM". */
    val desde: String,
    /** Total de meses (p. ej. 18 de una compra a meses); null = cada mes, sin fin. */
    val meses: Int? = null,
    val activo: Boolean = true,
)

/** Un mes de un [Acuerdo]: lo que te deben ese mes y si ya te lo pagaron. */
@Entity(tableName = "cobros", indices = [Index(value = ["acuerdoId", "mes"], unique = true)])
data class Cobro(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val acuerdoId: Long,
    /** "AAAA-MM". */
    val mes: String,
    /** 1, 2, 3… desde el primer mes del acuerdo. */
    val numero: Int,
    val monto: Double,
    /** Mediodía del día en que debía pagarte. */
    val vence: Long,
    /** Cuándo te pagó; null = pendiente. */
    val pagado: Long? = null,
    /** El abono (movimiento de ingreso) con el que te pagó, si lo ligaste. */
    val abonoId: Long? = null,
    /** Última vez que la app te avisó que no te ha pagado. */
    val ultimoAviso: Long? = null,
)

/**
 * "Debo": lo que le debes a alguien sin fecha fija. Es una libreta: lo que se suma ([DeudaMov] tipo
 * cargo: un préstamo, cada clase o consulta) menos lo que vas abonando.
 */
@Entity(tableName = "deudas")
data class Deuda(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val persona: String,
    /** Costo por sesión o visita (clases, consultas); null = solo un saldo (un préstamo). */
    val tarifa: Double? = null,
    /** Preguntarte cada sábado si fuiste (solo con tarifa). */
    val preguntarSabado: Boolean = false,
    val activa: Boolean = true,
    val creada: Long = System.currentTimeMillis(),
)

/** Un renglón de la libreta de una [Deuda]. */
@Entity(tableName = "deuda_movs", indices = [Index("deudaId")])
data class DeudaMov(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val deudaId: Long,
    val fecha: Long,
    /** Siempre positivo: [tipo] dice si suma o resta. */
    val monto: Double,
    /** [Deudas.CARGO] (sesión, préstamo, saldo inicial) o [Deudas.ABONO] (lo que pagaste). */
    val tipo: String,
    val nota: String = "",
    /** La transferencia con la que pagaste, si la ligaste. */
    val transaccionId: Long? = null,
)

/** Un viaje o una cuenta compartida (estilo Splitwise). Tú siempre estás; [GrupoMiembro] son los demás. */
@Entity(tableName = "grupos")
data class Grupo(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val nombre: String,
    val creado: Long = System.currentTimeMillis(),
    val activo: Boolean = true,
)

@Entity(tableName = "grupo_miembros", indices = [Index(value = ["grupoId", "persona"], unique = true)])
data class GrupoMiembro(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val grupoId: Long,
    val persona: String,
)

/**
 * Un gasto del grupo ([com.misfinanzas.Grupos.GASTO]: [pago] pagó [monto] y se reparte en
 * [GrupoParte]) o un pago entre ellos ([com.misfinanzas.Grupos.PAGO]: [pago] le dio el dinero a
 * quien dice su única parte). [pago] es un nombre o [com.misfinanzas.Grupos.YO].
 */
@Entity(tableName = "grupo_gastos", indices = [Index("grupoId")])
data class GrupoGasto(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val grupoId: Long,
    val fecha: Long,
    val concepto: String,
    val monto: Double,
    val pago: String,
    val tipo: String,
    /** Tu movimiento de tarjeta con el que pagaste, si salió de ahí. */
    val transaccionId: Long? = null,
)

/** Cuánto le toca a cada quien de un [GrupoGasto]. */
@Entity(tableName = "grupo_partes", indices = [Index("gastoId")])
data class GrupoParte(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val gastoId: Long,
    val persona: String,
    val monto: Double,
)

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

    @Query("SELECT COALESCE(SUM(COALESCE(miParte, monto)), 0) FROM transacciones WHERE tipo = 'gasto' AND fecha >= :desde AND fecha < :hasta")
    suspend fun sumaGastos(desde: Long, hasta: Long): Double

    /** Igual que [sumaGastos] pero se vuelve a emitir cada vez que cambian los movimientos. */
    @Query("SELECT COALESCE(SUM(COALESCE(miParte, monto)), 0) FROM transacciones WHERE tipo = 'gasto' AND fecha >= :desde AND fecha < :hasta")
    fun observarSumaGastos(desde: Long, hasta: Long): Flow<Double>

    @Query(
        """SELECT EXISTS(SELECT 1 FROM transacciones WHERE claveUnica = :clave OR claveVinculada = :clave)
           OR EXISTS(SELECT 1 FROM claves_descartadas WHERE clave = :clave)"""
    )
    suspend fun claveVista(clave: String): Boolean

    /** ¿Hay un movimiento con llave vieja (solo texto, sin hora) igual a [base] entre [desde] y [hasta]? */
    @Query(
        """SELECT EXISTS(SELECT 1 FROM transacciones WHERE fecha BETWEEN :desde AND :hasta
             AND (claveUnica = :base OR claveVinculada = :base))"""
    )
    suspend fun textoVistoCerca(base: String, desde: Long, hasta: Long): Boolean

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

    /** Limpieza automática: las que marcaste "Ignorar" y llegaron antes de [antesDe]. */
    @Query("DELETE FROM notificaciones_crudas WHERE etiqueta = 'ignorar' AND fecha < :antesDe")
    suspend fun borrarIgnoradasViejas(antesDe: Long): Int

    @Query("SELECT * FROM notas ORDER BY fecha DESC")
    fun observarNotas(): Flow<List<Nota>>

    // ---------- Me deben ----------

    @Insert
    suspend fun insertarAcuerdo(a: Acuerdo): Long

    @Update
    suspend fun actualizarAcuerdo(a: Acuerdo)

    @Query("SELECT * FROM acuerdos WHERE activo = 1")
    suspend fun acuerdosActivos(): List<Acuerdo>

    @Query("SELECT * FROM acuerdos")
    suspend fun acuerdosTodos(): List<Acuerdo>

    @Query("SELECT * FROM acuerdos ORDER BY activo DESC, persona")
    fun observarAcuerdos(): Flow<List<Acuerdo>>

    @Query("DELETE FROM acuerdos WHERE id = :id")
    suspend fun borrarAcuerdo(id: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertarCobros(c: List<Cobro>)

    @Query("SELECT * FROM cobros ORDER BY vence")
    fun observarCobros(): Flow<List<Cobro>>

    @Query("SELECT * FROM cobros WHERE pagado IS NULL ORDER BY vence")
    suspend fun cobrosPendientes(): List<Cobro>

    @Query("SELECT * FROM cobros WHERE acuerdoId = :acuerdoId ORDER BY numero")
    suspend fun cobrosDe(acuerdoId: Long): List<Cobro>

    @Query("UPDATE cobros SET pagado = :pagado, abonoId = :abonoId WHERE id = :id")
    suspend fun marcarCobro(id: Long, pagado: Long?, abonoId: Long?)

    @Query("UPDATE cobros SET ultimoAviso = :cuando WHERE id IN (:ids)")
    suspend fun cobrosAvisados(ids: List<Long>, cuando: Long)

    @Query("DELETE FROM cobros WHERE acuerdoId = :acuerdoId AND pagado IS NULL")
    suspend fun borrarCobrosPendientes(acuerdoId: Long)

    @Query("DELETE FROM cobros WHERE acuerdoId = :acuerdoId")
    suspend fun borrarCobros(acuerdoId: Long)

    @Query("UPDATE cobros SET monto = :monto, vence = :vence WHERE id = :id")
    suspend fun ajustarCobro(id: Long, monto: Double, vence: Long)

    @Query("UPDATE cobros SET monto = :monto WHERE id = :id")
    suspend fun cambiarMontoCobro(id: Long, monto: Double)

    @Query("DELETE FROM cobros WHERE acuerdoId = :acuerdoId AND pagado IS NULL AND numero > :ultimo")
    suspend fun borrarCobrosDespuesDe(acuerdoId: Long, ultimo: Int)

    // ---------- Personas ----------

    @Query("UPDATE acuerdos SET persona = :nombre WHERE id IN (:ids)")
    suspend fun renombrarAcuerdos(ids: List<Long>, nombre: String)

    @Query("UPDATE deudas SET persona = :nombre WHERE id IN (:ids)")
    suspend fun renombrarDeudas(ids: List<Long>, nombre: String)

    // ---------- Grupos ----------

    @Query("SELECT * FROM grupos ORDER BY activo DESC, creado DESC")
    fun observarGrupos(): Flow<List<Grupo>>

    @Query("SELECT * FROM grupos")
    suspend fun gruposTodos(): List<Grupo>

    @Query("SELECT * FROM grupo_gastos")
    suspend fun grupoGastosTodos(): List<GrupoGasto>

    @Query("SELECT * FROM grupo_miembros")
    suspend fun miembrosTodos(): List<GrupoMiembro>

    @Query("SELECT * FROM grupo_partes")
    suspend fun partesTodas(): List<GrupoParte>

    @Query("SELECT * FROM grupo_miembros")
    fun observarMiembros(): Flow<List<GrupoMiembro>>

    @Query("SELECT * FROM grupo_gastos ORDER BY fecha DESC, id DESC")
    fun observarGrupoGastos(): Flow<List<GrupoGasto>>

    @Query("SELECT * FROM grupo_partes")
    fun observarGrupoPartes(): Flow<List<GrupoParte>>

    @Insert
    suspend fun insertarGrupo(g: Grupo): Long

    @Update
    suspend fun actualizarGrupo(g: Grupo)

    @Query("DELETE FROM grupos WHERE id = :id")
    suspend fun borrarGrupo(id: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertarMiembros(m: List<GrupoMiembro>)

    @Query("DELETE FROM grupo_miembros WHERE grupoId = :grupoId AND persona NOT IN (:quedan)")
    suspend fun quitarMiembros(grupoId: Long, quedan: List<String>)

    @Query("DELETE FROM grupo_miembros WHERE grupoId = :grupoId")
    suspend fun borrarMiembros(grupoId: Long)

    @Query("SELECT * FROM grupo_gastos WHERE grupoId = :grupoId")
    suspend fun gastosDeGrupo(grupoId: Long): List<GrupoGasto>

    @Insert
    suspend fun insertarGrupoGasto(g: GrupoGasto): Long

    @Update
    suspend fun actualizarGrupoGasto(g: GrupoGasto)

    @Query("DELETE FROM grupo_gastos WHERE id = :id")
    suspend fun borrarGrupoGasto(id: Long)

    @Insert
    suspend fun insertarPartes(p: List<GrupoParte>)

    @Query("DELETE FROM grupo_partes WHERE gastoId = :gastoId")
    suspend fun borrarPartes(gastoId: Long)

    @Query("UPDATE transacciones SET miParte = :miParte WHERE id = :id")
    suspend fun ponerMiParte(id: Long, miParte: Double?)

    @Query("UPDATE grupo_miembros SET persona = :nombre WHERE id IN (:ids)")
    suspend fun renombrarMiembros(ids: List<Long>, nombre: String)

    @Query("UPDATE grupo_gastos SET pago = :nombre WHERE pago = :viejo")
    suspend fun renombrarPagador(viejo: String, nombre: String)

    @Query("UPDATE grupo_partes SET persona = :nombre WHERE persona = :viejo")
    suspend fun renombrarEnPartes(viejo: String, nombre: String)

    // ---------- Debo ----------

    @Query("SELECT * FROM deudas ORDER BY persona COLLATE NOCASE")
    fun observarDeudas(): Flow<List<com.misfinanzas.data.Deuda>>

    @Query("SELECT * FROM deuda_movs ORDER BY fecha DESC, id DESC")
    fun observarDeudaMovs(): Flow<List<com.misfinanzas.data.DeudaMov>>

    @Query("SELECT * FROM deudas WHERE id = :id")
    suspend fun deudaPorId(id: Long): com.misfinanzas.data.Deuda?

    @Query("SELECT * FROM deudas WHERE activa = 1 AND preguntarSabado = 1 AND tarifa IS NOT NULL")
    suspend fun deudasQuePreguntan(): List<com.misfinanzas.data.Deuda>

    @Query("SELECT * FROM deuda_movs WHERE deudaId = :deudaId")
    suspend fun movsDe(deudaId: Long): List<com.misfinanzas.data.DeudaMov>

    @Insert
    suspend fun insertarDeuda(d: com.misfinanzas.data.Deuda): Long

    @androidx.room.Update
    suspend fun actualizarDeuda(d: com.misfinanzas.data.Deuda)

    @Query("DELETE FROM deudas WHERE id = :id")
    suspend fun borrarDeuda(id: Long)

    @Query("DELETE FROM deuda_movs WHERE deudaId = :deudaId")
    suspend fun borrarMovsDe(deudaId: Long)

    @Insert
    suspend fun insertarDeudaMov(m: com.misfinanzas.data.DeudaMov): Long

    @androidx.room.Update
    suspend fun actualizarDeudaMov(m: com.misfinanzas.data.DeudaMov)

    @Query("DELETE FROM deuda_movs WHERE id = :id")
    suspend fun borrarDeudaMov(id: Long)

    /** Transferencias que salieron: candidatas a ser un abono de lo que debes. */
    @Query("SELECT * FROM transacciones WHERE tipo = 'transferencia' AND fecha >= :desde ORDER BY fecha DESC")
    fun observarTransferenciasDesde(desde: Long): Flow<List<Transaccion>>

    /** Ingresos recientes: candidatos a ser el pago de alguien. */
    @Query("SELECT * FROM transacciones WHERE tipo = 'ingreso' AND fecha >= :desde ORDER BY fecha DESC")
    fun observarIngresosDesde(desde: Long): Flow<List<Transaccion>>

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

@Database(entities = [Transaccion::class, CategoriaComercio::class, NotificacionCruda::class, Nota::class, TarjetaConfig::class, ClaveDescartada::class, Acuerdo::class, Cobro::class, Deuda::class, DeudaMov::class, Grupo::class, GrupoMiembro::class, GrupoGasto::class, GrupoParte::class], version = 14, exportSchema = false)
abstract class FinanzasDb : RoomDatabase() {
    abstract fun dao(): TransaccionDao

    companion object {
        @Volatile private var instancia: FinanzasDb? = null

        fun get(context: Context): FinanzasDb =
            instancia ?: synchronized(this) {
                instancia ?: Room.databaseBuilder(
                    context.applicationContext, FinanzasDb::class.java, "finanzas.db"
                ).addMigrations(MIGRACION_1_2, MIGRACION_2_3, MIGRACION_3_4, MIGRACION_4_5, MIGRACION_5_6, MIGRACION_6_7, MIGRACION_7_8, MIGRACION_8_9, MIGRACION_9_10, MIGRACION_10_11, MIGRACION_11_12, MIGRACION_12_13, MIGRACION_13_14).build().also { instancia = it }
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

/** Tabla de tarjetas de crédito, con las fechas que tenías al crearla (13 nov 2026). */
private val MIGRACION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS tarjetas (nombre TEXT NOT NULL PRIMARY KEY, diaCorte INTEGER NOT NULL, " +
                "diaLimite INTEGER NOT NULL, avisarDiasAntes INTEGER NOT NULL, pagadoCorte INTEGER)"
        )
        listOf(
            Triple(Tarjetas.BBVA_CREDITO, 12, 2),
            Triple(Tarjetas.RAPPICARD, 15, 4),
            Triple(Tarjetas.NU, 14, 26),
            Triple(Tarjetas.MERCADO_PAGO, 21, 1),
            Triple(Tarjetas.BANAMEX, 16, 6),
        ).forEach { (nombre, corte, limite) ->
            db.execSQL(
                "INSERT OR IGNORE INTO tarjetas (nombre, diaCorte, diaLimite, avisarDiasAntes) VALUES (?, ?, ?, 3)",
                arrayOf<Any>(nombre, corte, limite),
            )
        }
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

/** "Debo": tablas nuevas; no toca las existentes. */
private val MIGRACION_12_13 = object : Migration(12, 13) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS deudas (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, persona TEXT NOT NULL, " +
                "tarifa REAL, preguntarSabado INTEGER NOT NULL, activa INTEGER NOT NULL, creada INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS deuda_movs (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, deudaId INTEGER NOT NULL, " +
                "fecha INTEGER NOT NULL, monto REAL NOT NULL, tipo TEXT NOT NULL, nota TEXT NOT NULL, transaccionId INTEGER)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_deuda_movs_deudaId ON deuda_movs (deudaId)")
    }
}

/** "Me deben": tablas nuevas; no toca las existentes. */
private val MIGRACION_11_12 = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS acuerdos (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, persona TEXT NOT NULL, " +
                "concepto TEXT NOT NULL, monto REAL NOT NULL, dia INTEGER NOT NULL, desde TEXT NOT NULL, meses INTEGER, " +
                "activo INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS cobros (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, acuerdoId INTEGER NOT NULL, " +
                "mes TEXT NOT NULL, numero INTEGER NOT NULL, monto REAL NOT NULL, vence INTEGER NOT NULL, pagado INTEGER, " +
                "abonoId INTEGER, ultimoAviso INTEGER)"
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_cobros_acuerdoId_mes ON cobros (acuerdoId, mes)")
    }
}

private val MIGRACION_10_11 = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE transacciones ADD COLUMN lat REAL")
        db.execSQL("ALTER TABLE transacciones ADD COLUMN lon REAL")
        db.execSQL("ALTER TABLE transacciones ADD COLUMN lugar TEXT")
    }
}

/** Grupos y viajes (estilo Splitwise), y "tu parte" de un gasto compartido. */
internal val SQL_13_14 = listOf(
    "ALTER TABLE transacciones ADD COLUMN miParte REAL",
    "CREATE TABLE IF NOT EXISTS grupos (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, nombre TEXT NOT NULL, " +
        "creado INTEGER NOT NULL, activo INTEGER NOT NULL)",
    "CREATE TABLE IF NOT EXISTS grupo_miembros (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, grupoId INTEGER NOT NULL, " +
        "persona TEXT NOT NULL)",
    "CREATE UNIQUE INDEX IF NOT EXISTS index_grupo_miembros_grupoId_persona ON grupo_miembros(grupoId, persona)",
    "CREATE TABLE IF NOT EXISTS grupo_gastos (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, grupoId INTEGER NOT NULL, " +
        "fecha INTEGER NOT NULL, concepto TEXT NOT NULL, monto REAL NOT NULL, pago TEXT NOT NULL, tipo TEXT NOT NULL, " +
        "transaccionId INTEGER)",
    "CREATE INDEX IF NOT EXISTS index_grupo_gastos_grupoId ON grupo_gastos(grupoId)",
    "CREATE TABLE IF NOT EXISTS grupo_partes (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, gastoId INTEGER NOT NULL, " +
        "persona TEXT NOT NULL, monto REAL NOT NULL)",
    "CREATE INDEX IF NOT EXISTS index_grupo_partes_gastoId ON grupo_partes(gastoId)",
)

private val MIGRACION_13_14 = object : Migration(13, 14) {
    override fun migrate(db: SupportSQLiteDatabase) {
        SQL_13_14.forEach(db::execSQL)
    }
}
