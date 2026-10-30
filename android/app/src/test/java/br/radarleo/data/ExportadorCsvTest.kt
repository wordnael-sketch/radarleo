package br.radarleo.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.util.Locale

class ExportadorCsvTest {

    private fun reporte(id: String, dia: String, direcao: Double?, limite: Int?, tipo: String = "FIXO") = Radar(
        id = id, latitude = -15.7938891, longitude = -47.8827778, tipo = tipo, fonte = "USUARIO",
        limiteKmh = limite, direcaoGraus = direcao, rodovia = null, km = null,
        confiabilidade = 90, atualizadoEm = dia, fontes = "USUARIO",
    )

    private val reportes = listOf(
        reporte("b", "2026-11-03", null, null, tipo = "SEMAFORO"),
        reporte("a", "2026-11-01", 71.53, 60),
    )

    @Test
    fun `formato de data manual`() {
        val linhas = ExportadorCsv.gerar(reportes).trimEnd().lines()
        assertEquals("latitude,longitude,tipo,limite_kmh,direcao_graus,rodovia,km,fonte,observacao", linhas[0])
        // ordenado por data; ponto decimal; vazio quando não há direção/limite; fonte MANUAL
        assertEquals("-15.7938891,-47.8827778,FIXO,60,71.5,,,MANUAL,reportado no app em 2026-11-01", linhas[1])
        assertEquals("-15.7938891,-47.8827778,SEMAFORO,,,,,MANUAL,reportado no app em 2026-11-03", linhas[2])
    }

    @Test
    fun `decimal com ponto mesmo em aparelho em portugues`() {
        val anterior = Locale.getDefault()
        try {
            Locale.setDefault(Locale("pt", "BR"))
            assert(ExportadorCsv.gerar(reportes).contains("-15.7938891,-47.8827778"))
        } finally {
            Locale.setDefault(anterior)
        }
    }

    /** Deixa um exemplo em build/ para conferir com o leitor do ETL (etl/fontes/manual.py). */
    @Test
    fun `grava exemplo para o ETL`() {
        File("build/tmp/reportes_exemplo.csv").apply { parentFile?.mkdirs() }.writeText(ExportadorCsv.gerar(reportes))
    }
}
