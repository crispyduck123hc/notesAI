# Architecture

One Kotlin Multiplatform codebase; each subsystem gets a section here. Sections marked *(stub)* are headings to be filled in later.

---

## Sync Engine

### The model: optimistic concurrency over Drive `appDataFolder`

Each device keeps notes in a local SQLite database and syncs through a hidden folder inside the user's own Google Drive. There is no central server. Every record is one small JSON file on Drive; the sync engine reconciles local state with the Drive listing on each pass.

Two local tables carry the whole design:

- **Outbox** — a durable queue of local changes owed to the remote. Every write (edit, create, delete) queues a row. It is the source of truth for "work owed"; a queued row survives crashes, kills and offline spells.
- **Baseline** — for each entity, the remote revision (`version` on the Drive file) this device last saw. Change detection is always *against the baseline*, never against wall clocks, so a device with a wrong clock cannot win by accident.

Deletions are **tombstones** on both sides: a deleted record is still a file, carrying `deletedAt`, which is how an offline device learns about a deletion.

### The decision matrix

Each pass runs `remote.list()`, then decides per entity from three signals:

- `isDirty` — an outbox row exists (local change not yet confirmed pushed)
- `remoteChanged` — remote file's version ≠ baseline (another device wrote since we last saw it)
- `remoteMissing` — baseline exists but the remote file is gone from the listing

```kotlin
when {
    isDirty && remoteChanged   -> CONFLICT      // edited here AND changed there
    isDirty && remoteMissing   -> CONFLICT      // edited here, gone there (delete-vs-edit question)
    isDirty                    -> PUSH          // edited here, remote unchanged
    remoteChanged              -> PULL          // untouched here, changed there
    remoteMissing              -> RECONCILE     // alive here, absent there, untouched by us
    else                       -> nothing to do // baseline matches both sides
}
```

No problems when state is simply dirty, remote-changed, remote-missing or nothing happened. Problems arise when both sides changed — that is the lost-update case the outbox + baseline design exists to catch.

### PUSH

**Entered when:** `isDirty && !remoteChanged && !remoteMissing`.

Outbox content is uploaded (create or overwrite the remote file), then the new baseline is set locally and the outbox row is cleared. A settle window (debounce) holds back changes younger than a threshold so a burst of edits coalesces into one push; pulls are never delayed. The note's text is fingerprinted *before* the request so a later pass can recognise its own write if the response is lost (see Conflicts).

### PULL

**Entered when:** `remoteChanged && !isDirty`.

The remote file is downloaded and applied locally (new uuid ⇒ created elsewhere; changed version ⇒ edited elsewhere), and the baseline advances to the remote revision. Folders pull before notes so a pulled note can resolve its parent; pulls run before pushes so remote structure exists before anything writes against it. Deletions arrive as tombstone files and are applied as soft deletes.

### CONFLICT RESOLUTION

**Entered when:** `isDirty && remoteChanged` (edited here, changed there) or `isDirty && remoteMissing` (edited here, gone there).

The stage compares local with remote content hashes to first check the edge case where the content *was* pushed but the local baseline update never completed (request went through, response lost, or app killed mid-pass):

- **Hashes equal** — not a real conflict. Adopt the remote entry as the baseline, clear the outbox, stand down. This check is what prevents one note duplicating into copies of itself after a restart.
- **Hashes differ** — assume a different device wrote during that time. For note content: **keep-both** — last-writer-wins (later `updatedAt`, uuid as tie-breaker, so every device converges) decides which text becomes *the* note; the losing text is preserved as a conflicted copy (`conflictOf`) published as its own file. For anything structural (folder changed on both sides, delete racing an edit, remote-missing while alive locally): parked in a conflict inbox for a human decision; the local change stays queued until resolved.
- **Both deleted** — agreement; push the tombstone. **One deleted, one edited** — deferred to the user (`delete-vs-edit`).

An outbox row is never dropped on failure. After a failed request, `adoptIfTheWriteLanded` re-checks whether the remote now holds exactly what was being written — a timeout says nothing about what the server did with the bytes — and if so adopts the baseline instead of re-queueing a duplicate.

### RECONCILE

**Entered when:** `remoteMissing && !isDirty && !localDeleted`. Absence from Drive is never silently read as a deletion — a bad listing would delete notes wholesale. It becomes a `remote-missing` question for the user instead of an automatic action.

### What triggers a sync pass at all

App start, the window regaining focus, a 60-second interval, a best-effort flush on close on desktop, plus a **wake-up** signal: `SyncWakeup` watches the outbox row count and fires two passes per burst (one immediately — draining a queue left by a crash or offline spell; one after the settle window — publishing the edit that was too young to publish at first). A mutex guarantees only one pass runs at a time; a shutdown deadline budget stops a pass from starting requests it cannot finish — unfinished work stays queued for the next pass.

### Files

| File | Role |
|---|---|
| `sync/NoteSyncEngine.kt` | The pass: decide push/pull/conflict, then apply |
| `sync/SyncFiles.kt` | Remote file format and its encoding |
| `sync/SyncWakeup.kt` | Outbox → wake-up signal |
| `sync/DriveRemote.kt` / `SyncRemote.kt` | Remote abstraction (real Drive vs test fakes) |
| `drive/DriveClient.kt` | Thin Drive v3 REST client, scoped to `appDataFolder` |

---

## Auth *(stub)*

Google sign-in via OAuth 2.0 authorization code + PKCE (`auth/GoogleAuthManager.kt`, `GoogleOAuth.kt`). Scoped to `drive.appdata` plus `openid`/`email`; scope-grant comparison handles OpenID Connect's expanded scope spellings. Tokens in a `TokenStore`; re-authorization is a warning shown to the user, never an automatic lockout. Each platform registers its own OAuth client.

---

## GitHub integration *(stub)*

Optional per-account link (`auth/GitHubAuthManager.kt`, `github/`). Pull issues into the note tree as issue notes; push edits back. Pull skips rows with local edits unless forced; push never silently overwrites — anything that moved or was refused is reported, the user decides.

---

## Data layer *(stub)*

SQLite (SQLDelight) via `data/NoteRepository.kt`; every write queues an outbox entry. Folder/note tree, tombstones, per-account scoping (`AccountScope.kt`).

---

## Editor *(stub)*

Plain-string document; input/output transformations for list markers, tabs and checklist glyphs (`ui/editor/`). Overlay plan in `response.md` (text field + overlay, phases 0–6).

---

## Update checker *(stub)*

Semantic-version check against the released site (`update/`).

---

## AI assistance *(future, not yet built)*

LLM-assisted note generation and manipulation, on top of the plain-string document model.
