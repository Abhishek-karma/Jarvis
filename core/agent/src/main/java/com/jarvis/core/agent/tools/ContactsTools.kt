package com.jarvis.core.agent.tools

import com.jarvis.core.agent.PermissionTier
import com.jarvis.core.agent.Tool
import com.jarvis.core.agent.ToolResult

/**
 * Contacts tools (v0.5 catalog, `06-AGENT §3`): look up a contact's phone number or email.
 * The ContactsContract reader is injected as a lambda so the tool is JVM-unit-testable;
 * AgentModule binds the real resolver behind it.
 */
object ContactsTools {
    const val LOOKUP_CONTACT = "lookup_contact"

    val manifestNames: List<String> = listOf(LOOKUP_CONTACT)

    fun all(
        lookup: suspend (name: String) -> Result<List<ContactMatch>>,
    ): List<Tool> = listOf(lookupContact(lookup))

    /** One contact hit. At most one of phone/email may be null. */
    data class ContactMatch(
        val displayName: String,
        val phone: String? = null,
        val email: String? = null,
    )

    fun lookupContact(lookup: suspend (String) -> Result<List<ContactMatch>>): Tool =
        object : Tool {
            override val name = LOOKUP_CONTACT
            override val description =
                "Look up a contact by name and return their phone number and/or email. Read-only."
            override val tier = PermissionTier.READ_ONLY
            override val parametersSchemaJson = LOOKUP_SCHEMA

            override suspend fun execute(argsJson: String): ToolResult {
                val args = Args.parse(argsJson)
                if (args == null) {
                    return ToolResult(
                        success = false,
                        observationText = "Arguments are not valid JSON.",
                        error = "invalid JSON arguments",
                    )
                }
                val name = args.string("name")
                if (name.isNullOrBlank()) {
                    return ToolResult(
                        success = false,
                        observationText = "Missing argument: name is required.",
                        error = "name is required",
                    )
                }
                return lookup(name.trim()).fold(
                    onSuccess = { matches ->
                        when {
                            matches.isEmpty() ->
                                ToolResult(
                                    success = true,
                                    observationText = "No contact found for \"$name\".",
                                    structuredData = mapOf("count" to 0),
                                )

                            else -> {
                                val lines =
                                    matches.take(MAX_MATCHES).joinToString("\n") { match ->
                                        val channels =
                                            listOfNotNull(
                                                match.phone?.let { "tel $it" },
                                                match.email?.let { "email $it" },
                                            ).joinToString(", ")
                                        "- ${match.displayName}" +
                                            (if (channels.isEmpty()) "" else " — $channels")
                                    }
                                ToolResult(
                                    success = true,
                                    observationText =
                                        "${matches.size} match(es) for \"$name\":\n$lines" +
                                            if (matches.size > MAX_MATCHES) "\n(truncated)" else "",
                                    structuredData = mapOf("count" to matches.size),
                                )
                            }
                        }
                    },
                    onFailure = { error ->
                        ToolResult(
                            success = false,
                            observationText = "Could not read contacts.",
                            error = error.message ?: "Contacts query failed",
                        )
                    },
                )
            }
        }

    internal const val MAX_MATCHES = 5

    private const val LOOKUP_SCHEMA =
        """{"type":"object","properties":{"name":{"type":"string"}},"required":["name"]}"""
}
