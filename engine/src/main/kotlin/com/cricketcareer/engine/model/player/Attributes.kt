package com.cricketcareer.engine.model.player

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * A cricketer's full attribute set: one value in [MIN]..[MAX] per [Attribute].
 *
 * Immutable. Every "change" returns a new instance — the career layer applies
 * training and ageing by producing a new set, which keeps a player's history
 * inspectable and keeps the match engine unable to mutate anyone mid-match.
 *
 * Stored as an `IntArray` indexed by [Attribute.ordinal] rather than as ~43
 * named fields, so training, ageing, injury and generation can work over groups
 * in a loop. Named access is restored by the accessors below, so the maths in
 * the engine still reads in cricket terms.
 *
 * Serialized as a name → value map (see [Attributes.Serializer]), never as a
 * positional array, because the seed database is meant to be edited by hand:
 * `"TECHNIQUE": 71` survives a reordering of the enum and tells a reader what it
 * is. That is also why an unrecognised key is an error — it is almost always a
 * typo, and silently dropping it would leave someone wondering why their edit
 * did nothing.
 */
@Serializable(with = Attributes.Serializer::class)
class Attributes private constructor(private val values: IntArray) {

    init {
        require(values.size == Attribute.ALL.size) {
            "expected ${Attribute.ALL.size} attribute values, got ${values.size}"
        }
    }

    operator fun get(attribute: Attribute): Int = values[attribute.ordinal]

    /**
     * The attribute on the engine's working scale, [0, 1].
     *
     * Nothing in the simulation reads a raw 1-100 value; it normalises here,
     * once, at the boundary. See docs/SIMULATION_MODEL.md §2.
     */
    fun normalised(attribute: Attribute): Double = (values[attribute.ordinal] - MIN).toDouble() / (MAX - MIN)

    /** A copy with [attribute] set to [value], clamped into range. */
    fun with(attribute: Attribute, value: Int): Attributes {
        val copy = values.copyOf()
        copy[attribute.ordinal] = value.coerceIn(MIN, MAX)
        return Attributes(copy)
    }

    /** A copy with each supplied attribute replaced. */
    fun withAll(changes: Map<Attribute, Int>): Attributes {
        if (changes.isEmpty()) return this
        val copy = values.copyOf()
        changes.forEach { (attribute, value) -> copy[attribute.ordinal] = value.coerceIn(MIN, MAX) }
        return Attributes(copy)
    }

    /** A copy with [delta] added to every attribute in [group]. Used by ageing and injury. */
    fun adjustGroup(group: AttributeGroup, delta: Int): Attributes {
        val copy = values.copyOf()
        Attribute.inGroup(group).forEach { copy[it.ordinal] = (copy[it.ordinal] + delta).coerceIn(MIN, MAX) }
        return Attributes(copy)
    }

    /** A copy produced by applying [transform] to every attribute. */
    fun map(transform: (Attribute, Int) -> Int): Attributes {
        val copy = IntArray(values.size)
        Attribute.ALL.forEach { copy[it.ordinal] = transform(it, values[it.ordinal]).coerceIn(MIN, MAX) }
        return Attributes(copy)
    }

    /** Mean value across a group. The headline number shown for "Batting" in the UI. */
    fun groupAverage(group: AttributeGroup): Double {
        val members = Attribute.inGroup(group)
        return members.sumOf { values[it.ordinal] }.toDouble() / members.size
    }

    fun toMap(): Map<Attribute, Int> = Attribute.ALL.associateWith { values[it.ordinal] }

    /** Defensive copy; callers must not be able to reach into the backing array. */
    fun toIntArray(): IntArray = values.copyOf()

    override fun equals(other: Any?): Boolean =
        this === other || (other is Attributes && values.contentEquals(other.values))

    override fun hashCode(): Int = values.contentHashCode()

    override fun toString(): String =
        AttributeGroup.ALL.joinToString(", ") { "${it.displayName}=${"%.0f".format(groupAverage(it))}" }

    companion object {
        const val MIN: Int = 1
        const val MAX: Int = 100

        /**
         * Value used for any attribute a seed record does not mention.
         *
         * 20, not 1 and not 50: a record that omits the keeping attributes is
         * describing someone who does not keep, and a genuine non-keeper handed
         * the gloves in an emergency is poor rather than incapable. Omitting an
         * attribute is legitimate; a hand-edited database would be unusable if
         * every player had to list all 43.
         */
        const val DEFAULT: Int = 20

        /** Every attribute at [DEFAULT]. */
        fun defaults(): Attributes = uniform(DEFAULT)

        /** Every attribute at [value]. The basis of the calibration "average player". */
        fun uniform(value: Int): Attributes {
            require(value in MIN..MAX) { "attribute value $value outside $MIN..$MAX" }
            return Attributes(IntArray(Attribute.ALL.size) { value })
        }

        /** From an explicit map; anything absent takes [DEFAULT]. */
        fun of(values: Map<Attribute, Int>): Attributes {
            val array = IntArray(Attribute.ALL.size) { DEFAULT }
            values.forEach { (attribute, value) ->
                require(value in MIN..MAX) { "${attribute.name} = $value is outside $MIN..$MAX" }
                array[attribute.ordinal] = value
            }
            return Attributes(array)
        }

        /** From an array in [Attribute] ordinal order. Values are clamped. */
        fun ofOrdinalArray(values: IntArray): Attributes =
            Attributes(IntArray(values.size) { values[it].coerceIn(MIN, MAX) })
    }

