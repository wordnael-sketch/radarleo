package br.radarleo.alert

import br.radarleo.data.Radar
import br.radarleo.location.Posicao
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos

class MotorAlertasTest {

    private val lat0 = -15.8
    private val lon0 = -47.9
    private val mLat = 1 / 111_195.08
    private val mLon = 1 / (111_195.08 * cos(Math.toRadians(lat0)))

    private fun radar(
        id: String = "r1", norteM: Double = 0.0, lesteM: Double = 0.0, direcao: Double? = null,
        limite: Int? = 60, confiabilidade: Int = 85, tipo: String = "FIXO",
    ) = Radar(
        id = id, latitude = lat0 + norteM * mLat, longitude = lon0 + lesteM * mLon, tipo = tipo, fonte = "OSM",
        limiteKmh = limite, direcaoGraus = direcao, rodovia = null, km = null,
        confiabilidade = confiabilidade, atualizadoEm = "2026-10-30", fontes = "OSM",
    )

    /**
     * Carro andando em linha reta pelo eixo norte-sul que passa pelo ponto zero.
     * rumo 0 = indo para o norte (de sul para norte). Devolve os eventos de cada leitura.
     */
    private fun dirigir(
        motor: MotorAlertas, radares: List<Radar>, deM: Int = -700, ateM: Int = 200, passoM: Int = 20,
        kmh: Double = 72.0, rumo: Double = 0.0, inicioMs: Long = 0,
    ): List<Pair<Int, Evento>> {
        val sinal = if (rumo == 180.0) -1 else 1
        val saida = mutableListOf<Pair<Int, Evento>>()
        var t = inicioMs
        for (pos in deM..ateM step passoM) {
            val norte = pos * sinal.toDouble()
            val r = motor.processar(lat0 + norte * mLat, lon0, Movimento(kmh, rumo), t, radares)
            r.eventos.forEach { saida += pos to it }
            t += 1_000
        }
        return saida
    }

    private fun faixas(eventos: List<Pair<Int, Evento>>) = eventos.map { it.second }.filterIsInstance<Aviso>().map { it.faixaM }

    @Test
    fun `avisa a 500, 300 e 100 m e nao repete depois de passar`() {
        val ev = dirigir(MotorAlertas(), listOf(radar()))
        assertEquals(listOf(500, 300, 100), faixas(ev))
        assertTrue(ev.all { it.first <= 0 }) // nada depois de passar pelo radar
    }

    @Test
    fun `radar atras nao avisa`() {
        val ev = dirigir(MotorAlertas(), listOf(radar(norteM = -800.0)), deM = -700, ateM = 0)
        assertTrue(ev.isEmpty())
    }

    @Test
    fun `radar fora do cone de 30 graus nao avisa`() {
        // 200 m a leste: visto de 300 m antes, está a ~34° do rumo
        val ev = dirigir(MotorAlertas(), listOf(radar(lesteM = 200.0)), deM = -300, ateM = -300)
        assertTrue(ev.isEmpty())
    }

    @Test
    fun `radar da pista contraria nao avisa`() {
        // fiscaliza quem vai para o sul (180°); o carro vai para o norte
        val ev = dirigir(MotorAlertas(), listOf(radar(direcao = 180.0, lesteM = -15.0)))
        assertTrue(ev.isEmpty())
    }

    @Test
    fun `tolerancia de 45 graus na direcao do radar`() {
        assertEquals(listOf(500, 300, 100), faixas(dirigir(MotorAlertas(), listOf(radar(direcao = 40.0)))))
        assertTrue(dirigir(MotorAlertas(), listOf(radar(direcao = 50.0))).isEmpty())
        assertEquals(3, faixas(dirigir(MotorAlertas(), listOf(radar(direcao = 330.0)))).size) // atravessa o norte
    }

    @Test
    fun `abaixo de 10 km por hora nao avisa`() {
        assertTrue(dirigir(MotorAlertas(), listOf(radar()), kmh = 9.0).isEmpty())
    }

    @Test
    fun `quem aparece ja a 250 m ouve so a faixa de 300`() {
        val ev = dirigir(MotorAlertas(), listOf(radar()), deM = -250, ateM = -150)
        assertEquals(listOf(300), faixas(ev))
    }

