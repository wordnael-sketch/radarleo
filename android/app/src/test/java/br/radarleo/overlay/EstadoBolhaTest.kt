package br.radarleo.overlay

import br.radarleo.alert.ConfigAlertas
import br.radarleo.alert.Movimento
import br.radarleo.alert.RadarAFrente
import br.radarleo.data.Radar
import br.radarleo.data.TipoRadar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EstadoBolhaTest {

    private fun aFrente(dist: Double, limite: Int? = 60, conf: Int = 85, tipo: String = "LOMBADA") = RadarAFrente(
        Radar("r", -15.8, -47.9, tipo, "OSM", limite, null, null, null, conf, "2026-10-30", "OSM"), dist,
    )

    private fun estado(a: RadarAFrente?, kmh: Double?) = EstadoBolha.de(a, Movimento(kmh, 0.0), ConfigAlertas())

    @Test
    fun `some sem radar a frente`() {
        assertNull(estado(null, 80.0))
    }

    @Test
    fun `mostra tipo, distancia, limite e velocidade`() {
        val e = estado(aFrente(327.9), 54.4)!!
        assertEquals(TipoRadar.LOMBADA, e.tipo)
        assertEquals("320 m", e.distanciaTexto)
        assertEquals(60, e.limiteKmh)
        assertEquals("54", e.velocidadeTexto)
        assertEquals(NivelVelocidade.NORMAL, e.nivel)
        assertFalse(e.possivel)
    }

    @Test
    fun `cor da velocidade`() {
        assertEquals(NivelVelocidade.NORMAL, estado(aFrente(200.0), 60.0)!!.nivel)
        assertEquals(NivelVelocidade.ACIMA, estado(aFrente(200.0), 64.0)!!.nivel)   // acima de 60, dentro de 67
        assertEquals(NivelVelocidade.EXCESSO, estado(aFrente(200.0), 68.0)!!.nivel)
        assertEquals(NivelVelocidade.NORMAL, estado(aFrente(200.0, limite = null), 150.0)!!.nivel)
    }

    @Test
    fun `possivel radar e sem velocidade`() {
        val e = estado(aFrente(90.0, conf = 50), null)!!
        assertTrue(e.possivel)
        assertEquals("–", e.velocidadeTexto)
        assertEquals("90 m", e.distanciaTexto)
    }

    @Test
    fun `distancia regressiva`() {
        assertEquals("0 m", EstadoBolha.textoDistancia(7.0))
        assertEquals("490 m", EstadoBolha.textoDistancia(499.9))
        assertEquals("1,2 km", EstadoBolha.textoDistancia(1_234.0))
    }
}
