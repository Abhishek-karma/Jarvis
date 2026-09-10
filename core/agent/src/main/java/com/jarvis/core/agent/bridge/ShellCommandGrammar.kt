package com.jarvis.core.agent.bridge

/**
 * Strict grammar for policy-evaluable shell commands.
 *
 * The privileged Shizuku service ultimately runs every shell string through
 * `sh -c <command>`. Policy must therefore reason about the ENTIRE expression,
 * never about a safe-looking fragment inside it. A command is only evaluable
 * when it parses as a single simple command:
 *
 *   command = word (word)*          (no operators, no substitution, no redirection)
 *   word    = an unquoted, unescaped token
 *
 * Anything that could alter execution semantics causes the WHOLE string to be
 * rejected as not policy-evaluable:
 * - control operators:   `;` `;;` `&` `&&` `||` `|` `;;&` newlines
 * - redirection:         `<` `>` `>>` `<<` `<<-` `<<<` `&>` `>&` `>|` `<>`
 * - parameter expansion: `$var`, `${...}`, `$(...)`, `$((...))`
 * - command substitution: `$(...)`, backticks
 * - quoting and backslash escaping (all forms)
 * - comments, tilde/brace/pathname (glob) expansion: `~` `{...}` `*` `?` `[...]`
 * - compound-command keywords: `if`, `for`, `while`, `case`, `function`, ...
 * - environment-prefix assignments (`FOO=bar cmd`) that could invoke functions
 *
 * No splitting or fragment matching is performed: the whole string is either
 * ONE simple command or it is not policy-evaluable. This removes the entire
 * class of "safe command contained in a larger shell expression" bypasses,
 * because any expression that is not a single simple command can never be
 * classified ALLOWED or DOUBLE_CONFIRM from its text alone.
 */
object ShellCommandGrammar {

    /**
     * Result of parsing a raw command string as a single simple command.
     */
    data class ParsedCommand(
        val executable: String,
        val arguments: List<String>,
    )

    private val controlOperatorChars = setOf(';', '&', '|', '\n', '\r')

    private val redirectionChars = setOf('<', '>')

    private val quoteChars = setOf('"', '\'')

    // Characters that trigger pathname (glob) expansion beyond a literal argument.
    private val globChars = setOf('*', '?', '[')

    // Multi-word shell keywords that start compound commands or list structure.
    private val reservedWords = setOf(
        "if", "then", "elif", "else", "fi", "for", "while", "until", "case",
        "esac", "do", "done", "in", "select", "time", "coproc", "function", "!",
    )

    // Environment-variable prefix assignments (FOO=bar cmd) can redefine or shadow
    // command lookup via shell functions, so they are never policy-evaluable.
    private val envAssignmentPattern = Regex("^[A-Za-z_][A-Za-z0-9_]*=.*$")

    /**
     * Parses [command] as a single simple shell command.
     *
     * @return the parsed command, or `null` when the string is not a
     * policy-evaluable single simple command (operators, substitution,
     * quoting, escaping, redirection, expansions, etc.).
     */
    fun parse(command: String): ParsedCommand? {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.containsAny(controlOperatorChars)) return null
        if (trimmed.containsAny(redirectionChars)) return null
        if (trimmed.contains('$')) return null
        if (trimmed.contains('`')) return null
        if (trimmed.containsAny(quoteChars)) return null
        if (trimmed.contains('\\')) return null
        if (trimmed.contains('#')) return null
        if (trimmed.contains('~')) return null
        if (trimmed.contains('{') || trimmed.contains('}')) return null
        if (trimmed.containsAny(globChars)) return null
        return tokenize(trimmed)
    }

    /**
     * Splits an already-validated simple command on whitespace and enforces
     * word-level grammar (no reserved words, no env assignments).
     */
    private fun tokenize(trimmed: String): ParsedCommand? {
        val tokens = trimmed.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null

        val head = tokens.first()
        if (head.lowercase() in reservedWords) return null
        if (envAssignmentPattern.matches(head)) return null

        return ParsedCommand(executable = head, arguments = tokens.drop(1))
    }

    private fun String.containsAny(chars: Set<Char>): Boolean =
        any { it in chars }
}
