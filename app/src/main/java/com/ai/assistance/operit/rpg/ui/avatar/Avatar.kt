package com.ai.assistance.operit.rpg.ui.avatar

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp

private fun colorOf(hex: String): Color = Color(0xFF000000L or hex.substring(1).toLong(16))

/** Draws the baked brush-stroke glyph of [spec] on its pair's background circle. */
@Composable
fun Avatar(spec: AvatarSpec, size: Dp, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val catalog = remember(context) { AvatarCatalog.load(context) }
    val glyph = catalog.glyphPath(spec.kind, spec.glyphId)
    val path = remember(catalog, spec.kind, spec.glyphId) { PathParser().parsePathString(glyph).toPath() }
    val pair = catalog.pair(spec.pairId)
    Canvas(modifier = modifier.size(size)) {
        val side = this.size.minDimension
        drawCircle(color = colorOf(pair.bg), radius = side / 2f)
        withTransform({
            translate(side / 2f, side / 2f)
            scale(side / 100f * catalog.inkScale, side / 100f * catalog.inkScale, Offset.Zero)
        }) {
            drawPath(path, color = colorOf(pair.line))
        }
    }
}
