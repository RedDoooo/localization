package com.example.musicplayerapp

data class SearchResultItem(
    val title: String,
    val artist: String,
    val downloadUrl: String // This might be a relative path or an identifier
)
