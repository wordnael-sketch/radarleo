package br.radarleo.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.time.LocalDate
import java.util.UUID

class RadarRepositorio(
    private val dao: RadarDao,
    private val abrirBaseEmbutida: () -> InputStream, // assets/radares.json
) {
    val baseInfo: Flow<BaseInfo?> = dao.baseInfoFlow()
    val total: Flow<Int> = dao.totalFlow()
    val totalDoUsuario: Flow<Int> = dao.totalDoUsuarioFlow()

    // Base embutida e sincronização podem rodar ao mesmo tempo na primeira abertura;
    // sem a trava, a embutida (mais velha) poderia sobrescrever a recém-baixada.
    private val trava = Mutex()

    suspend fun radaresPerto(lat: Double, lon: Double, raioM: Double): List<Radar> =
        dao.naCaixa(Caixa.emVoltaDe(lat, lon, raioM))

    suspend fun radaresNaCaixa(c: Caixa, limite: Int): List<Radar> =
        dao.naCaixaAte(c.latMin, c.latMax, c.lonMin, c.lonMax, limite)

    suspend fun versaoInstalada(): String? = dao.baseInfo()?.versao

    /**
     * "Reportar radar aqui": guarda a posição e o rumo atuais (rumo null = os dois sentidos).
     * Fonte USUARIO: a sincronização nunca apaga, e o motor já passa a avisar dele.
     */
    suspend fun reportar(lat: Double, lon: Double, tipo: TipoRadar, rumo: Double?, limiteKmh: Int?): Radar {
        val r = Radar(
            id = "usr-" + UUID.randomUUID().toString().take(12),
            latitude = lat, longitude = lon, tipo = tipo.name, fonte = Fonte.USUARIO.name,
            limiteKmh = limiteKmh, direcaoGraus = rumo?.let { Math.round(it * 10) / 10.0 },
            rodovia = null, km = null, confiabilidade = CONFIABILIDADE_USUARIO,
            atualizadoEm = LocalDate.now().toString(), fontes = Fonte.USUARIO.name,
        )
        withContext(Dispatchers.IO) { dao.inserirDoUsuario(r) }
        return r
    }

    suspend fun csvDosReportes(): String = withContext(Dispatchers.IO) { ExportadorCsv.gerar(dao.doUsuario()) }

    /**
     * Na primeira abertura (ou depois de uma atualização do app com base mais nova
     * embutida), instala a base que vem no APK. Funciona offline.
     * Devolve true se instalou.
     */
    suspend fun garantirBaseEmbutida(): Boolean = withContext(Dispatchers.IO) {
        // InputStream.readNBytes só existe a partir da API 33; se a leitura vier curta e a
        // versão não aparecer, cai no caminho seguro de decodificar o arquivo.
        val inicio = abrirBaseEmbutida().use {
            val buf = ByteArray(256)
            String(buf, 0, it.read(buf).coerceAtLeast(0), Charsets.UTF_8)
        }
        val versaoEmbutida = BaseJsonLeitor.versaoNoInicio(inicio)
        if (versaoEmbutida != null && !Versao.ehMaisNova(versaoEmbutida, versaoInstalada())) {
            return@withContext false
        }
        instalarSeMaisNova(abrirBaseEmbutida().use { BaseJsonLeitor.ler(it) })
    }

    /**
     * Substitui a base (numa transação, mantendo os radares do usuário) só se a versão
     * for mais nova que a instalada. Devolve true se instalou.
     */
    suspend fun instalarSeMaisNova(base: BaseJson): Boolean = withContext(Dispatchers.IO) {
        trava.withLock {
            if (!Versao.ehMaisNova(base.versao, versaoInstalada())) return@withLock false
            dao.substituirBase(
                radares = base.radares.map { it.paraEntidade() },
                info = BaseInfo(
                    versao = base.versao, geradoEm = base.geradoEm, total = base.total,
                    instaladaEm = System.currentTimeMillis(),
                ),
            )
            true
        }
    }

    private companion object {
        const val CONFIABILIDADE_USUARIO = 90 // igual ao MANUAL do ETL: foi você que viu
    }
}
