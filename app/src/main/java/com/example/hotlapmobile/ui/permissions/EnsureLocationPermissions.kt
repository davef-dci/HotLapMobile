package com.example.hotlapmobile.ui.permissions

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.compose.ui.unit.dp

@Composable
fun EnsureLocationPermission(content: @Composable () -> Unit) {
    val context = LocalContext.current

    // current grant state
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    // whether we should show rationale (i.e., user denied once but not "Don't ask again")
    var shouldShowRationale by remember {
        mutableStateOf(
            ActivityCompat.shouldShowRequestPermissionRationale(
                // Need an Activity; LocalContext is fine for typical Activity hosts
                context.findActivity(), Manifest.permission.ACCESS_FINE_LOCATION
            )
        )
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        granted = isGranted
        // After a request, update rationale flag for the next render
        shouldShowRationale = ActivityCompat.shouldShowRequestPermissionRationale(
            context.findActivity(), Manifest.permission.ACCESS_FINE_LOCATION
        )
    }

    if (granted) {
        content()
        return
    }

    // Not granted: show explicit UI
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Hot Lap Mobile needs Location to detect corners and laps.")

        Spacer(Modifier.height(12.dp))

        // If user can still be prompted, show "Grant" button
        if (!granted && !isPermanentlyDenied(granted, shouldShowRationale)) {
            Button(onClick = { launcher.launch(Manifest.permission.ACCESS_FINE_LOCATION) }) {
                Text("Grant location permission")
            }
        } else {
            // Permanently denied ("Don't ask again") — send to Settings
            Button(onClick = {
                val intent = Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", context.packageName, null)
                )
                context.startActivity(intent)
            }) {
                Text("Open app settings")
            }
            Spacer(Modifier.height(8.dp))
            Text("Enable Location permission in Settings, then return.")
        }
    }
}

// --- helpers ---

private fun isPermanentlyDenied(granted: Boolean, shouldShowRationale: Boolean): Boolean {
    // Permanently denied if not granted and shouldShowRationale == false (after at least one denial)
    return !granted && !shouldShowRationale
}

// Get Activity from context (works when Composables are hosted by an Activity)
private fun android.content.Context.findActivity(): android.app.Activity {
    var ctx = this
    while (ctx is android.content.ContextWrapper) {
        if (ctx is android.app.Activity) return ctx
        ctx = ctx.baseContext
    }
    error("Permissions UI must be hosted in an Activity")
}
