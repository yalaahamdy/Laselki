package com.wavetalk.app.presentation.walkie.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Visual states of the big radio button. */
enum class PttVisual { IDLE, TALKING, BUSY, NO_MIC, NO_WIFI }

private data class ButtonColors(val ring: Color, val fill: Color, val onFill: Color)

/**
 * The heart of the UI: a large circular Push-To-Talk button with press
 * feedback, transmission glow and an audio-level waveform.
 */
@Composable
fun PttButton(
    visual: PttVisual,
    label: String,
    subLabel: String?,
    icon: ImageVector,
    level: Float,
    enabled: Boolean,
    onPressStart: () -> Unit,
    onPressEnd: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 224.dp,
) {
    var pressed by remember { mutableStateOf(false) }

    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.94f else 1f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 700f),
        label = "pressScale",
    )

    val pulse = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by pulse.animateFloat(
        initialValue = 0.5f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Restart),
        label = "pulseAlpha",
    )
    val pulseScale by pulse.animateFloat(
        initialValue = 0.9f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Restart),
        label = "pulseScale",
    )

    val colors = when (visual) {
        PttVisual.IDLE -> ButtonColors(
            ring = MaterialTheme.colorScheme.primary,
            fill = MaterialTheme.colorScheme.surfaceVariant,
            onFill = MaterialTheme.colorScheme.onSurface,
        )
        PttVisual.TALKING -> ButtonColors(
            ring = MaterialTheme.colorScheme.primary,
            fill = MaterialTheme.colorScheme.primaryContainer,
            onFill = MaterialTheme.colorScheme.primary,
        )
        PttVisual.BUSY -> ButtonColors(
            ring = MaterialTheme.colorScheme.error,
            fill = MaterialTheme.colorScheme.surfaceVariant,
            onFill = MaterialTheme.colorScheme.error,
        )
        PttVisual.NO_MIC, PttVisual.NO_WIFI -> ButtonColors(
            ring = MaterialTheme.colorScheme.outline,
            fill = MaterialTheme.colorScheme.surfaceVariant,
            onFill = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(size)) {

            // Transmission pulse rings
            if (visual == PttVisual.TALKING) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .scale(pulseScale)
                        .alpha(pulseAlpha)
                        .background(
                            brush = Brush.radialGradient(
                                colors = listOf(colors.ring, Color.Transparent),
                            ),
                            shape = CircleShape,
                        ),
                )
            }

            // Outer bezel → gap → inner face, mimicking a radio speaker button
            Box(
                modifier = Modifier
                    .size(size * 0.84f)
                    .scale(pressScale)
                    .shadow(
                        elevation = if (pressed || visual == PttVisual.TALKING) 18.dp else 8.dp,
                        shape = CircleShape,
                        ambientColor = colors.ring.copy(alpha = 0.6f),
                        spotColor = colors.ring.copy(alpha = 0.6f),
                    )
                    .background(
                        brush = Brush.linearGradient(
                            colors = listOf(
                                colors.ring.copy(alpha = if (visual == PttVisual.TALKING) 0.95f else 0.75f),
                                colors.ring.copy(alpha = 0.25f),
                            ),
                        ),
                        shape = CircleShape,
                    )
                    .padding(3.dp)
                    .background(MaterialTheme.colorScheme.background, CircleShape)
                    .padding(3.dp)
                    .background(colors.fill, CircleShape)
                    .semantics { contentDescription = label }
                    .pointerInput(enabled) {
                        detectTapGestures(
                            onPress = {
                                if (enabled) {
                                    pressed = true
                                    onPressStart()
                                    tryAwaitRelease()
                                    pressed = false
                                    onPressEnd()
                                }
                            },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = colors.onFill,
                        modifier = Modifier.size(54.dp),
                    )
                    Waveform(
                        level = if (visual == PttVisual.TALKING) level else 0f,
                        color = colors.onFill,
                        modifier = Modifier.offset(y = 6.dp),
                    )
                }
            }
        }

        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.2.sp,
            textAlign = TextAlign.Center,
            color = if (visual == PttVisual.BUSY) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.offset(y = 12.dp),
        )
        if (subLabel != null) {
            Text(
                text = subLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.offset(y = 14.dp),
            )
        }
    }
}

/** Simple 5-bar live waveform driven by the actual audio level. */
@Composable
fun Waveform(level: Float, color: Color, modifier: Modifier = Modifier) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier,
    ) {
        val factors = listOf(0.45f, 0.75f, 1f, 0.75f, 0.45f)
        factors.forEach { factor ->
            val target: Dp = 4.dp + 26.dp * (level * factor)
            val h by animateDpAsState(
                targetValue = target,
                animationSpec = spring(stiffness = 900f),
                label = "barHeight",
            )
            Box(
                Modifier
                    .width(5.dp)
                    .height(h)
                    .background(color, MaterialTheme.shapes.extraSmall),
            )
        }
    }
}
