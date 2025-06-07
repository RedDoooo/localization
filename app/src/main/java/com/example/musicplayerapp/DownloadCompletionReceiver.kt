package com.example.musicplayerapp

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.media.MediaScannerConnection
import android.util.Log
import android.widget.Toast

class DownloadCompletionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action == DownloadManager.ACTION_DOWNLOAD_COMPLETE && context != null) {
            val downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
            if (downloadId == -1L) {
                Log.e("DownloadReceiver", "Received download complete action with no ID.")
                return
            }

            val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val query = DownloadManager.Query().setFilterById(downloadId)
            val cursor: Cursor? = downloadManager.query(query)

            if (cursor != null && cursor.moveToFirst()) {
                val statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                val reasonIndex = cursor.getColumnIndex(DownloadManager.COLUMN_REASON)
                val titleIndex = cursor.getColumnIndex(DownloadManager.COLUMN_TITLE)
                val localUriIndex = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)

                val status = if(statusIndex != -1) cursor.getInt(statusIndex) else -1
                val reason = if(reasonIndex != -1) cursor.getInt(reasonIndex) else 0
                val title = if(titleIndex != -1) cursor.getString(titleIndex) ?: "Unknown Title" else "Unknown Title"
                val localUriString = if(localUriIndex != -1) cursor.getString(localUriIndex) else null

                if (status == DownloadManager.STATUS_SUCCESSFUL) {
                    Log.d("DownloadReceiver", "Download successful for ID: $downloadId, Title: $title")
                    Toast.makeText(context, "Song '$title' downloaded.", Toast.LENGTH_SHORT).show()
                    localUriString?.let {
                        val filePathUri = android.net.Uri.parse(it)
                        filePathUri.path?.let { path ->
                            MediaScannerConnection.scanFile(context, arrayOf(path), null) { scannedPath, scannedUri ->
                                Log.d("DownloadReceiver", "Media Scanner finished for $scannedPath, URI: $scannedUri")
                            }
                        } ?: Log.e("DownloadReceiver", "File path is null from local URI: $it")
                    } ?: Log.e("DownloadReceiver", "Local URI string is null for successful download ID: $downloadId")

                } else if (status == DownloadManager.STATUS_FAILED) {
                    Log.e("DownloadReceiver", "Download failed for ID: $downloadId, Title: $title. Reason: $reason - ${getDownloadErrorReason(reason)}")
                    Toast.makeText(context, "Download failed for '$title'. ${getDownloadErrorReason(reason)}", Toast.LENGTH_LONG).show()
                } else {
                    Log.w("DownloadReceiver", "Download completed with unhandled status: $status for ID: $downloadId, Title: $title")
                }
                cursor.close()
            } else {
                Log.e("DownloadReceiver", "Could not move cursor to first for download ID: $downloadId. Or cursor is null.")
            }
        }
    }

    private fun getDownloadErrorReason(reason: Int): String {
        return when (reason) {
            DownloadManager.ERROR_CANNOT_RESUME -> "Error: Cannot Resume"
            DownloadManager.ERROR_DEVICE_NOT_FOUND -> "Error: Device Not Found"
            DownloadManager.ERROR_FILE_ALREADY_EXISTS -> "Error: File Already Exists"
            DownloadManager.ERROR_FILE_ERROR -> "Error: File Error"
            DownloadManager.ERROR_HTTP_DATA_ERROR -> "Error: HTTP Data Error"
            DownloadManager.ERROR_INSUFFICIENT_SPACE -> "Error: Insufficient Space"
            DownloadManager.ERROR_TOO_MANY_REDIRECTS -> "Error: Too Many Redirects"
            DownloadManager.ERROR_UNHANDLED_HTTP_CODE -> "Error: Unhandled HTTP Code"
            DownloadManager.ERROR_UNKNOWN -> "Error: Unknown"
            else -> "Error: Code $reason"
        }
    }
}
// Old code for reference, to be replaced by the above.
// class DownloadCompletionReceiver : BroadcastReceiver() {
//    override fun onReceive(context: Context?, intent: Intent?) {
//        if (intent?.action == DownloadManager.ACTION_DOWNLOAD_COMPLETE) {
//            val downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
//            if (downloadId != -1L && context != null) {
//                Log.d("DownloadReceiver", "Download completed for ID: $downloadId")
//                Toast.makeText(context, "Song download complete!", Toast.LENGTH_SHORT).show()
//
//                // Optional: Trigger media scan to make the file visible in other media apps
//                val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
//                try {
//                    val uri = downloadManager.getUriForDownloadedFile(downloadId)
//                    uri?.let { downloadedFileUri ->
//                        // MediaScannerConnection needs the actual file path, not content URI for all cases
//                        // Querying the DownloadManager for the local file path is more robust
//                        val query = DownloadManager.Query().setFilterById(downloadId)
//                        val cursor = downloadManager.query(query)
//                        if (cursor != null && cursor.moveToFirst()) {
//                            val localUriColumn = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
//                            if (localUriColumn != -1) {
//                                val localUriString = cursor.getString(localUriColumn)
//                                if (localUriString != null) {
//                                    val filePathUri = android.net.Uri.parse(localUriString)
//                                    filePathUri.path?.let { path ->
//                                        MediaScannerConnection.scanFile(context, arrayOf(path), null) { scannedPath, scannedUri ->
//                                            Log.d("DownloadReceiver", "Media Scanner finished for $scannedPath, URI: $scannedUri")
//                                        }
//                                    } ?: Log.e("DownloadReceiver", "File path is null for downloaded file.")
//                                } else {
//                                     Log.e("DownloadReceiver", "Local URI string is null.")
//                                }
//                            } else {
//                                Log.e("DownloadReceiver", "COLUMN_LOCAL_URI not found.")
//                            }
//                            cursor.close()
//                        } else {
//                            Log.e("DownloadReceiver", "Could not retrieve local URI for downloaded file ID: $downloadId")
//                        }
//                    } ?: Log.e("DownloadReceiver", "URI for downloaded file is null, ID: $downloadId")
//                } catch (e: Exception) {
//                    Log.e("DownloadReceiver", "Error during media scan or retrieving file URI", e)
//                }
//            }
//        }
//    }
// }
