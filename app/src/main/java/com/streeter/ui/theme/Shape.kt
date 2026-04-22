package com.streeter.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

val StreeterShapes = Shapes(
    extraSmall = RoundedCornerShape(12.dp),  // sm
    small = RoundedCornerShape(12.dp),       // sm
    medium = RoundedCornerShape(20.dp),      // md
    large = RoundedCornerShape(28.dp),       // lg — card radius
    extraLarge = RoundedCornerShape(32.dp)   // xl
)
