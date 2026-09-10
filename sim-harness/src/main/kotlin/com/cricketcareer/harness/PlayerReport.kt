package com.cricketcareer.harness

import com.cricketcareer.engine.generator.PlayerGenerator
import com.cricketcareer.engine.generator.PlayerSpec
import com.cricketcareer.engine.model.player.AttributeGroup
import com.cricketcareer.engine.model.player.Player
import com.cricketcareer.engine.model.world.LadderLevel
import com.cricketcareer.engine.rng.MatchRandom
import com.cricketcareer.engine.rng.RngStreams
import java.time.LocalDate

/**
 * Prints a generated squad and the population statistics behind it.
 *
 * Phase 1's deliverable is a believable *distribution*, which is not something
 * you can eyeball from one player. This report shows a squad so the names and
 * shapes can be sanity-checked, then the ladder means so the pyramid is visible.
 */
object PlayerReport {

    private val REFERENCE_DATE: LocalDate = LocalDate.of(2025, 4, 1)

    fun run(args: HarnessArgs) {
        val generator = PlayerGenerator()
        val rng = MatchRandom(args.seed).stream(RngStreams.CONDITIONS)

        val squad = generator.generateSquad(
            rng = rng,
            spec = PlayerSpec(country = "IND", region = "MH", level = LadderLevel.STATE_FIRST_CLASS),
            size = 16,
            today = REFERENCE_DATE,
            idPrefix = "MH",
        )

        println("A state first-class squad, seed ${args.seed}")
        println()
        println(
            "%-22s %-4s %-24s %-5s %4s %4s %4s %4s %4s".format(
                "Name", "Age", "Role", "Style", "Bat", "Bowl", "Fld", "Phy", "Men",
            ),
        )
        println("-".repeat(84))
        squad.forEach { p -> println(row(p)) }

        println()
        println("Ladder means over ${args.matches} generated players per level")
        println("Bowl is averaged over players who actually bowl; including the rest")
        println("would measure how many specialists a level has, not how good they are.")
        println()
        println("%-22s %6s %6s %6s %7s".format("Level", "Peak", "Bat", "Bowl", "Age"))
        println("-".repeat(52))
        // Age-group cricket sits below district league on current ability and
        // above it on potential: those players are 18, not 24. That is the model
        // working, not an ordering bug.
        LadderLevel.ALL.forEach { level ->
            val sample = (1..args.matches).map {
                generator.generate(
                    rng,
                    PlayerSpec(country = "IND", region = "MH", level = level),
                    REFERENCE_DATE,
                    com.cricketcareer.engine.model.player.PlayerId("s$it"),
                )
            }
            val bowlers = sample.filter { it.bowls }
            println(
                "%-22s %6.1f %6.1f %6.1f %7.1f".format(
                    level.displayName,
                    sample.map { p -> AttributeGroup.ALL.maxOf { p.attributes.groupAverage(it) } }.average(),
                    sample.map { it.attributes.groupAverage(AttributeGroup.BATTING) }.average(),
                    if (bowlers.isEmpty()) 0.0 else bowlers.map { it.attributes.groupAverage(AttributeGroup.BOWLING) }.average(),
                    sample.map { it.ageOn(REFERENCE_DATE) }.average(),
                ),
            )
        }
    }

    private fun row(p: Player): String = "%-22s %-4d %-24s %-5s %4.0f %4.0f %4.0f %4.0f %4.0f".format(
        p.name.full,
        p.ageOn(REFERENCE_DATE),
        p.role.displayName,
        p.bowlingStyle.short,
        p.attributes.groupAverage(AttributeGroup.BATTING),
        p.attributes.groupAverage(AttributeGroup.BOWLING),
        p.attributes.groupAverage(AttributeGroup.FIELDING),
        p.attributes.groupAverage(AttributeGroup.PHYSICAL),
        p.attributes.groupAverage(AttributeGroup.MENTAL),
    )
}
