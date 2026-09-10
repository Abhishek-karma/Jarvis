package com.jarvis.core.agent.bridge

/**
 * Defense-in-depth guard validating the model-influenced string components of
 * [TypedOp]s BEFORE any bridge translates them into shell strings.
 *
 * Typed operations (grant/revoke permission, force-stop, settings writes, appops,
 * app enable/disable) are translated by [ShizukuBridge] into `pm`/`settings`/`cmd`
 * shell invocations with string interpolation. To make that interpolation safe, the
 * components are restricted to strict character sets that contain no shell
 * metacharacters and no whitespace, so a composed command is always a single
 * simple command exactly equivalent to the typed operation's intent.
 */
object TypedOpComponentGuard {

    /**
     * Allowlist of characters permitted in typed-op components: letters, digits and
     * a small punctuation set used by packages, permissions, settings keys and values.
     * Everything else — shell operators, expansion chars, whitespace, quotes,
     * backslashes, non-ASCII — is rejected.
     */
    private val permittedComponentChars = Regex("""^[A-Za-z0-9._+@=:/,-]+${'$'}""")

    /** Package names: reverse-domain labels and digits only (`com.example.app`). */
    private val packageNamePattern = Regex("""^[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z0-9_]+)+$""")

    /**
     * Android permission names: reverse-domain path segments
     * (e.g. `android.permission.CAMERA`).
     */
    private val permissionNamePattern = Regex("""^[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z0-9_]+)+$""")

    /** Settings keys: dotted identifiers (`wifi_on`, `bluetooth_on`, `a.b.c`). */
    private val settingKeyPattern = Regex("""^[a-zA-Z0-9_.]+$""")

    /** AppOps operation names: dotted identifiers (e.g. `project_fine_vibrate`). */
    private val appOpPattern = Regex("""^[a-zA-Z0-9_.]+$""")

    /** AppOps modes: the set accepted by `cmd appops set`. */
    private val appOpModes = setOf("allow", "ignore", "deny", "default", "foreground")

    /**
     * Validates that [value] contains no shell metacharacters or whitespace.
     *
     * @return `null` when the value is safe; a human-readable reason otherwise.
     */
    fun validateNoShellMetacharacters(value: String): String? {
        if (!permittedComponentChars.matches(value)) {
            return "Contains characters outside the permitted component set"
        }
        return null
    }

    /**
     * Validates a package name component.
     *
     * @return `null` when safe, otherwise the rejection reason.
     */
    fun validatePackageName(value: String): String? {
        if (value.isBlank()) return "Package name cannot be empty"
        validateNoShellMetacharacters(value)?.let { return it }
        if (!packageNamePattern.matches(value)) {
            return "Package name contains characters outside the allowed pattern"
        }
        return null
    }

    /**
     * Validates a permission name component.
     *
     * @return `null` when safe, otherwise the rejection reason.
     */
    fun validatePermissionName(value: String): String? {
        if (value.isBlank()) return "Permission name cannot be empty"
        validateNoShellMetacharacters(value)?.let { return it }
        if (!permissionNamePattern.matches(value)) {
            return "Permission name contains characters outside the allowed pattern"
        }
        return null
    }

    /**
     * Validates a settings key component.
     *
     * @return `null` when safe, otherwise the rejection reason.
     */
    fun validateSettingKey(value: String): String? {
        if (value.isBlank()) return "Setting key cannot be empty"
        validateNoShellMetacharacters(value)?.let { return it }
        if (!settingKeyPattern.matches(value)) {
            return "Setting key contains characters outside the allowed pattern"
        }
        return null
    }

    /**
     * Validates a settings value component (free-form string, but strictly
     * free of shell metacharacters and whitespace).
     *
     * @return `null` when safe, otherwise the rejection reason.
     */
    fun validateSettingValue(value: String): String? {
        if (value.isBlank()) return "Setting value cannot be blank"
        return validateNoShellMetacharacters(value)
    }

    /**
     * Validates an AppOps operation and mode pair.
     *
     * @return `null` when safe, otherwise the rejection reason.
     */
    fun validateAppOp(op: String, mode: String): String? {
        if (op.isBlank()) return "AppOps operation cannot be empty"
        validateNoShellMetacharacters(op)?.let { return it }
        if (!appOpPattern.matches(op)) {
            return "AppOps operation contains characters outside the allowed pattern"
        }
        val normalizedMode = mode.trim().lowercase()
        if (normalizedMode !in appOpModes) {
            return "AppOps mode '$mode' is not a recognized mode"
        }
        return null
    }

    /**
     * Validates the full component set of a typed operation.
     *
     * @return `null` when every component is safe, otherwise the rejection reason.
     */
    fun validate(op: TypedOp): String? = when (op) {
        is TypedOp.GrantPermission -> validatePackageName(op.packageName)
            ?: validatePermissionName(op.permission)

        is TypedOp.RevokePermission -> validatePackageName(op.packageName)
            ?: validatePermissionName(op.permission)

        is TypedOp.SetGlobalSetting -> validateSettingKey(op.key)
            ?: validateSettingValue(op.value)

        is TypedOp.SetSecureSetting -> validateSettingKey(op.key)
            ?: validateSettingValue(op.value)

        is TypedOp.ForceStop -> validatePackageName(op.packageName)

        is TypedOp.SetAppEnabled -> validatePackageName(op.packageName)

        is TypedOp.AppOp -> validateAppOp(op.op, op.mode)

        is TypedOp.Screenshot, is TypedOp.InputGesture, is TypedOp.ShellCommand -> null
    }
}
