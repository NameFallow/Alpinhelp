package com.coordscanner.utils

import kotlin.math.*

object CoordConverter {

    // Krassovsky 1940 ellipsoid
    private const val A = 6378245.0
    private const val F = 1.0 / 298.3
    private const val E2 = 2 * F - F * F   // 0.006693421522

    // WGS84 ellipsoid
    private const val A_WGS = 6378137.0
    private const val F_WGS = 1.0 / 298.257223563

    // Datum shift Pulkovo 1942 -> WGS84 (standard for FSU/Russia, 3-parameter Molodensky)
    private const val DX = 25.0
    private const val DY = -141.0
    private const val DZ = -79.0

    /**
     * Convert SK-42 (Gauss-Kruger) X/Y with zone to WGS84 lat/lon in decimal degrees.
     */
    fun sk42ToWgs84(x: Double, y: Double, zone: Int): Pair<Double, Double> {
        val (lat42, lon42) = gaussKrugerToGeographic(x, y, zone)
        return pulkovoToWgs84(lat42, lon42)
    }

    /**
     * Gauss-Kruger (Krassovsky) X/Y -> geographic lat/lon in Pulkovo 1942 (decimal degrees).
     */
    fun gaussKrugerToGeographic(x: Double, y: Double, zone: Int): Pair<Double, Double> {
        val a = A
        val e2 = E2

        // Central meridian for this zone
        val l0Deg = 6.0 * zone - 3.0
        val l0 = Math.toRadians(l0Deg)

        // Strip zone prefix and false easting from Y
        val y0 = y - zone * 1_000_000.0 - 500_000.0

        // Iterative computation of footprint latitude B0 from meridional arc X
        // Meridional arc series coefficients (Krassovsky)
        val e4 = e2 * e2
        val e6 = e4 * e2
        val e8 = e4 * e4

        // Meridional arc length per unit radian (at equator)
        val c0 = a * (1 - e2) * (1.0 + 3.0/4*e2 + 45.0/64*e4 + 175.0/256*e6 + 11025.0/16384*e8)

        // Coefficients for B0 series inversion
        val c2 = 3.0/8*e2 + 15.0/32*e4 + 525.0/1024*e6 + 2205.0/4096*e8
        val c4 = 15.0/256*e4 + 105.0/1024*e6 + 2205.0/16384*e8
        val c6 = 35.0/3072*e6 + 315.0/12288*e8
        val c8 = 315.0/131072*e8

        val b0arg = x / c0
        val b0 = b0arg +
                c2 * sin(2 * b0arg) +
                c4 * sin(4 * b0arg) +
                c6 * sin(6 * b0arg) +
                c8 * sin(8 * b0arg)

        val sinB0 = sin(b0)
        val cosB0 = cos(b0)
        val tanB0 = tan(b0)

        val W0 = sqrt(1 - e2 * sinB0 * sinB0)
        val N0 = a / W0                          // prime vertical radius
        val M0 = a * (1 - e2) / (W0 * W0 * W0)  // meridional radius
        val eta2 = e2 / (1 - e2) * cosB0 * cosB0

        val t = tanB0
        val t2 = t * t
        val t4 = t2 * t2

        val y02 = y0 * y0
        val N02 = N0 * N0
        val N04 = N02 * N02

        // Latitude (series expansion)
        val latRad = b0 -
                t / (2 * M0 * N0) * y02 * (
                        1 - y02 / (12 * N02) * (5 + 3*t2 + eta2 - 9*eta2*t2 - 4*eta2*eta2) +
                        y02*y02 / (360 * N04) * (61 + 90*t2 + 45*t4)
                )

        // Longitude (series expansion)
        val lonRad = l0 +
                y0 / (N0 * cosB0) * (
                        1 - y02 / (6 * N02) * (1 + 2*t2 + eta2) +
                        y02*y02 / (120 * N04) * (5 + 28*t2 + 24*t4 + 6*eta2 + 8*eta2*t2)
                )

        return Pair(Math.toDegrees(latRad), Math.toDegrees(lonRad))
    }

    /**
     * Abridged Molodensky: Pulkovo 1942 (Krassovsky) -> WGS84, decimal degrees.
     */
    fun pulkovoToWgs84(latDeg: Double, lonDeg: Double): Pair<Double, Double> {
        val phi = Math.toRadians(latDeg)
        val lam = Math.toRadians(lonDeg)

        val sinPhi = sin(phi)
        val cosPhi = cos(phi)
        val sinLam = sin(lam)
        val cosLam = cos(lam)

        val da = A_WGS - A
        val df = F_WGS - F
        val e2 = E2

        val W = sqrt(1 - e2 * sinPhi * sinPhi)
        val N = A / W
        val M = A * (1 - e2) / (W * W * W)

        // Abridged Molodensky formulas (height assumed 0)
        val dPhi = ((-DX * sinPhi * cosLam - DY * sinPhi * sinLam + DZ * cosPhi) +
                (A * df + F * da) * sin(2 * phi)) / (M)

        val dLam = (-DX * sinLam + DY * cosLam) / (N * cosPhi)

        val latWgs = latDeg + Math.toDegrees(dPhi)
        val lonWgs = lonDeg + Math.toDegrees(dLam)

        return Pair(latWgs, lonWgs)
    }

    // ---- Formatting helpers ----

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
        return "${decimalToDms(lat)}$latHem  ${decimalToDms(lon)}$lonHem"
    }
}