    @Test
    fun `excesso insiste a cada 4 s e respeita a tolerancia`() {
        // 80 km/h com limite 60 (máximo 67): excesso
        val ev = dirigir(MotorAlertas(), listOf(radar()), deM = -490, ateM = -400, passoM = 10, kmh = 80.0)
        val instantes = ev.filter { it.second is Excesso }.map { it.first }
        assertEquals(listOf(-490, -450, -410), instantes) // 10 m por leitura de 1 s → a cada 40 m
        // 66 km/h está dentro da tolerância
        assertTrue(dirigir(MotorAlertas(), listOf(radar()), kmh = 66.0).none { it.second is Excesso })
    }

    @Test
    fun `excesso so do radar mais proximo`() {
        val perto = radar(id = "perto", limite = 80)
        val longe = radar(id = "longe", norteM = 300.0, limite = 60)
        val ev = dirigir(MotorAlertas(), listOf(perto, longe), deM = -200, ateM = -100, passoM = 50, kmh = 100.0)
        assertEquals(setOf("perto"), ev.map { it.second }.filterIsInstance<Excesso>().map { it.radar.id }.toSet())
    }

    @Test
    fun `tolerancia do Inmetro`() {
        val c = ConfigAlertas()
        assertEquals(67.0, c.velocidadeMaxima(60), 1e-9)
        assertEquals(107.0, c.velocidadeMaxima(100), 1e-9)
        assertEquals(128.4, c.velocidadeMaxima(120), 1e-9)
        assertEquals(66.0, ConfigAlertas(toleranciaPct = 10).velocidadeMaxima(60), 1e-9)
        assertEquals(60.0, ConfigAlertas(toleranciaPct = 0).velocidadeMaxima(60), 1e-9)
    }

    @Test
    fun `possivel radar avisa uma vez so e de leve`() {
        val ev = dirigir(MotorAlertas(), listOf(radar(confiabilidade = 50)), kmh = 90.0)
        val avisos = ev.map { it.second }.filterIsInstance<Aviso>()
        assertEquals(1, avisos.size)
        assertTrue(avisos[0].discreto && avisos[0].faixaM == 300)
        assertEquals(1, ev.count { it.second is Excesso })
        assertEquals("Possível radar a 300 metros", Frases.de(avisos[0]))
    }

    @Test
    fun `numa nova passagem avisa de novo`() {
        val motor = MotorAlertas()
        val r = radar()
        dirigir(motor, listOf(r))
        motor.processar(lat0 + 5_000 * mLat, lon0, Movimento(72.0, 0.0), 0, emptyList()) // longe: saiu do raio
        assertEquals(listOf(500, 300, 100), faixas(dirigir(motor, listOf(r))))
    }

    @Test
    fun `retorno logo depois do radar avisa de novo se ele nao tiver direcao`() {
        val motor = MotorAlertas()
        val r = radar()
        assertEquals(3, faixas(dirigir(motor, listOf(r))).size)
        // faz o retorno 200 m depois do radar e volta para o sul pela mesma via
        assertEquals(listOf(300, 100), faixas(dirigir(motor, listOf(r), deM = -200, ateM = 200, rumo = 180.0)))
        // radar com direção (para o norte) não avisa na volta
        val comDirecao = radar(id = "r2", direcao = 0.0)
        val m2 = MotorAlertas()
        dirigir(m2, listOf(comDirecao))
        assertTrue(dirigir(m2, listOf(comDirecao), deM = -200, ateM = 200, rumo = 180.0).isEmpty())
    }

    @Test
    fun `informa o radar mais proximo a frente`() {
        val motor = MotorAlertas()
        val perto = radar(id = "perto", norteM = 200.0)
        val longe = radar(id = "longe", norteM = 450.0)
        val r = motor.processar(lat0, lon0, Movimento(50.0, 0.0), 0, listOf(longe, perto))
        assertEquals("perto", r.aFrente!!.radar.id)
        // sem nenhum rumo conhecido ainda, não há "à frente"
        assertNull(MotorAlertas().processar(lat0, lon0, Movimento(0.0, null), 0, listOf(perto)).aFrente)
    }

    @Test
    fun `parado no semaforo fica calado mas continua mostrando o radar`() {
        val motor = MotorAlertas()
        val r = radar()
        assertEquals(listOf(500, 300), faixas(dirigir(motor, listOf(r), deM = -600, ateM = -150, passoM = 50)))
        // para a 150 m do radar: GPS sem rumo, velocidade 0
        val parado = motor.processar(lat0 - 150 * mLat, lon0, Movimento(0.0, null), 20_000, listOf(r))
        assertTrue(parado.eventos.isEmpty())
        assertEquals("r1", parado.aFrente!!.radar.id)
        // volta a andar: aviso de 100 m sai normalmente
        assertEquals(listOf(100), faixas(dirigir(motor, listOf(r), deM = -100, ateM = -50, passoM = 50, inicioMs = 30_000)))
    }

