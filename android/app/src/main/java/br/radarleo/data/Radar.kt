package br.radarleo.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Espelho do modelo do ETL (etl/modelo.py). tipo e fonte ficam como texto para que
 * um valor novo vindo do ETL não quebre o app; os enums abaixo servem à interface.
 */
@Entity(
    tableName = "radares",
    indices = [Index(value = ["latitude", "longitude"]), Index(value = ["fonte"])],
)
data class Radar(
    @PrimaryKey val id: String,
    val latitude: Double,
    val longitude: Double,
    val tipo: String,
    val fonte: String,
    @ColumnInfo(name = "limite_kmh") val limiteKmh: Int?,
    @ColumnInfo(name = "direcao_graus") val direcaoGraus: Double?, // null = os dois sentidos
    val rodovia: String?,
    val km: Double?,
    val confiabilidade: Int,
    @ColumnInfo(name = "atualizado_em") val atualizadoEm: String,
    val fontes: String, // fontes que confirmaram o radar, separadas por vírgula
)

/** Uma linha só (id = 0): qual base está instalada. */
@Entity(tableName = "base_info")
data class BaseInfo(
    @PrimaryKey val id: Int = 0,
    val versao: String,
    @ColumnInfo(name = "gerado_em") val geradoEm: String,
    val total: Int,
    @ColumnInfo(name = "instalada_em") val instaladaEm: Long,
)

enum class TipoRadar { FIXO, SEMAFORO, LOMBADA, VELOCIDADE_MEDIA, DESCONHECIDO;
    companion object {
        fun de(texto: String) = entries.firstOrNull { it.name == texto } ?: DESCONHECIDO
    }
}

/** USUARIO = radar reportado no próprio app; nunca é apagado pela sincronização. */
enum class Fonte { OSM, ANTT, DNIT, LAI_DETRAN_DF, LAI_DER_DF, MANUAL, USUARIO }
