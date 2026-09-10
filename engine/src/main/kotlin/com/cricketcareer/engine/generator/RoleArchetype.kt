package com.cricketcareer.engine.generator

import com.cricketcareer.engine.model.player.Attribute
import com.cricketcareer.engine.model.player.AttributeGroup
import com.cricketcareer.engine.model.player.PlayerRole

/**
 * What a role is *for*, expressed as offsets in attribute points.
 *
 * [groupOffsets] moves whole groups: a fast bowler's batting is worse than his
 * overall quality suggests, his bowling better. [attributeOffsets] shapes the
 * detail within a group: a finisher's range hitting is high and his patience
 * low, at the same batting level.
 *
 * Offsets are in raw 1-100 points and are applied before clamping. They are
 * relative to the player's own quality, so a great fast bowler and a poor one
 * have the same *shape* at different levels — which is what makes roles read as
 * roles rather than as quality tiers.
 */
data class RoleArchetype(
    val role: PlayerRole,
    val groupOffsets: Map<AttributeGroup, Double>,
    val attributeOffsets: Map<Attribute, Double> = emptyMap(),
) {
    fun offsetFor(attribute: Attribute): Double =
        (groupOffsets[attribute.group] ?: 0.0) + (attributeOffsets[attribute] ?: 0.0)

    companion object {
        private fun groups(
            batting: Double = 0.0,
            bowling: Double = 0.0,
            fielding: Double = 0.0,
            keeping: Double = 0.0,
            physical: Double = 0.0,
            mental: Double = 0.0,
        ) = mapOf(
            AttributeGroup.BATTING to batting,
            AttributeGroup.BOWLING to bowling,
            AttributeGroup.FIELDING to fielding,
            AttributeGroup.KEEPING to keeping,
            AttributeGroup.PHYSICAL to physical,
            AttributeGroup.MENTAL to mental,
        )

        /**
         * The non-keeper's gloves penalty. A specialist fielder handed the
         * gloves in an emergency is poor, not incapable.
         */
        private const val NOT_A_KEEPER = -32.0

        val ALL: Map<PlayerRole, RoleArchetype> = listOf(
            RoleArchetype(
                PlayerRole.OPENING_BAT,
                // Faces the new ball: technique and leaving are everything,
                // range hitting is not what he is picked for.
                groups(batting = 14.0, bowling = -30.0, keeping = NOT_A_KEEPER, mental = 3.0),
                mapOf(
                    Attribute.TECHNIQUE to 6.0,
                    Attribute.SWING_PLAY to 6.0,
                    Attribute.PATIENCE to 7.0,
                    Attribute.CONCENTRATION to 5.0,
                    Attribute.RANGE_HITTING to -6.0,
                ),
            ),
            RoleArchetype(
                PlayerRole.TOP_ORDER_BAT,
                groups(batting = 15.0, bowling = -28.0, keeping = NOT_A_KEEPER, mental = 3.0),
                mapOf(
                    Attribute.TECHNIQUE to 5.0,
                    Attribute.CONCENTRATION to 5.0,
                    Attribute.SPIN_PLAY to 3.0,
                ),
            ),
            RoleArchetype(
                PlayerRole.MIDDLE_ORDER_BAT,
                groups(batting = 13.0, bowling = -24.0, keeping = NOT_A_KEEPER, mental = 2.0),
                mapOf(
                    Attribute.SPIN_PLAY to 6.0,
                    Attribute.STRIKE_ROTATION to 5.0,
                ),
            ),
            RoleArchetype(
                PlayerRole.FINISHER,
                // Comes in with ten overs left: power and nerve, not patience.
                groups(batting = 11.0, bowling = -22.0, keeping = NOT_A_KEEPER, physical = 4.0),
                mapOf(
                    Attribute.POWER to 12.0,
                    Attribute.RANGE_HITTING to 14.0,
                    Attribute.STRIKE_ROTATION to 7.0,
                    Attribute.RUNNING_BETWEEN_WICKETS to 6.0,
                    Attribute.PATIENCE to -9.0,
                    Attribute.CONCENTRATION to -4.0,
                    Attribute.COMPOSURE to 5.0,
                ),
            ),
            RoleArchetype(
                PlayerRole.WICKETKEEPER_BAT,
                groups(batting = 8.0, bowling = -34.0, keeping = 20.0, fielding = 4.0, physical = 3.0),
                mapOf(
                    Attribute.REFLEXES to 10.0,
                    Attribute.SPIN_PLAY to 4.0,
                    Attribute.CATCHING to 6.0,
                ),
            ),
            RoleArchetype(
                PlayerRole.SEAM_ALLROUNDER,
                groups(batting = 2.0, bowling = 6.0, keeping = NOT_A_KEEPER, physical = 6.0),
                mapOf(
                    Attribute.TURN to -30.0,
                    Attribute.DRIFT to -28.0,
                    Attribute.STAMINA to 5.0,
                ),
            ),
            RoleArchetype(
                PlayerRole.SPIN_ALLROUNDER,
                groups(batting = 2.0, bowling = 6.0, keeping = NOT_A_KEEPER),
                mapOf(
                    Attribute.PACE to -34.0,
                    Attribute.SEAM_MOVEMENT to -22.0,
                    Attribute.SWING to -24.0,
                    Attribute.REVERSE_SWING to -24.0,
                ),
            ),
            RoleArchetype(
                PlayerRole.FAST_BOWLER,
                // The classic shape: bowls beautifully, bats at eleven.
                groups(batting = -30.0, bowling = 16.0, keeping = NOT_A_KEEPER, physical = 8.0),
                mapOf(
                    Attribute.PACE to 10.0,
                    Attribute.TURN to -36.0,
                    Attribute.DRIFT to -32.0,
                    Attribute.STAMINA to 6.0,
                    Attribute.RUNNING_BETWEEN_WICKETS to -6.0,
                ),
            ),
            RoleArchetype(
                PlayerRole.SPINNER,
                groups(batting = -26.0, bowling = 16.0, keeping = NOT_A_KEEPER),
                mapOf(
                    Attribute.TURN to 12.0,
                    Attribute.DRIFT to 8.0,
                    Attribute.VARIATIONS to 8.0,
                    Attribute.PACE to -38.0,
                    Attribute.SEAM_MOVEMENT to -26.0,
                    Attribute.SWING to -28.0,
                    Attribute.REVERSE_SWING to -28.0,
                    Attribute.BOUNCE to -6.0,
                ),
            ),
        ).associateBy { it.role }

        fun forRole(role: PlayerRole): RoleArchetype = ALL.getValue(role)
    }
}
