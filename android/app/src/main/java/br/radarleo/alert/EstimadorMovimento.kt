package br.radarleo.alert

import br.radarleo.location.Posicao

data class Movimento(val velocidadeKmh: Double?, val rumoGraus: Double?)

/**
 * Velocidade e rumo para o motor de alertas. Usa o que o GPS informa e, quando falta
 * ou é incoerente, calcula pelo deslocamento das últimas leituras (janela de ~3 s,
 * que suaviza o ruído de posição de alguns metros a cada segundo).
 *
 * A velocidade entregue é a mediana das 3 últimas: um pico isolado do GPS não vira
 * "Reduza!" falso, e um excesso de verdade (que dura mais de 1 s) continua sendo pego.
 */
class EstimadorMovimento(private val janelaMs: Long = 3_000) {

    private val historico = ArrayDeque<Posicao>()
    private val velocidades = ArrayDeque<Double>()

    fun estimar(p: Posicao): Movimento {
        // Salto impossível (saída de túnel, GPS recuperando o sinal): recomeça do ponto novo
        // em vez de calcular uma velocidade absurda a partir dele.
        historico.lastOrNull()?.let { ultima ->
            val dt = (p.instante - ultima.instante) / 1000.0
            val d = Geo.distanciaM(ultima.latitude, ultima.longitude, p.latitude, p.longitude)
            if (dt <= 0 || d / dt * 3.6 > SALTO_KMH) reiniciar()
        }
        historico.addLast(p)
        while (historico.size > 2 && p.instante - historico.first().instante > janelaMs) {
            historico.removeFirst()
        }
        val antiga = historico.first()
        val dtS = (p.instante - antiga.instante) / 1000.0
        val distM = Geo.distanciaM(antiga.latitude, antiga.longitude, p.latitude, p.longitude)

        val calculada = if (dtS >= MIN_JANELA_S) distM / dtS * 3.6 else null
        val gps = p.velocidadeKmh
        val instantanea = when {
            gps == null -> calculada
            // GPS dizendo menos da metade do deslocamento real a mais de 20 km/h: travado ou sem fix bom
            calculada != null && calculada > 20 && gps < calculada / 2 -> calculada
            else -> gps
        }

        val rumo = p.rumoGraus
            ?: if (distM >= MIN_DESLOCAMENTO_RUMO_M) Geo.rumo(antiga.latitude, antiga.longitude, p.latitude, p.longitude)
            else null
        return Movimento(mediana(instantanea), rumo)
    }

    private fun mediana(v: Double?): Double? {
        if (v == null) return null
        velocidades.addLast(v)
        while (velocidades.size > AMOSTRAS_MEDIANA) velocidades.removeFirst()
        val ordenadas = velocidades.sorted()
        val meio = ordenadas.size / 2
        return if (ordenadas.size % 2 == 1) ordenadas[meio] else (ordenadas[meio - 1] + ordenadas[meio]) / 2
    }

    fun reiniciar() {
        historico.clear()
        velocidades.clear()
    }

    private companion object {
        const val MIN_JANELA_S = 1.5
        const val SALTO_KMH = 250.0
        const val AMOSTRAS_MEDIANA = 3
        const val MIN_DESLOCAMENTO_RUMO_M = 10.0 // abaixo disso o rumo calculado é só ruído
    }
}
