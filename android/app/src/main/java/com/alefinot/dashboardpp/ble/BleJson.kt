package com.alefinot.dashboardpp.ble

/**
 * The small JSON reader and writer the BLE link needs.
 *
 * The HTTP client could hand every answer to `FlatJson` because the old endpoints
 * all replied with one flat object. BLE answers are envelopes -
 * `{"id":n,"ok":true,"r":{...}}` - and a `config.get` chunk nests the settings
 * under `d`, so the link needs a parser that follows braces. It is written here,
 * on the JVM, for the same reason `FlatJson` exists: the org.json classes in the
 * mockable `android.jar` throw, and protocol framing is exactly the code that has
 * to be tested without a radio.
 *
 * The result is untyped on purpose. The only consumers are settings values, whose
 * firmware types are known from the key, and [Snapshot.parse], which still reads
 * telemetry as the same flat text the unit used to send over HTTP.
 */
object BleJson {

    /** A compact encoding of [value], for the request bodies built in code. */
    fun encode(value: Any?): String = when (value) {
        null -> "null"
        is String -> quote(value)
        is Boolean -> if (value) "true" else "false"
        is Double -> number(value)
        is Float -> number(value.toDouble())
        is Int, is Long, is Short, is Byte -> value.toString()
        is Raw -> value.json
        is Map<*, *> -> value.entries.joinToString(",", "{", "}") { (k, v) ->
            quote(k.toString()) + ":" + encode(v)
        }
        is Iterable<*> -> value.joinToString(",", "[", "]") { encode(it) }
        else -> quote(value.toString())
    }

    /** Builds `{"key":value,...}` from Kotlin pairs. */
    fun params(vararg pairs: Pair<String, Any?>): String =
        pairs.joinToString(",", "{", "}") { (key, value) -> quote(key) + ":" + encode(value) }

    /**
     * Parses [text] into maps, lists and scalars (numbers as [Double]). Null for
     * anything malformed, including trailing garbage after the value.
     */
    fun parse(text: String): Any? {
        val reader = Reader(text)
        return try {
            reader.skipSpace()
            val value = reader.value()
            reader.skipSpace()
            if (reader.index != text.length) null else value
        } catch (e: IllegalArgumentException) {
            null
        } catch (e: StringIndexOutOfBoundsException) {
            null
        }
    }

    /** [parse] for a JSON object; null for any other shape or malformed text. */
    fun obj(text: String): Map<String, Any?>? = parse(text) as? Map<String, Any?>

    /** One typed field of a parsed object: a JSON string. */
    fun string(map: Map<String, Any?>, key: String): String? = map[key] as? String

    /** One typed field of a parsed object: a JSON number, truncated to Int. */
    fun int(map: Map<String, Any?>, key: String): Int? = (map[key] as? Double)?.toInt()

    fun bool(map: Map<String, Any?>, key: String): Boolean? = map[key] as? Boolean

    /** A display string for a settings value: numbers lose the `.0` JSON adds. */
    fun display(value: Any?): String = when (value) {
        null -> "null"
        is String -> value
        is Boolean -> if (value) "true" else "false"
        is Double -> number(value)
        else -> encode(value)
    }

    /** Quotes a string, escaping what JSON requires. */
    fun quote(text: String): String {
        val out = StringBuilder(text.length + 2)
        out.append('"')
        for (c in text) {
            when (c) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                else -> if (c < ' ') {
                    out.append("\\u")
                    out.append(c.code.toString(16).padStart(4, '0'))
                } else {
                    out.append(c)
                }
            }
        }
        out.append('"')
        return out.toString()
    }

    /** A pre-encoded JSON fragment, so [params] can carry a nested object. */
    @JvmInline
    value class Raw(val json: String)

    private fun number(value: Double): String {
        if (value.isNaN() || value.isInfinite()) return "null"
        if (value == 0.0) return "0"
        val asLong = value.toLong()
        return if (asLong.toDouble() == value && kotlin.math.abs(value) < 1e15) {
            asLong.toString()
        } else {
            value.toString()
        }
    }

    private class Reader(private val text: String) {
        var index = 0

        fun skipSpace() {
            while (index < text.length && text[index].isWhitespace()) index++
        }

        fun value(): Any? {
            skipSpace()
            if (index >= text.length) throw IllegalArgumentException("eof")
            return when (text[index]) {
                '{' -> objectValue()
                '[' -> arrayValue()
                '"' -> stringValue()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> numberValue()
            }
        }

        private fun objectValue(): Map<String, Any?> {
            expect('{')
            val out = LinkedHashMap<String, Any?>()
            skipSpace()
            if (peek('}')) {
                index++
                return out
            }
            while (true) {
                skipSpace()
                val key = stringValue()
                skipSpace()
                expect(':')
                out[key] = value()
                skipSpace()
                if (peek(',')) {
                    index++
                    continue
                }
                expect('}')
                return out
            }
        }

        private fun arrayValue(): List<Any?> {
            expect('[')
            val out = ArrayList<Any?>()
            skipSpace()
            if (peek(']')) {
                index++
                return out
            }
            while (true) {
                out.add(value())
                skipSpace()
                if (peek(',')) {
                    index++
                    continue
                }
                expect(']')
                return out
            }
        }

        private fun stringValue(): String {
            expect('"')
            val out = StringBuilder()
            while (true) {
                if (index >= text.length) throw IllegalArgumentException("unterminated")
                val c = text[index++]
                when (c) {
                    '"' -> return out.toString()
                    '\\' -> {
                        if (index >= text.length) throw IllegalArgumentException("escape")
                        when (val e = text[index++]) {
                            '"' -> out.append('"')
                            '\\' -> out.append('\\')
                            '/' -> out.append('/')
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000C')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                if (index + 4 > text.length) throw IllegalArgumentException("u")
                                val hex = text.substring(index, index + 4)
                                index += 4
                                out.append(hex.toInt(16).toChar())
                            }
                            else -> throw IllegalArgumentException("escape $e")
                        }
                    }
                    else -> out.append(c)
                }
            }
        }

        private fun numberValue(): Double {
            val start = index
            if (peek('-')) index++
            while (index < text.length && text[index].isDigit()) index++
            if (peek('.')) {
                index++
                while (index < text.length && text[index].isDigit()) index++
            }
            if (peek('e') || peek('E')) {
                index++
                if (peek('+') || peek('-')) index++
                while (index < text.length && text[index].isDigit()) index++
            }
            if (start == index) throw IllegalArgumentException("number")
            return text.substring(start, index).toDouble()
        }

        private fun literal(word: String, value: Any?): Any? {
            if (!text.startsWith(word, index)) throw IllegalArgumentException("literal")
            index += word.length
            return value
        }

        private fun peek(c: Char): Boolean = index < text.length && text[index] == c

        private fun expect(c: Char) {
            if (!peek(c)) throw IllegalArgumentException("expected $c")
            index++
        }
    }
}
