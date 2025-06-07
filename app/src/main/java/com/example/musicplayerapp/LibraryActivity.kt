package com.example.musicplayerapp

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.provider.MediaStore
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ActionMode
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.TimeUnit

class LibraryActivity : AppCompatActivity() {

    private val TAG = "LibraryActivity"
    private val REQUEST_CODE_READ_STORAGE_LIB = 2

    private lateinit var libraryRecyclerView: RecyclerView
    private lateinit var libraryMessageTextView: TextView
    private lateinit var songAdapter: SongAdapter
    private var musicList: MutableList<Song> = mutableListOf()
    private lateinit var layoutManager: LinearLayoutManager
    private var layoutManagerState: Parcelable? = null

    private var actionMode: ActionMode? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_library)
        title = getString(R.string.library_activity_title)

        if (savedInstanceState != null) {
            layoutManagerState = savedInstanceState.getParcelable("LAYOUT_MANAGER_STATE_LIB")
        }

        libraryRecyclerView = findViewById(R.id.libraryRecyclerView)
        libraryMessageTextView = findViewById(R.id.libraryMessageTextView)

        setupRecyclerView()

        if (checkStoragePermission()) {
            loadMusicFiles()
        } else {
            requestStoragePermission()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::layoutManager.isInitialized) {
            outState.putParcelable("LAYOUT_MANAGER_STATE_LIB", layoutManager.onSaveInstanceState())
        }
    }

    private fun setupRecyclerView() {
        layoutManager = LinearLayoutManager(this@LibraryActivity)
        songAdapter = SongAdapter(
            musicList,
            itemClickListener = { song, position ->
                if (actionMode != null) {
                    toggleSelection(position)
                } else {
                    // Single click: Play song
                    val intent = Intent(this, MainActivity::class.java).apply {
                        action = MusicService.ACTION_PLAY_LIST
                        putParcelableArrayListExtra(MusicService.EXTRA_SONG_LIST, ArrayList(musicList))
                        putExtra(MusicService.EXTRA_SONG_INDEX, position) // Send original position
                        flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    }
                    startActivity(intent)
                    Toast.makeText(this, "Playing: ${song.title}", Toast.LENGTH_SHORT).show()
                }
            },
            itemLongClickListener = { _, position ->
                if (actionMode == null) {
                    actionMode = startSupportActionMode(actionModeCallback)
                }
                toggleSelection(position)
                true
            }
        )
        libraryRecyclerView.apply {
            this.layoutManager = this@LibraryActivity.layoutManager
            adapter = songAdapter
            setHasFixedSize(true) // Optimization if item sizes don't change
        }
    }

    private fun toggleSelection(position: Int) {
        songAdapter.toggleSelection(position)
        if (songAdapter.getSelectedSongItems().isEmpty()) {
            actionMode?.finish()
        } else {
            actionMode?.invalidate() // Triggers onPrepareActionMode to update title
        }
    }

    private val actionModeCallback = object : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            val inflater = mode.menuInflater
            inflater.inflate(R.menu.library_contextual_menu, menu)
            songAdapter.setMultiSelectMode(true)
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
            mode.title = "${songAdapter.getSelectedSongItems().size} selected"
            return true
        }

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            when (item.itemId) {
                R.id.action_queue_selected -> {
                    val selectedSongs = songAdapter.getSelectedSongItems()
                    if (selectedSongs.isNotEmpty()) {
                        val intent = Intent(this@LibraryActivity, MusicService::class.java).apply {
                            action = MusicService.ACTION_QUEUE_SONGS
                            putParcelableArrayListExtra(MusicService.EXTRA_SONGS_TO_QUEUE, ArrayList(selectedSongs))
                        }
                        ContextCompat.startForegroundService(this@LibraryActivity, intent) // Ensure service starts if not running
                        Toast.makeText(this@LibraryActivity, "${selectedSongs.size} songs queued.", Toast.LENGTH_SHORT).show()
                    }
                    mode.finish()
                    return true
                }
                R.id.action_select_all -> {
                    songAdapter.selectAll()
                    actionMode?.invalidate() // Update title
                    return true
                }
                else -> return false
            }
        }

        override fun onDestroyActionMode(mode: ActionMode) {
            songAdapter.setMultiSelectMode(false)
            // clearSelections is called within setMultiSelectMode(false)
            actionMode = null
        }
    }

    private fun checkStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestStoragePermission() {
        val permissionToRequest = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        ActivityCompat.requestPermissions(this, arrayOf(permissionToRequest), REQUEST_CODE_READ_STORAGE_LIB)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_READ_STORAGE_LIB) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                loadMusicFiles()
            } else {
                libraryMessageTextView.text = "Storage permission is required to display music."
                libraryMessageTextView.visibility = View.VISIBLE
                libraryRecyclerView.visibility = View.GONE
            }
        }
    }

    private fun loadMusicFiles() {
        Log.d(TAG, "Loading music files for Library...")
        val tempMusicList = mutableListOf<Song>()
        val projection = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.DATA, MediaStore.Audio.Media.DURATION)
        val selection = MediaStore.Audio.Media.IS_MUSIC + " != 0"
        val sortOrder = MediaStore.Audio.Media.TITLE + " ASC"

        contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection, selection, null, sortOrder)?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID))
                val title = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE))
                val artist = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST))
                val album = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM))
                val path = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA))
                val duration = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION))
                tempMusicList.add(Song(id, title, artist, album, path, duration))
            }
        }

        // musicList is already the source for the adapter, update it directly
        // songAdapter.updateSongs(tempMusicList) // This also clears selections and exits multi-select

        // More controlled update if we want to preserve selection across data refresh (though typically not needed for full refresh)
        val currentSelections = songAdapter.getSelectedPositions().mapNotNull { pos ->
            if (pos < musicList.size) musicList[pos].id else null
        }.toSet()

        musicList.clear()
        musicList.addAll(tempMusicList)
        songAdapter.notifyDataSetChanged() // Adapter uses musicList directly

        // Reapply selections if needed (not typical for a full list refresh)
        // For now, a full refresh will clear selections via songAdapter.updateSongs or implicitly if we call clearSelections.
        // Let's ensure multi-select mode is exited if it was active.
        if (actionMode != null) {
            songAdapter.clearSelections() // Clear adapter's selection state
            actionMode?.finish()      // Finish CAB
        }


        if (musicList.isEmpty()) {
            libraryMessageTextView.text = "No music files found on this device."
            libraryMessageTextView.visibility = View.VISIBLE
            libraryRecyclerView.visibility = View.GONE
        } else {
            libraryMessageTextView.visibility = View.GONE
            libraryRecyclerView.visibility = View.VISIBLE
            layoutManagerState?.let {
                layoutManager.onRestoreInstanceState(it)
                layoutManagerState = null
            }
        }
    }

    private fun formatDuration(duration: Long): String {
        if (duration < 0) return "0:00"
        val minutes = TimeUnit.MILLISECONDS.toMinutes(duration)
        val seconds = TimeUnit.MILLISECONDS.toSeconds(duration) - TimeUnit.MINUTES.toSeconds(minutes)
        return String.format("%02d:%02d", minutes, seconds)
    }
}
