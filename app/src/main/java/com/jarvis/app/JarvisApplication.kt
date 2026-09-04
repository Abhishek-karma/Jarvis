package com.jarvis.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/** Root of the Hilt DI graph (02-ARCHITECTURE.md §2) — all :core:* bindings resolve from here. */
@HiltAndroidApp
class JarvisApplication : Application()
