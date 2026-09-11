package com.cricketcareer.engine.career

import com.cricketcareer.engine.config.ContractTuning
import com.cricketcareer.engine.model.player.Player
import com.cricketcareer.engine.model.player.PlayerId
import com.cricketcareer.engine.model.world.LadderLevel
import com.cricketcareer.engine.model.world.MatchFormat
import com.cricketcareer.engine.rng.SimRandom
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToLong

/** A team's identity for contract purposes, kept minimal so `:data` owns the rest. */
data class ContractParty(val teamId: String, val displayName: String, val level: LadderLevel) {
    init {
        require(teamId.isNotBlank()) { "team id must not be blank" }
    }
}

/**
 * An offer on the table.
 *
 * [perSeason] is in the abstract units described by [ContractTuning] — never a
 * currency. The seed database attaches a currency and a scale to a country so
 * that a state contract and a franchise deal are comparable without the engine
 * knowing what either is denominated in.
 */
data class ContractOffer(
    val from: ContractParty,
    val perSeason: Long,
    val seasons: Int,
    /**
     * Whether the club has promised a place. Worth a great deal to a player:
     * a bench year at a big club costs sharpness, form and, through those, the
     * next selection.
     */
    val guaranteedPlace: Boolean = false,
    /** Release clause in the same units, if any. */
    val releaseClause: Long? = null,
) {
    init {
        require(perSeason >= 0) { "perSeason $perSeason cannot be negative" }
        require(seasons in 1..10) { "seasons $seasons must be 1..10" }
        require(releaseClause == null || releaseClause >= 0) { "releaseClause $releaseClause cannot be negative" }
    }

    val totalValue: Long get() = perSeason * seasons
}

/** Why an offer was or was not attractive, so the UI can say more than a number. */
data class OfferAppraisal(
    val offer: ContractOffer,
    val money: Double,
    val standard: Double,
    val certainty: Double,
    val noise: Double,
    val total: Double,
)

/**
 * What a cricketer is worth and what he will sign.
 *
 * See docs/CAREER_MODEL.md §8. Two separate questions, deliberately: a club's
 * valuation is about what he will do for them, and a player's appraisal is
 * about what the move will do for him. Collapsing them into one number makes
 * every transfer a foregone conclusion.
 */
object Contracts {

    /**
     * What a club at [level] should pay this player per season.
     *
     * Convex in standard, because the market for cricketers is: the best player
     * in a competition is worth far more than twice the median, which is why a
     * squad has a couple of stars and nine journeymen rather than eleven
     * identical players.
     */
    fun value(
        player: Player,
        age: Int,
        level: LadderLevel,
        format: MatchFormat,
        reputation: Double,
        tuning: ContractTuning,
    ): Long {
        require(reputation in 0.0..1.0) { "reputation $reputation must be in 0..1" }
        val standard = Selection.standardFor(player, format)
        val base = tuning.topOfMarketPerSeason * standard.pow(tuning.standardExponent)
        val formTerm = 1.0 + tuning.formPremium * player.state.form
        val reputationTerm = 1.0 + tuning.reputationPremium * reputation
        // Clubs buy futures, so the market peaks a little before the player
        // does. A 24-year-old is worth more than a 31-year-old of equal ability.
        val yearsFromPeak = abs(age - tuning.peakMarketAge)
        val ageTerm = (1.0 - tuning.valueFallPerYearFromPeak * yearsFromPeak).coerceAtLeast(0.05)
        // A franchise competition pays more than a state one for the same
        // player, because the attention is what pays the salary.
        val marketTerm = 0.25 + 0.75 * level.attention
        return (base * formTerm * reputationTerm * ageTerm * marketTerm).roundToLong().coerceAtLeast(0L)
    }

    /**
     * How attractive an offer is to the player, relative to [current].
     *
     * Returns a score where zero is "no better than what I have". Money,
     * standard of cricket and certainty of a place are weighed separately
     * because the interesting decisions are the ones where they disagree —
     * more money to sit on a bench at a better club is a real dilemma and it
     * should read as one.
     */
    fun appraise(
        offer: ContractOffer,
        current: ContractOffer?,
        random: SimRandom,
        tuning: ContractTuning,
    ): OfferAppraisal {
        val currentPay = current?.perSeason ?: 0L
        // Relative rather than absolute, so the same raise means the same thing
        // to a state player and an international.
        val money = if (currentPay > 0) {
            (offer.perSeason - currentPay).toDouble() / currentPay
        } else {
            1.0
        }
        val currentStandard = current?.from?.level?.standard ?: 0.0
        val standard = (offer.from.level.standard - currentStandard) * tuning.standardWorth
        val currentCertain = current?.guaranteedPlace ?: false
        val certainty = when {
            offer.guaranteedPlace && !currentCertain -> tuning.guaranteedPlaceWorth
            !offer.guaranteedPlace && currentCertain -> -tuning.guaranteedPlaceWorth
            else -> 0.0
        }
        val noise = random.nextGaussian() * tuning.decisionSigma
        return OfferAppraisal(offer, money, standard, certainty, noise, money + standard + certainty + noise)
    }

