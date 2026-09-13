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
     * @param multiDay a red-ball field, which is a different game: a cordon
     *   rather than sweepers, because in a match with no clock on the innings
     *   the only thing worth buying is a wicket.
     * @param newBatter whether the man on strike has just arrived. A wicket
     *   falls and the field comes up — this is what makes that happen.
     * @param ballIsDoingSomething whether the ball still has enough on it to be
     *   worth a catcher. When it does not, a red-ball captain's slip goes out to
     *   save runs, which is the other half of what makes a Test field move.
     */
    fun setField(
        bowlingSide: List<Player>,
        bowler: PlayerId,
        keeper: PlayerId,
        phase: MatchPhase,
        bowlerIsSpin: Boolean,
        fieldersOutsideLimit: Int?,
        multiDay: Boolean = false,
        newBatter: Boolean = false,
        ballIsDoingSomething: Boolean = true,
    ): FieldSetting {
        val positions = positionsFor(
            phase, bowlerIsSpin, fieldersOutsideLimit, multiDay, newBatter, ballIsDoingSomething,
        )
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
        multiDay: Boolean,
        newBatter: Boolean,
        ballIsDoingSomething: Boolean,
    ): List<FieldPosition> {
        val effectiveLimit = limit ?: 5
        return when {
            // --- Red ball. A different game, and it was being played with a
            // fifty-over middle-overs field: no slips anywhere, a third man and
            // a cow corner. In a match with no clock on the innings there is
            // nothing to buy but a wicket, so the catchers come in and the
            // sweepers go.
            multiDay && newBatter && !spin -> listOf(
                FieldPosition.WICKETKEEPER, FieldPosition.FIRST_SLIP, FieldPosition.SECOND_SLIP,
                FieldPosition.GULLY, FieldPosition.POINT, FieldPosition.COVER,
                FieldPosition.MID_OFF, FieldPosition.MIDWICKET, FieldPosition.THIRD_MAN,
                FieldPosition.DEEP_SQUARE_LEG,
            )
            multiDay && newBatter -> listOf(
                FieldPosition.WICKETKEEPER, FieldPosition.FIRST_SLIP, FieldPosition.SILLY_POINT,
                FieldPosition.SHORT_LEG, FieldPosition.POINT, FieldPosition.COVER,
                FieldPosition.MID_OFF, FieldPosition.MID_ON, FieldPosition.MIDWICKET,
                FieldPosition.SQUARE_LEG,
            )
            // A set batter in a Test. One slip kept and three men out: as a
            // batter gets in, a captain gives up on the edge and starts saving
            // runs, which is the whole difference between the first over of a
            // spell and the fortieth of a session. Keeping two slips here made
            // four-day batting so hard that a batter lasted 52 balls against a
            // band of 55 to 65 and the dot rate went three points over its own.
            // Set batter, old ball, nothing happening: the slip goes out and
            // the captain settles for containment until the next new ball is
            // due. Keeping one in for all eighty overs is a captain nobody has
            // ever seen, and it cost a four-day batter six balls of his innings.
            multiDay && !ballIsDoingSomething -> listOf(
                FieldPosition.WICKETKEEPER, FieldPosition.POINT, FieldPosition.COVER,
                FieldPosition.MID_OFF, FieldPosition.MID_ON, FieldPosition.MIDWICKET,
                FieldPosition.THIRD_MAN, FieldPosition.DEEP_POINT, FieldPosition.LONG_OFF,
                FieldPosition.DEEP_SQUARE_LEG,
            )
            multiDay && !spin -> listOf(
                FieldPosition.WICKETKEEPER, FieldPosition.FIRST_SLIP, FieldPosition.POINT,
                FieldPosition.COVER, FieldPosition.MID_OFF, FieldPosition.MIDWICKET,
                FieldPosition.THIRD_MAN, FieldPosition.DEEP_POINT, FieldPosition.LONG_OFF,
                FieldPosition.DEEP_SQUARE_LEG,
            )
            multiDay -> listOf(
                FieldPosition.WICKETKEEPER, FieldPosition.FIRST_SLIP, FieldPosition.SILLY_POINT,
                FieldPosition.POINT, FieldPosition.COVER, FieldPosition.MID_OFF,
                FieldPosition.MID_ON, FieldPosition.MIDWICKET, FieldPosition.DEEP_SQUARE_LEG,
                FieldPosition.LONG_ON,
            )

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
            // A white-ball captain attacks a new batter too, and pays for the
            // catcher out of the deep: one sweeper comes in, which keeps the
            // field legal by construction and is exactly the trade a captain
            // makes for the two overs after a wicket.
            effectiveLimit <= 4 && newBatter -> listOf(
                FieldPosition.WICKETKEEPER, FieldPosition.FIRST_SLIP, FieldPosition.POINT,
                FieldPosition.COVER, FieldPosition.MID_OFF, FieldPosition.MID_ON,
                FieldPosition.MIDWICKET, FieldPosition.THIRD_MAN,
                FieldPosition.COW_CORNER, FieldPosition.DEEP_SQUARE_LEG,
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
            // Nobody posts a slip at the death: the new man there is a
            // tail-ender swinging, and the boundary is the thing worth saving.
            // So the new-batter field belongs to the middle overs only.
            newBatter -> listOf(
                FieldPosition.WICKETKEEPER, FieldPosition.FIRST_SLIP, FieldPosition.POINT,
                FieldPosition.COVER, FieldPosition.MID_OFF, FieldPosition.MIDWICKET,
                FieldPosition.THIRD_MAN, FieldPosition.LONG_OFF,
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
