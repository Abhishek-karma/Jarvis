package com.jarvis.core.agent.bridge

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Adversarial regression tests for the privileged shell policy boundary.
 *
 * The Shizuku UserService executes shell strings with `sh -c`. The policy engine
 * must therefore reason about the ENTIRE expression. These tests prove that a
 * safe command appearing INSIDE a larger shell expression can never make the
 * whole expression SAFE (ALLOWED), and that legitimate safe commands still work.
 */
class ShellInjectionPolicyTest {

    private val engine = CommandPolicyEngine()

    private val safeCommands = listOf(
        "ls",
        "ls -la /sdcard",
        "dumpsys battery",
        "dumpsys activity",
        "getprop",
        "getprop ro.build.version.release",
        "pm list packages",
        "pm list packages -3",
        "settings get global wifi_on",
        "settings get secure android_id",
        "uptime",
        "df",
        "df -h",
        "cat /proc/uptime",
        "cat /proc/meminfo",
    )

    // A safe command embedded inside (or combined with) shell composition
    // mechanisms. Every one of these MUST NOT be classified as safe.
    private val compositionAttacks = listOf(
        // Command chaining
        "ls && id",
        "ls; id",
        "ls || id",
        "ls & id",
        "ls&",
        "ls ;id",
        "ls && rm -rf /",
        "echo safe; rm -rf /",
        "getprop; rm -rf /",
        "dumpsys battery && pm uninstall com.android.settings",
        "uptime; dd if=/dev/zero of=/dev/block/mmcblk0",
        // Pipes
        "ls | id",
        "dumpsys battery | grep level",
        "cat /proc/uptime | sh",
        "ls | sh",
        // Redirection
        "ls > /tmp/x",
        "ls >> /tmp/x",
        "ls < /etc/passwd",
        "ls > /system/build.prop",
        "uptime > /sdcard/out.txt",
        "ls << EOF",
        "ls <<< input",
        "ls &> /tmp/x",
        // Command substitution
        "ls \$(id)",
        "ls \$(rm -rf /)",
        "getprop \$(reboot)",
        "dumpsys \$(pm uninstall com.android.settings)",
        "ls \$(id | sh)",
        // Backticks
        "ls `id`",
        "ls `rm -rf /`",
        "uptime `whoami`",
        // Background execution
        "ls &",
        "rm -rf / &",
        // Quoting / escaping bypass attempts
        "ls \"&& id\"",
        "ls '; id'",
        "ls \\; id",
        "ls \\| id",
        "ls \\$\\(id\\)",
        "\"ls\"; id",
        "'ls' && id",
        "ls\\",
        // Glob / brace / tilde expansion
        "ls *",
        "ls /system/*",
        "ls {a,b}",
        "ls ~root",
        "cat ~",
        // Compound keywords and comments
        "if ls; then id; fi",
        "for i in 1 2; do ls; done",
        "ls # comment; id",
        // Env-prefix assignments
        "FOO=bar ls",
        "LD_PRELOAD=/evil/lib.so ls",
        // Newline separators
        "ls\nid",
        "uptime\nrm -rf /",
        // Argument injection — safe binary coerced into dangerous behavior
        "ls > /data/system/packages.xml",
        "df > /data/system/packages.xml",
    )

    @Nested
    inner class SafeCommandsRemainSafe {
        @Test
        fun `legitimate read-only commands are classified ALLOWED`() {
            for (cmd in safeCommands) {
                val eval = engine.evaluate(cmd)
                assertEquals(PolicyClassification.ALLOWED, eval.classification, "Expected ALLOWED for: $cmd")
            }
        }

        @Test
        fun `legitimate safe commands remain allowed in background`() {
            for (cmd in safeCommands) {
                assertTrue(
                    engine.isAllowedInBackground(TypedOp.ShellCommand(cmd)),
                    "Expected background allowance for: $cmd",
                )
            }
        }

        @Test
        fun `safe command parses as a single simple command`() {
            val parsed = ShellCommandGrammar.parse("ls -la /sdcard")
            assertNotNull(parsed)
            assertEquals("ls", parsed!!.executable)
            assertEquals(listOf("-la", "/sdcard"), parsed.arguments)
        }
    }

