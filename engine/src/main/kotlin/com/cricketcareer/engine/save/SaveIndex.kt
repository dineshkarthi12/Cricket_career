package com.cricketcareer.engine.save

/**
 * Several careers at once.
 *
 * The brief asks for multiple careers, and the only thing that actually makes
 * them hard is the list screen: opening every save to draw it is fine at three
 * careers and not at thirty. So the index is a *cache* of each save's summary,
 * and it is treated as one — [reconcile] rebuilds any entry whose save has
 * moved on, and the save is always the authority.
 *
 * There is no separate "current career" flag stored in each save. A flag in two
 * places is a flag that disagrees with itself; the index names the one being
 * played and nothing else does.
 */
data class SaveIndex(
    val entries: List<CareerSummary> = emptyList(),
    /** The career the player is in the middle of, if any. */
    val currentId: String? = null,
) {
    init {
        require(entries.map { it.id }.toSet().size == entries.size) {
            "two careers share an id: ${entries.map { it.id }}"
        }
        require(currentId == null || entries.any { it.id == currentId }) {
            "currentId '$currentId' is not one of the careers in the index"
        }
    }

    val current: CareerSummary? get() = entries.firstOrNull { it.id == currentId }

    /** Adds a career, or replaces the entry for one already here. */
    fun put(save: CareerSave): SaveIndex {
        val summary = save.summarise()
        val others = entries.filterNot { it.id == summary.id }
        return copy(entries = others + summary, currentId = currentId ?: summary.id)
    }

    /**
     * Forgets a career.
     *
     * When it was the one being played, the index stops naming a current
     * career rather than silently promoting another one: which career the
     * player wants next is his choice, and guessing it is how a game opens the
     * wrong save.
     */
    fun remove(id: String): SaveIndex = copy(
        entries = entries.filterNot { it.id == id },
        currentId = if (currentId == id) null else currentId,
    )

    fun select(id: String): SaveIndex {
        require(entries.any { it.id == id }) { "no career '$id' to select" }
        return copy(currentId = id)
    }

    /**
     * Rebuilds the index from the saves themselves.
     *
     * The index is a cache and this is what makes that safe: a career whose
     * save has been deleted outside the game drops out, one whose save has
     * moved on is re-summarised, and the current career survives only if its
     * save still does.
     */
    fun reconcile(saves: List<CareerSave>): SaveIndex {
        val summaries = saves.map { it.summarise() }
        // Most recently played first, which is the order a list screen wants
        // and the order a player expects. Ties break on id so the list is
        // stable rather than dependent on the filesystem's own order.
        val ordered = summaries.sortedWith(compareByDescending<CareerSummary> { it.today }.thenBy { it.id })
        return SaveIndex(
            entries = ordered,
            currentId = currentId?.takeIf { id -> ordered.any { it.id == id } },
        )
    }

    /** The careers this index holds, most recently played first. */
    fun ordered(): List<CareerSummary> =
        entries.sortedWith(compareByDescending<CareerSummary> { it.today }.thenBy { it.id })
}
