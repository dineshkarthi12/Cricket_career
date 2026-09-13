package com.cricketcareer.presentation

import com.cricketcareer.engine.match.field.FieldPosition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChartDescriptionsTest {

    @Test
    fun `an empty chart says there is nothing rather than describing nothing`() {
        assertEquals("No cricket yet.", ChartDescriptions.worm(emptyList()))
        assertEquals("No cricket yet.", ChartDescriptions.worm(listOf(WormPoint(0, 0))))
        assertEquals("No overs bowled.", ChartDescriptions.manhattan(emptyList()))
        assertEquals("No deliveries to show.", ChartDescriptions.pitchMap(emptyList()))
        assertEquals("No scoring shots to show.", ChartDescriptions.wagonWheel(emptyList()))
    }

    @Test
    fun `the worm says the score, the overs and the run rate`() {
        val worm = (0..120).map { WormPoint(it, it) }
        val text = ChartDescriptions.worm(worm)
        assertTrue(text.startsWith("120 runs off 20.0 overs, 6.00 an over.")) { text }
    }

    @Test
    fun `the worm finds the passage where the innings got away`() {
        // Thirty dot balls, then thirty at four a ball, then thirty more dots.
        val points = mutableListOf(WormPoint(0, 0))
        var runs = 0
        (1..90).forEach { ball ->
            if (ball in 31..60) runs += 4
            points += WormPoint(ball, runs)
        }
        val text = ChartDescriptions.worm(points)
        assertTrue(text.contains("from over 6 to over 10")) { text }
        assertTrue(text.contains("120 runs in five")) { text }
    }

    @Test
    fun `the manhattan names the over that cost the most, as an ordinal`() {
        val bars = listOf(OverBar(1, 4, 0), OverBar(2, 19, 0), OverBar(3, 1, 1))
        val text = ChartDescriptions.manhattan(bars)
        assertTrue(text.contains("Most expensive the 2nd, 19 runs.")) { text }
        assertTrue(text.contains("Wickets in the 3rd.")) { text }
    }

    @Test
    fun `ordinals get the teens right`() {
        assertEquals("1st", ChartDescriptions.ordinal(1))
        assertEquals("2nd", ChartDescriptions.ordinal(2))
        assertEquals("3rd", ChartDescriptions.ordinal(3))
        assertEquals("4th", ChartDescriptions.ordinal(4))
        // The case everyone gets wrong.
        assertEquals("11th", ChartDescriptions.ordinal(11))
        assertEquals("12th", ChartDescriptions.ordinal(12))
        assertEquals("13th", ChartDescriptions.ordinal(13))
        assertEquals("21st", ChartDescriptions.ordinal(21))
        assertEquals("22nd", ChartDescriptions.ordinal(22))
    }

    @Test
    fun `the pitch map speaks the engine's own vocabulary`() {
        // Not a second opinion about what a good length is: a screen reader
        // that disagreed with the commentary beside it would be worse than
        // nothing.
        val points = List(10) { PitchMapPoint(0.18, 7.0, fullToss = false, wicket = false, boundary = false) }
        val text = ChartDescriptions.pitchMap(points)
        assertTrue(text.contains("good length")) { text }
        assertTrue(text.contains("off stump")) { text }
        assertTrue(text.startsWith("10 deliveries")) { text }
    }

    @Test
    fun `a full toss is called a full toss, not a negative length`() {
        val point = PitchMapPoint(0.0, -1.2, fullToss = true, wicket = false, boundary = false)
        assertEquals("full toss", ChartDescriptions.lengthWord(point))
    }

    @Test
    fun `the wagon wheel sectors agree with where the fielders actually stand`() {
        // The bearing runs clockwise from straight through the off side, and
        // naming it the other way round is an easy mistake that nothing else
        // would catch. So the sectors are checked against the field positions
        // themselves.
        assertEquals("cover", ChartDescriptions.sector(FieldPosition.COVER.azimuthDegrees))
        assertEquals("point", ChartDescriptions.sector(FieldPosition.POINT.azimuthDegrees))
        assertEquals("third man", ChartDescriptions.sector(FieldPosition.THIRD_MAN.azimuthDegrees))
        assertEquals("fine leg", ChartDescriptions.sector(FieldPosition.SHORT_FINE_LEG.azimuthDegrees))
        assertEquals("square leg", ChartDescriptions.sector(FieldPosition.SQUARE_LEG.azimuthDegrees))
        assertEquals("midwicket", ChartDescriptions.sector(FieldPosition.MIDWICKET.azimuthDegrees))
        assertEquals("straight", ChartDescriptions.sector(FieldPosition.MID_OFF.azimuthDegrees))
        assertEquals("straight", ChartDescriptions.sector(FieldPosition.MID_ON.azimuthDegrees))
        assertEquals("behind the wicket", ChartDescriptions.sector(FieldPosition.WICKETKEEPER.azimuthDegrees))
    }

    @Test
    fun `the wagon wheel reports which side of the wicket the runs went`() {
        val legSide = List(4) { WagonSpoke(FieldPosition.MIDWICKET.azimuthDegrees, 40.0, 4) }
        val offSide = List(1) { WagonSpoke(FieldPosition.COVER.azimuthDegrees, 40.0, 4) }
        val text = ChartDescriptions.wagonWheel(legSide + offSide)
        assertTrue(text.contains("Strongest through midwicket, 16 runs.")) { text }
        assertTrue(text.contains("80% on the leg side.")) { text }
    }

    @Test
    fun `a dot ball is not a scoring shot`() {
        val text = ChartDescriptions.wagonWheel(
            listOf(WagonSpoke(0.0, 3.0, 0), WagonSpoke(FieldPosition.COVER.azimuthDegrees, 60.0, 4)),
        )
        assertTrue(text.startsWith("4 runs off 1 scoring shots")) { text }
    }

    @Test
    fun `every chart on the screen gets a description`() {
        val state = ChartState(
            worm = listOf(WormPoint(0, 0), WormPoint(6, 8)),
            manhattan = listOf(OverBar(1, 8, 0)),
            pitchMap = listOf(PitchMapPoint(0.2, 7.0, false, false, false)),
            wagonWheel = listOf(WagonSpoke(50.0, 60.0, 4)),
        )
        val described = ChartDescriptions.of(state)
        assertEquals(setOf("worm", "manhattan", "pitchMap", "wagonWheel"), described.keys)
        described.forEach { (chart, text) -> assertTrue(text.isNotBlank()) { chart } }
    }
}
