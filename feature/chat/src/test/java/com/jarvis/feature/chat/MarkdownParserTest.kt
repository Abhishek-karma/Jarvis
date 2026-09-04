package com.jarvis.feature.chat

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MarkdownParserTest {

    // ── Block parsing ──────────────────────────────────────────────────

    @Test
    fun `plain paragraph produces a single paragraph block`() {
        val blocks = parseMarkdown("Hello world")
        assertEquals(1, blocks.size)
        assertTrue(blocks[0] is MdBlock.Paragraph)
        val para = blocks[0] as MdBlock.Paragraph
        assertEquals(listOf(MdSpan("Hello world")), para.spans)
    }

    @Test
    fun `headings are parsed with level`() {
        val blocks = parseMarkdown("# Title\n## Sub\n### Subsub")
        assertEquals(3, blocks.size)
        assertEquals(MdBlock.Heading(1, listOf(MdSpan("Title"))), blocks[0])
        assertEquals(MdBlock.Heading(2, listOf(MdSpan("Sub"))), blocks[1])
        assertEquals(MdBlock.Heading(3, listOf(MdSpan("Subsub"))), blocks[2])
    }

    @Test
    fun `fenced code block captures language and code`() {
        val blocks = parseMarkdown("```kotlin\nval x = 1\n```")
        assertEquals(1, blocks.size)
        val code = blocks[0] as MdBlock.CodeBlock
        assertEquals("kotlin", code.language)
        assertEquals("val x = 1", code.code)
    }

    @Test
    fun `multi-line fenced code preserves newlines`() {
        val blocks = parseMarkdown("```\nline1\nline2\n```")
        val code = blocks[0] as MdBlock.CodeBlock
        assertEquals("line1\nline2", code.code)
    }

    @Test
    fun `bullet list parses items`() {
        val blocks = parseMarkdown("- one\n- two\n- three")
        assertEquals(1, blocks.size)
        val list = blocks[0] as MdBlock.BulletList
        assertEquals(3, list.items.size)
        assertEquals(listOf(MdSpan("one")), list.items[0])
    }

    @Test
    fun `numbered list parses items`() {
        val blocks = parseMarkdown("1. first\n2. second")
        assertEquals(1, blocks.size)
        val list = blocks[0] as MdBlock.NumberedList
        assertEquals(2, list.items.size)
        assertEquals(listOf(MdSpan("first")), list.items[0])
    }

    @Test
    fun `blockquote is parsed`() {
        val blocks = parseMarkdown("> quoted text")
        assertEquals(1, blocks.size)
        val quote = blocks[0] as MdBlock.Quote
        assertEquals(listOf(MdSpan("quoted text")), quote.spans)
    }

    @Test
    fun `thematic break produces divider`() {
        val blocks = parseMarkdown("---")
        assertEquals(1, blocks.size)
        assertTrue(blocks[0] is MdBlock.Divider)
    }

    @Test
    fun `paragraph spanning multiple lines joins with space`() {
        val blocks = parseMarkdown("first line\nsecond line")
        val para = blocks[0] as MdBlock.Paragraph
        assertEquals(listOf(MdSpan("first line second line")), para.spans)
    }

    @Test
    fun `blank lines separate paragraphs`() {
        val blocks = parseMarkdown("para one\n\npara two")
        assertEquals(2, blocks.size)
        assertEquals(MdBlock.Paragraph(listOf(MdSpan("para one"))), blocks[0])
        assertEquals(MdBlock.Paragraph(listOf(MdSpan("para two"))), blocks[1])
    }

    // ── Inline parsing ─────────────────────────────────────────────────

    @Test
    fun `bold span is parsed`() {
        val spans = parseInline("a **bold** b")
        assertEquals(
            listOf(MdSpan("a "), MdSpan("bold", bold = true), MdSpan(" b")),
            spans,
        )
    }

    @Test
    fun `italic span is parsed`() {
        val spans = parseInline("a *italic* b")
        assertEquals(
            listOf(MdSpan("a "), MdSpan("italic", italic = true), MdSpan(" b")),
            spans,
        )
    }

    @Test
    fun `inline code is parsed`() {
        val spans = parseInline("run `gradle build` now")
        assertEquals(
            listOf(MdSpan("run "), MdSpan("gradle build", code = true), MdSpan(" now")),
            spans,
        )
    }

    @Test
    fun `link is parsed with url`() {
        val spans = parseInline("see [docs](https://example.com) here")
        assertEquals(
            listOf(
                MdSpan("see "),
                MdSpan("docs", url = "https://example.com"),
                MdSpan(" here"),
            ),
            spans,
        )
    }

    @Test
    fun `unmatched marker is treated as literal text`() {
        val spans = parseInline("no *closing here")
        assertEquals(listOf(MdSpan("no *closing here")), spans)
    }

    @Test
    fun `empty input yields no spans`() {
        assertTrue(parseInline("").isEmpty())
    }

    @Test
    fun `nested markers inside bold are flattened to bold text`() {
        val spans = parseInline("**bold `code` inside**")
        // `code` is consumed as part of the bold segment (no inner span splitting)
        assertEquals(1, spans.size)
        assertTrue(spans[0].bold)
    }

    @Test
    fun `url span is only set when a link is present`() {
        assertNull(MdSpan("plain").url)
    }
}
