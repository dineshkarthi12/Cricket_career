package com.cricketcareer.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cricketcareer.app.ui.screens.MatchControls
import com.cricketcareer.app.ui.screens.MatchCentreScreen
import com.cricketcareer.app.ui.screens.ScorecardScreen
import com.cricketcareer.app.ui.theme.CricketType
import com.cricketcareer.app.ui.theme.LocalCricketColors
import com.cricketcareer.presentation.demo.DemoMatch
import com.cricketcareer.presentation.scorecardState
import kotlinx.coroutines.delay

/** Which screen is showing. Navigation proper arrives with the career layer. */
private enum class Screen(val label: String) { MATCH("Live"), CARD("Scorecard") }

/**
 * The app.
 *
 * Everything cricket-shaped on this screen comes out of `:presentation`: the
 * match is simulated there, the playback is a value there, and every number is
 * read off a state object built there. What is left here is layout, colour and
 * which of two screens is showing — which is the whole point of the split.
 *
 * The seed is fixed so the demo match is the same every launch. That is not a
 * placeholder for "random later"; it is the determinism contract, and it means
 * a screenshot of something going wrong is a reproducible bug report.
 */
@Composable
fun CricketDynastyApp() {
    // Simulated once and remembered. A T20 is a few hundred deliveries and the
    // engine runs thousands of matches a second, so this costs a frame at
    // startup. When :data arrives the match comes from a save file instead.
    val demo = remember { DemoMatch.simulate(seed = DEMO_SEED) }

    var session by remember { mutableStateOf(demo.session) }
    var playing by remember { mutableStateOf(false) }
    var screen by remember { mutableStateOf(Screen.MATCH) }

    // One ball at a time while playing. Keyed on the cursor as well as the
    // flag, so each delivery schedules the next rather than the whole innings
    // running off in a single effect that nothing can interrupt.
    LaunchedEffect(playing, session.cursor) {
        if (playing) {
            if (session.isFinished) {
                playing = false
            } else {
                delay(BALL_INTERVAL_MILLIS)
                session = session.advance()
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Header(
            title = "${demo.homeTeam} v ${demo.awayTeam}",
            subtitle = session.state.result ?: "Twenty20 · City Ground, Chennai",
        )
        Tabs(current = screen, onSelect = { screen = it })

        Box(modifier = Modifier.weight(1f)) {
            when (screen) {
                Screen.MATCH -> MatchCentreScreen(
                    state = session.state,
                    homeTeam = demo.homeTeam,
                    awayTeam = demo.awayTeam,
                    firstInningsScore = demo.firstInnings?.display.orEmpty(),
                    controls = MatchControls(
                        onPlayPause = { playing = !playing },
                        onNextBall = { playing = false; session = session.advance() },
                        onNextWicket = { playing = false; session = session.toNextWicket() },
                        onSkipToEnd = { playing = false; session = session.toEnd() },
                        playing = playing,
                        enabled = !session.isFinished,
                    ),
                )

                Screen.CARD -> {
                    val innings = demo.firstInnings
                    if (innings == null) {
                        Text(
                            text = "No innings yet.",
                            style = CricketType.Body,
                            color = LocalCricketColors.current.soft,
                            modifier = Modifier.padding(16.dp),
                        )
                    } else {
                        ScorecardScreen(state = scorecardState(innings, demo.names))
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(title: String, subtitle: String) {
    val colors = LocalCricketColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.headerBottom)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(
            text = title.uppercase(),
            style = CricketType.Label,
            color = colors.gold,
            fontWeight = FontWeight.Bold,
        )
        Text(text = subtitle, style = CricketType.Body, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun Tabs(current: Screen, onSelect: (Screen) -> Unit) {
    val colors = LocalCricketColors.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(11.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Screen.entries.forEach { screen ->
            val selected = screen == current
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(7.dp))
                    .background(if (selected) MaterialTheme.colorScheme.primary else colors.cardRaised)
                    .clickable { onSelect(screen) }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = screen.label.uppercase(),
                    style = CricketType.Label,
                    color = if (selected) MaterialTheme.colorScheme.onPrimary else colors.soft,
                )
            }
        }
    }
}

/** Fixed, so the demo match is the same every launch. See the note above. */
private const val DEMO_SEED = 77L

/** Roughly the rhythm of a real over when you are watching rather than playing. */
private const val BALL_INTERVAL_MILLIS = 850L
