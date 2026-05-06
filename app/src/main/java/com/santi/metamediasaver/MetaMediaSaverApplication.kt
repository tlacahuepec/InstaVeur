package com.santi.metamediasaver

import android.app.Application

class MetaMediaSaverApplication : Application() {
    lateinit var appContainer: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        appContainer = AppContainer(this)
    }
}
