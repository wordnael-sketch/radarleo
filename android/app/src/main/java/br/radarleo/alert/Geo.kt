package br.radarleo.alert

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Mesmas fórmulas do ETL (etl/consolidar.py), para app e base concordarem nas distâncias. */
object Geo {
    private const val RAIO_TERRA_M = 6_371_008.8

    /** Distância de Haversine em metros. */
    fun distanciaM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val f1 = Math.toRadians(lat1)
        val f2 = Math.toRadians(lat2)
        val df = f2 - f1
        val dl = Math.toRadians(lon2 - lon1)
        val a = sin(df / 2) * sin(df / 2) + cos(f1) * cos(f2) * sin(dl / 2) * sin(dl / 2)
        return 2 * RAIO_TERRA_M * asin(sqrt(a))
    }

    /** Rumo inicial de 1 para 2, em graus [0, 360), 0 = norte, 90 = leste. */
    fun rumo(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val f1 = Math.toRadians(lat1)
        val f2 = Math.toRadians(lat2)
        val dl = Math.toRadians(lon2 - lon1)
        val y = sin(dl) * cos(f2)
        val x = cos(f1) * sin(f2) - sin(f1) * cos(f2) * cos(dl)
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }

    /** Menor ângulo entre dois rumos, em [0, 180]. 350° e 10° → 20°. */
    fun diferencaAngular(a: Double, b: Double): Double {
        val d = abs(a - b) % 360
        return if (d > 180) 360 - d else d
    }
}
