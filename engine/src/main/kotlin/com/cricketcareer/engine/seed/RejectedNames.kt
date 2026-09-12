package com.cricketcareer.engine.seed

/**
 * Names the database must not contain.
 *
 * Non-negotiable #6 says no licensed content: no real player names, no real
 * franchise names, no crests. Until now that rule lived as an absence — certain
 * names were simply deleted from `generator/NamePools.kt` — which is a rule
 * nobody can check and one careless edit can undo. The seed database is a file
 * the player is *invited* to edit, so it is exactly where a breach walks back
 * in.
 *
 * **This is a guard, not a legal review.** Two honest limits:
 *
 *  - **Franchise words are a closed set and are blocked outright.** There are
 *    only so many of them and every one is a trademark.
 *  - **Surnames are not.** Sharma, Khan, Patel and Singh belong to millions of
 *    people and blocking them would empty the name pools for no benefit. So a
 *    person is only rejected on a *full name* match, which catches the obvious
 *    case — someone typing a famous cricketer straight in — and nothing subtler.
 *
 * A user who edits the file to put a real player in it can still do so. That is
 * his business on his own device. What this stops is such a name shipping in
 * the repository, which is ours.
 */
object RejectedNames {

    /**
     * Words that only ever appear in a franchise name.
     *
     * Matched as whole words against a team name, case-insensitively, so
     * "Chennai" passes and "Chennai Super Kings" does not — which is the exact
     * line the brief draws: cities are fine, franchises are not.
     */
    val FRANCHISE_WORDS: Set<String> = setOf(
        "super", "kings", "indians", "royals", "capitals", "challengers",
        "knight", "riders", "titans", "sunrisers", "lions", "daredevils",
        "warriors", "strikers", "sixers", "stars", "scorchers", "renegades",
        "heat", "hurricanes", "thunder", "gladiators", "qalandars", "zalmi",
        "united", "originals", "invincibles", "fire", "rockets", "phoenix",
        "brave", "spirit", "superchargers", "sultans", "panthers",
    )

    /**
     * Full names of real cricketers.
     *
     * Deliberately short. A long list would be both unmaintainable and
     * misleading about how much protection it offers — the honest guarantee is
     * "an obvious paste is caught", not "no real person can appear".
     */
    val REAL_PLAYER_FULL_NAMES: Set<String> = setOf(
        "sachin tendulkar", "virat kohli", "rohit sharma", "ms dhoni",
        "mahendra singh dhoni", "jasprit bumrah", "ravindra jadeja",
        "rahul dravid", "sourav ganguly", "anil kumble", "kapil dev",
        "don bradman", "donald bradman", "brian lara", "jacques kallis",
        "ricky ponting", "shane warne", "muttiah muralitharan", "wasim akram",
        "ab de villiers", "kane williamson", "steve smith", "joe root",
        "ben stokes", "james anderson", "babar azam", "shaheen afridi",
    )

    /** True when [teamName] contains a franchise word. */
    fun isFranchiseName(teamName: String): Boolean =
        words(teamName).any { it in FRANCHISE_WORDS }

    /** True when [fullName] is one of the real cricketers above. */
    fun isRealPlayer(fullName: String): Boolean =
        normalise(fullName) in REAL_PLAYER_FULL_NAMES

    /** The offending word, for an error message that says what to change. */
    fun franchiseWordIn(teamName: String): String? =
        words(teamName).firstOrNull { it in FRANCHISE_WORDS }

    private fun words(text: String): List<String> = normalise(text).split(' ').filter { it.isNotEmpty() }

    /**
     * Lower case, punctuation to spaces, runs of spaces collapsed.
     *
     * The collapse is load-bearing: "Virat  Kohli" with two spaces would
     * otherwise walk straight past a list that has it with one, and a guard
     * that a stray keystroke defeats is not a guard.
     */
    private fun normalise(text: String): String = text
        .lowercase()
        .map { if (it.isLetterOrDigit()) it else ' ' }
        .joinToString("")
        .split(' ')
        .filter { it.isNotEmpty() }
        .joinToString(" ")
}
