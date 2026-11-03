package com.example.notesai.ui.tree

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.FilterChip
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
import com.example.notesai.github.CreatedWithin
import com.example.notesai.model.NoteRef
import com.example.notesai.model.NoteTreeItem
import com.example.notesai.ui.theme.Dimens
import com.example.notesai.update.AvailableUpdate
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
    /** Non-null when a newer release has been published than the one running. */
    availableUpdate: AvailableUpdate?,
    gitHub: GitHubPaneState,
    onToggle: (Long) -> Unit,
    onSelectFolder: (Long) -> Unit,
    onSelectNote: (NoteTreeItem.Note) -> Unit,
    onSelectIssue: (NoteTreeItem.Issue) -> Unit,
    onDeleteFolder: (Long) -> Unit,
    onDeleteNote: (Long) -> Unit,
    onRenameFolder: (Long) -> Unit,
    onMoveNote: (Long) -> Unit,
    onCreateNote: () -> Unit,
    onCreateFolder: () -> Unit,
    onReviewConflicts: () -> Unit,
    onShowSyncError: () -> Unit,
    onOpenUpdate: () -> Unit,
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
            // The key comes from the row itself, so the rule lives beside the ids it protects rather
            // than here — several GitHub issue rows would otherwise share one id.
            items(tree, key = { it.key }) { item ->
                // One branch per kind, and no branch has to ask whether a row is really what it says:
                // the type is the answer. Adding a kind fails to compile here, which is the point.
                when (item) {
                    is NoteTreeItem.Folder ->
                        NoteTreeRow(
                            label = item.label,
                            depth = item.depth,
                            selected = item.id == selectedFolderId,
                            expanded = item.expanded.takeIf { item.expandable },
                            onClick = {
                                // Selecting makes the folder the target for new notes; expanding is what
                                // clicking a folder in a tree is expected to do. The chevron reports both.
                                onSelectFolder(item.id)
                                if (item.expandable) onToggle(item.id)
                            },
                            // The root is the anchor for everything else, so it cannot be deleted —
                            // but naming it is still the user's business.
                            onDelete = if (item.isRoot) null else ({ onDeleteFolder(item.id) }),
                            deleteLabel = "Delete folder",
                            onRename = ({ onRenameFolder(item.id) }),
                        )

                    is NoteTreeItem.Repository ->
                        NoteTreeRow(
                            label = item.label,
                            depth = item.depth,
                            selected = item.nodeId == selectedFolderId,
                            expanded = item.expanded.takeIf { item.expandable },
                            onClick = {
                                // Selecting the node is what shows its link/pull/push actions below the
                                // tree, so a click has to do both rather than trade one for the other.
                                onSelectFolder(item.nodeId)
                                if (item.expandable) onToggle(item.nodeId)
                            },
                            // A repository is unlinked from its action row, never deleted like a folder.
                        )

                    is NoteTreeItem.Note ->
                        NoteTreeRow(
                            label = item.label,
                            depth = item.depth,
                            selected = selectedRef == item.ref,
                            onClick = { onSelectNote(item) },
                            onDelete = ({ onDeleteNote(item.id) }),
                            deleteLabel = "Delete note",
                            onMove = ({ onMoveNote(item.id) }),
                        )

                    is NoteTreeItem.Issue ->
                        NoteTreeRow(
                            label = item.label,
                            depth = item.depth,
                            selected = selectedRef == item.ref,
                            onClick = { onSelectIssue(item) },
                            badge = item.badge,
                            unsaved = item.unsaved,
                            // No delete: an issue is closed on GitHub, never removed from here.
                            onComplete = ({ gitHub.onToggleComplete(item.repoId, item.number, !item.isClosed) }),
                            completeLabel = if (item.isClosed) "Reopen" else "Mark complete",
                        )
                }
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

        // Who you are signed in as, spelled out. The address gets its own line rather than sharing one
        // with the Sign out button, where a long address was being ellipsised away to nothing useful.
        Text(
            text = "Signed in as",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            // A sign-in saved before the address could be read has none stored — say what fixes it
            // rather than looking like a bug.
            text = accountEmail ?: "(sign in again to show your address)",
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
        TextButton(onClick = onSignOut, modifier = Modifier.fillMaxWidth()) {
            Text("Sign out")
        }

        if (availableUpdate != null) {
            // Replaces the version line rather than sitting beside it: the useful fact when there is
            // something to install is that something exists, and the build number is still named on
            // the line below so a bug report can quote it.
            TextButton(
                onClick = onOpenUpdate,
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = Dimens.Gutter, vertical = 0.dp),
            ) {
                Text(
                    text = "Update available: ${availableUpdate.tag}",
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = "you have $APP_VERSION",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
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

    // Two state chips, independent of each other: both on is "everything", one on is a filter, neither is
    // "nothing" — which the tree then shows as an unexpandable node rather than a silent empty list.
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        FilterChip(
            selected = gitHub.filter.showOpen,
            onClick = gitHub.onFilterOpen,
            label = { Text("Open ${gitHub.openCount}") },
        )
        Spacer(modifier = Modifier.width(4.dp))
        FilterChip(
            selected = gitHub.filter.showClosed,
            onClick = gitHub.onFilterClosed,
            label = { Text("Closed ${gitHub.closedCount}") },
        )
    }

    // A second row: the date dimension. Short labels because the pane is 280dp wide on a phone.
    FlowRow(modifier = Modifier.fillMaxWidth()) {
        CreatedWithin.entries.forEach { range ->
            FilterChip(
                selected = gitHub.filter.createdWithin == range,
                onClick = { gitHub.onCreatedWithin(range) },
                label = { Text("Opened ${range.label}") },
                modifier = Modifier.padding(end = 4.dp),
            )
        }
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
        OutlinedButton(onClick = { gitHub.onNewIssue(repo.id) }, enabled = !gitHub.busy) {
            Text("New issue\u2026")
        }
        Spacer(modifier = Modifier.width(4.dp))
        TextButton(onClick = { gitHub.onForcePull(repo.id) }, enabled = !gitHub.busy) {
            Text("Force pull\u2026")
        }
    }
    TextButton(onClick = { gitHub.onUnlink(repo.id) }, enabled = !gitHub.busy) {
        Text("Unlink")
    }
}
