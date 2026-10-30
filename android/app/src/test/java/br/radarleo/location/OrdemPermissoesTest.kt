package br.radarleo.location

import br.radarleo.location.Etapa.LOCALIZACAO_PRECISA
import br.radarleo.location.Etapa.LOCALIZACAO_SEGUNDO_PLANO
import br.radarleo.location.Etapa.NOTIFICACOES
import br.radarleo.location.Etapa.SOBREPOSICAO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OrdemPermissoesTest {

    private fun proxima(sdk: Int, concedidas: Set<Etapa> = emptySet(), puladas: Set<Etapa> = emptySet()) =
        OrdemPermissoes.proxima(sdk, concedidas, puladas)

    /** Simula o usuário aceitando tudo, na ordem que o app pede. */
    private fun sequencia(sdk: Int): List<Etapa> {
        val concedidas = mutableSetOf<Etapa>()
        val ordem = mutableListOf<Etapa>()
        while (true) {
            val e = proxima(sdk, concedidas) ?: return ordem
            ordem += e
            concedidas += e
        }
    }

    @Test
    fun `ordem no Android 14`() {
        assertEquals(listOf(LOCALIZACAO_PRECISA, LOCALIZACAO_SEGUNDO_PLANO, NOTIFICACOES, SOBREPOSICAO), sequencia(34))
    }

    @Test
    fun `Android 12 nao pede notificacoes`() {
        assertEquals(listOf(LOCALIZACAO_PRECISA, LOCALIZACAO_SEGUNDO_PLANO, SOBREPOSICAO), sequencia(31))
    }

    @Test
    fun `Android 8 nao tem segundo plano separado nem notificacoes`() {
        assertEquals(listOf(LOCALIZACAO_PRECISA, SOBREPOSICAO), sequencia(26))
    }

    @Test
    fun `localizacao precisa nao pode ser pulada`() {
        assertEquals(LOCALIZACAO_PRECISA, proxima(34, puladas = setOf(LOCALIZACAO_PRECISA)))
    }

    @Test
    fun `segundo plano so depois da precisa, mesmo ja concedido`() {
        assertEquals(LOCALIZACAO_PRECISA, proxima(34, concedidas = setOf(LOCALIZACAO_SEGUNDO_PLANO)))
    }

    @Test
    fun `opcionais pulados nao voltam`() {
        val puladas = setOf(LOCALIZACAO_SEGUNDO_PLANO, NOTIFICACOES, SOBREPOSICAO)
        assertNull(proxima(34, concedidas = setOf(LOCALIZACAO_PRECISA), puladas = puladas))
    }

    @Test
    fun `velocidade em km por hora`() {
        assertEquals(36.0, Posicao.msParaKmh(10f), 1e-6)
    }
}
