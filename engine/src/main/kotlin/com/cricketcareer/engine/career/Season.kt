package com.cricketcareer.engine.career

import com.cricketcareer.engine.config.CareerTuning
import com.cricketcareer.engine.model.player.Player
import com.cricketcareer.engine.model.player.PlayerId
import com.cricketcareer.engine.model.world.LadderLevel
import com.cricketcareer.engine.model.world.MatchFormat
import com.cricketcareer.engine.model.world.Pitch
import com.cricketcareer.engine.rng.SimRandom
import java.time.LocalDate

/** One scheduled match. */
data class Fixture(
    val id: String,
    val date: LocalDate,
    val format: MatchFormat,
    val level: LadderLevel,
    val pitch: Pitch = Pitch.AVERAGE,
    /** Standard of the opposition, in [0, 1]. */
    val oppositionStandard: Double = 0.5,
) {
    init {
        require(id.isNotBlank()) { "fixture id must not be blank" }
        require(oppositionStandard in 0.0..1.0) { "oppositionStandard $oppositionStandard must be in 0..1" }
    }
}

/** What one player did in one match. */
data class Appearance(
    val fixture: String,
    val date: LocalDate,
    val format: MatchFormat,
    val level: LadderLevel,
    val runs: Int,
    val ballsFaced: Int,
    val out: Boolean,
    val wickets: Int = 0,
    val runsConceded: Int = 0,
    val ballsBowled: Int = 0,
)

/** A player's season: what he played, and what it left him as. */
data class SeasonRecord(
    val player: Player,
    val appearances: List<Appearance>,
    /** Fixtures he was available for and not picked. The other half of a career. */
    val omissions: Int,
    /**
     * Times he broke down during the season.
     *
     * Distinct from `player.state.isInjured`, which only says whether he
     * happens to be injured on the season's last day. A crowded season that
     * finishes in May leaves months for everything to heal, so counting
     * outstanding injuries would report it as the *safer* schedule.
     */
    val injuries: Int = 0,
) {
    val matches: Int get() = appearances.size
    val runs: Int get() = appearances.sumOf { it.runs }
    val dismissals: Int get() = appearances.count { it.out }
    val wickets: Int get() = appearances.sumOf { it.wickets }

    /** Batting average, or null when he was never dismissed. */
    val battingAverage: Double? get() = if (dismissals == 0) null else runs.toDouble() / dismissals

    val highestScore: Int get() = appearances.maxOfOrNull { it.runs } ?: 0
    val fifties: Int get() = appearances.count { it.runs in 50..99 }
    val hundreds: Int get() = appearances.count { it.runs >= 100 }
}

/**
 * One season, for one squad.
 *
 * This is the object that turns the separate models into a career. It owns the
 * order in which things happen to a player across a season and nothing else:
 * the selection panel decides the XI, the reduced-form model decides what
 * happened, and the clock decides what the days in between cost.
 *
 * Every match here is tier 2. Tier 1 — running the real ball-by-ball engine for
 * the user's own matches — is the caller's job, because the engine's
 * `MatchSimulator` needs two full sides and a venue that a season does not own.
 * The split is deliberate: `Season` must not quietly become a second, worse
 * match engine.
 *
 * See docs/CAREER_MODEL.md.
 */
class Season(private val tuning: CareerTuning = CareerTuning.DEFAULT) {

    private val clock = CareerClock(tuning)

    /**
     * Play [fixtures] in date order.
     *
     * Fixtures are sorted by date and then by id, so a caller handing them over
     * in an arbitrary order gets the same season as one handing them over
     * sorted. Without that, a save file's list ordering would change results.
     */
    fun play(
        squad: List<Player>,
        fixtures: List<Fixture>,
        start: LocalDate,
        end: LocalDate,
        personality: SelectorPersonality,
        coaching: Double,
        random: CareerRandom,
    ): List<SeasonRecord> {
        require(!end.isBefore(start)) { "season ends before it starts: $start to $end" }
        val ordered = fixtures.sortedWith(compareBy<Fixture> { it.date }.thenBy { it.id })
        require(ordered.none { it.date < start || it.date > end }) {
            "a fixture falls outside the season $start..$end"
        }

        var players = squad
        val appearances = LinkedHashMap<PlayerId, MutableList<Appearance>>()
        val omissions = LinkedHashMap<PlayerId, Int>()
        val injuries = LinkedHashMap<PlayerId, Int>()
        var incumbents = emptySet<PlayerId>()
        var date = start

        for (fixture in ordered) {
            // The days between matches: rehab, recovery, rustiness, birthdays.
            players = clock.advance(players, date, fixture.date, random = random.stream(CareerStreams.AGEING))
            date = fixture.date

            val available = players.filter { it.state.isAvailable }
            if (available.size < XI) {
                // Not a squad that can field a side. Skipping is honest; padding
                // it with injured players would not be.
                continue
            }
            val context = SelectionContext(
                format = fixture.format,
                pitch = fixture.pitch,
                incumbents = incumbents,
            )
            val picked = Selection.pickXI(
                candidates = available,
                context = context,
                personality = personality,
                random = random.stream(CareerStreams.SELECTION),
                tuning = tuning.selection,
                size = XI,
            )
            val pickedIds = picked.map { it.player.id }.toSet()
            incumbents = pickedIds

            players.forEach { player ->
                if (player.state.isAvailable && player.id !in pickedIds) {
                    omissions[player.id] = (omissions[player.id] ?: 0) + 1
                }
            }

            players = players.map { player ->
                if (player.id !in pickedIds) return@map player
                val (appearance, played) = playOne(player, fixture, random)
                appearances.getOrPut(player.id) { mutableListOf() } += appearance
                if (played.state.isInjured && !player.state.isInjured) {
                    injuries[player.id] = (injuries[player.id] ?: 0) + 1
                }
                played
            }
        }

        // Run the clock out to the end of the season, so an injury picked up in
        // the last match heals over the off-season rather than at the start of
        // the next one.
        players = clock.advance(players, date, end, random = random.stream(CareerStreams.AGEING))

        return players.map { player ->
            SeasonRecord(
                player = player,
                appearances = appearances[player.id].orEmpty().toList(),
                omissions = omissions[player.id] ?: 0,
                injuries = injuries[player.id] ?: 0,
            )
        }
    }

