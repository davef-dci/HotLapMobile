package com.example.hotlapmobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.hotlapmobile.config.Track
import com.example.hotlapmobile.data.DriverRepo
import com.example.hotlapmobile.ui.race.useTrainingTelemetry
import kotlin.math.roundToInt
import androidx.compose.ui.text.style.TextAlign


@Composable
fun TrainingScreen(track: Track, repo: DriverRepo, onFinished: () -> Unit) {
    // Session key must be declared BEFORE the hook and passed in
    var sessionKey by remember { mutableStateOf(0) }
    val telem = useTrainingTelemetry(repo, sessionKey)

    val trained = repo.trainedCorners

    val totalCorners = track.corners.size
    val trainedCount by remember { derivedStateOf { trained.count { it } } }

    LaunchedEffect(trainedCount, totalCorners) {
        if (totalCorners > 0 && trainedCount >= totalCorners) {
            onFinished()
        }
    }


    val context = LocalContext.current
    val prefs = remember(context) { com.example.hotlapmobile.data.PrefsRepo(context) }
    val brakeThreshG = prefs.brakeThreshG.collectAsStateWithLifecycle(0.30f).value

    Box(
        Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier.fillMaxWidth(0.9f)
        ) {
            Text(
                "First Lap Brake Point Training",
                fontSize = 48.sp,
                fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )


            // Corner list with bigger dots/text
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                track.corners.forEachIndexed { i, _ ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val color = if (trained[i]) Color(0xFF2ECC71) else Color.Gray
                        Box(
                            Modifier
                                .size(48.dp) // was 20.dp
                                .clip(CircleShape)
                                .background(color)
                        )
                        Spacer(Modifier.width(12.dp))
                        Text("Corner ${i + 1}", fontSize = 36.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            Text(
                "(Brake points will update after your every fastest lap!)",
                fontSize = 36.sp,
                fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )

            // Buttons larger
            Button(
                onClick = { repo.reset(); sessionKey++ },
                modifier = Modifier
                    .padding(top = 8.dp)
            ) {
                Text("Reset Driver", fontSize = 18.sp)
            }

            // Optional: compact debug lines (still bigger than before)
            Divider(Modifier.padding(vertical = 8.dp))
            val gps = telem.lastGps
            Text(
                "GPS: " + if (gps != null)
                    "${"%.6f".format(gps.lat)}, ${"%.6f".format(gps.lon)}"
                else "Waiting…",
                fontSize = 16.sp
            )
            val idx = telem.nearestCornerIndex
            val dist = telem.distanceToNearestCornerM
            Text(
                "Target: " + (if (idx != null) "C${idx + 1}" else "—") +
                        (if (dist != null && dist.isFinite()) "  (${dist.roundToInt()} m)" else ""),
                fontSize = 16.sp
            )
            Text(
                "In zone: " + if (telem.inBrakeZone) "Yes" else "No",
                fontSize = 16.sp,
                color = if (telem.inBrakeZone) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface
            )
        }
    }

}
