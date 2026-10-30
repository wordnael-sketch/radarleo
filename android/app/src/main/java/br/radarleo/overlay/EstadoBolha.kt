package br.radarleo.overlay

import br.radarleo.alert.ConfigAlertas
import br.radarleo.alert.Movimento
import br.radarleo.alert.RadarAFrente
import br.radarleo.data.TipoRadar

/** NORMAL: dentro do limite. ACIMA: passou do limite, ainda na tolerância. EXCESSO: vai bipar. */
enum class NivelVelocidade { NORMAL, ACIMA, EXCESSO }

/** O que a bolha mostra. Sem nada de Android, para testar na JVM. */
data class EstadoBolha(
    val tipo: TipoRadar,
    val distanciaTexto: String,
    val limiteKmh: Int?,
    val velocidadeTexto: String,
    val nivel: NivelVelocidade,
    val possivel: Boolean,
) {
    companion object {
        /** null = não há radar à frente: a bolha some. */
        fun de(aFrente: RadarAFrente?, movimento: Movimento?, config: ConfigAlertas): EstadoBolha? {
            val r = aFrente?.radar ?: return null
            val v = movimento?.velocidadeKmh
            val limite = r.limiteKmh
            val nivel = when {
                v == null || limite == null -> NivelVelocidade.NORMAL
                v > config.velocidadeMaxima(limite) -> NivelVelocidade.EXCESSO
                v > limite -> NivelVelocidade.ACIMA
                else -> NivelVelocidade.NORMAL
            }
            return EstadoBolha(
                tipo = TipoRadar.de(r.tipo),
                distanciaTexto = textoDistancia(aFrente.distanciaM),
                limiteKmh = limite,
                velocidadeTexto = v?.let { "%.0f".format(it) } ?: "–",
                nivel = nivel,
                possivel = r.confiabilidade < config.confiabilidadeMinima,
            )
        }

        /** Contagem regressiva de 10 em 10 m (arredonda para baixo: nunca promete mais distância). */
        fun textoDistancia(m: Double): String =
            if (m >= 1000) "%.1f km".format(m / 1000).replace('.', ',')
            else "${(m / 10).toInt() * 10} m"
    }
}
