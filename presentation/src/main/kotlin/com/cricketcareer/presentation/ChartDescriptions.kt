package com.cricketcareer.presentation

import com.cricketcareer.engine.match.delivery.LengthBand
import com.cricketcareer.engine.match.delivery.LineBand
import kotlin.math.roundToInt

/**
 * The charts, in words.
 *
 * A worm graph is four hundred points of pure shape and nothing a screen reader
 * can say about it. The usual answer — a content description reading "worm
 * chart" — tells a blind player that a chart exists and nothing about the
 * cricket in it, which is the same as telling him nothing.
 *
 * So each chart gets a *summary of what it shows*, written the way a
 * commentator would describe it: where the scoring came from, which overs cost
 * runs, where the bowler was landing it, which side of the wicket the runs went.
 * The sighted player reads the shape off the picture and the blind player reads
 * the same facts off this. Neither is a reduced version of the other.
 *
 * This is `:presentation`'s job rather than `:app`'s for the usual reason: it
 * is a decision about what the screen says, it is full of cricket, and it is
 * testable on a bare JDK instead of by squinting at TalkBack in an emulator.
 */
object ChartDescriptions {

    /** An over that cost this many or more is worth naming. */
    private const val EXPENSIVE_OVER = 12

    /** ...and this many or fewer is worth naming as a maiden-ish over. */
    private const val CHEAP_OVER = 2

    /**
     * Every chart on the analysis screen, described.
     *
     * Returned as a map keyed by the chart so a screen can attach each one to
     * the right picture without knowing how any of them were written.
     */
    fun of(state: ChartState): Map<String, String> = buildMap {
        put("worm", worm(state.worm))
        put("manhattan", manhattan(state.manhattan))
        put("pitchMap", pitchMap(state.pitchMap))
        put("wagonWheel", wagonWheel(state.wagonWheel))
    }

    /** "142 for 4 off 20 overs. Fastest scoring between overs 15 and 20, at 11.2 an over." */
    fun worm(points: List<WormPoint>): String {
        val last = points.lastOrNull() ?: return "No cricket yet."
        if (last.legalBalls == 0) return "No cricket yet."
        val overs = overs(last.legalBalls)
        val rate = last.runs * 6.0 / last.legalBalls

        // The best five-over stretch, which is the thing the shape of a worm
        // actually shows: where the innings got away or stalled.
        val window = 30
        var bestStart = 0
        var bestRuns = -1
        points.forEach { point ->
            val earlier = points.lastOrNull { it.legalBalls <= point.legalBalls - window } ?: return@forEach
            val gained = point.runs - earlier.runs
            if (gained > bestRuns) {
                bestRuns = gained
                bestStart = earlier.legalBalls
            }
        }

        val opening = "%d runs off %s overs, %.2f an over.".format(last.runs, overs, rate)
        if (bestRuns < 0) return opening
        return opening + " Fastest scoring from over %d to over %d, %d runs in five.".format(
            bestStart / 6 + 1, (bestStart + window) / 6, bestRuns,
        )
    }

    /** "Most expensive over the 18th, 19 runs. Three overs went for 2 or fewer." */
    fun manhattan(bars: List<OverBar>): String {
        if (bars.isEmpty()) return "No overs bowled."
        val worst = bars.maxBy { it.runs }
        val cheap = bars.count { it.runs <= CHEAP_OVER }
        val wicketOvers = bars.filter { it.wickets > 0 }
        val expensive = bars.count { it.runs >= EXPENSIVE_OVER }

        return buildString {
            append("%d overs. Most expensive the %s, %d runs.".format(bars.size, ordinal(worst.over), worst.runs))
            if (expensive > 1) append(" %d overs went for %d or more.".format(expensive, EXPENSIVE_OVER))
            if (cheap > 0) append(" %d for %d or fewer.".format(cheap, CHEAP_OVER))
            if (wicketOvers.isNotEmpty()) {
                append(
                    " Wickets in the %s.".format(
                        wicketOvers.joinToString(", ") { ordinal(it.over) },
                    ),
                )
            }
        }
    }

