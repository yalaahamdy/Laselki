package com.wavetalk.app.di

import android.content.Context
import com.wavetalk.app.session.TalkEngine
import com.wavetalk.app.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Hand-rolled dependency container — deliberately tiny and transparent.
 */
class AppContainer(context: Context) {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settingsRepository: SettingsRepository = SettingsRepository(context)

    val talkEngine: TalkEngine by lazy {
        TalkEngine(context.applicationContext, settingsRepository)
    }
}
