package com.jarvis.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/** Root of the Hilt DI graph — all :core:* bindings resolve from here. */
@HiltAndroidApp
class JarvisApplication : Application()
