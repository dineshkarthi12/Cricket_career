package com.cricketcareer.harness

import com.cricketcareer.engine.rng.MatchRandom
import com.cricketcareer.engine.rng.RngStreams

/**
 * Entry point for bulk simulation and calibration reporting.
 *
 * Phase 0: the wiring only. There is no match engine yet, so this validates the
 * command line, proves :sim-harness can reach :engine, and prints the report
 * skeleton that Phase 2 fills in with real numbers.
 */
fun main(args: Array<String>) {
    val parsed = try {
        HarnessArgs.parse(args)
    } catch (e: IllegalArgumentException) {
        System.err.println("cricket-career sim-harness: ${e.message}")
        System.err.println()
        System.err.println("usage: --format=T20 --matches=5000 --seed=1 --report=calibration [--threads=8]")
        kotlin.system.exitProcess(2)
    }

    println("cricket-career :sim-harness")
    println("  format   ${parsed.format}")
    println("  matches  ${parsed.matches}")
    println("  seed     ${parsed.seed} (match n uses seed + n)")
    println("  report   ${parsed.report}")
    println("  threads  ${parsed.threads}")
    println()

    // Proves the seeding contract end to end: the harness can name a match and
    // reproduce it exactly. Phase 2 replaces this with a simulated innings.
    val sample = MatchRandom(parsed.seed)
    println("  seed check: stream '${RngStreams.EXECUTION}' first draw = ${sample.stream(RngStreams.EXECUTION).nextLong()}")
    println()
    println("No match engine yet - Phase 2 wires the delivery pipeline in here.")
    println("Calibration targets to be reported against are in docs/CALIBRATION.md.")
}
