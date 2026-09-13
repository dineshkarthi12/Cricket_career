package com.cricketcareer.engine.save

import com.cricketcareer.engine.career.CareerPosition
import com.cricketcareer.engine.career.Difficulty
import com.cricketcareer.engine.fixtures.Fixtures
import com.cricketcareer.engine.model.world.LadderLevel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.LocalDate

class SaveIndexTest {

    private fun career(id: String, today: LocalDate = LocalDate.of(2026, 9, 1)) = CareerSave(
        id = id,
        name = id,
        careerSeed = id.hashCode().toLong(),
        difficulty = Difficulty.PROFESSIONAL,
        player = Fixtures.averagePlayer("user"),
        position = CareerPosition("southern-state", LadderLevel.STATE_FIRST_CLASS),
        today = today,
    )

    @Test
    fun `the first career added becomes the one being played`() {
        val index = SaveIndex().put(career("a"))
        assertEquals("a", index.currentId)
    }

    @Test
    fun `adding a second career does not steal the player out of the first`() {
        val index = SaveIndex().put(career("a")).put(career("b"))
        assertEquals("a", index.currentId)
        assertEquals(2, index.entries.size)
    }

    @Test
    fun `putting the same career twice updates it rather than duplicating it`() {
        val index = SaveIndex()
            .put(career("a"))
            .put(career("a", today = LocalDate.of(2027, 4, 1)))
        assertEquals(1, index.entries.size)
        assertEquals(LocalDate.of(2027, 4, 1), index.current!!.today)
    }

    @Test
    fun `deleting the career being played leaves none selected`() {
        // Rather than silently promoting another one: which career he wants
        // next is his choice, and guessing it is how a game opens the wrong
        // save.
        val index = SaveIndex().put(career("a")).put(career("b")).remove("a")
        assertNull(index.currentId)
        assertEquals(listOf("b"), index.entries.map { it.id })
    }

    @Test
    fun `an index cannot name a career it does not hold`() {
        assertThrows<IllegalArgumentException> {
            SaveIndex(entries = listOf(career("a").summarise()), currentId = "b")
        }
        assertThrows<IllegalArgumentException> { SaveIndex().put(career("a")).select("b") }
    }

    @Test
    fun `two careers cannot share an id`() {
        val one = career("a").summarise()
        assertThrows<IllegalArgumentException> { SaveIndex(entries = listOf(one, one)) }
    }

    @Test
    fun `reconciling drops careers whose saves are gone and refreshes the rest`() {
        val index = SaveIndex().put(career("a")).put(career("b")).select("b")
        val reconciled = index.reconcile(listOf(career("b", today = LocalDate.of(2028, 4, 1))))
        assertEquals(listOf("b"), reconciled.entries.map { it.id })
        assertEquals("b", reconciled.currentId)
        assertEquals(LocalDate.of(2028, 4, 1), reconciled.current!!.today)
    }

    @Test
    fun `reconciling forgets a current career whose save has gone`() {
        val index = SaveIndex().put(career("a")).put(career("b")).select("a")
        assertNull(index.reconcile(listOf(career("b"))).currentId)
    }

    @Test
    fun `careers list most recently played first`() {
        val index = SaveIndex()
            .put(career("old", today = LocalDate.of(2026, 4, 1)))
            .put(career("new", today = LocalDate.of(2030, 4, 1)))
        assertEquals(listOf("new", "old"), index.ordered().map { it.id })
    }
}
