package com.jarvis.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jarvis.core.agent.RoutineScheduler
import com.jarvis.core.agent.TaskEngine
import com.jarvis.core.common.DispatcherProvider
import com.jarvis.core.common.Routine
import com.jarvis.core.common.RoutineScheduleType
import com.jarvis.core.common.Task
import com.jarvis.core.common.TaskState
import com.jarvis.core.database.repository.RoutineRepository
import com.jarvis.core.database.repository.TaskRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

data class AutomationUiState(
    val selectedTab: Int = 0, // 0 = Routines, 1 = Tasks
    val routines: List<Routine> = emptyList(),
    val tasks: List<Task> = emptyList(),
    val editingRoutine: Routine? = null,
    val showRoutineDialog: Boolean = false,
    val showImportDialog: Boolean = false,
    val selectedTaskStateFilter: TaskState? = null,
    val isRunningRoutineId: String? = null,
)

sealed interface AutomationEvent {
    data class ShowMessage(val message: String) : AutomationEvent
    data class ShowError(val error: String) : AutomationEvent
}

@HiltViewModel
class AutomationViewModel @Inject constructor(
    private val routineRepository: RoutineRepository,
    private val taskRepository: TaskRepository,
    private val routineScheduler: RoutineScheduler,
    private val taskEngine: TaskEngine,
    private val dispatchers: DispatcherProvider,
) : ViewModel() {

    private val _selectedTab = MutableStateFlow(0)
    private val _editingRoutine = MutableStateFlow<Routine?>(null)
    private val _showRoutineDialog = MutableStateFlow(false)
    private val _showImportDialog = MutableStateFlow(false)
    private val _selectedTaskStateFilter = MutableStateFlow<TaskState?>(null)
    private val _isRunningRoutineId = MutableStateFlow<String?>(null)

    private val _events = MutableSharedFlow<AutomationEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<AutomationEvent> = _events.asSharedFlow()

    private data class AutomationControls(
        val tab: Int,
        val editingRoutine: Routine?,
        val showDialog: Boolean,
        val showImportDialog: Boolean,
        val filterState: TaskState?,
        val runningRoutineId: String?,
    )

    private val _dialogControls = combine(
        _editingRoutine,
        _showRoutineDialog,
        _showImportDialog,
    ) { editingRoutine, showDialog, showImportDialog ->
        Triple(editingRoutine, showDialog, showImportDialog)
    }

    private val _uiControls = combine(
        _selectedTab,
        _dialogControls,
        _selectedTaskStateFilter,
        _isRunningRoutineId,
    ) { tab, dialogs, filterState, runningRoutineId ->
        AutomationControls(
            tab = tab,
            editingRoutine = dialogs.first,
            showDialog = dialogs.second,
            showImportDialog = dialogs.third,
            filterState = filterState,
            runningRoutineId = runningRoutineId,
        )
    }

    val uiState: StateFlow<AutomationUiState> = combine(
        routineRepository.observeAll(),
        taskRepository.observeAll(),
        _uiControls,
    ) { routines, tasks, controls ->
        val filteredTasks = if (controls.filterState == null) tasks else tasks.filter { it.state == controls.filterState }
        AutomationUiState(
            selectedTab = controls.tab,
            routines = routines,
            tasks = filteredTasks,
            editingRoutine = controls.editingRoutine,
            showRoutineDialog = controls.showDialog,
            showImportDialog = controls.showImportDialog,
            selectedTaskStateFilter = controls.filterState,
            isRunningRoutineId = controls.runningRoutineId,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = AutomationUiState(),
    )

    fun selectTab(tab: Int) {
        _selectedTab.value = tab
    }

    fun setTaskStateFilter(filter: TaskState?) {
        _selectedTaskStateFilter.value = filter
    }

    fun setShowRoutineDialog(show: Boolean) {
        _showRoutineDialog.value = show
        if (!show) _editingRoutine.value = null
    }

    fun setShowImportDialog(show: Boolean) {
        _showImportDialog.value = show
    }

    fun exportRoutinesJson(): String {
        val routines = uiState.value.routines
        val array = org.json.JSONArray()
        for (r in routines) {
            val obj = org.json.JSONObject().apply {
                put("name", r.name)
                put("goal", r.goal)
                put("schedule_type", r.scheduleType.name)
                put("cron_or_interval", r.cronOrInterval)
                put("enabled", r.enabled)
            }
            array.put(obj)
        }
        return array.toString(2)
    }

    fun importRoutinesJson(jsonStr: String) {
        viewModelScope.launch(dispatchers.io) {
            runCatching {
                val array = org.json.JSONArray(jsonStr)
                var count = 0
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val name = obj.getString("name").trim()
                    val goal = obj.getString("goal").trim()
                    val scheduleTypeName = obj.optString("schedule_type", RoutineScheduleType.RECURRING.name)
                    val scheduleType = runCatching { RoutineScheduleType.valueOf(scheduleTypeName) }.getOrDefault(RoutineScheduleType.RECURRING)
                    val cronOrInterval = obj.optString("cron_or_interval", "0 9 * * *").trim()
                    val enabled = obj.optBoolean("enabled", true)
                    val now = System.currentTimeMillis()
                    val nextRun = if (scheduleType == RoutineScheduleType.RECURRING) {
                        RoutineScheduler.calculateNextRunTime(scheduleType, cronOrInterval, now)
                    } else {
                        cronOrInterval.toLongOrNull() ?: (now + 3600_000L)
                    }
                    val routine = Routine(
                        id = UUID.randomUUID().toString(),
                        name = name,
                        goal = goal,
                        scheduleType = scheduleType,
                        cronOrInterval = cronOrInterval,
                        enabled = enabled,
                        nextRunAt = nextRun,
                        createdAt = now,
                        updatedAt = now,
                    )
                    routineRepository.upsert(routine)
                    if (routine.enabled && routine.scheduleType == RoutineScheduleType.RECURRING) {
                        routineScheduler.setEnabled(routine.id, true)
                    }
                    count++
                }
                _showImportDialog.value = false
                _events.tryEmit(AutomationEvent.ShowMessage("Imported $count routine(s)"))
            }.onFailure { err ->
                _events.tryEmit(AutomationEvent.ShowError("Import failed: ${err.message ?: "Invalid JSON"}"))
            }
        }
    }

    fun setEditingRoutine(routine: Routine?) {
        _editingRoutine.value = routine
        _showRoutineDialog.value = routine != null
    }

    fun runRoutineNow(routineId: String) {
        viewModelScope.launch(dispatchers.io) {
            _isRunningRoutineId.value = routineId
            val result = routineScheduler.runNow(routineId)
            _isRunningRoutineId.value = null
            result.onSuccess { msg ->
                _events.tryEmit(AutomationEvent.ShowMessage(msg))
            }.onFailure { err ->
                _events.tryEmit(AutomationEvent.ShowError(err.message ?: "Failed to execute routine"))
            }
        }
    }

    fun toggleRoutineEnabled(routineId: String, enabled: Boolean) {
        viewModelScope.launch(dispatchers.io) {
            routineScheduler.setEnabled(routineId, enabled)
        }
    }

    fun deleteRoutine(routineId: String) {
        viewModelScope.launch(dispatchers.io) {
            routineScheduler.cancel(routineId)
            routineRepository.delete(routineId)
            _events.tryEmit(AutomationEvent.ShowMessage("Routine deleted"))
        }
    }

    fun saveRoutine(
        id: String?,
        name: String,
        goal: String,
        scheduleType: RoutineScheduleType,
        cronOrInterval: String,
    ) {
        viewModelScope.launch(dispatchers.io) {
            val now = System.currentTimeMillis()
            val nextRun = if (scheduleType == RoutineScheduleType.RECURRING) {
                RoutineScheduler.calculateNextRunTime(scheduleType, cronOrInterval, now)
            } else {
                cronOrInterval.toLongOrNull() ?: (now + 3600_000L)
            }

            val routine = if (id != null) {
                val existing = routineRepository.get(id)
                existing?.copy(
                    name = name.trim(),
                    goal = goal.trim(),
                    scheduleType = scheduleType,
                    cronOrInterval = cronOrInterval.trim(),
                    nextRunAt = nextRun,
                    updatedAt = now,
                ) ?: Routine(
                    id = id,
                    name = name.trim(),
                    goal = goal.trim(),
                    scheduleType = scheduleType,
                    cronOrInterval = cronOrInterval.trim(),
                    nextRunAt = nextRun,
                )
            } else {
                Routine(
                    id = UUID.randomUUID().toString(),
                    name = name.trim(),
                    goal = goal.trim(),
                    scheduleType = scheduleType,
                    cronOrInterval = cronOrInterval.trim(),
                    nextRunAt = nextRun,
                )
            }
            routineRepository.upsert(routine)
            // Re-arm WorkManager when the new routine is enabled and recurring.
            if (routine.enabled && routine.scheduleType == RoutineScheduleType.RECURRING) {
                routineScheduler.setEnabled(routine.id, true)
            }
            _showRoutineDialog.value = false
            _editingRoutine.value = null
            _events.tryEmit(AutomationEvent.ShowMessage("Routine saved"))
        }
    }

    fun cancelTask(taskId: String) {
        viewModelScope.launch(dispatchers.io) {
            taskEngine.cancelTask(taskId)
            _events.tryEmit(AutomationEvent.ShowMessage("Task cancelled"))
        }
    }

    fun retryTask(taskId: String) {
        viewModelScope.launch(dispatchers.io) {
            taskEngine.retryTask(taskId)
            _events.tryEmit(AutomationEvent.ShowMessage("Task re-queued for retry"))
        }
    }

    fun deleteTask(taskId: String) {
        viewModelScope.launch(dispatchers.io) {
            taskRepository.delete(taskId)
            _events.tryEmit(AutomationEvent.ShowMessage("Task deleted"))
        }
    }
}
