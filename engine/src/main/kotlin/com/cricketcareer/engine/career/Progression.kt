package com.cricketcareer.engine.career

import com.cricketcareer.engine.config.CareerTuning
import com.cricketcareer.engine.config.ProgressionTuning
import com.cricketcareer.engine.model.player.Player
import com.cricketcareer.engine.model.world.LadderLevel
import com.cricketcareer.engine.model.world.MatchFormat
import com.cricketcareer.engine.model.world.Pitch
import com.cricketcareer.engine.rng.SimRandom
import com.cricketcareer.engine.seed.SeedDatabase

/** Where a player is on the ladder, and how long he has been there. */
data class CareerPosition(
    val teamId: String,
    val level: LadderLevel,
    /** Seasons at this rung, this spell. Reset by a move in either direction. */
    val seasonsHere: Int = 0,
    /** Consecutive seasons he has failed to interest the rung above. */
    val seasonsOverlooked: Int = 0,
)

/** What a season did to a player's standing. */
enum class Movement(val displayName: String) {
    PROMOTED("Called up"),
    HELD("Retained"),
    DROPPED("Released"),
}

/**
 * The verdict on a season, with the reasoning kept.
 *
 * [claim] and [required] are here so a screen can say *why* — "eleventh in a
 * squad that wanted eighth" is a sentence a player understands, and "your
 * progression score was 0.62" is not.
 */
data class ProgressionOutcome(
    val position: CareerPosition,
    val movement: Movement,
    /** Where he would have ranked in the best side above him, or null if none looked. */
    val claim: Int?,
    /** The rank he had to beat there. */
    val required: Int?,
    /** The side that would take him, when one would. */
    val suitor: String?,
)

/**
 * Moving up and down the ladder.
 *
 * The career layer's answer to "what happens next season". [Ladder] says which
 * doors exist; this decides which of them open, and it decides it the same way
 * the rest of the project decides everything — by asking the selection model,
 * not by inventing a second opinion about how good a player is.
 *
 * A call-up is therefore never a threshold on an average. It is a panel at the
 * rung above scoring him against the men already there, which is why a good
 * season in a weak state side is worth less than a modest one in a strong
 * competition, without a single line saying so.
 *
 * See docs/CAREER_MODEL.md §9.
 */
object Progression {

    /**
     * Where a player starts.
     *
     * A cricketer the database already has in a squad starts there — that is
     * what the squad list means. Anyone else starts on the bottom rung he is
     * eligible for, which for a created player is his district side.
     */
    fun startingPosition(player: Player, database: SeedDatabase): CareerPosition? {
        val existing = database.teams.firstOrNull { player.id in it.squad }
        if (existing != null) return CareerPosition(existing.id, existing.level)
        val bottom = Ladder.forPlayer(player, database).firstOrNull() ?: return null
        return CareerPosition(bottom.teams.first().id, bottom.level)
    }

    /**
     * The verdict on one season.
     *
     * Three questions in order, because they are not symmetric: a man who has
     * played well enough to go up is not also a candidate to go down, and a man
     * nobody has picked all summer is not helped by being assessed against the
     * rung above.
     */
    fun review(
        player: Player,
        record: SeasonRecord,
        position: CareerPosition,
        database: SeedDatabase,
        personality: SelectorPersonality,
        random: SimRandom,
        tuning: CareerTuning = CareerTuning.DEFAULT,
    ): ProgressionOutcome {
        val progression = tuning.progression
        val rung = Ladder.nextRung(player, position.level, database)

        // Nobody looks at a player who has not played. That is the whole cost
        // of a season spent carrying drinks.
        val played = record.matches + record.omissions
        val share = if (played == 0) 0.0 else record.matches.toDouble() / played
        val inTheReckoning = share >= progression.retentionShare

        val interest = if (rung == null || !inTheReckoning) {
            null
        } else {
            bestClaim(player, rung, database, personality, random, tuning)
        }

        return when {
            rung != null && interest != null && interest.rank <= interest.required -> ProgressionOutcome(
                position = CareerPosition(interest.teamId, rung.level),
                movement = Movement.PROMOTED,
                claim = interest.rank,
                required = interest.required,
                suitor = interest.teamId,
            )

            shouldDrop(position, share, progression) -> {
                val below = Ladder.previousRung(player, position.level, database)
                if (below == null) {
                    // Already on the bottom rung: there is nowhere to send him,
                    // and a player out of the side at district level is simply
                    // out of the side.
                    held(position, interest)
                } else {
                    ProgressionOutcome(
                        position = CareerPosition(below.teams.first().id, below.level),
                        movement = Movement.DROPPED,
                        claim = interest?.rank,
                        required = interest?.required,
                        suitor = null,
                    )
                }
            }

            else -> held(position, interest)
        }
    }

