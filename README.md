# notesAI

A notes app you own outright.

Your notes live in a local SQLite database on each device, and sync through a **hidden folder
inside your own Google Drive**. There is no notesAI server, no account to create with anyone, and
nothing to subscribe to: the only thing the app talks to is Google, using credentials you control.
Because the app is limited to `drive.appdata`, it can see *only its own files* — not the rest of
your Drive.

Kotlin Multiplatform and Compose Multiplatform, sharing one codebase across desktop (macOS,
Windows, Linux), Android and iOS.

**Status.** Desktop is complete and usable. Android and iOS compile, but **cannot sign in yet** —
that work is deliberately deferred. See [Not implemented yet](#not-implemented-yet).

---

## Contents

- [How it works](#how-it-works)
- [Requirements](#requirements)
- [Setup](#setup) — including creating your own Google credentials
- [Where your data lives](#where-your-data-lives)
- [Building installers](#building-installers)
- [First launch on macOS](#first-launch-on-macos) — the warning, and how to get past it
- [Troubleshooting](#troubleshooting)
- [Project layout](#project-layout)
- [Not implemented yet](#not-implemented-yet)

---

## How it works

Each device keeps its own SQLite database. That database is the **source of truth** — the app
works fully offline, and sync is something that happens in the background rather than a dependency.

Sync copies one small JSON file per record into Google Drive's `appDataFolder`, a hidden
per-application space:

```
{uuid}.note.json
{uuid}.folder.json
```

The remote layout is **flat**. The folder tree you see in the app is a UI metaphor; structure is
expressed by uuid references inside the files, not by remote directories. `formatVersion` is 1.

Some decisions worth knowing about:

| Behaviour | How it works |
|---|---|
| Deletes | Soft — a tombstone is just a file carrying `deletedAt`, so deletions propagate like any other change. Tombstones are cleaned up after 30 days |
| Editing the same note on two devices | **Keep both.** The local copy keeps its identity; the remote version comes back as a separate "conflicted copy" you can reconcile |
| Deleting on one device while editing on another | **The app asks.** These are parked in a conflict inbox, because guessing wrong loses work |
| Detecting what changed | A per-record **baseline** (the remote revision last seen), not timestamps. Absence from Drive is never treated as a deletion on its own |
| Uploading | Local changes wait out a short settle window (1 minute) so a burst of typing becomes one upload. Pulls are never delayed |

Sync runs when the app starts, when the window regains focus, every 60 seconds, and when you close
the window on desktop (a best-effort flush). There is no sync button — it is meant to be invisible.

Each pass settles the remote first and then pushes local state, folders before notes.

---

## Requirements

| | |
|---|---|
| **Any platform** | A JVM to start Gradle. The build pins JDK 21 (Azul Zulu) and downloads it automatically, so you normally don't need to install 21 yourself |
| **Desktop** | macOS 13+, Windows 10+, or a modern Linux. Nothing else |
| **Android** | Android Studio (or the Android SDK) with **SDK Platform 37**. Gradle fetches it if licences are accepted |
| **iOS** | macOS with Xcode 16 or later |

---

## Setup

### 1. Get the code

```bash
git clone https://github.com/crispyduck123hc/notesAI.git
cd notesAI
```

The project **builds and its tests pass without any credentials**. You only need them to sign in:

```bash
./gradlew :shared:jvmTest           # 66 tests, no credentials needed
```

### 2. Create your own Google credentials

The app has no credentials of its own — you make your own, so your notes go to *your* Drive. This
is a one-time job, about five minutes.

**a. Create a project** at <https://console.cloud.google.com/projectcreate>. Any name will do.

**b. Enable the Drive API** for that project:
<https://console.cloud.google.com/apis/library/drive.googleapis.com> → **Enable**.
Skip this and the app gets `403 accessNotConfigured` on every sync.

**c. Configure the OAuth consent screen** at
<https://console.cloud.google.com/auth/audience>:

- User type: **External** (unless you only need Google Workspace accounts)
- Fill in an app name, your support email, and a developer contact email
- Add scopes: `https://www.googleapis.com/auth/drive.appdata`, plus `openid` and `email`
- While the status is **Testing**, add your own Google address under **Test users** — otherwise
  Google will refuse to let you sign in

> **Publish it when you're done.** While a consent screen sits in *Testing*, Google expires the
> sign-in after **7 days**, so you'd have to sign in again every week. Click **Publish app** —
> `drive.appdata` is a
> [non-sensitive scope](https://developers.google.com/drive/api/guides/api-specific-auth), so this
> is immediate and requires no review. (Until it's published, the 100-test-user limit applies.)

**d. Create the client** at <https://console.cloud.google.com/apis/credentials> → **Create
credentials** → **OAuth client ID** → application type **Desktop app**. Copy the **client ID** and
**client secret**.

The app uses the loopback redirect `http://127.0.0.1:8765`, so **port 8765 must be free** when you
sign in. Desktop clients may use any loopback port, so you don't need to register this URI
precisely — but if 8765 is taken, change it in
`shared/src/jvmMain/kotlin/com/example/notesai/auth/GoogleOAuth.jvm.kt`.

### 3. Put them in `local.properties`

In the project root. This file is git-ignored, and the values are compiled into the app at build
time — nothing is committed.

```properties
google.desktop.clientId=YOUR_CLIENT_ID.apps.googleusercontent.com
google.desktop.clientSecret=YOUR_CLIENT_SECRET
```

Missing values are harmless: the project still builds, and sign-in tells you they're missing rather
than failing silently.

`local.properties` is also where the Android SDK path goes (`sdk.dir=...`), which Android Studio
writes for you. Android builds need it; everything else does not.

Optional, for the mobile apps — see [Not implemented yet](#not-implemented-yet):

```properties
google.android.clientId=...
google.ios.clientId=...
```

### 4. Run it

**Desktop** — the primary target:

```bash
./gradlew :desktopApp:run
```

**Android** — with a device or emulator running (or just build the APK):

```bash
./gradlew :androidApp:installDebug     # requires a connected device
./gradlew :androidApp:assembleDebug    # or: produces androidApp/build/outputs/apk/debug/
```

**iOS** — open the Xcode project and run from there:

```bash
open iosApp/iosApp.xcodeproj
```

### 5. Run the tests

```bash
./gradlew :shared:jvmTest                  # the main suite: 66 tests on the JVM
./gradlew :shared:testAndroidHostTest      # the same common tests, on Android
./gradlew :shared:iosSimulatorArm64Test    # ...and in the iOS simulator
```

---

## Where your data lives

**One database per signed-in account**, named from a hash of the email address
(`notes-<first 8 bytes of sha256(email)>.db`), so signing in as someone else opens a different
database rather than showing you the previous account's notes. Signing out does *not* delete it.

| Platform | Location |
|---|---|
| macOS | `~/Library/Application Support/notesAI/` |
| Windows | `%APPDATA%\notesAI\` |
| Linux | `${XDG_DATA_HOME:-~/.local/share}/notesAI/` |

Sign-in tokens are stored in the platform's credential store:

| Platform | Storage |
|---|---|
| macOS | Login Keychain (service `com.example.notesai.oauth`) |
| iOS | Keychain |
| Android | Android Keystore-encrypted (AES-GCM) preferences |
| Windows / Linux | `~/.notesai/tokens.json`, permissions `0600` |

> The Windows and Linux fallback is a file, not a real credential store. That is deliberate for now
> and is noted under [Not implemented yet](#not-implemented-yet) — it should become DPAPI and
> libsecret respectively.

To start over completely: **Sign out** in the sidebar, then delete the database file for that
account. Notes that were synced stay in your Drive and will come back on the next pull; anything
never uploaded is gone, so quit the app first and let it finish syncing.

---

## Building installers

```bash
./gradlew :desktopApp:packageDistributionForCurrentOS   # whatever this OS produces
./gradlew :desktopApp:packageDmg                        # macOS
./gradlew :desktopApp:packageMsi                        # Windows
./gradlew :desktopApp:packageDeb                        # Linux
```

Output lands in `desktopApp/build/compose/binaries/main/`.

**Installers cannot be cross-built.** Each format must be produced on its own operating system,
which is why [`.github/workflows/release-desktop.yml`](.github/workflows/release-desktop.yml)
runs on a macOS/Windows/Linux matrix and attaches the results to a GitHub Release when you push a
`v*` tag.

Test the real thing from the terminal, as a packaged app is launched with an arbitrary working
directory (Finder uses `/`, which is not writable):

```bash
./gradlew :desktopApp:createDistributable      # app image, no installer
./gradlew :desktopApp:runDistributable         # launch that image
```

Icon, signing and notarization are not configured yet: packages build without them, and readers on
macOS get the warning described next.

---

## First launch on macOS

If you build the app yourself, it just opens. If it arrives **as a download**, macOS blocks it once:

> "notesAI" cannot be opened because Apple cannot check it for malicious software.

That is Gatekeeper, and it is about *how the file travelled*, not about the app. macOS marks files
that come over the internet, and only checks marked files — which is why a locally built copy is
never questioned. This build is ad-hoc signed (enough for Apple silicon to run it) but not signed
with an Apple Developer ID, so Gatekeeper refuses it.

To get past it, either:

- **System Settings → Privacy & Security**, scroll to Security, and click **Open Anyway**; or
- in Terminal — the guaranteed route:

  ```bash
  xattr -dr com.apple.quarantine /Applications/notesAI.app
  ```

Once per downloaded copy. Updates are new downloads, so the prompt returns each time until the app
is signed with a paid Apple Developer certificate and notarized.

On Windows, an unsigned installer shows "Windows protected your PC" once → **More info** → **Run
anyway**. On Linux there is no equivalent check.

---

## Troubleshooting

**`Drive request failed (403, accessNotConfigured)`** — the Drive API is switched off in your
Cloud project. Enable it at
<https://console.cloud.google.com/apis/library/drive.googleapis.com>.

**`Drive request failed (403, insufficientPermissions)`** — the saved sign-in doesn't cover Drive.
Sign out in the sidebar and sign in again. (A refresh token can never gain new permissions, so
signing in again is the only way to widen one.)

**`Drive request failed (401)` or "Invalid Credentials"** — the sign-in went stale. Sign out and
sign in again.

**Signed in, but everything fails after about a week** — your consent screen is still in Testing
status. See step 2c.

**"Google OAuth client id is not configured"** — `local.properties` is missing its
`google.desktop.clientId`. See step 3.

**The app opens but the window is empty, or it crashed at startup** — you are probably running a
packaged build that predates a fix: the database used to be written to the working directory, and
the packaged Java runtime used to be missing the JDBC module. Both are fixed; rebuild if you have
an old copy.

**Notes appear to be missing** — check you are signed into the same Google account. Each account
gets its own database, so a different account shows a different, emptier set of notes.

---

## Project layout

```
shared/       Kotlin Multiplatform: UI, database, sync engine, OAuth, Drive client
  commonMain/          shared code and Compose UI
    sqldelight/        schema (.sq), migrations (.sqm) and version snapshots (databases/*.db)
  jvmMain/ androidMain/ iosMain/    per-platform actuals: drivers, token stores, sign-in
  commonTest/ jvmTest/  tests
desktopApp/   desktop entry point and packaging
androidApp/   Android entry point
iosApp/       Xcode project and iOS entry point
```

Inside `shared/commonMain`, the interesting parts:

| Path | What it does |
|---|---|
| `sync/NoteSyncEngine.kt` | The sync pass: decide push/pull/conflict, then apply |
| `sync/SyncFiles.kt` | The remote file format and its encoding |
| `drive/DriveClient.kt` | Thin Drive v3 REST client, scoped to `appDataFolder` |
| `data/NoteRepository.kt` | All database access; writes queue an outbox entry |
| `auth/` | OAuth 2.0 authorization code + PKCE, token storage |
| `ui/` | One file per composable |

---

## Not implemented yet

Honest list, so nothing here is a surprise:

- **Mobile sign-in.** `authorizeInteractively` throws `NotImplementedError` on Android and iOS, so
  those apps cannot get past the login screen. Desktop is the target for now. Android needs Custom
  Tabs plus a redirect intent filter; iOS needs `ASWebAuthenticationSession` plus a URL scheme.
- **Desktop code signing and notarization.** Configuration exists and is inert until a certificate
  is present. See [First launch on macOS](#first-launch-on-macos).
- **Windows and Linux token storage** that isn't a file. Should be DPAPI and libsecret.
- **Encryption at rest.** The local database is plain SQLite; it relies on full-disk encryption.
- **A web target.** Excluded from `settings.gradle.kts`.
- **Drag and drop** in the note tree, and incremental pulls via Drive's `changes.list` (the app
  lists the folder each pass, which is fine at this size).

## More detail

Deeper design notes — the sync protocol, conflict policy, packaging and deployment reasoning —
live in the commit history and in the code comments, which are written to explain *why* rather than
*what*.
