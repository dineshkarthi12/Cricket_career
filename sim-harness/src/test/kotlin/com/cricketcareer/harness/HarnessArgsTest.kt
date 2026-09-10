package com.cricketcareer.harness

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class HarnessArgsTest {

    @Test
    fun `defaults are sensible when nothing is passed`() {
        val args = HarnessArgs.parse(emptyArray())
        assertEquals("T20", args.format)
        assertEquals(1000, args.matches)
        assertEquals(1L, args.seed)
        assertEquals("calibration", args.report)
    }

    @Test
    fun `parses the documented invocation from the brief`() {
        val args = HarnessArgs.parse(arrayOf("--format=TEST", "--matches=5000", "--report=calibration"))
        assertEquals("TEST", args.format)
        assertEquals(5000, args.matches)
        assertEquals("calibration", args.report)
    }

    @Test
    fun `format is case insensitive`() {
        assertEquals("LIST_A", HarnessArgs.parse(arrayOf("--format=list_a")).format)
    }

    @Test
    fun `rejects an unknown format`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            HarnessArgs.parse(arrayOf("--format=BEACH"))
        }
        assertEquals(true, error.message!!.contains("unknown format"))
    }

    @Test
    fun `accepts the player-generation report`() {
        assertEquals("players", HarnessArgs.parse(arrayOf("--report=players")).report)
    }

    @Test
    fun `rejects an unknown report`() {
        assertThrows(IllegalArgumentException::class.java) { HarnessArgs.parse(arrayOf("--report=vibes")) }
    }

    @Test
    fun `rejects an unknown option rather than ignoring it`() {
        // Silently dropping --mathces=5000 would waste an hour of someone's day.
        assertThrows(IllegalArgumentException::class.java) {
            HarnessArgs.parse(arrayOf("--mathces=5000"))
        }
    }

    @Test
    fun `rejects malformed arguments`() {
        assertThrows(IllegalArgumentException::class.java) { HarnessArgs.parse(arrayOf("format=T20")) }
        assertThrows(IllegalArgumentException::class.java) { HarnessArgs.parse(arrayOf("--matches")) }
        assertThrows(IllegalArgumentException::class.java) { HarnessArgs.parse(arrayOf("--matches=lots")) }
        assertThrows(IllegalArgumentException::class.java) { HarnessArgs.parse(arrayOf("--matches=0")) }
        assertThrows(IllegalArgumentException::class.java) { HarnessArgs.parse(arrayOf("--threads=-1")) }
    }
}
