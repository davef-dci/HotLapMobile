package com.example.hotlapmobile.util

import kotlin.math.*

/**
 * Compute great-circle distance between two LatLon points in meters
 * using the haversine formula.
 */
fun haversineMeters(a: com.example.hotlapmobile.config.LatLon, b: com.example.hotlapmobile.config.LatLon): Double {
    val R = 6371000.0 // Earth radius in meters

    val dLat = Math.toRadians(b.lat - a.lat)
    val dLon = Math.toRadians(b.lon - a.lon)
    val lat1 = Math.toRadians(a.lat)
    val lat2 = Math.toRadians(b.lat)

    val sinDlat = sin(dLat / 2)
    val sinDlon = sin(dLon / 2)
    val h = sinDlat * sinDlat + cos(lat1) * cos(lat2) * sinDlon * sinDlon

    return 2 * R * asin(sqrt(h))
}
