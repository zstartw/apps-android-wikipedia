package org.wikipedia.gallery

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore

import org.wikipedia.R
import org.wikipedia.WikipediaApp
import org.wikipedia.feed.image.FeaturedImage
import org.wikipedia.offline.Compilation
import org.wikipedia.util.FileUtil

import java.io.File

class MediaDownloadReceiver : BroadcastReceiver() {

    private var callback: Callback? = null

    interface Callback {
        fun onSuccess()
    }

    fun setCallback(callback: Callback?) {
        this.callback = callback
    }

    fun download(context: Context, featuredImage: FeaturedImage) {
        val filename = FileUtil.sanitizeFileName(featuredImage.title())
        val targetDirectory = Environment.DIRECTORY_PICTURES
        performDownloadRequest(context, featuredImage.image().source(), targetDirectory, filename, null)
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (DownloadManager.ACTION_DOWNLOAD_COMPLETE == action) {
            val downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, 0)
            val query = DownloadManager.Query()
            query.setFilterById(downloadId)
            val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val c = downloadManager.query(query)
            try {
                if (c.moveToFirst()) {
                    val statusIndex = c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
                    val pathIndex = c.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI)
                    val mimeIndex = c.getColumnIndexOrThrow(DownloadManager.COLUMN_MEDIA_TYPE)
                    if (DownloadManager.STATUS_SUCCESSFUL == c.getInt(statusIndex)) {
                        if (callback != null) {
                            callback!!.onSuccess()
                        }
                        notifyContentResolver(context, Uri.parse(c.getString(pathIndex)).path,
                                c.getString(mimeIndex))
                    }
                }
            } finally {
                c.close()
            }
        }
    }

    private fun notifyContentResolver(context: Context, path: String, mimeType: String) {
        val values = ContentValues()
        val contentUri: Uri
        when {
            FileUtil.isVideo(mimeType) -> {
                values.put(MediaStore.Video.Media.DATA, path)
                values.put(MediaStore.Video.Media.MIME_TYPE, mimeType)
                contentUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            }
            FileUtil.isAudio(mimeType) -> {
                values.put(MediaStore.Audio.Media.DATA, path)
                values.put(MediaStore.Audio.Media.MIME_TYPE, mimeType)
                contentUri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            }
            else -> {
                values.put(MediaStore.Images.Media.DATA, path)
                values.put(MediaStore.Images.Media.MIME_TYPE, mimeType)
                contentUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }
        }
        context.contentResolver.insert(contentUri, values)
    }

    companion object {
        private val FILE_NAMESPACE = "File:"

        fun download(context: Context, compilation: Compilation) {
            val filename = FileUtil.sanitizeFileName(compilation.uri()!!.lastPathSegment)
            val targetDirectory = Environment.DIRECTORY_DOWNLOADS
            performDownloadRequest(context, compilation.uri()!!, targetDirectory, filename, Compilation.MIME_TYPE)
        }

        fun download(context: Context, galleryItem: GalleryItem) {
            val saveFilename = FileUtil.sanitizeFileName(trimFileNamespace(galleryItem.name))
            val targetDirectoryType: String
            if (FileUtil.isVideo(galleryItem.mimeType)) {
                targetDirectoryType = Environment.DIRECTORY_MOVIES
            } else if (FileUtil.isAudio(galleryItem.mimeType)) {
                targetDirectoryType = Environment.DIRECTORY_MUSIC
            } else if (FileUtil.isImage(galleryItem.mimeType)) {
                targetDirectoryType = Environment.DIRECTORY_PICTURES
            } else {
                targetDirectoryType = Environment.DIRECTORY_DOWNLOADS
            }
            performDownloadRequest(context, Uri.parse(galleryItem.url), targetDirectoryType,
                    saveFilename, galleryItem.mimeType)
        }

        private fun performDownloadRequest(context: Context, uri: Uri,
                                           targetDirectoryType: String,
                                           targetFileName: String, mimeType: String?) {
            val targetSubfolderName = WikipediaApp.instance.getString(R.string.app_name)
            val categoryFolder = Environment.getExternalStoragePublicDirectory(targetDirectoryType)
            val targetFolder = File(categoryFolder, targetSubfolderName)
            val targetFile = File(targetFolder, targetFileName)

            // creates the directory if it doesn't exist else it's harmless
            targetFolder.mkdir()

            val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val request = DownloadManager.Request(uri)
            request.setDestinationUri(Uri.fromFile(targetFile))
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            if (mimeType != null) {
                request.setMimeType(mimeType)
            }
            request.allowScanningByMediaScanner()
            downloadManager.enqueue(request)
        }

        private fun trimFileNamespace(filename: String): String {
            return if (filename.startsWith(FILE_NAMESPACE)) filename.substring(FILE_NAMESPACE.length) else filename
        }
    }
}
