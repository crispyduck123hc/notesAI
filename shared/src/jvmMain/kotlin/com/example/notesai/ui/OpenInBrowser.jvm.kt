package com.example.notesai.ui

import com.example.notesai.auth.openBrowser

/** The same launcher the OAuth flow uses, so there is one implementation of "open the browser". */
actual fun openInBrowser(url: String) {
    openBrowser(url)
}
