package za.co.meridiangroup.attendance.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import za.co.meridiangroup.attendance.ui.theme.MeridianTheme

/** Worked-hours ring for the StatusHero. `progress` in [0,1]. */
@Composable
fun HoursRing(progress: Float, centerText: String, caption: String, modifier: Modifier = Modifier, size: Dp = 112.dp) {
    val animated by animateFloatAsState(progress.coerceIn(0f, 1f), label = "hours")
    val track = MeridianTheme.extended.neutralContainer
    val fill = MeridianTheme.extended.success
    Box(modifier.size(size).semantics { contentDescription = "$caption: $centerText" }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val stroke = Stroke(width = 10.dp.toPx(), cap = StrokeCap.Round)
            drawArc(track, startAngle = -90f, sweepAngle = 360f, useCenter = false, style = stroke)
            drawArc(fill, startAngle = -90f, sweepAngle = 360f * animated, useCenter = false, style = stroke)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(centerText, style = MaterialTheme.typography.titleLarge)
            Text(caption, style = MaterialTheme.typography.labelSmall, color = MeridianTheme.extended.mutedText)
        }
    }
}
