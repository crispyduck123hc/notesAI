package com.example.notesai

import java.io.File

/**
 * The per-user directory this application owns: the database and the instance lock live here.
 *
 * A packaged app is launched with an arbitrary working directory — Finder uses `/` on macOS — so
 * nothing may be written relative to the process. Every OS has a conventional place instead.
 */
internal fun appDataDirectory(): File {
    val home = System.getProperty("user.home")
    val os = System.getProperty("os.name").orEmpty().lowercase()

    val base =
        when {
            os.contains("mac") -> File(home, "Library/Application Support")
            os.contains("win") -> File(System.getenv("APPDATA") ?: home)
            else -> File(System.getenv("XDG_DATA_HOME") ?: "$home/.local/share")
        }

    return File(base, "notesAI").apply { mkdirs() }
}
