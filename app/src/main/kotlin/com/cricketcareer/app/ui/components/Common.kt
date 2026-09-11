package com.cricketcareer.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.cricketcareer.app.ui.theme.CricketType
import com.cricketcareer.app.ui.theme.LocalCricketColors

/**
 * Shared pieces. Nothing here knows any cricket — a [Gauge] draws a fraction
 * and does not care whether it is a pitch's moisture or a batter's technique.
 */

/** A card with the standard inset and border. */
@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val colors = LocalCricketColors.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 11.dp, vertical = 5.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, colors.line, RoundedCornerShape(10.dp)),
        content = content,
    )
}

/** A small all-caps section heading. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = CricketType.Label,
        color = LocalCricketColors.current.muted,
        modifier = modifier,
    )
}

/** A labelled bar, 0 to 1, with the number on the end. */
@Composable
fun Gauge(
    name: String,
    fraction: Double,
    value: String,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.primary,
) {
    val colors = LocalCricketColors.current
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Text(name, style = CricketType.Body, color = colors.soft, modifier = Modifier.weight(0.42f))
        Box(
            modifier = Modifier
                .weight(0.44f)
                .height(7.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.background),
        ) {
            Box(
                modifier = Modifier
                    // coerceIn rather than trusting the caller: a bar drawn past
                    // its track is a rendering artefact nobody can debug from a
                    // screenshot, and the state layer already rejects the values
                    // that would cause it.
                    .fillMaxWidth(fraction.coerceIn(0.0, 1.0).toFloat())
                    .height(7.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(tint),
            )
        }
        Text(
            value,
            style = CricketType.Figure,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(0.14f),
            textAlign = TextAlign.End,
        )
    }
}

/** One ball in the this-over strip. */
@Composable
fun Pip(label: String, wicket: Boolean, four: Boolean, six: Boolean) {
    val colors = LocalCricketColors.current
    val background = when {
        wicket -> colors.wicket
        six -> colors.gold
        four -> colors.goldDim
        else -> colors.cardRaised
    }
    val foreground = when {
        wicket || six -> MaterialTheme.colorScheme.background
        four -> colors.gold
        else -> colors.soft
    }
    Box(
        modifier = Modifier.size(23.dp).clip(CircleShape).background(background),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = CricketType.Figure, color = foreground)
    }
}

/** A row of label/value pairs down the side of a card. */
@Composable
fun KeyValue(key: String, value: String, modifier: Modifier = Modifier) {
    val colors = LocalCricketColors.current
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(key, style = CricketType.Body, color = colors.soft)
        Text(value, style = CricketType.Figure, color = MaterialTheme.colorScheme.onSurface)
    }
}
