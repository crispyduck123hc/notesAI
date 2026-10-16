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
that work is deliberately deferred. See [Platform support](#platform-support).

---

## Contents

- [Platform support](#platform-support)
- [How it works](#how-it-works)
- [Requirements](#requirements)
- [Setup](#setup) — including creating your own Google credentials
- [Where your data lives](#where-your-data-lives)
- [Building installers](#building-installers)
- [First launch on macOS](#first-launch-on-macos) — the warning, and how to get past it
- [Troubleshooting](#troubleshooting)
- [Project layout](#project-layout)
- [Not implemented yet](#not-implemented-yet)
- [Licence](#licence)
- [Privacy](#privacy)

---

## Platform support

| Platform | State |
|---|---|
| **Desktop** — macOS, Windows, Linux | **Works.** This is the supported platform, and the only one releases are built for |
| **Android** | Builds, but **cannot sign in**, so it is unusable in practice |
| **iOS** | Builds, but **cannot sign in**, so it is unusable in practice |

On mobile, `authorizeInteractively` throws `NotImplementedError`, so the app stops at the login
screen. Everything else already works there — the database, the sync engine and the entire UI are
shared, and only the browser-based sign-in step is missing. Desktop remains the target until that
changes, and publishing mobile builds before then would be shipping an app nobody can log into.

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
| **Any platform** | A JVM to start Gradle. The build pins JDK 21 (any vendor) and fetches one if your machine has none, so you normally don't need to install 21 yourself |
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

### Versioning

One value drives the desktop installer names, the Android `versionName`, and the version shown at
the bottom of the app's sidebar:

```properties
# gradle.properties
notesai.version=1.0.0
```

Bump that one line, then tag the commit `v<same version>`. Two constraints are worth knowing before
you pick a number:

- **The major version cannot be zero.** The macOS installer bundler rejects `0.1.0` outright —
  "the first number in an app-version cannot be zero or negative" — and the Windows and Linux
  bundlers apply version rules of their own. Keeping the major at 1 or more is the safe choice for
  all three. A pre-release therefore has to be expressed in the **tag** (`v1.0.0-beta.1`, which the
  release workflow marks as a pre-release automatically) rather than in the number.
- **The tag must match the version.** The installer filename comes from `notesai.version`, not from
  the tag: tag `v1.0.0` against `notesai.version=1.0.0` and you get `notesAI-1.0.0.dmg`. Get them out
  of step and your release page contradicts its own heading, without anything erroring.

iOS is the one exception — Xcode takes its version from
`iosApp/Configuration/Config.xcconfig` (`MARKETING_VERSION`), so mirror the value there. It is the
only place that cannot read `gradle.properties`.

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

**`Unable to download toolchain matching the requirements`** — the daemon JVM criteria in
`gradle/gradle-daemon-jvm.properties` pin a JDK version and possibly a vendor, and the pinned
vendor's download URL has probably gone stale. Fix it with:

```bash
./gradlew updateDaemonJvm --jvm-version=21
```

Leaving out `--jvm-vendor` is deliberate: pinning no vendor means any installed JDK 21 satisfies the
build, which is what lets CI use the JDK it already has instead of downloading one.

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

## Contributing

Issues and pull requests are welcome. A few things worth knowing first:

- **Tests and builds**: `./gradlew :shared:jvmTest` should be green, and `:desktopApp:compileKotlin`,
  `:androidApp:assembleDebug` and `:shared:linkDebugFrameworkIosSimulatorArm64` should all build.
- **Formatting and static analysis** run as part of `check`, and in CI:

  ```bash
  ./gradlew ktlintCheck detekt    # what CI enforces
  ./gradlew ktlintFormat          # fix formatting automatically
  ```

  ktlint covers formatting and can fix its own findings. detekt covers code smells — complexity,
  swallowed exceptions, suspicious constructs. Both deliberately skip generated code: SQLDelight's
  interfaces, Compose's resource collectors, and the secret and version constants the build writes.
  Their rule overrides live in [`.editorconfig`](.editorconfig) and
  [`config/detekt/detekt.yml`](config/detekt/detekt.yml), mostly to stop Compose idioms (long
  parameter lists, capitalised `@Composable` functions) being reported as problems.

  detekt also has a **per-module baseline** (`<module>/detekt-baseline.xml`) recording the findings
  that already existed when it was introduced, so it gates new code without demanding a cleanup
  first. When you fix one, run `./gradlew detektBaseline` and the baseline is rewritten without it.
  Empty baselines are the goal.

  All four pieces of that configuration are committed on purpose — `.editorconfig`,
  `config/detekt/detekt.yml` and the baselines are project settings, not personal ones. CI lints with
  them; without them CI would disagree with your machine.

  There is deliberately no pre-commit hook: the checks run in CI on every push, and
  `./gradlew check` gives the same answer locally before you push. A hook would have to be installed
  separately on each machine anyway, since git does not version `.git/hooks`.

- **Lint the workflow files before pushing.** GitHub rejects a workflow file outright when it uses a
  context where one isn't available — `secrets` in a step's `if:`, for example — and the failure is
  silent: no job runs, so nothing goes red. `actionlint` catches that class of mistake locally, and
  CI runs it too:

  ```bash
  brew install actionlint        # or: go install github.com/rhysd/actionlint/cmd/actionlint@latest
  actionlint
  ```

- **Releases**: see [Versioning](#versioning).

## Licence

MIT — see [LICENSE](LICENSE). In short: use it, fork it, modify it, ship it, sell it, as long as you
keep the copyright notice. It comes with no warranty.

## Privacy

There is no notesAI server. Your notes live on your own device and, if you sign in, in a hidden
folder inside your own Google Drive; nothing is sent anywhere else, and the author of this app
receives none of it.

The full policy — including what the one requested Google permission can and cannot see, and how to
delete everything — is in [PRIVACY_POLICY.md](PRIVACY_POLICY.md). That file is also what Google links
to from the sign-in consent screen, so it is written to be read by someone deciding whether to trust
the app.

## More detail

Deeper design notes — the sync protocol, conflict policy, packaging and deployment reasoning —
live in the commit history and in the code comments, which are written to explain *why* rather than
*what*.
