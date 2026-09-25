package com.jarvis.core.voice

/**
 * Utility for natural speech sentence and phrase boundary detection and text normalization.
 * Ensures streaming LLM tokens are buffered into natural speech units for TTS synthesis.
 */
object SentenceSplitter {

    private val ABBREVIATIONS = setOf(
        "mr", "mrs", "ms", "dr", "prof", "sr", "jr", "vs", "eg", "ie", "etc", "approx", "st", "gen", "dept", "fig", "no"
    )

    /**
     * Cleans markdown formatting, code snippets, and URL clutter from LLM text
     * so that the spoken voice output sounds natural and conversational.
     */
    fun cleanForSpeech(rawText: String): String {
        if (rawText.isBlank()) return ""

        var text = rawText

        // Strip code blocks ```...```
        text = text.replace(Regex("```[a-zA-Z]*\\n?[\\s\\S]*?```"), " ")
        // Strip inline code `...`
        text = text.replace(Regex("`([^`]+)`"), "$1")
        // Convert markdown links [Label](url) -> Label
        text = text.replace(Regex("\\[([^\\]]+)\\]\\([^)]+\\)"), "$1")
        // Remove raw URLs
        text = text.replace(Regex("https?://\\S+"), "")
        // Remove markdown bold/italics markers: ***text***, **text**, *text*, __text__, _text_
        text = text.replace(Regex("\\*{1,3}([^*]+)\\*{1,3}"), "$1")
        text = text.replace(Regex("_{1,3}([^_]+)_{1,3}"), "$1")
        // Remove strikethrough ~~text~~
        text = text.replace(Regex("~~([^~]+)~~"), "$1")
        // Remove markdown headers: # Header -> Header
        text = text.replace(Regex("(?m)^#{1,6}\\s*"), "")
        // Convert bullet points to gentle pause
        text = text.replace(Regex("(?m)^\\s*[-*+]\\s+"), "")
        // Convert numbered lists: 1. Item -> Item
        text = text.replace(Regex("(?m)^\\s*\\d+\\.\\s+"), "")
        // Remove blockquote markers: > Quote -> Quote
        text = text.replace(Regex("(?m)^\\s*>\\s*"), "")
        // Normalize multiple spaces and newlines
        text = text.replace(Regex("[ \\t]+"), " ")
        text = text.replace(Regex("\\n{2,}"), "\n")

        return text.trim()
    }

    /**
     * Finds the index (exclusive) of the first natural sentence or phrase boundary in [text].
     * Returns -1 if no natural boundary is reached yet and more streaming tokens should be buffered.
     *
     * @param text The current text buffer.
     * @param isComplete If true, forces splitting remaining buffer text.
     */
    fun findSentenceSplitIndex(text: String, isComplete: Boolean): Int {
        if (text.isBlank()) return -1
        if (isComplete) return text.length

        val len = text.length
        val minChunkLen = 6

        for (i in 0 until len) {
            val c = text[i]

            // Major sentence terminators
            if (c == '.' || c == '?' || c == '!' || c == '\n') {
                if (i < minChunkLen - 1) continue

                // Check for ellipsis (...)
                if (c == '.' && ((i + 1 < len && text[i + 1] == '.') || (i > 0 && text[i - 1] == '.'))) {
                    continue
                }

                // Check for decimal numbers (e.g. 3.14)
                if (c == '.' && i > 0 && i + 1 < len && text[i - 1].isDigit() && text[i + 1].isDigit()) {
                    continue
                }

                // Check for abbreviations (e.g. Dr., Mr., vs.)
                if (c == '.' && isPrecededByAbbreviation(text, i)) {
                    continue
                }

                // Check if followed by space or end of text (with adequate buffer)
                if (i + 1 < len && text[i + 1].isWhitespace()) {
                    return i + 1
                } else if (i + 1 == len && len >= minChunkLen) {
                    return i + 1
                }
            }

            // Natural secondary clause boundary for long sentences (>= 38 chars)
            if ((c == ',' || c == ';' || c == ':' || c == '—') && i >= 38) {
                if (i + 1 < len && text[i + 1].isWhitespace()) {
                    // Check that there's at least a few words in this clause
                    val clause = text.substring(0, i)
                    if (clause.count { it.isWhitespace() } >= 5) {
                        return i + 1
                    }
                }
            }
        }

        // Safety fallback: If buffer exceeds 110 characters without punctuation,
        // split at the last space after index 35 so speech starts without unbounded delay.
        if (len > 110) {
            val lastSpace = text.lastIndexOf(' ')
            if (lastSpace >= 35) {
                return lastSpace + 1
            }
        }

        return -1
    }

    private fun isPrecededByAbbreviation(text: String, dotIndex: Int): Boolean {
        var start = dotIndex - 1
        while (start >= 0 && text[start].isLetter()) {
            start--
        }
        val word = text.substring(start + 1, dotIndex).lowercase()
        return word in ABBREVIATIONS
    }
}
