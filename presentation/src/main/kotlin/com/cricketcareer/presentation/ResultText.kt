package com.cricketcareer.presentation

import com.cricketcareer.engine.match.state.MatchResult

/**
 * A result, in the words a scorecard uses.
 *
 * The engine returns a `MatchResult` as structured data — winner, loser,
 * margin — and deliberately no sentence, because a sentence is a rendering
 * decision and would otherwise be made in the one module that must not make
 * them. So the sentence is made here.
 *
 * A tie and a draw are different results and read differently. Conflating them
 * is the sort of thing a cricket supporter notices immediately and a
 * distribution test never will.
 */
fun resultText(result: MatchResult?): String? = when (result) {
    null -> null
    is MatchResult.WonByRuns -> "${result.winner} won by ${result.runs} ${runs(result.runs)}"
    is MatchResult.WonByWickets -> "${result.winner} won by ${result.wickets} ${wickets(result.wickets)}"
    is MatchResult.WonByInnings -> "${result.winner} won by an innings and ${result.runs} ${runs(result.runs)}"
    is MatchResult.Tied -> "Match tied"
    is MatchResult.Drawn -> "Match drawn"
    is MatchResult.NoResult -> "No result — ${result.reason}"
}

private fun runs(n: Int) = if (n == 1) "run" else "runs"

private fun wickets(n: Int) = if (n == 1) "wicket" else "wickets"
