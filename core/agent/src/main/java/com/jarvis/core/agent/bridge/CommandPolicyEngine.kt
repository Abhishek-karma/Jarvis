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
 */
class CommandPolicyEngine(
    private val customDenylistRegexes: List<Regex> = emptyList(),
) {
    private val staticBlockedRules: List<Pair<Regex, String>> = listOf(
        Regex("""rm\s+(-r[fF]?|-f)\s+(/(?=\s|$)|/\*|\*|\.\./\.\.|/system.*|/data.*)""", RegexOption.IGNORE_CASE) to "Destructive filesystem root/system deletion is permanently blocked.",
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

    private val safeReadRules: List<Regex> = listOf(
        Regex("""dumpsys(\s+.*)?""", RegexOption.IGNORE_CASE),
        Regex("""getprop(\s+.*)?""", RegexOption.IGNORE_CASE),
        Regex("""pm\s+list\s+.*""", RegexOption.IGNORE_CASE),
        Regex("""settings\s+get\s+.*""", RegexOption.IGNORE_CASE),
        Regex("""uptime""", RegexOption.IGNORE_CASE),
        Regex("""df(\s+.*)?""", RegexOption.IGNORE_CASE),
        Regex("""ls(\s+.*)?""", RegexOption.IGNORE_CASE),
        Regex("""cat\s+/proc/.*""", RegexOption.IGNORE_CASE),
    )

    /**
     * Evaluates the safety level of a shell command.
     */
    fun evaluate(command: String): PolicyEvaluation {
        val trimmed = command.trim()

        if (trimmed.isEmpty()) {
            return PolicyEvaluation(
                classification = PolicyClassification.BLOCKED,
                reason = "Command string cannot be empty.",
            )
        }

        // 1. Check custom denylist
        for (regex in customDenylistRegexes) {
            if (regex.containsMatchIn(trimmed)) {
                return PolicyEvaluation(
                    classification = PolicyClassification.BLOCKED,
                    reason = "Command matches custom user denylist rule: ${regex.pattern}",
                    matchedRule = regex.pattern,
                )
            }
        }

        // 2. Check static permanent blocked denylist
        for ((regex, reason) in staticBlockedRules) {
            if (regex.containsMatchIn(trimmed)) {
                return PolicyEvaluation(
                    classification = PolicyClassification.BLOCKED,
                    reason = reason,
                    matchedRule = regex.pattern,
                )
            }
        }

        // 3. Check double confirmation rules
        for ((regex, reason) in doubleConfirmRules) {
            if (regex.containsMatchIn(trimmed)) {
                return PolicyEvaluation(
                    classification = PolicyClassification.DOUBLE_CONFIRM,
                    reason = reason,
                    matchedRule = regex.pattern,
                )
            }
        }

        // 4. Check safe read-only rules
        for (regex in safeReadRules) {
            if (regex.matches(trimmed) || regex.containsMatchIn(trimmed)) {
                return PolicyEvaluation(
                    classification = PolicyClassification.ALLOWED,
                    reason = "Safe read-only inspection command.",
                    matchedRule = regex.pattern,
                )
            }
        }

        // 5. Default elevated command -> Single confirmation SENSITIVE
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
}