    @Test
    fun `frases`() {
        val a = Aviso(radar(tipo = "LOMBADA", limite = 40), 280.0, 300, false)
        assertEquals("Lombada eletrônica a 300 metros, limite 40", Frases.de(a))
        assertEquals("Radar a 500 metros", Frases.de(Aviso(radar(limite = null), 480.0, 500, false)))
        assertEquals("Reduza! Limite 60", Frases.de(Excesso(radar(), 200.0, 80.0, 60, false)))
    }

    // --- estimador

    private fun pos(norteM: Double, t: Long, kmh: Double? = null, rumo: Double? = null) =
        Posicao(lat0 + norteM * mLat, lon0, kmh, rumo, 5f, t)

    @Test
    fun `estimador calcula velocidade e rumo quando o GPS nao informa`() {
        val e = EstimadorMovimento()
        e.estimar(pos(0.0, 0))
        e.estimar(pos(20.0, 1_000))
        val m = e.estimar(pos(40.0, 2_000))
        assertEquals(72.0, m.velocidadeKmh!!, 0.5)
        assertEquals(0.0, m.rumoGraus!!, 0.5)
    }

    @Test
    fun `estimador troca velocidade do GPS incoerente pela calculada`() {
        val e = EstimadorMovimento()
        // GPS travado em ~10 km/h com o carro andando 20 m por segundo
        val v = (0..5).map { i -> e.estimar(pos(i * 20.0, i * 1_000L, kmh = 10.0, rumo = 0.0)).velocidadeKmh!! }
        assertEquals(72.0, v.last(), 0.5)
    }

    @Test
    fun `estimador ignora pico isolado de velocidade`() {
        val e = EstimadorMovimento()
        // carro a 54 km/h (15 m/s); o GPS do emulador mandou um pico de 71 no meio
        val gps = listOf(54.0, 54.0, 71.0, 54.0, 54.0, 0.0, 54.0)
        val v = gps.mapIndexed { i, k -> e.estimar(pos(i * 15.0, i * 1_000L, kmh = k, rumo = 0.0)).velocidadeKmh!! }
        assertTrue(v.drop(2).all { it in 50.0..58.0 })
    }

    @Test
    fun `estimador acompanha excesso de verdade`() {
        val e = EstimadorMovimento()
        val gps = listOf(54.0, 54.0, 80.0, 80.0, 80.0)
        val v = gps.mapIndexed { i, k -> e.estimar(pos(i * 18.0, i * 1_000L, kmh = k, rumo = 0.0)).velocidadeKmh!! }
        assertEquals(80.0, v[3], 1e-9) // 2 leituras seguidas acima: a mediana já sobe
    }

    @Test
    fun `estimador confia no GPS quando ele e coerente`() {
        val e = EstimadorMovimento()
        e.estimar(pos(0.0, 0, kmh = 70.0))
        e.estimar(pos(20.0, 1_000, kmh = 70.0))
        assertEquals(70.0, e.estimar(pos(40.0, 2_000, kmh = 70.0)).velocidadeKmh!!, 1e-9)
    }

    @Test
    fun `estimador ignora salto do GPS`() {
        val e = EstimadorMovimento()
        e.estimar(pos(0.0, 0))
        e.estimar(pos(20.0, 1_000))
        // 12 km em 1 s: o GPS recuperou o sinal longe dali
        val m = e.estimar(pos(12_000.0, 2_000))
        assertNull(m.velocidadeKmh)
        // a partir daí volta a calcular normalmente
        e.estimar(pos(12_020.0, 3_000))
        assertEquals(72.0, e.estimar(pos(12_040.0, 4_000)).velocidadeKmh!!, 0.5)
    }

    @Test
    fun `estimador parado nao inventa rumo`() {
        val e = EstimadorMovimento()
        e.estimar(pos(0.0, 0))
        e.estimar(pos(1.0, 1_000))
        val m = e.estimar(pos(2.0, 2_000))
        assertNull(m.rumoGraus)
        assertNotNull(m.velocidadeKmh)
    }
}
