package com.example.musicplayerapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.IOException
import java.util.concurrent.TimeUnit
import android.os.Handler // For progress updates


class MusicService : Service() {

    private val TAG = "MusicService"
    private var mediaPlayer: MediaPlayer? = null
    private var mediaSession: MediaSessionCompat? = null

    private var musicList: MutableList<Song> = mutableListOf()
    private var currentSongIndex: Int = -1
    private var isShuffleOn: Boolean = false
    private var repeatMode: RepeatMode = RepeatMode.OFF

    private val NOTIFICATION_ID = 1
    private val CHANNEL_ID = "music_playback_channel"

    private var callback: MusicServiceCallback? = null
    private val progressUpdateHandler = Handler(Looper.getMainLooper())
    private lateinit var progressUpdateRunnable: Runnable


    // Binder given to clients
    private val binder = MusicBinder()

    inner class MusicBinder : Binder() {
        fun getService(): MusicService = this@MusicService
    }

    companion object {
        // Intent Actions
        const val ACTION_PLAY_LIST = "com.example.musicplayerapp.ACTION_PLAY_LIST" // Can be used by LibraryActivity
        const val ACTION_PLAY_PAUSE = "com.example.musicplayerapp.ACTION_PLAY_PAUSE"
        const val ACTION_NEXT = "com.example.musicplayerapp.ACTION_NEXT"
        const val ACTION_PREVIOUS = "com.example.musicplayerapp.ACTION_PREVIOUS"
        const val ACTION_STOP_SERVICE = "com.example.musicplayerapp.ACTION_STOP_SERVICE"
        const val ACTION_SEEK_TO = "com.example.musicplayerapp.ACTION_SEEK_TO" // For notification/widget seek
        const val ACTION_QUEUE_SONGS = "com.example.musicplayerapp.ACTION_QUEUE_SONGS"


        const val EXTRA_SONG_LIST = "extra_song_list"
        const val EXTRA_SONG_INDEX = "extra_song_index"
        const val EXTRA_SEEK_POSITION = "extra_seek_position"
        const val EXTRA_SHUFFLE_MODE = "extra_shuffle_mode"
        const val EXTRA_REPEAT_MODE = "extra_repeat_mode"
        const val EXTRA_SONGS_TO_QUEUE = "com.example.musicplayerapp.EXTRA_SONGS_TO_QUEUE"
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")
        mediaPlayer = MediaPlayer()
        initMediaSession()
        createNotificationChannel()
        setupProgressUpdater()
    }

    fun setCallback(callback: MusicServiceCallback?) {
        this.callback = callback
    }

