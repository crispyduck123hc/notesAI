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
import com.example.notesai.auth.TokenStore
import com.example.notesai.auth.defaultOAuthConfig
import com.example.notesai.data.NoteRepository
import com.example.notesai.db.DatabaseDriverFactory
import com.example.notesai.drive.DriveClient
import com.example.notesai.sync.DriveRemote
import com.example.notesai.sync.NoteSyncEngine
import com.example.notesai.ui.LoginScreen
import com.example.notesai.ui.NotesScreen
import io.ktor.client.HttpClient
import kotlinx.coroutines.launch

/**
 * @param tokenStore platform credential storage — see `desktopTokenStore()`,
 *   `AndroidKeystoreTokenStore` and `KeychainTokenStore`.
 */
@Composable
fun App(driverFactory: DatabaseDriverFactory, tokenStore: TokenStore) {
    val repository = remember { NoteRepository(driverFactory) }
    val http = remember { HttpClient() }
    val auth = remember(http, tokenStore) {
        GoogleAuthManager(config = defaultOAuthConfig(), store = tokenStore, http = http)
    }
    val drive = remember(http, auth) { DriveClient(http, auth::accessToken) }
    val syncEngine = remember(repository, drive) { NoteSyncEngine(repository, DriveRemote(drive)) }
    val scope = rememberCoroutineScope()

    var account by remember { mutableStateOf<AccountInfo?>(null) }
    var restoring by remember { mutableStateOf(true) }
    var signingIn by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    var syncStatus by remember { mutableStateOf<String?>(null) }
    var syncing by remember { mutableStateOf(false) }

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
                syncStatus = syncStatus,
                syncing = syncing,
                onSync = {
                    syncing = true
                    syncStatus = null
                    scope.launch {
                        try {
                            syncStatus = syncNow(syncEngine, repository)
                        } catch (t: Throwable) {
                            syncStatus = t.message ?: "Sync failed"
                        } finally {
                            syncing = false
                        }
                    }
                },
                onSignOut = {
                    auth.signOut()
                    account = null
                    syncStatus = null
                },
            )
        }
    }
}

/**
 * Runs one push/pull pass and reports what happened. Sync is manual for now; a
 * background/on-focus trigger is the next step once the engine has been exercised.
 */
private suspend fun syncNow(engine: NoteSyncEngine, repository: NoteRepository): String {
    val result = engine.sync()
    val pending = repository.unresolvedConflicts().size

    return buildString {
        if (!result.changed && pending == 0) {
            append("Up to date")
        } else {
            append("Synced — pushed ${result.pushed}, pulled ${result.pulled}")
            if (result.conflictsResolved > 0) {
                append(", kept both for ${result.conflictsResolved} edit conflict(s)")
            }
        }
        if (pending > 0) append(". $pending conflict(s) need a decision")
    }
}
