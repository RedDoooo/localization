package com.example.musicplayerapp

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.TimeUnit

class SongAdapter(
    private var songs: MutableList<Song>, // Changed to MutableList to allow updates
    private val itemClickListener: (Song) -> Unit
) : RecyclerView.Adapter<SongAdapter.ViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.list_item_song, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val song = songs[position]
        holder.bind(song, itemClickListener)
    }

    override fun getItemCount(): Int = songs.size

    // Helper function to update data
    fun updateSongs(newSongs: List<Song>) {
        songs.clear()
        songs.addAll(newSongs)
        notifyDataSetChanged() // Consider DiffUtil for better performance
    }
     fun clearSongs() {
        songs.clear()
        notifyDataSetChanged()
    }


    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val titleTextView: TextView = itemView.findViewById(R.id.songTitleTextViewLib)
        private val artistTextView: TextView = itemView.findViewById(R.id.songArtistTextViewLib)
        private val durationTextView: TextView = itemView.findViewById(R.id.songDurationTextViewLib)

        fun bind(song: Song, clickListener: (Song) -> Unit) {
            titleTextView.text = song.title ?: "Unknown Title"
            artistTextView.text = song.artist ?: "Unknown Artist"
            durationTextView.text = formatDuration(song.duration)
            itemView.setOnClickListener { clickListener(song) }
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
