package com.cricketcareer.engine.match.sim

import com.cricketcareer.engine.config.RainTuning
import com.cricketcareer.engine.match.delivery.Weather
import com.cricketcareer.engine.model.world.MatchFormat
import com.cricketcareer.engine.rng.SimRandom

/**
 * One stoppage in a limited-overs match.
 *
 * Expressed as "after this many completed overs of this innings, strike this
 * many overs off what is left" — which is what a reserve-day-less playing
 * condition actually does, and what the resource table needs to know.
 */
data class Stoppage(
    /** 1 or 2. */
    val innings: Int,
    /** Completed overs of that innings when the players go off. 0 is before a ball. */
    val afterOvers: Int,
    /** Overs struck off the remainder. */
    val oversLost: Int,
) {
    init {
        require(innings in 1..2) { "innings $innings" }
        require(afterOvers >= 0) { "afterOvers $afterOvers" }
        require(oversLost >= 0) { "oversLost $oversLost" }
    }
}

/** Everything the weather is going to do to this match, drawn before a ball is bowled. */
data class RainPlan(
    val stoppages: List<Stoppage>,
    /** The match is off without a ball bowled. */
    val isWashout: Boolean = false,
) {
    fun inInnings(number: Int): List<Stoppage> =
        stoppages.filter { it.innings == number }.sortedBy { it.afterOvers }

    val isDry: Boolean get() = stoppages.isEmpty() && !isWashout

    companion object {
        val DRY: RainPlan = RainPlan(emptyList())
    }
}

/**
 * Whether it rains, when, and how much is lost.
 *
 * Drawn **in full before the match starts**, from the conditions stream, for
 * the determinism contract: a plan drawn ball by ball would make the weather
 * depend on how many draws the batting had taken, so changing the shot model
 * would change the forecast. Drawing it up front costs a handful of numbers and
 * means the rain in match 4,912 is the same rain every time it is replayed.
 *
 * Nothing here decides a result. It decides how much cricket there is; what
 * that is worth is `DuckworthLewis`'s job.
 */
object RainModel {

    fun plan(
        format: MatchFormat,
        weather: Weather,
        random: SimRandom,
        tuning: RainTuning = RainTuning(),
    ): RainPlan {
        val overs = format.oversPerInnings ?: return RainPlan.DRY
        if (!format.usesDls) return RainPlan.DRY

        // The same cloud and humidity the swing model reads, so the day the
        // ball hoops is the day the covers come on. Squared, because rain needs
        // both together: a muggy clear day and an overcast dry one are each far
        // safer than the average of the two would suggest.
        val wetness = (weather.cloudCover * 0.7 + weather.humidity * 0.3).coerceIn(0.0, 1.0)
        if (!random.chance(tuning.chanceBase + tuning.chanceWeather * wetness * wetness)) return RainPlan.DRY

        if (random.chance(tuning.washoutChance)) return RainPlan(emptyList(), isWashout = true)

        val stoppages = mutableListOf(draw(format, overs, random, tuning))
        // The second draw is always taken, so that whether it rains twice does
        // not change how many numbers the stream has handed out. A stage that
        // consumes a variable count would make every later draw in the match
        // depend on the weather.
        val second = draw(format, overs, random, tuning)
        if (random.chance(tuning.secondStoppageChance)) stoppages += second

        return RainPlan(stoppages.sortedWith(compareBy({ it.innings }, { it.afterOvers })))
    }

    private fun draw(format: MatchFormat, overs: Int, random: SimRandom, tuning: RainTuning): Stoppage {
        // Which innings, and how far into it. Uniform across the match: rain
        // does not prefer a session.
        val innings = if (random.chance(0.5)) 1 else 2
        val afterOvers = (random.nextDouble() * overs).toInt().coerceIn(0, overs)
        val remaining = overs - afterOvers

        // Severity as a product of uniform draws: the usual interruption costs
        // a few overs, and the washout is the tail rather than the middle.
        var severity = 1.0
        repeat(tuning.severityDraws) { severity *= random.nextDouble() }
        val lost = (severity * remaining * tuning.maximumShareLost).toInt().coerceIn(0, remaining)

        return Stoppage(innings = innings, afterOvers = afterOvers, oversLost = lost)
    }
}
