package br.radarleo.alert

import br.radarleo.data.TipoRadar

/** O que a voz fala. Curto de propósito: o motorista ouve enquanto dirige. */
object Frases {

    fun nomeDoTipo(tipo: String) = when (TipoRadar.de(tipo)) {
        TipoRadar.FIXO, TipoRadar.DESCONHECIDO -> "Radar"
        TipoRadar.SEMAFORO -> "Radar de semáforo"
        TipoRadar.LOMBADA -> "Lombada eletrônica"
        TipoRadar.VELOCIDADE_MEDIA -> "Radar de velocidade média"
    }

    fun de(e: Evento): String = when (e) {
        is Aviso ->
            if (e.discreto) "Possível radar a ${e.faixaM} metros"
            else buildString {
                append("${nomeDoTipo(e.radar.tipo)} a ${e.faixaM} metros")
                e.radar.limiteKmh?.let { append(", limite $it") }
            }
        is Excesso ->
            if (e.discreto) "Atenção, possível radar, limite ${e.limiteKmh}"
            else "Reduza! Limite ${e.limiteKmh}"
    }
}
