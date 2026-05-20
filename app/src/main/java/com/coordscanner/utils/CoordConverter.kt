package com.coordscanner.utils

import kotlin.math.*

object CoordConverter {

    // Krassovsky 1940 ellipsoid (SK-42)
    private const val A   = 6378245.0
    private const val E2  = 0.006693421522
    private const val F   = 1.0 / 298.3

    // WGS-84
    private const val A84 = 6378137.0
    private const val F84 = 1.0 / 298.257223563

    // Molodensky shift Pulkovo 1942 → WGS-84 (EPSG:1258, Russia)
    private const val DX = 25.0
    private const val DY = -141.0
    private const val DZ = -79.0

    fun sk42ToWgs84(x: Double, y: Double, zone: Int): Pair<Double, Double> {
        val (lat42, lon42) = gaussKrugerToGeographic(x, y, zone)
        return molodenskyPulkovoToWgs84(lat42, lon42)
    }

    fun gaussKrugerToGeographic(x: Double, y: Double, zone: Int): Pair<Double, Double> {
        val e2 = E2; val e4 = e2 * e2; val e6 = e4 * e2

        val l0 = Math.toRadians(6.0 * zone - 3.0)
        val y0 = y - zone * 1_000_000.0 - 500_000.0

        // Meridional arc forward-series coefficients (Helmert)
        val C0 = 1.0 - e2 / 4 - 3 * e4 / 64 - 5 * e6 / 256
        val C2 = 3.0 / 8    * (e2 + e4 / 4   + 15 * e6 / 128)
        val C4 = 15.0 / 256 * (e4 + 3 * e6 / 4)
        val C6 = 35.0 / 3072 * e6

        // Newton-Raphson: find footprint latitude B such that M(B) = X
        var B = x / (A * C0)
        repeat(10) {
            val M  = A * (C0 * B - C2 * sin(2 * B) + C4 * sin(4 * B) - C6 * sin(6 * B))
            val dM = A * (C0 - 2 * C2 * cos(2 * B) + 4 * C4 * cos(4 * B) - 6 * C6 * cos(6 * B))
            B -= (M - x) / dM
        }

        val sinB = sin(B); val cosB = cos(B); val tanB = tan(B)
        val W  = sqrt(1 - e2 * sinB * sinB)
        val N  = A / W
        val Mc = A * (1 - e2) / (W * W * W)
        val n2 = e2 / (1 - e2) * cosB * cosB
        val t = tanB; val t2 = t * t; val t4 = t2 * t2

        val q  = y0 / N
        val q2 = q * q; val q4 = q2 * q2

        val lat = B - (t * N / Mc) * (q2 / 2) *
            (1.0 - q2 / 12 * (5 + 3 * t2 + n2 - 9 * n2 * t2 - 4 * n2 * n2)
                 + q4 / 360 * (61 + 90 * t2 + 45 * t4))

        val lon = l0 + (q / cosB) *
            (1.0 - q2 / 6   * (1 + 2 * t2 + n2)
                 + q4 / 120 * (5 + 28 * t2 + 24 * t4 + 6 * n2 + 8 * n2 * t2))

        return Pair(Math.toDegrees(lat), Math.toDegrees(lon))
    }

    fun molodenskyPulkovoToWgs84(latDeg: Double, lonDeg: Double): Pair<Double, Double> {
        val phi = Math.toRadians(latDeg)
        val lam = Math.toRadians(lonDeg)

        val sinPhi = sin(phi); val cosPhi = cos(phi)
        val sinLam = sin(lam); val cosLam = cos(lam)

        val da = A84 - A
        val df = F84 - F

        val W = sqrt(1 - E2 * sinPhi * sinPhi)
        val N = A / W
        val M = A * (1 - E2) / (W * W * W)

        val dPhi = ((-DX * sinPhi * cosLam - DY * sinPhi * sinLam + DZ * cosPhi) +
                   (A * df + F * da) * sin(2 * phi)) / M

        val dLam = (-DX * sinLam + DY * cosLam) / (N * cosPhi)

        return Pair(latDeg + Math.toDegrees(dPhi), lonDeg + Math.toDegrees(dLam))
    }

    fun pulkovoToWgs84(latDeg: Double, lonDeg: Double) =
        molodenskyPulkovoToWgs84(latDeg, lonDeg)

    fun decimalToDms(decimal: Double): String {
        val abs = abs(decimal)
        val deg = abs.toInt()
        val minFull = (abs - deg) * 60.0
        val min = minFull.toInt()
        val sec = (minFull - min) * 60.0
        return "%d°%02d'%05.2f\"".format(deg, min, sec)
    }

    fun wgs84ToDisplayString(lat: Double, lon: Double): String {
        val latHem = if (lat >= 0) "N" else "S"
        val lonHem = if (lon >= 0) "E" else "W"
        return "%.6f° %s   %.6f° %s".format(kotlin.math.abs(lat), latHem, kotlin.math.abs(lon), lonHem)
    }
}
