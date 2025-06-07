package com.example.musicplayerapp

import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class SearchResultsAdapter(
    private val activity: SearchActivity, // Activity context for callbacks
    private var results: MutableList<SearchResultItem>
) : RecyclerView.Adapter<SearchResultsAdapter.SearchResultViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SearchResultViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.list_item_search_result, parent, false)
        return SearchResultViewHolder(view)
    }

    override fun onBindViewHolder(holder: SearchResultViewHolder, position: Int) {
        val item = results[position]
        holder.titleTextView.text = item.title
        holder.artistTextView.text = item.artist
        holder.downloadButton.setOnClickListener {
            Log.d("SearchAdapter", "Download button clicked for: ${item.title} - ${item.downloadUrl}")
            activity.initiateDownload(item)
        }
    }

    override fun getItemCount(): Int = results.size

    fun updateData(newData: List<SearchResultItem>) {
        results.clear()
        results.addAll(newData)
        notifyDataSetChanged() // Consider using DiffUtil for better performance
    }

    fun clearData() {
        results.clear()
        notifyDataSetChanged()
    }

    class SearchResultViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val titleTextView: TextView = itemView.findViewById(R.id.searchResultTitleTextView)
        val artistTextView: TextView = itemView.findViewById(R.id.searchResultArtistTextView)
        val downloadButton: ImageButton = itemView.findViewById(R.id.downloadButton)
    }
}
