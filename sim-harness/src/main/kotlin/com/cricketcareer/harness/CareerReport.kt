package com.cricketcareer.harness

import com.cricketcareer.engine.career.CareerClock
import com.cricketcareer.engine.career.CareerPosition
import com.cricketcareer.engine.career.CareerRandom
import com.cricketcareer.engine.career.CareerStreams
import com.cricketcareer.engine.career.FixtureList
import com.cricketcareer.engine.career.Ladder
import com.cricketcareer.engine.career.Movement
import com.cricketcareer.engine.career.Progression
import com.cricketcareer.engine.career.Retirement
import com.cricketcareer.engine.career.Selection
import com.cricketcareer.engine.career.Season
import com.cricketcareer.engine.career.SeasonExposure
import com.cricketcareer.engine.career.SeasonRecord
import com.cricketcareer.engine.career.SelectorPersonality
import com.cricketcareer.engine.career.WorldAgeing
import com.cricketcareer.engine.config.CareerTuning
import com.cricketcareer.engine.generator.PlayerGenerator
import com.cricketcareer.engine.generator.PlayerSpec
import com.cricketcareer.engine.model.player.Attribute
import com.cricketcareer.engine.model.player.Player
import com.cricketcareer.engine.model.player.PlayerId
import com.cricketcareer.engine.model.world.LadderLevel
import com.cricketcareer.engine.model.world.MatchFormat
import com.cricketcareer.engine.rng.SimRandom
import com.cricketcareer.engine.seed.SeedDatabase
import java.io.File
import java.time.LocalDate

/**
 * A whole career, printed, in the world the game ships with.
 *
 * The career layer's equivalent of the calibration report: the point is to be
 * able to read twenty years of one cricketer's life and see whether it looks
 * like a career or like a spreadsheet. A distribution test cannot tell you that
 * a player peaked at 24 and was finished at 29, or that nobody in the world
 * ever gets picked for their zone; a printed career can, and has.
 *
 *   ./gradlew :sim-harness:run --args="--report=career --matches=20 --seed=77"
 *
 * `--matches` is read as the number of seasons.
 */
object CareerReport {

    private const val SEASON_START_MONTH = 4
    private const val COACHING_FALLBACK = 0.6

    fun run(args: HarnessArgs, seedDirectory: String) {
        val database = load(seedDirectory) ?: return
        val seasons = args.matches
        val tuning = CareerTuning.DEFAULT
        val careerRandom = CareerRandom(args.seed)
        val generator = PlayerGenerator()

        // One created cricketer, from a real state, starting where a real
        // eighteen-year-old starts: his district side.
        //
        // Generated with the *potential* of the rung he might reach rather than
        // the one he is on. That is deliberate and it is the point of the
        // report: a player drawn from the district distribution stays in the
        // district league, which is true of almost every district cricketer and
        // makes for twenty-two identical lines. A career is worth printing when
        // there is a career in it.
        val firstSeason = LocalDate.of(2026, SEASON_START_MONTH, 1)
        var subject = generator.generate(
            rng = careerRandom.stream(CareerStreams.GENERATION),
            spec = PlayerSpec(country = HOME, region = HOME_REGION, level = PROSPECT_LEVEL),
            today = firstSeason,
            id = PlayerId("YOU"),
        ).let { it.copy(dateOfBirth = firstSeason.minusYears(START_AGE)) }

        var position = Progression.startingPosition(subject, database)
        if (position == null) {
            println("No ladder for a ${subject.region} cricketer in this database.")
            return
        }

        val clock = CareerClock(tuning)
        val season = Season(tuning)
        // The world is not a photograph: everyone he is competing with ages,
        // declines and eventually stops, and their places are taken by people
        // who were not there when he started.
        var world = database
        var retired = 0
        var debutants = 0
        // A career needs a memory of its own best, because a cricketer is
        // driven out by being unable to do what he *could* do.
        var peakStandard = 0.0
        var accumulatedDamage = 0
        var idleSeasons = 0
        var farewell: com.cricketcareer.engine.career.RetirementProspect? = null

        println("Career report - $seasons seasons in the shipped world, seed ${args.seed}")
        println("Subject: ${subject.name.full} (${subject.role.displayName}), ${subject.region}")
        println("Ladder:  " + Ladder.forPlayer(subject, database).joinToString(" -> ") { it.level.displayName })
        println()
        println(HEADER)
        println("-".repeat(HEADER.length))

        val career = ArrayList<SeasonRecord>(seasons)
        repeat(seasons) { year ->
            val at = checkNotNull(position)
            val team = checkNotNull(world.teamsById[at.teamId])
            val fixtures = FixtureList.seasonFor(
                teamId = at.teamId,
                seasonYear = 2026 + year,
                database = world,
                random = careerRandom.stream(CareerStreams.WORLD),
            )
            // The season runs from the first fixture to the last; the clock
            // owns the gap on either side.
            val from = fixtures.first().date.minusDays(1)
            val to = fixtures.last().date.plusDays(LAST_MATCH_DAYS)

            // He is in the side's squad, in place of whoever the generator put
            // there. Until the world simulation ages the rest of the database
            // (tier 3), everyone around him is the player the seed file froze.
            val squad = listOf(subject) + world.squadOf(at.teamId).drop(1)
            val personality = SelectorPersonality.draw(careerRandom.stream(CareerStreams.SELECTION))

            val records = season.play(
                squad = squad,
                fixtures = fixtures,
                start = from,
                end = to,
                personality = personality,
                coaching = at.level.coachingQuality.takeIf { it > 0.0 } ?: COACHING_FALLBACK,
                random = CareerRandom(careerRandom.matchSeed("season:$year")),
                previousExposure = mapOf(subject.id to (career.lastOrNull()?.exposure ?: SeasonExposure.NONE)),
            )
            val record = records.first { it.player.id == subject.id }
            career += record
            subject = record.player

            val verdict = Progression.review(
                player = subject,
                record = record,
                position = at,
                database = world,
                personality = personality,
                random = careerRandom.stream(CareerStreams.SELECTION),
                tuning = tuning,
            )
            println(line(record, clock.ageOn(subject, to), team.shortName, at.level, verdict.movement))

            position = verdict.position
            val nextSeason = LocalDate.of(2027 + year, SEASON_START_MONTH, 1)

            peakStandard = maxOf(peakStandard, Selection.standardFor(subject, MatchFormat.LIST_A))
            // Only what an injury actually took for good. The running total is
            // the career layer's memory: nothing on the player carries it.
            accumulatedDamage += subject.state.injury?.permanentDamage ?: 0
            idleSeasons = if (record.matches == 0) idleSeasons + 1 else 0

            val prospect = Retirement.consider(
                player = subject,
                age = clock.ageOn(subject, to),
                level = at.level,
                peakStandard = peakStandard,
                seasonsWithoutCricket = idleSeasons,
                careerMatches = career.sumOf { it.matches },
                accumulatedDamage = accumulatedDamage,
                nowhereLeftToPlay = false,
                tuning = tuning,
            )
            if (Retirement.decide(prospect, careerRandom.stream(CareerStreams.AGEING))) {
                farewell = prospect
                return@repeat
            }

            // A year happens to everybody else too. His own player is excluded:
            // the career clock below advances him through the cricket he
            // actually played, and passing him through here would age him twice.
            val (nextWorld, worldReport) = WorldAgeing.advanceSeason(
                database = world,
                from = from,
                to = nextSeason,
                random = CareerRandom(careerRandom.matchSeed("world:$year")),
                tuning = tuning,
                exclude = setOf(subject.id),
            )
            world = nextWorld
            retired += worldReport.retired.size
            debutants += worldReport.debutants.size
            if (to.isBefore(nextSeason)) {
                // Skip the off-season and a player whose birthday falls in it
                // never ages at all - which is exactly what this report showed
                // the first time it was run.
                subject = clock.advance(
                    players = listOf(subject),
                    from = to,
                    to = nextSeason,
                    exposure = mapOf(subject.id to record.exposure),
                    random = SimRandom.fromSeed(careerRandom.matchSeed("offseason:$year")),
                ).first()
            }
        }

        println()
        summarise(career, checkNotNull(position))
        println("        $retired cricketers retired around him, $debutants came through")
        farewell?.let { prospect ->
            println()
            println("Retired.")
            prospect.pressures.filter { it.isReal }.forEach { pressure ->
                println("  %3.0f%%  %s".format(100 * pressure.weight, pressure.reason))
            }
        }
    }

