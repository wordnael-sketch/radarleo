package br.radarleo.location

import android.location.Location
import br.radarleo.alert.Movimento
import br.radarleo.alert.RadarAFrente
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Uma leitura do GPS já nas unidades que o motor de alertas usa. */
data class Posicao(
    val latitude: Double,
    val longitude: Double,
    val velocidadeKmh: Double?, // null quando o GPS não informa
    val rumoGraus: Double?,     // 0 = norte; null parado ou sem informação
    val precisaoM: Float?,
    val instante: Long,
) {
    companion object {
        fun de(l: Location) = Posicao(
            latitude = l.latitude,
            longitude = l.longitude,
            velocidadeKmh = if (l.hasSpeed()) msParaKmh(l.speed) else null,
            rumoGraus = if (l.hasBearing()) l.bearing.toDouble() else null,
            precisaoM = if (l.hasAccuracy()) l.accuracy else null,
            instante = l.time,
        )

        fun msParaKmh(ms: Float): Double = ms * 3.6
    }
}

/** Estado compartilhado entre o serviço (que escreve) e a tela / motor de alertas (que leem). */
object Rastreamento {
    private val _ativo = MutableStateFlow(false)
    val ativo: StateFlow<Boolean> = _ativo.asStateFlow()

    private val _posicao = MutableStateFlow<Posicao?>(null)
    val posicao: StateFlow<Posicao?> = _posicao.asStateFlow()

    /** Velocidade e rumo já tratados pelo estimador (o que o motor de alertas usou). */
    private val _movimento = MutableStateFlow<Movimento?>(null)
    val movimento: StateFlow<Movimento?> = _movimento.asStateFlow()

    /** Radar mais próximo à frente, para a tela e a bolha. */
    private val _aFrente = MutableStateFlow<RadarAFrente?>(null)
    val aFrente: StateFlow<RadarAFrente?> = _aFrente.asStateFlow()

    /** O RadarLeo está na tela: a bolha se esconde (repetiria o que o painel já mostra). */
    private val _appVisivel = MutableStateFlow(false)
    val appVisivel: StateFlow<Boolean> = _appVisivel.asStateFlow()

    fun appVisivel(v: Boolean) { _appVisivel.value = v }

    internal fun iniciado() { _ativo.value = true }

    internal fun publicar(p: Posicao) { _posicao.value = p }

    internal fun publicarAnalise(m: Movimento, a: RadarAFrente?) {
        _movimento.value = m
        _aFrente.value = a
    }

    internal fun parado() {
        _ativo.value = false
        _posicao.value = null
        _movimento.value = null
        _aFrente.value = null
    }
}
