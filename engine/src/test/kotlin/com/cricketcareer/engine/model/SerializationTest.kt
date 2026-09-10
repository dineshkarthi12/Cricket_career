package com.cricketcareer.engine.model

import com.cricketcareer.engine.generator.NamePools
import com.cricketcareer.engine.generator.PlayerGenerator
import com.cricketcareer.engine.generator.PlayerSpec
import com.cricketcareer.engine.model.player.Attribute
import com.cricketcareer.engine.model.player.Attributes
import com.cricketcareer.engine.model.player.HiddenAttributes
import com.cricketcareer.engine.model.player.Player
import com.cricketcareer.engine.model.player.PlayerId
import com.cricketcareer.engine.model.world.LadderLevel
import com.cricketcareer.engine.model.world.MatchFormat
import com.cricketcareer.engine.model.world.Pitch
import com.cricketcareer.engine.model.world.PitchArchetype
import com.cricketcareer.engine.model.world.SoilType
import com.cricketcareer.engine.model.world.Venue
import com.cricketcareer.engine.rng.SimRandom
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * The seed database is meant to be edited by hand, so these tests are as much
 * about the *error messages* as about the round trip. A user with a text editor
 * is an expected user; a serialization stack trace is not an acceptable answer
 * to a typo.
 */
class SerializationTest {

    private val json = Json { prettyPrint = true }

    @Test
    fun `attributes round trip`() {
        val original = Attributes.of(mapOf(Attribute.TECHNIQUE to 71, Attribute.PACE to 88))
        val restored = json.decodeFromString<Attributes>(json.encodeToString(original))
        assertEquals(original, restored)
        assertEquals(71, restored[Attribute.TECHNIQUE])
        assertEquals(88, restored[Attribute.PACE])
    }

    @Test
    fun `attributes serialize by name so the file is readable and reorder-proof`() {
        val encoded = json.encodeToString(Attributes.uniform(50))
        assertTrue(encoded.contains("\"TECHNIQUE\""), "attribute names must appear in the JSON:\n$encoded")
        assertTrue(encoded.contains("\"REVERSE_SWING\""))
    }

    @Test
    fun `an omitted attribute takes the documented default`() {
        // A hand-edited player should not have to list all 43.
        val restored = json.decodeFromString<Attributes>("""{"TECHNIQUE": 70}""")
        assertEquals(70, restored[Attribute.TECHNIQUE])
        assertEquals(Attributes.DEFAULT, restored[Attribute.GLOVEWORK])
    }

    @Test
    fun `an unknown attribute name is rejected with the offending key`() {
        // Almost always a typo. Dropping it silently wastes someone's afternoon.
        val error = assertThrows(SerializationException::class.java) {
            json.decodeFromString<Attributes>("""{"TECHNQIUE": 70}""")
        }
        assertTrue(error.message!!.contains("TECHNQIUE"), "message did not name the bad key: ${error.message}")
        assertTrue(error.message!!.contains("TECHNIQUE"), "message did not list the valid names")
    }

    @Test
    fun `an out of range attribute is rejected with its value`() {
        val error = assertThrows(SerializationException::class.java) {
            json.decodeFromString<Attributes>("""{"TECHNIQUE": 140}""")
        }
        assertTrue(error.message!!.contains("140"))
        assertTrue(error.message!!.contains("TECHNIQUE"))
    }

    @Test
    fun `a whole player round trips including his date of birth`() {
        val generated = PlayerGenerator(names = NamePools.FALLBACK).generate(
            SimRandom.fromSeed(42L),
            PlayerSpec(country = "IND", region = "MH", level = LadderLevel.STATE_FIRST_CLASS),
            LocalDate.of(2025, 4, 1),
            PlayerId("mh-1"),
        )
        val restored = json.decodeFromString<Player>(json.encodeToString(generated))
        assertEquals(generated, restored)
        assertEquals(generated.dateOfBirth, restored.dateOfBirth)
    }

    @Test
    fun `dates are ISO strings, not epoch numbers`() {
        val player = PlayerGenerator().generate(
            SimRandom.fromSeed(1L),
            PlayerSpec("IND", "MH", LadderLevel.COLLEGE, age = 19),
            LocalDate.of(2025, 4, 1),
            PlayerId("x"),
        )
        assertTrue(
            json.encodeToString(player).contains("\"${player.dateOfBirth}\""),
            "a hand-editable database needs readable dates",
        )
    }

    @Test
    fun `a malformed date says what is wrong with it`() {
        val error = assertThrows(SerializationException::class.java) {
            json.decodeFromString<Player>(
                """{"id":"x","name":{"given":"A","family":"B"},"dateOfBirth":"17-04-1999",
                   "country":"IND","region":"MH","battingHand":"RIGHT","bowlingStyle":"NONE",
                   "role":"OPENING_BAT","attributes":{},"hidden":{"potential":50,
                   "injuryProneness":50,"temperament":50,"bigMatchFactor":50,"learningRate":50}}""",
            )
        }
        assertTrue(error.message!!.contains("YYYY-MM-DD"), "unhelpful message: ${error.message}")
    }

    @Test
    fun `hidden attributes round trip but never print themselves`() {
        val hidden = HiddenAttributes(potential = 88, injuryProneness = 30, temperament = 61, bigMatchFactor = 74, learningRate = 55)
        assertEquals(hidden, json.decodeFromString<HiddenAttributes>(json.encodeToString(hidden)))
        assertEquals("HiddenAttributes(hidden)", hidden.toString())
        assertTrue(!"$hidden".contains("88"), "a hidden attribute leaked through toString")
    }

    @Test
    fun `a player's toString does not leak his hidden attributes`() {
        val player = PlayerGenerator().generate(
            SimRandom.fromSeed(2L),
            PlayerSpec("IND", "MH", LadderLevel.INTERNATIONAL),
            LocalDate.of(2025, 4, 1),
            PlayerId("x"),
        )
        val printed = player.toString()
        assertTrue(!printed.contains("potential"), "Player.toString leaked hidden attributes: $printed")
    }

    @Test
    fun `pitch and venue round trip`() {
        val pitch = Pitch.AVERAGE.copy(soilType = SoilType.BLACK, turn = 0.9)
        assertEquals(pitch, json.decodeFromString<Pitch>(json.encodeToString(pitch)))

        val venue = Venue(
            id = "v", name = "Ground", city = "Chennai", country = "IND", region = "TN",
            archetypeWeights = mapOf(PitchArchetype.RANK_TURNER to 3.0, PitchArchetype.BALANCED to 1.0),
        )
        assertEquals(venue, json.decodeFromString<Venue>(json.encodeToString(venue)))
    }

    @Test
    fun `match formats round trip with their powerplays`() {
        MatchFormat.ALL.forEach { format ->
            assertEquals(format, json.decodeFromString<MatchFormat>(json.encodeToString(format)))
        }
    }

    @Test
    fun `an invalid format is rejected on load rather than at run time`() {
        // Five bowlers capped at two overs cannot cover a 20-over innings.
        assertThrows(IllegalArgumentException::class.java) {
            json.decodeFromString<MatchFormat>(
                """{"id":"BAD","displayName":"Bad","kind":"LIMITED_OVERS","oversPerInnings":20,
                   "inningsPerSide":1,"maxOversPerBowler":2}""",
            )
        }
    }

    @Test
    fun `name pools round trip`() {
        assertEquals(
            NamePools.FALLBACK,
            json.decodeFromString<NamePools>(json.encodeToString(NamePools.FALLBACK)),
        )
    }
}
