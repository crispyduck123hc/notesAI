package com.example.notesai.ui.notes

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.example.notesai.data.NoteRepository
import com.example.notesai.github.CreatedWithin
import com.example.notesai.github.IssueFilter
import com.example.notesai.model.NoteRef
import com.example.notesai.model.NoteTreeItem
import com.example.notesai.model.folderAndDescendants
import com.example.notesai.ui.dialogs.LinkableRepo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Everything the notes screen remembers while it is on screen: what is selected, what is expanded, which dialog is
 * open, and how the last GitHub action went.
 *
 * In its own class rather than a dozen `remember { mutableStateOf(…) }` calls inside the composable, because this is
 * the state three separate parts of the screen share — the panes, the GitHub section and every dialog. Passing one
 * object keeps the composables about layout, and gives a redesign somewhere obvious to add or drop a piece of state.
 *
 * Not a view-model: it has no coroutines of its own beyond the screen's scope, and it holds no repository. The screen
 * still asks the repository to do things; this only remembers what the user is looking at.
 */
@Stable
internal class NotesScreenState(
    private val scope: CoroutineScope,
) {
    var expanded by mutableStateOf(setOf(NoteRepository.ROOT_FOLDER_ID))
    var selectedFolderId by mutableStateOf(NoteRepository.ROOT_FOLDER_ID)
    var selectedRef by mutableStateOf<NoteRef?>(null)

    /** Which issues the tree shows. A view concern; the mirror keeps everything. */
    var issueFilter by mutableStateOf(IssueFilter())
    var showDateRange by mutableStateOf(false)

    var showNewFolderDialog by mutableStateOf(false)
    var renamingFolderId by mutableStateOf<Long?>(null)
    var movingNoteId by mutableStateOf<Long?>(null)
    var showConflicts by mutableStateOf(false)
    var showSyncError by mutableStateOf(false)

    // GitHub folder state. `gitHubBusy` guards the buttons: a pull is a network round trip, and pressing it twice
    // must not start two of them.
    var gitHubBusy by mutableStateOf(false)
    var gitHubError by mutableStateOf<String?>(null)
    var gitHubNotice by mutableStateOf<String?>(null)
    var linkableRepos by mutableStateOf<List<LinkableRepo>?>(null)
    var linkLoading by mutableStateOf(false)
    var linkError by mutableStateOf<String?>(null)
    var forcePullTarget by mutableStateOf<Long?>(null)
    var newIssueRepo by mutableStateOf<Long?>(null)

    fun toggle(id: Long) {
        expanded = if (id in expanded) expanded - id else expanded + id
    }

    fun selectFolder(id: Long) {
        selectedFolderId = id
    }

    fun selectNote(note: NoteTreeItem.Note) {
        selectedRef = note.ref
        selectedFolderId = note.folderId
    }

    fun selectIssue(issue: NoteTreeItem.Issue) {
        selectedRef = issue.ref
        selectedFolderId = issue.parentNodeId
    }

    /** A folder is about to be deleted: drop any selection that pointed inside it. */
    fun forgetFolder(
        id: Long,
        folders: List<com.example.notesai.db.FolderEntity>,
        notes: List<com.example.notesai.db.NoteEntity>,
        selectedNoteId: Long?,
    ) {
        val removed = folderAndDescendants(id, folders)
        if (selectedFolderId in removed) selectedFolderId = NoteRepository.ROOT_FOLDER_ID
        val openNote = notes.firstOrNull { it.id == selectedNoteId }
        if (openNote != null && openNote.folderId in removed) selectedRef = null
    }

    /** A note is about to be deleted: close the editor if it was showing it. */
    fun forgetNote(
        id: Long,
        selectedNoteId: Long?,
    ) {
        if (selectedNoteId == id) selectedRef = null
    }

    fun createdWithin(range: CreatedWithin) {
        issueFilter = issueFilter.createdWithin(range)
    }

    fun createdBetween(
        from: kotlinx.datetime.LocalDate?,
        to: kotlinx.datetime.LocalDate?,
    ) {
        issueFilter = issueFilter.createdBetween(from, to)
        showDateRange = false
    }

    /**
     * Runs a GitHub action with uniform busy/error reporting, returning a message worth showing.
     *
     * One place, because every GitHub action reports the same way: disable the buttons while it runs, and put the
     * outcome — success sentence or failure sentence — somewhere the user will look.
     */
    fun runGitHub(block: suspend () -> String?) {
        scope.launch {
            gitHubBusy = true
            try {
                block()?.let { gitHubNotice = it }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (
                // Broad on purpose: every GitHub failure is reported the same way, with whatever the client already
                // turned into a sentence.
                @Suppress("TooGenericExceptionCaught")
                t: Throwable,
            ) {
                gitHubError = t.message ?: t.toString()
            } finally {
                gitHubBusy = false
            }
        }
    }
}

/** The screen's state, remembered for as long as the screen is composed. */
@Composable
internal fun rememberNotesScreenState(): NotesScreenState {
    val scope = rememberCoroutineScope()
    return remember(scope) { NotesScreenState(scope) }
}
