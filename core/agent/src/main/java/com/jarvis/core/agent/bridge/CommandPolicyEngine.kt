package com.jarvis.core.agent.bridge

/**
 * Classification level for command execution safety.
 */
enum class PolicyClassification {
    /** Strictly blocked — dangerous destructive command, cannot be executed even with confirmation. */
    BLOCKED,

    /** High risk — requires explicit double-confirmation with full verbatim command preview. */
    DOUBLE_CONFIRM,

    /** Standard elevated operation — requires single user confirmation. */
    SENSITIVE,

    /** Safe read-only inspection command. */
    ALLOWED,
}

data class PolicyEvaluation(
    val classification: PolicyClassification,
    val reason: String,
    val matchedRule: String? = null,
)

/**
 * Pure, deterministic safety policy engine for shell commands and privileged operations.
 *
 * Evaluation is performed on the ENTIRE shell expression, never on a fragment of it.
 * A command string can only ever reach ALLOWED / DOUBLE_CONFIRM when it parses as a
 * single simple command under [ShellCommandGrammar] — any shell composition mechanism
 * (operators, substitution, redirection, quoting, escaping) makes the expression
 * non-evaluable, and it is then rejected or escalated rather than trusted.
 */
class CommandPolicyEngine(
    private val customDenylistRegexes: List<Regex> = emptyList(),
) {
    private val staticBlockedRules: List<Pair<Regex, String>> = listOf(
        Regex("""rm\s+(-r[fF]?|-f)\s+(/(?=[\s;|&)'"`]|$)|/\*|\*|\.\./\.\.|/system.*|/data.*)""", RegexOption.IGNORE_CASE) to "Destructive filesystem root/system deletion is permanently blocked.",
        Regex("""mkfs.*""", RegexOption.IGNORE_CASE) to "Filesystem format command is permanently blocked.",
        Regex("""dd\s+if=.*of=(/dev/.*|/dev/block/.*)""", RegexOption.IGNORE_CASE) to "Direct block device write is permanently blocked.",
        Regex("""reboot(\s+(recovery|bootloader|fastboot))?""", RegexOption.IGNORE_CASE) to "Device reboot/recovery manipulation is blocked.",
        Regex("""wipe\s+data""", RegexOption.IGNORE_CASE) to "Device factory wipe is permanently blocked.",
        Regex("""flash_image.*""", RegexOption.IGNORE_CASE) to "Firmware flashing is permanently blocked.",
        Regex("""pm\s+uninstall\s+(com\.android\..*|com\.google\.android\..*)""", RegexOption.IGNORE_CASE) to "Uninstalling critical system Android packages is permanently blocked.",
        Regex("""settings\s+put\s+global\s+device_provisioned\s+0""", RegexOption.IGNORE_CASE) to "Un-provisioning the device is permanently blocked.",
    )

    private val doubleConfirmRules: List<Pair<Regex, String>> = listOf(
        Regex("""pm\s+(disable|clear|hide).*""", RegexOption.IGNORE_CASE) to "Disabling or clearing application data may cause irreversible app state loss.",
        Regex("""rm\s+-r[fF]?\s+.*""", RegexOption.IGNORE_CASE) to "Recursive file removal requires explicit double-confirmation.",
        Regex("""settings\s+put\s+.*""", RegexOption.IGNORE_CASE) to "Modifying secure/global system settings requires double-confirmation.",
        Regex("""setprop\s+.*""", RegexOption.IGNORE_CASE) to "Modifying system properties requires double-confirmation.",
        Regex("""kill\s+-9\s+.*""", RegexOption.IGNORE_CASE) to "Force-killing processes requires double-confirmation.",
        Regex("""am\s+force-stop.*""", RegexOption.IGNORE_CASE) to "Force-stopping target applications requires double-confirmation.",
    )

    /**
     * Safe read-only rules are matched against the command's head word and validated
     * arguments only — never via substring or raw-prefix matching. Every rule must
     * additionally satisfy [isPermittedSafeArgument] for every argument.
     */
    private val safeReadRules: List<SafeReadRule> = listOf(
        SafeReadRule(
            head = "dumpsys",
            description = "Service state dump is read-only.",
            argumentValidator = { arg -> isPermittedSafeArgument(arg) },
        ),
        SafeReadRule(
            head = "getprop",
            description = "System property read is read-only.",
            argumentValidator = { arg -> isPermittedSafeArgument(arg) },
        ),
        SafeReadRule(
            head = "pm",
            argumentValidator = { arg -> isPermittedSafeArgument(arg) },
            argumentStructureValidator = { args ->
                args.firstOrNull()?.lowercase() == "list" && args.size >= 2
            },
            description = "Package listing is read-only.",
        ),
        SafeReadRule(
            head = "settings",
            argumentValidator = { arg -> isPermittedSafeArgument(arg) },
            argumentStructureValidator = { args ->
                val op = args.firstOrNull()?.lowercase()
                val ns = args.getOrNull(1)?.lowercase()
                (op == "get" || op == "list") && (ns == "system" || ns == "global" || ns == "secure" || ns == "config")
            },
            description = "Reading settings namespaces is read-only.",
        ),
        SafeReadRule(
            head = "uptime",
            description = "Uptime report is read-only.",
            argumentValidator = { _ -> true },
        ),
        SafeReadRule(
            head = "df",
            description = "Disk usage report is read-only.",
            argumentValidator = { arg -> isPermittedSafeArgument(arg) },
        ),
        SafeReadRule(
            head = "ls",
            description = "Directory listing is read-only.",
            argumentValidator = { arg -> isPermittedSafeArgument(arg) },
        ),
        SafeReadRule(
            head = "cat",
            description = "Reading /proc pseudo-files is read-only.",
            argumentValidator = { arg -> isPermittedSafeArgument(arg) },
            argumentStructureValidator = { args ->
                args.isNotEmpty() && args.all { it.lowercase().startsWith("/proc/") }
            },
        ),
    )

    /**
     * Evaluates the safety level of a shell command.
     *
     * The whole expression is parsed first. Anything that is not a single simple
     * command (operators, substitution, redirection, quoting, escaping) can never
     * be classified ALLOWED or DOUBLE_CONFIRM: blocked-rule matching still applies
     * to the raw string as defense in depth, and otherwise the command falls back
     * to the default SENSITIVE confirmation tier.
     */
    fun evaluate(command: String): PolicyEvaluation {
        val trimmed = command.trim()

        if (trimmed.isEmpty()) {
            return PolicyEvaluation(
                classification = PolicyClassification.BLOCKED,
                reason = "Command string cannot be empty.",
            )
        }

        // 1. Check custom denylist (raw string — best-effort catch-all)
        for (regex in customDenylistRegexes) {
            if (regex.containsMatchIn(trimmed)) {
                return PolicyEvaluation(
                    classification = PolicyClassification.BLOCKED,
                    reason = "Command matches custom user denylist rule: ${regex.pattern}",
                    matchedRule = regex.pattern,
                )
            }
        }

        // 2. Parse the WHOLE expression as a single simple command.
        val parsed = ShellCommandGrammar.parse(trimmed)

        // 3. Check static permanent blocked denylist (raw string, composition-aware
        //    defensive pass so that embedded destructive fragments are still caught).
        for ((regex, reason) in staticBlockedRules) {
            if (regex.containsMatchIn(trimmed)) {
                return PolicyEvaluation(
                    classification = PolicyClassification.BLOCKED,
                    reason = reason,
                    matchedRule = regex.pattern,
                )
            }
        }

        // 4. Check double confirmation rules (same composition-aware pass).
        for ((regex, reason) in doubleConfirmRules) {
            if (regex.containsMatchIn(trimmed)) {
                return PolicyEvaluation(
                    classification = PolicyClassification.DOUBLE_CONFIRM,
                    reason = reason,
                    matchedRule = regex.pattern,
                )
            }
        }

        // 5. Safe read-only classification requires a fully parsed simple command.
        //    Non-evaluable expressions (chaining, pipes, redirection, substitution,
        //    quoting, escaping, env-prefix assignments) never reach this point and
        //    therefore can never be classified as safe.
        if (parsed != null) {
            for (rule in safeReadRules) {
                if (rule.match(parsed)) {
                    return PolicyEvaluation(
                        classification = PolicyClassification.ALLOWED,
                        reason = "Safe read-only inspection command: ${rule.description}",
                        matchedRule = rule.head,
                    )
                }
            }
        }

        // 6. Default elevated command -> Single confirmation SENSITIVE
        return PolicyEvaluation(
            classification = PolicyClassification.SENSITIVE,
            reason = "Standard elevated command requires user authorization.",
        )
    }

    /**
     * Invariant: Background routines are strictly forbidden from executing shell commands
     * or non-read-only bridge operations.
     */
    fun isAllowedInBackground(op: TypedOp): Boolean {
        if (op is TypedOp.ShellCommand) {
            val eval = evaluate(op.command)
            return eval.classification == PolicyClassification.ALLOWED
        }
        return op.isReadOnly
    }

    private data class SafeReadRule(
        val head: String,
        val argumentValidator: (String) -> Boolean,
        val argumentStructureValidator: ((List<String>) -> Boolean)? = null,
        val description: String = "read-only inspection",
    ) {
        /** Matches a fully parsed simple command against this rule. */
        fun match(parsed: ShellCommandGrammar.ParsedCommand): Boolean {
            if (!parsed.executable.equals(head, ignoreCase = true)) return false
            if (parsed.arguments.any { !argumentValidator(it) }) return false
            val structure = argumentStructureValidator ?: return true
            return structure(parsed.arguments)
        }
    }

    companion object {
        /**
         * A conservative argument whitelist for safe read-only rules.
         * Arguments may only contain word characters, dots, dashes, slashes,
         * colons, commas, `@`, `+`, `=` and parentheses-free plain text.
         * Anything shell-flavored here is already rejected upstream by the
         * grammar (quoting, `$`, backticks, operators, globs), so this second
         * gate is defense in depth for argument payloads themselves.
         */
        private val permittedArgumentChars = Regex("""^[A-Za-z0-9._+@=:/,\[\]_-]+$""")

        fun isPermittedSafeArgument(arg: String): Boolean =
            permittedArgumentChars.matches(arg)
    }
}
