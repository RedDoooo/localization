package com.example.musicplayerapp

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class Song(
    val id: Long,
    val title: String?,
    val artist: String?,
    val album: String?,
    val path: String?,
    val duration: Long
) : Parcelable
