package com.streeter.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

val StreeterShapes =
    Shapes(
        // sm
        extraSmall = RoundedCornerShape(12.dp),
        // sm
        small = RoundedCornerShape(12.dp),
        // md
        medium = RoundedCornerShape(20.dp),
        // lg — card radius
        large = RoundedCornerShape(28.dp),
        // xl
        extraLarge = RoundedCornerShape(32.dp),
    )
