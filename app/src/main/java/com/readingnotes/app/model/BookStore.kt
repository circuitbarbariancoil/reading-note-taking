package com.readingnotes.app.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy

/** Serialization for `book.json`. The app is the sole writer (DESIGN.md §4). */
object BookStore {
    @OptIn(ExperimentalSerializationApi::class)
    val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
        namingStrategy = JsonNamingStrategy.SnakeCase
    }

    fun encode(book: Book): String = json.encodeToString(Book.serializer(), book)
    fun decode(text: String): Book = json.decodeFromString(Book.serializer(), text)
}
