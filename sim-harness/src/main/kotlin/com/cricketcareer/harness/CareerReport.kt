package com.cricketcareer.harness

import com.cricketcareer.engine.career.CareerClock
import com.cricketcareer.engine.career.CareerRandom
import com.cricketcareer.engine.career.Fixture
import com.cricketcareer.engine.career.Season
import com.cricketcareer.engine.career.SeasonRecord
import com.cricketcareer.engine.career.SelectorPersonality
import com.cricketcareer.engine.config.CareerTuning
import com.cricketcareer.engine.generator.PlayerGenerator
import com.cricketcareer.engine.generator.PlayerSpec
import com.cricketcareer.engine.model.player.Attribute
import com.cricketcareer.engine.model.player.Player
import com.cricketcareer.engine.model.world.LadderLevel
import com.cricketcareer.engine.model.world.MatchFormat
import com.cricketcareer.engine.rng.SimRandom
import java.time.LocalDate

/**
 * A whole career, printed.
 *
 * The career layer's equivalent of the calibration report: the point is to be
 * able to read twenty years of one cricketer's life and see whether it looks
 * like a career or like a spreadsheet. A distribution test cannot tell you that
 * a player peaked at 24 and was finished at 29; a printed career can.
 *
 *   ./gradlew :sim-harness:run --args="--report=career --matches=20 --seed=77"
 *
 * `--matches` is read as the number of seasons.
 */
object CareerReport {

    private const val SEASON_START_MONTH = 4
    private const val FIXTURES_PER_SEASON = 16
    private const val DAYS_BETWEEN_FIXTURES = 6L

    fun run(args: HarnessArgs) {
        val seasons = args.matches
        val format = formatFor(args.format)
        val generator = PlayerGenerator()
        val squadRandom = SimRandom.fromSeed(args.seed)

        // A squad of twenty, so places are genuinely contested: an XI picked
        // from eleven players is not a selection model, it is a list.
        val firstSeason = LocalDate.of(2026, SEASON_START_MONTH, 1)
        var squad: List<Player> = generator.generateSquad(
            rng = squadRandom,
            spec = PlayerSpec(country = "AVG", region = "AVG", level = LadderLevel.STATE_WHITE_BALL),
            size = 20,
            today = firstSeason,
            idPrefix = "P",
        )
        // The youngest man in the squad, because a career report is only
        // interesting if there is a career left to watch.
        val subject = squad.maxBy { it.dateOfBirth }.id
        val clock = CareerClock(CareerTuning.DEFAULT)
        val season = Season(CareerTuning.DEFAULT)
        val personality = SelectorPersonality.draw(squadRandom)

        val subjectPlayer = squad.first { it.id == subject }
        println("Career report - $seasons seasons of ${format.displayName}, seed ${args.seed}")
        println("Subject: ${subjectPlayer.name.full} (${subjectPlayer.role.displayName})")
        println(
            "Panel: loyalty %.2f  boldness %.2f  form weighting %.2f"
                .format(personality.loyalty, personality.boldness, personality.formWeighting),
        )
        println()
        println(HEADER)
        println("-".repeat(HEADER.length))

        val career = ArrayList<SeasonRecord>(seasons)
        var exposure = emptyMap<com.cricketcareer.engine.model.player.PlayerId, com.cricketcareer.engine.career.SeasonExposure>()
        repeat(seasons) { year ->
            val from = LocalDate.of(2026 + year, SEASON_START_MONTH, 1)
            val to = from.plusMonths(6)
            val fixtures = List(FIXTURES_PER_SEASON) { i ->
                Fixture(
                    id = "Y%02d-F%02d".format(year, i),
                    date = from.plusDays(7 + i * DAYS_BETWEEN_FIXTURES),
                    format = format,
                    level = LadderLevel.STATE_WHITE_BALL,
                )
            }
            val records = season.play(
                squad = squad,
                fixtures = fixtures,
                start = from,
                end = to,
                personality = personality,
                coaching = 0.65,
                random = CareerRandom(args.seed + year),
                previousExposure = exposure,
            )
            squad = records.map { it.player }
            exposure = records.associate { it.player.id to it.exposure }
            // Season.play owns start..end and nothing outside it, so the gap
            // to the next season is the caller's. Skip it and a player whose
            // birthday falls in the off-season never ages at all - which is
            // exactly what this report showed the first time it was run.
            squad = clock.advance(
                players = squad,
                from = to,
                to = LocalDate.of(2027 + year, SEASON_START_MONTH, 1),
                exposure = exposure,
                random = SimRandom.fromSeed(args.seed + 90_000 + year),
            )
            val record = records.first { it.player.id == subject }
            career += record
            println(line(record, clock.ageOn(record.player, to)))
        }

        println()
        summarise(career)
    }

    // Form and sharpness are read on the season's last day, which is after the
    // off-season - a regular and a reserve both come back rusty, and that is
    // the point rather than a defect in the report.
    private const val HEADER =
        "Age  Mat   Runs   HS    Avg     SR   50  100  Omit  Inj  Expo   Tech Pow Fit Pace"

    private fun line(record: SeasonRecord, age: Int): String {
        val balls = record.appearances.sumOf { it.ballsFaced }
        val strikeRate = if (balls == 0) 0.0 else record.runs * 100.0 / balls
        val attributes = record.player.attributes
        return "%3d %4d %6d %4d %6s %6.1f %4d %4d %5d %4d %5.2f %6d %3d %3d %4d".format(
            age,
            record.matches,
            record.runs,
            record.highestScore,
            record.battingAverage?.let { "%.2f".format(it) } ?: "-",
            strikeRate,
            record.fifties,
            record.hundreds,
            record.omissions,
            record.injuries,
            record.exposure.minutes,
            attributes[Attribute.TECHNIQUE],
            attributes[Attribute.POWER],
            attributes[Attribute.FITNESS],
            attributes[Attribute.PACE],
        )
    }

    private fun summarise(career: List<SeasonRecord>) {
        val matches = career.sumOf { it.matches }
        val runs = career.sumOf { it.runs }
        val dismissals = career.sumOf { it.dismissals }
        val best = career.maxOfOrNull { it.highestScore } ?: 0
        val peak = career.withIndex().maxByOrNull { it.value.player.attributes[Attribute.TECHNIQUE] }

        println("Career: $matches matches, $runs runs at ${"%.2f".format(runs.toDouble() / maxOf(dismissals, 1))}")
        println("        best $best, ${career.sumOf { it.fifties }} fifties, ${career.sumOf { it.hundreds }} hundreds")
        println("        ${career.sumOf { it.omissions }} times left out, ${career.sumOf { it.injuries }} injuries")
        if (peak != null) {
            println("        technique peaked in season ${peak.index + 1} at ${peak.value.player.attributes[Attribute.TECHNIQUE]}")
        }
    }

    private fun formatFor(name: String): MatchFormat = when (name) {
        "T20" -> MatchFormat.T20
        "LIST_A" -> MatchFormat.LIST_A
        "FIRST_CLASS" -> MatchFormat.FOUR_DAY
        else -> MatchFormat.TEST
    }
}
