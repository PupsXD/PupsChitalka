package com.ozvuchka.app.data

import java.util.UUID

data class Chapter(
    val title: String,
    val paragraphs: List<String>,
    val sourceUrl: String? = null,
    val nextUrl: String? = null,
)

data class Book(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val author: String = "",
    val format: String,
    val chapters: List<Chapter>,
    val source: String? = null,
    val currentChapter: Int = 0,
    val chapterProgress: Float = 0f,
    val addedAt: Long = System.currentTimeMillis(),
    val warnings: List<String> = emptyList(),
)
