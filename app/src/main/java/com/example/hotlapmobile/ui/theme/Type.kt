package com.example.hotlapmobile.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Use built-in families to avoid Google Fonts dependency for now.
private val rubik = FontFamily.SansSerif
private val inter = FontFamily.SansSerif

val AppTypography = Typography(
    displayLarge = TextStyle(fontFamily = rubik, fontWeight = FontWeight.W700, fontSize = 40.sp),
    headlineLarge = TextStyle(fontFamily = rubik, fontWeight = FontWeight.W700, fontSize = 32.sp),
    headlineMedium = TextStyle(fontFamily = rubik, fontWeight = FontWeight.W700, fontSize = 24.sp),
    bodyLarge = TextStyle(fontFamily = inter, fontWeight = FontWeight.W500, fontSize = 16.sp),
    bodyMedium = TextStyle(fontFamily = inter, fontWeight = FontWeight.W500, fontSize = 14.sp),
    labelLarge = TextStyle(fontFamily = inter, fontWeight = FontWeight.W700, fontSize = 14.sp)
)