    private fun initMediaSession() {
        mediaSession = MediaSessionCompat(this, "MusicServiceMediaSession").apply {
            setCallback(mediaSessionCallback)
            setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS)
            // isActive = true // Set active when something is ready to play
        }
    }

    private val mediaSessionCallback = object : MediaSessionCompat.Callback() {
        override fun onPlay() {
            Log.d(TAG, "MediaSessionCallback: onPlay")
            resumePlayback()
        }

        override fun onPause() {
            Log.d(TAG, "MediaSessionCallback: onPause")
            pausePlayback()
        }

        override fun onSkipToNext() {
            Log.d(TAG, "MediaSessionCallback: onSkipToNext")
            playNextSongInternal()
        }

        override fun onSkipToPrevious() {
            Log.d(TAG, "MediaSessionCallback: onSkipToPrevious")
            playPreviousSongInternal()
        }

        override fun onSeekTo(pos: Long) {
            Log.d(TAG, "MediaSessionCallback: onSeekTo $pos")
            seekToPosition(pos.toInt())
        }

        override fun onStop() {
            Log.d(TAG, "MediaSessionCallback: onStop")
            stopSelfAndPlayback() // Or just pausePlayback() if you want to keep service alive
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand, action: ${intent?.action}")
        when (intent?.action) {
            ACTION_PLAY_LIST -> {
                val receivedList = intent.getParcelableArrayListExtra<Song>(EXTRA_SONG_LIST)
                val startIndex = intent.getIntExtra(EXTRA_SONG_INDEX, 0)
                if (receivedList != null) {
                    musicList.clear()
                    musicList.addAll(receivedList)
                    if (musicList.isNotEmpty()) {
                        playSong(startIndex)
                    }
                } else {
                    Log.w(TAG, "Received null song list for ACTION_PLAY_LIST")
                }
            }
            ACTION_PLAY_PAUSE -> {
                if (mediaPlayer?.isPlaying == true) {
                    pausePlayback()
                } else {
                    resumePlayback()
                }
            }
            ACTION_NEXT -> playNextSongInternal()
            ACTION_PREVIOUS -> playPreviousSongInternal()
            ACTION_STOP_SERVICE -> stopSelfAndPlayback()
            ACTION_SEEK_TO -> {
                val position = intent.getIntExtra(EXTRA_SEEK_POSITION, 0)
                seekToPosition(position)
            }
            ACTION_QUEUE_SONGS -> {
                val songsToQueue = intent.getParcelableArrayListExtra<Song>(EXTRA_SONGS_TO_QUEUE)
                if (songsToQueue != null && songsToQueue.isNotEmpty()) {
                    val wasEmptyOrNotPlaying = musicList.isEmpty() || mediaPlayer?.isPlaying == false && currentSongIndex == -1

                    // Add new songs to the existing list
                    musicList.addAll(songsToQueue)
                    Log.d(TAG, "Queued ${songsToQueue.size} songs. New playlist size: ${musicList.size}")
                    callback?.onPlaylistChanged(musicList) // Notify UI about playlist change

                    // If nothing was playing and the list was previously empty, or if player was stopped
                    // start playing the first of the newly queued songs.
                    if (wasEmptyOrNotPlaying) {
                        // The index of the first newly queued song is where the old list ended.
                        val firstQueuedSongIndex = musicList.size - songsToQueue.size
                        if (firstQueuedSongIndex in musicList.indices) { // Ensure index is valid
                           Log.d(TAG, "Playlist was empty or not playing, starting playback of first queued song at index $firstQueuedSongIndex")
                           playSong(firstQueuedSongIndex)
                        } else {
                            Log.e(TAG, "Error determining start index for queued songs.")
                        }
                    } else {
                        // If already playing, just log. User can skip or they'll play eventually.
                        Log.d(TAG, "Songs queued. Current playback continues.")
                        // Optionally, could provide a Toast message via callback if desired.
                    }
                }
            }
        }
        return START_NOT_STICKY
    }


    fun playSong(songIndex: Int) {
        if (songIndex < 0 || songIndex >= musicList.size) {
            Log.e(TAG, "Invalid songIndex: $songIndex or musicList is empty.")
            return
        }
        currentSongIndex = songIndex
        val song = musicList[currentSongIndex]
        Log.d(TAG, "playSong: ${song.title}")

        try {
            mediaPlayer?.reset()
            try {
                mediaPlayer?.setDataSource(song.path)
            } catch (e: Exception) { // IOException, IllegalArgumentException, SecurityException, IllegalStateException
                Log.e(TAG, "Error setting data source for ${song.path}", e)
                callback?.onPlaybackError("Cannot play '${song.title}'. File issue or permissions.")
                if (musicList.size > 1) {
                    playNextSongInternal(forceNext = true)
                } else {
                    stopForeground(true)
                    stopSelf()
                }
                return // Don't proceed with prepareAsync
            }
            mediaPlayer?.setOnPreparedListener { mp ->
                Log.d(TAG, "MediaPlayer prepared: ${song.title}")
                mp.start()
                mediaSession?.isActive = true
                updateMediaSessionMetadata(song)
                updatePlaybackState(PlaybackStateCompat.STATE_PLAYING, mediaPlayer?.currentPosition?.toLong() ?: 0L)
                startForeground(NOTIFICATION_ID, buildNotification(song, true))
                callback?.onSongChanged(song)
                callback?.onPlaybackStateChanged(true, mediaPlayer?.currentPosition ?: 0)
                startProgressUpdates()
            }
            mediaPlayer?.setOnCompletionListener {
                Log.d(TAG, "MediaPlayer onCompletion: ${song.title}")
                handleSongCompletion()
            }
            mediaPlayer?.setOnErrorListener { mp, what, extra ->
                Log.e(TAG, "MediaPlayer error: What: $what, Extra: $extra for song: ${song.title}")
                updatePlaybackState(PlaybackStateCompat.STATE_ERROR, mp?.currentPosition?.toLong() ?: 0L)
                callback?.onPlaybackError("Playback error. Code: $what, $extra")
                stopProgressUpdates()

                // Simplified recovery: Release player. More robust could be to skip.
                // For now, to prevent repeated errors, we stop and release.
                // Consider if playNextSongInternal should be called, but be wary of error loops.
                // If we decide to skip, it should be to a *different* song.
                // A simple way to avoid loops with skip is to mark the current song as problematic
                // or have a small retry counter per song.
                // For this iteration, releasing is safer than a potential error loop.
                mediaPlayer?.reset() // Reset to idle to be safe
                // Or more drastically:
                // releaseMediaPlayerAndStop()
                true // True if the error has been handled
            }
            mediaPlayer?.prepareAsync()
        } catch (e: Exception) { // Broader catch for other unexpected issues during setup
            Log.e(TAG, "Unexpected error in playSong for ${song.path}", e)
            updatePlaybackState(PlaybackStateCompat.STATE_ERROR, 0L)
            callback?.onPlaybackError("Cannot initialize player for '${song.title}'.")
            stopProgressUpdates()
            // Consider stopping service or trying next if in a playlist
        }
    }

    private fun releaseMediaPlayer() { // Helper to centralize release if needed often
        mediaPlayer?.reset()
        // mediaPlayer?.release() // If completely done with it, but reset is often enough for reuse
        // mediaPlayer = null // If you want to re-initialize fully next time
        Log.d(TAG, "MediaPlayer has been reset.")
    }


    fun pausePlayback() {
        Log.d(TAG, "pausePlayback")
        mediaPlayer?.takeIf { it.isPlaying }?.pause()
        val currentPos = mediaPlayer?.currentPosition ?: 0
        updatePlaybackState(PlaybackStateCompat.STATE_PAUSED, currentPos.toLong())
        callback?.onPlaybackStateChanged(false, currentPos)
        stopProgressUpdates()
        currentSongIndex.takeIf { it != -1 && it < musicList.size }?.let {
            startForeground(NOTIFICATION_ID, buildNotification(musicList[it], false)) // Update notification
            stopForeground(false) // Keep notification, but allow service to be killed if needed
        }
    }

    fun resumePlayback() {
        Log.d(TAG, "resumePlayback")
        if (musicList.isNotEmpty() && currentSongIndex != -1 && currentSongIndex < musicList.size) {
            if (mediaPlayer?.isPlaying == false) {
                 mediaPlayer?.start()
                 val currentPos = mediaPlayer?.currentPosition ?: 0
                 updatePlaybackState(PlaybackStateCompat.STATE_PLAYING, currentPos.toLong())
                 callback?.onPlaybackStateChanged(true, currentPos)
                 startForeground(NOTIFICATION_ID, buildNotification(musicList[currentSongIndex], true))
                 startProgressUpdates()
            } else if (mediaPlayer == null || mediaPlayer?.isPlaying == false && currentSongIndex == -1) { // If nothing was playing or player reset
                playSong(if (currentSongIndex != -1 && currentSongIndex < musicList.size) currentSongIndex else 0)
            }
        } else {
            Log.w(TAG, "Cannot resume: musicList empty or currentSongIndex invalid")
            // If list not empty, play first song
            if (musicList.isNotEmpty()) playSong(0)
        }
    }

    fun seekToPosition(position: Int) {
        mediaPlayer?.seekTo(position)
        // Playback state is updated by the MediaPlayer's internal state, but we can force an update
        val currentPos = mediaPlayer?.currentPosition?.toLong() ?: position.toLong()
        updatePlaybackState(if (mediaPlayer?.isPlaying == true) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED, currentPos)
        callback?.onProgressUpdate(currentPos.toInt(), mediaPlayer?.duration ?: 0) // Immediate feedback
    }

    private fun handleSongCompletion() {
        stopProgressUpdates() // Stop updates for completed song
        val currentPos = 0 // Song completed, so progress is 0 for next state
        if (repeatMode == RepeatMode.ONE) {
            mediaPlayer?.seekTo(0)
            mediaPlayer?.start()
            updatePlaybackState(PlaybackStateCompat.STATE_PLAYING, currentPos.toLong())
            callback?.onPlaybackStateChanged(true, currentPos)
            startProgressUpdates()
        } else {
            // Let playNextSongInternal handle UI updates via callbacks
            playNextSongInternal()
            // If playNextSongInternal decided to stop (end of list, repeat off),
            // ensure the callback reflects this. It should call onPlaybackStateChanged(false) itself.
        }
    }

    fun skipToNext() { // Public wrapper for MediaSession
        playNextSongInternal()
    }

    fun skipToPrevious() { // Public wrapper for MediaSession
        playPreviousSongInternal()
    }

    fun togglePlayPause() {
        if (mediaPlayer?.isPlaying == true) {
            pausePlayback()
        } else {
            resumePlayback()
        }
    }

    fun setPlaylist(songs: List<Song>, startIndex: Int, shuffle: Boolean, repeat: RepeatMode) {
        Log.d(TAG, "setPlaylist called with ${songs.size} songs, starting at $startIndex, shuffle: $shuffle, repeat: $repeat")
        musicList.clear()
        musicList.addAll(songs)
        this.isShuffleOn = shuffle // Set shuffle state
        this.repeatMode = repeat   // Set repeat state

        // Inform MainActivity about the new shuffle and repeat states
        callback?.onShuffleModeChanged(this.isShuffleOn)
        callback?.onRepeatModeChanged(this.repeatMode)

        if (musicList.isNotEmpty()) {
            currentSongIndex = if (startIndex in musicList.indices) startIndex else 0
            playSong(currentSongIndex)
        } else {
            Log.w(TAG, "setPlaylist: provided list is empty.")
            stopSelfAndPlayback() // Or update UI to reflect empty playlist
            callback?.onSongChanged(null)
            callback?.onPlaybackStateChanged(false, 0)
        }
    }

    fun toggleShuffle() {
        isShuffleOn = !isShuffleOn
        Log.d(TAG, "Shuffle mode set to: $isShuffleOn")
        callback?.onShuffleModeChanged(isShuffleOn)
        // Optionally, if a song is playing, you might want to reshuffle the *upcoming* playlist
        // but keep the current song. For now, it just affects next/prev.
    }

    fun toggleRepeatMode() {
        repeatMode = when (repeatMode) {
            RepeatMode.OFF -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.OFF
        }
        Log.d(TAG, "Repeat mode set to: $repeatMode")
        callback?.onRepeatModeChanged(repeatMode)
    }

    // Getter methods for MainActivity to sync UI on connection
    fun getCurrentSong(): Song? = if (currentSongIndex != -1 && currentSongIndex < musicList.size) musicList[currentSongIndex] else null
    fun isPlaying(): Boolean = mediaPlayer?.isPlaying ?: false
    fun getCurrentProgress(): Int = mediaPlayer?.currentPosition ?: 0
    fun getDuration(): Int = mediaPlayer?.duration ?: 0
    fun isShuffleEnabled(): Boolean = isShuffleOn
    fun getRepeatMode(): RepeatMode = repeatMode


    private fun playNextSongInternal(forceNext: Boolean = false) {
        Log.d(TAG, "playNextSongInternal called, forceNext: $forceNext")
        if (musicList.isEmpty()) {
            callback?.onPlaylistEnded()
            updatePlaybackState(PlaybackStateCompat.STATE_STOPPED, 0)
            stopSelfAndPlayback() // Nothing to play
            return
        }

        val previousSongIndex = currentSongIndex
        var nextIndexCandidate: Int

        if (isShuffleOn) {
            if (musicList.size > 1) {
                // Try to find a different song
                nextIndexCandidate = (0 until musicList.size).filter { it != currentSongIndex }.randomOrNull() ?: currentSongIndex
            } else { // Only one song in the list
                nextIndexCandidate = currentSongIndex
            }
        } else { // Sequential
            nextIndexCandidate = currentSongIndex + 1
        }

        // Loop or stop logic
        if (nextIndexCandidate >= musicList.size) { // Reached end of list
            if (repeatMode == RepeatMode.ALL) {
                nextIndexCandidate = 0 // Wrap around
            } else { // RepeatMode.OFF or RepeatMode.ONE (completion handles ONE for current song)
                Log.d(TAG, "End of playlist. Repeat is OFF.")
                updatePlaybackState(PlaybackStateCompat.STATE_STOPPED, 0)
                callback?.onPlaylistEnded()
                // Keep current song info for notification until service stops
                currentSongIndex.takeIf { it != -1 && it < musicList.size }?.let {
                    startForeground(NOTIFICATION_ID, buildNotification(musicList[it], false))
                    stopForeground(false) // Allow service to be killed
                }
                // If forced next due to error, and we are at end, better to stop.
                if (forceNext) stopSelfAndPlayback()
                return
            }
        }

        // If forceNext is true, we must advance. If we landed on the same song (e.g. single song list, shuffle on)
        // and repeat is OFF, then stop to prevent error loop.
        if (forceNext && nextIndexCandidate == previousSongIndex && musicList.size == 1 && repeatMode == RepeatMode.OFF) {
            Log.w(TAG, "Forced next on single song list with repeat OFF. Stopping to prevent loop.")
            updatePlaybackState(PlaybackStateCompat.STATE_STOPPED, 0)
            callback?.onPlaylistEnded()
            stopSelfAndPlayback()
            return
        }

        playSong(nextIndexCandidate)
    }

    private fun playPreviousSongInternal(forcePrev: Boolean = false) { // forcePrev might be useful later
        Log.d(TAG, "playPreviousSongInternal called")
        if (musicList.isEmpty()) {
            updatePlaybackState(PlaybackStateCompat.STATE_STOPPED, 0)
            stopSelfAndPlayback()
            return
        }

        val previousSongIndex = currentSongIndex
        var prevIndexCandidate: Int

        if (isShuffleOn) {
             if (musicList.size > 1) {
                prevIndexCandidate = (0 until musicList.size).filter { it != currentSongIndex }.randomOrNull() ?: currentSongIndex
             } else {
                prevIndexCandidate = currentSongIndex
             }
        } else { // Sequential
            prevIndexCandidate = currentSongIndex - 1
        }

        if (prevIndexCandidate < 0) {
            if (repeatMode == RepeatMode.ALL) {
                prevIndexCandidate = musicList.size - 1
                 if (prevIndexCandidate < 0) { // List somehow became empty
                    updatePlaybackState(PlaybackStateCompat.STATE_STOPPED, 0)
                    stopSelfAndPlayback()
                    return
                }
            } else {
                // If not repeating all, and at the beginning, play first song or stay.
                prevIndexCandidate = 0
            }
        }

        // Similar to forceNext, if forcePrev and landed on same, consider stopping if repeat OFF
        if (forcePrev && prevIndexCandidate == previousSongIndex && musicList.size == 1 && repeatMode == RepeatMode.OFF) {
            Log.w(TAG, "Forced previous on single song list with repeat OFF. Stopping.")
            updatePlaybackState(PlaybackStateCompat.STATE_STOPPED, 0)
            callback?.onPlaylistEnded()
            stopSelfAndPlayback()
            return
        }
        playSong(prevIndexCandidate)
    }


    private fun updateMediaSessionMetadata(song: Song) {
        val metadata = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, song.title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, song.artist)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, song.album)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, song.duration)
            .build()
        mediaSession?.setMetadata(metadata)
        callback?.onSongChanged(song) // Notify MainActivity about song change
    }

    private fun updatePlaybackState(state: Int, position: Long = mediaPlayer?.currentPosition?.toLong() ?: 0L) {
        val actions = PlaybackStateCompat.ACTION_PLAY_PAUSE or
                PlaybackStateCompat.ACTION_PLAY or // Explicitly add PLAY
                PlaybackStateCompat.ACTION_PAUSE or // Explicitly add PAUSE
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                PlaybackStateCompat.ACTION_SEEK_TO or
                PlaybackStateCompat.ACTION_STOP

        val playbackState = PlaybackStateCompat.Builder()
            .setState(state, position, 1.0f)
            .setActions(actions)
            .build()
        mediaSession?.setPlaybackState(playbackState)
        // Also directly inform callback if state is about playback (playing/paused)
        // This might be redundant if called from play/pause methods but ensures consistency
        if (state == PlaybackStateCompat.STATE_PLAYING || state == PlaybackStateCompat.STATE_PAUSED) {
             callback?.onPlaybackStateChanged(state == PlaybackStateCompat.STATE_PLAYING, position.toInt())
        }
    }

    private fun setupProgressUpdater() {
        progressUpdateRunnable = Runnable {
            mediaPlayer?.let {
                if (it.isPlaying) {
                    try {
                        callback?.onProgressUpdate(it.currentPosition, it.duration)
                        progressUpdateHandler.postDelayed(progressUpdateRunnable, 1000)
                    } catch (e: IllegalStateException) {
                        Log.e(TAG, "MediaPlayer in illegal state during progress update.", e)
                    }
                }
            }
        }
    }

    private fun startProgressUpdates() {
        progressUpdateHandler.removeCallbacks(progressUpdateRunnable) // Remove existing
        progressUpdateHandler.post(progressUpdateRunnable) // Post new
    }

    private fun stopProgressUpdates() {
        progressUpdateHandler.removeCallbacks(progressUpdateRunnable)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Music Playback",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Channel for music playback controls"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(song: Song, isPlaying: Boolean): Notification {
        val playPauseIcon = if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        val playPauseActionText = if (isPlaying) "Pause" else "Play"

        val playPausePendingIntent = PendingIntent.getService(this, 1, // Unique request code
            Intent(this, MusicService::class.java).setAction(ACTION_PLAY_PAUSE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val prevPendingIntent = PendingIntent.getService(this, 2, // Unique request code
            Intent(this, MusicService::class.java).setAction(ACTION_PREVIOUS),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val nextPendingIntent = PendingIntent.getService(this, 3, // Unique request code
            Intent(this, MusicService::class.java).setAction(ACTION_NEXT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        val prevIcon = android.R.drawable.ic_media_previous
        val nextIcon = android.R.drawable.ic_media_next
        val actualPlayPauseIcon = if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play


        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(song.title ?: "Unknown Title")
            .setContentText(song.artist ?: "Unknown Artist")
            .setSmallIcon(android.R.drawable.stat_notify_musicplayer)
            .addAction(prevIcon, "Previous", prevPendingIntent)
            .addAction(actualPlayPauseIcon, playPauseActionText, playPausePendingIntent)
            .addAction(nextIcon, "Next", nextPendingIntent)
            .setStyle(androidx.media.app.NotificationCompat.MediaStyle()
                .setMediaSession(mediaSession?.sessionToken)
                .setShowActionsInCompactView(0, 1, 2))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(isPlaying)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    private fun stopSelfAndPlayback() {
        Log.d(TAG, "stopSelfAndPlayback")
        isStopping = true // Mark that service is stopping
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
        mediaSession?.isActive = false
        mediaSession?.release()
        mediaSession = null
        stopProgressUpdates()
        callback?.onServiceStopping() // Inform MainActivity
        stopForeground(true)
        stopSelf()
        isStopping = false // Reset if service somehow doesn't die or is recreated
    }

    private var isStopping: Boolean = false
    fun isServiceStopping(): Boolean = isStopping

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        stopSelfAndPlayback() // Ensure all resources are released
        super.onDestroy()
    }
}
