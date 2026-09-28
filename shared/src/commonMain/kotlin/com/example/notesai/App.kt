package com.example.notesai

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.example.notesai.auth.AccountInfo
import com.example.notesai.auth.GoogleAuthManager
import com.example.notesai.auth.MetadataTokenStore
import com.example.notesai.auth.defaultOAuthConfig
import com.example.notesai.data.NoteRepository
import com.example.notesai.db.DatabaseDriverFactory
import com.example.notesai.ui.LoginScreen
import com.example.notesai.ui.NotesScreen
import kotlinx.coroutines.launch

@Composable
fun App(driverFactory: DatabaseDriverFactory) {
    val repository = remember { NoteRepository(driverFactory) }
    val auth = remember(repository) {
        GoogleAuthManager(
            config = defaultOAuthConfig(),
            store = MetadataTokenStore(
                read = repository::metadata,
                write = repository::putMetadata,
                remove = repository::removeMetadata,
            ),
        )
    }
    val scope = rememberCoroutineScope()

    var account by remember { mutableStateOf<AccountInfo?>(null) }
    var restoring by remember { mutableStateOf(true) }
    var signingIn by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(auth) {
        account = auth.restore()
        restoring = false
    }

    MaterialTheme {
        when {
            restoring -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }

            account == null -> LoginScreen(
                isSigningIn = signingIn,
                error = error,
                onSignIn = {
                    signingIn = true
                    error = null
                    scope.launch {
                        try {
                            account = auth.signIn()
                        } catch (t: Throwable) {
                            error = t.message ?: "Sign-in failed"
                        } finally {
                            signingIn = false
                        }
                    }
                },
            )

            else -> NotesScreen(
                repository = repository,
                account = account!!,
                onSignOut = {
                    auth.signOut()
                    account = null
                },
            )
        }
    }
}