    /**
     * Name-keyed JSON, for an editable database.
     *
     * Missing keys take [DEFAULT]; unknown keys fail loudly with the offending
     * name, because an unknown key is a typo and a silent one wastes an
     * afternoon.
     */
    object Serializer : KSerializer<Attributes> {
        private val delegate = MapSerializer(String.serializer(), Int.serializer())
        override val descriptor: SerialDescriptor = delegate.descriptor

        override fun serialize(encoder: Encoder, value: Attributes) {
            // Sorted by declaration order, not alphabetically: a hand-editing
            // user reads batting attributes together, not TECHNIQUE next to THROW_ARM.
            delegate.serialize(encoder, Attribute.ALL.associate { it.name to value[it] })
        }

        override fun deserialize(decoder: Decoder): Attributes {
            val raw = delegate.deserialize(decoder)
            val array = IntArray(Attribute.ALL.size) { DEFAULT }
            raw.forEach { (key, value) ->
                val attribute = Attribute.byName(key)
                    ?: throw SerializationException(
                        "unknown attribute '$key'. Valid names: ${Attribute.ALL.joinToString(", ") { it.name }}",
                    )
                if (value !in MIN..MAX) {
                    throw SerializationException("attribute '$key' = $value is outside $MIN..$MAX")
                }
                array[attribute.ordinal] = value
            }
            return Attributes(array)
        }
    }
}

// --- Named access -----------------------------------------------------------
// So the engine reads `attributes.shortBallPlay` rather than
// `attributes[Attribute.SHORT_BALL_PLAY]`. Generated by hand deliberately: the
// list is stable and an accessor is one line.

val Attributes.technique: Int get() = this[Attribute.TECHNIQUE]
val Attributes.timing: Int get() = this[Attribute.TIMING]
val Attributes.power: Int get() = this[Attribute.POWER]
val Attributes.footwork: Int get() = this[Attribute.FOOTWORK]
val Attributes.backfootPlay: Int get() = this[Attribute.BACKFOOT_PLAY]
val Attributes.frontfootPlay: Int get() = this[Attribute.FRONTFOOT_PLAY]
val Attributes.spinPlay: Int get() = this[Attribute.SPIN_PLAY]
val Attributes.pacePlay: Int get() = this[Attribute.PACE_PLAY]
val Attributes.shortBallPlay: Int get() = this[Attribute.SHORT_BALL_PLAY]
val Attributes.swingPlay: Int get() = this[Attribute.SWING_PLAY]
val Attributes.patience: Int get() = this[Attribute.PATIENCE]
val Attributes.strikeRotation: Int get() = this[Attribute.STRIKE_ROTATION]
val Attributes.rangeHitting: Int get() = this[Attribute.RANGE_HITTING]
val Attributes.concentration: Int get() = this[Attribute.CONCENTRATION]
val Attributes.runningBetweenWickets: Int get() = this[Attribute.RUNNING_BETWEEN_WICKETS]

val Attributes.pace: Int get() = this[Attribute.PACE]
val Attributes.turn: Int get() = this[Attribute.TURN]
val Attributes.accuracy: Int get() = this[Attribute.ACCURACY]
val Attributes.seamMovement: Int get() = this[Attribute.SEAM_MOVEMENT]
val Attributes.swing: Int get() = this[Attribute.SWING]
val Attributes.reverseSwing: Int get() = this[Attribute.REVERSE_SWING]
val Attributes.bounce: Int get() = this[Attribute.BOUNCE]
val Attributes.drift: Int get() = this[Attribute.DRIFT]
val Attributes.variations: Int get() = this[Attribute.VARIATIONS]
val Attributes.deathBowling: Int get() = this[Attribute.DEATH_BOWLING]
val Attributes.newBallSkill: Int get() = this[Attribute.NEW_BALL_SKILL]
val Attributes.oldBallSkill: Int get() = this[Attribute.OLD_BALL_SKILL]

val Attributes.catching: Int get() = this[Attribute.CATCHING]
val Attributes.groundFielding: Int get() = this[Attribute.GROUND_FIELDING]
val Attributes.throwArm: Int get() = this[Attribute.THROW_ARM]
val Attributes.reflexes: Int get() = this[Attribute.REFLEXES]

val Attributes.glovework: Int get() = this[Attribute.GLOVEWORK]
val Attributes.standingUp: Int get() = this[Attribute.STANDING_UP]
val Attributes.legSideCollection: Int get() = this[Attribute.LEG_SIDE_COLLECTION]

val Attributes.fitness: Int get() = this[Attribute.FITNESS]
val Attributes.stamina: Int get() = this[Attribute.STAMINA]
val Attributes.speed: Int get() = this[Attribute.SPEED]
val Attributes.injuryResistance: Int get() = this[Attribute.INJURY_RESISTANCE]

val Attributes.discipline: Int get() = this[Attribute.DISCIPLINE]
val Attributes.aggression: Int get() = this[Attribute.AGGRESSION]
val Attributes.composure: Int get() = this[Attribute.COMPOSURE]
val Attributes.leadership: Int get() = this[Attribute.LEADERSHIP]
val Attributes.adaptability: Int get() = this[Attribute.ADAPTABILITY]
