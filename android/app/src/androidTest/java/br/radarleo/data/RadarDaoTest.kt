package br.radarleo.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RadarDaoTest {
    private lateinit var db: RadarDb
    private lateinit var dao: RadarDao

    private fun radar(id: String, lat: Double, lon: Double, fonte: String = "OSM") = Radar(
        id = id, latitude = lat, longitude = lon, tipo = "FIXO", fonte = fonte,
        limiteKmh = 60, direcaoGraus = null, rodovia = null, km = null,
        confiabilidade = 60, atualizadoEm = "2026-10-30", fontes = fonte,
    )

    private fun info(versao: String) = BaseInfo(versao = versao, geradoEm = "2026-10-30T12:00:00-03:00", total = 0, instaladaEm = 0)

    @Before
    fun abrir() {
        db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext, RadarDb::class.java,
        ).build()
        dao = db.radarDao()
    }

    @After
    fun fechar() = db.close()

    @Test
    fun consultaPorCaixa() = runBlocking {
        dao.substituirBase(
            listOf(radar("perto", -15.800, -47.900), radar("longe", -15.900, -47.900)), info("v1"),
        )
        val achados = dao.naCaixa(Caixa.emVoltaDe(-15.801, -47.900, 1000.0))
        assertEquals(listOf("perto"), achados.map { it.id })
    }

    @Test
    fun substituirBasePreservaOsDoUsuario() = runBlocking {
        dao.substituirBase(listOf(radar("a", -15.8, -47.9)), info("v1"))
        dao.inserirDoUsuario(radar("meu", -15.81, -47.91, fonte = "USUARIO"))
        // a base nova traz um radar com o mesmo id do usuário: fica o do usuário
        dao.substituirBase(listOf(radar("b", -15.7, -47.8), radar("meu", 0.0, 0.0)), info("v2"))
        val ids = dao.naCaixa(-90.0, 90.0, -180.0, 180.0).map { it.id }.sorted()
        assertEquals(listOf("b", "meu"), ids)
        assertEquals("USUARIO", dao.doUsuario().single().fonte)
        assertEquals("v2", dao.baseInfo()!!.versao)
    }
}
