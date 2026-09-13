package com.cricketcareer.presentation

import com.cricketcareer.engine.career.CareerPosition
import com.cricketcareer.engine.career.Difficulty
import com.cricketcareer.engine.generator.PlayerGenerator
import com.cricketcareer.engine.generator.PlayerSpec
import com.cricketcareer.engine.model.player.PlayerId
import com.cricketcareer.engine.rng.SimRandom
import com.cricketcareer.engine.model.world.LadderLevel
import com.cricketcareer.engine.save.CareerSave
import com.cricketcareer.engine.save.PlayedMatch
import com.cricketcareer.engine.save.SaveIndex
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate

class CareerSelectStateTest {

    // Any generated cricketer will do: this screen shows his name and nothing
    // else about him.
    private val player = PlayerGenerator().generate(
        rng = SimRandom.fromSeed(5),
        spec = PlayerSpec(country = "IND", region = "South", level = LadderLevel.STATE_FIRST_CLASS),
        today = LocalDate.of(2026, 4, 1),
        id = PlayerId("user"),
    )

    private fun career(
        id: String,
        seasons: Int = 3,
        matches: List<PlayedMatch> = emptyList(),
        today: LocalDate = LocalDate.of(2029, 4, 1),
        difficulty: Difficulty = Difficulty.PROFESSIONAL,
    ) = CareerSave(
        id = id,
        name = id,
        careerSeed = 1L,
        difficulty = difficulty,
        player = player,
        position = CareerPosition("southern-state", LadderLevel.STATE_FIRST_CLASS),
        today = today,
        seasonsPlayed = seasons,
        matches = matches,
    )

    private fun played(n: Int, runs: Int, out: Boolean = true) = PlayedMatch(
        fixture = "m$n",
        date = LocalDate.of(2027, 4, 1).plusDays(n.toLong()),
        formatId = "list-a",
        level = LadderLevel.STATE_FIRST_CLASS,
        opponent = "x",
        atHome = true,
        seed = n.toLong(),
        engineVersion = "test",
        runs = runs,
        ballsFaced = runs + 10,
        out = out,
    )

    @Test
    fun `an empty list says so instead of showing nothing at all`() {
        val state = careerSelectState(SaveIndex())
        assertTrue(state.careers.isEmpty())
        assertNotNull(state.emptyMessage)
    }

    @Test
    fun `a list with careers in it has no empty message`() {
        val state = careerSelectState(SaveIndex().put(career("a")))
        assertNull(state.emptyMessage)
        assertTrue(state.careers.single().isCurrent)
    }

    @Test
    fun `one season reads as one season`() {
        val state = careerSelectState(SaveIndex().put(career("a", seasons = 1, matches = listOf(played(1, 40)))))
        assertTrue(state.careers.single().summary.startsWith("1 season - 1 match")) {
            state.careers.single().summary
        }
    }

    @Test
    fun `a career with runs shows them grouped and averaged`() {
        val matches = List(30) { played(it, 40) }
        val state = careerSelectState(SaveIndex().put(career("a", matches = matches)))
        assertTrue(state.careers.single().summary.endsWith("1,200 runs at 40.00")) {
            state.careers.single().summary
        }
    }

    @Test
    fun `a career that has not started yet says so rather than dividing by zero`() {
        val state = careerSelectState(SaveIndex().put(career("a", seasons = 0)))
        assertTrue(state.careers.single().summary.contains("yet to play"))
    }

    @Test
    fun `careers list most recently played first`() {
        val index = SaveIndex()
            .put(career("old", today = LocalDate.of(2026, 4, 1)))
            .put(career("new", today = LocalDate.of(2031, 4, 1)))
        assertEquals(listOf("new", "old"), careerSelectState(index).careers.map { it.id })
    }

    @Test
    fun `every difficulty is offered, exactly one is the default, and each says what it does`() {
        val state = careerSelectState(SaveIndex())
        assertEquals(Difficulty.entries.size, state.difficulties.size)
        assertEquals(1, state.difficulties.count { it.isDefault })
        assertEquals(Difficulty.DEFAULT.name, state.difficulties.single { it.isDefault }.id)
    }

    @Test
    fun `every difficulty promises in writing that the cricket does not change`() {
        // The one sentence this screen must carry. A player who suspects the
        // game is shading his attributes has no reason to trust any number it
        // shows him afterwards, and the numbers are the product.
        careerSelectState(SaveIndex()).difficulties.forEach {
            assertTrue(it.description.contains("Nothing about the cricket itself changes")) { it.name }
        }
    }
}
