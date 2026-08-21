package com.jasonschoenbrun.ytmtrigger.calendar

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin

/**
 * Sunset for a date and place, computed locally.
 *
 * This replaces the fixed 17:30 / 21:30 clock times the Shabat and Yom Tov
 * windows used to assume. Those were not merely imprecise: sunset in Israel
 * runs from about 16:39 in December to about 19:48 in June, so a fixed 17:30
 * start blocked nearly two hours of Friday afternoon in summer *and* began
 * almost an hour after Shabat had already started in winter - the second of
 * which let music play on Shabat.
 *
 * Standard sunrise/sunset algorithm (the one on Wikipedia, derived from NOAA's
 * solar position notes). Accurate to well under a minute at these latitudes,
 * needs no network and no location permission.
 */
object SolarCalculator {

    /** Solar disc + refraction correction, in degrees below the horizon. */
    private const val SUNSET_ZENITH_DEG = -0.833

    /**
     * Local time of sunset on [date] at [latitude]/[longitude] (degrees, north
     * and east positive), or null inside a polar day or night where the sun
     * does not cross the horizon.
     */
    fun sunset(
        date: LocalDate,
        latitude: Double,
        longitude: Double,
        zone: ZoneId = ZoneId.systemDefault(),
    ): LocalDateTime? {
        // The reference algorithm measures longitude westward.
        val lw = -longitude
        val julianDate = date.toEpochDay() + 2440587.5
        val n = Math.round(julianDate - 2451545.0 + 0.0009 - lw / 360.0).toDouble()
        val meanSolarNoon = 2451545.0 + 0.0009 + lw / 360.0 + n

        val meanAnomalyDeg = wrap360(357.5291 + 0.98560028 * n)
        val meanAnomaly = Math.toRadians(meanAnomalyDeg)
        val centreDeg = 1.9148 * sin(meanAnomaly) +
            0.0200 * sin(2 * meanAnomaly) +
            0.0003 * sin(3 * meanAnomaly)
        val eclipticLonDeg = wrap360(meanAnomalyDeg + centreDeg + 180.0 + 102.9372)
        val eclipticLon = Math.toRadians(eclipticLonDeg)

        val transit = meanSolarNoon + 0.0053 * sin(meanAnomaly) - 0.0069 * sin(2 * eclipticLon)
        val declination = asin(sin(eclipticLon) * sin(Math.toRadians(23.4397)))

        val lat = Math.toRadians(latitude)
        val cosHourAngle =
            (sin(Math.toRadians(SUNSET_ZENITH_DEG)) - sin(lat) * sin(declination)) /
                (cos(lat) * cos(declination))
        if (cosHourAngle < -1.0 || cosHourAngle > 1.0) return null

        val hourAngleDeg = Math.toDegrees(acos(cosHourAngle))
        val julianSunset = transit + hourAngleDeg / 360.0
        val millis = ((julianSunset - 2440587.5) * 86_400_000.0).toLong()
        return Instant.ofEpochMilli(millis).atZone(zone).toLocalDateTime()
    }

    private fun wrap360(deg: Double): Double = ((deg % 360.0) + 360.0) % 360.0
}

