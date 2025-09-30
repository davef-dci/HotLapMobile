// data/DriverRepo.kt
package com.example.hotlapmobile.data

import androidx.compose.runtime.mutableStateListOf
import com.example.hotlapmobile.config.LatLon
import com.example.hotlapmobile.config.Track

class DriverRepo(val track: Track) {
    // One boolean per corner
    val trainedCorners = mutableStateListOf<Boolean>().apply {
        repeat(track.corners.size) { add(false) }
    }

    // One LatLon per corner (null = not trained yet)
    val brakePoints = mutableStateListOf<LatLon?>().apply {
        repeat(track.corners.size) { add(null) }
    }

    fun reset() {
        for (i in trainedCorners.indices) {
            trainedCorners[i] = false
            brakePoints[i] = null
        }
    }

    fun markTrained(index: Int, point: LatLon) {
        if (index in trainedCorners.indices && !trainedCorners[index]) {
            trainedCorners[index] = true
            brakePoints[index] = point
        }
    }

    fun allTrained() = trainedCorners.all { it }
}
