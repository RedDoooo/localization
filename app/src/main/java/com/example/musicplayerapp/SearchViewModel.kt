package com.example.musicplayerapp

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLEncoder

class SearchViewModel : ViewModel() {
    private val _searchResults = MutableLiveData<List<SearchResultItem>>()
    val searchResults: LiveData<List<SearchResultItem>> = _searchResults

    private val _isLoading = MutableLiveData<Boolean>()
    val isLoading: LiveData<Boolean> = _isLoading

    private val _message = MutableLiveData<String?>()
    val message: LiveData<String?> = _message

    var currentQuery: String = ""
    private var lastResults: List<SearchResultItem>? = null

    // Base URL for the search, defined here for clarity
    // It's better to pass this as a parameter or get from a config if it can change
    private val defaultSearchUrlBase = "https://www.gequbao.com/s/" // Example
    private val siteBaseUrl = "https://www.gequbao.com"          // For relative links

    fun performSearch(query: String, noResultsMsg: String, errorMsg: String) {
        if (query.isBlank()) {
            _message.value = "Please enter a search query."
            _searchResults.value = emptyList()
            lastResults = emptyList()
            return
        }
        currentQuery = query
        _isLoading.value = true
        _message.value = null
        // Don't clear results immediately if you want to show old results while new ones load.
        // Or clear them like this if that's the desired UX:
        _searchResults.value = emptyList()


        viewModelScope.launch {
            try {
                val encodedQuery = URLEncoder.encode(query, "UTF-8")
                // The URL structure for gequbao.com is /s/keyword (no .html usually)
                val url = "$defaultSearchUrlBase$encodedQuery"
                Log.d("SearchViewModel", "Effective Search URL: $url")

                val doc = withContext(Dispatchers.IO) {
                    Jsoup.connect(url)
                        .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
                        .timeout(10000) // 10 seconds
                        .get()
                }
                val results = parseSearchResults(doc)

                if (results.isNotEmpty()) {
                    _searchResults.postValue(results)
                    lastResults = results
                } else {
                    _message.postValue(noResultsMsg)
                    _searchResults.postValue(emptyList()) // Ensure observer is notified of empty list
                    lastResults = emptyList()
                }
            } catch (e: Exception) {
                Log.e("SearchViewModel", "Search error for query '$query'", e)
                _message.postValue("$errorMsg: ${e.message}")
                _searchResults.postValue(emptyList())
                lastResults = emptyList()
            } finally {
                _isLoading.postValue(false)
            }
        }
    }

    // Using the previously defined parsing logic, adapted for ViewModel
    private fun parseSearchResults(doc: Document): List<SearchResultItem> {
        val items = mutableListOf<SearchResultItem>()
        // HYPOTHETICAL selectors for gequbao.com
        val elements = doc.select("div.row.list-group-item") // Main container for each item
        Log.d("SearchViewModel", "Found ${elements.size} potential result elements in document.")

        for (element in elements) {
            try {
                val titleElement = element.select("div.col-xs-10.col-md-11 > a.text-primary.lead").first()
                val title = titleElement?.text()?.trim() ?: "Unknown Title"

                // Artist might be in a <small> tag or similar
                val artistAlbumElement = element.select("div.col-xs-10.col-md-11 > small.text-muted").first()
                var artist = artistAlbumElement?.text()?.trim() ?: "Unknown Artist"
                // Simple split if "Artist - Album" format, common on such sites
                if (artist.contains(" - ")) {
                    artist = artist.substringBefore(" - ").trim()
                } else if (artist.startsWith("艺人：")) { // Example if it's labeled
                    artist = artist.replace("艺人：", "").trim()
                }


                // Download link often needs careful selection
                val downloadLinkElement = element.select("div.col-xs-2.col-md-1.text-right > a[href*=/music/], div.col-xs-2.col-md-1.text-right > a[href*=/download/]").first()
                var downloadUrl = downloadLinkElement?.attr("abs:href") // abs:href resolves relative URLs against document's base URI

                // If abs:href didn't work or returned a relative path incorrectly (shouldn't if base URI is correct)
                if (downloadUrl == null || !downloadUrl.startsWith("http")) {
                     val relativeUrl = downloadLinkElement?.attr("href") ?: ""
                     if (relativeUrl.startsWith("/")) {
                        downloadUrl = siteBaseUrl + relativeUrl
                     } else {
                         // If it's not an absolute URL and not starting with /, it's harder to resolve without more context.
                         // For now, we'll assume it's either absolute or needs the siteBaseUrl.
                         if (relativeUrl.isNotEmpty()) downloadUrl = siteBaseUrl + "/" + relativeUrl // Best guess
                         else downloadUrl = "" // Mark as invalid if empty
                     }
                }


                if (title.isNotEmpty() && title != "Unknown Title" && downloadUrl.isNotEmpty()) {
                    Log.d("SearchViewModel", "Parsed Item: Title='$title', Artist='$artist', URL='$downloadUrl'")
                    items.add(SearchResultItem(title, artist, downloadUrl))
                } else {
                    Log.w("SearchViewModel", "Skipped item due to missing info: Title='$title', URL='$downloadUrl', Artist='$artist'")
                }
            } catch (e: Exception) {
                Log.w("SearchViewModel", "Failed to parse a search result item: ${e.message}. Item HTML: ${element.html()}", e)
                // Continue to the next element
            }
        }
        if (items.isEmpty() && elements.isNotEmpty()) {
             Log.w("SearchViewModel", "Found ${elements.size} result blocks but parsed 0 items. Check CSS selectors.")
        }
        return items
    }


    fun restoreLastResults() {
        if (_searchResults.value.isNullOrEmpty() && !lastResults.isNullOrEmpty()) {
             _searchResults.value = lastResults!! // Use !! because we checked isNullOrEmpty
        }
        // If isLoading was true due to rotation during search, reset it
        if (_isLoading.value == true && lastResults != null) { // lastResults being non-null implies search ended
            _isLoading.value = false
        }
    }
}
