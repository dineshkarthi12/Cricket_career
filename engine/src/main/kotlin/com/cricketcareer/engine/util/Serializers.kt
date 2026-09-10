package com.cricketcareer.engine.util

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * ISO-8601 dates ("1999-04-17") in the seed database and in saves.
 *
 * `java.time.LocalDate` rather than a hand-rolled date type: it is pure, has no
 * clock in it unless you call `now()` (banned in :engine), and is available on
 * Android from API 26, which is the project's minimum. Leap years and month
 * lengths are not a wheel worth reinventing for a game where a player's age
 * decides his growth curve.
 */
object LocalDateSerializer : KSerializer<LocalDate> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("java.time.LocalDate", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: LocalDate) {
        encoder.encodeString(value.toString())
    }

    override fun deserialize(decoder: Decoder): LocalDate {
        val raw = decoder.decodeString()
        return try {
            LocalDate.parse(raw)
        } catch (e: DateTimeParseException) {
            throw SerializationException("'$raw' is not a date in YYYY-MM-DD form", e)
        }
    }
}