    @Nested
    inner class ShellCompositionIsNeverSafe {
        @Test
        fun `composed shell expressions are never classified ALLOWED`() {
            for (cmd in compositionAttacks) {
                val eval = engine.evaluate(cmd)
                assertFalse(
                    eval.classification == PolicyClassification.ALLOWED,
                    "Shell expression must NOT be SAFE: \"$cmd\" (got ${eval.classification})",
                )
            }
        }

        @Test
        fun `composed shell expressions never parse as simple commands`() {
            for (cmd in compositionAttacks) {
                assertNull(
                    ShellCommandGrammar.parse(cmd),
                    "Shell expression must not parse as a single simple command: \"$cmd\"",
                )
            }
        }

        @Test
        fun `composed shell expressions are never allowed in background`() {
            for (cmd in compositionAttacks) {
                assertFalse(
                    engine.isAllowedInBackground(TypedOp.ShellCommand(cmd)),
                    "Shell expression must never be background-executable: \"$cmd\"",
                )
            }
        }

        @Test
        fun `chained destructive fragments are classified BLOCKED`() {
            // The destructive fragment inside a composition must trigger the
            // blocked rules, not merely lose the safe classification.
            val blocked = listOf(
                "ls && rm -rf /",
                "echo safe; rm -rf /",
                "getprop; rm -rf /",
                "uptime; dd if=/dev/zero of=/dev/block/mmcblk0",
                "rm -rf / &",
                "ls \$(rm -rf /)",
                "ls `rm -rf /`",
            )
            for (cmd in blocked) {
                val eval = engine.evaluate(cmd)
                assertEquals(
                    PolicyClassification.BLOCKED,
                    eval.classification,
                    "Expected BLOCKED for: $cmd",
                )
            }
        }

        @Test
        fun `composed risky fragments keep confirmation semantics`() {
            // A risky (double-confirm) fragment inside a composition must never
            // degrade to a lower-privilege classification.
            val eval = engine.evaluate("dumpsys battery && pm clear com.example.app")
            assertTrue(
                eval.classification == PolicyClassification.DOUBLE_CONFIRM || eval.classification == PolicyClassification.BLOCKED,
                "Composed risky fragment must retain confirmation semantics (got ${eval.classification})",
            )
        }

        @Test
        fun `composed expressions fall back to sensitive confirmation`() {
            // Compositions of benign commands are not parseable as safe, so they
            // must land on (or above) the SENSITIVE confirmation tier.
            for (cmd in listOf("ls && id", "ls | id", "ls > /tmp/x", "ls \$(id)", "ls `id`", "ls & id")) {
                val eval = engine.evaluate(cmd)
                assertTrue(
                    eval.classification == PolicyClassification.SENSITIVE || eval.classification == PolicyClassification.DOUBLE_CONFIRM || eval.classification == PolicyClassification.BLOCKED,
                    "Expected confirmation-tier or blocked classification for: $cmd (got ${eval.classification})",
                )
            }
        }
    }

    @Nested
    inner class DangerousCommandsStayBlocked {
        @Test
        fun `dangerous commands are permanently blocked`() {
            val dangerous = listOf(
                "rm -rf /",
                "mkfs",
                "mkfs.ext4 /dev/block/mmcblk0",
                "dd if=/dev/zero of=/dev/block/mmcblk0",
                "reboot",
                "reboot recovery",
                "wipe data",
                "pm uninstall com.android.settings",
            )
            for (cmd in dangerous) {
                val eval = engine.evaluate(cmd)
                assertEquals(PolicyClassification.BLOCKED, eval.classification, "Expected BLOCKED for: $cmd")
            }
        }

        @Test
        fun `risky commands retain double confirmation`() {
            val risky = listOf(
                "pm clear com.example.app",
                "pm disable com.example.app",
                "rm -r /sdcard/Download/temp",
                "settings put global wifi_on 0",
                "setprop ro.debuggable 1",
                "kill -9 1234",
                "am force-stop com.example.app",
            )
            for (cmd in risky) {
                val eval = engine.evaluate(cmd)
                assertEquals(PolicyClassification.DOUBLE_CONFIRM, eval.classification, "Expected DOUBLE_CONFIRM for: $cmd")
            }
        }

        @Test
        fun `non-safe non-dangerous commands default to sensitive confirmation`() {
            val sensitive = listOf(
                "id",
                "whoami",
                "ps -A",
                "input tap 100 200",
                "logcat -d",
            )
            for (cmd in sensitive) {
                val eval = engine.evaluate(cmd)
                assertEquals(PolicyClassification.SENSITIVE, eval.classification, "Expected SENSITIVE for: $cmd")
            }
        }

        @Test
        fun `empty commands are blocked`() {
            assertEquals(PolicyClassification.BLOCKED, engine.evaluate("").classification)
            assertEquals(PolicyClassification.BLOCKED, engine.evaluate("   ").classification)
        }
    }

