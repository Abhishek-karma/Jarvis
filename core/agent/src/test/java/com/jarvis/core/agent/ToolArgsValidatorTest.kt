package com.jarvis.core.agent

import com.jarvis.core.agent.ToolArgsValidator.Result.Rejected
import com.jarvis.core.agent.ToolArgsValidator.Result.Valid
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private const val SCHEMA = """{"type":"object","properties":{"level":{"type":"integer"},"note":{"type":"string"}},"required":["level"]}"""

class ToolArgsValidatorTest {
    private val validator = ToolArgsValidator()
    private val schema = SCHEMA

    @Test
    fun `valid args pass`() {
        assertTrue(validator.validate(schema, """{"level": 1, "note": "hi"}""") is Valid)
    }

    @Test
    fun `missing required argument is rejected and names the key`() {
        val result = validator.validate(schema, """{"note": "hi"}""") as Rejected
        assertTrue(result.reason.contains("level"))
    }

    @Test
    fun `type mismatch is rejected`() {
        assertTrue(validator.validate(schema, """{"level": "one"}""") is Rejected)
    }

    @Test
    fun `numeric string coerced to integer is accepted`() {
        assertTrue(validator.validate(schema, """{"level": "42"}""") is Valid)
    }

    @Test
    fun `boolean string representations are accepted`() {
        val boolSchema = """{"type":"object","properties":{"flag":{"type":"boolean"}},"required":["flag"]}"""
        assertTrue(validator.validate(boolSchema, """{"flag": "true"}""") is Valid)
        assertTrue(validator.validate(boolSchema, """{"flag": "yes"}""") is Valid)
        assertTrue(validator.validate(boolSchema, """{"flag": "1"}""") is Valid)
    }

    @Test
    fun `malformed args json is rejected`() {
        assertTrue(validator.validate(schema, """{"level": 1,""") is Rejected)
    }

    @Test
    fun `schema with no constraints accepts anything parseable`() {
        assertTrue(validator.validate("""{}""", """{"whatever": true}""") is Valid)
    }

    @Test
    fun `file_name accepts filename alias from on-device models`() {
        val schema =
            """{"type":"object","properties":{"file_name":{"type":"string"},"content":{"type":"string"}},"required":["file_name","content"]}"""
        // Observed Gemma output uses `filename` instead of the schema's `file_name`.
        assertTrue(
            validator.validate(schema, """{"filename":"welcome.txt","content":"welcome"}""") is Valid,
        )
        assertTrue(
            validator.validate(schema, """{"name":"welcome.txt","content":"welcome"}""") is Valid,
        )
        assertTrue(
            validator.validate(schema, """{"content":"welcome"}""") is Rejected,
        )
    }
}
