package com.jarvis.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jarvis.core.common.DispatcherProvider
import com.jarvis.core.preferences.ThemeMode
import com.jarvis.core.preferences.UserPreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * App-shell ViewModel: surfaces the persisted theme mode so [MainActivity] can theme the
 * whole app from user preferences instead of only the system dark flag.
 */
@HiltViewModel
class MainViewModel
    @Inject
    constructor(
        userPreferences: UserPreferencesRepository,
        dispatchers: DispatcherProvider,
    ) : ViewModel() {
        private val _themeMode = MutableStateFlow(ThemeMode.SYSTEM)
        val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

        init {
            viewModelScope.launch(dispatchers.main) {
                userPreferences.themeMode.collect { mode -> _themeMode.value = mode }
            }
        }
    }
