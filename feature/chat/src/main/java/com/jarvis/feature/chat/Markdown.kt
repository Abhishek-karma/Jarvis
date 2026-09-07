package com.jarvis.feature.chat


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


        if (tableRowRegex.matches(line) &&
            i + 1 < lines.size &&
            isTableDelimiter(lines[i + 1])
        ) {
            val headerCells = splitTableRow(line)
            i += 2
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
            i++
            blocks.add(MdBlock.CodeBlock(lang, code.toString().trimEnd('\n')))
            continue
        }


        val heading = Regex("^\\s{0,3}(#{1,6})\\s+(.+)$").find(line)
        if (heading != null) {
            blocks.add(MdBlock.Heading(heading.groupValues[1].length, parseInline(heading.groupValues[2])))
            i++
            continue
        }


        if (Regex("^\\s{0,3}(\\*{3,}|-{3,}|_{3,})\\s*$").matches(line)) {
            blocks.add(MdBlock.Divider)
            i++
            continue
        }


        if (line.trimStart().startsWith(">")) {
            val quoteLines = mutableListOf<String>()
            while (i < lines.size && lines[i].trimStart().startsWith(">")) {
                quoteLines.add(lines[i].trimStart().removePrefix(">").trimStart())
                i++
            }
            blocks.add(MdBlock.Quote(parseInline(quoteLines.joinToString(" "))))
            continue
        }


        val bulletMarker = Regex("^\\s*([-+*])\\s+").find(line)
        if (bulletMarker != null) {


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


        if (line.isBlank()) {
            i++
            continue
        }



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


        if (c == '`') {
            val end = source.indexOf('`', i + 1)
            if (end > i) {
                flush()
                spans.add(MdSpan(source.substring(i + 1, end), code = true))
                i = end + 1
                continue
            }
        }


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


        if ((c == '*' || c == '_') && !(i + 1 < source.length && (source[i + 1] == '*' || source[i + 1] == '_'))) {
            val end = source.indexOf(c, i + 1)
            if (end > i) {
                flush()
                spans.add(MdSpan(source.substring(i + 1, end), italic = true))
                i = end + 1
                continue
            }
        }


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
