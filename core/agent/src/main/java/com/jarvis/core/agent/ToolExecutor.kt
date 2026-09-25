package com.jarvis.core.agent

import com.jarvis.core.agent.execution.ErrorCode
import com.jarvis.core.agent.execution.ExecutionErrorMapper
import com.jarvis.core.agent.execution.UserFacingState
import com.jarvis.core.agent.execution.UserFacingStateType
import com.jarvis.core.common.Operation
import com.jarvis.core.common.OperationStatus
import com.jarvis.core.common.PermissionTier
import com.jarvis.core.database.repository.OperationRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Result of a single tool execution attempt.
 */
data class ToolExecutionOutcome(
    val toolName: String,
    val success: Boolean,
    val observationText: String,
    val errorCode: ErrorCode? = null,
    val userFacingState: UserFacingState? = null,
    val cancelled: Boolean = false,
    val rejected: Boolean = false,
    val rejectionReason: String? = null,
)

/**
 * Canonical service responsible for tool validation, policy checks,
 * confirmation gating, execution, audit logging, observation clamping,
 * and durable operation tracking (idempotency ledger for side-effects).
 */
@Singleton
class ToolExecutor @Inject constructor(
    private val registry: ToolRegistry,
    private val audit: AuditLogger,
    private val toolPolicy: ToolPolicy = DefaultToolPolicy(),
    private val contextManager: ContextManager = ContextManager(),
    private val operationRepository: OperationRepository = OperationRepository(),
) {
    private val validator = ToolArgsValidator()
    private val operationLocks = ConcurrentHashMap<String, Mutex>()

    /**
     * Executes a tool with end-to-end validation, policy evaluation, confirmation,
     * auditing, observation clamping, and durable operation deduplication.
     */
    suspend fun execute(
        toolName: String,
        argsJson: String,
        agentRunId: String? = null,
        idempotencyKey: String? = null,
        isExplicitRetry: Boolean = false,
        confirmationGate: ConfirmationGate? = null,
        forceConfirm: Boolean = false,
        disabledTools: Set<String> = emptySet(),
        llmProvider: com.jarvis.core.network.LlmProvider? = null,
        modelId: String? = null,
        onConfirmationRequired: (suspend (toolName: String, argsJson: String) -> Unit)? = null,
    ): ToolExecutionOutcome {
        val tool = registry.get(toolName)
        if (tool == null) {
            val available = registry.definitions().joinToString { it.name }
            val reason = "Tool '$toolName' not found. Available: $available."
            val uf = ExecutionErrorMapper.map(ErrorCode.TOOL_NOT_FOUND, reason)
            return ToolExecutionOutcome(
                toolName = toolName,
                success = false,
                observationText = reason,
                errorCode = ErrorCode.TOOL_NOT_FOUND,
                userFacingState = uf,
                rejected = true,
                rejectionReason = reason,
            )
        }

        if (tool.name in disabledTools) {
            val reason = "Tool '${tool.name}' is disabled in this environment."
            val uf = ExecutionErrorMapper.map(ErrorCode.TOOL_DISABLED, reason)
            return ToolExecutionOutcome(
                toolName = tool.name,
                success = false,
                observationText = reason,
                errorCode = ErrorCode.TOOL_DISABLED,
                userFacingState = uf,
                rejected = true,
                rejectionReason = reason,
            )
        }

        // 1. Argument validation
        when (val validation = validator.validate(tool.parametersSchemaJson, argsJson)) {
            is ToolArgsValidator.Result.Rejected -> {
                val uf = ExecutionErrorMapper.map(ErrorCode.INVALID_TOOL_ARGUMENTS, validation.reason)
                return ToolExecutionOutcome(
                    toolName = tool.name,
                    success = false,
                    observationText = "Tool '${tool.name}' rejected its arguments: ${validation.reason}",
                    errorCode = ErrorCode.INVALID_TOOL_ARGUMENTS,
                    userFacingState = uf,
                    rejected = true,
                    rejectionReason = validation.reason,
                )
            }
            ToolArgsValidator.Result.Valid -> Unit
        }

        // 2. Stable operation identity & Idempotency check for side effects
        val isSideEffecting = tool.tier != PermissionTier.READ_ONLY
        val effectiveKey = idempotencyKey ?: if (isSideEffecting && agentRunId != null) {
            "$agentRunId:${tool.name}:${stableOperationArguments(argsJson)}"
        } else {
            null
        }

        if (effectiveKey != null) {
            val opLock = operationLocks.computeIfAbsent(effectiveKey) { Mutex() }
            return opLock.withLock {
                executeWithOperationTracking(
                    tool = tool,
                    argsJson = argsJson,
                    agentRunId = agentRunId,
                    effectiveKey = effectiveKey,
                    isExplicitRetry = isExplicitRetry,
                    confirmationGate = confirmationGate,
                    forceConfirm = forceConfirm,
                    llmProvider = llmProvider,
                    modelId = modelId,
                    onConfirmationRequired = onConfirmationRequired,
                )
            }
        }

        return executeDirect(
            tool = tool,
            argsJson = argsJson,
            agentRunId = agentRunId,
            confirmationGate = confirmationGate,
            forceConfirm = forceConfirm,
            llmProvider = llmProvider,
            modelId = modelId,
            onConfirmationRequired = onConfirmationRequired,
        )
    }

    private suspend fun executeWithOperationTracking(
        tool: Tool,
        argsJson: String,
        agentRunId: String?,
        effectiveKey: String,
        isExplicitRetry: Boolean,
        confirmationGate: ConfirmationGate?,
        forceConfirm: Boolean,
        llmProvider: com.jarvis.core.network.LlmProvider? = null,
        modelId: String? = null,
        onConfirmationRequired: (suspend (toolName: String, argsJson: String) -> Unit)?,
    ): ToolExecutionOutcome {
        val opRepo = operationRepository

        val existing = opRepo.getByKey(effectiveKey)
        if (existing != null) {
            when (existing.status) {
                OperationStatus.SUCCEEDED -> {
                    val obs = existing.resultJson ?: "Operation $effectiveKey was already completed in prior run."
                    withContext(NonCancellable) {
                        audit.record(
                            AuditRecord(
                                agentRunId = agentRunId,
                                toolName = tool.name,
                                tier = tool.tier.name.lowercase(),
                                paramsRedactedJson = AuditRedaction.redact(argsJson),
                                resultStatus = "deduplicated_success",
                                userConfirmed = true,
                            ),
                        )
                    }
                    return ToolExecutionOutcome(
                        toolName = tool.name,
                        success = true,
                        observationText = obs,
                        userFacingState = UserFacingState(
                            type = UserFacingStateType.SUCCESS,
                            title = "Done",
                            message = obs,
                        ),
                    )
                }
                OperationStatus.EXECUTING -> {
                    val msg = "Operation $effectiveKey was in-flight when previous attempt was interrupted. Automatic retry blocked to prevent duplicate side effects."
                    val uf = ExecutionErrorMapper.map(ErrorCode.OPERATION_UNCERTAIN, msg)
                    return ToolExecutionOutcome(
                        toolName = tool.name,
                        success = false,
                        observationText = msg,
                        errorCode = ErrorCode.OPERATION_UNCERTAIN,
                        userFacingState = uf,
                        rejected = true,
                        rejectionReason = msg,
                    )
                }
                OperationStatus.UNKNOWN -> {
                    val msg = "Operation $effectiveKey outcome is UNKNOWN after an interrupted execution. Requires recovery before retry."
                    val uf = ExecutionErrorMapper.map(ErrorCode.OPERATION_UNCERTAIN, msg)
                    return ToolExecutionOutcome(
                        toolName = tool.name,
                        success = false,
                        observationText = msg,
                        errorCode = ErrorCode.OPERATION_UNCERTAIN,
                        userFacingState = uf,
                        rejected = true,
                        rejectionReason = msg,
                    )
                }
                OperationStatus.FAILED -> {
                    if (!isExplicitRetry) {
                        val msg = existing.errorMessage ?: "Operation previously failed"
                        val uf = ExecutionErrorMapper.map(ErrorCode.TOOL_EXECUTION_FAILED, msg)
                        return ToolExecutionOutcome(
                            toolName = tool.name,
                            success = false,
                            observationText = msg,
                            errorCode = ErrorCode.TOOL_EXECUTION_FAILED,
                            userFacingState = uf,
                            rejected = true,
                            rejectionReason = msg,
                        )
                    }
                }
            }
        }

        val now = System.currentTimeMillis()
        val operationId = existing?.id ?: UUID.randomUUID().toString()

        if (existing == null) {
            try {
                opRepo.insert(
                    Operation(
                        id = operationId,
                        taskId = agentRunId ?: "interactive",
                        toolName = tool.name,
                        idempotencyKey = effectiveKey,
                        status = OperationStatus.EXECUTING,
                        createdAt = now,
                        updatedAt = now,
                    ),
                )
            } catch (_: Exception) {
                // Handle concurrent insert race
                val concurrent = opRepo.getByKey(effectiveKey)
                if (concurrent != null && concurrent.status == OperationStatus.SUCCEEDED) {
                    val obs = concurrent.resultJson ?: "Operation completed."
                    return ToolExecutionOutcome(
                        toolName = tool.name,
                        success = true,
                        observationText = obs,
                        userFacingState = UserFacingState(UserFacingStateType.SUCCESS, "Done", obs),
                    )
                }
            }
        } else {
            opRepo.updateStatus(
                existing.copy(
                    status = OperationStatus.EXECUTING,
                    errorMessage = null,
                    updatedAt = now,
                ),
            )
        }

        val outcome = try {
            executeDirect(
                tool = tool,
                argsJson = argsJson,
                agentRunId = agentRunId,
                confirmationGate = confirmationGate,
                forceConfirm = forceConfirm,
                onConfirmationRequired = onConfirmationRequired,
            )
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                opRepo.updateStatus(
                    Operation(
                        id = operationId,
                        taskId = agentRunId ?: "interactive",
                        toolName = tool.name,
                        idempotencyKey = effectiveKey,
                        status = OperationStatus.UNKNOWN,
                        errorMessage = "Operation interrupted mid-execution; external outcome is uncertain.",
                        createdAt = existing?.createdAt ?: now,
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
            }
            throw e
        } catch (e: Exception) {
            withContext(NonCancellable) {
                opRepo.updateStatus(
                    Operation(
                        id = operationId,
                        taskId = agentRunId ?: "interactive",
                        toolName = tool.name,
                        idempotencyKey = effectiveKey,
                        status = OperationStatus.FAILED,
                        errorMessage = e.message ?: "Execution failed",
                        createdAt = existing?.createdAt ?: now,
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
            }
            return ToolExecutionOutcome(
                toolName = tool.name,
                success = false,
                observationText = "Tool execution failed: ${e.message ?: e.javaClass.simpleName}",
                errorCode = ErrorCode.TOOL_EXECUTION_FAILED,
                userFacingState = ExecutionErrorMapper.map(ErrorCode.TOOL_EXECUTION_FAILED, e.message),
            )
        }

        withContext(NonCancellable) {
            if (outcome.success) {
                opRepo.updateStatus(
                    Operation(
                        id = operationId,
                        taskId = agentRunId ?: "interactive",
                        toolName = tool.name,
                        idempotencyKey = effectiveKey,
                        status = OperationStatus.SUCCEEDED,
                        resultJson = outcome.observationText,
                        createdAt = existing?.createdAt ?: now,
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
            } else {
                val failureMsg = if (outcome.cancelled) {
                    "Operation cancelled: ${outcome.observationText}"
                } else if (outcome.rejected) {
                    "Operation rejected: ${outcome.rejectionReason ?: outcome.observationText}"
                } else {
                    outcome.observationText
                }
                opRepo.updateStatus(
                    Operation(
                        id = operationId,
                        taskId = agentRunId ?: "interactive",
                        toolName = tool.name,
                        idempotencyKey = effectiveKey,
                        status = OperationStatus.FAILED,
                        errorMessage = failureMsg,
                        createdAt = existing?.createdAt ?: now,
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
            }
        }

        return outcome
    }

    private suspend fun executeDirect(
        tool: Tool,
        argsJson: String,
        agentRunId: String?,
        confirmationGate: ConfirmationGate?,
        forceConfirm: Boolean,
        llmProvider: com.jarvis.core.network.LlmProvider? = null,
        modelId: String? = null,
        onConfirmationRequired: (suspend (toolName: String, argsJson: String) -> Unit)?,
    ): ToolExecutionOutcome {
        // Policy evaluation
        val policyDecision = toolPolicy.evaluate(tool, argsJson, forceConfirm)
        if (policyDecision is PolicyDecision.Deny) {
            val uf = ExecutionErrorMapper.map(ErrorCode.POLICY_DENIED, policyDecision.reason)
            withContext(NonCancellable) {
                audit.record(
                    AuditRecord(
                        agentRunId = agentRunId,
                        toolName = tool.name,
                        tier = tool.tier.name.lowercase(),
                        paramsRedactedJson = AuditRedaction.redact(argsJson),
                        resultStatus = "blocked",
                        userConfirmed = false,
                    ),
                )
            }
            return ToolExecutionOutcome(
                toolName = tool.name,
                success = false,
                observationText = "Execution blocked by policy: ${policyDecision.reason}",
                errorCode = ErrorCode.POLICY_DENIED,
                userFacingState = uf,
                rejected = true,
                rejectionReason = policyDecision.reason,
            )
        }

        // User confirmation gate
        var userConfirmed = false
        if (policyDecision is PolicyDecision.RequireConfirmation) {
            onConfirmationRequired?.invoke(tool.name, argsJson)
            val allowed = confirmationGate?.confirm(tool.name, argsJson) ?: false
            if (!allowed) {
                withContext(NonCancellable) {
                    audit.record(
                        AuditRecord(
                            agentRunId = agentRunId,
                            toolName = tool.name,
                            tier = tool.tier.name.lowercase(),
                            paramsRedactedJson = AuditRedaction.redact(argsJson),
                            resultStatus = "cancelled",
                            userConfirmed = false,
                        ),
                    )
                }
                return ToolExecutionOutcome(
                    toolName = tool.name,
                    success = false,
                    observationText = "Action '${tool.name}' was cancelled by user.",
                    errorCode = ErrorCode.USER_CANCELLED,
                    userFacingState = ExecutionErrorMapper.map(ErrorCode.USER_CANCELLED),
                    cancelled = true,
                )
            }
            userConfirmed = true
        }

        // Execution
        val execResult = try {
            val gateElement = ConfirmationGateElement(confirmationGate)
            val llmElement = LlmProviderElement(llmProvider, modelId)
            val phoneApprovalElement = PhoneAutomationApprovalElement(approved = userConfirmed)
            withContext(gateElement + llmElement + phoneApprovalElement) {
                tool.execute(argsJson)
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                audit.record(
                    AuditRecord(
                        agentRunId = agentRunId,
                        toolName = tool.name,
                        tier = tool.tier.name.lowercase(),
                        paramsRedactedJson = AuditRedaction.redact(argsJson),
                        resultStatus = "cancelled",
                        userConfirmed = userConfirmed,
                    ),
                )
            }
            throw e
        } catch (e: Exception) {
            val errCode = when {
                e is java.net.UnknownHostException -> ErrorCode.NETWORK_UNAVAILABLE
                e is java.net.SocketTimeoutException -> ErrorCode.NETWORK_TIMEOUT
                e.message?.contains("Accessibility", ignoreCase = true) == true -> ErrorCode.ACCESSIBILITY_UNAVAILABLE
                else -> ErrorCode.TOOL_EXECUTION_FAILED
            }
            ToolResult(
                success = false,
                observationText = "Tool execution failed: ${e.message ?: e.javaClass.simpleName}",
                errorCode = errCode,
            )
        }

        // Clamp observation
        val clampedObs = contextManager.clampObservation(execResult.observationText, maxTokens = 1000)

        // Audit record
        withContext(NonCancellable) {
            audit.record(
                AuditRecord(
                    agentRunId = agentRunId,
                    toolName = tool.name,
                    tier = tool.tier.name.lowercase(),
                    paramsRedactedJson = AuditRedaction.redact(argsJson),
                    resultStatus = if (execResult.success) "success" else "failed",
                    userConfirmed = userConfirmed || tool.tier == PermissionTier.SENSITIVE,
                ),
            )
        }

        val resolvedCode = execResult.resolvedErrorCode
        val ufState = if (execResult.success) {
            UserFacingState(
                type = UserFacingStateType.SUCCESS,
                title = "Done",
                message = clampedObs,
            )
        } else {
            ExecutionErrorMapper.map(resolvedCode, clampedObs, mapOf("tool" to tool.name))
        }

        return ToolExecutionOutcome(
            toolName = tool.name,
            success = execResult.success,
            observationText = clampedObs,
            errorCode = if (execResult.success) null else resolvedCode,
            userFacingState = ufState,
        )
    }

    private fun stableOperationArguments(rawJson: String): String {
        val parsed = runCatching { Json.parseToJsonElement(rawJson) }.getOrNull() ?: return rawJson.trim()
        return canonicalJson(parsed).toString()
    }

    private fun canonicalJson(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.entries.sortedBy { it.key }.associate { it.key to canonicalJson(it.value) })
        is JsonArray -> JsonArray(element.map(::canonicalJson))
        else -> element
    }
}

