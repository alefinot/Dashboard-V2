package com.alefinot.dashboardpp.vehicles

import java.util.Locale

/**
 * Text form of a [Vehicle]: one JSON record inside `vehicles.json`, the same shape `SessionCodec`
 * uses for a trip session.
 *
 * Hand-rolled on purpose, for the reason [com.alefinot.dashboardpp.api.FlatJson]
 * states in its own doc: the org.json classes in the unit-test android.jar are
 * stubs that throw, so a codec written against them could not be tested off-device.
 * The reader still has to speak the whole JSON grammar even though a current record
 * is flat, because a row written by an older build carries an `ips` array that has to
 * be parsed in order to be skipped. A self-contained reader and writer of the format
 * written here is a few dozen lines, keeps rule 13 (no new dependencies) intact, and
 * makes the format's tolerance rules testable.
 *
 * [decode] is deliberately forgiving, because losing a card the user named is the
 * expensive failure and a garbled field is not:
 *
 * - unknown keys are skipped (a newer build's record must not be wrecked by an
 *   older app reading it back, and this is how a record from the Wi-Fi era - which
 *   carried an `ips` array - still reads with that array simply dropped),
 * - missing keys take the [Vehicle] defaults,
 * - a `ble` value that is not a string leaves [Vehicle.bleAddress] null rather than
 *   dropping the card,
 * - a `type` this build does not know falls back to [VehicleType.OTHER] rather
 *   than dropping the card,
 * - a field whose value has the wrong shape defaults that field only.
 *
 * It returns `null` only for text that cannot be used at all - not an object, a
 * version tag this build cannot read, or a body that does not parse - which is how [GarageFile]
 * tells "this record is junk" from "this file is not ours" and refuses to write the garage back
 * over a card it could not read.
 *
 * Nothing here decides what a value *means*: `mac` round-trips verbatim, junk
 * included, because identity belongs to [VehicleMatch] and the store's job is to
 * remember what it was given.
 */
object VehicleCodec {

    /** Format tag, checked by [decode]. Bump only with a reader that still reads v1. */
    private const val V = "v1"

    /** Hostile or torn text may nest; a bounded reader cannot be talked into a stack overflow. */
    private const val MAX_DEPTH = 8

    fun encode(v: Vehicle): String {
        val parts = ArrayList<String>(15)
        parts.add(field("v", quote(V)))
        parts.add(field("id", quote(v.id)))
        parts.add(field("mac", nullable(v.mac)))
        parts.add(field("name", quote(v.name)))
        parts.add(field("photoFile", nullable(v.photoFile)))
        parts.add(field("type", quote(v.type.name)))
        parts.add(field("plate", quote(v.plate)))
        parts.add(field("maker", quote(v.maker)))
        parts.add(field("model", quote(v.model)))
        parts.add(field("year", quote(v.year)))
        parts.add(field("notes", quote(v.notes)))
        parts.add(field("ble", nullable(v.bleAddress)))
        parts.add(field("lastConnectedAtMs", v.lastConnectedAtMs?.toString() ?: "null"))
        parts.add(field("firmwareVersion", nullable(v.firmwareVersion)))
        parts.add(field("createdAtMs", v.createdAtMs.toString()))
        return parts.joinToString(prefix = "{", postfix = "}")
    }

    fun decode(text: String): Vehicle? {
        val obj = parseValue(text) as? Map<*, *> ?: return null
        return decode(obj)
    }

    /**
     * A record that has already been parsed out of a larger document — today
     * [GarageFile], which reads one JSON document holding every card. The tolerance rules
     * and the `v` tag check are the same ones [decode] applies to a lone row, because a
     * record must not mean two different things depending on which file it came in.
     */
    internal fun decode(obj: Map<*, *>): Vehicle? {

        // A record from another format version is not half-read: the fields a later
        // build might add could be misread as defaults that then get written back.
        if (obj["v"] != V) return null

        return Vehicle(
            id = str(obj, "id", ""),
            mac = nullableStr(obj, "mac"),
            name = str(obj, "name", ""),
            photoFile = nullableStr(obj, "photoFile"),
            type = typeOf(obj),
            plate = str(obj, "plate", ""),
            maker = str(obj, "maker", ""),
            model = str(obj, "model", ""),
            year = str(obj, "year", ""),
            notes = str(obj, "notes", ""),
            bleAddress = nullableStr(obj, "ble"),
            lastConnectedAtMs = longOrNull(obj, "lastConnectedAtMs"),
            firmwareVersion = nullableStr(obj, "firmwareVersion"),
            createdAtMs = longOrNull(obj, "createdAtMs") ?: 0L,
        )
    }

