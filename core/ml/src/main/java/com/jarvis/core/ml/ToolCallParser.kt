package com.jarvis.core.ml

/**
 * Parses the strict on-device agent tool-call formats: `[[{"name":...,"args":{...}}]]`
 * and the observed Gemma `<|toolcall|>call:devicecontrol:createfile{...}<tool_call>`.
 * Extracts balanced JSON (respecting string escapes), validates `name`/`args`, and
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
    fun parseAll(text: String): List<ParsedToolCall> =
        (findBracketSpans(text) + findGemmaSpans(text))
            .sortedBy { it.start }
            .flatMap { it.calls }
            .distinctBy { it.name + "\n" + it.argsJson }

    /**
     * Removes all well-formed tool-call blocks, preserving surrounding prose. Leftover
     * markers are also stripped so raw protocol never reaches user-visible text.
     */
    fun stripToolCalls(text: String): String {
        val spans = (findBracketSpans(text) + findGemmaSpans(text)).sortedBy { it.start }
        val sb = StringBuilder()
        var cursor = 0
        for (span in spans) {
            if (span.start < cursor) continue
            sb.append(text, cursor, span.start)
            cursor = span.endExclusive
        }
        sb.append(text, cursor, text.length)
        return stripOrphanMarkers(sb.toString())
    }

    private fun findSpans(text: String): List<Span> = findBracketSpans(text)

    private fun findBracketSpans(text: String): List<Span> {
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

    /**
     * Strictly scoped fallback for the observed on-device Gemma format:
     * `<|toolcall|>call:devicecontrol:createfile{filename:"welcome.txt"}<tool_call>`
     * Only `call:<name>{...}` inside tool markers is accepted; the name must resolve via
     * [LocalToolNameAliases] and `{...}` must be a balanced, validated args object.
     */
    private fun findGemmaSpans(text: String): List<Span> {
        val spans = mutableListOf<Span>()
        var i = 0
        while (i < text.length) {
            val open = indexOfMarkerOpen(text, i) ?: break
            val close = indexOfMarkerClose(text, open.endExclusive) ?: break
            val inner = text.substring(open.endExclusive, close.start)
            parseGemmaCall(inner)?.let { spans += Span(open.start, close.endExclusive, listOf(it)) }
            i = close.endExclusive
        }
        return spans
    }

    private data class Marker(val start: Int, val endExclusive: Int)

    private fun indexOfMarkerOpen(text: String, from: Int): Marker? {
        val idx = text.indexOf(OPEN_MARKER, from, ignoreCase = true)
        if (idx == -1) return null
        return Marker(idx, idx + OPEN_MARKER.length)
    }

    private fun indexOfMarkerClose(text: String, from: Int): Marker? {
        return CLOSE_MARKERS.mapNotNull { marker ->
            val idx = text.indexOf(marker, from, ignoreCase = true)
            if (idx == -1) null else Marker(idx, idx + marker.length)
        }.minByOrNull { it.start }
    }

    private fun parseGemmaCall(inner: String): ParsedToolCall? {
        val callIdx = findCallKeyword(inner) ?: return null
        val brace = inner.indexOf('{', callIdx + CALL_KEYWORD.length)
        if (brace == -1) return null
        val rawName = inner.substring(callIdx + CALL_KEYWORD.length, brace).trim().trimEnd(':').trim()
        if (rawName.isEmpty() || rawName.length > 128) return null
        val argsEnd = findObjectClose(inner, brace)
        if (argsEnd == -1) return null
        if (inner.substring(argsEnd + 1).trim().trim(':').isNotEmpty()) return null
        val rawArgs = inner.substring(brace, argsEnd + 1)
        val argsJson = normalizeLenientArgs(rawArgs) ?: return null
        // Well-formed calls with unknown names are surfaced with the raw name so the
        // agent layer (ToolRegistry lookup) rejects them through the canonical path —
        // silently dropping them would hide the protocol mismatch from the model.
        val canonical = LocalToolNameAliases.resolve(rawName) ?: return ParsedToolCall(rawName, argsJson)
        return ParsedToolCall(canonical, argsJson)
    }

    private fun findCallKeyword(inner: String): Int? {
        var from = 0
        while (true) {
            val idx = inner.indexOf(CALL_KEYWORD, from, ignoreCase = true)
            if (idx == -1) return null
            val prev = if (idx == 0) ' ' else inner[idx - 1]
            if (!prev.isLetterOrDigit() && prev != '_') return idx
            from = idx + 1
        }
    }

    /** Normalizes lenient `{key:"v"}` args into strict JSON; null when invalid. */
    private fun normalizeLenientArgs(raw: String): String? {
        if (raw.length > MAX_GEMMA_ARGS_CHARS) return null
        val out = StringBuilder()
        var j = 0
        var depth = 0
        while (j < raw.length) {
            val c = raw[j]
            when {
                c == '"' -> {
                    val end = findStringEnd(raw, j) ?: return null
                    out.append(raw, j, end + 1)
                    j = end + 1
                }
                c == '{' -> {
                    depth++
                    if (depth > MAX_GEMMA_ARGS_DEPTH) return null
                    out.append(c)
                    j++
                }
                c == '}' -> {
                    depth--
                    if (depth < 0) return null
                    out.append(c)
                    j++
                }
                c == ':' || c == ',' -> {
                    out.append(c)
                    j++
                }
                c.isWhitespace() -> {
                    out.append(c)
                    j++
                }
                else -> {
                    var k = j
                    while (k < raw.length && (raw[k].isLetterOrDigit() || raw[k] == '_' || raw[k] == '.' || raw[k] == '-')) k++
                    if (k == j) return null
                    val token = raw.substring(j, k)
                    val after = skipWsInline(raw, k)
                    if (after < raw.length && raw[after] == ':') {
                        if (token.isEmpty() || token.length > 64) return null
                        out.append('"').append(token).append('"')
                    } else {
                        if (token != "true" && token != "false" && token != "null" &&
                            token.toDoubleOrNull() == null
                        ) {
                            return null
                        }
                        out.append(token)
                    }
                    j = k
                }
            }
        }
        if (depth != 0) return null
        return out.toString()
    }

    private fun findStringEnd(text: String, quoteIndex: Int): Int? {
        var j = quoteIndex + 1
        while (j < text.length) {
            val c = text[j]
            if (c == '\\') {
                j += 2
            } else if (c == '"') {
                return j
            } else if (c < ' ') {
                return null
            } else {
                j++
            }
        }
        return null
    }

    private fun skipWsInline(text: String, from: Int): Int {
        var i = from
        while (i < text.length && (text[i] == ' ' || text[i] == '\t' || text[i] == '\n' || text[i] == '\r')) i++
        return i
    }

    private fun stripOrphanMarkers(text: String): String {
        var out = text
        for (marker in CLOSE_MARKERS + OPEN_MARKER) {
            out = out.replace(marker, "", ignoreCase = true)
        }
        return out
    }

    private const val OPEN_MARKER = "<|toolcall|>"
    private val CLOSE_MARKERS = listOf("<tool_call>", "</tool_call>", "<|tool_call|>", "<|toolcall|>")
    private const val CALL_KEYWORD = "call:"
    private const val MAX_GEMMA_ARGS_CHARS = 4000
    private const val MAX_GEMMA_ARGS_DEPTH = 4

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
