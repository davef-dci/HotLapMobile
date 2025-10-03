package com.example.hotlapmobile.util

import kotlin.math.*

/** Haversine distance in meters between two lat/lon points. */
fun haversineMeters(
    lat1: Double, lon1: Double,
    lat2: Double, lon2: Double
): Double {
    val R = 6371000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2).pow(2.0) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2).pow(2.0)
    return 2 * R * atan2(sqrt(a), sqrt(1 - a))
}
