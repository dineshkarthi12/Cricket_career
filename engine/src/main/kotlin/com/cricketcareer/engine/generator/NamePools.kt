package com.cricketcareer.engine.generator

import com.cricketcareer.engine.model.player.PersonName
import com.cricketcareer.engine.rng.SimRandom
import kotlinx.serialization.Serializable

/** Given names and surnames for one region. */
@Serializable
data class NamePool(val given: List<String>, val family: List<String>) {
    init {
        require(given.isNotEmpty()) { "a name pool needs at least one given name" }
        require(family.isNotEmpty()) { "a name pool needs at least one family name" }
    }
}

/**
 * Regional name pools, shipped as seed data and editable by the user.
 *
 * Names are drawn independently from the two lists, so a pool of 40 given names
 * and 40 surnames yields 1,600 combinations — enough that a career does not
 * feel repetitive, and small enough that someone can edit it in a text file.
 *
 * All generated players are fictional. Given names and surnames are ordinary
 * ones from the region; a random pairing could in principle coincide with a
 * real person, which is inherent to any name generator and is why the seed
 * database is editable.
 */
@Serializable
data class NamePools(
    /** Keyed by region id. A region with no pool falls back to [default]. */
    val byRegion: Map<String, NamePool> = emptyMap(),
    val default: NamePool,
) {
    fun draw(rng: SimRandom, region: String): PersonName {
        val pool = byRegion[region] ?: default
        return PersonName(rng.pick(pool.given), rng.pick(pool.family))
    }

    companion object {
        /**
         * A small built-in pool so the engine and its tests can generate players
         * without loading a database. The shipped seed data replaces this.
         */
        val FALLBACK: NamePools = NamePools(
            default = NamePool(
                given = listOf(
                    "Aarav", "Rohan", "Vikram", "Nikhil", "Arjun", "Kabir", "Rishabh", "Sanjay",
                    "Dhruv", "Manav", "Aditya", "Karan", "Varun", "Siddharth", "Tarun", "Pranav",
                    "Yash", "Naveen", "Harsh", "Anirudh", "Devansh", "Kunal", "Rahul", "Girish",
                    "Imran", "Farhan", "Zubair", "Aslam", "Joseph", "Denzil", "Ashwin", "Lokesh",
                ),
                family = listOf(
                    "Kulkarni", "Menon", "Reddy", "Iyer", "Chauhan", "Bhatt", "Naik", "Deshmukh",
                    "Rathore", "Salvi", "Pillai", "Ghosh", "Mahajan", "Tiwari", "Sekhon", "Vaidya",
                    "Barman", "Chandran", "Dixit", "Fernandes", "Grewal", "Hegde", "Jadeja", "Kamble",
                    "Lobo", "Mirza", "Nandy", "Oberoi", "Purohit", "Quereshi", "Rane", "Sarkar",
                ),
            ),
        )
    }
}
