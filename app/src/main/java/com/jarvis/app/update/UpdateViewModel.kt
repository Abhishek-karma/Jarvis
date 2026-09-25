package com.jarvis.app.update

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jarvis.app.BuildConfig
import com.jarvis.core.common.DispatcherProvider
import com.jarvis.core.network.update.UpdateChecker
import com.jarvis.core.network.update.UpdateCheckResult
import com.jarvis.core.preferences.UserPreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Drives the throttled startup update check + "update available" notification. */
@HiltViewModel
class UpdateViewModel
    @Inject
    constructor(
        private val updateChecker: UpdateChecker,
        private val notifier: UpdateNotifier,
        private val prefs: UserPreferencesRepository,
        dispatchers: DispatcherProvider,
    ) : ViewModel() {

        init {
            viewModelScope.launch(dispatchers.io) {
                val last = prefs.lastUpdateCheckMs.first()
                if (System.currentTimeMillis() - last >= CHECK_INTERVAL_MS) {
                    runCheck(notify = true)
                }
            }
        }

        private suspend fun runCheck(notify: Boolean) {
            when (val result = updateChecker.check(BuildConfig.VERSION_NAME)) {
                is UpdateCheckResult.UpdateAvailable -> {
                    prefs.markUpdateChecked()
                    if (notify) notifier.notifyUpdate(result.latestVersion, result.apkUrl)
                }
                UpdateCheckResult.UpToDate -> prefs.markUpdateChecked()
                UpdateCheckResult.Unavailable -> Unit
            }
        }

        companion object {
            /** Daily throttle for the passive startup check. */
            const val CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L
        }
    }
