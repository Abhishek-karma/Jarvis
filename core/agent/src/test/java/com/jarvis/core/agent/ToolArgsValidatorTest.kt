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
    fun `string rejecting number`() {
        val strSchema = """{"type":"object","properties":{"val":{"type":"string"}},"required":["val"]}"""
        assertTrue(validator.validate(strSchema, """{"val": 123}""") is Rejected)
    }

    @Test
    fun `string rejecting boolean`() {
        val strSchema = """{"type":"object","properties":{"val":{"type":"string"}},"required":["val"]}"""
        assertTrue(validator.validate(strSchema, """{"val": true}""") is Rejected)
    }

    @Test
    fun `string accepts string value`() {
        val strSchema = """{"type":"object","properties":{"val":{"type":"string"}},"required":["val"]}"""
        assertTrue(validator.validate(strSchema, """{"val": "123"}""") is Valid)
    }

    @Test
    fun `boolean rejecting string`() {
        val boolSchema = """{"type":"object","properties":{"flag":{"type":"boolean"}},"required":["flag"]}"""
        assertTrue(validator.validate(boolSchema, """{"flag": "true"}""") is Rejected)
        assertTrue(validator.validate(boolSchema, """{"flag": "yes"}""") is Rejected)
        assertTrue(validator.validate(boolSchema, """{"flag": "1"}""") is Rejected)
        assertTrue(validator.validate(boolSchema, """{"flag": 1}""") is Rejected)
        assertTrue(validator.validate(boolSchema, """{"flag": true}""") is Valid)
        assertTrue(validator.validate(boolSchema, """{"flag": false}""") is Valid)
    }

    @Test
    fun `number rejecting string`() {
        val numSchema = """{"type":"object","properties":{"num":{"type":"number"}},"required":["num"]}"""
        assertTrue(validator.validate(numSchema, """{"num": "12.5"}""") is Rejected)
        assertTrue(validator.validate(numSchema, """{"num": "42"}""") is Rejected)
        assertTrue(validator.validate(numSchema, """{"num": 12.5}""") is Valid)
        assertTrue(validator.validate(numSchema, """{"num": 42}""") is Valid)
    }

    @Test
    fun `integer rejecting decimal`() {
        val intSchema = """{"type":"object","properties":{"count":{"type":"integer"}},"required":["count"]}"""
        assertTrue(validator.validate(intSchema, """{"count": 12.5}""") is Rejected)
        assertTrue(validator.validate(intSchema, """{"count": "42"}""") is Rejected)
        assertTrue(validator.validate(intSchema, """{"count": 42}""") is Valid)
    }

    @Test
    fun `enum accepted`() {
        val enumSchema = """{"type":"object","properties":{"status":{"type":"string","enum":["active","pending"]}},"required":["status"]}"""
        assertTrue(validator.validate(enumSchema, """{"status": "active"}""") is Valid)
        assertTrue(validator.validate(enumSchema, """{"status": "pending"}""") is Valid)
    }

    @Test
    fun `enum rejected`() {
        val enumSchema = """{"type":"object","properties":{"status":{"type":"string","enum":["active","pending"]}},"required":["status"]}"""
        assertTrue(validator.validate(enumSchema, """{"status": "archived"}""") is Rejected)
        assertTrue(validator.validate(enumSchema, """{"status": 123}""") is Rejected)
    }

    @Test
    fun `malformed args json is rejected`() {
        assertTrue(validator.validate(schema, """{"level": 1,""") is Rejected)
        assertTrue(validator.validate(schema, """not a json""") is Rejected)
        assertTrue(validator.validate(schema, """[1, 2, 3]""") is Rejected)
    }

    @Test
    fun `schema with no constraints accepts anything parseable`() {
        assertTrue(validator.validate("""{}""", """{"whatever": true}""") is Valid)
    }

    @Test
    fun `malformed schema json is rejected`() {
        assertTrue(validator.validate("""{"type": "object", broken}""", """{"level": 1}""") is Rejected)
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
            validator.validate(schema, """{"path":"welcome.txt","content":"welcome"}""") is Valid,
        )
        assertTrue(
            validator.validate(schema, """{"content":"welcome"}""") is Rejected,
        )
    }

    @Test
    fun `camelCase and snake_case compatibility works bidirectionally`() {
        val snakeSchema =
            """{"type":"object","properties":{"duration_seconds":{"type":"integer"}},"required":["duration_seconds"]}"""
        assertTrue(validator.validate(snakeSchema, """{"durationSeconds": 60}""") is Valid)

        val camelSchema =
            """{"type":"object","properties":{"durationSeconds":{"type":"integer"}},"required":["durationSeconds"]}"""
        assertTrue(validator.validate(camelSchema, """{"duration_seconds": 60}""") is Valid)
    }
}
