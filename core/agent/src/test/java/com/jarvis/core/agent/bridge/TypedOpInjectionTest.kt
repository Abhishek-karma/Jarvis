package com.jarvis.core.agent.bridge

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Regression tests for the typed-operation shell translation boundary.
 *
 * TypedOps are translated by ShizukuBridge into shell strings via string
 * interpolation. The TypedOpComponentGuard must reject model-influenced
 * components carrying shell metacharacters so the composed command can never
 * gain additional shell semantics.
 */
class TypedOpComponentGuardTest {

    @Test
    fun `valid package names pass`() {
        assertNull(TypedOpComponentGuard.validatePackageName("com.example.app"))
        assertNull(TypedOpComponentGuard.validatePackageName("org.mozilla.firefox"))
    }

    @Test
    fun `package name injection attempts fail`() {
        val attacks = listOf(
            "com.example.app; reboot",
            "com.example.app && id",
            "com.example.app|id",
            "com.example.app\$(id)",
            "com.example.app`id`",
            "com.example.app > /tmp/x",
            "com.example.app & id",
            "com example app",
            "com.example.app\nreboot",
            "",
            "   ",
        )
        for (attack in attacks) {
            assertNotNull(
                TypedOpComponentGuard.validatePackageName(attack),
                "Expected rejection for package name: \"$attack\"",
            )
        }
    }

    @Test
    fun `valid permission names pass`() {
        assertNull(TypedOpComponentGuard.validatePermissionName("android.permission.CAMERA"))
    }

    @Test
    fun `permission name injection attempts fail`() {
        val attacks = listOf(
            "android.permission.CAMERA; rm -rf /",
            "android.permission.CAMERA && reboot",
            "android.permission.CAMERA|id",
            "android.permission \$(id)",
            "android.permission.CAMERA`id`",
            "android.permission.CAMERA > x",
            "",
        )
        for (attack in attacks) {
            assertNotNull(
                TypedOpComponentGuard.validatePermissionName(attack),
                "Expected rejection for permission: \"$attack\"",
            )
        }
    }

    @Test
    fun `valid setting keys and values pass`() {
        assertNull(TypedOpComponentGuard.validateSettingKey("wifi_on"))
        assertNull(TypedOpComponentGuard.validateSettingKey("bluetooth_on"))
        assertNull(TypedOpComponentGuard.validateSettingValue("1"))
        assertNull(TypedOpComponentGuard.validateSettingValue("enabled"))
    }

    @Test
    fun `setting value injection attempts fail`() {
        val attacks = listOf(
            "1; reboot",
            "1 && id",
            "1|id",
            "1\$(id)",
            "1`id`",
            "1 > /tmp/x",
            "1 & id",
            "value with spaces",
            "a\tb",
            "a\nb",
            "",
        )
        for (attack in attacks) {
            assertNotNull(
                TypedOpComponentGuard.validateSettingValue(attack),
                "Expected rejection for setting value: \"$attack\"",
            )
        }
    }

    @Test
    fun `setting key injection attempts fail`() {
        val attacks = listOf(
            "wifi_on; reboot",
            "wifi_on && id",
            "wifi_on|id",
            "wifi_on \$(id)",
            "wifi_on `id`",
            "wifi_on > x",
            "",
        )
        for (attack in attacks) {
            assertNotNull(
                TypedOpComponentGuard.validateSettingKey(attack),
                "Expected rejection for setting key: \"$attack\"",
            )
        }
    }

    @Test
    fun `valid appops pass`() {
        assertNull(TypedOpComponentGuard.validateAppOp("vibrate", "allow"))
        assertNull(TypedOpComponentGuard.validateAppOp("project_fine_vibrate", "deny"))
    }

    @Test
    fun `appop injection and unknown modes fail`() {
        val attacksOp = listOf(
            "vibrate; reboot",
            "vibrate && id",
            "vibrate|id",
            "vibrate \$(id)",
            "vibrate `id`",
            "",
        )
        for (attack in attacksOp) {
            assertNotNull(
                TypedOpComponentGuard.validateAppOp(attack, "allow"),
                "Expected rejection for appop op: \"$attack\"",
            )
        }
        assertNotNull(TypedOpComponentGuard.validateAppOp("vibrate", "allow; reboot"))
        assertNotNull(TypedOpComponentGuard.validateAppOp("vibrate", "not-a-mode"))
    }

