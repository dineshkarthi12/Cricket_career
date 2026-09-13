package com.cricketcareer.harness

import com.cricketcareer.engine.career.WorldSim
import com.cricketcareer.engine.config.CareerTuning
import com.cricketcareer.engine.config.EngineTuning
import com.cricketcareer.engine.fixtures.Fixtures
import com.cricketcareer.engine.match.delivery.Weather
import com.cricketcareer.engine.match.event.BallEvent
import com.cricketcareer.engine.match.event.BallEventSink
import com.cricketcareer.engine.match.sim.InningsSimulator
import com.cricketcareer.engine.model.world.LadderLevel
import com.cricketcareer.engine.model.world.MatchFormat
import com.cricketcareer.engine.rng.MatchRandom
import com.cricketcareer.engine.rng.SimRandom

/**
 * What the engine costs, against the budget in docs/ARCHITECTURE.md §7.
 *
 * The budget is set backwards from Phase 8's requirement — a full world season
 * in seconds on a mid-range phone — and it is a *per-ball* budget, so it has to
 * be measured per ball. This is the instrument that says whether the engine is
 * inside it, and the reason §7's rules (no allocation in the hot path outside
 * tier A, event capture as a strategy rather than a per-field flag check, no
 * strings in the hot path) were written into Phase 2 rather than retrofitted
 * here.
 *
 * Three tiers, three different costs:
 *
 * - **Tier A** is the user's own match. He is watching it, so it carries the
 *   full `BallEvent` stream and a generous budget.
 * - **Tier B** is his rivals' matches. Same pipeline, no events kept: the sink
 *   is a no-op and the difference between the two numbers is exactly what
 *   retaining events costs.
 * - **Tier C** is the rest of the world, reduced-form, budgeted per *match*
 *   rather than per ball because it never bowls one.
 *
 * ```
 * ./gradlew :sim-harness:run --args="--report=bench"
 * ```
 *
 * A laptop is not a phone. The report states measured figures and then the
 * same figures multiplied by [PHONE_PENALTY], which is the pessimistic end of
 * §7's "3-5x slower per operation on a mid-range phone" — the budget is judged
 * on the scaled number, because that is the machine the game ships on.
 */
object BenchReport {

    /** The pessimistic end of §7's estimate, so the budget is judged on the bad case. */
    private const val PHONE_PENALTY = 5.0

    /** Microseconds per ball, on the phone, allowed at each tier. */
    private const val TIER_A_BUDGET_US = 1000.0
    private const val TIER_B_BUDGET_US = 8.0

    /** Microseconds per reduced match, on the phone. */
    private const val TIER_C_BUDGET_US = 50.0

    /**
     * Innings run before the timer starts.
     *
     * The JIT has not finished with this code for the first few thousand balls,
     * and a benchmark that includes the interpreter measures the interpreter.
     */
    private const val WARMUP_INNINGS = 60

    /** Timed rounds of each tier, alternating. The best of them is reported. */
    private const val ROUNDS = 3

    private data class Line(
        val label: String,
        val perUnitUs: Double,
        val budgetUs: Double,
        val unit: String,
    ) {
        val onPhoneUs: Double get() = perUnitUs * PHONE_PENALTY
        val inside: Boolean get() = onPhoneUs <= budgetUs
        val headroom: Double get() = budgetUs / onPhoneUs
    }

