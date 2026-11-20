package com.example.kmptest.presentation.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** A rounded surface with a hairline border: the app's basic container. */
/** Widest the content gets on a big window (desktop); the background behind it still spans the whole window. */
val ContentMaxWidth = 760.dp

/** Full width on a phone; on a wide window a centred column of [ContentMaxWidth]. Put backgrounds before it. */
fun Modifier.centeredContent(): Modifier =
    fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = ContentMaxWidth).fillMaxWidth()

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val border = if (selected) BorderStroke(1.5.dp, colors.primary) else BorderStroke(1.dp, colors.outlineVariant)
    val container = if (selected) colors.primaryContainer.copy(alpha = 0.55f) else colors.surfaceContainer
    val base = modifier.fillMaxWidth()
    if (onClick != null) {
        Surface(onClick = onClick, enabled = enabled, modifier = base, shape = MaterialTheme.shapes.large, color = container, border = border) { content() }
    } else {
        Surface(modifier = base, shape = MaterialTheme.shapes.large, color = container, border = border) { content() }
    }
}

/** Section heading with a short blue accent bar. */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(4.dp).height(16.dp).clip(RoundedCornerShape(2.dp)).background(SolidColor(BrandBlue)))
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
    }
}

/**
 * A "liquid glass" pill, as on iOS: a flat translucent tint (no gradient), a thin light edge and a soft glow.
 * [tonal] is the quiet variant for secondary actions; the default is the stronger tint for the main action.
 */
@Composable
fun GlassButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tonal: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(50)
    val tint = colors.primary
    val fill = when {
        !enabled -> colors.onSurface.copy(alpha = 0.08f)
        tonal -> tint.copy(alpha = 0.18f)
        else -> tint.copy(alpha = 0.78f)
    }
    val edge = if (enabled) Color.White.copy(alpha = 0.45f) else Color.White.copy(alpha = 0.12f)
    val content = when {
        !enabled -> colors.onSurfaceVariant.copy(alpha = 0.6f)
        tonal -> tint
        else -> Color.White
    }
    Box(
        modifier
            .heightIn(min = 48.dp)
            .then(if (enabled && !tonal) Modifier.shadow(12.dp, shape, ambientColor = tint, spotColor = tint) else Modifier)
            .clip(shape)
            .background(fill)
            .border(1.dp, edge, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 26.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium, color = content)
    }
}

@Composable
fun Dot(color: Color, modifier: Modifier = Modifier) {
    Box(modifier.clip(RoundedCornerShape(50)).background(color))
}
