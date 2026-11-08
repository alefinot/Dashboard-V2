package com.alefinot.dashboardpp.vehicles

import java.util.Locale

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

    internal fun decode(obj: Map<*, *>): Vehicle? {

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

    internal fun parseValue(text: String): Any? =
        try {
            Parser(text).parseTop()
        } catch (e: Exception) {
            null
        }

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

    internal fun textOf(raw: Any?): String = when (raw) {
        is Num -> raw.text
        is String -> raw
        null -> "(none)"
        else -> raw.toString()
    }

    private fun nullableStr(obj: Map<*, *>, key: String): String? = obj[key] as? String

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
        else -> VehicleType.OTHER
    }

    // --- writer ----------------------------------------------------------------

    private fun field(key: String, rawValue: String): String = "${quote(key)}:$rawValue"

    private fun nullable(value: String?): String = if (value == null) "null" else quote(value)

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
                            sb.append(unicodeEscape())
                        } else {
                            sb.append(unescape(e) ?: throw Malformed("unknown escape '$e'"))
                            i++ // the escape letter itself
                        }
                    }
                    else -> {
                        if (c < ' ') throw Malformed("control character in a string")
                        sb.append(c)
                        i++
                    }
                }
            }
        }

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
