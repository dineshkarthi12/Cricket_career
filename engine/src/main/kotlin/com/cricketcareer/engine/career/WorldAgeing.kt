package com.cricketcareer.engine.career

import com.cricketcareer.engine.config.CareerTuning
import com.cricketcareer.engine.generator.PlayerGenerator
import com.cricketcareer.engine.generator.PlayerSpec
import com.cricketcareer.engine.model.player.Player
import com.cricketcareer.engine.model.player.PlayerId
import com.cricketcareer.engine.model.world.BowlingMix
import com.cricketcareer.engine.model.world.LadderLevel
import com.cricketcareer.engine.model.world.Team
import com.cricketcareer.engine.rng.SimRandom
import com.cricketcareer.engine.seed.SeedDatabase
import java.time.LocalDate

/** Who left the game this season, and who came into it. */
data class WorldSeasonReport(
    val retired: List<Player>,
    val debutants: List<Player>,
) {
    val turnover: Int get() = retired.size
}

/**
 * A year happening to everybody else.
 *
 * Tier 3 of the world simulation: no balls are bowled, but the two thousand
 * nine hundred cricketers a career is played among get a year older, a year
 * better or worse, and some of them stop.
 *
 * Without it the world is a photograph. Everyone a player competes with is the
 * cricketer the seed file froze, so a place in a side never opens up through
 * anything but his own improvement, a twenty-year career ends against the same
 * men it started against, and the omission counts in a career report read high
 * for a reason nothing in the project could explain.
 *
 * See docs/CAREER_MODEL.md §11.
 */
object WorldAgeing {

    /**
     * Advance the whole world by one season.
     *
     * Returns a new database — nothing is mutated, because the career layer is
     * allowed to hold the world as it was at the start of a season and compare.
     *
     * The user's own player is **not** in here. He is advanced by the career
     * clock day by day, through matches he actually played; passing him through
     * this would age him twice.
     */
    fun advanceSeason(
        database: SeedDatabase,
        from: LocalDate,
        to: LocalDate,
        random: CareerRandom,
        tuning: CareerTuning = CareerTuning.DEFAULT,
        generator: PlayerGenerator = PlayerGenerator(),
        exclude: Set<PlayerId> = emptySet(),
    ): Pair<SeedDatabase, WorldSeasonReport> {
        require(!to.isBefore(from)) { "cannot advance the world backwards: $from to $to" }
        val clock = CareerClock(tuning)
        val ageingRng = random.stream(CareerStreams.AGEING)
        val generationRng = random.stream(CareerStreams.GENERATION)

        // Which rung each player belongs to, so his year is worth what the
        // cricket he plays is worth. A player in no squad is a free agent and
        // develops as if he were at the bottom of his country's ladder.
        val levelOf = LinkedHashMap<String, LadderLevel>(database.players.size)
        database.teams.forEach { team -> team.squad.forEach { levelOf[it.value] = team.level } }

        val aged = clock.advance(
            players = database.players.filterNot { it.id in exclude },
            from = from,
            to = to,
            exposure = database.players.associate { player ->
                val level = levelOf[player.id.value]
                player.id to SeasonExposure(
                    minutes = if (level == null) 0.0 else tuning.worldAgeing.squadExposure,
                    coaching = level?.coachingQuality ?: LadderLevel.COLLEGE.coachingQuality,
                )
            },
            random = ageingRng,
        )

        val kept = ArrayList<Player>(aged.size)
        val retired = ArrayList<Player>()
        aged.forEach { player ->
            val level = levelOf[player.id.value] ?: LadderLevel.DISTRICT_CLUB
            if (retires(player, level, clock.ageOn(player, to), ageingRng, tuning)) {
                retired += player
            } else {
                // He played a season. It simply was not simulated, and the
                // clock cannot tell the difference between a cricketer whose
                // matches went unmodelled and one who spent the winter in bed.
                kept += if (levelOf.containsKey(player.id.value)) {
                    player.copy(state = player.state.copy(sharpness = tuning.worldAgeing.seasonEndSharpness))
                } else {
                    player
                }
            }
        }

        // Everyone the user was holding on to passes through untouched.
        val untouched = database.players.filter { it.id in exclude }

        val (teams, debutants) = refill(database, kept, retired, to, generationRng, tuning, generator)

        val updated = database.copy(
            teams = teams,
            players = kept + untouched + debutants,
        )
        return updated to WorldSeasonReport(retired, debutants)
    }

