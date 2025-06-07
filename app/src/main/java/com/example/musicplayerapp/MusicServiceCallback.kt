package com.example.musicplayerapp

interface MusicServiceCallback {
    fun onSongChanged(song: Song?) // Song can be null if playlist ends or error
    fun onPlaybackStateChanged(isPlaying: Boolean, currentPosition: Int) // Include current position for immediate UI sync
    fun onProgressUpdate(progress: Int, duration: Int)
    fun onShuffleModeChanged(shuffleEnabled: Boolean)
    fun onRepeatModeChanged(repeatMode: RepeatMode)
    fun onPlaylistEnded() // For when the playlist finishes and repeat is off
    fun onServiceStopping() // When service is about to stop, MainActivity can update UI
    fun onPlaylistChanged(newPlaylist: List<Song>) // For when songs are queued
    fun onPlaybackError(errorMsg: String) // When a playback error occurs
}
