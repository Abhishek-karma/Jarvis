package com.jarvis.feature.chat

/**
 * Lightweight markdown model + parser for chat bubbles.
 *
 * A deliberately small subset of CommonMark, covering what LLM responses actually produce:
 * headings, fenced code blocks, inline code, bold, italic, bullet/numbered lists, blockquotes,
 * links and thematic breaks. Kept dependency-free so it runs on the JVM for unit tests.
 */
sealed interface MdBlock {
    data class Paragraph(val spans: List<MdSpan>) : MdBlock
    data class Heading(val level: Int, val spans: List<MdSpan>) : MdBlock
    data class CodeBlock(val language: String?, val code: String) : MdBlock
    data class BulletList(val items: List<List<MdSpan>>) : MdBlock
    data class NumberedList(val items: List<List<MdSpan>>) : MdBlock
    data class Quote(val spans: List<MdSpan>) : MdBlock
    data object Divider : MdBlock
}

data class MdSpan(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val code: Boolean = false,
    val url: String? = null,
)

/** Parse markdown source into a list of block-level nodes. */
fun parseMarkdown(source: String): List<MdBlock> {
    val lines = source.replace("\r\n", "\n").split("\n")
    val blocks = mutableListOf<MdBlock>()
    var i = 0

    while (i < lines.size) {
        val line = lines[i]

        // Fenced code block
        val fence = Regex("^\\s*(```+|~~~+)\\s*(\\S*)\\s*$").find(line)
        if (fence != null) {
            val marker = fence.groupValues[1].first().toString()
            val lang = fence.groupValues[2].ifEmpty { null }
            val code = StringBuilder()
            i++
            while (i < lines.size && !lines[i].trimStart().startsWith(marker)) {
                code.append(lines[i]).append('\n')
                i++
            }
            i++ // skip closing fence
            blocks.add(MdBlock.CodeBlock(lang, code.toString().trimEnd('\n')))
            continue
        }

        // Heading
        val heading = Regex("^\\s{0,3}(#{1,6})\\s+(.+)$").find(line)
        if (heading != null) {
            blocks.add(MdBlock.Heading(heading.groupValues[1].length, parseInline(heading.groupValues[2])))
            i++
            continue
        }

        // Thematic break
        if (Regex("^\\s{0,3}(\\*{3,}|-{3,}|_{3,})\\s*$").matches(line)) {
            blocks.add(MdBlock.Divider)
            i++
            continue
        }

        // Blockquote
        if (line.trimStart().startsWith(">")) {
            val quoteLines = mutableListOf<String>()
            while (i < lines.size && lines[i].trimStart().startsWith(">")) {
                quoteLines.add(lines[i].trimStart().removePrefix(">").trimStart())
                i++
            }
            blocks.add(MdBlock.Quote(parseInline(quoteLines.joinToString(" "))))
            continue
        }

        // Lists
        if (Regex("^\\s*[-+*]\\s+").containsMatchIn(line)) {
            val items = mutableListOf<List<MdSpan>>()
            while (i < lines.size && Regex("^\\s*[-+*]\\s+").containsMatchIn(lines[i])) {
                items.add(parseInline(lines[i].trim().removePrefix(Regex("^[-+*]\\s+").find(lines[i].trim())!!.value)))
                i++
            }
            blocks.add(MdBlock.BulletList(items))
            continue
        }
        if (Regex("^\\s*\\d+\\.\\s+").containsMatchIn(line)) {
            val items = mutableListOf<List<MdSpan>>()
            while (i < lines.size && Regex("^\\s*\\d+\\.\\s+").containsMatchIn(lines[i])) {
                items.add(parseInline(lines[i].trim().replaceFirst(Regex("^\\d+\\.\\s+"), "")))
                i++
            }
            blocks.add(MdBlock.NumberedList(items))
            continue
        }

        // Blank line
        if (line.isBlank()) {
            i++
            continue
        }

        // Paragraph (accumulate until blank line or block start)
        val paraLines = mutableListOf(line)
        i++
        while (i < lines.size && lines[i].isNotBlank() &&
            !lines[i].trimStart().startsWith(">") &&
            !Regex("^\\s{0,3}#{1,6}\\s").matches(lines[i]) &&
            !Regex("^\\s*(```+|~~~+)").matches(lines[i])
        ) {
            paraLines.add(lines[i])
            i++
        }
        blocks.add(MdBlock.Paragraph(parseInline(paraLines.joinToString(" "))))
    }
    return blocks
}

/**
 * Parse inline markdown into styled spans. Supports `code`, **bold**, *italic* and [links](url).
 * Handles nested/overlapping markers gracefully by treating them as plain text if malformed.
 */
fun parseInline(source: String): List<MdSpan> {
    val spans = mutableListOf<MdSpan>()
    val text = StringBuilder()
    var i = 0

    fun flush() {
        if (text.isNotEmpty()) {
            spans.add(MdSpan(text.toString()))
            text.clear()
        }
    }

    while (i < source.length) {
        val c = source[i]

        // Fenced inline code
        if (c == '`') {
            val end = source.indexOf('`', i + 1)
            if (end > i) {
                flush()
                spans.add(MdSpan(source.substring(i + 1, end), code = true))
                i = end + 1
                continue
            }
        }

        // Bold
        if (c == '*' && i + 1 < source.length && source[i + 1] == '*') {
            val end = source.indexOf("**", i + 2)
            if (end > i) {
                flush()
                spans.add(MdSpan(source.substring(i + 2, end), bold = true))
                i = end + 2
                continue
            }
        }
        if (c == '_' && i + 1 < source.length && source[i + 1] == '_') {
            val end = source.indexOf("__", i + 2)
            if (end > i) {
                flush()
                spans.add(MdSpan(source.substring(i + 2, end), bold = true))
                i = end + 2
                continue
            }
        }

        // Italic
        if ((c == '*' || c == '_') && !(i + 1 < source.length && (source[i + 1] == '*' || source[i + 1] == '_'))) {
            val end = source.indexOf(c, i + 1)
            if (end > i) {
                flush()
                spans.add(MdSpan(source.substring(i + 1, end), italic = true))
                i = end + 1
                continue
            }
        }

        // Link
        if (c == '[') {
            val close = source.indexOf(']', i + 1)
            if (close > i && close + 1 < source.length && source[close + 1] == '(') {
                val endParen = source.indexOf(')', close + 2)
                if (endParen > close) {
                    val label = source.substring(i + 1, close)
                    val url = source.substring(close + 2, endParen)
                    flush()
                    spans.add(MdSpan(label, url = url))
                    i = endParen + 1
                    continue
                }
            }
        }

        text.append(c)
        i++
    }
    flush()
    return spans
}
