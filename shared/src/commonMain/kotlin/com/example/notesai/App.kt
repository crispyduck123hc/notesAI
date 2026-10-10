package com.example.notesai

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalWindowInfo
import com.example.notesai.auth.AccountInfo
import com.example.notesai.auth.GoogleAuthManager
import com.example.notesai.auth.TokenStore
import com.example.notesai.auth.defaultOAuthConfig
import com.example.notesai.data.NoteRepository
import com.example.notesai.data.accountDatabaseName
import com.example.notesai.db.DatabaseDriverFactory
import com.example.notesai.drive.DriveClient
import com.example.notesai.sync.DriveRemote
import com.example.notesai.sync.NoteSyncEngine
import com.example.notesai.ui.LoginScreen
import com.example.notesai.ui.NotesScreen
import com.example.notesai.ui.theme.NotesAiTheme
import io.ktor.client.HttpClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Automatic syncs hold local changes back this long. A burst of edits — or simply typing in a
 * note — then coalesces into a single push instead of one per pause, and two devices are far
 * less likely to be writing the same record at the same moment. Nothing is lost meanwhile:
 * the change sits in the durable outbox.
 */
private const val PUSH_SETTLE_MILLIS = 1 * 60_000L

/** How often to look for changes made on other devices while the app is open. */
private const val PERIODIC_SYNC_MILLIS = 60_000L

private const val ACCOUNT_MISMATCH_MESSAGE =
    "Sync paused: this database belongs to a different account. Sign out and back in."

private const val REAUTHORIZATION_MESSAGE =
    "Your Google sign-in no longer covers Google Drive, so syncing stopped. " +
        "Please sign in again — your notes are still safe on this device."

@Composable
fun App(
    driverFactory: DatabaseDriverFactory,
    tokenStore: TokenStore,
    // Desktop supplies a remembered instance so its window-close handler can drain the queue.
    // Mobile has no equivalent hook, so the default is simply never used there.
    exitFlush: ExitFlush = remember { ExitFlush() },
) {
    val http = remember { HttpClient() }
    val auth = remember(http, tokenStore) {
        GoogleAuthManager(config = defaultOAuthConfig(), store = tokenStore, http = http)
    }

    var account by remember { mutableStateOf<AccountInfo?>(null) }
    var restoring by remember { mutableStateOf(true) }
    var signingIn by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(auth) {
        // A saved grant that cannot sync is worse than no saved grant: the app looks signed in
        // while every upload fails. Clearing it puts the user back on the login screen with a
        // reason, and for a scope problem that is the fix rather than a workaround.
        if (auth.needsReauthorization()) {
            auth.signOut()
            error = REAUTHORIZATION_MESSAGE
        }
        account = auth.restore()
        restoring = false
    }

    NotesAiTheme {
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

            else -> AccountNotes(
                account = account!!,
                driverFactory = driverFactory,
                http = http,
                accessToken = auth::accessToken,
                exitFlush = exitFlush,
                onSignOut = {
                    auth.signOut()
                    account = null
                },
            )
        }
    }
}

/**
 * Everything that needs a local store. Keyed on the account, so signing in as someone else
 * opens an entirely separate database rather than showing them the previous account's notes.
 */
