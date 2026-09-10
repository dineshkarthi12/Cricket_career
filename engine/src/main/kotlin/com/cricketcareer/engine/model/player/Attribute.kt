package com.cricketcareer.engine.model.player

/**
 * Every rated quality a cricketer has, on a 1-100 scale.
 *
 * Modelled as an enum rather than as named fields on a data class because the
 * career layer needs to work over attributes *generically*: training allocates
 * to a focus area, ageing decays a group at a time, a serious injury shaves
 * points off physical attributes, and the generator fills a whole group from one
 * archetype. All of that is a loop here and forty lines of copy-paste otherwise.
 *
 * Named access still exists — see the accessors on [Attributes] — so call sites
 * read as `player.attributes.technique`, not `get(Attribute.TECHNIQUE)`.
 *
 * ORDER IS PART OF THE SAVE FORMAT only in the sense that [Attributes] stores
 * values in ordinal order in memory. Serialization is by *name*, so attributes
 * may be added and reordered without breaking existing seed files or saves.
 */
enum class Attribute(val group: AttributeGroup, val displayName: String) {

    // ---- Batting -----------------------------------------------------------
    /** Soundness of the basic method: head position, alignment, bat path. */
    TECHNIQUE(AttributeGroup.BATTING, "Technique"),

    /** Meeting the ball out of the middle. Drives contact quality more than power does. */
    TIMING(AttributeGroup.BATTING, "Timing"),

    /** Raw force. Turns a middled shot into a six rather than a caught-at-the-rope four. */
    POWER(AttributeGroup.BATTING, "Power"),

    /** Getting to the pitch of the ball, and back to the short one. */
    FOOTWORK(AttributeGroup.BATTING, "Footwork"),

    /** Playing off the back foot: cuts, pulls, back-foot punches. */
    BACKFOOT_PLAY(AttributeGroup.BATTING, "Back foot"),

    /** Playing off the front foot: drives, forward defence. */
    FRONTFOOT_PLAY(AttributeGroup.BATTING, "Front foot"),

    /** Reading and playing spin, including picking a wrong'un. */
    SPIN_PLAY(AttributeGroup.BATTING, "Against spin"),

    /** Coping with genuine pace. Sets the speed above which perception degrades. */
    PACE_PLAY(AttributeGroup.BATTING, "Against pace"),

    /** Handling the short ball: ducking, swaying, controlling the pull. */
    SHORT_BALL_PLAY(AttributeGroup.BATTING, "Short ball"),

    /** Playing the moving ball, and knowing which one to leave. */
    SWING_PLAY(AttributeGroup.BATTING, "Against swing"),

    /** Willingness to wait. Lowers risk appetite when there is no need to score. */
    PATIENCE(AttributeGroup.BATTING, "Patience"),

    /** Turning ones into twos and finding singles to keep the scoreboard moving. */
    STRIKE_ROTATION(AttributeGroup.BATTING, "Strike rotation"),

    /** Clearing the rope on purpose: the T20 attribute. */
    RANGE_HITTING(AttributeGroup.BATTING, "Range hitting"),

    /** Staying in. Resists the lapse that ends a long innings. */
    CONCENTRATION(AttributeGroup.BATTING, "Concentration"),

    /** Judgement and speed between the wickets; the run-out model reads this. */
    RUNNING_BETWEEN_WICKETS(AttributeGroup.BATTING, "Running"),

    // ---- Bowling -----------------------------------------------------------
    /** Speed through the air. For a spinner this is low by definition, not a flaw. */
    PACE(AttributeGroup.BOWLING, "Pace"),

    /** Revolutions and lateral grip off the pitch. The spinner's counterpart to PACE. */
    TURN(AttributeGroup.BOWLING, "Turn"),

    /** How tightly the ball lands where it was aimed. The single most valuable bowling attribute. */
    ACCURACY(AttributeGroup.BOWLING, "Accuracy"),

    /** Movement off the seam. Random in direction, which is what makes it dangerous. */
    SEAM_MOVEMENT(AttributeGroup.BOWLING, "Seam"),

    /** Conventional swing with a newish ball. */
    SWING(AttributeGroup.BOWLING, "Swing"),

    /** Reverse swing with an old, roughed-up ball. */
    REVERSE_SWING(AttributeGroup.BOWLING, "Reverse swing"),

    /** Extracting steepness from a length; a tall bowler's weapon. */
    BOUNCE(AttributeGroup.BOWLING, "Bounce"),

    /** In-air drift for a spinner, away from the eventual turn. */
    DRIFT(AttributeGroup.BOWLING, "Drift"),

    /** The size and quality of the repertoire: slower balls, googlies, cutters. */
    VARIATIONS(AttributeGroup.BOWLING, "Variations"),

    /** Holding nerve and yorker length at the death. */
    DEATH_BOWLING(AttributeGroup.BOWLING, "Death bowling"),

