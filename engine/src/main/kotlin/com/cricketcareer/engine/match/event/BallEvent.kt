package com.cricketcareer.engine.match.event

import com.cricketcareer.engine.match.delivery.BowlerIntent
import com.cricketcareer.engine.match.delivery.CommentaryFacts
import com.cricketcareer.engine.match.delivery.Contact
import com.cricketcareer.engine.match.delivery.DeliveredBall
import com.cricketcareer.engine.match.delivery.FieldingResolution
import com.cricketcareer.engine.match.delivery.PerceivedBall
import com.cricketcareer.engine.match.delivery.Release
import com.cricketcareer.engine.match.delivery.ShotSelection
import com.cricketcareer.engine.match.delivery.Trajectory
import com.cricketcareer.engine.match.state.DeliveryOutcome
import com.cricketcareer.engine.model.player.PlayerId

/** Which ball this was. */
data class BallId(val innings: Int, val over: Int, val ballInOver: Int, val legalBallIndex: Int) {
    /** "12.4" */
    override fun toString(): String = "$over.$ballInOver"
}

/**
 * The full causal record of one delivery.
 *
 * Everything downstream — commentary, wagon wheel, pitch map, beehive,
 * regression diffs — is a pure function of a stream of these. The cost is
 * memory; what it buys is that "thick outside edge, flew at catchable height
 * through a vacant fourth slip, four" is a sentence assembled from what actually
 * happened rather than a canned string picked by outcome.
 *
 * Only retained for matches being watched; see docs/ARCHITECTURE.md §3.
 */
data class BallEvent(
    val id: BallId,
    val striker: PlayerId,
    val nonStriker: PlayerId,
    val bowler: PlayerId,
    val intent: BowlerIntent,
    val release: Release,
    val delivered: DeliveredBall,
    val perceived: PerceivedBall,
    val shot: ShotSelection,
    val contact: Contact,
    val trajectory: Trajectory?,
    val fielding: FieldingResolution?,
    val outcome: DeliveryOutcome,
    val facts: CommentaryFacts,
    val pressure: Double,
    val scoreAfter: Int,
    val wicketsAfter: Int,
)

/**
 * Where ball events go.
 *
 * A strategy rather than a flag checked per field: the background tiers install
 * [Discard] and pay one virtual call per ball, while a watched match installs a
 * recorder. That is what keeps the Tier B budget of ~8 microseconds a ball
 * reachable (docs/ARCHITECTURE.md §7).
 */
fun interface BallEventSink {
    fun accept(event: BallEvent)

    companion object {
        /** Costs one call and nothing else. */
        val Discard: BallEventSink = BallEventSink { }
    }
}

/** Keeps every event. For the match the user is watching, and for tests. */
class RecordingSink : BallEventSink {
    private val events = mutableListOf<BallEvent>()
    override fun accept(event: BallEvent) {
        events += event
    }

    fun events(): List<BallEvent> = events.toList()
    val size: Int get() = events.size
}
