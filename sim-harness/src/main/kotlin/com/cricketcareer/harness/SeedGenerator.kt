package com.cricketcareer.harness

import com.cricketcareer.engine.generator.PlayerGenerator
import com.cricketcareer.engine.generator.PlayerSpec
import com.cricketcareer.engine.model.player.Player
import com.cricketcareer.engine.model.player.PlayerId
import com.cricketcareer.engine.model.world.BoundaryShape
import com.cricketcareer.engine.model.world.BowlingMix
import com.cricketcareer.engine.model.world.Country
import com.cricketcareer.engine.model.world.LadderLevel
import com.cricketcareer.engine.model.world.PitchArchetype
import com.cricketcareer.engine.model.world.Region
import com.cricketcareer.engine.model.world.SoilType
import com.cricketcareer.engine.model.world.Team
import com.cricketcareer.engine.model.world.Venue
import com.cricketcareer.engine.rng.SimRandom
import com.cricketcareer.engine.seed.Competition
import com.cricketcareer.engine.seed.CompetitionStructure
import com.cricketcareer.engine.seed.SeedDatabase
import java.io.File
import java.time.LocalDate

/**
 * Builds the seed database.
 *
 * Four thousand cricketers is not something anyone types, so the file is
 * generated — but it is written out as an ordinary JSON file and edited like
 * one. Regenerating is deterministic in a seed, so a world can be rebuilt
 * exactly, or nudged by hand and kept.
 *
 *   ./gradlew :sim-harness:run --args="--report=seed --seed=20260912"
 *
 * The structure below — which regions exist, which districts, which
 * competitions — is the part a person would actually want to change, so it is
 * written as plain lists rather than derived from anything clever.
 *
 * See docs/SEED_DATABASE.md.
 */
object SeedGenerator {

    /**
     * One country in full, the rest at international level only (Q2).
     *
     * Names are places. No franchise names anywhere, and the loader checks
     * rather than trusting this file.
     */
    private val REGIONS = listOf(
        // name                zone        districts
        Reg("Maharashtra", "West", listOf("Pune", "Nashik", "Kolhapur", "Solapur", "Nagpur", "Aurangabad")),
        Reg("Mumbai", "West", listOf("Bandra", "Dadar", "Borivali", "Thane", "Andheri", "Worli")),
        Reg("Gujarat", "West", listOf("Ahmedabad", "Surat", "Vadodara", "Rajkot", "Bhavnagar", "Jamnagar")),
        Reg("Saurashtra", "West", listOf("Junagadh", "Porbandar", "Morbi", "Amreli", "Gondal", "Veraval")),
        Reg("Tamil Nadu", "South", listOf("Chennai", "Coimbatore", "Madurai", "Trichy", "Salem", "Erode")),
        Reg("Karnataka", "South", listOf("Bengaluru", "Mysuru", "Hubli", "Mangaluru", "Belagavi", "Davangere")),
        Reg("Kerala", "South", listOf("Kochi", "Thiruvananthapuram", "Kozhikode", "Thrissur", "Kollam", "Kannur")),
        Reg("Hyderabad", "South", listOf("Secunderabad", "Warangal", "Nizamabad", "Karimnagar", "Khammam", "Guntur")),
        Reg("Andhra", "South", listOf("Visakhapatnam", "Vijayawada", "Tirupati", "Kakinada", "Nellore", "Kurnool")),
        Reg("Delhi", "North", listOf("Rohini", "Dwarka", "Karol Bagh", "Saket", "Shahdara", "Najafgarh")),
        Reg("Punjab", "North", listOf("Mohali", "Ludhiana", "Amritsar", "Jalandhar", "Patiala", "Bathinda")),
        Reg("Haryana", "North", listOf("Gurugram", "Rohtak", "Hisar", "Karnal", "Panipat", "Ambala")),
        Reg("Himachal", "North", listOf("Dharamsala", "Shimla", "Mandi", "Solan", "Una", "Bilaspur")),
        Reg("Jammu", "North", listOf("Srinagar", "Udhampur", "Anantnag", "Baramulla", "Kathua", "Rajouri")),
        Reg("Bengal", "East", listOf("Kolkata", "Howrah", "Siliguri", "Durgapur", "Asansol", "Kharagpur")),
        Reg("Odisha", "East", listOf("Cuttack", "Bhubaneswar", "Rourkela", "Sambalpur", "Puri", "Balasore")),
        Reg("Jharkhand", "East", listOf("Ranchi", "Jamshedpur", "Dhanbad", "Bokaro", "Hazaribagh", "Deoghar")),
        Reg("Bihar", "East", listOf("Patna", "Gaya", "Muzaffarpur", "Bhagalpur", "Darbhanga", "Purnia")),
        Reg("Assam", "East", listOf("Guwahati", "Dibrugarh", "Silchar", "Jorhat", "Tezpur", "Nagaon")),
        Reg("Uttar Pradesh", "Central", listOf("Lucknow", "Kanpur", "Varanasi", "Agra", "Meerut", "Prayagraj")),
        Reg("Madhya Pradesh", "Central", listOf("Indore", "Bhopal", "Jabalpur", "Gwalior", "Ujjain", "Sagar")),
        Reg("Rajasthan", "Central", listOf("Jaipur", "Jodhpur", "Udaipur", "Kota", "Ajmer", "Bikaner")),
        Reg("Vidarbha", "Central", listOf("Amravati", "Akola", "Chandrapur", "Wardha", "Yavatmal", "Gondia")),
        Reg("Chhattisgarh", "Central", listOf("Raipur", "Bilaspur", "Durg", "Korba", "Raigarh", "Jagdalpur")),
    )

