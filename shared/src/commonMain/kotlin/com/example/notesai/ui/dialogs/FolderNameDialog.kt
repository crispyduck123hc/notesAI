package com.example.notesai.ui.dialogs

import androidx.compose.runtime.Composable

/** Names a folder — used for both creating and renaming. */
@Composable
fun FolderNameDialog(
    title: String,
    confirmLabel: String,
    initialValue: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    TextPromptDialog(
        title = title,
        fieldLabel = "Name",
        confirmLabel = confirmLabel,
        initialValue = initialValue,
        onDismiss = onDismiss,
        onConfirm = onConfirm,
    )
}
