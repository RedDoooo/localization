package com.example.musicplayerapp

data class Song(
    val id: Long,
    val title: String?,
    val artist: String?,
    val album: String?,
    val path: String?,
    val duration: Long
)