    private val ZONES = listOf("North", "South", "East", "West", "Central")

    /** Ids are upper snake case throughout, so one rule builds all of them. */
    private fun id(vararg parts: String) = parts.joinToString("-") { it.uppercase().replace(' ', '_') }

    /** The team id of a zone, so the region table and the team table agree. */
    private fun zoneIdFor(zone: String): String = id(HOME, "ZONE", zone)

    /** Other nations, international level only. */
    private val NATIONS = listOf(
        Nation("AUS", "Australia", BowlingMix(fast = 2.0, fastMedium = 2.2, medium = 1.0, offSpin = 0.5, legSpin = 0.6, leftArmSpin = 0.4)),
        Nation("ENG", "England", BowlingMix(fast = 1.6, fastMedium = 2.4, medium = 1.4, offSpin = 0.6, legSpin = 0.4, leftArmSpin = 0.4)),
        Nation("SAF", "South Africa", BowlingMix(fast = 2.1, fastMedium = 2.2, medium = 1.0, offSpin = 0.5, legSpin = 0.4, leftArmSpin = 0.4)),
        Nation("NZL", "New Zealand", BowlingMix(fast = 1.7, fastMedium = 2.3, medium = 1.3, offSpin = 0.6, legSpin = 0.4, leftArmSpin = 0.6)),
        Nation("PAK", "Pakistan", BowlingMix(fast = 1.9, fastMedium = 1.9, medium = 0.9, offSpin = 0.8, legSpin = 0.9, leftArmSpin = 0.7)),
        Nation("SLK", "Sri Lanka", BowlingMix(fast = 1.0, fastMedium = 1.5, medium = 1.0, offSpin = 1.4, legSpin = 0.8, leftArmSpin = 0.9)),
        Nation("BAN", "Bangladesh", BowlingMix(fast = 1.0, fastMedium = 1.4, medium = 1.1, offSpin = 1.5, legSpin = 0.6, leftArmSpin = 1.2)),
        Nation("WIN", "West Indies", BowlingMix(fast = 2.0, fastMedium = 2.0, medium = 1.0, offSpin = 0.8, legSpin = 0.5, leftArmSpin = 0.6)),
        Nation("AFG", "Afghanistan", BowlingMix(fast = 1.1, fastMedium = 1.4, medium = 1.0, offSpin = 1.2, legSpin = 1.4, leftArmSpin = 0.8)),
    )

