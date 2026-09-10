package com.cricketcareer.engine.fixtures

import com.cricketcareer.engine.model.player.Attributes
import com.cricketcareer.engine.model.player.BattingHand
import com.cricketcareer.engine.model.player.BowlingStyle
import com.cricketcareer.engine.model.player.HiddenAttributes
import com.cricketcareer.engine.model.player.PersonName
import com.cricketcareer.engine.model.player.Player
import com.cricketcareer.engine.model.player.PlayerId
import com.cricketcareer.engine.model.player.PlayerRole
import com.cricketcareer.engine.model.world.BoundaryShape
import com.cricketcareer.engine.model.world.MatchFormat
import com.cricketcareer.engine.model.world.Pitch
import com.cricketcareer.engine.model.world.PitchArchetype
import com.cricketcareer.engine.model.world.SoilType
import com.cricketcareer.engine.model.world.Venue
import java.time.LocalDate

/**
 * The single definition of "average", used by the engine's tests, by
 * :sim-harness and by :data.
 *
 * docs/CALIBRATION.md judges the engine on the output of "average-quality
 * players on an average pitch". That phrase is only meaningful if every part of
 * the project means the same thing by it — otherwise a band measured today is
 * not comparable with one measured in Phase 6, and the calibration log is
 * fiction. This file is that definition, and it answers Q10 in
 * docs/OPEN_QUESTIONS.md.
 *
 * Lives in `testFixtures` so it can never be shipped inside a release artifact.
 */
object Fixtures {

    /** Every attribute at 50 and every hidden attribute at 50. */
    const val AVERAGE_RATING: Int = 50

    /** The reference date for fixture players, so ages are stable across runs. */
    val REFERENCE_DATE: LocalDate = LocalDate.of(2025, 4, 1)

    /**
     * A player who is exactly average at everything.
     *
     * Deliberately flat rather than role-shaped: calibration needs a control,
     * and a control with a shape would fold the role archetype's opinions into
     * every measured band. Role-shaped players come from [PlayerGenerator]
     * when a test wants them.
     */
    fun averagePlayer(
        id: String,
        role: PlayerRole = PlayerRole.MIDDLE_ORDER_BAT,
        bowlingStyle: BowlingStyle = BowlingStyle.RIGHT_FAST_MEDIUM,
        battingHand: BattingHand = BattingHand.RIGHT,
        age: Int = 27,
    ): Player = Player(
        id = PlayerId(id),
        name = PersonName("Average", "Player$id"),
        dateOfBirth = REFERENCE_DATE.minusYears(age.toLong()),
        country = "AVG",
        region = "AVG",
        battingHand = battingHand,
        bowlingStyle = bowlingStyle,
        role = role,
        attributes = Attributes.uniform(AVERAGE_RATING),
        hidden = HiddenAttributes.uniform(AVERAGE_RATING),
    )

    /**
     * Eleven average players in a standard balance: five specialist batters, a
     * keeper, an all-rounder, three seamers and a spinner.
     *
     * All of them are rated 50 at everything, so the *balance* affects who
     * bowls and who bats where without affecting how good anyone is.
     */
    fun averageXI(prefix: String): List<Player> = listOf(
        averagePlayer("$prefix-1", PlayerRole.OPENING_BAT, BowlingStyle.NONE),
        averagePlayer("$prefix-2", PlayerRole.OPENING_BAT, BowlingStyle.NONE, BattingHand.LEFT),
        averagePlayer("$prefix-3", PlayerRole.TOP_ORDER_BAT, BowlingStyle.NONE),
        averagePlayer("$prefix-4", PlayerRole.MIDDLE_ORDER_BAT, BowlingStyle.NONE),
        averagePlayer("$prefix-5", PlayerRole.MIDDLE_ORDER_BAT, BowlingStyle.OFF_BREAK, BattingHand.LEFT),
        averagePlayer("$prefix-6", PlayerRole.WICKETKEEPER_BAT, BowlingStyle.NONE),
        averagePlayer("$prefix-7", PlayerRole.SEAM_ALLROUNDER, BowlingStyle.RIGHT_FAST_MEDIUM),
        averagePlayer("$prefix-8", PlayerRole.SPINNER, BowlingStyle.LEG_BREAK),
        averagePlayer("$prefix-9", PlayerRole.FAST_BOWLER, BowlingStyle.RIGHT_FAST_MEDIUM),
        averagePlayer("$prefix-10", PlayerRole.FAST_BOWLER, BowlingStyle.RIGHT_FAST, age = 25),
        averagePlayer("$prefix-11", PlayerRole.FAST_BOWLER, BowlingStyle.LEFT_FAST_MEDIUM, BattingHand.LEFT),
    )

    /** Every pitch parameter at its midpoint, on the most neutral soil. */
    val AVERAGE_PITCH: Pitch = Pitch.AVERAGE

    /** A mid-sized, symmetric ground at sea level. */
    val AVERAGE_VENUE: Venue = Venue(
        id = "avg-ground",
        name = "Neutral Ground",
        city = "Neutral",
        country = "AVG",
        region = "AVG",
        boundary = BoundaryShape.AVERAGE,
        soilType = SoilType.CLAY,
        archetypeWeights = mapOf(PitchArchetype.BALANCED to 1.0),
        altitudeMetres = 0.0,
        dewTendency = 0.3,
    )

    /** The batting orders for two average sides. */
    fun averageTeams(): Pair<List<Player>, List<Player>> = averageXI("HOME") to averageXI("AWAY")

    /** Standard formats, for readability at call sites. */
    val T20: MatchFormat = MatchFormat.T20
    val LIST_A: MatchFormat = MatchFormat.LIST_A
    val TEST: MatchFormat = MatchFormat.TEST
}