    // --- field readers: wrong shape defaults that field, never the record -------

    /**
     * A whole JSON document as maps, lists and scalars, or `null` for text that does not parse.
     *
     * Exposed inside the module because [GarageFile] needs the same grammar for its container:
     * two readers of one format is how a file starts meaning two things.
     */
    internal fun parseValue(text: String): Any? =
        try {
            Parser(text).parseTop()
        } catch (e: Exception) {
            null
        }

    /**
     * One integer field of an already-parsed object: the number kept as the text it was written
     * with, exactly as [longOrNull] does for a timestamp, so a hand-written `7.0` reads as 7 and
     * nothing in the file passes through a binary fraction.
     */
    internal fun intOrNull(obj: Map<*, *>, key: String): Int? {
        val text = when (val raw = obj[key]) {
            is Num -> raw.text
            is String -> raw
            else -> null
        } ?: return null
        return text.toIntOrNull() ?: text.toDoubleOrNull()?.toInt()
    }

    private fun str(obj: Map<*, *>, key: String, fallback: String): String =
        obj[key] as? String ?: fallback

    /**
     * A value from a parsed object as the text the file wrote it with, for a message that has to
     * quote the file back at someone.
     *
     * [parseValue] keeps numbers in an opaque [Num] so nothing passes through a binary fraction,
     * and that wrapper has no `toString()` of its own: interpolating one gives
     * `com.alefinot.dashboardpp.vehicles.VehicleCodec$Num@47e2e487`. That string is what an owner
     * would read on the vehicle page when `vehicles.json` came from a newer build — and it is the
     * one sentence that should tell them to update the app rather than delete the file.
     */
    internal fun textOf(raw: Any?): String = when (raw) {
        is Num -> raw.text
        is String -> raw
        null -> "(none)"
        else -> raw.toString()
    }

    private fun nullableStr(obj: Map<*, *>, key: String): String? = obj[key] as? String

    /**
     * Epoch milliseconds as they were written. Numbers stay as their source text
     * through the parser (see [Parser.Num]) so a 13-digit timestamp never passes
     * through a Double's 2^53 ceiling, and a value like `1700000000000.0` - which a
     * hand-edited file or a future writer might produce - still reads, following
     * `JsonFields.long`.
     */
    private fun longOrNull(obj: Map<*, *>, key: String): Long? {
        val text = when (val raw = obj[key]) {
            is Num -> raw.text
            is String -> raw
            else -> null
        } ?: return null
        return text.toLongOrNull() ?: text.toDoubleOrNull()?.toLong()
    }

    private fun typeOf(obj: Map<*, *>): VehicleType = when (obj["type"]) {
        null -> VehicleType.CAR // missing key: the data-class default
        "CAR" -> VehicleType.CAR
        "MOTORCYCLE" -> VehicleType.MOTORCYCLE
        "OTHER" -> VehicleType.OTHER
        // A name this build does not know (a newer app wrote "TRUCK"): keep the
        // card and file it under the catch-all; dropping it would delete a vehicle.
        else -> VehicleType.OTHER
    }

    // --- writer ----------------------------------------------------------------

    private fun field(key: String, rawValue: String): String = "${quote(key)}:$rawValue"

    private fun nullable(value: String?): String = if (value == null) "null" else quote(value)

