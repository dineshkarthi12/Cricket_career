package com.cricketcareer.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.cricketcareer.app.ui.components.Panel
import com.cricketcareer.app.ui.components.SectionLabel
import com.cricketcareer.app.ui.theme.CricketType
import com.cricketcareer.app.ui.theme.LocalCricketColors
import com.cricketcareer.presentation.BattingRow
import com.cricketcareer.presentation.BowlingRow
import com.cricketcareer.presentation.ScorecardState

/**
 * The scorecard.
 *
 * Column weights rather than fixed widths, so the card holds together on a
 * small phone and on a tablet without two layouts. The name column takes what
 * is left over, because it is the only one whose content varies in length.
 */
@Composable
fun ScorecardScreen(state: ScorecardState, modifier: Modifier = Modifier) {
    val colors = LocalCricketColors.current
    LazyColumn(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        item {
            Panel {
                Text(
                    text = "${state.battingTeam}  ${state.total}",
                    style = CricketType.Score,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }

        item { BattingHeader() }
        items(state.batting, key = { it.name }) { BattingRowView(it) }

        item {
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 23.dp, vertical = 7.dp)) {
                Text("Extras ${state.extrasBreakdown}", style = CricketType.Body, color = colors.soft)
                Text(
                    text = "${state.extrasTotal}",
                    style = CricketType.Figure,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.End,
                )
            }
        }

        if (state.fallOfWickets.isNotEmpty()) {
            item {
                Panel {
                    SectionLabel("Fall of wickets", Modifier.padding(12.dp, 10.dp, 12.dp, 2.dp))
                    Text(
                        text = state.fallOfWickets.joinToString(",  "),
                        style = CricketType.Commentary,
                        color = colors.soft,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
        }

        item { BowlingHeader() }
        items(state.bowling, key = { it.name }) { BowlingRowView(it) }
    }
}

@Composable
private fun BattingHeader() {
    val colors = LocalCricketColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.headerBottom)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SectionLabel("Batter", Modifier.weight(1f))
        listOf("R", "B", "4s", "6s", "SR").forEachIndexed { index, label ->
            SectionLabel(label, Modifier.weight(if (index == 4) 0.7f else 0.4f))
        }
    }
}

@Composable
private fun BattingRowView(row: BattingRow) {
    val colors = LocalCricketColors.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(row.name, style = CricketType.Body, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            Text(row.howOut, style = CricketType.Label, color = colors.muted)
        }
        Text(
            text = row.runsDisplay,
            style = CricketType.Figure,
            fontWeight = FontWeight.Bold,
            // A not-out batter's score is the one the eye should find first.
            color = if (row.notOut && row.batted) colors.good else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(0.4f),
            textAlign = TextAlign.End,
        )
        Figure("${row.balls}", 0.4f)
        Figure("${row.fours}", 0.4f)
        Figure("${row.sixes}", 0.4f)
        Figure(row.strikeRate, 0.7f)
    }
}

@Composable
private fun BowlingHeader() {
    val colors = LocalCricketColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.headerBottom)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SectionLabel("Bowler", Modifier.weight(1f))
        listOf("O", "M", "R", "W", "Econ").forEachIndexed { index, label ->
            SectionLabel(label, Modifier.weight(if (index == 4) 0.7f else 0.4f))
        }
    }
}

@Composable
private fun BowlingRowView(row: BowlingRow) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = row.name,
            style = CricketType.Body,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Figure(row.overs, 0.4f)
        Figure("${row.maidens}", 0.4f)
        Figure("${row.runs}", 0.4f)
        Figure("${row.wickets}", 0.4f)
        Figure(row.economy, 0.7f)
    }
}

@Composable
private fun RowScope.Figure(text: String, weight: Float) {
    Text(
        text = text,
        style = CricketType.Figure,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.weight(weight),
        textAlign = TextAlign.End,
    )
}
