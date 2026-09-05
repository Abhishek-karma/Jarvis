package com.jarvis.feature.chat

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class MarkdownParserTest {
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
    fun `all bullet marker shapes parse without crashing`() {
        // Regression: `*` / `+` markers with wide spacing previously hit a `!!` NPE when
        // the line-start regex and the item-extraction regex disagreed on the match.
        val blocks = parseMarkdown("*   star wide\n+ plus tight\n-  dash")
        assertEquals(1, blocks.size)
        val list = blocks[0] as MdBlock.BulletList
        assertEquals(3, list.items.size)
        assertEquals(listOf(MdSpan("star wide")), list.items[0])
        assertEquals(listOf(MdSpan("plus tight")), list.items[1])
        assertEquals(listOf(MdSpan("dash")), list.items[2])
    }

    @Test
    fun `indented numbered list items keep their text`() {
        val blocks = parseMarkdown("1.   first\n2. second")
        assertEquals(1, blocks.size)
        val list = blocks[0] as MdBlock.NumberedList
        assertEquals(2, list.items.size)
        assertEquals(listOf(MdSpan("first")), list.items[0])
        assertEquals(listOf(MdSpan("second")), list.items[1])
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

    @Nested
    inner class Tables {
        @Test
        fun `basic table parses header and rows`() {
            val blocks =
                parseMarkdown(
                    "| Name | Age |\n" +
                        "| --- | --- |\n" +
                        "| Alice | 30 |\n" +
                        "| Bob | 25 |",
                )

            assertEquals(1, blocks.size)
            val table = blocks[0] as MdBlock.TableBlock
            assertEquals(2, table.columnCount)
            assertEquals(
                listOf(listOf(MdSpan("Name")), listOf(MdSpan("Age"))),
                table.header,
            )
            assertEquals(2, table.rows.size)
            assertEquals(
                listOf(listOf(MdSpan("Alice")), listOf(MdSpan("30"))),
                table.rows[0],
            )
            assertEquals(
                listOf(listOf(MdSpan("Bob")), listOf(MdSpan("25"))),
                table.rows[1],
            )
        }

        @Test
        fun `table with alignment colons parses`() {
            val blocks =
                parseMarkdown(
                    "| Left | Center | Right |\n" +
                        "| --- | :---: | ---: |\n" +
                        "| a | b | c |",
                )

            val table = blocks[0] as MdBlock.TableBlock
            assertEquals(3, table.columnCount)
            assertEquals(
                listOf(
                    listOf(MdSpan("Left")),
                    listOf(MdSpan("Center")),
                    listOf(MdSpan("Right")),
                ),
                table.header,
            )
            assertEquals(1, table.rows.size)
        }

        @Test
        fun `table without leading or trailing pipes parses`() {
            val blocks =
                parseMarkdown(
                    "H1 | H2\n" +
                        "--- | ---\n" +
                        "a | b",
                )

            // GFM allows the pipe-less form; our subset requires the leading pipe, so this
            // must NOT be read as a table (it stays a paragraph) — documented limitation.
            assertFalse(blocks[0] is MdBlock.TableBlock)
        }

        @Test
        fun `inline styles inside cells are preserved`() {
            val blocks =
                parseMarkdown(
                    "| Item | Status |\n" +
                        "| --- | --- |\n" +
                        "| **Widget** | `ok` |",
                )

            val table = blocks[0] as MdBlock.TableBlock
            val row = table.rows.single()
            assertTrue(row[0].single().bold)
            assertTrue(row[1].single().code)
        }

        @Test
        fun `table interrupts an open paragraph`() {
            val blocks =
                parseMarkdown(
                    "Here is the data:\n" +
                        "| k | v |\n" +
                        "| --- | --- |\n" +
                        "| a | 1 |",
                )

            assertEquals(2, blocks.size)
            assertTrue(blocks[0] is MdBlock.Paragraph)
            assertTrue(blocks[1] is MdBlock.TableBlock)
            val para = blocks[0] as MdBlock.Paragraph
            assertEquals("Here is the data:", para.spans.single().text)
        }

        @Test
        fun `delimiter row alone is not a table`() {
            // A delimiter-looking line without a header row stays a paragraph/divider path.
            val blocks = parseMarkdown("| --- | --- |")
            assertFalse(blocks[0] is MdBlock.TableBlock)
        }

        @Test
        fun `short rows keep the table shape via columnCount`() {
            val blocks =
                parseMarkdown(
                    "| A | B | C |\n" +
                        "| --- | --- | --- |\n" +
                        "| 1 | 2 |",
                )

            val table = blocks[0] as MdBlock.TableBlock
            assertEquals(3, table.columnCount)
            assertEquals(2, table.rows.single().size)
        }

        @Test
        fun `table followed by a paragraph splits cleanly`() {
            val blocks =
                parseMarkdown(
                    "| a | b |\n" +
                        "| --- | --- |\n" +
                        "| 1 | 2 |\n" +
                        "\n" +
                        "Trailing prose.",
                )

            assertEquals(2, blocks.size)
            assertTrue(blocks[0] is MdBlock.TableBlock)
            assertTrue(blocks[1] is MdBlock.Paragraph)
        }
    }
}
