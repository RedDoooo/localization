package com.example.musicplayerapp

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.MediaStore
import android.util.Log
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity(), MusicServiceCallback {

    private val TAG = "MainActivity"
    private val REQUEST_CODE_READ_STORAGE = 1

    private var musicList: MutableList<Song> = mutableListOf()
    // Removed: currentSongIndex, mediaPlayer, handler, originalMusicList (partially, service might need original for shuffle off)
    // Playback state variables like isShuffleOn and repeatMode will be synced from MusicService

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
    private lateinit var navigateToLibraryButton: Button

    // MusicService related
    private var musicService: MusicService? = null
    private var isServiceBound: Boolean = false
    private var pendingPlaylist: ArrayList<Song>? = null
    private var pendingPlaylistIndex: Int = 0

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as MusicService.MusicBinder
            musicService = binder.getService()
            musicService?.setCallback(this@MainActivity)
            isServiceBound = true
            Log.d(TAG, "MusicService connected")
            syncUiWithServiceState()

            pendingPlaylist?.let { playlist ->
                Log.d(TAG, "Found pending playlist. Sending to service.")
                musicService?.setPlaylist(playlist, pendingPlaylistIndex, musicService?.isShuffleEnabled() ?: false, musicService?.getRepeatMode() ?: RepeatMode.OFF)
                pendingPlaylist = null // Clear after sending
                pendingPlaylistIndex = 0
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.d(TAG, "MusicService disconnected")
            musicService?.setCallback(null) // Important to avoid leaks
            musicService = null
            isServiceBound = false
            // Update UI to reflect service unavailability if needed
            playPauseButton.setImageResource(android.R.drawable.ic_media_play)
            songTitleTextView.text = getString(R.string.app_name)
            artistNameTextView.text = ""
            playbackSeekBar.progress = 0
            currentTimeTextView.text = formatDuration(0)
            totalDurationTextView.text = formatDuration(0)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        Log.d(TAG, "onCreate")

        initializeUiElements()

        if (checkStoragePermission()) {
            scanForMusicFiles() // Loads musicList
        } else {
            requestStoragePermission()
        }
        setupButtonClickListeners()
        setupSeekBarListener()
        handleIntent(intent) // Handle intent that might have started the activity
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        Log.d(TAG, "onNewIntent received")
        intent?.let {
            handleIntent(it)
        }
    }


    private fun handleIntent(intent: Intent) {
        if (intent.action == "PLAY_FROM_LIBRARY" || intent.action == MusicService.ACTION_PLAY_LIST) {
            val playlist = intent.getParcelableArrayListExtra<Song>(MusicService.EXTRA_SONG_LIST)
            val startIndex = intent.getIntExtra(MusicService.EXTRA_SONG_INDEX, 0)

            if (playlist != null && playlist.isNotEmpty()) {
                Log.d(TAG, "Intent to play from library/notification: ${playlist.size} songs, starting at $startIndex")
                musicList.clear()
                musicList.addAll(playlist) // Update MainActivity's list as well for consistency

                if (isServiceBound && musicService != null) {
                    Log.d(TAG, "Service bound, setting playlist directly.")
                    // Get current shuffle/repeat from service to maintain state unless specified otherwise
                    val currentShuffle = musicService?.isShuffleEnabled() ?: false
                    val currentRepeat = musicService?.getRepeatMode() ?: RepeatMode.OFF
                    musicService?.setPlaylist(playlist, startIndex, currentShuffle, currentRepeat)
                } else {
                    Log.d(TAG, "Service not bound, storing playlist as pending.")
                    pendingPlaylist = playlist
                    pendingPlaylistIndex = startIndex
                    // Attempt to bind again if not bound, or wait for onServiceConnected
                    if (!isServiceBound) {
                        Intent(this, MusicService::class.java).also { serviceIntent ->
                            bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
                            // Consider also starting the service if it might not be running
                            // ContextCompat.startForegroundService(this, serviceIntent)
                        }
                    }
                }
            } else {
                Log.w(TAG, "Intent PLAY_FROM_LIBRARY/ACTION_PLAY_LIST missing playlist or it's empty.")
            }
             // Prevent re-processing if activity is recreated without a new intent
            setIntent(Intent()) // Clear the intent or its action
        }
    }


    private fun initializeUiElements() {
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
        navigateToLibraryButton = findViewById(R.id.navigateToLibraryButton)
    }

    private fun syncUiWithServiceState() {
        if (!isServiceBound || musicService == null) return
        Log.d(TAG, "Syncing UI with service state")

        val currentSong = musicService?.getCurrentSong()
        onSongChanged(currentSong) // Handles null song

        val isPlaying = musicService?.isPlaying() ?: false
        val currentPosition = musicService?.getCurrentProgress() ?: 0
        onPlaybackStateChanged(isPlaying, currentPosition)

        val duration = musicService?.getDuration() ?: 0
        if (duration > 0) { // Only update if duration is valid
             onProgressUpdate(currentPosition, duration)
        } else { // Reset if duration is 0 or invalid
            playbackSeekBar.max = 100 // Default max
            playbackSeekBar.progress = 0
            totalDurationTextView.text = formatDuration(0)
            currentTimeTextView.text = formatDuration(0)
        }


        val shuffleEnabled = musicService?.isShuffleEnabled() ?: false
        onShuffleModeChanged(shuffleEnabled)

        val currentRepeatMode = musicService?.getRepeatMode() ?: RepeatMode.OFF
        onRepeatModeChanged(currentRepeatMode)
    }


    override fun onStart() {
        super.onStart()
        Log.d(TAG, "onStart - Binding to MusicService")
        Intent(this, MusicService::class.java).also { intent ->
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
            // If you want the service to start even if not playing, uncomment:
            // ContextCompat.startForegroundService(this, intent)
        }
    }

    override fun onStop() {
        Log.d(TAG, "onStop")
        if (isServiceBound) {
            musicService?.setCallback(null) // Remove callback before unbinding
            unbindService(serviceConnection)
            isServiceBound = false
            Log.d(TAG, "MusicService unbound")
        }
        super.onStop()
    }

    private fun formatDuration(duration: Long): String {
        if (duration < 0L) return "0:00"
        val minutes = TimeUnit.MILLISECONDS.toMinutes(duration)
        val seconds = TimeUnit.MILLISECONDS.toSeconds(duration) - TimeUnit.MINUTES.toSeconds(minutes)
        return String.format("%02d:%02d", minutes, seconds)
    }

    private fun checkStoragePermission(): Boolean { // Keep this for initial scan
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestStoragePermission() { // Keep this for initial scan
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
        ActivityCompat.requestPermissions(this, arrayOf(permission), REQUEST_CODE_READ_STORAGE)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_READ_STORAGE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                scanForMusicFiles()
            } else {
                Toast.makeText(this, "Storage permission denied. Cannot load local music.", Toast.LENGTH_LONG).show()
                // Update UI to reflect no music or disabled state for local playback
                songTitleTextView.text = "Permission Denied"
                artistNameTextView.text = "Cannot load local music."
                playPauseButton.isEnabled = false // Or handle differently
            }
        }
    }

    private fun scanForMusicFiles() { // Keep for loading the initial list
        Log.d(TAG, "Scanning for music files...")
        val tempList = mutableListOf<Song>()
        val projection = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.DATA, MediaStore.Audio.Media.DURATION)
        val selection = MediaStore.Audio.Media.IS_MUSIC + " != 0"
        contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection, selection, null, MediaStore.Audio.Media.DEFAULT_SORT_ORDER)
            ?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID))
                    val title = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE))
                    val artist = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST))
                    val album = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM))
                    val path = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA))
                    val duration = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION))
                    tempList.add(Song(id, title, artist, album, path, duration))
                }
            }
        musicList.clear()
        musicList.addAll(tempList)
        Log.d(TAG, "Found ${musicList.size} music files.")
        if (musicList.isEmpty()) {
            songTitleTextView.text = "No Music Found"
            artistNameTextView.text = "Add songs to your device or use Search."
             playPauseButton.isEnabled = false // Only disable if service not playing something else
        } else {
            playPauseButton.isEnabled = true // Enable play button if local songs found
            // Optionally, if service not playing, show first song details but don't play
            if (musicService?.isPlaying() != true && musicService?.getCurrentSong() == null) {
                 onSongChanged(musicList[0])
                 onPlaybackStateChanged(false, 0)
            }
            updatePlaybackControls(true) // Enable controls as we have music
        } else {
            // No music found after scan
            updatePlaybackControls(false) // Disable controls
            if (musicService?.isPlaying() != true) { // Only update text if service isn't playing something else
                songTitleTextView.text = "No Music Found"
                artistNameTextView.text = "Scan again or download songs."
            }
        }
    }

    private fun setupButtonClickListeners() {
        playPauseButton.setOnClickListener {
            if (!isServiceBound) {
                Toast.makeText(this, "Service not connected", Toast.LENGTH_SHORT).show()
                // Attempt to bind/start service again if user tries to play
                Intent(this, MusicService::class.java).also { intent ->
                    bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
                    // ContextCompat.startForegroundService(this, intent) // If service might not be running
                }
                return@setOnClickListener
            }
            if (musicList.isEmpty() && musicService?.getCurrentSong() == null) {
                 Toast.makeText(this, "No music to play. Load songs in library or search.", Toast.LENGTH_SHORT).show()
                 return@setOnClickListener
            }

            if (musicService?.getCurrentSong() == null && musicList.isNotEmpty()) {
                Log.d(TAG, "Play button: No current song in service, starting with MainActivity list.")
                val shuffle = musicService?.isShuffleEnabled() ?: false
                val repeat = musicService?.getRepeatMode() ?: RepeatMode.OFF
                val startIndex = if (shuffle && musicList.isNotEmpty()) (musicList.indices).random() else 0
                if (musicList.isNotEmpty()) {
                    musicService?.setPlaylist(musicList, startIndex, shuffle, repeat)
                } else {
                     Toast.makeText(this, "Music list is empty.", Toast.LENGTH_SHORT).show()
                }
            } else {
                musicService?.togglePlayPause()
            }
        }

        nextButton.setOnClickListener {
            if (!isServiceBound) return@setOnClickListener
            musicService?.skipToNext()
        }
        previousButton.setOnClickListener {
            if (!isServiceBound) return@setOnClickListener
            musicService?.skipToPrevious()
        }

        shuffleButton.setOnClickListener {
            if (!isServiceBound) return@setOnClickListener
            musicService?.toggleShuffle()
        }

        repeatButton.setOnClickListener {
            if (!isServiceBound) return@setOnClickListener
            musicService?.toggleRepeatMode()
        }

        navigateToSearchButton.setOnClickListener { startActivity(Intent(this, SearchActivity::class.java)) }
        navigateToLibraryButton.setOnClickListener {
             val intent = Intent(this, LibraryActivity::class.java)
             // No need to pass data from MainActivity to LibraryActivity directly
             // LibraryActivity loads its own list.
             startActivity(intent)
        }
    }

    private fun setupSeekBarListener() {
        playbackSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    currentTimeTextView.text = formatDuration(progress.toLong())
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                seekBar?.let { musicService?.seekToPosition(it.progress) }
            }
        })
    }

    // --- MusicServiceCallback Implementation ---
    override fun onSongChanged(song: Song?) {
        Log.d(TAG, "Callback: onSongChanged - ${song?.title}")
        runOnUiThread {
            if (song != null) {
                songTitleTextView.text = song.title ?: "Unknown Title"
                artistNameTextView.text = song.artist ?: "Unknown Artist"
                // Update album art here if available
            } else {
                songTitleTextView.text = getString(R.string.app_name) // Default text
                artistNameTextView.text = ""
                // Clear album art
            }
        }
    }

    override fun onPlaybackStateChanged(isPlaying: Boolean, currentPosition: Int) {
        Log.d(TAG, "Callback: onPlaybackStateChanged - isPlaying: $isPlaying, Position: $currentPosition")
        runOnUiThread {
            playPauseButton.setImageResource(if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play)
            // If playback stops, ensure seekbar reflects this if not already handled by onProgressUpdate
            if (!isPlaying) {
                 playbackSeekBar.progress = currentPosition // Sync seekbar to actual position when paused
                 currentTimeTextView.text = formatDuration(currentPosition.toLong())
            }
        }
    }

    override fun onProgressUpdate(progress: Int, duration: Int) {
        // Log.d(TAG, "Callback: onProgressUpdate - Progress: $progress, Duration: $duration") // Can be too noisy
        runOnUiThread {
            if (duration > 0) { // Avoid division by zero or setting invalid max
                playbackSeekBar.max = duration
                totalDurationTextView.text = formatDuration(duration.toLong())
            } else { // Reset if duration is somehow invalid
                playbackSeekBar.max = 100
                totalDurationTextView.text = formatDuration(0)
            }
            playbackSeekBar.progress = progress
            currentTimeTextView.text = formatDuration(progress.toLong())
        }
    }

    override fun onShuffleModeChanged(shuffleEnabled: Boolean) {
        Log.d(TAG, "Callback: onShuffleModeChanged - Enabled: $shuffleEnabled")
        runOnUiThread {
            shuffleButton.alpha = if (shuffleEnabled) 1.0f else 0.5f
        }
    }

    override fun onRepeatModeChanged(repeatMode: RepeatMode) {
        Log.d(TAG, "Callback: onRepeatModeChanged - Mode: $repeatMode")
        runOnUiThread {
            when (repeatMode) {
                RepeatMode.OFF -> { repeatButton.alpha = 0.5f; repeatButton.setImageResource(android.R.drawable.ic_menu_revert) } // Placeholder
                RepeatMode.ONE -> { repeatButton.alpha = 1.0f; repeatButton.setImageResource(android.R.drawable.ic_popup_sync) } // Placeholder
                RepeatMode.ALL -> { repeatButton.alpha = 1.0f; repeatButton.setImageResource(android.R.drawable.ic_menu_rotate) } // Placeholder
            }
        }
    }

    override fun onPlaylistEnded() {
        Log.d(TAG, "Callback: onPlaylistEnded")
        runOnUiThread {
            Toast.makeText(this, "Playlist ended", Toast.LENGTH_SHORT).show()
            playPauseButton.setImageResource(android.R.drawable.ic_media_play)
            // Optionally reset song info display
            // songTitleTextView.text = getString(R.string.app_name)
            // artistNameTextView.text = ""
            playbackSeekBar.progress = 0
            currentTimeTextView.text = formatDuration(0)
        }
    }

    override fun onPlaylistChanged(newPlaylist: List<Song>) {
        Log.d(TAG, "Callback: onPlaylistChanged - New playlist size: ${newPlaylist.size}")
        // Update MainActivity's list to reflect the service's current playlist
        this.musicList.clear()
        this.musicList.addAll(newPlaylist)

        // If the playlist became non-empty, ensure controls are enabled.
        // If it became empty, disable controls (unless service is already stopping).
        val serviceIsStopping = musicService?.isServiceStopping() ?: true // Assume stopping if service is null
        if (isServiceBound && !serviceIsStopping) {
            updatePlaybackControls(newPlaylist.isNotEmpty())
        }
    }

    override fun onServiceStopping() {
        Log.d(TAG, "Callback: onServiceStopping")
        runOnUiThread {
            playPauseButton.setImageResource(android.R.drawable.ic_media_play)
            updatePlaybackControls(false)
            // Optionally reset song info text
            // songTitleTextView.text = getString(R.string.app_name)
            // artistNameTextView.text = ""
        }
    }

    override fun onPlaybackError(errorMsg: String) {
        Log.e(TAG, "Callback: onPlaybackError - $errorMsg")
        runOnUiThread {
            Toast.makeText(this, "Playback Error: $errorMsg", Toast.LENGTH_LONG).show()
            // Update UI to a stopped state
            playPauseButton.setImageResource(android.R.drawable.ic_media_play)
            songTitleTextView.text = "Error"
            artistNameTextView.text = "Playback failed"
            playbackSeekBar.progress = 0
            currentTimeTextView.text = formatDuration(0)
            // Consider disabling controls until a new song is selected or list reloaded
            updatePlaybackControls(musicList.isNotEmpty())
        }
    }


    private fun updatePlaybackControls(enabled: Boolean) {
        Log.d(TAG, "Updating playback controls enabled: $enabled")
        playPauseButton.isEnabled = enabled
        nextButton.isEnabled = enabled
        previousButton.isEnabled = enabled
        playbackSeekBar.isEnabled = enabled
        // shuffleButton.isEnabled = enabled // Shuffle/repeat can be always enabled or tied to playlist presence
        // repeatButton.isEnabled = enabled
    }


    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        // Service unbinding is handled in onStop, but as a safeguard:
        if (isServiceBound) {
            musicService?.setCallback(null)
            unbindService(serviceConnection)
            isServiceBound = false
        }
        super.onDestroy()
    }
}
