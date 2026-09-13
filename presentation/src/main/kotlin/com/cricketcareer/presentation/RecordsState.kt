package com.cricketcareer.presentation

import com.cricketcareer.engine.career.Appearance
import com.cricketcareer.engine.career.BattingRecord
import com.cricketcareer.engine.career.BowlingRecord
import com.cricketcareer.engine.career.Records
import com.cricketcareer.engine.model.world.LadderLevel

/** One row of the batting table: a format, or the career line. */
data class CareerBattingRow(
    val label: String,
    val matches: String,
    val innings: String,
    val notOuts: String,
    val runs: String,
    val highest: String,
    val average: String,
    val strikeRate: String,
    val hundreds: String,
    val fifties: String,
    /** Set on the career total, so a screen can rule a line above it. */
    val isTotal: Boolean = false,
)

/** One row of the bowling table. */
data class CareerBowlingRow(
    val label: String,
    val matches: String,
    val overs: String,
    val runs: String,
    val wickets: String,
    val best: String,
    val average: String,
    val economy: String,
    val strikeRate: String,
    val fiveFors: String,
    val isTotal: Boolean = false,
)

/** One line of the milestone list. */
data class MilestoneRow(val date: String, val text: String, val context: String)

/** The records screen. */
data class RecordsState(
    val batting: List<CareerBattingRow>,
    val bowling: List<CareerBowlingRow>,
    val byLevel: List<CareerBattingRow>,
    /** Most recent first: a career is read backwards from where it has got to. */
    val milestones: List<MilestoneRow>,
    val hasBowled: Boolean,
)

/**
 * The records screen.
 *
 * Everything here is a *formatting* decision, which is the whole reason this
 * lives in `:presentation` and not in `:app`: whether an average shows two
 * decimal places, whether a player who has never been out shows a dash or a
 * zero, and whether 43 balls reads as "7.1" overs are all things that can be
 * wrong, and all things that can be tested on a bare JDK.
 *
 * The numbers themselves are `Records`' business and are never recomputed here.
 * A screen that did its own arithmetic would eventually disagree with the
 * scorecards, which is the one thing a records screen must never do.
 */
fun recordsState(appearances: List<Appearance>, formatOrder: List<String> = emptyList()): RecordsState {
    val book = Records.of(appearances)

    // Formats in the order the caller's world lists them, with anything it did
    // not name following in the order it was played. A records screen that
    // reorders itself as a player's career widens is disconcerting to read.
    val formats = (formatOrder.filter { it in book.battingByFormat } +
        book.battingByFormat.keys.filterNot { it in formatOrder })

    return RecordsState(
        batting = formats.map { battingRow(it, book.battingByFormat.getValue(it)) } +
            battingRow("Career", book.batting, isTotal = true),
        bowling = formats.mapNotNull { id ->
            book.bowlingByFormat[id]?.takeIf { it.balls > 0 }?.let { bowlingRow(id, it) }
        } + bowlingRow("Career", book.bowling, isTotal = true),
        byLevel = book.battingByLevel.entries
            .sortedBy { it.key.ordinal }
            .map { battingRow(levelName(it.key), it.value) },
        milestones = book.milestones.asReversed().map {
            MilestoneRow(
                date = it.date.toString(),
                text = it.description,
                context = "${it.format.displayName}, ${levelName(it.level)}",
            )
        },
        hasBowled = book.bowling.balls > 0,
    )
}

private fun battingRow(label: String, record: BattingRecord, isTotal: Boolean = false) = CareerBattingRow(
    label = label,
    matches = "${record.matches}",
    innings = "${record.innings}",
    notOuts = "${record.notOuts}",
    runs = "${record.runs}",
    highest = record.highestScoreText,
    average = twoPlaces(record.average),
    strikeRate = twoPlaces(record.strikeRate),
    hundreds = "${record.hundreds}",
    fifties = "${record.fifties}",
    isTotal = isTotal,
)

private fun bowlingRow(label: String, record: BowlingRecord, isTotal: Boolean = false) = CareerBowlingRow(
    label = label,
    matches = "${record.matches}",
    overs = overs(record.balls),
    runs = "${record.runs}",
    wickets = "${record.wickets}",
    best = record.bestText,
    average = twoPlaces(record.average),
    economy = twoPlaces(record.economy),
    strikeRate = twoPlaces(record.strikeRate),
    fiveFors = "${record.fiveWicketHauls}",
    isTotal = isTotal,
)

/**
 * Overs as a scorecard writes them: 7.1 is seven overs and one ball, not seven
 * and a tenth. The dot is a separator, not a decimal point — which is why this
 * cannot be a division.
 */
internal fun overs(balls: Int): String = "${balls / 6}.${balls % 6}"

/** An average, or a dash. A player with no average has no average, not a zero. */
private fun twoPlaces(value: Double?): String = value?.let { "%.2f".format(it) } ?: "-"

private fun levelName(level: LadderLevel): String = level.displayName
