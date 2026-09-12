package com.cricketcareer.engine.seed

import com.cricketcareer.engine.model.player.Player
import com.cricketcareer.engine.model.world.Country
import com.cricketcareer.engine.model.world.LadderLevel
import com.cricketcareer.engine.model.world.MatchFormat
import com.cricketcareer.engine.model.world.Team
import com.cricketcareer.engine.model.world.Venue
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Everything wrong with a database, with enough detail to go and fix it. */
class SeedValidationException(val problems: List<String>) :
    IllegalArgumentException("the seed database has ${problems.size} problem(s):\n" + problems.joinToString("\n") { " - $it" })

/**
 * The world a career happens in.
 *
 * Parsed from text, never from a file: `:engine` does no I/O, so whoever has a
 * filesystem — `:data` on a device, `:sim-harness` on a desktop — reads the
 * bytes and hands them over. That keeps the engine testable without one.
 *
 * See docs/SEED_DATABASE.md.
 */
@Serializable
data class SeedDatabase(
    val countries: List<Country> = emptyList(),
    val venues: List<Venue> = emptyList(),
    val teams: List<Team> = emptyList(),
    val competitions: List<Competition> = emptyList(),
    val players: List<Player> = emptyList(),
) {
    // LinkedHashMap throughout: nothing may depend on hash order, and a caller
    // walking these gets the file's own order back.
    val teamsById: Map<String, Team> by lazy { index(teams) { it.id } }
    val venuesById: Map<String, Venue> by lazy { index(venues) { it.id } }
    val countriesById: Map<String, Country> by lazy { index(countries) { it.id } }
    val competitionsById: Map<String, Competition> by lazy { index(competitions) { it.id } }
    val playersById by lazy { index(players) { it.id.value } }

    fun teamsAt(level: LadderLevel): List<Team> = teams.filter { it.level == level }

    fun competitionsIn(country: String): List<Competition> =
        competitions.filter { it.country == country }

    /** The squad a team actually has, resolved from ids. */
    fun squadOf(teamId: String): List<Player> =
        teamsById[teamId]?.squad.orEmpty().mapNotNull { playersById[it.value] }

    private fun <T> index(items: List<T>, id: (T) -> String): Map<String, T> =
        LinkedHashMap<String, T>(items.size).apply { items.forEach { put(id(it), it) } }

    companion object {
        /**
         * Lenient about unknown keys on purpose.
         *
         * This file is meant to be edited by hand. Someone adding a note to a
         * team, or loading a file written by a later version of the game,
         * should not be met with a parse failure over a key the engine does not
         * happen to use.
         */
        private val JSON = Json {
            ignoreUnknownKeys = true
            prettyPrint = true
        }

        /** Parse without validating. Use [load] unless you are writing a test. */
        fun parse(json: String): SeedDatabase = JSON.decodeFromString(serializer(), json)

        fun encode(database: SeedDatabase): String = JSON.encodeToString(serializer(), database)

        /**
         * Parse and validate, or throw with every problem listed.
         *
         * Every problem rather than the first, because fixing a hand-edited
         * file one error per run is miserable, and because the problems are
         * usually related — one renamed team breaks four references.
         */
        fun load(json: String): SeedDatabase = parse(json).also { database ->
            val problems = SeedValidator.validate(database)
            if (problems.isNotEmpty()) throw SeedValidationException(problems)
        }
    }
}

/**
 * The rules a database must satisfy before the game will start on it.
 *
 * A dangling reference that loads happily surfaces three seasons into somebody's
 * career as a crash with no explanation. Refusing at the door costs one error
 * message and saves that.
 */
object SeedValidator {

    fun validate(database: SeedDatabase): List<String> = buildList {
        addAll(duplicateIds(database))
        addAll(danglingReferences(database))
        addAll(competitionSanity(database))
        addAll(licensing(database))
    }

    private fun duplicateIds(database: SeedDatabase): List<String> = buildList {
        addAll(duplicates("country", database.countries.map { it.id }))
        addAll(duplicates("venue", database.venues.map { it.id }))
        addAll(duplicates("team", database.teams.map { it.id }))
        addAll(duplicates("competition", database.competitions.map { it.id }))
        addAll(duplicates("player", database.players.map { it.id.value }))
    }

    private fun duplicates(kind: String, ids: List<String>): List<String> =
        ids.groupBy { it }
            .filter { it.value.size > 1 }
            .map { "$kind id '${it.key}' appears ${it.value.size} times" }

    private fun danglingReferences(database: SeedDatabase): List<String> = buildList {
        val venues = database.venues.map { it.id }.toSet()
        val teams = database.teams.map { it.id }.toSet()
        val countries = database.countries.map { it.id }.toSet()
        val players = database.players.map { it.id.value }.toSet()
        val regions = database.countries.flatMap { country -> country.regions.map { it.id } }.toSet()

        database.teams.forEach { team ->
            if (team.homeVenue !in venues) add("team '${team.id}' has home venue '${team.homeVenue}', which does not exist")
            if (team.country !in countries) add("team '${team.id}' is in country '${team.country}', which does not exist")
            if (regions.isNotEmpty() && team.region !in regions) {
                add("team '${team.id}' is in region '${team.region}', which does not exist")
            }
            team.squad.forEach { player ->
                if (player.value !in players) add("team '${team.id}' has player '${player.value}' in its squad, who does not exist")
            }
        }

        database.competitions.forEach { competition ->
            if (competition.country.isNotEmpty() && competition.country !in countries) {
                add("competition '${competition.id}' is in country '${competition.country}', which does not exist")
            }
            competition.teams.forEach { team ->
                if (team !in teams) add("competition '${competition.id}' includes team '$team', which does not exist")
            }
        }

        database.players.forEach { player ->
            if (countries.isNotEmpty() && player.country !in countries) {
                add("player '${player.id.value}' is from country '${player.country}', which does not exist")
            }
        }
    }

    private fun competitionSanity(database: SeedDatabase): List<String> = buildList {
        val formats = MatchFormat.ALL.associateBy { it.id }
        database.competitions.forEach { competition ->
            if (competition.format !in formats) {
                add("competition '${competition.id}' uses format '${competition.format}', which is not one of ${formats.keys}")
            }
            // A district side in a national tournament is a data error that
            // would otherwise show up as a wildly wrong selection decision.
            competition.teams.forEach { teamId ->
                val team = database.teamsById[teamId] ?: return@forEach
                if (team.level != competition.level) {
                    add(
                        "competition '${competition.id}' is ${competition.level}, " +
                            "but team '$teamId' is ${team.level}",
                    )
                }
            }
        }
    }

    /** Non-negotiable #6, checked rather than trusted. See [RejectedNames]. */
    private fun licensing(database: SeedDatabase): List<String> = buildList {
        database.teams.forEach { team ->
            RejectedNames.franchiseWordIn(team.name)?.let { word ->
                add("team '${team.id}' is named '${team.name}', which contains the franchise word '$word' — cities and regions only")
            }
        }
        database.players.forEach { player ->
            if (RejectedNames.isRealPlayer(player.name.full)) {
                add("player '${player.id.value}' is named '${player.name.full}', who is a real cricketer")
            }
        }
    }
}
