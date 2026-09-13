package com.cricketcareer.engine.save

import com.cricketcareer.engine.career.CareerPosition
import com.cricketcareer.engine.career.Difficulty
import com.cricketcareer.engine.career.Records
import com.cricketcareer.engine.fixtures.Fixtures
import com.cricketcareer.engine.model.player.Player
import com.cricketcareer.engine.model.world.LadderLevel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.LocalDate

class CareerSaveTest {

    private val player: Player = Fixtures.averagePlayer("user")

    private fun match(
        n: Int,
        runs: Int,
        out: Boolean = true,
        wickets: Int = 0,
        engine: String = EngineVersion.CURRENT,
    ) = PlayedMatch(
        fixture = "m$n",
        date = LocalDate.of(2026, 4, 1).plusDays(n.toLong()),
        formatId = Fixtures.LIST_A.id,
        level = LadderLevel.STATE_FIRST_CLASS,
        opponent = "northern-districts",
        atHome = n % 2 == 0,
        seed = 1_000L + n,
        engineVersion = engine,
        runs = runs,
        ballsFaced = runs + 12,
        out = out,
        wickets = wickets,
    )

    private fun save(
        id: String = "career-1",
        matches: List<PlayedMatch> = listOf(match(1, 40), match(2, 0), match(3, 120, out = false)),
        today: LocalDate = LocalDate.of(2026, 9, 1),
        difficulty: Difficulty = Difficulty.PROFESSIONAL,
    ) = CareerSave(
        id = id,
        name = "First go",
        careerSeed = 4_242L,
        difficulty = difficulty,
        player = player,
        position = CareerPosition("southern-state", LadderLevel.STATE_FIRST_CLASS, seasonsHere = 2),
        today = today,
        seasonsPlayed = 2,
        matches = matches,
    )

    @Test
    fun `a career survives a round trip unchanged`() {
        val original = save()
        assertEquals(original, SaveCodec.decode(SaveCodec.encode(original)))
    }

    @Test
    fun `every difficulty survives a round trip`() {
        // It is stored by name, so a career opened on Elite stays on Elite.
        Difficulty.entries.forEach { difficulty ->
            val restored = SaveCodec.decode(SaveCodec.encode(save(difficulty = difficulty)))
            assertEquals(difficulty, restored.difficulty)
        }
    }

    @Test
    fun `a save written by a later build opens, minus what it added`() {
        // Bricking a player's career because a field he has never heard of
        // arrived is the worst outcome available here.
        val text = SaveCodec.encode(save())
            .replaceFirst("{", "{\"somethingFromTheFuture\": [1, 2, 3],")
        assertEquals(save(), SaveCodec.decode(text))
    }

    @Test
    fun `a save from a newer save FORMAT is refused, not half-read`() {
        // Ignoring a key you do not know is safe. Ignoring that a key you do
        // know now means something else is not, and the format number is the
        // only thing that can tell those apart.
        val text = SaveCodec.encode(save()).replaceFirst("\"saveFormat\": 1", "\"saveFormat\": 99")
        val thrown = assertThrows<SaveFormatException> { SaveCodec.decode(text) }
        assertTrue(thrown.message!!.contains("newer version"))
    }

    @Test
    fun `a corrupt save explains itself rather than crashing`() {
        val thrown = assertThrows<SaveFormatException> { SaveCodec.decode("this is not json") }
        assertTrue(thrown.message!!.isNotBlank())
        assertNull(SaveCodec.decodeOrNull("this is not json"))
    }

    @Test
    fun `a save with no id is not a career`() {
        val text = SaveCodec.encode(save()).replaceFirst("\"id\": \"career-1\"", "\"id\": \"\"")
        assertThrows<SaveFormatException> { SaveCodec.decode(text) }
    }

    @Test
    fun `replay is offered only when the engine that played the match is still here`() {
        val current = match(1, 40)
        val old = match(2, 40, engine = "phase3-2026-09-11")
        assertTrue(current.isReplayableBy(EngineVersion.CURRENT))
        assertFalse(old.isReplayableBy(EngineVersion.CURRENT))
        // But the figures are there either way: history is materialised.
        assertEquals(40, old.runs)
    }

    @Test
    fun `the summary agrees with the record book built from the same matches`() {
        // The one bug a records screen must not have is a total nothing
        // supports, so the two are computed from the same matches and checked
        // against each other here.
        val career = save()
        val summary = career.summarise()
        val book = Records.of(career.matches.map { it.toAppearance(Fixtures.LIST_A) })
        assertEquals(book.batting.runs, summary.runs)
        assertEquals(book.batting.average!!, summary.battingAverage!!, 1e-9)
        assertEquals(career.matches.size, summary.matches)
    }

    @Test
    fun `a career not yet retired has no retirement date`() {
        assertFalse(save().isRetired)
        val retired = save().copy(retiredOn = LocalDate.of(2041, 9, 1), retirementReason = "The body went first")
        assertTrue(retired.isRetired)
        assertEquals(retired, SaveCodec.decode(SaveCodec.encode(retired)))
    }

    @Test
    fun `encoding is stable, so a save file does not churn for no reason`() {
        val once = SaveCodec.encode(save())
        val twice = SaveCodec.encode(save())
        assertEquals(once, twice)
        assertNotNull(SaveCodec.decodeOrNull(once))
    }
}