    @Test
    fun `validate covers every typed op field set`() {
        // ShellCommand and Screenshot / InputGesture carry no interpolated
        // components and pass the guard (shell strings are governed by the
        // CommandPolicyEngine itself).
        assertNull(TypedOpComponentGuard.validate(TypedOp.Screenshot))
        assertNull(TypedOpComponentGuard.validate(TypedOp.ShellCommand("dumpsys battery")))

        assertNotNull(TypedOpComponentGuard.validate(TypedOp.ForceStop("com.x; reboot")))
        assertNotNull(TypedOpComponentGuard.validate(TypedOp.SetAppEnabled("com.x|id", true)))
        assertNotNull(TypedOpComponentGuard.validate(TypedOp.GrantPermission("com.example", "p; reboot")))
        assertNotNull(TypedOpComponentGuard.validate(TypedOp.RevokePermission("com.example; reboot", "p")))
        assertNotNull(TypedOpComponentGuard.validate(TypedOp.SetGlobalSetting("wifi_on; reboot", "1")))
        assertNotNull(TypedOpComponentGuard.validate(TypedOp.SetSecureSetting("wifi_on", "1; reboot")))
        assertNotNull(TypedOpComponentGuard.validate(TypedOp.AppOp("vibrate; reboot", "com.x", "allow")))
    }

    @Test
    fun `guard rejects values with whitespace and metacharacters`() {
        for (value in listOf("a b", "a\tb", "a\nb", "a;b", "a&b", "a|b", "a<b", "a>b", "a\$b", "a😈b", "a\\b", "a'b", "a\"b")) {
            assertNotNull(
                TypedOpComponentGuard.validateNoShellMetacharacters(value),
                "Expected rejection for: \"$value\"",
            )
        }
        assertNull(TypedOpComponentGuard.validateNoShellMetacharacters("wifi_on"))
        assertNull(TypedOpComponentGuard.validateNoShellMetacharacters("com.example.app"))
    }
}

/**
 * Regression tests proving that the bridge layer enforces policy BEFORE any
 * privileged execution can occur, and that the component guard blocks composed
 * typed ops before shell translation.
 */
class BridgePolicyEnforcementTest {

    private val context = mockk<android.content.Context>(relaxed = true)

    private fun newCoordinator(
        sandbox: SandboxBridge,
        shizuku: ShizukuBridge,
    ): BridgeCoordinator = BridgeCoordinator(
        sandboxBridge = sandbox,
        shizukuBridge = shizuku,
        policyEngine = CommandPolicyEngine(),
    )

    private fun mockSandbox(): SandboxBridge = mockk {
        every { tier } returns BridgeTier.SANDBOX
        every { name } returns "Mock Sandbox"
        coEvery { isAvailable() } returns BridgeStatus.AVAILABLE
        coEvery { execTyped(any()) } returns OpResult.Success("executed", BridgeTier.SANDBOX)
    }

    private fun mockShizuku(): ShizukuBridge = mockk {
        every { tier } returns BridgeTier.SHIZUKU
        every { name } returns "Mock Shizuku"
        coEvery { isAvailable() } returns BridgeStatus.AVAILABLE
        coEvery { execTyped(any()) } returns OpResult.Success("executed", BridgeTier.SHIZUKU)
    }

    @Test
    fun `coordinator blocks policy-failing shell commands before any bridge execution`() = runBlocking {
        val sandbox = mockSandbox()
        val shizuku = mockShizuku()
        val coordinator = newCoordinator(sandbox, shizuku)

        val result = coordinator.execute(TypedOp.ShellCommand("rm -rf /"))

        assertTrue(result is OpResult.Blocked, "Expected Blocked, got: $result")
        coVerify(exactly = 0) { shizuku.execTyped(any()) }
        coVerify(exactly = 0) { sandbox.execTyped(any()) }
    }