    private data class Reg(val name: String, val zone: String, val districts: List<String>)
    private data class Nation(val id: String, val name: String, val mix: BowlingMix)

    private const val HOME = "IND"
    private const val STATE_SQUAD = 18
    private const val DISTRICT_SQUAD = 14
    private const val ZONAL_SQUAD = 15
    private const val NATIONAL_SQUAD = 20
    private const val FRANCHISE_SQUAD = 18

    fun run(args: HarnessArgs, outputDirectory: String) {
        val random = SimRandom.fromSeed(args.seed)
        val generator = PlayerGenerator()
        val today = LocalDate.of(2026, 4, 1)

        val venues = mutableListOf<Venue>()
        val teams = mutableListOf<Team>()
        val players = mutableListOf<Player>()
        val competitions = mutableListOf<Competition>()

        fun squad(prefix: String, level: LadderLevel, size: Int, region: String, country: String, mix: BowlingMix): List<Player> =
            generator.generateSquad(
                rng = random,
                spec = PlayerSpec(country = country, region = region, level = level, bowlingMix = mix),
                size = size,
                today = today,
                idPrefix = prefix,
            ).also { players += it }

        // ---- India: the full pyramid ------------------------------------
        REGIONS.forEach { region ->
            val regionId = id(HOME, region.name)
            val ground = venue(id(regionId, "GROUND"), "${region.name} Ground", region.name, HOME, region.name, random)
            venues += ground

            // The state side plays both a red-ball and a white-ball
            // competition, and it is the same squad - which is why a
            // multi-day grinder and a T20 hitter compete for the same place.
            val stateSquad = squad("$regionId-P", LadderLevel.STATE_FIRST_CLASS, STATE_SQUAD, region.name, HOME, INDIA_MIX)
            teams += Team(
                id = regionId, name = region.name, shortName = shortName(region.name),
                country = HOME, region = region.name, level = LadderLevel.STATE_FIRST_CLASS,
                homeVenue = ground.id, squad = stateSquad.map { it.id },
            )
            teams += Team(
                id = "$regionId-WB", name = region.name, shortName = shortName(region.name),
                country = HOME, region = region.name, level = LadderLevel.STATE_WHITE_BALL,
                homeVenue = ground.id, squad = stateSquad.map { it.id },
            )

            region.districts.forEach { district ->
                val districtId = id(regionId, district)
                val districtGround = venue(id(districtId, "GROUND"), "$district Ground", district, HOME, region.name, random)
                venues += districtGround
                val districtSquad = squad("$districtId-P", LadderLevel.DISTRICT_CLUB, DISTRICT_SQUAD, region.name, HOME, INDIA_MIX)
                teams += Team(
                    id = districtId, name = district, shortName = shortName(district),
                    country = HOME, region = region.name, level = LadderLevel.DISTRICT_CLUB,
                    homeVenue = districtGround.id, squad = districtSquad.map { it.id },
                )
            }

            competitions += Competition(
                id = id(regionId, "DISTRICT", "LEAGUE"),
                name = "${region.name} District League",
                country = HOME, level = LadderLevel.DISTRICT_CLUB, format = "OD50",
                structure = CompetitionStructure.SINGLE_ROUND_ROBIN,
                teams = region.districts.map { id(regionId, it) },
                startMonth = 7, weeks = 8, prestige = 0.08,
            )
        }

        // ---- Zones ------------------------------------------------------
        ZONES.forEach { zone ->
            val zoneId = id(HOME, "ZONE", zone)
            val zoneRegions = REGIONS.filter { it.zone == zone }
            val ground = venue(id(zoneId, "GROUND"), "$zone Zone Ground", zone, HOME, zoneRegions.first().name, random)
            venues += ground
            val zoneSquad = squad("$zoneId-P", LadderLevel.ZONAL, ZONAL_SQUAD, zoneRegions.first().name, HOME, INDIA_MIX)
            teams += Team(
                id = zoneId, name = "$zone Zone", shortName = zone.take(3).uppercase(),
                country = HOME, region = zoneRegions.first().name, level = LadderLevel.ZONAL,
                homeVenue = ground.id, squad = zoneSquad.map { it.id },
            )
        }
        competitions += Competition(
            id = id(HOME, "ZONAL", "TROPHY"), name = "Zonal Trophy",
            country = HOME, level = LadderLevel.ZONAL, format = "FC4",
            structure = CompetitionStructure.SINGLE_ROUND_ROBIN,
            teams = ZONES.map { id(HOME, "ZONE", it) },
            startMonth = 2, weeks = 5, prestige = 0.45,
        )

        // ---- The two national domestic competitions ---------------------
        competitions += Competition(
            id = id(HOME, "FC"), name = "National Championship",
            country = HOME, level = LadderLevel.STATE_FIRST_CLASS, format = "FC4",
            structure = CompetitionStructure.GROUPS_THEN_KNOCKOUT,
            teams = REGIONS.map { id(HOME, it.name) },
            startMonth = 10, weeks = 16, prestige = 0.55,
        )
        competitions += Competition(
            id = id(HOME, "STATE", "T20"), name = "State T20 Cup",
            country = HOME, level = LadderLevel.STATE_WHITE_BALL, format = "T20",
            structure = CompetitionStructure.GROUPS_THEN_KNOCKOUT,
            teams = REGIONS.map { "${id(HOME, it.name)}-WB" },
            startMonth = 1, weeks = 5, prestige = 0.5,
        )

        // ---- The franchise league ---------------------------------------
        val franchiseCities = listOf(
            "Chennai", "Mumbai", "Bengaluru", "Kolkata", "Delhi",
            "Hyderabad", "Jaipur", "Ahmedabad", "Lucknow", "Mohali",
        )
        franchiseCities.forEach { city ->
            val teamId = id(HOME, "T20", city)
            val ground = venue(id(teamId, "GROUND"), "$city Stadium", city, HOME, city, random)
            venues += ground
            val franchiseSquad = squad("$teamId-P", LadderLevel.FRANCHISE_T20, FRANCHISE_SQUAD, city, HOME, INDIA_MIX)
            teams += Team(
                id = teamId, name = city, shortName = shortName(city),
                country = HOME, region = city, level = LadderLevel.FRANCHISE_T20,
                homeVenue = ground.id, squad = franchiseSquad.map { it.id },
            )
        }
        competitions += Competition(
            id = id(HOME, "T20", "LEAGUE"), name = "Premier T20 League",
            country = HOME, level = LadderLevel.FRANCHISE_T20, format = "T20",
            structure = CompetitionStructure.DOUBLE_ROUND_ROBIN,
            teams = franchiseCities.map { id(HOME, "T20", it) },
            startMonth = 4, weeks = 8, prestige = 1.0,
        )

        // ---- International ----------------------------------------------
        val allNations = listOf(Nation(HOME, "India", INDIA_MIX)) + NATIONS
        allNations.forEach { nation ->
            val teamId = id(nation.id, "INTL")
            val ground = venue(id(teamId, "GROUND"), "${nation.name} National Ground", nation.name, nation.id, nation.name, random)
            venues += ground
            val squadPlayers = squad("$teamId-P", LadderLevel.INTERNATIONAL, NATIONAL_SQUAD, nation.name, nation.id, nation.mix)
            teams += Team(
                id = teamId, name = nation.name, shortName = nation.id,
                country = nation.id, region = nation.name, level = LadderLevel.INTERNATIONAL,
                homeVenue = ground.id, squad = squadPlayers.map { it.id },
            )
        }
        listOf("T20" to "T20 International Series", "OD50" to "One-Day International Series", "TEST" to "Test Series")
            .forEachIndexed { index, (format, name) ->
                competitions += Competition(
                    id = id("INTL", format), name = name,
                    country = "", level = LadderLevel.INTERNATIONAL, format = format,
                    structure = CompetitionStructure.BILATERAL_SERIES,
                    teams = allNations.map { id(it.id, "INTL") },
                    startMonth = 6 + index, weeks = 10, prestige = 0.9 + index * 0.03,
                )
            }

        // ---- Countries ---------------------------------------------------
        val countries = listOf(
            Country(
                id = HOME, name = "India", bowlingMix = INDIA_MIX,
                // Franchise cities overlap the state list — Mumbai, Delhi and
                // Hyderabad are both a state side and a city team — so the
                // region list is deduplicated rather than concatenated.
                // Franchise cities and the national side are regions too (a
                // team has to have one) but they feed no zone: nobody is picked
                // for West Zone because he plays for the Mumbai franchise.
                regions = (
                    REGIONS.map { Region(id = it.name, name = it.name, zone = zoneIdFor(it.zone)) } +
                        (franchiseCities + "India").map { Region(id = it, name = it) }
                    ).distinctBy { it.id },
            ),
        ) + NATIONS.map { nation ->
            Country(
                id = nation.id, name = nation.name, bowlingMix = nation.mix,
                regions = listOf(Region(id = nation.name, name = nation.name)),
            )
        }

        val database = SeedDatabase(
            countries = countries, venues = venues, teams = teams,
            competitions = competitions, players = players,
        )

        // Validate before writing, through the same two-file path the game
        // uses. A generator that can emit a file its own loader refuses is
        // worse than no generator.
        val world = SeedDatabase.encodeWorld(database)
        val roster = SeedDatabase.encodePlayers(database)
        SeedDatabase.load(world, roster)

        val directory = File(outputDirectory).apply { mkdirs() }
        File(directory, "world.json").writeText(world)
        File(directory, "players.json").writeText(roster)

        println("Seed database written to ${directory.absolutePath}")
        println("  world.json    ${world.length / 1024} KB")
        println("  players.json  ${roster.length / 1024} KB")
        println("  countries     ${countries.size}")
        println("  venues        ${venues.size}")
        println("  teams         ${teams.size}")
        println("  competitions  ${competitions.size}")
        println("  players       ${players.size}")
        LadderLevel.ALL.forEach { level ->
            val count = teams.count { it.level == level }
            if (count > 0) println("    %-18s %3d teams".format(level.displayName, count))
        }
    }

