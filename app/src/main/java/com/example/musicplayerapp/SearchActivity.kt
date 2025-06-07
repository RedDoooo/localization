package com.example.musicplayerapp

import android.Manifest
import android.app.DownloadManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Parcelable
import android.util.Log
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels // Import for by viewModels()
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView


class SearchActivity : AppCompatActivity() {

    private val TAG = "SearchActivity"
    private val REQUEST_WRITE_STORAGE_PERMISSION = 124

    private lateinit var searchEditText: EditText
    private lateinit var searchButton: Button
    private lateinit var searchProgressBar: ProgressBar
    private lateinit var searchResultsRecyclerView: RecyclerView
    private lateinit var searchMessageTextView: TextView
    private lateinit var searchResultsAdapter: SearchResultsAdapter
    private lateinit var layoutManager: LinearLayoutManager


    private var pendingDownloadItem: SearchResultItem? = null
    private val viewModel: SearchViewModel by viewModels()

    // For RecyclerView scroll state
    private var layoutManagerState: Parcelable? = null
    private val GITHUB_SEARCH_URL_BASE = "https://www.gequbao.com/s/" // Example base URL


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_search)
        title = getString(R.string.search_activity_title)

        searchEditText = findViewById(R.id.searchEditText)
        searchButton = findViewById(R.id.searchButton)
        searchProgressBar = findViewById(R.id.searchProgressBar)
        searchResultsRecyclerView = findViewById(R.id.searchResultsRecyclerView)
        searchMessageTextView = findViewById(R.id.searchMessageTextView)

        setupRecyclerView()
        setupObservers()

        searchButton.setOnClickListener {
            val query = searchEditText.text.toString().trim()
            viewModel.performSearch(query, getString(R.string.no_results_message), getString(R.string.search_error_message))
            // Hide keyboard
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.hideSoftInputFromWindow(searchEditText.windowToken, 0)
        }

        searchEditText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                searchButton.performClick()
                true
            } else {
                false
            }
        }

        if (savedInstanceState != null) {
            layoutManagerState = savedInstanceState.getParcelable("LAYOUT_MANAGER_STATE")
            viewModel.currentQuery = savedInstanceState.getString("CURRENT_QUERY") ?: ""
            // ViewModel automatically retains LiveData, but we restore query for EditText
            searchEditText.setText(viewModel.currentQuery)
            // If there were results, ViewModel will provide them via LiveData.
            // If a message was showing, ViewModel will provide it.
        } else {
            // If it's a fresh start and there's a query (e.g. from a previous non-config-change session),
            // you might want to trigger search or restore results.
            // For now, if query is not blank, it means it was likely set by savedInstanceState or user typed before rotation
            if (viewModel.currentQuery.isNotBlank() && viewModel.searchResults.value.isNullOrEmpty()) {
                 // This ensures if activity is destroyed and recreated (not just rotated),
                 // and if viewmodel survived, it can re-trigger search if results are empty.
                 // However, viewModel.performSearch is already robust. Let's rely on LiveData.
            }
        }
        // Restore last results if available and not currently loading
        if (viewModel.isLoading.value == false && !viewModel.searchResults.value.isNullOrEmpty()) {
             viewModel.restoreLastResults()
        }

    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::layoutManager.isInitialized) { // Check if layoutManager has been initialized
            outState.putParcelable("LAYOUT_MANAGER_STATE", layoutManager.onSaveInstanceState())
        }
        outState.putString("CURRENT_QUERY", viewModel.currentQuery)
    }


    private fun setupRecyclerView() {
        layoutManager = LinearLayoutManager(this) // Initialize here
        searchResultsAdapter = SearchResultsAdapter(this, mutableListOf())
        searchResultsRecyclerView.apply {
            this.layoutManager = this@SearchActivity.layoutManager // Assign the member variable
            adapter = searchResultsAdapter
        }
    }

    private fun setupObservers() {
        viewModel.searchResults.observe(this) { results ->
            Log.d(TAG, "Observer: searchResults changed, ${results.size} items")
            searchResultsAdapter.updateData(results)
            if (results.isNotEmpty()) {
                searchResultsRecyclerView.visibility = View.VISIBLE
                searchMessageTextView.visibility = View.GONE
                layoutManagerState?.let {
                    layoutManager.onRestoreInstanceState(it)
                    layoutManagerState = null // Consume state
                }
            } else if (viewModel.isLoading.value == false && viewModel.message.value == null) {
                // If not loading and no specific message, but results are empty (e.g. after a search that yielded nothing)
                // The message LiveData should handle "no results"
            }
        }

        viewModel.isLoading.observe(this) { isLoading ->
            Log.d(TAG, "Observer: isLoading changed to $isLoading")
            searchProgressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
            if (isLoading) {
                searchResultsRecyclerView.visibility = View.GONE // Hide results while loading new ones
                searchMessageTextView.visibility = View.GONE // Hide messages while loading
            }
        }

        viewModel.message.observe(this) { message ->
            Log.d(TAG, "Observer: message changed to $message")
            if (message != null) {
                searchMessageTextView.text = message
                searchMessageTextView.visibility = View.VISIBLE
                searchResultsRecyclerView.visibility = View.GONE // Hide results when a message is shown
            } else {
                searchMessageTextView.visibility = View.GONE
            }
        }
    }


    // --- Download Logic (remains in Activity as it involves system services and UI like Toast/Permissions) ---
    fun initiateDownload(item: SearchResultItem) {
        pendingDownloadItem = item
        if (checkAndRequestStoragePermission()) {
            startDownload(item)
        }
    }

    private fun checkAndRequestStoragePermission(): Boolean {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQUEST_WRITE_STORAGE_PERMISSION)
                return false
            }
        }
        return true
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_WRITE_STORAGE_PERMISSION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "WRITE_EXTERNAL_STORAGE permission granted.")
                pendingDownloadItem?.let { startDownload(it) }
            } else {
                Log.d(TAG, "WRITE_EXTERNAL_STORAGE permission denied.")
                Toast.makeText(this, "Storage permission is required to download songs.", Toast.LENGTH_LONG).show()
                pendingDownloadItem = null
            }
        }
    }

    private fun startDownload(item: SearchResultItem) {
        if (item.downloadUrl.isEmpty() || !item.downloadUrl.startsWith("http")) { // Basic validation
            Toast.makeText(this, "Invalid download URL: ${item.downloadUrl}", Toast.LENGTH_SHORT).show()
            pendingDownloadItem = null
            return
        }

        val fileName = (item.title.replace(Regex("[^a-zA-Z0-9\\s.-]"), "_") + "_" + (item.artist.replace(Regex("[^a-zA-Z0-9\\s.-]"), "_"))).take(100) + ".mp3"

        val downloadRequest = DownloadManager.Request(Uri.parse(item.downloadUrl))
        downloadRequest.setTitle(item.title)
        // downloadRequest.setArtist(item.artist) // Not a standard DownloadManager field
        downloadRequest.setDescription("Downloading ${item.title} - ${item.artist}")
        downloadRequest.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        downloadRequest.setDestinationInExternalPublicDir(Environment.DIRECTORY_MUSIC, "MusicPlayerApp/" + fileName)
        downloadRequest.setAllowedOverMetered(true)
        downloadRequest.setMimeType("audio/mpeg")

        val manager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        try {
            manager.enqueue(downloadRequest)
            Toast.makeText(this, "Starting download: ${item.title}", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "Error enqueuing download", e)
            Toast.makeText(this, "Error starting download: ${e.message}", Toast.LENGTH_LONG).show()
        }
        pendingDownloadItem = null
    }
}
