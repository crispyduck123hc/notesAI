package com.example.notesai.ui.dialogs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.notesai.github.GitHubRepo

/** A repository the app can reach, with the installation that grants it. */
internal data class LinkableRepo(
    val installationId: Long,
    val repo: GitHubRepo,
) {
    val label: String get() = repo.fullName.ifBlank { repo.name }
}

/**
 * Picks a repository to link.
 *
 * The list comes from the app's installations, so it only contains repositories the GitHub App was
 * actually granted — installed by the user, intersected with their own access, and filtered to those
 * they can push to. Nothing here can grant access; that happens on GitHub, which is why the empty case
 * points at the installation page.
 */
@Composable
internal fun LinkRepoDialog(
    repos: List<LinkableRepo>,
    loading: Boolean,
    error: String?,
    onSelect: (LinkableRepo) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Link a GitHub repository") },
        text = {
            Column(
                modifier =
                    Modifier
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
            ) {
                when {
                    loading -> Text("Looking for repositories\u2026")

                    error != null -> Text(error, color = MaterialTheme.colorScheme.error)

                    repos.isEmpty() ->
                        Text(
                            "No repositories available. The GitHub App has to be installed on a " +
                                "repository first, and you need permission to push to it.",
                        )

                    else ->
                        repos.forEach { linkable ->
                            Text(
                                text = linkable.label,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable { onSelect(linkable) },
                            )
                        }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
