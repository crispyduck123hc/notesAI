package com.example.notesai

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
@JsModule("@js-joda/timezone")
@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
external object TimezoneModule

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    ComposeViewport {
        App()
    }
}