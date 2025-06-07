package com.example.musicplayerapp

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.TimeUnit
import android.graphics.Color // For selection background
import androidx.core.content.ContextCompat // For colors from resources

class SongAdapter(
    private var songs: MutableList<Song>,
    private val itemClickListener: (Song, Int) -> Unit,          // Updated to include position
    private val itemLongClickListener: (Song, Int) -> Boolean   // For starting CAB
) : RecyclerView.Adapter<SongAdapter.ViewHolder>() {

    private val selectedItems = mutableSetOf<Int>()
    private var multiSelectMode = false

    fun toggleSelection(position: Int) {
        if (selectedItems.contains(position)) {
            selectedItems.remove(position)
        } else {
            selectedItems.add(position)
        }
        notifyItemChanged(position)
    }

    fun clearSelections() {
        val previouslySelectedCount = selectedItems.size
        selectedItems.clear()
        // Only notify if there were items selected to avoid unnecessary redraws
        if (previouslySelectedCount > 0) {
             notifyDataSetChanged() // Could optimize to notify only selected items
        }
    }

    fun selectAll() {
        if (songs.size == selectedItems.size) { // If all already selected, clear (or make it a deselect all)
            // clearSelections() // This would make it a toggle for "select all"
            return // Or do nothing if all are already selected
        }
        for (i in songs.indices) {
            selectedItems.add(i)
        }
        notifyDataSetChanged()
    }


    fun getSelectedSongItems(): List<Song> {
        return selectedItems.map { songs[it] }.toList()
    }

    fun getSelectedPositions(): Set<Int> {
        return selectedItems.toSet() // Return a copy
    }


    fun isInMultiSelectMode(): Boolean = multiSelectMode

    fun setMultiSelectMode(enabled: Boolean) {
        val prevMode = multiSelectMode
        multiSelectMode = enabled
        if (!enabled) {
            clearSelections()
        }
        // If mode changed and it affects all items, might need notifyDataSetChanged()
        // but individual item clicks/long-clicks will handle their own appearance.
        // If there's a global visual change for multi-select mode itself (not item-specific), then notify.
        if (prevMode != enabled) {
            // This is important if the click behavior changes globally based on mode
            // For example, if single click switches from play to select.
            // notifyDataSetChanged() // Can be heavy, use if necessary.
        }
    }


    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.list_item_song, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val song = songs[position]
        holder.bind(song, position, selectedItems.contains(position), itemClickListener, itemLongClickListener, multiSelectMode)
    }

    override fun getItemCount(): Int = songs.size

    fun updateSongs(newSongs: List<Song>) {
        songs.clear()
        songs.addAll(newSongs)
        clearSelections() // Clear selections when new data is loaded
        setMultiSelectMode(false) // Exit multi-select mode
        notifyDataSetChanged()
    }

     fun clearSongs() {
        songs.clear()
        clearSelections()
        setMultiSelectMode(false)
        notifyDataSetChanged()
    }

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val titleTextView: TextView = itemView.findViewById(R.id.songTitleTextViewLib)
        private val artistTextView: TextView = itemView.findViewById(R.id.songArtistTextViewLib)
        private val durationTextView: TextView = itemView.findViewById(R.id.songDurationTextViewLib)

        fun bind(
            song: Song,
            position: Int,
            isSelected: Boolean,
            clickListener: (Song, Int) -> Unit,
            longClickListener: (Song, Int) -> Boolean,
            isInMultiSelectMode: Boolean
        ) {
            titleTextView.text = song.title ?: "Unknown Title"
            artistTextView.text = song.artist ?: "Unknown Artist"
            durationTextView.text = formatDuration(song.duration)

            if (isSelected) {
                itemView.setBackgroundColor(ContextCompat.getColor(itemView.context, R.color.selected_item_background))
            } else {
                itemView.setBackgroundColor(ContextCompat.getColor(itemView.context, R.color.default_item_background))
            }

            itemView.setOnClickListener {
                clickListener(song, position)
            }
            itemView.setOnLongClickListener {
                longClickListener(song, position)
            }
        }

        private fun formatDuration(duration: Long): String {
            if (duration < 0) return "0:00" // Or some other placeholder for invalid duration
            val minutes = TimeUnit.MILLISECONDS.toMinutes(duration)
            val seconds = TimeUnit.MILLISECONDS.toSeconds(duration) -
                    TimeUnit.MINUTES.toSeconds(minutes)
            return String.format("%02d:%02d", minutes, seconds)
        }
    }
}
