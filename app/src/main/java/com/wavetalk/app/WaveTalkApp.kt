package com.wavetalk.app

import android.app.Application
import com.wavetalk.app.di.AppContainer
import kotlinx.coroutines.launch

class WaveTalkApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Bring the engine up as early as possible so discovery is warm
        // before the user reaches the walkie screen.
        container.appScope.launch { container.talkEngine.start() }
    }
}
