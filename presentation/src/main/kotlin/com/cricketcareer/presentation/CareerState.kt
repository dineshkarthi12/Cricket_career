package com.cricketcareer.presentation

import com.cricketcareer.engine.career.SeasonRecord
import com.cricketcareer.engine.model.player.Attribute
import com.cricketcareer.engine.model.world.Pitch
import com.cricketcareer.engine.match.delivery.Weather

/** One season on the career table. */
data class CareerSeasonRow(
    val season: String,
    val matches: Int,
    val runs: Int,
    val highestScore: String,
    val average: String,
    val fifties: Int,
    val hundreds: Int,
    val wickets: Int,
)

/** The career screen. */
data class CareerTableState(
    val rows: List<CareerSeasonRow>,
    val totals: CareerSeasonRow,
)

/**
 * A career table.
 *
 * [seasonLabels] names the seasons, because the engine's `SeasonRecord` does
 * not know what year it was — a season is a list of fixtures and the calendar
 * belongs to whoever built it.
 */
fun careerTableState(records: List<SeasonRecord>, seasonLabels: List<String>): CareerTableState {
    require(seasonLabels.size == records.size) {
        "${records.size} seasons but ${seasonLabels.size} labels"
    }
    val rows = records.mapIndexed { index, record -> row(seasonLabels[index], record) }
    return CareerTableState(rows = rows, totals = totals(records))
}

private fun row(label: String, record: SeasonRecord) = CareerSeasonRow(
    season = label,
    matches = record.matches,
    runs = record.runs,
    // A best score is marked not out if the innings it came in was unbeaten.
    highestScore = record.appearances
        .maxByOrNull { it.runs }
        ?.let { if (it.out) "${it.runs}" else "${it.runs}*" }
        ?: "-",
    average = record.battingAverage?.let { "%.2f".format(it) } ?: "-",
    fifties = record.fifties,
    hundreds = record.hundreds,
    wickets = record.wickets,
)

/**
 * Career totals.
 *
 * Runs and dismissals are summed and divided *once*. Averaging the seasonal
 * averages would weight a two-innings season the same as a twenty-innings one
 * and produce a career average that appears nowhere in the record.
 */
private fun totals(records: List<SeasonRecord>): CareerSeasonRow {
    val runs = records.sumOf { it.runs }
    val dismissals = records.sumOf { it.dismissals }
    val best = records.flatMap { it.appearances }.maxByOrNull { it.runs }
    return CareerSeasonRow(
        season = "Career",
        matches = records.sumOf { it.matches },
        runs = runs,
        highestScore = best?.let { if (it.out) "${it.runs}" else "${it.runs}*" } ?: "-",
        average = if (dismissals == 0) "-" else "%.2f".format(runs.toDouble() / dismissals),
        fifties = records.sumOf { it.fifties },
        hundreds = records.sumOf { it.hundreds },
        wickets = records.sumOf { it.wickets },
    )
}

/** One labelled gauge, 0 to 1. */
data class Gauge(val name: String, val value: Double) {
    init {
        require(value.isFinite() && value in 0.0..1.0) { "$name = $value must be in 0..1" }
    }

    /** "0.72" — the engine's own scale, shown as it is. */
    val display: String get() = "%.2f".format(value)
}

/** The pitch report. */
data class PitchReportState(val soil: String, val summary: String, val gauges: List<Gauge>)

/**
 * The pitch report.
 *
 * Every gauge is a field of the engine's own [Pitch], under the name a report
 * would use. Nothing is derived and nothing is combined: the engine deliberately
 * has no "batting friendliness" number, because two parts of the code could then
 * disagree about the same pitch, and inventing one here would be the same
 * mistake one module up.
 */
fun pitchReportState(pitch: Pitch): PitchReportState = PitchReportState(
    soil = pitch.soilType.name.lowercase().replaceFirstChar { it.uppercase() },
    summary = describePitch(pitch),
    gauges = listOf(
        Gauge("Hardness", pitch.hardness),
        Gauge("Grass cover", pitch.grassCover),
        Gauge("Moisture", pitch.moisture),
        Gauge("Pace", pitch.pace),
        Gauge("Bounce", pitch.bounce),
        Gauge("Evenness", pitch.evenness),
        Gauge("Turn", pitch.turn),
        Gauge("Seam grip", pitch.gripSeam),
        Gauge("Cracks", pitch.cracks),
        Gauge("Abrasion", pitch.abrasion),
        Gauge("Deterioration", pitch.deterioration),
    ),
)

/** Weather and light. */
data class ConditionsState(val summary: String, val gauges: List<Gauge>)

fun conditionsState(weather: Weather): ConditionsState = ConditionsState(
    summary = describeWeather(weather),
    gauges = listOf(
        Gauge("Humidity", weather.humidity),
        Gauge("Cloud cover", weather.cloudCover),
        Gauge("Light", weather.lightQuality),
        Gauge("Dew", weather.dew),
        Gauge("Outfield wear", weather.outfieldAbrasion),
    ),
)

/**
 * What the strip will do, in the words a pitch report uses.
 *
 * Assembled from what is actually there rather than picked from a list by a
 * single "type" field, for the same reason the commentary generator is: a
 * canned sentence is right once and wrong the other nine times.
 */
private fun describePitch(pitch: Pitch): String {
    val clauses = buildList {
        add(
            when {
                pitch.hardness >= 0.66 -> "Hard"
                pitch.hardness >= 0.33 -> "Firm enough"
                else -> "Soft"
            },
        )
        if (pitch.grassCover >= 0.6) add("well grassed")
        if (pitch.moisture >= 0.55) add("with moisture still in it")
        if (pitch.cracks >= 0.6) add("and already cracking")
        if (pitch.turn >= 0.65) add("it will turn")
        if (pitch.gripSeam >= 0.65) add("there is something in it for the seamers")
        if (pitch.pace <= 0.3) add("the ball will come on slowly")
    }
    return clauses.joinToString(", ").replaceFirstChar { it.uppercase() } + "."
}

private fun describeWeather(weather: Weather): String = when {
    weather.lightQuality <= 0.4 -> "The light is poor."
    weather.cloudCover >= 0.7 && weather.humidity >= 0.65 -> "Heavy and overcast. It should swing."
    weather.dew >= 0.5 -> "Dew about. The ball will skid on and the spinners will struggle to grip it."
    weather.cloudCover <= 0.25 -> "Clear and bright."
    else -> "Fair."
}

/** Attribute names, for a screen that wants them without reaching into the engine's enum. */
fun attributeName(attribute: Attribute): String = attribute.displayName