    fun run(args: HarnessArgs) {
        val format = formatFor(args.format)
        val innings = args.matches.coerceAtLeast(200)

        println("Performance - ${format.displayName}, against the budget in ARCHITECTURE.md 7")
        println("Warming up...")
        simulate(format, WARMUP_INNINGS, args.seed, keepEvents = true)
        simulate(format, WARMUP_INNINGS, args.seed, keepEvents = false)
        warmReduced(2_000)

        // Alternating rounds, best of each.
        //
        // Running tier A to completion and then tier B charges tier B for
        // collecting tier A's garbage, and the first version of this report
        // duly reported tier B as *slower* than tier A — which is impossible
        // and was entirely an artefact of the measurement. Alternating shares
        // that cost between them, and taking the best round rather than the
        // mean reports the engine rather than whatever else the machine was
        // doing.
        var tierA = Double.MAX_VALUE
        var tierB = Double.MAX_VALUE
        var tierC = Double.MAX_VALUE
        repeat(ROUNDS) {
            tierA = minOf(tierA, simulate(format, innings, args.seed, keepEvents = true).perBallUs)
            tierB = minOf(tierB, simulate(format, innings, args.seed, keepEvents = false).perBallUs)
            tierC = minOf(tierC, reduced(50_000))
        }

        val lines = listOf(
            Line("Tier A - watched, events kept", tierA, TIER_A_BUDGET_US, "ball"),
            Line("Tier B - rivals, events discarded", tierB, TIER_B_BUDGET_US, "ball"),
            Line("Tier C - reduced-form world", tierC, TIER_C_BUDGET_US, "match"),
        )

        println()
        println("%-36s %11s %11s %9s %s".format("", "measured", "x5 (phone)", "budget", ""))
        println("-".repeat(80))
        lines.forEach { line ->
            println(
                "%-36s %8.2f us %8.2f us %6.0f us %s".format(
                    line.label, line.perUnitUs, line.onPhoneUs, line.budgetUs,
                    if (line.inside) "ok  (%.1fx headroom)".format(line.headroom)
                    else "OVER (%.2fx budget)".format(1.0 / line.headroom),
                ),
            )
        }

        println()
        println("What keeping the events costs: %.2f us/ball, %.0f%% on top of tier B"
            .format(tierA - tierB, (tierA / tierB - 1.0) * 100))

        // The number the budget was set backwards from. Volumes are
        // ARCHITECTURE.md 7's estimates for one season of one world.
        val seasonSeconds = (
            40 * 1_200 * tierA +
                300 * 800 * tierB +
                2_000 * tierC
            ) * PHONE_PENALTY / 1_000_000.0
        println()
        println("A whole season off-screen, on a phone: %.2f s (budget 5 s)".format(seasonSeconds))
        println(if (seasonSeconds <= 5.0) "Inside the budget." else "OVER the budget.")

        println()
        println(
            "Best of %d rounds of %d innings each, after %d warm-up innings."
                .format(ROUNDS, innings, WARMUP_INNINGS),
        )
        println("The phone column is the measured figure times %.0f, the pessimistic end of".format(PHONE_PENALTY))
        println("ARCHITECTURE.md 7's estimate. A laptop is not a phone and this is not a device test.")
    }

    private class Timing(val balls: Long, val nanos: Long) {
        val perBallUs: Double get() = nanos / 1_000.0 / balls
    }

    private fun simulate(format: MatchFormat, innings: Int, seed: Long, keepEvents: Boolean): Timing {
        val (batting, bowling) = Fixtures.averageTeams()
        var balls = 0L
        val started = System.nanoTime() // determinism-ok: timing the harness, not the simulation
        repeat(innings) { i ->
            // Tier A holds the stream; tier B installs a sink that throws it
            // away, which is the one virtual call per ball ARCHITECTURE.md 7
            // specifies instead of a flag check per field.
            val kept = if (keepEvents) ArrayList<BallEvent>(1_200) else null
            val sink = if (kept != null) BallEventSink { kept += it } else BallEventSink.Discard
            val state = InningsSimulator(
                format = format,
                battingSide = batting,
                bowlingSide = bowling,
                venue = Fixtures.AVERAGE_VENUE,
                pitch = Fixtures.AVERAGE_PITCH,
                weather = Weather.AVERAGE,
                level = LadderLevel.STATE_FIRST_CLASS,
                random = MatchRandom(seed + i),
                tuning = EngineTuning.DEFAULT,
                sink = sink,
            ).simulate(overLimit = if (format.isMultiDay) 130 else null)
            balls += state.legalBalls
        }
        val elapsed = System.nanoTime() - started // determinism-ok: harness timing
        return Timing(balls.coerceAtLeast(1), elapsed)
    }

    private fun warmReduced(count: Int) {
        reduced(count)
    }

    /** Microseconds per tier-C match. A reduced match is two innings. */
    private fun reduced(matches: Int): Double {
        val batter = Fixtures.averagePlayer("bench")
        val tuning = CareerTuning.DEFAULT.world
        val rng = SimRandom.fromSeed(99)
        val started = System.nanoTime() // determinism-ok: harness timing
        repeat(matches) {
            WorldSim.innings(batter, MatchFormat.LIST_A, 0.5, rng, tuning)
            WorldSim.innings(batter, MatchFormat.LIST_A, 0.5, rng, tuning)
        }
        return (System.nanoTime() - started) / 1_000.0 / matches // determinism-ok: harness timing
    }
}
