package com.jarvis.feature.chat

/**
 * Lightweight markdown model + parser for chat bubbles.
 *
 * A deliberately small subset of CommonMark, covering what LLM responses actually produce:
 * headings, fenced code blocks, inline code, bold, italic, bullet/numbered lists, blockquotes,
 * links and thematic breaks. Kept dependency-free so it runs on the JVM for unit tests.
 */
sealed interface MdBlock {
    data class Paragraph(
        val spans: List<MdSpan>,
    ) : MdBlock

    data class Heading(
        val level: Int,
        val spans: List<MdSpan>,
    ) : MdBlock

    data class CodeBlock(
        val language: String?,
        val code: String,
    ) : MdBlock

    data class BulletList(
        val items: List<List<MdSpan>>,
    ) : MdBlock

    data class NumberedList(
        val items: List<List<MdSpan>>,
    ) : MdBlock

    data class Quote(
        val spans: List<MdSpan>,
    ) : MdBlock

    /**
     * A pipe-delimited markdown table. [header] and each [rows] entry hold one span list
     * per cell; [columnCount] is the widest row so the renderer can pad short rows.
     */
    data class TableBlock(
        val header: List<List<MdSpan>>,
        val rows: List<List<List<MdSpan>>>,
        val columnCount: Int,
    ) : MdBlock

    data object Divider : MdBlock
}

data class MdSpan(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val code: Boolean = false,
    val url: String? = null,
)

/** A `| a | b |`-shaped line (at least one pipe with non-blank content around it). */
private val tableRowRegex = Regex("^\\s{0,3}\\|(.+)\\|?\\s*$")

/** The GFM delimiter row: `| --- | :---: | --- |` — only dashes, colons and pipes. */
private val tableDelimiterRegex = Regex("^\\s{0,3}\\|?(\\s*:?-{3,}:?\\s*\\|)+\\s*:?-{3,}:?\\s*\\|?\\s*$")

/** Parse one delimiter row into the column alignment specs (kept for future use). */
private fun isTableDelimiter(line: String): Boolean = tableDelimiterRegex.matches(line)

/** Split a `| a | b |` row into raw cell texts (outer pipes stripped, not cell separators). */
private fun splitTableRow(line: String): List<String> {
    val match = tableRowRegex.find(line) ?: return emptyList()
    // The greedy group can swallow the trailing pipe — drop exactly one before splitting.
    var body = match.groupValues[1]
    if (body.endsWith("|")) body = body.dropLast(1)
    return body
        .split('|')
        .map { it.trim() }
}

/** Parse markdown source into a list of block-level nodes. */
fun parseMarkdown(source: String): List<MdBlock> {
    val lines = source.replace("\r\n", "\n").split("\n")
    val blocks = mutableListOf<MdBlock>()
    var i = 0

    while (i < lines.size) {
        val line = lines[i]

        // Table: a pipe row followed by a GFM delimiter row.
        if (tableRowRegex.matches(line) &&
            i + 1 < lines.size &&
            isTableDelimiter(lines[i + 1])
        ) {
            val headerCells = splitTableRow(line)
            i += 2 // skip header + delimiter
            val bodyRows = mutableListOf<List<List<MdSpan>>>()
            while (i < lines.size && tableRowRegex.matches(lines[i]) && lines[i].isNotBlank()) {
                bodyRows.add(splitTableRow(lines[i]).map { parseInline(it) })
                i++
            }
            val columnCount =
                (listOf(headerCells.size) + bodyRows.map { it.size } + listOf(1)).max()
            blocks.add(
                MdBlock.TableBlock(
                    header = headerCells.map { parseInline(it) },
                    rows = bodyRows,
                    columnCount = columnCount,
                ),
            )
            continue
        }

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
        val bulletMarker = Regex("^\\s*([-+*])\\s+").find(line)
        if (bulletMarker != null) {
            // Strip the same marker shape from every continuation line so the item text is
            // extracted uniformly (never via a second regex match that could disagree).
            val markerRegex = Regex("^\\s*[-+]\\s+|^\\s*\\*\\s+")
            val items = mutableListOf<List<MdSpan>>()
            while (i < lines.size) {
                val m = markerRegex.find(lines[i]) ?: break
                items.add(parseInline(lines[i].removePrefix(m.value)))
                i++
            }
            blocks.add(MdBlock.BulletList(items))
            continue
        }
        if (Regex("^\\s*\\d+\\.\\s+").containsMatchIn(line)) {
            val items = mutableListOf<List<MdSpan>>()
            while (i < lines.size) {
                val m = Regex("^\\s*\\d+\\.\\s+").find(lines[i]) ?: break
                items.add(parseInline(lines[i].removePrefix(m.value)))
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

        // Paragraph (accumulate until blank line or block start). A pipe row followed by
        // a delimiter row also interrupts — GFM tables may open right after prose.
        val paraLines = mutableListOf(line)
        i++
        while (i < lines.size &&
            lines[i].isNotBlank() &&
            !lines[i].trimStart().startsWith(">") &&
            !Regex("^\\s{0,3}#{1,6}\\s").matches(lines[i]) &&
            !Regex("^\\s*(```+|~~~+)").matches(lines[i]) &&
            !(tableRowRegex.matches(lines[i]) && i + 1 < lines.size && isTableDelimiter(lines[i + 1]))
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
