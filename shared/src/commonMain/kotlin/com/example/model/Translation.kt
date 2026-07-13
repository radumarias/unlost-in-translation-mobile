package com.example.model

/**
 * A stored translation — the domain model shared across platforms (design §2).
 * Decouples the UI / view model / repository from the Android Room entity;
 * platform storage maps to/from this. Field names/semantics match the former
 * TranslationEntity so history renders identically.
 */
data class Translation(
    val id: Long = 0,
    val sourceText: String,
    val translatedText: String,
    val sourceLangCode: String,
    val sourceLangName: String,
    val targetLangCode: String,
    val targetLangName: String,
    val timestamp: Long,
    val isStarred: Boolean = false,
)
