package moca.core

internal object Json {
    fun parse(text: String): Any? {
        val parser = Parser(text)
        val value = parser.value()
        parser.skipWhitespace()
        require(parser.atEnd()) { "trailing content at ${parser.position}" }
        return value
    }

    private class Parser(val text: String) {
        var position = 0

        fun atEnd(): Boolean = position >= text.length

        fun skipWhitespace() {
            while (position < text.length && text[position].isWhitespace()) position++
        }

        fun value(): Any? {
            skipWhitespace()
            require(!atEnd()) { "unexpected end of json" }
            return when (val c = text[position]) {
                '{' -> obj()
                '[' -> array()
                '"' -> string()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c.isDigit()) number() else error("unexpected '$c' at $position")
            }
        }

        private fun literal(word: String, result: Any?): Any? {
            require(text.startsWith(word, position)) { "expected $word at $position" }
            position += word.length
            return result
        }

        private fun obj(): Map<String, Any?> {
            expect('{')
            val result = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (peek() == '}') {
                position++
                return result
            }
            while (true) {
                skipWhitespace()
                val key = string()
                skipWhitespace()
                expect(':')
                result[key] = value()
                skipWhitespace()
                when (val c = next()) {
                    ',' -> continue
                    '}' -> return result
                    else -> error("unexpected '$c' at ${position - 1}")
                }
            }
        }

        private fun array(): List<Any?> {
            expect('[')
            val result = mutableListOf<Any?>()
            skipWhitespace()
            if (peek() == ']') {
                position++
                return result
            }
            while (true) {
                result += value()
                skipWhitespace()
                when (val c = next()) {
                    ',' -> continue
                    ']' -> return result
                    else -> error("unexpected '$c' at ${position - 1}")
                }
            }
        }

        private fun string(): String {
            expect('"')
            val builder = StringBuilder()
            while (true) {
                val c = next()
                when (c) {
                    '"' -> return builder.toString()
                    '\\' -> when (val esc = next()) {
                        '"', '\\', '/' -> builder.append(esc)
                        'b' -> builder.append('\b')
                        'f' -> builder.append('\u000C')
                        'n' -> builder.append('\n')
                        'r' -> builder.append('\r')
                        't' -> builder.append('\t')
                        'u' -> {
                            val hex = text.substring(position, position + 4)
                            builder.append(hex.toInt(16).toChar())
                            position += 4
                        }
                        else -> error("bad escape \\$esc")
                    }
                    else -> builder.append(c)
                }
            }
        }

        private fun number(): Double {
            val start = position
            if (peek() == '-') position++
            while (!atEnd() && (text[position].isDigit() || text[position] in ".eE+-")) position++
            return text.substring(start, position).toDouble()
        }

        private fun peek(): Char = text[position]

        private fun next(): Char {
            require(!atEnd()) { "unexpected end of json" }
            return text[position++]
        }

        private fun expect(c: Char) {
            val actual = next()
            require(actual == c) { "expected '$c' but was '$actual' at ${position - 1}" }
        }

        private fun error(message: String): Nothing = throw IllegalArgumentException(message)
    }
}
