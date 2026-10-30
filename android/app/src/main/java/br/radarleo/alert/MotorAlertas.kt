package br.radarleo.alert

import br.radarleo.data.Radar

data class ConfigAlertas(
    val distanciasM: List<Int> = listOf(500, 300, 100),
    /** Tolerância sobre o limite, em %. null = regra do Inmetro: 7 km/h até 100 km/h, 7% acima. */
    val toleranciaPct: Int? = null,
    val velocidadeMinimaKmh: Double = 10.0,
    val coneGraus: Double = 30.0,
    val toleranciaDirecaoGraus: Double = 45.0,
    val confiabilidadeMinima: Int = 60,
    val intervaloInsistenciaMs: Long = 4_000,
) {
    /** Da maior para a menor: [500, 300, 100]. */
    val faixas: List<Int> = distanciasM.filter { it > 0 }.distinct().sortedDescending().ifEmpty { listOf(300) }
    val alcanceM: Int get() = faixas.first()

    /** Radar de baixa confiabilidade fala uma vez só, na faixa do meio (300 m por padrão). */
    val faixaDiscreta: Int get() = faixas.getOrElse(1) { faixas.last() }

    fun velocidadeMaxima(limiteKmh: Int): Double =
        limiteKmh + (toleranciaPct?.let { limiteKmh * it / 100.0 } ?: if (limiteKmh <= 100) 7.0 else limiteKmh * 0.07)
}

sealed interface Evento {
    val radar: Radar
    val distanciaM: Double
    /** Radar de baixa confiabilidade: alerta mais discreto. */
    val discreto: Boolean
}

/** Aviso de aproximação numa das faixas (500/300/100 m). */
data class Aviso(
    override val radar: Radar,
    override val distanciaM: Double,
    val faixaM: Int,
    override val discreto: Boolean,
) : Evento

/** Acima do limite (com tolerância) com o radar à frente: bipe + voz insistente. */
data class Excesso(
    override val radar: Radar,
    override val distanciaM: Double,
    val velocidadeKmh: Double,
    val limiteKmh: Int,
    override val discreto: Boolean,
) : Evento

data class RadarAFrente(val radar: Radar, val distanciaM: Double)

data class ResultadoAlertas(val eventos: List<Evento>, val aFrente: RadarAFrente?)

/**
 * Decide, a cada leitura do GPS, que alertas tocar. Não sabe nada de Android:
 * recebe posição, movimento e os radares da vizinhança; devolve eventos.
 *
 * Um radar conta como "à frente" quando:
 * - está dentro de ±[ConfigAlertas.coneGraus] do rumo do carro; e
 * - se tiver direção, ela difere do rumo do carro em até [ConfigAlertas.toleranciaDirecaoGraus]
 *   (radar da pista contrária não avisa).
 */
class MotorAlertas(var config: ConfigAlertas = ConfigAlertas()) {

    private class Estado {
        val faixasFaladas = mutableSetOf<Int>()
        var passado = false
        var rumoNaPassagem = 0.0
        var ultimoExcesso: Long? = null
    }

    private val estados = HashMap<String, Estado>()
    private var ultimoRumo: Double? = null

    /** Raio para buscar radares no banco: um pouco além do alcance, para lembrar dos que ficaram para trás. */
    val raioBuscaM: Double get() = (config.alcanceM + MARGEM_BUSCA_M).toDouble()

    fun processar(
        lat: Double, lon: Double, movimento: Movimento, agoraMs: Long, radares: List<Radar>,
    ): ResultadoAlertas {
        // Radar que saiu do raio de busca é esquecido: numa próxima passagem, avisa de novo.
        val perto = radares.mapTo(HashSet()) { it.id }
        estados.keys.retainAll(perto)

        val vel = movimento.velocidadeKmh
        // Parado, o GPS deixa de informar o rumo: vale o último conhecido, para a bolha
        // continuar mostrando o radar à frente enquanto o carro espera no semáforo.
        val rumo = movimento.rumoGraus?.also { ultimoRumo = it } ?: ultimoRumo
            ?: return ResultadoAlertas(emptyList(), null)
        // Abaixo da velocidade mínima: nenhum alerta, só a informação do radar à frente.
        val silencio = vel == null || vel < config.velocidadeMinimaKmh

        val eventos = mutableListOf<Evento>()
        var aFrente: RadarAFrente? = null
        val porDistancia = radares
            .map { it to Geo.distanciaM(lat, lon, it.latitude, it.longitude) }
            .sortedBy { it.second }

        for ((r, d) in porDistancia) {
            var est = estados.getOrPut(r.id) { Estado() }
            if (est.passado) {
                // fez o retorno e vem de volta pela mesma via: o radar volta a valer
                if (Geo.diferencaAngular(rumo, est.rumoNaPassagem) <= RETORNO_GRAUS) continue
                est = Estado().also { estados[r.id] = it }
            }

            val desvio = Geo.diferencaAngular(rumo, Geo.rumo(lat, lon, r.latitude, r.longitude))
            if (desvio > ATRAS_GRAUS) {
                // já avisado e agora atrás do carro: passou, não fala mais dele
                if (est.faixasFaladas.isNotEmpty()) {
                    est.passado = true
                    est.rumoNaPassagem = rumo
                }
                continue
            }
            val noCone = desvio <= config.coneGraus
            val mesmoSentido = r.direcaoGraus == null ||
                Geo.diferencaAngular(rumo, r.direcaoGraus) <= config.toleranciaDirecaoGraus
            if (!noCone || !mesmoSentido || d > config.alcanceM) continue

            if (aFrente == null) aFrente = RadarAFrente(r, d)
            if (silencio || vel == null) continue
            val discreto = r.confiabilidade < config.confiabilidadeMinima

            if (discreto) {
                if (d <= config.faixaDiscreta && est.faixasFaladas.isEmpty()) {
                    eventos += Aviso(r, d, config.faixaDiscreta, discreto = true)
                    est.faixasFaladas += config.faixas
                }
            } else {
                // menor faixa já alcançada; quem entra direto a 250 m ouve só a de 300
                val faixa = config.faixas.last { d <= it }
                if (faixa !in est.faixasFaladas) {
                    eventos += Aviso(r, d, faixa, discreto = false)
                    est.faixasFaladas += config.faixas.filter { it >= faixa }
                }
            }

            // insiste só no limite do radar mais próximo: dois "Reduza!" alternando confundem
            val limite = r.limiteKmh
            if (aFrente.radar.id == r.id && limite != null && vel > config.velocidadeMaxima(limite)) {
                val ultimo = est.ultimoExcesso
                val pode = if (discreto) ultimo == null
                else ultimo == null || agoraMs - ultimo >= config.intervaloInsistenciaMs
                if (pode) {
                    eventos += Excesso(r, d, vel, limite, discreto)
                    est.ultimoExcesso = agoraMs
                }
            }
        }
        return ResultadoAlertas(eventos, aFrente)
    }

    fun reiniciar() {
        estados.clear()
        ultimoRumo = null
    }

    private companion object {
        const val ATRAS_GRAUS = 90.0
        const val RETORNO_GRAUS = 120.0
        const val MARGEM_BUSCA_M = 100
    }
}
