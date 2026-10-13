## Installing

| Your system | Download |
|---|---|
| macOS | the `.dmg` below |
| Windows | the `.msi` below |
| Debian / Ubuntu | the `.deb` below |

The app needs access to your Google Drive to sync: the first time you open it, sign in with the
Google account you want your notes kept in. Nothing is sent anywhere except Google, to a hidden
folder only this app can see.

## First launch on macOS

This build is not signed with a paid Apple Developer certificate, so macOS blocks a downloaded copy
once:

> "notesAI" cannot be opened because Apple cannot check it for malicious software.

To get past it, either:

1. Open **System Settings → Privacy & Security**, scroll down to **Security**, and click
   **Open Anyway**; or
2. run this in Terminal — the reliable route:

   ```bash
   xattr -dr com.apple.quarantine /Applications/notesAI.app
   ```

This is a check on how the file arrived, not on the app: a copy built on your own machine opens
without any warning. It has to be repeated for each new download.

On **Windows**, you'll see "Windows protected your PC" once — click **More info**, then **Run
anyway**. On **Linux** there is nothing to do.

## Where your notes live

Each device keeps its own local database and syncs it to a hidden folder in **your own Google
Drive** using the `drive.appdata` permission — which means the app can see its own files and
nothing else in your Drive. There is no server behind this app, no account to create, and no
subscription: if the project disappeared tomorrow, your notes would still be in your Drive and on
your disk.
