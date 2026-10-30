package br.radarleo.sync

import br.radarleo.data.BaseJson
import br.radarleo.data.BaseJsonLeitor
import br.radarleo.data.Versao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

sealed interface ResultadoSync {
    data class Atualizada(val versao: String, val total: Int) : ResultadoSync
    data class JaAtualizada(val versao: String) : ResultadoSync
    /** temporaria = vale tentar de novo depois (rede); senão o arquivo veio ruim. */
    data class Falha(val motivo: String, val temporaria: Boolean) : ResultadoSync
}

/**
 * Baixa o radares.json publicado pelo ETL e troca a base se a versão for nova.
 * As dependências entram como funções para dar para testar sem rede nem banco.
 */
class SincronizadorBase(
    private val url: String,
    private val baixar: (String) -> ByteArray,
    private val versaoInstalada: suspend () -> String?,
    private val instalarSeMaisNova: suspend (BaseJson) -> Boolean,
) {
    suspend fun sincronizar(): ResultadoSync {
        val bytes = try {
            withContext(Dispatchers.IO) { baixar(url) }
        } catch (e: IOException) {
            return ResultadoSync.Falha("sem resposta do servidor da base (${e.message})", temporaria = true)
        }

        // A versão vem no começo do arquivo: se for a mesma, nem decodifica o resto.
        val versao = BaseJsonLeitor.versaoNoInicio(String(bytes, 0, minOf(256, bytes.size), Charsets.UTF_8))
            ?: return ResultadoSync.Falha("o arquivo baixado não é uma base do RadarLeo", temporaria = false)
        val atual = versaoInstalada()
        if (!Versao.ehMaisNova(versao, atual)) return ResultadoSync.JaAtualizada(atual ?: versao)

        val base = try {
            BaseJsonLeitor.ler(bytes.inputStream())
        } catch (e: Exception) { // SerializationException, IllegalArgumentException
            return ResultadoSync.Falha("a base baixada está corrompida", temporaria = false)
        }
        if (base.radares.isEmpty() || base.total != base.radares.size) {
            return ResultadoSync.Falha(
                "a base baixada está incompleta (${base.radares.size} de ${base.total} radares)",
                temporaria = false,
            )
        }
        return if (instalarSeMaisNova(base)) {
            ResultadoSync.Atualizada(base.versao, base.total)
        } else {
            ResultadoSync.JaAtualizada(versaoInstalada() ?: base.versao)
        }
    }

    companion object {
        /** GET simples; o HttpURLConnection já pede e descompacta gzip sozinho. */
        fun baixarHttp(url: String, agente: String): ByteArray {
            val conexao = URL(url).openConnection() as HttpURLConnection
            conexao.connectTimeout = 15_000
            conexao.readTimeout = 30_000
            conexao.setRequestProperty("User-Agent", agente)
            try {
                if (conexao.responseCode != HttpURLConnection.HTTP_OK) {
                    throw IOException("HTTP ${conexao.responseCode}")
                }
                return conexao.inputStream.use { it.readBytes() }
            } finally {
                conexao.disconnect()
            }
        }

        fun mensagem(r: ResultadoSync): String = when (r) {
            is ResultadoSync.Atualizada -> "Base atualizada: ${r.total} radares"
            is ResultadoSync.JaAtualizada -> "A base já está na versão mais recente"
            is ResultadoSync.Falha -> "Não foi possível atualizar: ${r.motivo}"
        }
    }
}
