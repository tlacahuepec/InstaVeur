package com.santi.metamediasaver

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

class MainActivity : ComponentActivity() {
    // replay = 1 so a cold-start deep link (handled in onCreate before Compose
    // subscribes) is still delivered to the eventual collector. Without replay
    // the OAuth callback that launches the app from a killed state is lost.
    private val deepLinks = MutableSharedFlow<Uri>(replay = 1)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val appContainer = (application as MetaMediaSaverApplication).appContainer

        setContent {
            MetaMediaSaverApp(
                appContainer = appContainer,
                deepLinks = deepLinks,
            )
        }

        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent?.data?.let { deepLinks.tryEmit(it) }
    }
}

typealias DeepLinkFlow = SharedFlow<Uri>
