package com.cricketcareer.engine.conventions

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * A grep with a grudge.
 *
 * The engine's contract — pure, deterministic, Android-free, replayable from a
 * seed — is the kind of thing that is easy to state in a doc and easy to break
 * with one convenient import at 11pm. Every API below silently destroys
 * reproducibility or portability, so the build refuses them outright.
 *
 * If a use is genuinely justified, put `determinism-ok:` and a reason on the
 * same line and it will be allowed through — the point is that it has to be a
 * decision, not an accident.
 */
class DeterminismConventionsTest {

    private data class Ban(val pattern: Regex, val why: String)

    private val bans = listOf(
        Ban(
            Regex("""\bkotlin\.random\.Random\b|(?<![.\w])Random\.Default|\bjava\.util\.Random\b|ThreadLocalRandom|Math\.random"""),
            "unseeded randomness: use SimRandom, which replays from a seed",
        ),
        Ban(
            Regex("""System\.currentTimeMillis|System\.nanoTime|Instant\.now|LocalDate\.now|LocalDateTime\.now|Clock\.System"""),
            "reading the clock makes a simulation unrepeatable: pass the date in",
        ),
        Ban(
            Regex("""\bandroid[.x]\.|androidx\."""),
            ":engine must stay pure Kotlin/JVM so it can be mass-simulated and unit tested",
        ),
        Ban(
            Regex("""\bHashMap\b|\bHashSet\b|hashMapOf\(|hashSetOf\("""),
            "hash iteration order is unspecified: use LinkedHashMap/LinkedHashSet or sort",
        ),
        Ban(
            Regex("""(?<![.\w])println\(|(?<![.\w])print\(|System\.out|System\.err"""),
            "the engine returns values, it does not print: emit an event instead",
        ),
        Ban(
            Regex("""\bThread\(|GlobalScope|Dispatchers\."""),
            "a simulation runs on its caller's thread; parallelism belongs to the harness",
        ),
    )

    @Test
    fun `engine sources contain no non-deterministic or Android APIs`() {
        val sourceRoot = File("src/main/kotlin")
        assertTrue(sourceRoot.isDirectory, "expected to run with the module dir as cwd, was ${File(".").absolutePath}")

        val violations = mutableListOf<String>()
        sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                file.readLines().forEachIndexed { index, line ->
                    if ("determinism-ok:" in line) return@forEachIndexed
                    // Skip comment-only lines: the bans are about code, and the
                    // docs above are allowed to name the thing they forbid.
                    val trimmed = line.trim()
                    if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) {
                        return@forEachIndexed
                    }
                    bans.forEach { ban ->
                        if (ban.pattern.containsMatchIn(line)) {
                            violations += "${file.path}:${index + 1}  ${ban.why}\n    $trimmed"
                        }
                    }
                }
            }

        assertTrue(
            violations.isEmpty(),
            "Engine determinism rules violated:\n\n" + violations.joinToString("\n\n"),
        )
    }

    @Test
    fun `the ban list actually matches what it claims to`() {
        // Guards against a regex that quietly stops matching after an edit.
        val samples = listOf(
            "val x = Random.Default.nextInt()",
            "val t = System.currentTimeMillis()",
            "import androidx.room.Entity",
            "val m = HashMap<String, Int>()",
            "println(score)",
            "GlobalScope.launch { }",
        )
        samples.forEach { sample ->
            assertTrue(
                bans.any { it.pattern.containsMatchIn(sample) },
                "no ban matched the sample: $sample",
            )
        }
    }
}
