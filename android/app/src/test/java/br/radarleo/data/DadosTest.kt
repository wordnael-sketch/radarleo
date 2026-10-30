package br.radarleo.data

import br.radarleo.ui.rotuloBase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DadosTest {

    // Trecho no formato real de output/radares.json (um radar por linha, campo extra ignorado).
    private val amostra = """
        {"versao": "20261030-2e13fff0aa", "gerado_em": "2026-10-30T12:02:57-03:00", "total": 2, "radares": [
        {"id": "392eae735abef338", "latitude": -16.025451, "longitude": -47.9796488, "tipo": "FIXO", "fonte": "OSM", "limite_kmh": 60, "direcao_graus": 168.0, "rodovia": "BR-040", "km": 5.6, "confiabilidade": 60, "atualizado_em": "2026-10-30", "fontes": ["OSM", "DNIT"]},
        {"id": "002cda06a5448394", "latitude": -14.808177, "longitude": -39.296566, "tipo": "FIXO", "fonte": "DNIT", "limite_kmh": null, "direcao_graus": null, "rodovia": "BR-101", "km": 506.88, "confiabilidade": 50, "atualizado_em": "2026-10-30", "fontes": ["DNIT"], "campo_novo": 1}
        ]}
    """.trimIndent()

    @Test
    fun `le o json do ETL`() {
        val base = BaseJsonLeitor.ler(amostra.byteInputStream())
        assertEquals("20261030-2e13fff0aa", base.versao)
        assertEquals(2, base.radares.size)
        val r = base.radares[0].paraEntidade()
        assertEquals(60, r.limiteKmh)
        assertEquals(168.0, r.direcaoGraus!!, 0.0)
        assertEquals("OSM,DNIT", r.fontes)
        val semDirecao = base.radares[1].paraEntidade()
        assertNull(semDirecao.direcaoGraus)
        assertNull(semDirecao.limiteKmh)
    }

    @Test
    fun `le a versao sem decodificar o arquivo inteiro`() {
        assertEquals("20261030-2e13fff0aa", BaseJsonLeitor.versaoNoInicio(amostra.take(256)))
        assertNull(BaseJsonLeitor.versaoNoInicio("{\"total\": 3"))
    }

    @Test
    fun `compara versoes`() {
        assertTrue(Versao.ehMaisNova("20261030-aaa", null))
        assertFalse(Versao.ehMaisNova("20261030-aaa", "20261030-aaa"))
        assertTrue(Versao.ehMaisNova("20261106-bbb", "20261030-aaa"))
        assertTrue(Versao.ehMaisNova("20261030-bbb", "20261030-aaa")) // mesmo dia, conteúdo novo
        assertFalse(Versao.ehMaisNova("20261023-ccc", "20261030-aaa")) // embutida mais velha
    }

    @Test
    fun `caixa de 500 m`() {
        val lat = -15.8
        val lon = -47.9
        val c = Caixa.emVoltaDe(lat, lon, 500.0)
        val grau = 111_195.08
        assertTrue(c.contem(lat + 499 / grau, lon))
        assertFalse(c.contem(lat + 501 / grau, lon))
        // em -15,8° um grau de longitude tem ~107 km: a caixa é mais larga em graus
        val lonPor500m = 500 / (grau * Math.cos(Math.toRadians(lat)))
        assertTrue(c.contem(lat, lon + lonPor500m * 0.99))
        assertFalse(c.contem(lat, lon + lonPor500m * 1.01))
    }

    @Test
    fun `rotulo da base`() {
        val info = BaseInfo(versao = "x", geradoEm = "2026-10-30T12:02:57-03:00", total = 1889, instaladaEm = 0)
        assertEquals("Base atualizada em 30/10 — 1889 radares", rotuloBase(info, 1889))
        assertNull(rotuloBase(null, 0))
    }
}
