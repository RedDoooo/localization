package com.example.musicplayerapp

import android.Manifest
import android.app.DownloadManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLEncoder

class SearchActivity : AppCompatActivity() {

    private val TAG = "SearchActivity"
    private val REQUEST_WRITE_STORAGE_PERMISSION = 124

    private lateinit var searchEditText: EditText
    private lateinit var searchButton: Button
    private lateinit var searchProgressBar: ProgressBar
    private lateinit var searchResultsRecyclerView: RecyclerView
    private lateinit var searchMessageTextView: TextView
    private lateinit var searchResultsAdapter: SearchResultsAdapter

    private var pendingDownloadItem: SearchResultItem? = null

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

        searchButton.setOnClickListener {
            val query = searchEditText.text.toString().trim()
            if (query.isNotEmpty()) {
                performSearchWithUiUpdate(query)
            } else {
                searchMessageTextView.text = "Please enter a search query."
                searchMessageTextView.visibility = View.VISIBLE
                searchResultsRecyclerView.visibility = View.GONE
                searchResultsAdapter.clearData()
            }
        }
    }

    private fun setupRecyclerView() {
        searchResultsAdapter = SearchResultsAdapter(this, mutableListOf()) // Pass activity context
        searchResultsRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@SearchActivity)
            adapter = searchResultsAdapter
        }
    }

    private fun performSearchWithUiUpdate(query: String) {
        searchProgressBar.visibility = View.VISIBLE
        searchMessageTextView.visibility = View.GONE
        searchResultsRecyclerView.visibility = View.GONE
        searchResultsAdapter.clearData()

        lifecycleScope.launch {
            val results = performSearch(query)
            if (results != null) {
                searchProgressBar.visibility = View.GONE
                if (results.isNotEmpty()) {
                    searchResultsAdapter.updateData(results)
                    searchResultsRecyclerView.visibility = View.VISIBLE
                } else {
                    searchMessageTextView.text = getString(R.string.no_results_message)
                    searchMessageTextView.visibility = View.VISIBLE
                }
            } else { // Error case
                searchProgressBar.visibility = View.GONE
                searchMessageTextView.text = getString(R.string.search_error_message)
                searchMessageTextView.visibility = View.VISIBLE
            }
        }
    }

    private suspend fun performSearch(query: String): List<SearchResultItem>? {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val url = "https://www.gequbao.com/s/$encodedQuery"
        Log.d(TAG, "Searching URL: $url")

        return withContext(Dispatchers.IO) {
            try {
                val doc: Document = Jsoup.connect(url)
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
                    .timeout(10000)
                    .get()
                parseSearchResults(doc)
            } catch (e: Exception) {
                Log.e(TAG, "Error during search network call or parsing for query: $query", e)
                null
            }
        }
    }

    private fun parseSearchResults(doc: Document): List<SearchResultItem> {
        val items = mutableListOf<SearchResultItem>()
        val elements = doc.select("div.row.list-group-item")
        Log.d(TAG, "Found ${elements.size} potential result elements.")
        for (element in elements) {
            try {
                val titleElement = element.select("div.col-xs-10.col-md-11 > a.text-primary.lead").first()
                val title = titleElement?.text()?.trim() ?: ""
                val artistAlbumElement = element.select("div.col-xs-10.col-md-11 > small.text-muted").first()
                var artist = artistAlbumElement?.text()?.trim() ?: "Unknown Artist"
                if (artist.contains(" - ")) {
                    artist = artist.substringBefore(" - ").trim()
                }
                val downloadLinkElement = element.select("div.col-xs-2.col-md-1.text-right > a[href*=/music/]").first()
                var downloadUrl = downloadLinkElement?.attr("href") ?: ""
                if (downloadUrl.isNotEmpty() && !downloadUrl.startsWith("http")) {
                    downloadUrl = "https://www.gequbao.com$downloadUrl"
                }
                if (title.isNotEmpty() && downloadUrl.isNotEmpty()) {
                    Log.d(TAG, "Parsed: Title='$title', Artist='$artist', URL='$downloadUrl'")
                    items.add(SearchResultItem(title, artist, downloadUrl))
                } else {
                    Log.w(TAG, "Skipped item, missing title or URL. Title: '$title', URL: '$downloadUrl'")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing a single search result item", e)
            }
        }
        if (items.isEmpty() && elements.isNotEmpty()) {
            Log.w(TAG, "Found result elements but failed to parse any items. Check selectors.")
        }
        return items
    }

    // --- Download Logic ---
    fun initiateDownload(item: SearchResultItem) {
        pendingDownloadItem = item
        if (checkAndRequestStoragePermission()) {
            startDownload(item)
        }
    }

    private fun checkAndRequestStoragePermission(): Boolean {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) { // P is API 28
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQUEST_WRITE_STORAGE_PERMISSION)
                return false
            }
        }
        // For API 29+ (Q), DownloadManager saves to app-specific directory in shared storage or public collections
        // without needing explicit WRITE_EXTERNAL_STORAGE for those specific locations.
        return true
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_WRITE_STORAGE_PERMISSION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "WRITE_EXTERNAL_STORAGE permission granted.")
                pendingDownloadItem?.let {
                    startDownload(it)
                }
            } else {
                Log.d(TAG, "WRITE_EXTERNAL_STORAGE permission denied.")
                Toast.makeText(this, "Storage permission is required to download songs.", Toast.LENGTH_LONG).show()
                pendingDownloadItem = null
            }
        }
    }

    private fun startDownload(item: SearchResultItem) {
        if (item.downloadUrl.isEmpty()) {
            Toast.makeText(this, "Download URL is missing.", Toast.LENGTH_SHORT).show()
            pendingDownloadItem = null
            return
        }

        // Sanitize title for filename
        val fileName = (item.title.replace(Regex("[^a-zA-Z0-9\\s.-]"), "_") + "_" + (item.artist.replace(Regex("[^a-zA-Z0-9\\s.-]"), "_"))).take(100) + ".mp3"


        val downloadRequest = DownloadManager.Request(Uri.parse(item.downloadUrl))
        downloadRequest.setTitle(item.title)
        downloadRequest.setArtist(item.artist) // Set artist metadata if available and supported
        downloadRequest.setDescription("Downloading ${item.title} - ${item.artist}")
        downloadRequest.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)

        // Save to public Music directory in a "MusicPlayerApp" subfolder
        // This works for all API levels, DownloadManager handles appropriate storage.
        downloadRequest.setDestinationInExternalPublicDir(Environment.DIRECTORY_MUSIC, "MusicPlayerApp/" + fileName)

        downloadRequest.setAllowedOverMetered(true) // Allow download over mobile data
        downloadRequest.setMimeType("audio/mpeg") // Assume mp3

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
