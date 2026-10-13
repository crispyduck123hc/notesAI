# Privacy Policy — notesAI

**Last updated: 13 October 2026**

notesAI is a notes app that keeps your notes on your own devices and syncs them to your own Google
Drive. This policy sets out exactly what happens to your data. It is short because the app does very
little with it: there is nothing to opt out of, and nothing is collected.

This policy covers the notesAI applications for macOS, Windows, Linux, Android and iOS.

---

## The short version

- **There is no notesAI server.** I never receive your notes, your email address, or any information
  about you or your devices.
- Your notes are stored in a database **on your own computer or phone**, and synced to a **hidden
  folder inside your own Google Drive**.
- The app asks for **one Google permission**: `drive.appdata`. That grants access to the app's own
  files and nothing else — it cannot read any other file in your Drive, and it cannot see your
  email, contacts, calendar or profile.

## What the app stores, and where

| Data | Where it lives | Who can reach it |
|---|---|---|
| Your notes and folders | A local database on each device where you use the app | You, and anyone with access to that device |
| Your synced notes and folders | A hidden application-data folder in **your** Google Drive | You and Google; not me |
| Your Google sign-in tokens | macOS Keychain, iOS Keychain, Android Keystore, or a permission-restricted file (`0600`) on Windows and Linux | You |
| Which Google account a database belongs to | The local database (the account's email address is hashed, not stored in full) | You |
| A random device identifier | The local database, generated on first run | Nobody — it is not currently transmitted anywhere |

Notes you delete are kept as **tombstones** for 30 days so the deletion can propagate to your other
devices, and are then removed from both your device and your Drive.

## What is sent to Google, and nothing else

To sync, the app sends your note and folder contents — as small JSON files — to Google, which stores
them in the hidden application-data folder of your Drive. Google's handling of data in your Drive is
governed by [Google's Privacy Policy](https://policies.google.com/privacy). That transfer happens
directly between your device and Google.

**No data is sent anywhere else**, and none of it passes through any system operated by me.

## What the app does not do

- No analytics, telemetry or crash reporting
- No advertising and no tracking
- No third-party SDKs that collect data
- No account with me, and no collection of your email address or any other personal information
- No sharing or selling of data, because none is held

## Children

notesAI is not directed at children under 13, and no personal information is collected from anyone,
including children.

## Retention and deletion

You are in control of all of it:

- **Delete a note or folder** in the app and it is removed locally, and that removal is synced to your
  Drive within 30 days of the deletion being made.
- **Delete the app** and your local database goes with it. Your notes remain in your Google Drive.
- **Remove everything**: in Google Drive on the web, open **Settings → Manage apps**, find notesAI
  and delete its hidden app data. You can also revoke the app's access at
  <https://myaccount.google.com/permissions> — that stops it reaching your Drive, though it does not
  by itself remove files already stored there.
- **Sign out** in the app to delete the stored sign-in tokens from your device.

Because I never hold your data, there is nothing for you to request from me and no copy to delete.

## Changes to this policy

If this policy changes, the date at the top of this file will change and the edit will be visible in
the repository's history. Continued use of the app after a change means you accept the updated
policy.

## Contact

For any question about this policy: hcao06@gmail.com, or open an issue at
<https://github.com/crispyduck123hc/notesAI/issues>.

