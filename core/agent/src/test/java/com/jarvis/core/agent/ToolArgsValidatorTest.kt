package com.jarvis.core.agent

import com.jarvis.core.agent.ToolArgsValidator.Result.Rejected
import com.jarvis.core.agent.ToolArgsValidator.Result.Valid
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ToolArgsValidatorTest {

    private val validator = ToolArgsValidator()
    private val schema = """{"type":"object","properties":{"level":{"type":"integer"},"note":{"type":"string"}},"required":["level"]}"""

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
    fun `malformed args json is rejected`() {
        assertTrue(validator.validate(schema, """{"level": 1,""") is Rejected)
    }

    @Test
    fun `schema with no constraints accepts anything parseable`() {
        assertTrue(validator.validate("""{}""", """{"whatever": true}""") is Valid)
    }
}