    /**
     * A JSON string literal. Only the characters JSON requires escaping are
     * escaped, so names stay readable in a backup file; Unicode (including the
     * surrogate pairs of an emoji) is written as itself, and [Parser] reads it back
     * one char at a time, which reassembles them.
     */
    private fun quote(s: String): String = buildString(s.length + 2) {
        append('"')
        for (c in s) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ') {
                    // Locale.US: a locale that renders digits differently must not
                    // change an escape sequence we later have to read back.
                    append(String.format(Locale.US, "\\u%04x", c.code))
                } else {
                    append(c)
                }
            }
        }
        append('"')
    }

    /** A number as it appeared in the text, kept unparsed on purpose - see [longOrNull]. */
    private class Num(val text: String)

    private class Malformed(message: String) : Exception(message)

    /**
     * The whole JSON grammar this format needs: objects, arrays, strings with
     * escapes, numbers, literals. Anything outside it throws [Malformed], which
     * [decode] turns into a `null` record.
     */
    private class Parser(private val s: String) {
        private var i = 0

        fun parseTop(): Any? {
            val value = value(0)
            skipWs()
            if (i != s.length) throw Malformed("trailing text after the record")
            return value
        }

        private fun value(depth: Int): Any? {
            if (depth > MAX_DEPTH) throw Malformed("nested too deeply")
            skipWs()
            if (i >= s.length) throw Malformed("unexpected end of text")
            return when (s[i]) {
                '{' -> objectValue(depth)
                '[' -> arrayValue(depth)
                '"' -> stringValue()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> numberValue()
            }
        }

        private fun objectValue(depth: Int): Map<String, Any?> {
            val out = LinkedHashMap<String, Any?>()
            i++ // '{'
            skipWs()
            if (i < s.length && s[i] == '}') {
                i++
                return out
            }
            while (true) {
                skipWs()
                if (i >= s.length || s[i] != '"') throw Malformed("expected a key")
                val key = stringValue()
                skipWs()
                if (i >= s.length || s[i] != ':') throw Malformed("expected ':'")
                i++
                out[key] = value(depth + 1)
                skipWs()
                if (i >= s.length) throw Malformed("unterminated object")
                when (s[i]) {
                    ',' -> i++
                    '}' -> {
                        i++
                        return out
                    }
                    else -> throw Malformed("expected ',' or '}'")
                }
            }
        }

        private fun arrayValue(depth: Int): List<Any?> {
            val out = ArrayList<Any?>()
            i++ // '['
            skipWs()
            if (i < s.length && s[i] == ']') {
                i++
                return out
            }
            while (true) {
                out.add(value(depth + 1))
                skipWs()
                if (i >= s.length) throw Malformed("unterminated array")
                when (s[i]) {
                    ',' -> i++
                    ']' -> {
                        i++
                        return out
                    }
                    else -> throw Malformed("expected ',' or ']'")
                }
            }
        }

        private fun stringValue(): String {
            val sb = StringBuilder()
            i++ // opening quote
            while (true) {
                if (i >= s.length) throw Malformed("unterminated string")
                when (val c = s[i]) {
                    '"' -> {
                        i++
                        return sb.toString()
                    }
                    '\\' -> {
                        i++
                        if (i >= s.length) throw Malformed("unterminated escape")
                        val e = s[i]
                        if (e == 'u') {
                            // unicodeEscape consumes 'u' and its four digits; the
                            // simple escapes below are only two characters wide.
                            sb.append(unicodeEscape())
                        } else {
                            sb.append(unescape(e) ?: throw Malformed("unknown escape '$e'"))
                            i++ // the escape letter itself
                        }
                    }
                    // A raw control character inside a string is not valid JSON and
                    // is the usual sign of a torn row.
                    else -> {
                        if (c < ' ') throw Malformed("control character in a string")
                        sb.append(c)
                        i++
                    }
                }
            }
        }

        /**
         * The character an escape letter stands for, or null for a letter JSON does
         * not define. Written out rather than inlined in [stringValue] so the
         * "consume both characters" rule has one obvious place.
         */
        private fun unescape(e: Char): Char? = when (e) {
            '"' -> '"'
            '\\' -> '\\'
            '/' -> '/'
            'b' -> '\b'
            'f' -> '\u000C'
            'n' -> '\n'
            'r' -> '\r'
            't' -> '\t'
            else -> null
        }

        /** \uXXXX, where XXXX is exactly four hex digits. */
        private fun unicodeEscape(): Char {
            if (i + 4 > s.length) throw Malformed("truncated \\u escape")
            val hex = s.substring(i + 1, i + 5)
            val code = hex.toIntOrNull(16) ?: throw Malformed("bad \\u escape '$hex'")
            i += 5
            return code.toChar()
        }

        private fun numberValue(): Num {
            val start = i
            // No whitespace in the scan set: a number ends at the first character that
            // cannot be part of one, and the caller's own separator check is what
            // rejects nonsense. "12 34" therefore ends at the space and is refused a
            // step later as `expected ',' or '}'`, while `12 }` and `12,` are ordinary
            // JSON and must read - the writer emits its own fields that way, and a
            // hand-edited row often does too.
            while (i < s.length && (s[i].isDigit() || "eE+-.".contains(s[i]))) i++
            val text = s.substring(start, i)
            if (text.isEmpty() || text.toDoubleOrNull() == null) throw Malformed("not a number: '$text'")
            return Num(text)
        }

        private fun literal(word: String, value: Any?): Any? {
            if (!s.startsWith(word, i)) throw Malformed("unexpected token")
            i += word.length
            return value
        }

        private fun skipWs() {
            while (i < s.length && s[i].isWhitespace()) i++
        }
    }
}
