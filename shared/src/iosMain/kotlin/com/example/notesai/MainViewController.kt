package com.example.notesai

import androidx.compose.ui.window.ComposeUIViewController
import com.example.notesai.auth.KeychainTokenStore
import com.example.notesai.db.DatabaseDriverFactory

fun MainViewController() =
    ComposeUIViewController {
        val driverFactory = DatabaseDriverFactory()
        App(
            driverFactory = driverFactory,
            tokenStore = KeychainTokenStore(),
            // A separate Keychain item, so the two providers sign in and out independently.
            gitHubTokenStore = KeychainTokenStore(account = "github"),
            // The AI grant is consented on demand and stored separately, like the GitHub one.
            aiTokenStore = KeychainTokenStore(account = "ai"),
        )
    }