    /**
     * "Mostly good length on off stump. 14 per cent short, 6 per cent full."
     *
     * Lengths and lines in words rather than metres: a player reads "back of a
     * length outside off", not "7.4 metres, 0.6 metres".
     */
    fun pitchMap(points: List<PitchMapPoint>): String {
        if (points.isEmpty()) return "No deliveries to show."
        val lengths = points.groupingBy { lengthWord(it) }.eachCount()
        val lines = points.groupingBy { lineWord(it.lineMetres) }.eachCount()
        val commonestLength = lengths.maxBy { it.value }
        val commonestLine = lines.maxBy { it.value }

        return "%d deliveries. Mostly %s, %s. %s.".format(
            points.size,
            commonestLength.key,
            commonestLine.key,
            lengths.entries
                .sortedByDescending { it.value }
                .take(3)
                .joinToString(", ") { "%d%% %s".format(percent(it.value, points.size), it.key) },
        )
    }

    /** "38 scoring shots. Strongest through midwicket. 62 per cent on the leg side." */
    fun wagonWheel(spokes: List<WagonSpoke>): String {
        val scoring = spokes.filter { it.runs > 0 }
        if (scoring.isEmpty()) return "No scoring shots to show."
        val runs = scoring.sumOf { it.runs }
        val bySector = scoring.groupBy { sector(it.azimuthDegrees) }
        val strongest = bySector.maxBy { entry -> entry.value.sumOf { it.runs } }
        // The off side is everything the batter plays square of or in front of
        // the wicket on the side his bat comes down; the frame is already
        // mirrored for a left-hander, so the same split holds either way.
        val legSideRuns = scoring.filter { isLegSide(it.azimuthDegrees) }.sumOf { it.runs }

        return "%d runs off %d scoring shots. Strongest through %s, %d runs. %d%% on the leg side.".format(
            runs, scoring.size, strongest.key, strongest.value.sumOf { it.runs },
            percent(legSideRuns, runs),
        )
    }

    private fun overs(legalBalls: Int): String = "${legalBalls / 6}.${legalBalls % 6}"

    private fun percent(part: Int, whole: Int): Int =
        if (whole == 0) 0 else (part * 100.0 / whole).roundToInt()

    /** "18th", "1st", "22nd" — and yes, the teens are the special case. */
    internal fun ordinal(n: Int): String {
        val suffix = when {
            n % 100 in 11..13 -> "th"
            n % 10 == 1 -> "st"
            n % 10 == 2 -> "nd"
            n % 10 == 3 -> "rd"
            else -> "th"
        }
        return "$n$suffix"
    }

    /**
     * Where it pitched and where it passed the stumps, in a bowler's words.
     *
     * The engine already owns both vocabularies, boundaries and all, so these
     * read them rather than inventing a second set. A description that called
     * 7 metres "back of a length" while the engine called it a good length
     * would be a screen reader disagreeing with the commentary beside it.
     *
     * A full toss never pitched, so it has no length to describe: the point
     * carries a flag for exactly that case and it is named rather than plotted
     * as a negative distance.
     */
    internal fun lengthWord(point: PitchMapPoint): String =
        if (point.fullToss) LengthBand.FULL_TOSS.displayName
        else LengthBand.of(point.lengthMetres, spin = false).displayName

    internal fun lineWord(metres: Double): String = LineBand.of(metres).displayName

    /** Eight sectors, named as a wagon wheel names them. */
    internal fun sector(azimuthDegrees: Double): String {
        val bearing = ((azimuthDegrees % 360.0) + 360.0) % 360.0
        // Zero is straight down the ground and the bearing increases towards
        // the off side, which is the frame FieldPosition is already in: cover
        // sits at 56 degrees, point at 96, the keeper at 180, square leg at 273
        // and midwicket at 306. Naming these the other way round is an easy and
        // completely invisible mistake, so the sectors are checked against the
        // field positions themselves in the tests.
        return when {
            bearing < 22.5 || bearing >= 337.5 -> "straight"
            bearing < 67.5 -> "cover"
            bearing < 112.5 -> "point"
            bearing < 157.5 -> "third man"
            bearing < 202.5 -> "behind the wicket"
            bearing < 247.5 -> "fine leg"
            bearing < 292.5 -> "square leg"
            else -> "midwicket"
        }
    }

    /**
     * Which side of the wicket it went.
     *
     * The bearing runs clockwise from straight down the ground through the off
     * side, so everything past the keeper at 180 degrees is leg side. The frame
     * is already mirrored for a left-hander, so the same split holds either way.
     */
    private fun isLegSide(azimuthDegrees: Double): Boolean {
        val bearing = ((azimuthDegrees % 360.0) + 360.0) % 360.0
        return bearing > 180.0
    }
}
