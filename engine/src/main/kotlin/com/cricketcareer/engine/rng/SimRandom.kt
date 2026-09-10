package com.cricketcareer.engine.rng

/**
 * The engine's only source of randomness.
 *
 * Determinism is a non-negotiable requirement (same seed + same inputs = same
 * result, forever), which rules out `kotlin.random.Random.Default`,
 * `java.util.Random` (whose algorithm is fixed but whose use is easy to leak
 * across threads) and anything touching the clock. Every draw in the simulation
 * comes from an instance of this class.
 *
 * Algorithm: xoshiro256** seeded through SplitMix64. Chosen because it is
 * small, fast (the harness makes billions of draws), has a well understood
 * period (2^256 - 1) and — the part that matters here — is fully specified, so
 * a saved seed replays identically on any JVM, any platform, any Kotlin version.
 *
 * NOT thread safe by design. One instance is owned by exactly one simulation.
 * Parallelism in the harness comes from running whole matches concurrently,
 * each with its own seed, never from sharing a generator.
 */
class SimRandom private constructor(
    private var s0: Long,
    private var s1: Long,
    private var s2: Long,
    private var s3: Long,
) {

    /** Raw 64 bits. Every other method is built on this. */
    fun nextLong(): Long {
        // xoshiro256** scrambler: rotl(s1 * 5, 7) * 9
        val result = java.lang.Long.rotateLeft(s1 * 5, 7) * 9
        val t = s1 shl 17
        s2 = s2 xor s0
        s3 = s3 xor s1
        s1 = s1 xor s2
        s0 = s0 xor s3
        s2 = s2 xor t
        s3 = java.lang.Long.rotateLeft(s3, 45)
        return result
    }

    /** Uniform in [0.0, 1.0). Uses the top 53 bits, which are the good ones. */
    fun nextDouble(): Double = (nextLong() ushr 11) * DOUBLE_UNIT

    /** Uniform in [min, max). */
    fun nextDouble(min: Double, max: Double): Double = min + nextDouble() * (max - min)

    /**
     * Uniform in [0, boundExclusive). Rejection sampled so the distribution is
     * exactly flat — a modulo would bias low values, which matters when we use
     * this to pick a fielder or a shot.
     */
    fun nextInt(boundExclusive: Int): Int {
        require(boundExclusive > 0) { "bound must be positive, was $boundExclusive" }
        val bound = boundExclusive.toLong()
        while (true) {
            val bits = nextLong() ushr 1
            val value = bits % bound
            // Reject the short final block that would otherwise be over-sampled.
            if (bits - value + (bound - 1) >= 0) return value.toInt()
        }
    }

    /** Uniform in [minInclusive, maxExclusive). */
    fun nextInt(minInclusive: Int, maxExclusive: Int): Int =
        minInclusive + nextInt(maxExclusive - minInclusive)

    /**
     * Standard normal, mean 0 and standard deviation 1.
     *
     * Box-Muller rather than Marsaglia polar: polar rejects samples, so it
     * consumes a variable number of underlying draws. Box-Muller always consumes
     * exactly two, which keeps a stream's consumption predictable and makes
     * regression diffs far easier to read when the maths changes.
     *
     * The second variate is deliberately discarded rather than cached, for the
     * same reason: caching would make the cost of a call depend on the parity of
     * the call count.
     */
    fun nextGaussian(): Double {
        var u1 = nextDouble()
        // ln(0) is -infinity; the probability is ~2^-53 but this runs billions of times.
        if (u1 < MIN_POSITIVE_UNIFORM) u1 = MIN_POSITIVE_UNIFORM
        val u2 = nextDouble()
        val radius = kotlin.math.sqrt(-2.0 * kotlin.math.ln(u1))
        return radius * kotlin.math.cos(TWO_PI * u2)
    }

    /** Normal with the given mean and standard deviation. */
    fun nextGaussian(mean: Double, standardDeviation: Double): Double =
        mean + standardDeviation * nextGaussian()

    /** True with probability [probability]. Clamped, so callers may pass raw maths output. */
    fun chance(probability: Double): Boolean = when {
        probability <= 0.0 -> false
        probability >= 1.0 -> true
        else -> nextDouble() < probability
    }

    /** Uniform choice from a list. */
    fun <T> pick(options: List<T>): T {
        require(options.isNotEmpty()) { "cannot pick from an empty list" }
        return options[nextInt(options.size)]
    }

    /**
     * Weighted choice. [weights] must be non-negative and must line up with
     * [options]; this is the primitive behind every softmax in the engine
     * (bowler intent, batter shot selection, field placement).
     */
    fun <T> pickWeighted(options: List<T>, weights: DoubleArray): T {
        require(options.size == weights.size) {
            "options (${options.size}) and weights (${weights.size}) must be the same length"
        }
        require(options.isNotEmpty()) { "cannot pick from an empty list" }
        var total = 0.0
        for (w in weights) {
            require(w >= 0.0 && w.isFinite()) { "weights must be finite and non-negative, was $w" }
            total += w
        }
        require(total > 0.0) { "at least one weight must be positive" }

        var target = nextDouble() * total
        for (i in options.indices) {
            target -= weights[i]
            if (target < 0.0) return options[i]
        }
        // Only reachable through floating point drift on the last element.
        return options[options.size - 1]
    }

    companion object {
        private const val DOUBLE_UNIT = 1.0 / (1L shl 53)
        private const val TWO_PI = 2.0 * kotlin.math.PI
        private const val MIN_POSITIVE_UNIFORM = 1.0e-300
        private const val GOLDEN_GAMMA = -0x61c8864680b583ebL // SplitMix64's odd increment

        /**
         * Builds a generator from a single seed.
         *
         * The seed is expanded through SplitMix64 so that adjacent seeds
         * (1, 2, 3 - exactly what a test loop produces) give completely
         * unrelated streams rather than correlated ones.
         */
        fun fromSeed(seed: Long): SimRandom {
            var z = seed
            fun next(): Long {
                z += GOLDEN_GAMMA
                var x = z
                x = (x xor (x ushr 30)) * -0x40a7b892e31b1a47L
                x = (x xor (x ushr 27)) * -0x6b2fb644ecceee15L
                return x xor (x ushr 31)
            }
            return SimRandom(next(), next(), next(), next())
        }

        /**
         * Derives a named sub-seed from a parent seed.
         *
         * This is the mechanism that makes the engine refactorable. Each
         * simulation stage draws from its own stream (see [RngStreams]), so
         * adding, removing or reordering a draw inside the execution model does
         * not shift the numbers the fielding model sees. Without this, any
         * change to any stage invalidates every regression baseline in the
         * project.
         *
         * Depends only on (seed, name) — never on how many draws have been taken
         * — so it is stable no matter where in the match it is called.
         */
        fun deriveSeed(seed: Long, name: String): Long {
            // FNV-1a 64 rather than String.hashCode(): 64 bits, and no dependence
            // on a JDK implementation detail we would rather not bet a save file on.
            var hash = -0x340d631b7bdddcdbL
            for (ch in name) {
                hash = hash xor ch.code.toLong()
                hash *= 0x100000001b3L
            }
            var mixed = seed xor hash
            mixed = (mixed xor (mixed ushr 33)) * -0x7ee3623a03d3c83fL
            mixed = (mixed xor (mixed ushr 29)) * -0x3b314601e57a13adL
            return mixed xor (mixed ushr 32)
        }
    }
}
