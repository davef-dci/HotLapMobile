package com.example.hotlapmobile.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.hotlapmobile.data.CalibRepo
import com.example.hotlapmobile.data.PrefsRepo
import com.example.hotlapmobile.data.TrackRepo
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.material3.ButtonDefaults

@Composable
private fun BigButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    subText: String? = null,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp), // taller touch target
        contentPadding = PaddingValues(vertical = 20.dp), // extra padding
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            if (subText != null) {
                Spacer(Modifier.height(4.dp))
                Text(subText, fontSize = 14.sp)
            }
        }
    }
}

@Composable
private fun BigOutlinedButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp),
        contentPadding = PaddingValues(vertical = 20.dp),
        colors = ButtonDefaults.outlinedButtonColors()
    ) {
        Text(text, fontSize = 20.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun MainMenu(
    onCalibrate: () -> Unit,
    onSelectTrack: () -> Unit,
    onSettings: () -> Unit,
    onRace: () -> Unit
) {
    val context = LocalContext.current

    // Repos remembered across recompositions
    val calibRepo = remember(context) { CalibRepo(context) }
    val trackRepo = remember(context) { TrackRepo(context) }

    // Reactive state from DataStore
    val calibState = calibRepo.state.collectAsStateWithLifecycle(initialValue = null).value
    val currentTrack = trackRepo.current.collectAsStateWithLifecycle(initialValue = null).value

    val isCalibrated = calibState?.vec != null
    val calibrationText = if (isCalibrated) "Calibration: Saved ✓" else "Calibration: Not set"
    val trackText = "Track: " + (currentTrack?.name ?: "None selected")

    val prefs = remember(context) { PrefsRepo(context) }
    val brakeThresh = prefs.brakeThreshG.collectAsStateWithLifecycle(0.30f).value

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Hot Lap Mobile - V1.4f",
            style = MaterialTheme.typography.headlineLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(6.dp))
        Text(
            text = calibrationText,
            style = MaterialTheme.typography.bodyMedium,
            color = if (isCalibrated) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.error
        )
        Text(
            text = trackText,
            style = MaterialTheme.typography.bodyMedium
        )

        Spacer(Modifier.height(24.dp))

        BigButton("Calibrate Accelerometers", onCalibrate)

        Spacer(Modifier.height(16.dp))

        BigButton("Select a Race Track", onSelectTrack)

        Spacer(Modifier.height(16.dp))

        BigOutlinedButton("Settings", onSettings)

        Spacer(Modifier.height(16.dp))

        BigButton(
            text = if (isCalibrated) "Go Race!" else "Go Race! (needs calibration)",
            subText = "Brake threshold: ${"%.2f".format(brakeThresh)} g",
            onClick = onRace,
            enabled = isCalibrated
        )

    }
}
