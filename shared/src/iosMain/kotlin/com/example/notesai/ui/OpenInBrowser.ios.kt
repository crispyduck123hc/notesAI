package com.example.notesai.ui

/**
 * Deliberately does nothing.
 *
 * iOS needs the presenting `UIViewController` to open a URL, which the shared module does not hold. As
 * with Android this is unreachable today, because GitHub sign-in is desktop-only.
 */
actual fun openInBrowser(url: String) = Unit
