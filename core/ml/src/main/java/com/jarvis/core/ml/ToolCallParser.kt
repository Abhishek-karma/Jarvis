package com.jarvis.core.ml

/**
 * Parses the strict on-device agent tool-call format:
 * `[[{"name":"tool_name","args":{"key":"value"}}, ...]]`
 *
 * Small local models often ignore the native LiteRT function-calling API and emit the
 * requested call as plain text. Without a text fallback that raw markup flows straight
 * through as the assistant's visible reply and no tool ever executes. This parser extracts
 * balanced JSON (respecting string escapes), validates the `name`/`args` structure, and
 * rejects malformed calls instead of partially parsing them.
 */
object ToolCallParser {
    data class ParsedToolCall(
        val name: String,
        val argsJson: String,
    )

    private data class Span(
        val start: Int,
        val endExclusive: Int,
        val calls: List<ParsedToolCall>,
    )

    /** Every well-formed tool call in [text], in order. Malformed blocks are skipped. */
    fun parseAll(text: String): List<ParsedToolCall> = findSpans(text).flatMap { it.calls }

    /**
     * Removes every well-formed `[[...]]` tool-call block from [text], preserving surrounding
     * prose. Text without a valid call is returned untouched.
     */
    fun stripToolCalls(text: String): String {
        val spans = findSpans(text)
        if (spans.isEmpty()) return text
        val sb = StringBuilder()
        var cursor = 0
        for (span in spans) {
            if (span.start < cursor) continue
            sb.append(text, cursor, span.start)
            cursor = span.endExclusive
        }
        sb.append(text, cursor, text.length)
        return sb.toString()
    }

    private fun findSpans(text: String): List<Span> {
        val spans = mutableListOf<Span>()
        var i = 0
        while (i < text.length - 1) {
            if (text[i] == '[' && text[i + 1] == '[') {
                val close = findBlockClose(text, i)
                if (close != -1 && close - 1 >= i + 2) {
                    val calls = parseCalls(text.substring(i + 2, close - 1))
                    if (calls.isNotEmpty()) {
                        spans += Span(i, close + 1, calls)
                        i = close + 1
                        continue
                    }
                }
            }
            i++
        }
        return spans
    }

    private fun parseCalls(inner: String): List<ParsedToolCall> {
        val calls = mutableListOf<ParsedToolCall>()
        for (obj in extractTopLevelObjects(inner)) {
            parseSingleCall(obj)?.let { calls += it }
        }
        return calls
    }

    private fun parseSingleCall(obj: String): ParsedToolCall? {
        val name = extractName(obj)?.takeIf { it.isNotBlank() } ?: return null
        val args = extractArgs(obj) ?: return null
        return ParsedToolCall(name, args)
    }

    private fun extractName(obj: String): String? {
        var from = 0
        while (true) {
            val keyIdx = obj.indexOf("\"name\"", from)
            if (keyIdx == -1) return null
            var k = skipWs(obj, keyIdx + 6)
            if (k >= obj.length || obj[k] != ':') {
                from = keyIdx + 1
                continue
            }
            k = skipWs(obj, k + 1)
            if (k >= obj.length || obj[k] != '"') {
                from = keyIdx + 1
                continue
            }
            // Unterminated string means the object is malformed: reject it.
            return readJsonString(obj, k) ?: return null
        }
    }

    /**
     * Balanced JSON object after the `"args":` key, `"{}"` when the key is absent, or null
     * when the key is present but not a well-formed object (malformed call).
     */
    private fun extractArgs(obj: String): String? {
        var from = 0
        while (true) {
            val keyIdx = obj.indexOf("\"args\"", from)
            if (keyIdx == -1) return "{}"
            var k = skipWs(obj, keyIdx + 6)
            if (k >= obj.length || obj[k] != ':') {
                from = keyIdx + 1
                continue
            }
            k = skipWs(obj, k + 1)
            if (k >= obj.length || obj[k] != '{') return null
            val end = findObjectClose(obj, k)
            if (end == -1) return null
            return obj.substring(k, end + 1)
        }
    }

    private fun extractTopLevelObjects(s: String): List<String> {
        val objs = mutableListOf<String>()
        var i = 0
        while (i < s.length) {
            if (s[i] != '{') {
                i++
                continue
            }
            val end = findObjectClose(s, i)
            if (end == -1) {
                i++
                continue
            }
            objs += s.substring(i, end + 1)
            i = end + 1
        }
        return objs
    }

    /** Index of the `]` closing the `[[` block opened at [openIndex], or -1. */
    private fun findBlockClose(text: String, openIndex: Int): Int {
        var depth = 0
        var inString = false
        var escape = false
        var j = openIndex
        while (j < text.length) {
            val c = text[j]
            if (inString) {
                if (escape) {
                    escape = false
                } else if (c == '\\') {
                    escape = true
                } else if (c == '"') {
                    inString = false
                }
            } else {
                when (c) {
                    '"' -> inString = true
                    '[' -> depth++
                    ']' -> {
                        depth--
                        if (depth == 0) return j
                    }
                }
            }
            j++
        }
        return -1
    }

    /** Index of the `}` closing the object opened at [openIndex], or -1. */
    private fun findObjectClose(text: String, openIndex: Int): Int {
        var depth = 0
        var inString = false
        var escape = false
        var j = openIndex
        while (j < text.length) {
            val c = text[j]
            if (inString) {
                if (escape) {
                    escape = false
                } else if (c == '\\') {
                    escape = true
                } else if (c == '"') {
                    inString = false
                }
            } else {
                when (c) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return j
                    }
                }
            }
            j++
        }
        return -1
    }

    private fun skipWs(text: String, from: Int): Int {
        var i = from
        while (i < text.length && text[i].isWhitespace()) i++
        return i
    }

    /** Reads the JSON string literal opening at [quoteIndex], unescaping its value. */
    private fun readJsonString(text: String, quoteIndex: Int): String? {
        val sb = StringBuilder()
        var j = quoteIndex + 1
        while (j < text.length) {
            val c = text[j]
            if (c == '\\') {
                if (j + 1 >= text.length) return null
                when (val e = text[j + 1]) {
                    '"', '\\', '/' -> sb.append(e)
                    'b' -> sb.append('\b')
                    'f' -> sb.append('')
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    'u' -> {
                        if (j + 5 >= text.length) return null
                        val code = text.substring(j + 2, j + 6).toIntOrNull(16) ?: return null
                        sb.append(code.toChar())
                        j += 4
                    }
                    else -> sb.append(e)
                }
                j += 2
            } else if (c == '"') {
                return sb.toString()
            } else {
                sb.append(c)
                j++
            }
        }
        return null
    }
}
