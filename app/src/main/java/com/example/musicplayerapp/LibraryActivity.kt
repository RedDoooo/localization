package com.example.musicplayerapp

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.TimeUnit

class LibraryActivity : AppCompatActivity() {

    private val TAG = "LibraryActivity"
    private val REQUEST_CODE_READ_STORAGE_LIB = 2 // Different request code from MainActivity

    private lateinit var libraryRecyclerView: RecyclerView
    private lateinit var libraryMessageTextView: TextView
    private lateinit var songAdapter: SongAdapter
    private var musicList: MutableList<Song> = mutableListOf()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_library)
        title = getString(R.string.library_activity_title)


        libraryRecyclerView = findViewById(R.id.libraryRecyclerView)
        libraryMessageTextView = findViewById(R.id.libraryMessageTextView)

        setupRecyclerView()

        if (checkStoragePermission()) {
            loadMusicFiles()
        } else {
            requestStoragePermission()
        }
    }

    private fun setupRecyclerView() {
        songAdapter = SongAdapter(musicList) { song ->
            Log.d(TAG, "Clicked song: ${song.title} by ${song.artist}, Path: ${song.path}")
            // TODO: Implement playback by sending this song to MainActivity or a playback service
            // For now, we can show a Toast or log
            android.widget.Toast.makeText(this, "Playing: ${song.title}", android.widget.Toast.LENGTH_SHORT).show()
        }
        libraryRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@LibraryActivity)
            adapter = songAdapter
        }
    }

    private fun checkStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { // Android 13+
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
        val permissionToRequest = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        ActivityCompat.requestPermissions(
            this,
            arrayOf(permissionToRequest),
            REQUEST_CODE_READ_STORAGE_LIB
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_READ_STORAGE_LIB) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "Storage permission granted for Library.")
                loadMusicFiles()
            } else {
                Log.d(TAG, "Storage permission denied for Library.")
                libraryMessageTextView.text = "Storage permission is required to display music."
                libraryMessageTextView.visibility = View.VISIBLE
                libraryRecyclerView.visibility = View.GONE
            }
        }
    }

    private fun loadMusicFiles() {
        Log.d(TAG, "Loading music files for Library...")
        val tempMusicList = mutableListOf<Song>()

        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.DURATION
        )

        val selection = MediaStore.Audio.Media.IS_MUSIC + " != 0"
        val sortOrder = MediaStore.Audio.Media.TITLE + " ASC" // Sort by title

        contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            null,
            sortOrder
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val pathColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
            val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val title = cursor.getString(titleColumn)
                val artist = cursor.getString(artistColumn)
                val album = cursor.getString(albumColumn)
                val path = cursor.getString(pathColumn)
                val duration = cursor.getLong(durationColumn)
                tempMusicList.add(Song(id, title, artist, album, path, duration))
            }
        }

        musicList.clear()
        musicList.addAll(tempMusicList)
        songAdapter.updateSongs(musicList) // Update adapter

        if (musicList.isEmpty()) {
            libraryMessageTextView.text = "No music files found on this device."
            libraryMessageTextView.visibility = View.VISIBLE
            libraryRecyclerView.visibility = View.GONE
            Log.d(TAG, "No music files found for Library.")
        } else {
            libraryMessageTextView.visibility = View.GONE
            libraryRecyclerView.visibility = View.VISIBLE
            Log.d(TAG, "Loaded ${musicList.size} music files for Library.")
        }
    }

    // Helper function (can be moved to a Util class if used elsewhere often)
    private fun formatDuration(duration: Long): String {
        if (duration < 0) return "0:00"
        val minutes = TimeUnit.MILLISECONDS.toMinutes(duration)
        val seconds = TimeUnit.MILLISECONDS.toSeconds(duration) -
                TimeUnit.MINUTES.toSeconds(minutes)
        return String.format("%02d:%02d", minutes, seconds)
    }
}
