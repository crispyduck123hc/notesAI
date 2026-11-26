package com.example.notesai.ai

import com.example.notesai.data.NoteRepository

/**
 * The two AI settings that live in the account's database (per account, like everything else):
 * the model generation runs on, and the Cloud project id named as the **quota project**
 * (`x-goog-user-project`) when Google requires one — both read at call time, so a change in
 * settings applies to the very next request without a restart.
 */
const val AI_MODEL_SETTING_KEY = "ai.model"
const val AI_QUOTA_PROJECT_KEY = "ai.quotaProject"

/** The models offered in settings. Lighter variants ride less contended serving pools. */
val AI_MODEL_CHOICES = listOf("gemini-3.5-flash-lite", "gemini-3.8-flash", "gemini-3.1-flash-lite")

fun NoteRepository.aiModel(): String = metadata(AI_MODEL_SETTING_KEY)?.takeIf { it.isNotBlank() } ?: DEFAULT_AI_MODEL

fun NoteRepository.recordAiModel(model: String) {
    putMetadata(AI_MODEL_SETTING_KEY, model)
}

fun NoteRepository.aiQuotaProject(): String? = metadata(AI_QUOTA_PROJECT_KEY)?.takeIf { it.isNotBlank() }

fun NoteRepository.recordAiQuotaProject(project: String?) {
    if (project.isNullOrBlank()) {
        removeMetadata(AI_QUOTA_PROJECT_KEY)
    } else {
        putMetadata(AI_QUOTA_PROJECT_KEY, project.trim())
    }
}
