package com.example.notesai

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform