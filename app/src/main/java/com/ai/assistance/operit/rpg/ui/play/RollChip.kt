package com.ai.assistance.operit.rpg.ui.play

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.operit.rpg.agent.ui.RollUi

/** A thin roll label that expands in place to show faces, modifier and expression. */
@Composable
fun RollChip(roll: RollUi, modifier: Modifier = Modifier) {
    var expanded by remember(roll) { mutableStateOf(false) }
    Column(modifier) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = Color.Transparent,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            modifier = Modifier.clickable { expanded = !expanded },
        ) {
            Row(
                Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DiceIcon(Modifier.size(14.dp))
                Text(
                    roll.summary,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (expanded) {
            Text(
                roll.detail,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 10.dp, top = 4.dp),
            )
        }
    }
}

/** A small d20 outline with three pips; drawn locally because material-icons-core has no die. */
@Composable
private fun DiceIcon(modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(modifier) {
        val stroke = size.minDimension * 0.1f
        drawRoundRect(
            color = color,
            topLeft = Offset(stroke, stroke),
            size = Size(size.width - stroke * 2, size.height - stroke * 2),
            cornerRadius = CornerRadius(size.minDimension * 0.18f),
            style = Stroke(width = stroke),
        )
        val dot = size.minDimension * 0.07f
        listOf(0.32f to 0.32f, 0.5f to 0.5f, 0.68f to 0.68f).forEach { (x, y) ->
            drawCircle(color, radius = dot, center = Offset(size.width * x, size.height * y))
        }
    }
}