    private fun load(directory: String): SeedDatabase? {
        val world = File(directory, "world.json")
        val players = File(directory, "players.json")
        if (!world.isFile || !players.isFile) {
            println("No seed database in ${File(directory).absolutePath}.")
            println("Build one first:  ./gradlew :sim-harness:run --args=\"--report=seed\"")
            return null
        }
        return SeedDatabase.load(world = world.readText(), players = players.readText())
    }

    private const val HOME = "IND"
    private const val HOME_REGION = "Maharashtra"
    private const val START_AGE = 18L

    /**
     * The potential the subject is drawn with.
     *
     * Not where he starts - he starts at his district side like everybody else.
     * This is how good he could become.
     */
    private val PROSPECT_LEVEL = LadderLevel.INTERNATIONAL

    /** Days charged for the last match, so a five-day Test is inside the season. */
    private const val LAST_MATCH_DAYS = 6L

    private const val HEADER =
        "Age  Side  Level             Mat   Runs   HS    Avg     SR  50 100  Wkt  Omit Inj  Then"

    private fun line(
        record: SeasonRecord,
        age: Int,
        side: String,
        level: LadderLevel,
        movement: Movement,
    ): String {
        val balls = record.appearances.sumOf { it.ballsFaced }
        val strikeRate = if (balls == 0) 0.0 else record.runs * 100.0 / balls
        return "%3d  %-5s %-16s %4d %6d %4d %6s %6.1f %3d %3d %4d %5d %3d  %s".format(
            age,
            side.take(5),
            level.displayName.take(16),
            record.matches,
            record.runs,
            record.highestScore,
            record.battingAverage?.let { "%.2f".format(it) } ?: "-",
            strikeRate,
            record.fifties,
            record.hundreds,
            record.wickets,
            record.omissions,
            record.injuries,
            movement.displayName,
        )
    }

    private fun summarise(career: List<SeasonRecord>, finished: CareerPosition) {
        val matches = career.sumOf { it.matches }
        val runs = career.sumOf { it.runs }
        val dismissals = career.sumOf { it.dismissals }
        val best = career.maxOfOrNull { it.highestScore } ?: 0
        val peak = career.withIndex().maxByOrNull { it.value.player.attributes[Attribute.TECHNIQUE] }

        println("Career: $matches matches, $runs runs at ${"%.2f".format(runs.toDouble() / maxOf(dismissals, 1))}")
        println("        best $best, ${career.sumOf { it.fifties }} fifties, ${career.sumOf { it.hundreds }} hundreds")
        println("        ${career.sumOf { it.wickets }} wickets")
        println("        ${career.sumOf { it.omissions }} times left out, ${career.sumOf { it.injuries }} injuries")
        println("        finished at ${finished.level.displayName} with ${finished.teamId}")
        if (peak != null) {
            println("        technique peaked in season ${peak.index + 1} at ${peak.value.player.attributes[Attribute.TECHNIQUE]}")
        }
    }
}
