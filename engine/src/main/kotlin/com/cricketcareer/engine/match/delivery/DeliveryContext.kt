package com.cricketcareer.engine.match.delivery

import com.cricketcareer.engine.config.EngineTuning
import com.cricketcareer.engine.match.field.FieldPosition
import com.cricketcareer.engine.match.field.FieldSetting
import com.cricketcareer.engine.model.player.Attribute
import com.cricketcareer.engine.model.player.EffectiveSkill
import com.cricketcareer.engine.model.player.Player
import com.cricketcareer.engine.model.player.PlayerId
import com.cricketcareer.engine.model.world.MatchFormat
import com.cricketcareer.engine.model.world.Pitch
import com.cricketcareer.engine.model.world.Venue

/**
 * Everything the six stages need to resolve one delivery.
 *
 * Assembled once per ball by the innings simulator and passed down the pipeline
 * read-only. Stages take this and return a value; none of them reaches into
 * another's internals, which is what makes it possible to replace the swing
 * model without touching the fielding model (CLAUDE.md §5).
 */
class DeliveryContext(
    val format: MatchFormat,
    val bowler: Player,
    val striker: Player,
    val nonStriker: Player,
    val pitch: Pitch,
    val venue: Venue,
    val weather: Weather,
    val ball: BallCondition,
    val situation: MatchSituation,
    /** The pressure index for this ball, in [0, 1]. */
    val pressure: Double,
    val field: FieldSetting,
    /**
     * What each stroke is worth against [field], precomputed.
     *
     * Built once when the captain sets the field and shared by every ball of
     * the over — see [FieldRewards]. It is passed in rather than derived here
     * because a `DeliveryContext` is built per ball, and deriving it here would
     * put the work straight back.
     */
    val fieldRewards: FieldRewards,
    /**
     * The fielding side, by id. Needed because a catch is taken by whoever is
     * standing there, and his hands are the ones that matter — reading the
     * bowler's or the batter's attributes instead would make every catch in the
     * game a different player's.
     */
    private val fieldingSide: Map<PlayerId, Player>,
    val tuning: EngineTuning,
    /** Legal balls the striker has faced in this innings. Drives settling. */
    val strikerBallsFaced: Int,
    /** Fatigue accumulated by the bowler inside this match, on top of his season fatigue. */
    val bowlerSpellFatigue: Double,
    /** The bowler's standing plan for this batter. */
    val plan: BowlingPlan,
    /**
     * The batting side's intent setting, -1 (block) to +1 (attack).
     *
     * For the user's cricketer this is his posture (Q3); for an AI batter it is
     * what the situation asks of him. Deliberately the *same* input either way —
     * never a parallel code path for the user's player.
     */
    val battingIntent: Double,
) {
    /** A bowling attribute, fatigue-adjusted. */
    fun bowlerSkill(attribute: Attribute): Double =
        EffectiveSkill.of(bowler, attribute, bowlerSpellFatigue)

    /** A batting attribute. */
    fun strikerSkill(attribute: Attribute): Double = EffectiveSkill.of(striker, attribute)

    /**
     * A batting attribute of the man at the other end.
     *
     * Running between the wickets is the one thing in the game decided by both
     * batters at once, so it is the one place a delivery needs him.
     */
    fun nonStrikerSkill(attribute: Attribute): Double = EffectiveSkill.of(nonStriker, attribute)

    /**
     * A fielder's attribute. Falls back to an average fielder when the roster
     * does not name him, so a malformed field setting degrades rather than
     * crashing a whole season of simulation.
     */
    fun fielderSkill(player: PlayerId, attribute: Attribute): Double =
        fieldingSide[player]?.let { EffectiveSkill.of(it, attribute) } ?: 0.5

    /**
     * Everyone who can field this ball: the placed field plus the bowler, who
     * is not in the field setting because nobody places him.
     */
    val fieldersIncludingBowler: List<com.cricketcareer.engine.match.field.Fielder>
        get() = this.field.fielders +
            com.cricketcareer.engine.match.field.Fielder(FieldPosition.BOWLER, bowler.id)

    /**
     * The keeper's player id, if one is placed.
     *
     * `this.field` rather than `field`: inside a property accessor, a bare
     * `field` is Kotlin's backing-field keyword, not this class's field setting.
     */
    val keeper: PlayerId?
        get() = this.field.fielders.firstOrNull { it.position == FieldPosition.WICKETKEEPER }?.player

    /**
     * How settled the striker is, 0 on arrival and approaching 1 as he plays
     * himself in. The single most important number in the batting model.
     */
    val settledness: Double
        get() = 1.0 - kotlin.math.exp(-strikerBallsFaced / tuning.perception.settleScaleBalls)

    val bowlerIsSpin: Boolean get() = bowler.bowlingStyle.isSpin
}
