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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalWindowInfo
import com.example.notesai.auth.AccountInfo
import com.example.notesai.auth.GitHubAuthManager
import com.example.notesai.auth.GoogleAuthManager
import com.example.notesai.auth.TokenStore
import com.example.notesai.auth.defaultGitHubOAuthConfig
import com.example.notesai.auth.defaultOAuthConfig
import com.example.notesai.data.LEGACY_ACCOUNT_SCOPE
import com.example.notesai.data.NoteRepository
import com.example.notesai.data.accountDatabaseName
import com.example.notesai.db.DatabaseDriverFactory
import com.example.notesai.drive.DriveClient
import com.example.notesai.github.GitHubClient
import com.example.notesai.github.GitHubIssueRepository
import com.example.notesai.sync.DriveRemote
import com.example.notesai.sync.NoteSyncEngine
import com.example.notesai.sync.SyncWakeup
import com.example.notesai.ui.LoginScreen
import com.example.notesai.ui.notes.NotesScreen
import com.example.notesai.ui.notes.SyncUiState
import com.example.notesai.ui.openInBrowser
import com.example.notesai.ui.theme.NotesAiTheme
import com.example.notesai.update.AvailableUpdate
import com.example.notesai.update.UpdateChecker
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/**
 * How long a record must have been quiet before an automatic sync publishes it. A burst of edits — or simply typing
 * in a note — then coalesces into a single push instead of one per pause, and two devices are far less likely to be
 * writing the same record at the same moment. Nothing is lost meanwhile: the change sits in the durable outbox.
 *
 * Ten seconds, not a minute. The window only needs to outlast a pause in typing (people pause for 2-10s mid-sentence,
 * and the editor's own write debounce is 300ms), and it is now the dominant term in delivery latency: a push cannot
 * happen until the window has passed. For comparison, y-websocket, the reference Yjs sync provider, defaults its
 * callback debounce to 2000ms with a 10s maximum wait.
 */
private const val PUSH_SETTLE_MILLIS = 10_000L

/**
 * Publish a record after this long even if it never went quiet. Continuous typing moves the settle window's clock
 * (`enqueuedAt`) with every edit, so without a maximum wait a note being typed into for an hour would never leave the
 * device. Ten seconds means "at most one upload per ten seconds of continuous writing".
 */
private const val PUSH_MAX_WAIT_MILLIS = 10_000L

/**
 * How long a wake-up waits before starting a pass, so a burst of queue changes is one pass rather than several.
 *
 * Deliberately shorter than the settle window: the first pass of a burst is there to pull and to publish anything
 * already stale, so making the caller wait a full window before even looking would defeat the point.
 */
private const val SYNC_WAKE_QUIET_MILLIS = 1_500L

/** How often to look for changes made on other devices while the app is open. */
private const val PERIODIC_SYNC_MILLIS = 60_000L

/**
 * How long one HTTP request may take before it is abandoned.
 *
 * Explicit rather than the library's defaults, because the shutdown flush has a ceiling of its own and it has
 * to be longer than this: a flush should be cancelled *between* requests, never in the middle of an upload the
 * server may already have accepted.
 */
private const val REQUEST_TIMEOUT_MILLIS = 10_000L

private const val ACCOUNT_MISMATCH_MESSAGE =
    "Sync paused: this database belongs to a different account. Sign out and back in."

private const val REAUTHORIZATION_MESSAGE =
    "The saved Google sign-in does not include Google Drive access, so syncing cannot work. " +
        "Use \"Sign out\" in the sidebar and sign in again to grant it. Your notes are safe " +
        "on this device."

