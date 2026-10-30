package br.radarleo.data

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import java.io.InputStream

/** Formato de output/radares.json gerado pelo ETL (Passo 7). */
@Serializable
data class BaseJson(
    val versao: String,
    @SerialName("gerado_em") val geradoEm: String,
    val total: Int,
    val radares: List<RadarJson>,
)

@Serializable
data class RadarJson(
    val id: String,
    val latitude: Double,
    val longitude: Double,
    val tipo: String,
    val fonte: String,
    @SerialName("limite_kmh") val limiteKmh: Int? = null,
    @SerialName("direcao_graus") val direcaoGraus: Double? = null,
    val rodovia: String? = null,
    val km: Double? = null,
    val confiabilidade: Int,
    @SerialName("atualizado_em") val atualizadoEm: String,
    val fontes: List<String> = emptyList(),
) {
    fun paraEntidade() = Radar(
        id = id, latitude = latitude, longitude = longitude, tipo = tipo, fonte = fonte,
        limiteKmh = limiteKmh, direcaoGraus = direcaoGraus, rodovia = rodovia, km = km,
        confiabilidade = confiabilidade, atualizadoEm = atualizadoEm,
        fontes = fontes.ifEmpty { listOf(fonte) }.joinToString(","),
    )
}

object BaseJsonLeitor {
    private val json = Json { ignoreUnknownKeys = true } // campos novos do ETL não quebram o app

    @OptIn(ExperimentalSerializationApi::class)
    fun ler(entrada: InputStream): BaseJson = json.decodeFromStream(entrada)

    private val reVersao = Regex(""""versao"\s*:\s*"([^"]+)"""")

    /**
     * Lê só a versão no começo do arquivo (o ETL grava "versao" primeiro), para não
     * precisar decodificar o JSON inteiro quando a base instalada já é a mesma.
     */
    fun versaoNoInicio(inicio: String): String? = reVersao.find(inicio)?.groupValues?.get(1)
}

object Versao {
    /**
     * Versões são "AAAAMMDD-hash". A nova vale se for diferente e não for de data anterior
     * (no mesmo dia, hash diferente = conteúdo novo).
     */
    fun ehMaisNova(nova: String, atual: String?): Boolean {
        if (atual == null) return true
        if (nova == atual) return false
        return nova.substringBefore("-") >= atual.substringBefore("-")
    }
}
