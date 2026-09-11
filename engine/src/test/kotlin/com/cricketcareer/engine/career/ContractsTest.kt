package com.cricketcareer.engine.career

import com.cricketcareer.engine.config.ContractTuning
import com.cricketcareer.engine.fixtures.Fixtures
import com.cricketcareer.engine.model.player.Attributes
import com.cricketcareer.engine.model.player.Player
import com.cricketcareer.engine.model.player.PlayerId
import com.cricketcareer.engine.model.player.PlayerState
import com.cricketcareer.engine.model.world.LadderLevel
import com.cricketcareer.engine.rng.SimRandom
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ContractsTest {

    private val tuning = ContractTuning()
    private val quiet = ContractTuning(decisionSigma = 0.0, auctionOverbid = 0.0)

    private fun rng(seed: Long = 7) = SimRandom.fromSeed(seed)

    private fun p(id: String, rating: Int = 60, form: Double = 0.0): Player =
        Fixtures.averagePlayer(id).copy(
            id = PlayerId(id),
            attributes = Attributes.uniform(rating),
            state = PlayerState.FRESH.copy(form = form),
        )

    private fun party(id: String, level: LadderLevel = LadderLevel.FRANCHISE_T20) =
        ContractParty(id, id, level)

    private fun value(player: Player, age: Int = 27, level: LadderLevel = LadderLevel.FRANCHISE_T20, reputation: Double = 0.5) =
        Contracts.value(player, age, level, Fixtures.T20, reputation, tuning)

    @Test
    fun `the market is steeply convex, not linear`() {
        // The reason a squad has a couple of stars and nine journeymen rather
        // than eleven identical players.
        val median = value(p("MED", rating = 55))
        val best = value(p("BEST", rating = 88))
        assertTrue(best > median * 2.5) { "best $best vs median $median" }
    }

    @Test
    fun `form and reputation both move a price, reputation more`() {
        val flat = value(p("A"), reputation = 0.0)
        val inForm = value(p("B", form = 0.9), reputation = 0.0)
        val famous = value(p("C"), reputation = 1.0)
        assertTrue(inForm > flat) { "in form $inForm vs flat $flat" }
        assertTrue(famous > inForm) { "famous $famous should beat merely in form $inForm" }
    }

    @Test
    fun `clubs buy futures, so the market peaks before the player does`() {
        val young = value(p("Y"), age = 24)
        val peak = value(p("P"), age = tuning.peakMarketAge)
        val old = value(p("O"), age = 34)
        assertTrue(peak >= young) { "peak $peak vs 24 $young" }
        assertTrue(young > old) { "a 24-year-old ($young) should outprice a 34-year-old ($old)" }
    }

    @Test
    fun `a franchise pays more than a state side for the same player`() {
        val franchise = value(p("A"), level = LadderLevel.FRANCHISE_T20)
        val state = value(p("A"), level = LadderLevel.STATE_WHITE_BALL)
        assertTrue(franchise > state) { "franchise $franchise vs state $state" }
    }

    @Test
    fun `more money for the same job is attractive`() {
        val current = ContractOffer(party("OLD"), perSeason = 100, seasons = 2, guaranteedPlace = true)
        val raise = ContractOffer(party("OLD"), perSeason = 180, seasons = 2, guaranteedPlace = true)
        val appraisal = Contracts.appraise(raise, current, rng(), quiet)
        assertTrue(appraisal.total > 0.0) { "appraisal $appraisal" }
    }

    @Test
    fun `more money to sit on a bench is a real dilemma`() {
        // The whole reason money, standard and certainty are separate terms.
        val current = ContractOffer(party("STATE", LadderLevel.STATE_WHITE_BALL), 100, 2, guaranteedPlace = true)
        val benchAtABigClub = ContractOffer(party("BIG", LadderLevel.FRANCHISE_T20), 260, 2, guaranteedPlace = false)
        val a = Contracts.appraise(benchAtABigClub, current, rng(), quiet)
        assertTrue(a.money > 0.0) { "the money is better: ${a.money}" }
        assertTrue(a.certainty < 0.0) { "the place is worse: ${a.certainty}" }
        assertTrue(a.standard > 0.0) { "the cricket is better: ${a.standard}" }
    }

    @Test
    fun `a good young player leaves money on the table for better cricket`() {
        val current = ContractOffer(party("CLUB", LadderLevel.DISTRICT_CLUB), 100, 1, guaranteedPlace = true)
        val richerButLower = ContractOffer(party("CLUB2", LadderLevel.DISTRICT_CLUB), 150, 1, guaranteedPlace = true)
        val poorerButHigher = ContractOffer(party("STATE", LadderLevel.STATE_FIRST_CLASS), 120, 1, guaranteedPlace = true)
        val chosen = Contracts.choose(listOf(richerButLower, poorerButHigher), current, rng(), quiet)
        assertEquals("STATE", chosen?.from?.teamId) {
            "should take the step up: ${Contracts.appraise(poorerButHigher, current, rng(), quiet)}"
        }
    }

    @Test
    fun `an offer no better than the current deal is declined`() {
        val current = ContractOffer(party("OLD"), 200, 2, guaranteedPlace = true)
        val worse = ContractOffer(party("NEW"), 150, 2, guaranteedPlace = false)
        assertNull(Contracts.choose(listOf(worse), current, rng(), quiet))
    }

    @Test
    fun `a first contract is always worth taking`() {
        val first = ContractOffer(party("CLUB", LadderLevel.DISTRICT_CLUB), 10, 1)
        assertNotNull(Contracts.choose(listOf(first), current = null, random = rng(), tuning = quiet))
    }

    @Test
    fun `a nonsense offer is rejected rather than stored`() {
        assertThrows<IllegalArgumentException> { ContractOffer(party("X"), -5, 2) }
        assertThrows<IllegalArgumentException> { ContractOffer(party("X"), 100, 0) }
        assertThrows<IllegalArgumentException> { ContractOffer(party("X"), 100, 99) }
    }

    // ---- Auction --------------------------------------------------------

    private fun teams(vararg budgets: Pair<String, Long>, slots: Int = 3) =
        budgets.map { (id, budget) -> AuctionTeam(party(id), budget, slots) }

    @Test
    fun `the keenest bidder wins, at about what the runner-up would have paid`() {
        val lot = AuctionLot(PlayerId("STAR"), basePrice = 100)
        val valuations = mapOf("RICH" to 900L, "MID" to 500L, "POOR" to 200L)
        val sales = Auction.run(
            listOf(lot),
            teams("RICH" to 2000, "MID" to 2000, "POOR" to 2000),
            { party, _ -> valuations.getValue(party.teamId) },
            rng(), quiet,
        )
        val sale = sales.single()
        assertEquals("RICH", sale.winner?.teamId)
        // Second-price-ish: the winner pays past the runner-up, not up to his own ceiling.
        assertTrue(sale.price in 501..600) { "price ${sale.price} should sit just above the runner-up's 500" }
    }

    @Test
    fun `a lot nobody wants goes unsold`() {
        val lot = AuctionLot(PlayerId("JOURNEYMAN"), basePrice = 500)
        val sales = Auction.run(listOf(lot), teams("A" to 2000, "B" to 2000), { _, _ -> 100L }, rng(), quiet)
        assertTrue(sales.single().unsold) { "sold for ${sales.single().price}" }
    }

    @Test
    fun `a team that overspends early cannot buy late`() {
        // The entire tactical content of an auction.
        val lots = listOf(
            AuctionLot(PlayerId("FIRST"), 100),
            AuctionLot(PlayerId("SECOND"), 100),
        )
        val sales = Auction.run(
            lots,
            teams("RICH" to 600, "OTHER" to 560, slots = 3),
            { _, _ -> 5000L },
            rng(), quiet,
        )
        assertEquals("RICH", sales[0].winner?.teamId)
        assertTrue(sales[0].price >= 560) { "the first lot should have cost RICH nearly everything: ${sales[0].price}" }
        assertEquals("OTHER", sales[1].winner?.teamId) {
            "RICH had ${600 - sales[0].price} left and should have been outbid"
        }
    }

    @Test
    fun `a team with no slots left stops bidding`() {
        val lots = listOf(AuctionLot(PlayerId("A"), 10), AuctionLot(PlayerId("B"), 10))
        val sales = Auction.run(
            lots,
            listOf(AuctionTeam(party("FULL"), budget = 10_000, slotsRemaining = 1)),
            { _, _ -> 5000L },
            rng(), quiet,
        )
        assertEquals("FULL", sales[0].winner?.teamId)
        assertTrue(sales[1].unsold) { "a full squad kept bidding" }
    }

    @Test
    fun `a winner never pays more than his own ceiling`() {
        val lot = AuctionLot(PlayerId("X"), basePrice = 10)
        val sales = Auction.run(
            listOf(lot),
            teams("A" to 10_000, "B" to 10_000),
            { party, _ -> if (party.teamId == "A") 300L else 250L },
            rng(), quiet,
        )
        assertTrue(sales.single().price <= 300) { "paid ${sales.single().price} above a ceiling of 300" }
    }

    @Test
    fun `the winner does not depend on the order the teams were listed`() {
        val lot = AuctionLot(PlayerId("X"), 100)
        val valuations = mapOf("A" to 400L, "B" to 700L, "C" to 300L)
        fun run(order: List<String>) = Auction.run(
            listOf(lot),
            order.map { AuctionTeam(party(it), 5000, 3) },
            { party, _ -> valuations.getValue(party.teamId) },
            rng(1), quiet,
        ).single()
        // With overbid noise off, the same room must reach the same result.
        assertEquals(run(listOf("A", "B", "C")).winner, run(listOf("C", "A", "B")).winner)
        assertEquals(run(listOf("A", "B", "C")).price, run(listOf("C", "A", "B")).price)
    }

    @Test
    fun `an auction is a pure function of its seed`() {
        val lots = (1..12).map { AuctionLot(PlayerId("P$it"), basePrice = 50L * it) }
        val room = teams("A" to 4000, "B" to 3500, "C" to 5000, slots = 5)
        fun run() = Auction.run(lots, room, { _, player -> 200L + player.value.length * 40L }, rng(4242), tuning)
        assertEquals(run(), run())
    }

    @Test
    fun `the room bids a little above valuation`() {
        // An auction is a room, not a spreadsheet: the last bid is always
        // slightly mad.
        val lot = AuctionLot(PlayerId("X"), 100)
        val prices = (1L..60L).map { seed ->
            Auction.run(
                listOf(lot),
                teams("A" to 100_000, "B" to 100_000),
                { _, _ -> 1000L },
                rng(seed), tuning,
            ).single().price
        }
        assertTrue(prices.any { it > 1000 }) { "nobody ever went past valuation: max ${prices.max()}" }
        assertTrue(prices.all { it <= 1180 }) { "someone went far past the overbid cap: max ${prices.max()}" }
    }
}