@Composable
fun App(
    driverFactory: DatabaseDriverFactory,
    tokenStore: TokenStore,
    /**
     * GitHub keeps its own credential slot: the two providers are independent, so signing out of one
     * must not disturb the other.
     */
    gitHubTokenStore: TokenStore,
    // Desktop supplies a remembered instance, so its window-close handler can commit the open editor's unsaved
    // text. Mobile has no interceptable quit, so the default is never asked to commit anything there.
    pendingEdit: PendingEdit = remember { PendingEdit() },
) {
    // Explicit timeouts rather than the defaults: a request that never returns holds a sync pass open forever, and
    // hangs are the failure mode that looks like "sync does nothing". Ten seconds is long enough for a small JSON
    // file on a poor connection and short enough that a stalled pass is retried rather than waited on.
    val http =
        remember {
            HttpClient {
                install(HttpTimeout) {
                    requestTimeoutMillis = REQUEST_TIMEOUT_MILLIS
                    connectTimeoutMillis = REQUEST_TIMEOUT_MILLIS
                    socketTimeoutMillis = REQUEST_TIMEOUT_MILLIS
                }
            }
        }
    val auth =
        remember(http, tokenStore) {
            GoogleAuthManager(config = defaultOAuthConfig(), store = tokenStore, http = http)
        }
    val gitHubAuth =
        remember(http, gitHubTokenStore) {
            GitHubAuthManager(config = defaultGitHubOAuthConfig(), store = gitHubTokenStore, http = http)
        }

    var account by remember { mutableStateOf<AccountInfo?>(null) }
    var gitHubLogin by remember { mutableStateOf<String?>(null) }
    var restoring by remember { mutableStateOf(true) }
    var signingIn by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(auth) {
        account = auth.restore()
        restoring = false
    }

    // GitHub is restored independently: it is an optional extra, not part of signing in.
    LaunchedEffect(gitHubAuth) {
        gitHubLogin = gitHubAuth.restore()?.login
    }

    NotesAiTheme {
        when {
            restoring ->
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }

            account == null ->
                LoginScreen(
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

            else ->
                AccountNotes(
                    account = account!!,
                    driverFactory = driverFactory,
                    http = http,
                    accessToken = auth::accessToken,
                    pendingEdit = pendingEdit,
                    gitHubAuth = gitHubAuth,
                    gitHubLogin = gitHubLogin,
                    onConnectGitHub = {
                        gitHubAuth.signIn()
                        gitHubLogin = gitHubAuth.restore()?.login
                    },
                    // A sign-in that looks unable to sync is mentioned up front, but the user is
                    // never signed out over it: this comparison has been wrong before, and the
                    // consequence of guessing wrong must not be losing access to your own notes.
                    // Any real failure still arrives with the same advice, from Google itself.
                    signInWarning = if (auth.needsReauthorization()) REAUTHORIZATION_MESSAGE else null,
                    onSignOut = {
                        // Signing out of the account also disconnects GitHub: the GitHub links live in
                        // this account's database, so leaving a token behind for the next account
                        // would show them someone else's repositories.
                        gitHubAuth.signOut()
                        gitHubLogin = null
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
    pendingEdit: PendingEdit,
    gitHubAuth: GitHubAuthManager,
    gitHubLogin: String?,
    onConnectGitHub: suspend () -> Unit,
    signInWarning: String?,
    onSignOut: () -> Unit,
) {
    val scopeName = remember(account) { accountDatabaseName(account.email) }

    // One driver for the whole account, shared by the note repository and the GitHub mirror. Two
    // SQLite connections to one file would work, but two views of the same database is a needless
    // way to get surprises.
    val driver = remember(scopeName) { driverFactory.createDriver(scopeName) }
    val repository =
        remember(driver) {
            NoteRepository(driver).also { repository ->
                when (repository.storedAccountScope()) {
                    null -> repository.recordAccountScope(scopeName)
                    // A database from before the account's address could be read: the file has been
                    // renamed to this account's name, so adopt that name rather than pausing sync as
                    // though it belonged to someone else.
                    LEGACY_ACCOUNT_SCOPE -> repository.upgradeAccountScope(scopeName)
                    else -> Unit
                }
            }
        }

    // Tripwire: the database is named after the account, so a mismatch means something is
    // badly wrong. Refuse to sync rather than merge two accounts' notes.
    val accountMismatch = remember(scopeName) { repository.storedAccountScope() != scopeName }

    val gitHubClient =
        remember(http, gitHubAuth) {
            GitHubClient(http = http, accessToken = gitHubAuth::accessToken)
        }
    val gitHubRepository = remember(driver, gitHubClient) { GitHubIssueRepository(driver, gitHubClient) }

    // One request per launch, and nothing is installed automatically: the result becomes a line in
    // the sidebar and a link to the release page. Deliberately not tied to the account — whether a
    // newer build exists is a property of the app, not of whose notes are open.
    val updateChecker = remember(http) { UpdateChecker(http = http) }
    var availableUpdate by remember { mutableStateOf<AvailableUpdate?>(null) }
    LaunchedEffect(updateChecker) {
        availableUpdate = updateChecker.availableUpdate()
    }

    val drive = remember(http, accessToken) { DriveClient(http, accessToken) }
    val syncEngine = remember(repository, drive) { NoteSyncEngine(repository, DriveRemote(drive)) }
    val scope = rememberCoroutineScope()

    // One value for the sidebar, so it cannot say "syncing" and show an error at the same time.
    // Starts as the sign-in warning so the guidance is available immediately; the first sync then replaces it,
    // clearing it on success. Requests are the authority, not this.
    var syncState by remember(scopeName) {
        mutableStateOf(
            if (signInWarning != null) SyncUiState.Failed(signInWarning) else SyncUiState.Idle,
        )
    }

    suspend fun runSync(pushSettleMillis: Long = PUSH_SETTLE_MILLIS) {
        syncState = SyncUiState.Running
        val next =
            try {
                if (accountMismatch) {
                    SyncUiState.Failed(ACCOUNT_MISMATCH_MESSAGE)
                } else {
                    val outcome = describeSync(syncEngine, repository, pushSettleMillis, PUSH_MAX_WAIT_MILLIS)
                    if (outcome.error == null) {
                        SyncUiState.Done(outcome.summary)
                    } else {
                        SyncUiState.Failed(summary = outcome.summary, detail = outcome.error)
                    }
                }
            } catch (cancellation: CancellationException) {
                // The screen was torn down (sign-out, account switch, window closed) while the pass was running.
                // Leaving "Syncing..." would be the only trace, and it would be a lie: nothing failed.
                if (syncState == SyncUiState.Running) syncState = SyncUiState.Idle
                throw cancellation
            }
        syncState = next
    }

    // The exit path writes through the editor, never through this screen: by the time a quit is in progress the
    // composition is being torn down, so all the screen owns here is closing the connection.
    DisposableEffect(scopeName) {
        onDispose { repository.close() }
    }

    // On sign-in and on cold start, so a fresh device populates itself immediately. This one
    // pushes regardless of the settle window: anything still queued is from a previous
    // session, not someone mid-sentence.
    LaunchedEffect(scopeName) { runSync(pushSettleMillis = 0L) }

    // Returning to the window is the natural moment to catch up, and it replaces the explicit
    // sync button. It respects the settle window so alt-tabbing doesn't spam uploads.
    // Collected rather than keyed on the focus value: `LaunchedEffect(windowFocused)` is cancelled and restarted
    // whenever the window gains or loses focus, which cancels a pass in flight and reports it as a failure
    // ("The coroutine scope left the composition"). Collecting keeps one long-lived effect, so a pass finishes.
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(scopeName) {
        if (accountMismatch) return@LaunchedEffect
        snapshotFlow { windowFocused }
            .distinctUntilChanged()
            .filter { it }
            .collect { runSync() }
    }

    // A pass is also started by the queue becoming non-empty. This is an additional trigger, not a bypass: the settle
    // window and the maximum wait still decide what a pass may publish, and this only decides when to look. It cannot
    // replace the tick below, because the outbox says nothing about other devices' writes.
    LaunchedEffect(scopeName, syncEngine) {
        if (accountMismatch) return@LaunchedEffect
        SyncWakeup(settleMillis = PUSH_SETTLE_MILLIS, quietMillis = SYNC_WAKE_QUIET_MILLIS)
            .wakes(repository.outboxCount)
            .collect { runSync() }
    }

    // A slow tick: picks up other devices' work, and eventually pushes the backlog.
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
        sync = syncState,
        gitHubRepository = gitHubRepository,
        gitHubLogin = gitHubLogin,
        availableUpdate = availableUpdate,
        onOpenUpdate = { update ->
            // The release page, where the install instructions live. Opening a browser is the one
            // action that does nothing on mobile — the message is still useful there, the click is
            // not, which is the same trade-off the GitHub links already make.
            openInBrowser(update.releaseUrl)
        },
        onConnectGitHub = onConnectGitHub,
        onRetrySync = { scope.launch { runSync(pushSettleMillis = 0L) } },
        onResolveConflict = { id, keepLocal ->
            scope.launch {
                try {
                    syncEngine.resolveConflict(id, keepLocal)
                    // Reconcile straight away so the resolution reaches the remote.
                    runSync()
                } catch (t: Throwable) {
                    syncState = SyncUiState.Failed(t.message ?: "Could not resolve conflict")
                }
            }
        },
        onSignOut = onSignOut,
        pendingEdit = pendingEdit,
    )
}

/**
 * One pass, reduced to what the UI shows: a short status line, plus the full text of a failure
 * when there was one. The two are kept apart because they belong in different places — the
 * line goes in the sidebar, the detail goes in a dialog the reader can copy from.
 */
internal class SyncOutcome(
    val summary: String,
    val error: String? = null,
)

/**
 * Runs one push/pull pass and describes what happened, including anything that needs a human
 * decision. Failures are reported rather than thrown, so a network problem doesn't take the
 * UI down.
 */
internal suspend fun describeSync(
    engine: NoteSyncEngine,
    repository: NoteRepository,
    pushSettleMillis: Long,
    pushMaxWaitMillis: Long,
): SyncOutcome {
    val result =
        try {
            engine.sync(pushSettleMillis = pushSettleMillis, pushMaxWaitMillis = pushMaxWaitMillis)
        } catch (cancellation: CancellationException) {
            // Not a failure: the caller's scope ended. Must propagate, or a cancelled pass is shown to the user as
            // "Sync failed: The coroutine scope left the composition" — the message Compose uses for exactly this.
            throw cancellation
        } catch (t: Throwable) {
            val detail = t.message ?: t.toString()
            // Also to stdout: the sidebar says "Sync failed", and whoever is debugging wants the
            // whole thing without clicking anything.
            println("[notesAI] sync failed: $detail")
            return SyncOutcome(summary = "Sync failed", error = detail)
        }
    val pending = repository.unresolvedConflicts().size
    val queued = repository.pendingOutbox().size

    val summary =
        buildString {
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
