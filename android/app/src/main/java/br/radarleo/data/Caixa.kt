package br.radarleo.data

import kotlin.math.cos

/** Bounding box em graus. Serve para a consulta usar o índice (lat, lon) do Room. */
data class Caixa(val latMin: Double, val latMax: Double, val lonMin: Double, val lonMax: Double) {

    fun contem(lat: Double, lon: Double) = lat in latMin..latMax && lon in lonMin..lonMax

    companion object {
        private const val METROS_POR_GRAU = 111_195.08 // raio médio da Terra, igual ao ETL

        /** Quadrado de lado 2·raio centrado no ponto (contém o círculo de raio [raioM]). */
        fun emVoltaDe(lat: Double, lon: Double, raioM: Double): Caixa {
            val dLat = raioM / METROS_POR_GRAU
            // um grau de longitude encolhe com cos(lat); o piso evita divisão por ~0 nos polos
            val dLon = raioM / (METROS_POR_GRAU * cos(Math.toRadians(lat)).coerceAtLeast(0.01))
            return Caixa(lat - dLat, lat + dLat, lon - dLon, lon + dLon)
        }
    }
}