    @Nested
    inner class TypedOpReadOnlyInvariant {
        @Test
        fun `typed op read-only rejects shell compositions with safe prefixes`() {
            assertFalse(TypedOp.ShellCommand("dumpsys; id").isReadOnly)
            assertFalse(TypedOp.ShellCommand("dumpsys && id").isReadOnly)
            assertFalse(TypedOp.ShellCommand("getprop | id").isReadOnly)
            assertFalse(TypedOp.ShellCommand("pm list packages; reboot").isReadOnly)
            assertFalse(TypedOp.ShellCommand("settings get global wifi_on > /tmp/x").isReadOnly)
            assertFalse(TypedOp.ShellCommand("dumpsys \$(id)").isReadOnly)
            assertFalse(TypedOp.ShellCommand("uptime `id`").isReadOnly)
        }

        @Test
        fun `typed op read-only accepts genuine read-only commands`() {
            assertTrue(TypedOp.ShellCommand("dumpsys battery").isReadOnly)
            assertTrue(TypedOp.ShellCommand("getprop ro.build.version.release").isReadOnly)
            assertTrue(TypedOp.ShellCommand("pm list packages").isReadOnly)
            assertTrue(TypedOp.ShellCommand("settings get global wifi_on").isReadOnly)
            assertTrue(TypedOp.Screenshot.isReadOnly)
        }
    }

    @Nested
    inner class GrammarRobustness {
        @Test
        fun `grammar rejects every control operator`() {
            for (cmd in listOf("ls;", ";ls", "ls ; ls", "ls &", "ls && ls", "ls || ls", "ls | ls", "ls;;", "ls&ls")) {
                assertNull(ShellCommandGrammar.parse(cmd), "Expected null for: $cmd")
            }
        }

        @Test
        fun `grammar rejects every redirection form`() {
            for (cmd in listOf("ls > f", "ls >> f", "ls < f", "ls << EOF", "ls <<< s", "ls 2> f", "ls &> f", "ls <> f", "ls >| f")) {
                assertNull(ShellCommandGrammar.parse(cmd), "Expected null for: $cmd")
            }
        }

        @Test
        fun `grammar rejects substitution and expansion`() {
            for (cmd in listOf("ls \$(id)", "ls \${HOME}", "ls \$PATH", "ls `id`", "ls $((1+1))", "echo \$0")) {
                assertNull(ShellCommandGrammar.parse(cmd), "Expected null for: $cmd")
            }
        }

        @Test
        fun `grammar rejects quoting escaping and comments`() {
            for (cmd in listOf("ls \"a\"", "ls 'a'", "ls \\;x", "ls #comment", "ls *", "ls {a}", "ls ~", "ls a?", "ls [a]")) {
                assertNull(ShellCommandGrammar.parse(cmd), "Expected null for: $cmd")
            }
        }

        @Test
        fun `grammar rejects compound keywords and env prefixes`() {
            for (cmd in listOf("if true", "for i", "while true", "case x", "function f", "! ls", "FOO=bar ls", "TIME ls")) {
                assertNull(ShellCommandGrammar.parse(cmd), "Expected null for: $cmd")
            }
        }

        @Test
        fun `grammar rejects newlines and empty input`() {
            assertNull(ShellCommandGrammar.parse("ls\nid"))
            assertNull(ShellCommandGrammar.parse(""))
            assertNull(ShellCommandGrammar.parse("   "))
        }
    }
}
