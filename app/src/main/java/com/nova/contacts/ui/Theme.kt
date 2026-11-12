package com.nova.contacts.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.ColumnScope

// NOVA Nebula — Dark Premium design language (see
// ~/workspace/nova-design/NOVA-NEBULA-DESIGN.md). Exact tokens, shared
// with Files, Dialer and Gallery. Dark is the default theme.
val NebulaBackground = Color(0xFF0A0C1C)
val NebulaSurface = Color(0xFF14172B)
val NebulaSurfaceElevated = Color(0xFF1B1F38)
val NebulaOutline = Color(0x14FFFFFF) // white 8% — 1dp card borders
val NebulaPrimary = Color(0xFF8B5CF6)
val NebulaGradientStart = Color(0xFF7C3AED)
val NebulaGradientEnd = Color(0xFF3B82F6)
val NebulaSecondary = Color(0xFF22D3EE)
val NebulaTertiary = Color(0xFFEC4899)
val NebulaOnSurface = Color(0xFFEDEFF7)
val NebulaSecondaryText = Color(0xFF9AA3C7)
val NebulaError = Color(0xFFF87171)
val NebulaSuccess = Color(0xFF34D399)

// Gradient for FABs, primary buttons, active pills and hero accents.
val NebulaGradient = Brush.horizontalGradient(listOf(NebulaGradientStart, NebulaGradientEnd))
// Softer tint of the same gradient for empty-state icon circles.
val NebulaGradientTint = Brush.horizontalGradient(
    listOf(NebulaGradientStart.copy(alpha = 0.28f), NebulaGradientEnd.copy(alpha = 0.28f))
)

private val NebulaDarkColors = darkColorScheme(
    primary = NebulaPrimary,
    secondary = NebulaSecondary,
    tertiary = NebulaTertiary,
    background = NebulaBackground,
    surface = NebulaSurface,
    surfaceVariant = NebulaSurfaceElevated,
    surfaceContainer = NebulaSurface,
    surfaceContainerHigh = NebulaSurfaceElevated,
    surfaceContainerHighest = Color(0xFF232842),
    secondaryContainer = NebulaSurfaceElevated,
    tertiaryContainer = NebulaSurfaceElevated,
    onPrimary = Color.White,
    onSecondary = Color(0xFF06222B),
    onTertiary = Color.White,
    onBackground = NebulaOnSurface,
    onSurface = NebulaOnSurface,
    onSurfaceVariant = NebulaSecondaryText,
    onSecondaryContainer = NebulaOnSurface,
    onTertiaryContainer = NebulaOnSurface,
    error = NebulaError,
    onError = Color.White,
    outline = Color(0xFF3D4364),
    outlineVariant = NebulaOutline
)

private val NebulaLightColors = lightColorScheme(
    primary = NebulaPrimary,
    secondary = Color(0xFF0891B2),
    tertiary = Color(0xFFDB2777),
    background = Color(0xFFF4F5FB),
    surface = Color.White,
    surfaceVariant = Color(0xFFEDEFF7),
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color(0xFFEDEFF7),
    surfaceContainerHighest = Color(0xFFE2E5F2),
    secondaryContainer = Color(0xFFE2F4FA),
    tertiaryContainer = Color(0xFFFDE7F1),
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = Color.White,
    onBackground = Color(0xFF12141F),
    onSurface = Color(0xFF12141F),
    onSurfaceVariant = Color(0xFF5A6076),
    onSecondaryContainer = Color(0xFF0B3B47),
    onTertiaryContainer = Color(0xFF4A0E2E),
    error = Color(0xFFDC2626),
    onError = Color.White,
    outline = Color(0xFFC9CEDF),
    outlineVariant = Color(0xFFE2E5F2)
)

// Shapes: cards 20dp, buttons 14dp, chips/pills 12dp.
private val NebulaShapes = Shapes(
    extraSmall = RoundedCornerShape(12.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun NovaTheme(darkTheme: Boolean = true, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) NebulaDarkColors else NebulaLightColors,
        shapes = NebulaShapes,
        content = content
    )
}

/** Primary action button: violet→blue gradient, 14dp radius. */
@Composable
fun NebulaButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.background(
            brush = if (enabled) NebulaGradient
            else Brush.horizontalGradient(
                listOf(
                    MaterialTheme.colorScheme.surfaceVariant,
                    MaterialTheme.colorScheme.surfaceVariant
                )
            ),
            shape = RoundedCornerShape(14.dp)
        ),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.Transparent,
            contentColor = Color.White,
            disabledContainerColor = Color.Transparent,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
        ),
        content = content
    )
}

/** Card: 20dp radius, 1dp outline border, no hard shadow. */
@Composable
fun NebulaCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        content = content
    )
}

/** Gradient FAB: 16dp radius, white icon. */
@Composable
fun NebulaFab(onClick: () -> Unit, content: @Composable () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = Color.Transparent,
        shadowElevation = 6.dp,
        modifier = Modifier.background(NebulaGradient, RoundedCornerShape(16.dp))
    ) {
        Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
            CompositionLocalProvider(LocalContentColor provides Color.White) {
                content()
            }
        }
    }
}

/** Empty-state icon: centered icon in a gradient-tinted circle. */
@Composable
fun NebulaEmptyIcon(icon: ImageVector, contentDescription: String?) {
    Box(
        Modifier.size(80.dp).clip(CircleShape).background(NebulaGradientTint),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary)
    }
}
