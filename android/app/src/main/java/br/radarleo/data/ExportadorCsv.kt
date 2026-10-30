package br.radarleo.data

import java.util.Locale

/**
 * Gera o CSV dos radares reportados no app, no formato que o ETL lê em data/manual/
 * (etl/fontes/manual.py). A fonte sai como MANUAL: o ETL não conhece "USUARIO"
 * e descartaria a linha.
 */
object ExportadorCsv {
    const val CABECALHO = "latitude,longitude,tipo,limite_kmh,direcao_graus,rodovia,km,fonte,observacao"

    fun gerar(radares: List<Radar>): String = buildString {
        append(CABECALHO).append('\n')
        for (r in radares.sortedBy { it.atualizadoEm }) {
            val campos = listOf(
                num(r.latitude, 7), num(r.longitude, 7), r.tipo,
                r.limiteKmh?.toString() ?: "",
                r.direcaoGraus?.let { num(it, 1) } ?: "",
                r.rodovia ?: "", r.km?.let { num(it, 3) } ?: "",
                "MANUAL",
                "reportado no app em ${r.atualizadoEm}",
            )
            append(campos.joinToString(",") { aspas(it) }).append('\n')
        }
    }

    /** Ponto como separador decimal, qualquer que seja o idioma do aparelho. */
    private fun num(v: Double, casas: Int) = String.format(Locale.ROOT, "%.${casas}f", v)

    private fun aspas(campo: String) =
        if (campo.any { it == ',' || it == '"' || it == '\n' }) "\"" + campo.replace("\"", "\"\"") + "\"" else campo
}