    @Test
    fun `coordinator blocks composed injection commands before any bridge execution`() = runBlocking {
        val sandbox = mockSandbox()
        val shizuku = mockShizuku()
        val coordinator = newCoordinator(sandbox, shizuku)

        for (cmd in listOf("ls && rm -rf /", "echo safe; rm -rf /", "getprop; rm -rf /")) {
            val result = coordinator.execute(TypedOp.ShellCommand(cmd))
            assertTrue(result is OpResult.Blocked, "Expected Blocked for \"$cmd\", got: $result")
        }
        coVerify(exactly = 0) { shizuku.execTyped(any()) }
        coVerify(exactly = 0) { sandbox.execTyped(any()) }
    }

    @Test
    fun `coordinator still executes legitimate typed ops through shizuku tier`() = runBlocking {
        val sandbox = mockSandbox()
        val shizuku = mockShizuku()
        val coordinator = newCoordinator(sandbox, shizuku)

        val result = coordinator.execute(TypedOp.ForceStop("com.example.app"))

        assertTrue(result is OpResult.Success, "Expected Success, got: $result")
        coVerify(exactly = 1) { shizuku.execTyped(TypedOp.ForceStop("com.example.app")) }
        coVerify(exactly = 0) { sandbox.execTyped(any()) }
    }

    @Test
    fun `shizuku bridge itself re-checks policy before privileged execution`() = runBlocking {
        // Relaxed adapter: package "not installed" so the bridge can never reach
        // privileged execution in tests. The Blocked verdict must still come from
        // the policy re-check BEFORE the availability short-circuit.
        val bridge = ShizukuBridge(context = context, adapter = mockk(relaxed = true))

        val result = bridge.execTyped(TypedOp.ShellCommand("rm -rf /"))

        assertTrue(result is OpResult.Blocked, "Expected Blocked, got: $result")
    }

    @Test
    fun `shizuku bridge rejects typed ops with injected components before availability check`() = runBlocking {
        val bridge = ShizukuBridge(context = context, adapter = mockk(relaxed = true))

        // The bridge is unavailable in this environment (no Shizuku package/binder),
        // but the guard must reject the op BEFORE availability short-circuits.
        val result = bridge.execTyped(TypedOp.SetGlobalSetting("wifi_on; reboot", "1"))

        assertTrue(result is OpResult.Blocked, "Expected Blocked, got: $result")
    }

    @Test
    fun `shizuku bridge reports unavailability for valid ops when shizuku is absent`() = runBlocking {
        val bridge = ShizukuBridge(context = context, adapter = mockk(relaxed = true))

        // Guard passes, policy passes -> execution stops at availability, NOT blocked.
        val result = bridge.execTyped(TypedOp.SetGlobalSetting("wifi_on", "1"))

        assertTrue(result is OpResult.Unavailable, "Expected Unavailable, got: $result")
    }

    @Test
    fun `sensitive shell commands are not auto-allowed by policy`() {
        // SENSITIVE commands are not auto-blocked, but they are also not auto-run:
        // the AgentRunner ToolPolicy + confirmation gate owns that decision (SENSITIVE
        // tier tool -> RequireConfirmation). This test pins the policy classification.
        val eval = CommandPolicyEngine().evaluate("input tap 100 200")
        assertEquals(PolicyClassification.SENSITIVE, eval.classification)
    }

    @Test
    fun `coordinator blocks empty shell commands`() = runBlocking {
        val sandbox = mockSandbox()
        val shizuku = mockShizuku()
        val coordinator = newCoordinator(sandbox, shizuku)

        val result = coordinator.execute(TypedOp.ShellCommand("   "))

        assertTrue(result is OpResult.Blocked, "Expected Blocked for empty command, got: $result")
        coVerify(exactly = 0) { shizuku.execTyped(any()) }
    }

    @Test
    fun `coordinator passes non-shell ops through without shell policy`() = runBlocking {
        val sandbox = mockSandbox()
        val shizuku = mockShizuku()
        val coordinator = newCoordinator(sandbox, shizuku)

        val result = coordinator.execute(TypedOp.Screenshot)

        assertTrue(result is OpResult.Success, "Expected Success, got: $result")
        coVerify(exactly = 1) { shizuku.execTyped(TypedOp.Screenshot) }
    }
}
