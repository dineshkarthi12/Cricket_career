package com.cricketcareer.harness

/**
 * Command line for the calibration harness.
 *
 * Kept hand-rolled and dependency-free on purpose: this tool has to keep
 * working for the life of the project, and an arg parser is not worth a
 * transitive dependency tree.
 *
 *   --format=T20|LIST_A|FIRST_CLASS|TEST   what to simulate
 *   --matches=5000                          sample size
 *   --seed=1                                first seed; match n uses seed+n
 *   --report=calibration|scorecard|players|none   what to print
 *   --threads=8                             worker threads (default: all cores)
 */
data class HarnessArgs(
    val format: String,
    val matches: Int,
    val seed: Long,
    val report: String,
    val threads: Int,
) {
    companion object {
        val KNOWN_FORMATS = listOf("T20", "LIST_A", "FIRST_CLASS", "TEST")
        val KNOWN_REPORTS = listOf("calibration", "scorecard", "players", "export", "none")

        fun parse(args: Array<String>): HarnessArgs {
            val values = LinkedHashMap<String, String>()
            args.forEach { arg ->
                require(arg.startsWith("--")) { "unrecognised argument '$arg' (expected --key=value)" }
                val body = arg.removePrefix("--")
                val separator = body.indexOf('=')
                require(separator > 0) { "argument '$arg' needs a value, e.g. --matches=5000" }
                values[body.substring(0, separator)] = body.substring(separator + 1)
            }

            val unknown = values.keys - setOf("format", "matches", "seed", "report", "threads")
            require(unknown.isEmpty()) { "unknown option(s): ${unknown.joinToString(", ")}" }

            val format = (values["format"] ?: "T20").uppercase()
            require(format in KNOWN_FORMATS) {
                "unknown format '$format' (expected one of ${KNOWN_FORMATS.joinToString(", ")})"
            }

            val matches = (values["matches"] ?: "1000").toIntOrNull()
                ?: throw IllegalArgumentException("--matches must be a whole number")
            require(matches > 0) { "--matches must be positive, was $matches" }

            val seed = (values["seed"] ?: "1").toLongOrNull()
                ?: throw IllegalArgumentException("--seed must be a whole number")

            val report = values["report"] ?: "calibration"
            require(report in KNOWN_REPORTS) {
                "unknown report '$report' (expected one of ${KNOWN_REPORTS.joinToString(", ")})"
            }

            val threads = (values["threads"] ?: Runtime.getRuntime().availableProcessors().toString())
                .toIntOrNull() ?: throw IllegalArgumentException("--threads must be a whole number")
            require(threads > 0) { "--threads must be positive, was $threads" }

            return HarnessArgs(format, matches, seed, report, threads)
        }
    }
}
