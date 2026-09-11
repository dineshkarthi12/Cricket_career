package com.cricketcareer.engine.match.field

import com.cricketcareer.engine.model.player.Attribute
import com.cricketcareer.engine.model.player.Player
import com.cricketcareer.engine.model.player.PlayerId
import kotlinx.serialization.Serializable

/** Where an innings is. Drives both the field and the batting side's intent. */
@Serializable
enum class MatchPhase { POWERPLAY, MIDDLE, DEATH }

/**
 * The AI captain's field settings.
 *
 * The user controls one cricketer, so unless he is the captain somebody else
 * sets the field — and it has to be a real field, because Stage 4 reads it: the
 * batter's reward for a lofted shot depends on whether there is a man there.
 *
 * Presets rather than a search over placements. A captain has a handful of
 * fields he actually uses, and evaluating placements per ball would not survive
 * the per-ball budget in docs/ARCHITECTURE.md §7.
 */
object FieldCaptain {

    /**
     * Set a field.
     *
     * @param bowlingSide the XI in the field, including the bowler.
     * @param bowler excluded from placement — he is running in.
     * @param keeper whoever is wearing the gloves.
     * @param fieldersOutsideLimit powerplay restriction, or null when there is none.
     */
    fun setField(
        bowlingSide: List<Player>,
        bowler: PlayerId,
        keeper: PlayerId,
        phase: MatchPhase,
        bowlerIsSpin: Boolean,
        fieldersOutsideLimit: Int?,
    ): FieldSetting {
        val positions = positionsFor(phase, bowlerIsSpin, fieldersOutsideLimit)
        val available = bowlingSide.filter { it.id != bowler && it.id != keeper }

        // Close catchers want reflexes, the deep wants safe hands and legs.
        val byReflexes = available.sortedByDescending { it.attributes.normalised(Attribute.REFLEXES) }
        val byOutfield = available.sortedByDescending {
            it.attributes.normalised(Attribute.CATCHING) * 0.6 +
                it.attributes.normalised(Attribute.SPEED) * 0.25 +
                it.attributes.normalised(Attribute.THROW_ARM) * 0.15
        }

        val taken = LinkedHashSet<PlayerId>()
        val placements = mutableListOf<Fielder>()

        positions.forEach { position ->
            if (position == FieldPosition.WICKETKEEPER) {
                placements += Fielder(position, keeper)
                taken += keeper
                return@forEach
            }
            val pool = if (position.catchesWithReflexes) byReflexes else byOutfield
            val chosen = pool.firstOrNull { it.id !in taken }
                ?: available.first { it.id !in taken }
            placements += Fielder(position, chosen.id)
            taken += chosen.id
        }

        return FieldSetting(placements)
    }

    /**
     * The preset for this situation.
     *
     * Each list is exactly ten and respects the restriction: a captain does not
     * set an illegal field, so the constraint is satisfied by construction
     * rather than checked and repaired.
     */
    private fun positionsFor(
        phase: MatchPhase,
        spin: Boolean,
        limit: Int?,
    ): List<FieldPosition> {
        val effectiveLimit = limit ?: 5
        return when {
            effectiveLimit <= 2 && !spin -> listOf(
                FieldPosition.WICKETKEEPER, FieldPosition.FIRST_SLIP, FieldPosition.POINT,
                FieldPosition.COVER, FieldPosition.MID_OFF, FieldPosition.MID_ON,
                FieldPosition.MIDWICKET, FieldPosition.SQUARE_LEG,
                FieldPosition.THIRD_MAN, FieldPosition.DEEP_FINE_LEG,
            )
            effectiveLimit <= 2 -> listOf(
                FieldPosition.WICKETKEEPER, FieldPosition.SILLY_POINT, FieldPosition.POINT,
                FieldPosition.COVER, FieldPosition.MID_OFF, FieldPosition.MID_ON,
                FieldPosition.MIDWICKET, FieldPosition.SQUARE_LEG,
                FieldPosition.DEEP_COVER, FieldPosition.DEEP_MIDWICKET,
            )
            effectiveLimit <= 4 -> listOf(
                FieldPosition.WICKETKEEPER, FieldPosition.POINT, FieldPosition.COVER,
                FieldPosition.MID_OFF, FieldPosition.MID_ON, FieldPosition.MIDWICKET,
                FieldPosition.THIRD_MAN, FieldPosition.DEEP_COVER,
                FieldPosition.COW_CORNER, FieldPosition.DEEP_SQUARE_LEG,
            )
            phase == MatchPhase.DEATH -> listOf(
                FieldPosition.WICKETKEEPER, FieldPosition.POINT, FieldPosition.COVER,
                FieldPosition.MID_OFF, FieldPosition.MIDWICKET,
                FieldPosition.THIRD_MAN, FieldPosition.LONG_OFF, FieldPosition.LONG_ON,
                FieldPosition.COW_CORNER, FieldPosition.DEEP_SQUARE_LEG,
            )
            else -> listOf(
                FieldPosition.WICKETKEEPER, FieldPosition.POINT, FieldPosition.COVER,
                FieldPosition.MID_OFF, FieldPosition.MIDWICKET,
                FieldPosition.THIRD_MAN, FieldPosition.DEEP_POINT, FieldPosition.LONG_OFF,
                FieldPosition.COW_CORNER, FieldPosition.DEEP_SQUARE_LEG,
            )
        }
    }
}
