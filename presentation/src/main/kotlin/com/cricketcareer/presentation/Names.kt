package com.cricketcareer.presentation

import com.cricketcareer.engine.model.player.Player
import com.cricketcareer.engine.model.player.PlayerId

/**
 * Turns a [PlayerId] into something a person reads.
 *
 * The engine deals in ids because a name is not an identity: two cricketers can
 * share one, and a save file must survive a user renaming his own player. So
 * every screen needs this translation, and doing it inline in a composable is
 * how you end up with `PlayerId(value=AWAY-4)` on a scorecard in a release
 * build.
 *
 * An unknown id returns a visibly wrong marker rather than throwing. A screen
 * that cannot name one fielder should still render; a crash mid-innings loses
 * the match.
 */
class Names(players: Collection<Player>) {

    // LinkedHashMap: nothing here may depend on hash order, and a caller
    // iterating this gets the squad in the order it was handed over.
    private val byId: Map<PlayerId, Player> =
        LinkedHashMap<PlayerId, Player>(players.size).apply {
            players.forEach { put(it.id, it) }
        }

    /** "R Kulkarni" — the scorecard form, and the one almost every screen wants. */
    fun short(id: PlayerId): String = byId[id]?.name?.scorecard ?: unknown(id)

    /** "Rohan Kulkarni" — for a profile or a headline. */
    fun full(id: PlayerId): String = byId[id]?.name?.full ?: unknown(id)

    /**
     * "Kulkarni" — the how-out form.
     *
     * A scorecard names the batter as "R Kulkarni" in his own row and the
     * bowler as plain "Kulkarni" in everyone else's dismissal: "c Rane b
     * Kadam", never "c V Rane b M Kadam". Two different forms of the same name
     * on the same card, and getting it wrong makes the card look generated.
     */
    fun surname(id: PlayerId): String = byId[id]?.name?.family ?: unknown(id)

    fun player(id: PlayerId): Player? = byId[id]

    operator fun contains(id: PlayerId): Boolean = id in byId

    /**
     * Visibly wrong on purpose. A missing name is a bug in whoever assembled
     * the squad, and it should look like one in a screenshot rather than
     * quietly reading as a surname.
     */
    private fun unknown(id: PlayerId): String = "?${id.value}"

    companion object {
        val EMPTY: Names = Names(emptyList())
    }
}
