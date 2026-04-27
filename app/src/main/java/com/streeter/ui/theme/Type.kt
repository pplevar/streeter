package com.streeter.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val StreeterTypography =
    Typography(
        displaySmall =
            TextStyle(
                fontWeight = FontWeight.Medium,
                fontSize = 40.sp,
                lineHeight = 42.sp,
                letterSpacing = (-1.2).sp,
            ),
        titleLarge =
            TextStyle(
                fontWeight = FontWeight.Medium,
                fontSize = 22.sp,
                lineHeight = 28.sp,
                letterSpacing = (-0.2).sp,
            ),
        bodyMedium =
            TextStyle(
                fontWeight = FontWeight.Normal,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                letterSpacing = 0.25.sp,
            ),
        bodySmall =
            TextStyle(
                fontWeight = FontWeight.Normal,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                letterSpacing = 0.4.sp,
            ),
        labelSmall =
            TextStyle(
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                lineHeight = 16.sp,
                letterSpacing = 0.5.sp,
            ),
        labelMedium =
            TextStyle(
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                lineHeight = 16.sp,
                letterSpacing = 0.5.sp,
            ),
    )
