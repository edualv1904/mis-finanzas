package com.misfinanzas

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.misfinanzas.data.FinanzasDb
import com.misfinanzas.data.NotificacionCruda
import com.misfinanzas.data.Nota
import com.misfinanzas.data.TarjetaConfig
import com.misfinanzas.data.Transaccion
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.YearMonth
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class FinanzasViewModel(app: Application) : AndroidViewModel(app) {
    private val dao = FinanzasDb.get(app).dao()
    private val zona = ZoneId.systemDefault()

    private val _mes = MutableStateFlow(YearMonth.now())
    val mes: StateFlow<YearMonth> = _mes.asStateFlow()

    val transacciones: StateFlow<List<Transaccion>> = _mes
        .flatMapLatest { m ->
            val desde = m.atDay(1).atStartOfDay(zona).toInstant().toEpochMilli()
            val hasta = m.plusMonths(1).atDay(1).atStartOfDay(zona).toInstant().toEpochMilli()
            dao.observarRango(desde, hasta)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Movimientos del mes que tienen ubicación, para el mapa. */
    val conUbicacion: StateFlow<List<Transaccion>> = _mes
        .flatMapLatest { m ->
            val desde = m.atDay(1).atStartOfDay(zona).toInstant().toEpochMilli()
            val hasta = m.plusMonths(1).atDay(1).atStartOfDay(zona).toInstant().toEpochMilli()
            dao.observarConUbicacion(desde, hasta)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val tarjetas: StateFlow<List<String>> = dao.observarTarjetas()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val crudas: StateFlow<List<NotificacionCruda>> = dao.observarCrudas()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val crudasSinRevisar: StateFlow<Int> = dao.observarCrudasSinRevisar()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    fun etiquetar(n: NotificacionCruda, etiqueta: String?) = viewModelScope.launch { dao.etiquetarCruda(n.id, etiqueta) }

    fun borrarIgnoradas() = viewModelScope.launch { dao.borrarCrudasIgnoradas() }

    val notas: StateFlow<List<Nota>> = dao.observarNotas()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun guardarNota(n: Nota) = viewModelScope.launch { dao.guardarNota(n) }

    fun eliminarNota(n: Nota) = viewModelScope.launch { dao.eliminarNota(n) }

    val tarjetasConfig: StateFlow<List<TarjetaConfig>> = dao.observarTarjetasConfig()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val primeraFecha: StateFlow<Long?> = dao.observarPrimeraFecha()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Movimientos de los últimos 3 meses: alcanzan para calcular los periodos de todas las tarjetas. */
    val recientes: StateFlow<List<Transaccion>> = dao.observarRango(
        desde = YearMonth.now().minusMonths(3).atDay(1).atStartOfDay(zona).toInstant().toEpochMilli(),
        hasta = Long.MAX_VALUE,
    ).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun guardarTarjeta(t: TarjetaConfig) = viewModelScope.launch { dao.guardarTarjeta(t) }

    fun eliminarTarjeta(t: TarjetaConfig) = viewModelScope.launch { dao.eliminarTarjeta(t) }

    fun cambiarMes(delta: Long) {
        _mes.value = _mes.value.plusMonths(delta)
    }

    fun guardar(t: Transaccion, categoriaCambio: Boolean) = viewModelScope.launch {
        Registro.guardar(getApplication(), t, categoriaCambio)
    }

    fun eliminar(t: Transaccion) = viewModelScope.launch { Registro.eliminar(getApplication(), t) }

    /** Compras a meses (con sus mensualidades), de cualquier fecha. */
    val meses: StateFlow<List<Meses.Plan>> = dao.observarMeses()
        .map(Meses::agrupar)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun pasarAMeses(t: Transaccion, meses: Int, primerMes: YearMonth) =
        viewModelScope.launch { Registro.pasarAMeses(getApplication(), t, meses, primerMes) }

    fun quitarMeses(t: Transaccion) = viewModelScope.launch { Registro.quitarMeses(getApplication(), t) }

    suspend fun posiblesDuplicados(t: Transaccion) = Registro.posiblesDuplicados(getApplication(), t)

    fun marcarDuplicado(quitar: Transaccion, conservar: Transaccion) =
        viewModelScope.launch { Registro.marcarDuplicado(getApplication(), quitar, conservar) }

    // ---------- Me deben ----------

    init {
        viewModelScope.launch { Cobros.generar(getApplication()) }
    }

    val acuerdos: StateFlow<List<com.misfinanzas.data.Acuerdo>> = dao.observarAcuerdos()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val cobros: StateFlow<List<com.misfinanzas.data.Cobro>> = dao.observarCobros()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Transferencias recibidas en los últimos 90 días: posibles pagos de quien te debe. */
    val ingresosRecientes: StateFlow<List<Transaccion>> = dao.observarIngresosDesde(System.currentTimeMillis() - 90L * 86_400_000L)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun crearAcuerdo(d: com.misfinanzas.ui.Datos) = viewModelScope.launch {
        val desde = if (d.meses == 1) Cobros.mesDeUnaVez(java.time.LocalDate.now(), d.dia) else null
        Cobros.crear(getApplication(), d.personas, d.concepto, d.monto, d.dia, d.meses, d.actual, d.esteMesPagado, desde)
    }

    fun editarAcuerdo(a: com.misfinanzas.data.Acuerdo) = viewModelScope.launch { Cobros.editar(getApplication(), a) }

    fun marcarCobro(c: com.misfinanzas.data.Cobro, pagado: Long?, abonoId: Long?) = viewModelScope.launch {
        dao.marcarCobro(c.id, pagado, abonoId)
        // Deshacer en un acuerdo que ya se había cerrado solo (todo pagado): vuelve a estar activo.
        if (pagado == null) acuerdos.value.firstOrNull { it.id == c.acuerdoId && !it.activo }?.let { dao.actualizarAcuerdo(it.copy(activo = true)) }
        Cobros.generar(getApplication())
    }

    fun cambiarMontoCobro(c: com.misfinanzas.data.Cobro, monto: Double) = viewModelScope.launch { dao.cambiarMontoCobro(c.id, monto) }

    fun terminarAcuerdo(a: com.misfinanzas.data.Acuerdo) = viewModelScope.launch {
        dao.actualizarAcuerdo(a.copy(activo = false))
        dao.borrarCobrosPendientes(a.id)
    }

    fun borrarAcuerdo(a: com.misfinanzas.data.Acuerdo) = viewModelScope.launch {
        dao.borrarCobros(a.id)
        dao.borrarAcuerdo(a.id)
    }
}
