package com.example.musicplayerapp

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.util.Log
import android.widget.Toast

class DownloadCompletionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action == DownloadManager.ACTION_DOWNLOAD_COMPLETE) {
            val downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
            if (downloadId != -1L && context != null) {
                Log.d("DownloadReceiver", "Download completed for ID: $downloadId")
                Toast.makeText(context, "Song download complete!", Toast.LENGTH_SHORT).show()

                // Optional: Trigger media scan to make the file visible in other media apps
                val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                try {
                    val uri = downloadManager.getUriForDownloadedFile(downloadId)
                    uri?.let { downloadedFileUri ->
                        // MediaScannerConnection needs the actual file path, not content URI for all cases
                        // Querying the DownloadManager for the local file path is more robust
                        val query = DownloadManager.Query().setFilterById(downloadId)
                        val cursor = downloadManager.query(query)
                        if (cursor != null && cursor.moveToFirst()) {
                            val localUriColumn = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
                            if (localUriColumn != -1) {
                                val localUriString = cursor.getString(localUriColumn)
                                if (localUriString != null) {
                                    val filePathUri = android.net.Uri.parse(localUriString)
                                    filePathUri.path?.let { path ->
                                        MediaScannerConnection.scanFile(context, arrayOf(path), null) { scannedPath, scannedUri ->
                                            Log.d("DownloadReceiver", "Media Scanner finished for $scannedPath, URI: $scannedUri")
                                        }
                                    } ?: Log.e("DownloadReceiver", "File path is null for downloaded file.")
                                } else {
                                     Log.e("DownloadReceiver", "Local URI string is null.")
                                }
                            } else {
                                Log.e("DownloadReceiver", "COLUMN_LOCAL_URI not found.")
                            }
                            cursor.close()
                        } else {
                            Log.e("DownloadReceiver", "Could not retrieve local URI for downloaded file ID: $downloadId")
                        }
                    } ?: Log.e("DownloadReceiver", "URI for downloaded file is null, ID: $downloadId")
                } catch (e: Exception) {
                    Log.e("DownloadReceiver", "Error during media scan or retrieving file URI", e)
                }
            }
        }
    }
}
