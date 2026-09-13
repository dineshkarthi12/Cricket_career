package com.cricketcareer.engine.save

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** A save that cannot be read, and why, in words a bug report can carry. */
class SaveFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Careers to text and back.
 *
 * `:engine` does no I/O, so this is the whole of saving as far as the engine is
 * concerned: it produces a string, and `:data` decides what file it goes in and
 * how it is backed up. The split means every save-format decision in the
 * project is testable on a bare JDK.
 *
 * The JSON is deliberately lenient in one direction and strict in the other:
 *
 * - **Unknown keys are ignored**, so a save written by a later build opens in
 *   an earlier one instead of bricking it. It will be missing whatever that
 *   build added, which is a recoverable disappointment; refusing to open a
 *   player's career is not.
 * - **A newer [CareerSave.saveFormat] is refused** rather than silently
 *   half-read. Ignoring unknown *keys* is safe; ignoring a changed *meaning*
 *   for a key that still exists is not, and the format number is the only thing
 *   that can tell the two apart.
 * - **Defaults are written out**, because a save is a record rather than a
 *   payload, and a field that is absent because it was defaulted is a field
 *   nobody can debug from a bug report.
 */
object SaveCodec {

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
        // Nothing in a save is allowed to be a non-finite double: NaN in a
        // form figure would come back as a career that renders blank
        // everywhere and cannot be diagnosed.
        allowSpecialFloatingPointValues = false
    }

    fun encode(save: CareerSave): String = json.encodeToString(CareerSave.serializer(), save)

    /**
     * Reads a save, or explains why it cannot.
     *
     * Every failure path here ends in a [SaveFormatException] carrying a
     * sentence, because the alternative — a `NullPointerException` from inside
     * a generated deserialiser — reaches the user as "Cricket Dynasty has
     * stopped" and reaches the developer as nothing at all.
     */
    fun decode(text: String): CareerSave {
        val save = try {
            json.decodeFromString(CareerSave.serializer(), text)
        } catch (e: SerializationException) {
            throw SaveFormatException("this save file is not readable: ${e.message}", e)
        } catch (e: IllegalArgumentException) {
            // A CareerSave's own init{} requirements land here.
            throw SaveFormatException("this save file is not a career: ${e.message}", e)
        }
        if (save.saveFormat > CareerSave.CURRENT_SAVE_FORMAT) {
            throw SaveFormatException(
                "this career was saved by a newer version of the game " +
                    "(save format ${save.saveFormat}, this build reads ${CareerSave.CURRENT_SAVE_FORMAT})",
            )
        }
        return save
    }

    /** Reads a save, or null. For a list screen that must not die on one bad file. */
    fun decodeOrNull(text: String): CareerSave? = try {
        decode(text)
    } catch (e: SaveFormatException) {
        null
    }
}
