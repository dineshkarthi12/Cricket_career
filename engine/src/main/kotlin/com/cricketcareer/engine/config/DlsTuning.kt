package com.cricketcareer.engine.config

/**
 * The Duckworth–Lewis resource model.
 *
 * A batting side's two resources are overs and wickets, and the model that
 * settles a rain-affected match is a statement about how much run-scoring
 * capacity remains given both. The published functional form is
 *
 * ```
 * Z(u, w) = asymptote[w] × (1 − exp(−decay[w] × u))
 * ```
 *
 * — runs a side with `w` down can still add in `u` overs. It is a saturating
 * curve for a real reason: a side with nine down cannot use forty overs, so its
 * curve flattens almost at once, while a side none down is still adding runs at
 * the fiftieth.
 *
 * **These numbers are measured, not copied.** They are fitted to this engine's
 * own output by `:sim-harness` and checked by `DuckworthLewisTest`, exactly as
 * `WorldTuning` is. That matters twice over: a table borrowed from real cricket
 * would settle matches by a scoring rate this simulation does not have, and
 * this project ships no licensed content (non-negotiable #6).
 *
 * Re-fit whenever the match engine's calibration moves:
 *
 * ```
 * ./gradlew :sim-harness:run --args="--report=dls --matches=4000"
 * ```
 *
 * See docs/SIMULATION_MODEL.md §12 and docs/CALIBRATION.md.
 */
data class DlsTuning(
    /**
     * Runs a side with *w* wickets down could still add given unlimited overs,
     * indexed by wickets lost, 0 to 9.
     *
     * The fall from index 0 to index 9 is the entire reason wickets are a
     * resource: it is worth about twenty-five times as much to have all ten in
     * hand as to have one.
     */
    val asymptote: List<Double> = listOf(
        379.2, 357.3, 334.6, 295.3, 244.8, 199.9, 159.3, 109.3, 71.8, 36.5,
    ),

    /**
     * How quickly a side with *w* down approaches its asymptote, per over.
     *
     * Rises steeply with wickets because a tail-end pair reaches everything it
     * is ever going to score within a few overs, while an opening pair is still
     * accelerating at the end of a fifty-over innings.
     */
    val decay: List<Double> = listOf(
        0.02543, 0.02626, 0.02710, 0.03035, 0.03654, 0.04410, 0.05414, 0.07758, 0.10533, 0.15827,
    ),

    /**
     * The average total for a full fifty-over innings, used when the side
     * batting second has **more** resource than the side batting first.
     *
     * It is needed because a proportional target would ask side two to score at
     * side one's rate over a longer innings, which is a harder task than side
     * one faced. The rule adds runs at the average scoring rate for the extra
     * resource instead. Measured from this engine, not assumed.
     */
    val averageFiftyOverTotal: Double = 269.5,

    /**
     * Overs in the innings the resource table is normalised against.
     *
     * Fifty, so that "100% of resources" means a full one-day innings and every
     * printed figure is comparable with the one-day cricket a player watches.
     * A T20 innings is simply a smaller share of the same table, which is what
     * the real method does too.
     */
    val referenceOvers: Double = 50.0,
) {
    init {
        require(asymptote.size == WICKET_STATES) { "asymptote needs $WICKET_STATES entries, has ${asymptote.size}" }
        require(decay.size == WICKET_STATES) { "decay needs $WICKET_STATES entries, has ${decay.size}" }
        asymptote.forEachIndexed { w, value ->
            require(value.isFinite() && value > 0.0) { "asymptote[$w] = $value must be positive" }
        }
        decay.forEachIndexed { w, value ->
            require(value.isFinite() && value > 0.0) { "decay[$w] = $value must be positive" }
        }
        // Monotonicity is not a tidiness rule: a table where losing a wicket
        // made a side better off would let a captain profit from a run out.
        require(asymptote.zipWithNext().all { (a, b) -> b < a }) {
            "asymptote must fall with every wicket lost: $asymptote"
        }
        require(decay.zipWithNext().all { (a, b) -> b > a }) {
            "decay must rise with every wicket lost: $decay"
        }
        // The product is the run rate off the first ball of the remaining
        // overs. If it rose with wickets lost, a side would score faster for
        // having lost one - and at short overs remaining the table would say a
        // wicket was worth having, which is the one thing it may never say.
        val rates = asymptote.indices.map { asymptote[it] * decay[it] }
        require(rates.zipWithNext().all { (a, b) -> b < a }) {
            "a side must not score faster for having lost a wicket: $rates"
        }
        require(averageFiftyOverTotal.isFinite() && averageFiftyOverTotal > 0.0) {
            "averageFiftyOverTotal $averageFiftyOverTotal"
        }
        require(referenceOvers.isFinite() && referenceOvers > 0.0) { "referenceOvers $referenceOvers" }
    }

    companion object {
        /** Wicket states a side can be in and still be batting: none down to nine down. */
        const val WICKET_STATES: Int = 10

        /**
         * Lazy on purpose.
         *
         * The constructor refuses an illegal table, so an eagerly-built default
         * meant that committing a bad one stopped the whole engine loading —
         * including `:sim-harness`, which is the tool for producing a good one.
         * A broken table must not be able to lock the door on its own repair.
         */
        val DEFAULT: DlsTuning by lazy { DlsTuning() }
    }
}
