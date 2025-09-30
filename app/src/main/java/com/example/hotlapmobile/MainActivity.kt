package com.example.hotlapmobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import android.view.WindowManager
import com.example.hotlapmobile.ui.AppNav
import com.example.hotlapmobile.ui.theme.HotLapTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 👇 Prevent lock screen / keep screen on
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            AppNav()
        }
    }
}