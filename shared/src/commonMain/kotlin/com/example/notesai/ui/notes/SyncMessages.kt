package com.example.notesai.ui.notes

import com.example.notesai.github.GitHubPushResult

/** What a push did, in one sentence — including the part the user has to decide about. */
internal fun describePush(result: GitHubPushResult): String =
    buildString {
        append("Pushed ${result.pushed} issue(s)")
        if (result.changedRemotely.isNotEmpty()) {
            append(
                ". ${result.changedRemotely.size} changed on GitHub since you pulled " +
                    "(${result.changedRemotely.joinToString(", ") { "#$it" }}) and were left alone",
            )
        }
        if (result.failed.isNotEmpty()) {
            append(". ${result.failed.size} failed: ${result.failed.first().second}")
        }
    }