    /**
     * Whether a cricketer walks away.
     *
     * Age and decline, and it is the second that makes it a decision rather
     * than a birthday: a thirty-five-year-old still worth his place goes on,
     * and one being carried does not. Nobody retires young, and nobody plays
     * forever.
     */
    fun retires(
        player: Player,
        level: LadderLevel,
        age: Int,
        random: SimRandom,
        tuning: CareerTuning = CareerTuning.DEFAULT,
    ): Boolean {
        val ageing = tuning.worldAgeing
        if (age < ageing.retirementFromAge) return false
        if (age >= ageing.retirementByAge) return true

        val yearsPast = age - ageing.retirementFromAge
        val standard = Selection.standardFor(player, com.cricketcareer.engine.model.world.MatchFormat.LIST_A)
        // How far under his rung he has fallen, 0 when he is still up to it.
        val shortfall = (level.standard - standard).coerceAtLeast(0.0)

        val hazard = ageing.retirementAgeHazard * yearsPast + ageing.retirementDeclineHazard * shortfall
        return random.chance(hazard.coerceIn(0.0, 1.0))
    }

    /**
     * Put the retired players' places back into the sides that lost them.
     *
     * A side that loses a thirty-eight-year-old replaces him from its academy,
     * not with another thirty-eight-year-old, which is why the debutants come
     * in young. It is also what keeps the pyramid the shape the seed file
     * describes: squads stay the size they were.
     *
     * **One replacement per retired player, not per vacancy.** A state
     * association fields a red-ball side and a white-ball side from the same
     * eighteen men, so a single retirement empties two squad slots. Generating
     * one newcomer for each slot grew the world by a sixth over twenty seasons
     * — measured, 2,903 cricketers became 3,332 — and quietly diluted every
     * side it touched.
     */
    private fun refill(
        database: SeedDatabase,
        kept: List<Player>,
        retired: List<Player>,
        today: LocalDate,
        random: SimRandom,
        tuning: CareerTuning,
        generator: PlayerGenerator,
    ): Pair<List<Team>, List<Player>> {
        if (retired.isEmpty()) return database.teams to emptyList()

        val stillPlaying = kept.map { it.id }.toSet()

        // The side a retiring player is replaced *into* is the strongest one he
        // was in: a state's white-ball and red-ball squads are the same men, and
        // his replacement inherits his whole membership.
        val squadsOf = LinkedHashMap<PlayerId, MutableList<Team>>()
        database.teams.forEach { team ->
            team.squad.forEach { id -> squadsOf.getOrPut(id) { mutableListOf() } += team }
        }

        val replacements = LinkedHashMap<PlayerId, PlayerId>()
        val debutants = ArrayList<Player>(retired.size)

        retired.forEachIndexed { index, leaving ->
            val sides = squadsOf[leaving.id].orEmpty()
            if (sides.isEmpty()) return@forEachIndexed
            val home = sides.maxBy { it.level.standard }
            val country = database.countriesById[home.country]
            // No age is specified: the generator already knows what a
            // cricketer at this rung looks like, and it draws one. Forcing
            // every replacement in at twenty filled the international side with
            // players who had not developed yet and cost it a tenth of its
            // standard over twenty seasons - an international side replaces a
            // retiring man with somebody who has already come through, not with
            // an academy graduate.
            val newcomer = generator.generate(
                rng = random,
                spec = PlayerSpec(
                    country = home.country,
                    region = home.region,
                    level = home.level,
                    bowlingMix = country?.bowlingMix ?: BowlingMix(),
                ),
                today = today,
                // Year in the id so a debutant can never collide with a player
                // who came through in an earlier season.
                id = PlayerId("${home.id}-D${today.year}-$index"),
            )
            debutants += newcomer
            replacements[leaving.id] = newcomer.id
        }

        val teams = database.teams.map { team ->
            if (team.squad.none { it !in stillPlaying }) return@map team
            team.copy(
                squad = team.squad.mapNotNull { id ->
                    if (id in stillPlaying) id else replacements[id]
                },
            )
        }
        return teams to debutants
    }
}
