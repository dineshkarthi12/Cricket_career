package com.cricketcareer.engine.save

import com.cricketcareer.engine.career.Appearance
import com.cricketcareer.engine.career.CareerPosition
import com.cricketcareer.engine.career.Difficulty
import com.cricketcareer.engine.model.player.Player
import com.cricketcareer.engine.model.world.LadderLevel
import com.cricketcareer.engine.model.world.MatchFormat
import com.cricketcareer.engine.util.LocalDateSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.LocalDate

/**
 * What the engine version stamp is for.
 *
 * A match is a pure function of `(seed, inputs)`, but the engine keeps
 * changing, and a Phase 5 engine will not reproduce a Phase 3 scorecard from
 * the same seed. So a save stores **both**: the materialised result, which is
 * what the player is shown and which therefore never changes under his feet,
 * and the seed, which lets a match be replayed ball by ball when — and only
 * when — the stamp still matches.
 *
 * Bump this whenever a change makes the engine produce different cricket from
 * the same seed. Getting that wrong does not corrupt a save: it makes "watch it
 * again" show a different match from the scorecard next to it, which is worse
 * than the feature being unavailable. When in doubt, bump it.
 *
 * See docs/ARCHITECTURE.md §4.
 */
object EngineVersion {
    const val CURRENT: String = "phase4-2026-09-13"
}

/**
 * One completed match, as a career remembers it.
 *
 * Deliberately *not* the full `BallEvent` stream. Those are held only for the
 * match being watched; once it is over a career keeps the scorecard figures and
 * the seed, which is a few dozen bytes against a few hundred kilobytes, and
 * everything else is recoverable by replay when the stamp allows it.
 */
@Serializable
data class PlayedMatch(
    val fixture: String,
    @Serializable(with = LocalDateSerializer::class)
    val date: LocalDate,
    val formatId: String,
    val level: LadderLevel,
    val opponent: String,
    val atHome: Boolean,
    /** The seed this match was played from. Replayable only under [engineVersion]. */
    val seed: Long,
    /** The engine that produced the figures below. */
    val engineVersion: String,
    val runs: Int,
    val ballsFaced: Int,
    val out: Boolean,
    val wickets: Int = 0,
    val runsConceded: Int = 0,
    val ballsBowled: Int = 0,
    /** Free text as the result read on the day: "won by 34 runs". */
    val result: String = "",
) {
    /** Whether this match can still be re-simulated ball by ball. */
    fun isReplayableBy(engine: String): Boolean = engineVersion == engine

    /** The career-layer view of the same match. */
    fun toAppearance(format: MatchFormat): Appearance = Appearance(
        fixture = fixture,
        date = date,
        format = format,
        level = level,
        runs = runs,
        ballsFaced = ballsFaced,
        out = out,
        wickets = wickets,
        runsConceded = runsConceded,
        ballsBowled = ballsBowled,
    )
}

/**
 * A whole career, as stored.
 *
 * The three rules this file exists to enforce:
 *
 * 1. **History is materialised.** [matches] is data, not a promise that the
 *    engine will produce the same thing again. A career's past never changes
 *    after an update.
 * 2. **The future is seeded, not stored.** Next season's fixtures, weather and
 *    opposition all derive from [careerSeed] plus a fixture identity, so a save
 *    stays small — and a career cannot be save-scummed by reloading and
 *    re-simulating the same match, because the seed is derived from the fixture
 *    rather than drawn.
 * 3. **Nothing here is a model.** This is the shape of a career on disk. It
 *    holds no cricket logic, and everything derived from it — the record book,
 *    the averages, the form figure — is recomputed rather than stored, so a
 *    screen can never disagree with the scorecards behind it.
 *
 * `:engine` does no I/O, so this serialises to and from a string and `:data`
 * decides where the bytes go.
 */
@Serializable
data class CareerSave(
    /**
     * The save *format* version, which is not the engine version.
     *
     * They move independently: a calibration change bumps the engine stamp and
     * leaves every save readable, while adding a field here bumps this one and
     * does not invalidate a single scorecard.
     */
    @SerialName("saveFormat")
    val saveFormat: Int = CURRENT_SAVE_FORMAT,

    /** Identifies this career among several. Stable for the career's life. */
    val id: String,

    /** What the player called it. */
    val name: String,

    /** Everything about this career derives from here. */
    val careerSeed: Long,

    val difficulty: Difficulty = Difficulty.DEFAULT,

    /** The player himself, current attributes and state. */
    val player: Player,

    /** Where he is on the ladder. */
    val position: CareerPosition,

    /** The date the career has reached. Nothing in `:engine` may read a clock. */
    @Serializable(with = LocalDateSerializer::class)
    val today: LocalDate,

    /** Seasons completed. The first season a career plays is season 1. */
    val seasonsPlayed: Int = 0,

    /** Every match he has played, oldest first. */
    val matches: List<PlayedMatch> = emptyList(),

    /** Set once he has retired, and the reason he gave. */
    val retiredOn: @Serializable(with = LocalDateSerializer::class) LocalDate? = null,
    val retirementReason: String = "",
) {
    init {
        require(id.isNotBlank()) { "a save needs an id" }
        require(saveFormat >= 1) { "saveFormat $saveFormat is not a version" }
    }

    val isRetired: Boolean get() = retiredOn != null

    /** The summary a "load career" screen shows without opening the save. */
    fun summarise(): CareerSummary {
        val innings = matches.filter { it.ballsFaced > 0 || it.out }
        val runs = innings.sumOf { it.runs }
        val dismissals = innings.count { it.out }
        return CareerSummary(
            id = id,
            name = name,
            difficulty = difficulty,
            playerName = player.name.full,
            level = position.level,
            teamId = position.teamId,
            today = today,
            seasonsPlayed = seasonsPlayed,
            matches = matches.size,
            runs = runs,
            wickets = matches.sumOf { it.wickets },
            battingAverage = if (dismissals == 0) null else runs.toDouble() / dismissals,
            isRetired = isRetired,
        )
    }

    companion object {
        /**
         * Bumped whenever a field is added, removed or given a new meaning.
         *
         * 1 — Phase 7. Career seed, player, ladder position, materialised
         *     matches, difficulty.
         */
        const val CURRENT_SAVE_FORMAT: Int = 1
    }
}

/**
 * One line of the career-select screen.
 *
 * Derived from a save rather than stored beside it, because a summary stored
 * separately is a summary that can be wrong. A "load career" screen reading
 * fifty of these is reading fifty saves, which is what an index is for — see
 * [SaveIndex].
 */
data class CareerSummary(
    val id: String,
    val name: String,
    val difficulty: Difficulty,
    val playerName: String,
    val level: LadderLevel,
    val teamId: String,
    val today: LocalDate,
    val seasonsPlayed: Int,
    val matches: Int,
    val runs: Int,
    val wickets: Int,
    val battingAverage: Double?,
    val isRetired: Boolean,
)