    /** Subcontinental: spin-heavy, and it is why this country produces spinners. */
    private val INDIA_MIX = BowlingMix(fast = 1.0, fastMedium = 1.8, medium = 1.2, offSpin = 1.5, legSpin = 1.1, leftArmSpin = 1.0)

    private fun venue(id: String, name: String, city: String, country: String, region: String, random: SimRandom) = Venue(
        id = id, name = name, city = city, country = country, region = region,
        boundary = BoundaryShape.AVERAGE,
        soilType = SoilType.entries[random.nextInt(SoilType.entries.size)],
        archetypeWeights = mapOf(
            PitchArchetype.BALANCED to 1.0,
            PitchArchetype.FLAT_ROAD to random.nextDouble(0.2, 1.0),
            PitchArchetype.RANK_TURNER to random.nextDouble(0.1, 0.9),
            PitchArchetype.GREEN_SEAMER to random.nextDouble(0.1, 0.7),
        ),
        altitudeMetres = random.nextDouble(0.0, 900.0),
        dewTendency = random.nextDouble(0.1, 0.8),
    )

    /** Three letters, upper case. "Tamil Nadu" becomes TAM, not TN — no real abbreviations. */
    private fun shortName(name: String): String =
        name.filter { it.isLetter() }.take(3).uppercase()
}