    /** Effectiveness with the new ball, when swing and hardness are at their peak. */
    NEW_BALL_SKILL(AttributeGroup.BOWLING, "New ball"),

    /** Effectiveness with an old ball, when containment and reverse take over. */
    OLD_BALL_SKILL(AttributeGroup.BOWLING, "Old ball"),

    // ---- Fielding ----------------------------------------------------------
    /** Outfield catching: the ball travelling, with time to judge it. */
    CATCHING(AttributeGroup.FIELDING, "Catching"),

    /** Gathering, intercepting and cutting off the boundary. */
    GROUND_FIELDING(AttributeGroup.FIELDING, "Ground fielding"),

    /** Throw power and accuracy; feeds the run-out margin directly. */
    THROW_ARM(AttributeGroup.FIELDING, "Throwing"),

    /** Close catching in the cordon and at short leg, where there is no time to judge. */
    REFLEXES(AttributeGroup.FIELDING, "Reflexes"),

    // ---- Wicketkeeping -----------------------------------------------------
    /** General glovework standing back. */
    GLOVEWORK(AttributeGroup.KEEPING, "Glovework"),

    /** Keeping up to the stumps to spin: the stumping attribute. */
    STANDING_UP(AttributeGroup.KEEPING, "Standing up"),

    /** Taking the ball down the leg side, where byes and missed stumpings live. */
    LEG_SIDE_COLLECTION(AttributeGroup.KEEPING, "Leg side"),

    // ---- Physical ----------------------------------------------------------
    /** Baseline conditioning. Slows fatigue accumulation across a day and a season. */
    FITNESS(AttributeGroup.PHYSICAL, "Fitness"),

    /**
     * Endurance within a spell or a long innings.
     *
     * Deliberately ONE attribute, though the brief lists stamina under both
     * bowling and physical. Two staminas would need two decay curves, two
     * training paths and a rule for which one a bowling all-rounder uses; the
     * bowling fatigue model reads this one.
     */
    STAMINA(AttributeGroup.PHYSICAL, "Stamina"),

    /** Sprint speed. Runs between the wickets, and chasing the ball down. */
    SPEED(AttributeGroup.PHYSICAL, "Speed"),

    /** Robustness. Distinct from the hidden `injuryProneness`: this one is visible and trainable. */
    INJURY_RESISTANCE(AttributeGroup.PHYSICAL, "Injury resistance"),

    // ---- Mental ------------------------------------------------------------
    /** Control and repeatability. A disciplined bowler is accurate but predictable. */
    DISCIPLINE(AttributeGroup.MENTAL, "Discipline"),

    /** Appetite for attack, with bat and ball. Raises both reward and risk. */
    AGGRESSION(AttributeGroup.MENTAL, "Aggression"),

    /** Holding technique together under pressure. Damps the pressure index's effect. */
    COMPOSURE(AttributeGroup.MENTAL, "Composure"),

    /** Captaincy and dressing-room standing. Read by the selection model. */
    LEADERSHIP(AttributeGroup.MENTAL, "Leadership"),

    /** Adjusting to new conditions, new formats and a higher standard. */
    ADAPTABILITY(AttributeGroup.MENTAL, "Adaptability"),
    ;

    companion object {
        /** All attributes, in declaration order. Allocation-free: computed once. */
        val ALL: List<Attribute> = entries.toList()

        /** Attributes in a group, in declaration order. */
        private val BY_GROUP: Map<AttributeGroup, List<Attribute>> =
            AttributeGroup.entries.associateWith { group -> ALL.filter { it.group == group } }

        fun inGroup(group: AttributeGroup): List<Attribute> = BY_GROUP.getValue(group)

        /** Lookup by serialized name, for the editable database. Null when unknown. */
        fun byName(name: String): Attribute? = BY_NAME[name]

        private val BY_NAME: Map<String, Attribute> = ALL.associateBy { it.name }
    }
}

/**
 * Attribute groupings, used by training, ageing and generation.
 *
 * [decaysEarly] marks the groups that decline first and fastest with age.
 * Physical attributes go early; the mental ones can still be improving in a
 * player's mid-thirties, which is what makes an ageing batter's decline
 * gradual and an ageing fast bowler's decline a cliff.
 */
enum class AttributeGroup(val displayName: String, val decaysEarly: Boolean) {
    BATTING("Batting", decaysEarly = false),
    BOWLING("Bowling", decaysEarly = false),
    FIELDING("Fielding", decaysEarly = true),
    KEEPING("Wicketkeeping", decaysEarly = false),
    PHYSICAL("Physical", decaysEarly = true),
    MENTAL("Mental", decaysEarly = false),
    ;

    companion object {
        val ALL: List<AttributeGroup> = entries.toList()
    }
}
