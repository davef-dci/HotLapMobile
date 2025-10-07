package com.example.hotlapmobile.config

data class LatLon(val lat: Double, val lon: Double)

data class Track(
    val name: String,
    val startFinish: LatLon,
    val startFinishRadiusM: Double = 20.0,
    val corners: List<LatLon>,
    // track-wide parameters
    val cornerToleranceM: Double = 20.0,
    val brakeZoneDistanceM: Double = 150.0,
    val brakeWarnDistanceM: Double = 120.0
)

object Tracks {
    val DcfNeighborhood = Track(
        name = "DCF Neighborhood",
        startFinish = LatLon(43.046651, -89.445516),
        startFinishRadiusM = 20.0,
        corners = listOf(
            LatLon(43.046943, -89.447696), // C1
            LatLon(43.042693, -89.448071), // C2
            LatLon(43.044651, -89.444843)  // C3
        ),
        cornerToleranceM = 20.0,
        brakeZoneDistanceM = 150.0,
        brakeWarnDistanceM = 120.0
    )

    // NEW: Kent Kallsen Demo Track
    val KentKallsenDemo = Track(
        name = "Kent Kallsen Demo Track",
        startFinish = LatLon(42.909616, -89.131885),
        startFinishRadiusM = 25.0,           // give a bit more radius for phone GPS
        corners = listOf(
            LatLon(42.912527, -89.131918),   // C1
            LatLon(42.911770, -89.151501),   // C2
            LatLon(42.897544, -89.151200),   // C3
            LatLon(42.897930, -89.131966)    // C4
        ),
        cornerToleranceM = 25.0,             // tweak per feel; 20–30m is typical
        brakeZoneDistanceM = 150.0,          // begin looking for braking
        brakeWarnDistanceM = 120.0           // start 6→!Brake! countdown
    )

    val all = listOf(
        DcfNeighborhood,
        KentKallsenDemo
    )
}
