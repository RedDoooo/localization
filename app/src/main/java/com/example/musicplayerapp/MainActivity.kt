package com.example.musicplayerapp

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.IOException
import java.util.concurrent.TimeUnit

// Define RepeatMode enum
enum class RepeatMode {
    OFF, ONE, ALL
}

class MainActivity : AppCompatActivity() {

    private val TAG = "MainActivity"
    private val REQUEST_CODE_READ_STORAGE = 1

    private var musicList: List<Song> = emptyList()
    private var originalMusicList: List<Song> = emptyList() // For shuffle
    private var currentSongIndex: Int = -1 // Index in the current list (musicList or a conceptual shuffled view)
    private var mediaPlayer: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())

    // Playback state variables
    private var isShuffleOn: Boolean = false
    private var repeatMode: RepeatMode = RepeatMode.OFF
    // private var shuffledIndices: MutableList<Int> = mutableListOf() // Not using this simpler shuffle for now

    // UI Elements
    private lateinit var albumArtImageView: ImageView
    private lateinit var songTitleTextView: TextView
    private lateinit var artistNameTextView: TextView
    private lateinit var playbackSeekBar: SeekBar
    private lateinit var currentTimeTextView: TextView
    private lateinit var totalDurationTextView: TextView
    private lateinit var playPauseButton: ImageButton
    private lateinit var nextButton: ImageButton
    private lateinit var previousButton: ImageButton
    private lateinit var shuffleButton: ImageButton
    private lateinit var repeatButton: ImageButton
    private lateinit var navigateToSearchButton: Button
    private lateinit var navigateToLibraryButton: Button // Added for library navigation


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize UI elements
        albumArtImageView = findViewById(R.id.albumArtImageView)
        songTitleTextView = findViewById(R.id.songTitleTextView)
        artistNameTextView = findViewById(R.id.artistNameTextView)
        playbackSeekBar = findViewById(R.id.playbackSeekBar)
        currentTimeTextView = findViewById(R.id.currentTimeTextView)
        totalDurationTextView = findViewById(R.id.totalDurationTextView)
        playPauseButton = findViewById(R.id.playPauseButton)
        nextButton = findViewById(R.id.nextButton)
        previousButton = findViewById(R.id.previousButton)
        shuffleButton = findViewById(R.id.shuffleButton)
        repeatButton = findViewById(R.id.repeatButton)
        navigateToSearchButton = findViewById(R.id.navigateToSearchButton)
        navigateToLibraryButton = findViewById(R.id.navigateToLibraryButton) // Initialize library button


        if (checkStoragePermission()) {
            scanForMusicFiles()
        } else {
            requestStoragePermission()
        }

        setupButtonClickListeners()
        setupSeekBarListener()
    }

    private fun formatDuration(duration: Long): String {
        val minutes = TimeUnit.MILLISECONDS.toMinutes(duration)
        val seconds = TimeUnit.MILLISECONDS.toSeconds(duration) -
                TimeUnit.MINUTES.toSeconds(minutes)
        return String.format("%02d:%02d", minutes, seconds)
    }

    private fun checkStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_MEDIA_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.READ_MEDIA_AUDIO),
                REQUEST_CODE_READ_STORAGE
            )
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE),
                REQUEST_CODE_READ_STORAGE
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_READ_STORAGE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "Storage permission granted")
                scanForMusicFiles()
                // If music is found, you might want to automatically play the first song
                // or enable the play button here.
                if (musicList.isNotEmpty()) {
                    // Example: playSong(0) // Or just enable UI
                }
            } else {
                Log.d(TAG, "Storage permission denied")
                // Handle permission denial (e.g., show a message to the user, disable music features)
                songTitleTextView.text = "Permission Denied"
                artistNameTextView.text = "Please grant storage access to play music."
                playPauseButton.isEnabled = false
                nextButton.isEnabled = false
                previousButton.isEnabled = false
                playbackSeekBar.isEnabled = false
            }
        }
    }

    private fun scanForMusicFiles() {
        Log.d(TAG, "Scanning for music files...")
        val tempMusicList = mutableListOf<Song>()

        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DATA, // File path
            MediaStore.Audio.Media.DURATION
        )

        val selection = MediaStore.Audio.Media.IS_MUSIC + " != 0"

        val cursor = contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            null,
            MediaStore.Audio.Media.DEFAULT_SORT_ORDER
        )

        cursor?.use { c ->
            val idColumn = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleColumn = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistColumn = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumColumn = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val pathColumn = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
            val durationColumn = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)

            while (c.moveToNext()) {
                val id = c.getLong(idColumn)
                val title = c.getString(titleColumn)
                val artist = c.getString(artistColumn)
                val album = c.getString(albumColumn)
                val path = c.getString(pathColumn)
                val duration = c.getLong(durationColumn)

                val song = Song(id, title, artist, album, path, duration)
                tempMusicList.add(song)
                Log.d(TAG, "Found song: ${song.title} by ${song.artist}")
            }
        }
        musicList = tempMusicList
        if (musicList.isEmpty()) {
            Log.d(TAG, "No music files found.")
            songTitleTextView.text = "No Music Found"
            artistNameTextView.text = "Please add music to your device."
            playPauseButton.isEnabled = false
            nextButton.isEnabled = false
            previousButton.isEnabled = false
            playbackSeekBar.isEnabled = false
        } else {
            Log.d(TAG, "Found ${musicList.size} music files.")
            // Enable UI elements if they were disabled
            playPauseButton.isEnabled = true
            nextButton.isEnabled = true
            previousButton.isEnabled = true
            playbackSeekBar.isEnabled = true
            originalMusicList = ArrayList(musicList) // Store the original order by creating a new list
            // Optionally, load the first song's details into UI but don't play yet
            // displaySongDetails(0)
        }
    }

    private fun setupButtonClickListeners() {
        playPauseButton.setOnClickListener {
            if (musicList.isEmpty()) return@setOnClickListener

            if (mediaPlayer == null) {
                currentSongIndex = if (isShuffleOn && musicList.isNotEmpty()) {
                    (0 until musicList.size).random()
                } else {
                    0
                }
                if(currentSongIndex != -1 && currentSongIndex < musicList.size) { // Ensure valid index before playing
                    playSong(currentSongIndex)
                } else if (musicList.isNotEmpty()) { // Fallback if random somehow failed or list became empty
                    playSong(0)
                }
            } else if (mediaPlayer?.isPlaying == true) {
                mediaPlayer?.pause()
                playPauseButton.setImageResource(android.R.drawable.ic_media_play)
                stopSeekBarUpdate()
            } else {
                mediaPlayer?.start()
                playPauseButton.setImageResource(android.R.drawable.ic_media_pause)
                startSeekBarUpdate()
            }
        }

        nextButton.setOnClickListener {
            playNextSong()
        }

        previousButton.setOnClickListener {
            playPreviousSong()
        }

        shuffleButton.setOnClickListener {
            isShuffleOn = !isShuffleOn
            it.alpha = if (isShuffleOn) 1.0f else 0.5f
            Log.d("PlaybackControls", "Shuffle mode: $isShuffleOn")
            if (isShuffleOn) {
                if (originalMusicList.isEmpty() && musicList.isNotEmpty()) {
                     originalMusicList = ArrayList(musicList) // Make a copy
                }
                Log.d(TAG, "Shuffle ON. Original list size: ${originalMusicList.size}")
            } else {
                Log.d(TAG, "Shuffle OFF. Music list size: ${musicList.size}")
            }
        }

        repeatButton.setOnClickListener {
            repeatMode = when (repeatMode) {
                RepeatMode.OFF -> RepeatMode.ONE
                RepeatMode.ONE -> RepeatMode.ALL
                RepeatMode.ALL -> RepeatMode.OFF
            }
            when (repeatMode) {
                RepeatMode.OFF -> { it.alpha = 0.5f; (it as ImageButton).setImageResource(android.R.drawable.ic_menu_revert); Log.d("PlaybackControls", "Repeat: OFF (using revert icon as placeholder for OFF state)") } // Placeholder for OFF
                RepeatMode.ONE -> { it.alpha = 1.0f; (it as ImageButton).setImageResource(android.R.drawable.ic_popup_sync); Log.d("PlaybackControls", "Repeat: ONE (using sync icon as placeholder)") }
                RepeatMode.ALL -> { it.alpha = 1.0f; (it as ImageButton).setImageResource(android.R.drawable.ic_menu_rotate); Log.d("PlaybackControls", "Repeat: ALL (using rotate icon as placeholder)") }
            }
        }

        navigateToSearchButton.setOnClickListener {
            val intent = Intent(this, SearchActivity::class.java)
            startActivity(intent)
        }

        navigateToLibraryButton.setOnClickListener {
            val intent = Intent(this, LibraryActivity::class.java)
            startActivity(intent)
        }
    }

    private fun setupSeekBarListener() {
        playbackSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    // Update current time text view as user scrubs
                    currentTimeTextView.text = formatDuration(progress.toLong())
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                // User started dragging the seek bar
                if (mediaPlayer?.isPlaying == true) { // Only stop updates if playing
                    stopSeekBarUpdate()
                }
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                seekBar?.let {
                    mediaPlayer?.seekTo(it.progress)
                    if (mediaPlayer?.isPlaying == true) { // Only resume updates if it was playing
                        startSeekBarUpdate()
                    } else { // If paused, just update current time once
                        currentTimeTextView.text = formatDuration(it.progress.toLong())
                    }
                }
            }
        })
    }

    private fun playSong(songIndex: Int) {
        if (songIndex < 0 || songIndex >= musicList.size) {
            Log.e(TAG, "Invalid song index: $songIndex or musicList is empty.")
            return
        }

        val song = musicList[songIndex]

        try {
            mediaPlayer?.release() // Release any existing player
            mediaPlayer = MediaPlayer().apply {
                setDataSource(song.path)
                setOnPreparedListener { mp ->
                    Log.d(TAG, "MediaPlayer prepared for: ${song.title}")
                    songTitleTextView.text = song.title ?: "Unknown Title"
                    artistNameTextView.text = song.artist ?: "Unknown Artist"
                    totalDurationTextView.text = formatDuration(mp.duration.toLong())
                    playbackSeekBar.max = mp.duration
                    mp.start()
                    playPauseButton.setImageResource(android.R.drawable.ic_media_pause)
                    currentSongIndex = songIndex
                    startSeekBarUpdate()
                }
                setOnCompletionListener {
                    Log.d(TAG, "Song completed: ${song.title}")
                    stopSeekBarUpdate() // Stop updates first
                    if (repeatMode == RepeatMode.ONE) {
                        mediaPlayer?.seekTo(0)
                        mediaPlayer?.start()
                        startSeekBarUpdate() // Restart updates
                        // Icon should remain pause as it's still playing
                        playPauseButton.setImageResource(android.R.drawable.ic_media_pause)
                    } else {
                        // currentSongIndex might be updated by playNextSong, or it might determine that playback stops.
                        // Store current state of isPlaying before calling playNextSong
                        val wasPlayingBeforeNext = mediaPlayer?.isPlaying ?: false

                        playNextSong()

                        // If playNextSong did not result in a new song playing (e.g. end of list and RepeatMode.OFF)
                        // then we need to set the UI to the stopped/paused state.
                        if (mediaPlayer?.isPlaying != true) {
                             playPauseButton.setImageResource(android.R.drawable.ic_media_play)
                             playbackSeekBar.progress = 0 // Reset seekbar for the stopped song
                             currentTimeTextView.text = formatDuration(0)
                             // If the song was playing and now it's not (because playNextSong decided to stop),
                             // we might also want to update song details to reflect nothing is playing or clear them.
                             if (wasPlayingBeforeNext) {
                                 // Optionally clear song title/artist or set to a default "stopped" state
                                 // songTitleTextView.text = getString(R.string.app_name) // Example
                                 // artistNameTextView.text = ""
                             }
                        }
                    }
                }
                setOnErrorListener { mp, what, extra ->
                    Log.e(TAG, "MediaPlayer Error: what: $what, extra: $extra for song ${song.title ?: "Unknown"}")
                    // Handle error, e.g., skip to next song, show error message
                    playPauseButton.setImageResource(android.R.drawable.ic_media_play)
                    stopSeekBarUpdate()
                    true // Error handled
                }
                prepareAsync() // Prepare asynchronously
            }
            Log.d(TAG, "MediaPlayer preparing for: ${song.title}")
        } catch (e: IOException) {
            Log.e(TAG, "MediaPlayer IOException for song ${song.path}", e)
            // Handle error (e.g., show message to user)
        } catch (e: IllegalStateException) {
            Log.e(TAG, "MediaPlayer IllegalStateException for song ${song.path ?: "Unknown"}", e)
        }
    }

    private fun playNextSong() {
        if (musicList.isEmpty()) {
            Log.d(TAG, "playNextSong: Music list is empty.")
            playPauseButton.setImageResource(android.R.drawable.ic_media_play) // Ensure UI reflects stopped state
            stopSeekBarUpdate()
            playbackSeekBar.progress = 0
            currentTimeTextView.text = formatDuration(0)
            return
        }

        var nextIndex = -1

        if (isShuffleOn) {
            if (musicList.size == 1) { // Only one song
                 // If repeat one is on, onCompletion will handle it. If repeat all, it's like repeat one. If off, it stops.
                if (repeatMode == RepeatMode.ALL || repeatMode == RepeatMode.ONE) {
                    nextIndex = currentSongIndex // Replay the same song
                } else { // RepeatMode.OFF
                    Log.d(TAG, "playNextSong (Shuffle): Only one song, RepeatMode.OFF. Playback stops.")
                    playPauseButton.setImageResource(android.R.drawable.ic_media_play)
                    stopSeekBarUpdate()
                    // Don't reset currentSongIndex, so if user presses play again, it replays the same song.
                    return
                }
            } else { // More than one song
                var potentialNextIndex = (0 until musicList.size).random()
                // Try not to pick the same song immediately unless it's the only option left after several tries (unlikely with large lists)
                var attempts = 0
                while (potentialNextIndex == currentSongIndex && attempts < musicList.size / 2) {
                    potentialNextIndex = (0 until musicList.size).random()
                    attempts++
                }
                nextIndex = potentialNextIndex
            }
        } else { // Sequential
            nextIndex = currentSongIndex + 1
        }

        // Boundary and RepeatMode.ALL logic
        if (nextIndex >= musicList.size) {
            if (repeatMode == RepeatMode.ALL) {
                nextIndex = 0
            } else { // RepeatMode.OFF (or RepeatMode.ONE, but that's handled by onCompletion)
                Log.d(TAG, "playNextSong: End of playlist. RepeatMode: $repeatMode")
                playPauseButton.setImageResource(android.R.drawable.ic_media_play)
                stopSeekBarUpdate()
                // Reset progress for the UI, but keep currentSongIndex at the end.
                // So if user hits previous, it goes to the last song.
                playbackSeekBar.progress = 0
                currentTimeTextView.text = formatDuration(0)
                // Let onCompletion handle the final UI state if called from there.
                // If called directly (e.g. user presses next at last song with repeat off), this is the behavior.
                return
            }
        }

        // prevIndex < 0 is not applicable for playNextSong

        if (nextIndex != -1 && nextIndex < musicList.size) { // Ensure nextIndex is valid
            playSong(nextIndex)
        } else {
             Log.d(TAG, "playNextSong: No valid next index determined or playback stopped. Next proposed: $nextIndex, Current: $currentSongIndex, Shuffle: $isShuffleOn, Repeat: $repeatMode")
        }
    }

    private fun playPreviousSong() {
        if (musicList.isEmpty()) {
            Log.d(TAG, "playPreviousSong: Music list is empty.")
            return
        }
        var prevIndex = -1

        if (isShuffleOn) {
             if (musicList.size == 1) { // Similar logic to playNextSong for single item list
                if (repeatMode == RepeatMode.ALL || repeatMode == RepeatMode.ONE) {
                    prevIndex = currentSongIndex
                } else {
                    Log.d(TAG, "playPreviousSong (Shuffle): Only one song, RepeatMode.OFF. No change.")
                    return
                }
            } else { // More than one song
                var potentialPrevIndex = (0 until musicList.size).random()
                var attempts = 0
                while (potentialPrevIndex == currentSongIndex && attempts < musicList.size / 2) {
                    potentialPrevIndex = (0 until musicList.size).random()
                    attempts++
                }
                prevIndex = potentialPrevIndex
            }
        } else { // Sequential
            prevIndex = currentSongIndex - 1
        }

        // Boundary and RepeatMode.ALL logic
        if (prevIndex < 0) {
            if (repeatMode == RepeatMode.ALL) {
                prevIndex = musicList.size - 1
                if (prevIndex < 0) { // Should not happen if musicList is not empty
                     Log.d(TAG, "playPreviousSong: Music list became empty during wrap around for ALL.")
                     return
                }
            } else { // RepeatMode.OFF or RepeatMode.ONE
                Log.d(TAG, "playPreviousSong: Start of playlist. RepeatMode: $repeatMode")
                // Play the first song again or just stay, let's make it play the first song.
                prevIndex = 0
            }
        }

         if (prevIndex != -1 && prevIndex < musicList.size) { // Ensure prevIndex is valid
            playSong(prevIndex)
        } else {
            Log.d(TAG, "playPreviousSong: No valid previous index found. Prev proposed: $prevIndex, Current: $currentSongIndex, Shuffle: $isShuffleOn, Repeat: $repeatMode")
        }
    }

    private val updateSeekBarRunnable = object : Runnable {
        override fun run() {
            mediaPlayer?.let {
                try {
                    if (it.isPlaying) {
                        val currentPosition = it.currentPosition
                        playbackSeekBar.progress = currentPosition
                        currentTimeTextView.text = formatDuration(currentPosition.toLong())
                        handler.postDelayed(this, 1000) // Update every second
                    }
                } catch (e: IllegalStateException) {
                    Log.e(TAG, "updateSeekBarRunnable: MediaPlayer released or in invalid state.", e)
                }
            }
        }
    }

    private fun startSeekBarUpdate() {
        // Remove any existing callbacks to prevent multiple updates
        handler.removeCallbacks(updateSeekBarRunnable)
        handler.post(updateSeekBarRunnable)
    }

    private fun stopSeekBarUpdate() {
        handler.removeCallbacks(updateSeekBarRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaPlayer?.release()
        mediaPlayer = null
        stopSeekBarUpdate()
    }
}