@Composable
private fun AccountNotes(
    account: AccountInfo,
    driverFactory: DatabaseDriverFactory,
    http: HttpClient,
    accessToken: suspend () -> String?,
    exitFlush: ExitFlush,
    onSignOut: () -> Unit,
) {
    val scopeName = remember(account) { accountDatabaseName(account.email) }

    val repository = remember(scopeName) {
        NoteRepository(driverFactory, scopeName).also { it.recordAccountScope(scopeName) }
    }

    // Tripwire: the database is named after the account, so a mismatch means something is
    // badly wrong. Refuse to sync rather than merge two accounts' notes.
    val accountMismatch = remember(scopeName) { repository.storedAccountScope() != scopeName }

    val drive = remember(http, accessToken) { DriveClient(http, accessToken) }
    val syncEngine = remember(repository, drive) { NoteSyncEngine(repository, DriveRemote(drive)) }
    val scope = rememberCoroutineScope()

    var syncStatus by remember(scopeName) { mutableStateOf<String?>(null) }
    var syncError by remember(scopeName) { mutableStateOf<String?>(null) }
    var syncing by remember(scopeName) { mutableStateOf(false) }

    suspend fun runSync(pushSettleMillis: Long = PUSH_SETTLE_MILLIS) {
        syncing = true
        if (accountMismatch) {
            syncStatus = ACCOUNT_MISMATCH_MESSAGE
            syncError = null
        } else {
            val outcome = describeSync(syncEngine, repository, pushSettleMillis)
            syncStatus = outcome.summary
            syncError = outcome.error
        }
        syncing = false
    }

    // Offer this account's outbox to the exit path. Unregistering before closing matters: the
    // flush touches the repository, and the process may be on its way out.
    DisposableEffect(scopeName) {
        exitFlush.register(
            pendingCount = { repository.pendingOutbox().size },
            flush = { runSync(pushSettleMillis = 0L) },
        )
        onDispose {
            exitFlush.unregister()
            repository.close()
        }
    }

    // On sign-in and on cold start, so a fresh device populates itself immediately. This one
    // pushes regardless of the settle window: anything still queued is from a previous
    // session, not someone mid-sentence.
    LaunchedEffect(scopeName) { runSync(pushSettleMillis = 0L) }

    // Returning to the window is the natural moment to catch up, and it replaces the explicit
    // sync button. It respects the settle window so alt-tabbing doesn't spam uploads.
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(windowFocused) {
        if (windowFocused && !accountMismatch) runSync()
    }

    // A slow tick: picks up other devices' work, and eventually pushes the backlog. There is
    // deliberately no per-edit trigger — with the settle window it would fire and do nothing.
    LaunchedEffect(scopeName) {
        if (accountMismatch) return@LaunchedEffect
        while (true) {
            delay(PERIODIC_SYNC_MILLIS)
            runSync()
        }
    }

    NotesScreen(
        repository = repository,
        account = account,
        syncStatus = syncStatus,
        syncError = syncError,
        syncing = syncing,
        onRetrySync = { scope.launch { runSync(pushSettleMillis = 0L) } },
        onResolveConflict = { id, keepLocal ->
            scope.launch {
                try {
                    syncEngine.resolveConflict(id, keepLocal)
                    // Reconcile straight away so the resolution reaches the remote.
                    runSync()
                } catch (t: Throwable) {
                    syncStatus = t.message ?: "Could not resolve conflict"
                }
            }
        },
        onSignOut = onSignOut,
    )
}

/**
 * One pass, reduced to what the UI shows: a short status line, plus the full text of a failure
 * when there was one. The two are kept apart because they belong in different places — the
 * line goes in the sidebar, the detail goes in a dialog the reader can copy from.
 */
private class SyncOutcome(val summary: String, val error: String? = null)

/**
 * Runs one push/pull pass and describes what happened, including anything that needs a human
 * decision. Failures are reported rather than thrown, so a network problem doesn't take the
 * UI down.
 */
private suspend fun describeSync(
    engine: NoteSyncEngine,
    repository: NoteRepository,
    pushSettleMillis: Long,
): SyncOutcome {
    val result = try {
        engine.sync(pushSettleMillis = pushSettleMillis)
    } catch (t: Throwable) {
        val detail = t.message ?: t.toString()
        // Also to stdout: the sidebar says "Sync failed", and whoever is debugging wants the
        // whole thing without clicking anything.
        println("[notesAI] sync failed: $detail")
        return SyncOutcome(summary = "Sync failed", error = detail)
    }
    val pending = repository.unresolvedConflicts().size
    val queued = repository.pendingOutbox().size

    val summary = buildString {
        if (!result.changed && pending == 0 && result.failed == 0 && queued == 0) {
            append("Up to date")
        } else {
            append("Synced — pushed ${result.pushed}, pulled ${result.pulled}")
            if (result.conflictsResolved > 0) {
                append(", kept both for ${result.conflictsResolved} edit conflict(s)")
            }
            if (result.purged > 0) append(", purged ${result.purged} old tombstone(s)")
            if (result.failed > 0) append(", ${result.failed} failed")
        }
        // Say so when a change is waiting on the settle window, otherwise "pushed 0" looks
        // like the edit was ignored.
        if (queued > 0) append(". $queued change(s) waiting to upload")
        if (pending > 0) append(". $pending conflict(s) need a decision")
    }

    return SyncOutcome(summary = summary)
}
