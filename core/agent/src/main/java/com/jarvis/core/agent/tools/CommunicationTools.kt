package com.jarvis.core.agent.tools

import com.jarvis.core.agent.Tool
import com.jarvis.core.agent.ToolResult
import com.jarvis.core.common.PermissionTier


object CommunicationTools {
    const val SEND_SMS = "send_sms"
    const val PLACE_CALL = "place_call"

    fun all(
        sendSms: suspend (to: String, body: String) -> Result<Unit>,
        placeCall: suspend (number: String) -> Result<Unit>,
    ): List<Tool> =
        listOf(
            sendSms(sendSms),
            placeCall(placeCall),
        )

    fun sendSms(send: suspend (String, String) -> Result<Unit>): Tool =
        object : Tool {
            override val name = SEND_SMS
            override val description =
                "Send a text message to a phone number. Sensitive: sends on the user's behalf " +
                    "and always requires explicit confirmation. When sending an SMS, invoke this tool directly; " +
                    "the system automatically prompts the user for confirmation."
            override val tier = PermissionTier.SENSITIVE
            override val parametersSchemaJson = SEND_SMS_SCHEMA

            override suspend fun execute(argsJson: String): ToolResult {
                val args = Args.parse(argsJson)
                if (args == null) {
                    return ToolResult(
                        success = false,
                        observationText = "Arguments are not valid JSON.",
                        error = "invalid JSON arguments",
                    )
                }
                val to = args.string("to")?.trim()
                    ?: args.string("number")?.trim()
                    ?: args.string("phone")?.trim()
                val body = args.string("body")?.trim() ?: args.string("message")?.trim()
                if (to.isNullOrEmpty() || body.isNullOrEmpty()) {
                    return ToolResult(
                        success = false,
                        observationText = "Missing argument: to and body are required.",
                        error = "to and body are required",
                    )
                }
                if (body.length > MAX_SMS_CHARS) {
                    return ToolResult(
                        success = false,
                        observationText = "Message exceeds $MAX_SMS_CHARS characters; shorten it.",
                        error = "body too long",
                    )
                }
                return send(to, body).fold(
                    onSuccess = {
                        ToolResult(
                            success = true,
                            observationText = "Text message sent to $to.",
                            structuredData = mapOf("to" to to, "chars" to body.length),
                        )
                    },
                    onFailure = { error ->
                        ToolResult(
                            success = false,
                            observationText = "Could not send the text message.",
                            error = error.message ?: "SMS send failed",
                        )
                    },
                )
            }
        }

    fun placeCall(place: suspend (String) -> Result<Unit>): Tool =
        object : Tool {
            override val name = PLACE_CALL
            override val description =
                "Place a phone call to a number. Sensitive: dials on the user's behalf and always " +
                    "requires explicit confirmation. When placing a call, invoke this tool directly; " +
                    "the system automatically prompts the user for confirmation."
            override val tier = PermissionTier.SENSITIVE
            override val parametersSchemaJson = PLACE_CALL_SCHEMA

            override suspend fun execute(argsJson: String): ToolResult {
                val args = Args.parse(argsJson)
                if (args == null) {
                    return ToolResult(
                        success = false,
                        observationText = "Arguments are not valid JSON.",
                        error = "invalid JSON arguments",
                    )
                }
                val number = args.string("number")?.trim()
                    ?: args.string("phone")?.trim()
                    ?: args.string("phone_number")?.trim()
                    ?: args.string("to")?.trim()
                if (number.isNullOrEmpty()) {
                    return ToolResult(
                        success = false,
                        observationText = "Missing argument: number is required.",
                        error = "number is required",
                    )
                }
                val name = args.string("name")?.trim() ?: args.string("recipient")?.trim()
                return place(number).fold(
                    onSuccess = {
                        val targetLabel = if (!name.isNullOrBlank()) "$name ($number)" else number
                        ToolResult(
                            success = true,
                            observationText = "Dialing $targetLabel.",
                            structuredData = mapOf("number" to number),
                        )
                    },
                    onFailure = { error ->
                        ToolResult(
                            success = false,
                            observationText = "Could not place the call.",
                            error = error.message ?: "Call failed",
                        )
                    },
                )
            }
        }

    internal const val MAX_SMS_CHARS = 1600

    private const val SEND_SMS_SCHEMA =
        """{"type":"object","properties":{"to":{"type":"string"},"body":{"type":"string"},"name":{"type":"string"},"recipient":{"type":"string"}},"required":["to","body"]}"""
    private const val PLACE_CALL_SCHEMA =
        """{"type":"object","properties":{"number":{"type":"string"},"name":{"type":"string"},"recipient":{"type":"string"}},"required":["number"]}"""
}
