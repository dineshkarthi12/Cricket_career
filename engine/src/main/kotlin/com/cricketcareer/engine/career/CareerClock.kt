package com.cricketcareer.engine.career

import com.cricketcareer.engine.config.CareerTuning
import com.cricketcareer.engine.model.player.Player
import com.cricketcareer.engine.model.player.PlayerId
import com.cricketcareer.engine.rng.SimRandom
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * What a player did with a year, as far as development is concerned.
 *
 * Supplied by the caller rather than accumulated here, because the clock does
 * not know what matches exist — that is the season's job. Both fields are
 * [0, 1].
 */
data class SeasonExposure(
    /**
     * Fraction of a full season's minutes spent on the field *at or above the
     * player's own standard*. A year of second-XI cricket is a low number and
     * develops almost nobody, which is the mechanism that makes getting picked
     * matter rather than merely feel nice.
     */
    val minutes: Double = 0.0,

    /** Coaching quality of the environment he spent the year in. */
    val coaching: Double = 0.5,
) {
    init {
        require(minutes.isFinite() && minutes in 0.0..1.0) { "minutes $minutes must be in 0..1" }
        require(coaching.isFinite() && coaching in 0.0..1.0) { "coaching $coaching must be in 0..1" }
    }

    companion object {
        /** A player who did not play at all. */
        val NONE: SeasonExposure = SeasonExposure()
    }
}

/**
 * The career clock.
 *
 * A career advances in days, not matches, so that a congested franchise season
 * and a quiet county summer diverge naturally rather than because someone wrote
 * a rule about it. Rehab, recovery, rustiness and ageing all key off elapsed
 * days.
 *
 * The daily processes run in a fixed order, listed in docs/CAREER_MODEL.md §2.
 * The order is part of the contract: it is what stops the result depending on
 * map iteration, and it is why a player who finishes rehab today also recovers
 * today rather than tomorrow.
 */
class CareerClock(private val tuning: CareerTuning = CareerTuning.DEFAULT) {

    /**
     * One day for one player.
     *
     * [exposure] is only read on a birthday. Passing it every day rather than
     * only on the birthday keeps the caller from having to know when birthdays
     * are, which is exactly the kind of duplicated rule that goes stale.
     */
    fun advanceDay(
        player: Player,
        date: LocalDate,
        exposure: SeasonExposure,
        random: SimRandom,
    ): Player {
        var current = player

        // 1. Rehab. Done first so that a player whose last rehab day is today
        //    is available today, not tomorrow.
        val resolving = current.state.injury
        current = current.copy(state = InjuryModel.rehabDay(current.state))
        if (resolving != null && current.state.injury == null && InjuryModel.leavesPermanentDamage(resolving)) {
            // The event that ends careers. Applied on resolving rather than on
            // occurring, so a career summary can date it to the comeback.
            current = current.copy(
                attributes = Ageing.applyPermanentDamage(
                    current.attributes,
                    tuning.injury.severePermanentDamage,
                ),
            )
        }

        // 2. Fatigue recovery.
        current = current.copy(
            state = FatigueModel.rest(current.state, days = 1, current.attributes, tuning.fatigue),
        )

        // 3 and 4. Sharpness drift and the ebb of form and confidence.
        current = current.copy(state = FormModel.restDay(current.state, tuning.form))

        // 5. The birthday. Ageing is applied once, on the day, rather than
        //    smeared across 365 - it is cheaper, it is testable, and a career
        //    summary can say "he lost a yard the year he turned 33" and mean it.
        if (isBirthday(current, date)) {
            val age = ageOn(current, date)
            current = current.copy(
                attributes = Ageing.ageOneYear(
                    player = current,
                    age = age,
                    exposure = exposure.minutes,
                    coaching = exposure.coaching,
                    random = random,
                    tuning = tuning.ageing,
                ),
            )
        }
        return current
    }

    /**
     * Advance a whole world from [from] to [to], exclusive of [from] and
     * inclusive of [to].
     *
     * Players are processed in list order every day and each draws from the
     * shared ageing stream, so the result depends on the order the caller hands
     * them over. That is deliberate and it is the caller's job to keep that
     * order stable — a save file stores the squad as a list for exactly this
     * reason.
     */
    fun advance(
        players: List<Player>,
        from: LocalDate,
        to: LocalDate,
        exposure: Map<PlayerId, SeasonExposure> = emptyMap(),
        random: SimRandom,
    ): List<Player> {
        require(!to.isBefore(from)) { "cannot advance backwards: $from to $to" }
        var current = players
        var date = from
        while (date.isBefore(to)) {
            date = date.plusDays(1)
            val today = date
            current = current.map { player ->
                advanceDay(player, today, exposure[player.id] ?: SeasonExposure.NONE, random)
            }
        }
        return current
    }

    /** Age in completed years on [date]. */
    fun ageOn(player: Player, date: LocalDate): Int =
        ChronoUnit.YEARS.between(player.dateOfBirth, date).toInt()

    /**
     * True on the player's birthday.
     *
     * 29 February is treated as 1 March in a non-leap year, so that a player
     * born on a leap day ages every year rather than once in four. Getting this
     * wrong would be invisible for decades and then produce one immortal
     * cricketer.
     */
    fun isBirthday(player: Player, date: LocalDate): Boolean {
        val dob = player.dateOfBirth
        if (dob.monthValue == date.monthValue && dob.dayOfMonth == date.dayOfMonth) return true
        val leapDayBorn = dob.monthValue == 2 && dob.dayOfMonth == 29
        val firstOfMarch = date.monthValue == 3 && date.dayOfMonth == 1
        return leapDayBorn && firstOfMarch && !date.isLeapYear
    }
}
