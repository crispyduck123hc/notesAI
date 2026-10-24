package com.example.notesai.ui

/**
 * Opens [url] in the user's default browser.
 *
 * Nothing is fetched or rendered in-app: "open on GitHub" hands the issue to the browser, where the
 * user is already signed in and where comments, labels and history live.
 */
expect fun openInBrowser(url: String)
