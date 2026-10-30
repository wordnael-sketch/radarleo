package br.radarleo.sync

import br.radarleo.data.BaseJson
import br.radarleo.data.Versao
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class SincronizadorBaseTest {

    private fun json(versao: String, total: Int = 1, radares: Int = total) = buildString {
        append("""{"versao": "$versao", "gerado_em": "2026-11-02T06:00:00-03:00", "total": $total, "radares": [""")
        append((1..radares).joinToString(",") {
            """{"id": "r$it", "latitude": -15.8, "longitude": -47.9, "tipo": "FIXO", "fonte": "OSM", "confiabilidade": 60, "atualizado_em": "2026-11-02"}"""
        })
        append("]}")
    }.toByteArray()

    /** Banco de mentira: guarda a versão e conta quantas vezes instalou. */
    private class BancoFalso(var versao: String?) {
        var instalacoes = 0
        suspend fun instalar(base: BaseJson): Boolean {
            if (!Versao.ehMaisNova(base.versao, versao)) return false
            versao = base.versao
            instalacoes++
            return true
        }
    }

    private fun sincronizador(banco: BancoFalso, baixar: (String) -> ByteArray) =
        SincronizadorBase("https://exemplo/radares.json", baixar, { banco.versao }, banco::instalar)

    @Test
    fun `versao nova e instalada`() = runBlocking {
        val banco = BancoFalso("20261030-aaa")
        val r = sincronizador(banco) { json("20261102-bbb", total = 3) }.sincronizar()
        assertEquals(ResultadoSync.Atualizada("20261102-bbb", 3), r)
        assertEquals(1, banco.instalacoes)
    }

    @Test
    fun `mesma versao nao reinstala`() = runBlocking {
        val banco = BancoFalso("20261030-aaa")
        val r = sincronizador(banco) { json("20261030-aaa") }.sincronizar()
        assertEquals(ResultadoSync.JaAtualizada("20261030-aaa"), r)
        assertEquals(0, banco.instalacoes)
    }

    @Test
    fun `sem rede e falha temporaria`() = runBlocking {
        val banco = BancoFalso("20261030-aaa")
        val r = sincronizador(banco) { throw IOException("HTTP 404") }.sincronizar()
        assertTrue(r is ResultadoSync.Falha && r.temporaria)
        assertEquals(0, banco.instalacoes)
    }

    @Test
    fun `arquivo que nao e base e recusado`() = runBlocking {
        val banco = BancoFalso(null)
        val r = sincronizador(banco) { "<html>erro</html>".toByteArray() }.sincronizar()
        assertTrue(r is ResultadoSync.Falha && !r.temporaria)
        assertEquals(0, banco.instalacoes)
    }

    @Test
    fun `base truncada e recusada`() = runBlocking {
        val banco = BancoFalso("20261030-aaa")
        val cortado = json("20261102-bbb", total = 3).let { it.copyOf(it.size - 40) }
        val r = sincronizador(banco) { cortado }.sincronizar()
        assertTrue(r is ResultadoSync.Falha && !r.temporaria)
        assertEquals(0, banco.instalacoes)
    }

    @Test
    fun `total diferente da lista e recusado`() = runBlocking {
        val banco = BancoFalso("20261030-aaa")
        val r = sincronizador(banco) { json("20261102-bbb", total = 1889, radares = 2) }.sincronizar()
        assertTrue(r is ResultadoSync.Falha && "incompleta" in (r as ResultadoSync.Falha).motivo)
        assertEquals(0, banco.instalacoes)
    }

    @Test
    fun `mensagens para a tela`() {
        assertEquals("Base atualizada: 1889 radares",
            SincronizadorBase.mensagem(ResultadoSync.Atualizada("v", 1889)))
        assertEquals("Não foi possível atualizar: x",
            SincronizadorBase.mensagem(ResultadoSync.Falha("x", true)))
    }
}
