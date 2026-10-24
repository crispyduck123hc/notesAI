package com.example.notesai.ui

/**
 * Deliberately does nothing.
 *
 * Android needs the `Activity` (or an application `Context`) to start an `ACTION_VIEW` intent, and the
 * shared module holds neither. It is unreachable today in any case: GitHub sign-in is desktop-only, so
 * there is no GitHub folder on Android to open anything from. Thread the context through when mobile
 * GitHub lands.
 */
actual fun openInBrowser(url: String) = Unit
