package com.cricketcareer.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.cricketcareer.app.ui.components.Panel
import com.cricketcareer.app.ui.components.Pip
import com.cricketcareer.app.ui.components.SectionLabel
import com.cricketcareer.app.ui.theme.CricketType
import com.cricketcareer.app.ui.theme.LocalCricketColors
import com.cricketcareer.presentation.FeedBall
import com.cricketcareer.presentation.MatchCentreState

/** What the user can do to a match in progress. */
data class MatchControls(
    val onPlayPause: () -> Unit,
    val onNextBall: () -> Unit,
    val onNextWicket: () -> Unit,
    val onSkipToEnd: () -> Unit,
    val playing: Boolean,
    val enabled: Boolean,
)

/**
 * The match centre.
 *
 * Everything on this screen is read off [state]. There is no arithmetic here:
 * the required rate, the balls remaining, which chip a delivery gets and what
 * order the feed is in were all decided in :presentation, where they are
 * tested. See docs/UI.md.
 */
@Composable
fun MatchCentreScreen(
    state: MatchCentreState,
    homeTeam: String,
    awayTeam: String,
    firstInningsScore: String,
    controls: MatchControls,
    modifier: Modifier = Modifier,
) {
    val colors = LocalCricketColors.current
    LazyColumn(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        item {
            Panel {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        InningsScore(homeTeam, firstInningsScore, "", Modifier.weight(1f), highlight = false)
                        InningsScore(awayTeam, state.score, state.overs, Modifier.weight(1f), highlight = true)
                    }
                }
                state.chase?.let { chase ->
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = "$awayTeam need ${chase.runsNeeded} from ${chase.ballsLeft} balls",
                            style = CricketType.Body,
                            color = colors.gold,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                            state.currentRunRate?.let {
                                Text("CRR %.1f".format(it), style = CricketType.Figure, color = colors.soft)
                            }
                            chase.requiredRate?.let {
                                Text("RRR %.1f".format(it), style = CricketType.Figure, color = colors.soft)
                            }
                        }
                    }
                }
            }
        }

        item { ControlBar(controls) }

        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                SectionLabel("Over ${state.overNumber}")
                state.thisOver.forEach { Pip(it.label, it.wicket, it.four, it.six) }
            }
        }

        state.result?.let { result ->
            item {
                Text(
                    text = result,
                    style = CricketType.Body,
                    color = colors.good,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                )
            }
        }

        // A stable key per delivery, so recomposition does not re-animate the
        // whole feed every time one ball is added to the top of it. Over alone
        // is not unique - two deliveries share "4.2" when one of them is a wide.
        items(state.feed, key = { "${it.over}|${it.text}" }) { FeedRow(it) }
    }
}

@Composable
private fun InningsScore(team: String, score: String, overs: String, modifier: Modifier, highlight: Boolean) {
    val colors = LocalCricketColors.current
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        SectionLabel(team)
        Text(
            text = score,
            style = CricketType.Score,
            color = if (highlight) colors.gold else MaterialTheme.colorScheme.onSurface,
        )
        if (overs.isNotEmpty()) {
            Text("$overs overs", style = CricketType.Figure, color = colors.muted)
        }
    }
}

@Composable
private fun ControlBar(controls: MatchControls) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 11.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        ControlButton(
            label = if (controls.playing) "Pause" else "Play",
            primary = true,
            enabled = controls.enabled,
            onClick = controls.onPlayPause,
            modifier = Modifier.weight(1.2f),
        )
        ControlButton("Ball", false, controls.enabled, controls.onNextBall, Modifier.weight(1f))
        ControlButton("Wicket", false, controls.enabled, controls.onNextWicket, Modifier.weight(1f))
        ControlButton("End", false, controls.enabled, controls.onSkipToEnd, Modifier.weight(1f))
    }
}

@Composable
private fun ControlButton(
    label: String,
    primary: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalCricketColors.current
    val background = if (primary) colors.gold else colors.cardRaised
    val foreground = if (primary) MaterialTheme.colorScheme.background else colors.soft
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(7.dp))
            .background(if (enabled) background else colors.cardRaised)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label.uppercase(),
            style = CricketType.Label,
            color = if (enabled) foreground else colors.muted,
        )
    }
}

@Composable
private fun FeedRow(ball: FeedBall) {
    val colors = LocalCricketColors.current
    val background = when {
        ball.wicket -> MaterialTheme.colorScheme.errorContainer
        ball.boundary -> colors.cardRaised
        else -> MaterialTheme.colorScheme.surface
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 11.dp, vertical = 3.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(background)
            .padding(9.dp),
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Text(ball.over, style = CricketType.Figure, color = colors.muted)
        Text(
            text = ball.text,
            style = CricketType.Commentary,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Pip(ball.chip, ball.wicket, ball.chip == "4", ball.chip == "6")
    }
}