    /** The offer this player would sign, or null if none of them beats staying put. */
    fun choose(
        offers: List<ContractOffer>,
        current: ContractOffer?,
        random: SimRandom,
        tuning: ContractTuning,
    ): ContractOffer? = offers
        .map { appraise(it, current, random, tuning) }
        .filter { it.total > 0.0 }
        .maxWithOrNull(compareBy<OfferAppraisal> { it.total }.thenByDescending { it.offer.from.teamId })
        ?.offer
}

/** A bidder at an auction: a budget, a valuation of each lot, and squad needs. */
data class AuctionTeam(
    val party: ContractParty,
    val budget: Long,
    /** Slots still to fill. A team with none left stops bidding, whatever its budget. */
    val slotsRemaining: Int,
) {
    init {
        require(budget >= 0) { "budget $budget cannot be negative" }
        require(slotsRemaining >= 0) { "slotsRemaining $slotsRemaining cannot be negative" }
    }
}

/** One lot: a player and the price the auction opens at. */
data class AuctionLot(val player: PlayerId, val basePrice: Long) {
    init {
        require(basePrice >= 0) { "basePrice $basePrice cannot be negative" }
    }
}

/** What a lot went for, or that it went unsold. */
data class AuctionSale(
    val lot: AuctionLot,
    val winner: ContractParty?,
    val price: Long,
) {
    val unsold: Boolean get() = winner == null
}

/**
 * An ascending auction, one lot at a time.
 *
 * Ascending rather than sealed-bid because the interesting part of a cricket
 * auction is watching a price climb past what anyone expected, and because it
 * produces the right second-price-ish outcome: the winner pays roughly what the
 * second-keenest bidder was willing to pay, not what he himself was.
 */
object Auction {

    /**
     * Run the lots in the order given.
     *
     * Budgets and slots are consumed as lots sell, so a team that overspends
     * early cannot buy late — which is the whole tactical content of an
     * auction and the reason lot order matters.
     */
    fun run(
        lots: List<AuctionLot>,
        teams: List<AuctionTeam>,
        valuation: (ContractParty, PlayerId) -> Long,
        random: SimRandom,
        tuning: ContractTuning,
    ): List<AuctionSale> {
        var remaining = teams
        val sales = ArrayList<AuctionSale>(lots.size)

        for (lot in lots) {
            // Each team's ceiling: what it thinks the player is worth, plus the
            // overbid that makes a room different from a spreadsheet, capped by
            // what it can actually pay.
            val ceilings = remaining.associate { team ->
                val enthusiasm = 1.0 + tuning.auctionOverbid * random.nextDouble()
                val ceiling = if (team.slotsRemaining <= 0) 0L
                else minOf((valuation(team.party, lot.player) * enthusiasm).roundToLong(), team.budget)
                team.party.teamId to ceiling
            }
            val contenders = remaining
                .filter { (ceilings[it.party.teamId] ?: 0L) >= lot.basePrice }
                // Ties broken by id so the winner never depends on list order.
                .sortedWith(
                    compareByDescending<AuctionTeam> { ceilings[it.party.teamId] }
                        .thenBy { it.party.teamId },
                )

            if (contenders.isEmpty()) {
                sales += AuctionSale(lot, winner = null, price = 0L)
                continue
            }
            val winner = contenders.first()
            val winnerCeiling = ceilings.getValue(winner.party.teamId)
            val runnerUp = contenders.getOrNull(1)?.let { ceilings.getValue(it.party.teamId) } ?: 0L
            // The winner pays one increment above the last bid the runner-up
            // was prepared to make, never above his own ceiling.
            val increment = (lot.basePrice * tuning.auctionIncrement).roundToLong().coerceAtLeast(1L)
            val price = minOf(maxOf(lot.basePrice, runnerUp + increment), winnerCeiling)

            sales += AuctionSale(lot, winner.party, price)
            remaining = remaining.map {
                if (it.party.teamId == winner.party.teamId) {
                    it.copy(budget = it.budget - price, slotsRemaining = it.slotsRemaining - 1)
                } else {
                    it
                }
            }
        }
        return sales
    }
}
