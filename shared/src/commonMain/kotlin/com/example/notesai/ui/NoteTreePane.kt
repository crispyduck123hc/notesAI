package com.example.notesai.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.notesai.APP_VERSION
import com.example.notesai.ui.theme.Dimens
import kotlinx.collections.immutable.ImmutableList

/** Left pane: creation buttons, the flattened note tree, sync state and the account. */
@Composable
internal fun NoteTreePane(
    tree: ImmutableList<NoteTreeItem>,
    selectedFolderId: Long,
    selectedRef: NoteRef?,
    accountEmail: String?,
    syncStatus: String?,
    hasSyncError: Boolean,
    syncing: Boolean,
    conflictCount: Int,
    gitHub: GitHubPaneState,
    onToggle: (Long) -> Unit,
    onSelectFolder: (Long) -> Unit,
    onSelectNote: (NoteTreeItem) -> Unit,
    onDeleteFolder: (Long) -> Unit,
    onDeleteNote: (Long) -> Unit,
    onRenameFolder: (Long) -> Unit,
    onMoveNote: (Long) -> Unit,
    onCreateNote: () -> Unit,
    onCreateFolder: () -> Unit,
    onReviewConflicts: () -> Unit,
    onShowSyncError: () -> Unit,
    onSignOut: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .width(Dimens.TreePaneWidth)
                .fillMaxHeight()
                .padding(Dimens.Gutter),
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Button(onClick = onCreateNote, modifier = Modifier.weight(1f)) {
                Text("New Note")
            }
            Spacer(modifier = Modifier.width(4.dp))
            OutlinedButton(onClick = onCreateFolder, modifier = Modifier.weight(1f)) {
                Text("New Folder")
            }
        }

        Spacer(modifier = Modifier.height(Dimens.Gutter))

        LazyColumn(modifier = Modifier.weight(1f)) {
            // Keyed on the ref for note rows: several GitHub issue rows share the unused local id 0,
            // and duplicate keys would confuse the list.
            items(tree, key = { it.ref?.toString() ?: "f${it.id}" }) { item ->
                val gitHubIssue = item.ref as? NoteRef.GitHubIssue
                val localRef = item.ref as? NoteRef.Local
                val isLocalRow = item.source == NoteSource.LOCAL

                NoteTreeRow(
                    item = item,
                    selected =
                        if (item.isFolder) {
                            item.id == selectedFolderId
                        } else {
                            item.ref == selectedRef
                        },
                    onToggle = { onToggle(item.id) },
                    onClick = { if (item.isFolder) onSelectFolder(item.id) else onSelectNote(item) },
                    onDelete = {
                        when {
                            item.isFolder -> onDeleteFolder(item.id)
                            localRef != null -> onDeleteNote(localRef.id)
                        }
                    },
                    onRename = if (item.isFolder && isLocalRow) ({ onRenameFolder(item.id) }) else null,
                    onMove = if (localRef != null) ({ onMoveNote(localRef.id) }) else null,
                    // A linked repository is unlinked from its action row, not deleted like a folder.
                    deletable = isLocalRow && !(item.isFolder && item.depth == 0),
                    badge = item.badge,
                    onComplete =
                        gitHubIssue?.let { issue ->
                            ({ gitHub.onToggleComplete(issue.repoId, issue.number, item.badge != "Closed") })
                        },
                    completeLabel =
                        if (gitHubIssue != null) {
                            if (item.badge == "Closed") "Reopen" else "Mark complete"
                        } else {
                            null
                        },
                )
            }
        }

        Spacer(modifier = Modifier.height(Dimens.Gutter))
        GitHubSection(gitHub)

        Spacer(modifier = Modifier.height(Dimens.Gutter))
        HorizontalDivider()

        // Sync state. There is deliberately no sync button: the app syncs on start, when the
        // window regains focus, and on a slow tick — this is a report, not a control.
        Text(
            text =
                when {
                    syncing -> "Syncing\u2026"
                    syncStatus != null -> syncStatus
                    else -> "Not synced yet"
                },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )

        if (hasSyncError) {
            TextButton(
                onClick = onShowSyncError,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "Sync problem \u2014 what went wrong?",
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        if (conflictCount > 0) {
            TextButton(
                onClick = onReviewConflicts,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text =
                        if (conflictCount ==
                            1
                        ) {
                            "1 conflict needs a decision"
                        } else {
                            "$conflictCount conflicts need a decision"
                        },
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        HorizontalDivider()
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = accountEmail ?: "Signed in",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onSignOut) {
                Text("Sign out")
            }
        }

        // The build people are actually running, so a bug report can name it.
        Text(
            text = "notesAI $APP_VERSION",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * The GitHub folder's controls.
 *
 * The actions belong to a *repository*, not to the app, so they appear when that repository's node is
 * selected rather than as buttons on every row. Pull and Force pull are separate on purpose: one keeps
 * unpushed local edits, the other deliberately discards them.
 */
@Composable
private fun GitHubSection(gitHub: GitHubPaneState) {
    if (gitHub.login == null) {
        TextButton(
            onClick = gitHub.onConnect,
            modifier = Modifier.fillMaxWidth(),
            enabled = !gitHub.busy,
        ) {
            Text("Connect GitHub\u2026")
        }
        return
    }

    Text(
        text = "GitHub: ${gitHub.login}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth(),
    )

    TextButton(
        onClick = gitHub.onLink,
        modifier = Modifier.fillMaxWidth(),
        enabled = !gitHub.busy,
    ) {
        Text(if (gitHub.repos.isEmpty()) "Link a repository\u2026" else "Link another repository\u2026")
    }

    val repo = gitHub.selectedRepo ?: return
    Text(
        text = "${repo.owner}/${repo.name}",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth(),
    )
    Row(modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = { gitHub.onPull(repo.id) }, enabled = !gitHub.busy) {
            Text("Pull")
        }
        Spacer(modifier = Modifier.width(4.dp))
        OutlinedButton(onClick = { gitHub.onPush(repo.id) }, enabled = !gitHub.busy) {
            Text("Push")
        }
    }
    Row(modifier = Modifier.fillMaxWidth()) {
        TextButton(onClick = { gitHub.onForcePull(repo.id) }, enabled = !gitHub.busy) {
            Text("Force pull\u2026")
        }
        Spacer(modifier = Modifier.width(4.dp))
        TextButton(onClick = { gitHub.onUnlink(repo.id) }, enabled = !gitHub.busy) {
            Text("Unlink")
        }
    }
}
