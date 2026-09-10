package com.jarvis.core.agent.tools

import com.jarvis.core.agent.Tool
import com.jarvis.core.agent.ToolResult
import com.jarvis.core.common.PermissionTier

object CalculatorTool {
    const val NAME = "calculator"

    private const val SCHEMA = """{
  "type": "object",
  "properties": {
    "expression": {"type": "string", "description": "The mathematical expression to evaluate, e.g. '24 * 7', '(150 / 3) + 45', '2 ^ 8'."}
  },
  "required": ["expression"]
}"""

    fun create(): Tool = object : Tool {
        override val name = NAME
        override val description = "Evaluates basic mathematical expressions (+, -, *, /, %, ^, parentheses). Read-only."
        override val parametersSchemaJson = SCHEMA
        override val tier = PermissionTier.READ_ONLY

        override suspend fun execute(argsJson: String): ToolResult {
            val args = Args.parse(argsJson)
                ?: return ToolResult(success = false, observationText = "Invalid arguments", error = "Malformed JSON")
            val expr = args.string("expression")
                ?: return ToolResult(success = false, observationText = "Missing 'expression'", error = "expression required")

            return runCatching {
                val result = evaluateExpression(expr)
                val formatted = if (result % 1.0 == 0.0) result.toLong().toString() else result.toString()
                ToolResult(
                    success = true,
                    observationText = "$expr = $formatted",
                    structuredData = mapOf("expression" to expr, "result" to result),
                )
            }.getOrElse { err ->
                ToolResult(
                    success = false,
                    observationText = "Failed to evaluate '$expr': ${err.message}",
                    error = err.message,
                )
            }
        }
    }

    fun evaluateExpression(str: String): Double {
        return Parser(str).parse()
    }

    private class Parser(private val str: String) {
        private var pos = -1
        private var ch = 0

        private fun nextChar() {
            ch = if (++pos < str.length) str[pos].code else -1
        }

        private fun eat(charToEat: Int): Boolean {
            while (ch == ' '.code) nextChar()
            if (ch == charToEat) {
                nextChar()
                return true
            }
            return false
        }

        fun parse(): Double {
            nextChar()
            val x = parseExpression()
            while (ch == ' '.code) nextChar()
            if (pos < str.length) error("Unexpected character: ${ch.toChar()}")
            return x
        }

        private fun parseExpression(): Double = parseTerm()

        private fun parseTerm(): Double {
            var x = parseFactor()
            while (true) {
                when {
                    eat('+'.code) -> x += parseFactor()
                    eat('-'.code) -> x -= parseFactor()
                    else -> return x
                }
            }
        }

        private fun parseFactor(): Double {
            var x = parsePower()
            while (true) {
                when {
                    eat('*'.code) -> x *= parsePower()
                    eat('/'.code) -> {
                        val divisor = parsePower()
                        if (divisor == 0.0) error("Division by zero")
                        x /= divisor
                    }
                    eat('%'.code) -> {
                        val divisor = parsePower()
                        if (divisor == 0.0) error("Modulo by zero")
                        x %= divisor
                    }
                    else -> return x
                }
            }
        }

        private fun parsePower(): Double {
            var x = parseBase()
            if (eat('^'.code)) {
                val exponent = parseBase()
                x = Math.pow(x, exponent)
            }
            return x
        }

        private fun parseBase(): Double {
            while (ch == ' '.code) nextChar()
            if (eat('+'.code)) return parseBase()
            if (eat('-'.code)) return -parseBase()

            var x: Double
            val startPos = pos
            if (eat('('.code)) {
                x = parseExpression()
                if (!eat(')'.code)) error("Missing closing parenthesis")
            } else if ((ch in '0'.code..'9'.code) || ch == '.'.code) {
                while ((ch in '0'.code..'9'.code) || ch == '.'.code) nextChar()
                x = str.substring(startPos, pos).toDouble()
            } else {
                error("Unexpected character: ${if (ch == -1) "end of input" else ch.toChar().toString()}")
            }
            return x
        }
    }
}