    /**
     * One player's match: what he did, and what it left him as.
     *
     * The order matters and is the same order it happens in real life: he
     * plays, the performance moves his form, the workload moves his fatigue,
     * and only then is the injury rolled — on the body that just did the work,
     * not on the one that started the day.
     */
    private fun playOne(player: Player, fixture: Fixture, random: CareerRandom): Pair<Appearance, Player> {
        val world = random.stream(CareerStreams.WORLD)
        val innings = WorldSim.innings(player, fixture.format, fixture.oppositionStandard, world, tuning.world)

        val ballsBowled = ballsFor(player, fixture.format)
        val spell = WorldSim.spell(player, fixture.format, ballsBowled, fixture.oppositionStandard, world, tuning.world)

        val appearance = Appearance(
            fixture = fixture.id,
            date = fixture.date,
            format = fixture.format,
            level = fixture.level,
            runs = innings.runs,
            ballsFaced = innings.ballsFaced,
            out = innings.out,
            wickets = spell.wickets,
            runsConceded = spell.runsConceded,
            ballsBowled = spell.ballsBowled,
        )

        // Expectation is the reduced model's own mean for this player in these
        // conditions, so "surprise" means surprising for him rather than
        // surprising in the abstract. Scoring 30 is a failure for a great
        // player and a triumph for a tail-ender, and form should say so.
        val expected = expectedRuns(player, fixture)
        val performance = Performance(
            actual = innings.runs.toDouble(),
            expected = expected,
            expectedStdDev = expected * tuning.world.runsDispersion,
        )
        var state = FormModel.afterPerformance(player.state, performance, player.hidden.temperament, tuning.form)

        val paceOvers = if (player.bowlingStyle.kind == com.cricketcareer.engine.model.player.BowlingKind.PACE) {
            ballsBowled / 6.0
        } else {
            0.0
        }
        val spinOvers = if (player.bowlingStyle.kind == com.cricketcareer.engine.model.player.BowlingKind.SPIN) {
            ballsBowled / 6.0
        } else {
            0.0
        }
        state = FatigueModel.afterMatch(
            state,
            MatchWorkload(
                oversBowledPace = paceOvers,
                oversBowledSpin = spinOvers,
                ballsFaced = innings.ballsFaced,
                fieldingHours = fieldingHours(fixture.format),
            ),
            tuning.fatigue,
        )

        val played = player.copy(state = state)
        val injury = InjuryModel.roll(
            player = played,
            age = clock.ageOn(played, fixture.date),
            context = InjuryContext.MATCH,
            intensity = 1.0,
            random = random.stream(CareerStreams.INJURY),
            tuning = tuning.injury,
        )
        return appearance to if (injury == null) played else played.copy(state = state.copy(injury = injury))
    }

    /** The reduced model's mean for this player, without drawing from it. */
    private fun expectedRuns(player: Player, fixture: Fixture): Double {
        val standard = Selection.standardFor(player, fixture.format)
        val base = when {
            fixture.format.isMultiDay -> tuning.world.meanRunsMultiDay
            (fixture.format.oversPerInnings ?: 50) <= 20 -> tuning.world.meanRunsT20
            else -> tuning.world.meanRunsListA
        }
        val skill = 1.0 + tuning.world.standardToRuns * (standard - 0.5)
        val opposition = 1.0 - 0.6 * (fixture.oppositionStandard - 0.5)
        return (base * skill * opposition).coerceAtLeast(1.0)
    }

    /**
     * Balls a player bowls in a match of this format.
     *
     * A flat figure per role and format rather than a simulated spell, because
     * this is tier 2: nobody will ever read an individual over of it, and a
     * more elaborate model would be precision the tier cannot support.
     */
    private fun ballsFor(player: Player, format: MatchFormat): Int {
        if (!player.role.bowls) return 0
        return when {
            format.isMultiDay -> 150
            (format.oversPerInnings ?: 50) <= 20 -> 24
            else -> 54
        }
    }

    private fun fieldingHours(format: MatchFormat): Double = when {
        format.isMultiDay -> 5.0
        (format.oversPerInnings ?: 50) <= 20 -> 1.6
        else -> 3.6
    }

    private companion object {
        const val XI = 11
    }
}