    private fun held(position: CareerPosition, interest: Claim?): ProgressionOutcome = ProgressionOutcome(
        position = position.copy(
            seasonsHere = position.seasonsHere + 1,
            seasonsOverlooked = position.seasonsOverlooked + 1,
        ),
        movement = Movement.HELD,
        claim = interest?.rank,
        required = interest?.required,
        suitor = interest?.teamId,
    )

    /**
     * Whether the side he is in has finished with him.
     *
     * Not on one bad season: [ProgressionTuning.graceSeasons] protects a new
     * arrival, and [ProgressionTuning.forgottenAfterSeasons] is what eventually
     * runs out. A player is released when he has stopped playing *and* has been
     * around long enough that it is no longer a settling-in period.
     */
    private fun shouldDrop(
        position: CareerPosition,
        share: Double,
        tuning: ProgressionTuning,
    ): Boolean =
        share < tuning.retentionShare &&
            position.seasonsHere >= tuning.graceSeasons &&
            position.seasonsOverlooked >= tuning.forgottenAfterSeasons - 1

    /** One side's interest in a player: where he would rank, and where he had to. */
    private data class Claim(val teamId: String, val rank: Int, val required: Int)

    /**
     * The strongest interest in a player from any side on [rung].
     *
     * Every side on the rung scores him against its own squad, and the best
     * offer wins — which is what a franchise auction is, and close enough to
     * what national selection is that it does not need a second model.
     */
    private fun bestClaim(
        player: Player,
        rung: Rung,
        database: SeedDatabase,
        personality: SelectorPersonality,
        random: SimRandom,
        tuning: CareerTuning,
    ): Claim? = rung.teams
        .mapNotNull { team ->
            val squad = database.squadOf(team.id).filter { it.id != player.id }
            if (squad.isEmpty()) return@mapNotNull null
            val format = formatAt(rung.level, database)
            val context = SelectionContext(
                format = format,
                // A panel picking a squad for a season is not picking for one
                // strip, so the reference pitch is the neutral one. Conditions
                // decide an XI, and that is `Selection.pickXI`'s business.
                pitch = Pitch.AVERAGE,
                // Only the side that plays is incumbent. The other seven in the
                // squad are the fringe, and the fringe is exactly who a player
                // coming up from below is competing with — treating all
                // eighteen as established men is what made the ladder inert.
                incumbents = squad
                    .sortedByDescending { Selection.standardFor(it, format) }
                    .take(Squads.XI)
                    .map { it.id }
                    .toSet(),
            )
            val ranked = Selection.score(squad + player, context, personality, random, tuning.selection)
            val rank = ranked.indexOfFirst { it.player.id == player.id }
            if (rank < 0) null else Claim(team.id, rank + 1, Squads.XI + tuning.progression.callUpMargin)
        }
        .minByOrNull { it.rank }

    /**
     * What is played at a rung.
     *
     * Read from the competitions on it rather than assumed, so a level that is
     * red-ball in one database and white-ball in another is judged by the right
     * standard. Where a level plays several formats the longest wins: a player
     * who can only do it for twenty overs is not yet a first-class cricketer.
     */
    fun formatAt(level: LadderLevel, database: SeedDatabase): MatchFormat =
        database.competitions
            .filter { it.level == level }
            .mapNotNull { competition -> MatchFormat.ALL.firstOrNull { it.id == competition.format } }
            .maxByOrNull { it.days ?: 0 }
            ?: MatchFormat.T20
}
