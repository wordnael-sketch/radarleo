package br.radarleo.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
abstract class RadarDao {

    /** Só os radares dentro da caixa: nunca varre a base inteira. */
    @Query(
        """SELECT * FROM radares
           WHERE latitude BETWEEN :latMin AND :latMax
             AND longitude BETWEEN :lonMin AND :lonMax"""
    )
    abstract suspend fun naCaixa(latMin: Double, latMax: Double, lonMin: Double, lonMax: Double): List<Radar>

    suspend fun naCaixa(c: Caixa) = naCaixa(c.latMin, c.latMax, c.lonMin, c.lonMax)

    /** Para o mapa: os mais confiáveis primeiro, com teto para não travar o desenho. */
    @Query(
        """SELECT * FROM radares
           WHERE latitude BETWEEN :latMin AND :latMax
             AND longitude BETWEEN :lonMin AND :lonMax
           ORDER BY confiabilidade DESC
           LIMIT :limite"""
    )
    abstract suspend fun naCaixaAte(
        latMin: Double, latMax: Double, lonMin: Double, lonMax: Double, limite: Int,
    ): List<Radar>

    @Query("SELECT COUNT(*) FROM radares")
    abstract fun totalFlow(): Flow<Int>

    @Query("SELECT COUNT(*) FROM radares WHERE fonte = 'USUARIO'")
    abstract fun totalDoUsuarioFlow(): Flow<Int>

    @Query("SELECT * FROM radares WHERE fonte = 'USUARIO'")
    abstract suspend fun doUsuario(): List<Radar>

    @Query("DELETE FROM radares WHERE fonte != 'USUARIO'")
    abstract suspend fun apagarMenosUsuario()

    /** IGNORE: se um radar do usuário tiver o mesmo id de um da base, fica o do usuário. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun inserirDaBase(radares: List<Radar>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun inserirDoUsuario(radar: Radar)

    @Query("SELECT * FROM base_info WHERE id = 0")
    abstract suspend fun baseInfo(): BaseInfo?

    @Query("SELECT * FROM base_info WHERE id = 0")
    abstract fun baseInfoFlow(): Flow<BaseInfo?>

    @Upsert
    abstract suspend fun gravarBaseInfo(info: BaseInfo)

    /** Troca a base numa transação só: ou entra a nova inteira, ou fica a antiga. */
    @Transaction
    open suspend fun substituirBase(radares: List<Radar>, info: BaseInfo) {
        apagarMenosUsuario()
        inserirDaBase(radares)
        gravarBaseInfo(info)
    }
}
