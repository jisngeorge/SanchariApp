package com.sanchari.bus.ui.helper

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.sanchari.bus.R
import com.sanchari.bus.data.model.AppVersion
import com.sanchari.bus.data.remote.NetworkManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

class ApkDownloadManager(
    private val activity: AppCompatActivity,
    savedState: Bundle?,
    private val downloadFile: suspend (String, File) -> Boolean = NetworkManager::downloadFile
) {
    private var stagedFile = savedState?.getString(KEY_FILE)?.let { File(activity.cacheDir, it) }
    private var fileName = savedState?.getString(KEY_NAME)
    private var waitingForDestination = stagedFile != null
    private var job: Job? = null
    private var progress: AlertDialog? = null

    private val createDocument = activity.registerForActivityResult(
        ActivityResultContracts.CreateDocument(APK_MIME)
    ) { uri ->
        waitingForDestination = false
        if (uri == null) {
            cleanup()
            message(R.string.apk_save_cancelled)
        } else {
            save { copyToDocument(uri) }
        }
    }

    private val storagePermission = activity.registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        waitingForDestination = false
        if (granted) saveToDownloads() else {
            cleanup()
            message(R.string.apk_downloads_permission)
        }
    }

    fun download(version: AppVersion) {
        if (job?.isActive == true || stagedFile != null) {
            message(R.string.update_in_progress)
            return
        }
        val staged = File(activity.cacheDir, "sanchari_update_${UUID.randomUUID()}.apk")
        stagedFile = staged
        showProgress(R.string.apk_downloading)
        job = activity.lifecycleScope.launch {
            try {
                val name = withContext(Dispatchers.IO) {
                    if (!downloadFile(version.url, staged)) throw IOException("APK download failed")
                    currentCoroutineContext().ensureActive()
                    @Suppress("DEPRECATION")
                    val versionName = activity.packageManager.getPackageArchiveInfo(staged.path, 0)
                        ?.versionName?.takeIf { it.isNotBlank() } ?: version.versionCode.toString()
                    "Sanchari_v${versionName.replace(Regex("[^A-Za-z0-9._-]"), "_")}.apk"
                }
                fileName = name
                progress?.dismiss()
                waitingForDestination = true
                try {
                    createDocument.launch(name)
                } catch (_: ActivityNotFoundException) {
                    waitingForDestination = false
                    message(R.string.apk_downloads_fallback)
                    if (Build.VERSION.SDK_INT <= 28 && ContextCompat.checkSelfPermission(
                            activity, Manifest.permission.WRITE_EXTERNAL_STORAGE
                        ) != PackageManager.PERMISSION_GRANTED) {
                        waitingForDestination = true
                        storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    } else {
                        saveToDownloads()
                    }
                }
            } catch (e: CancellationException) {
                cleanup()
                throw e
            } catch (e: IOException) {
                fail(e)
            } catch (e: SecurityException) {
                fail(e)
            } catch (e: IllegalArgumentException) {
                fail(e)
            } finally {
                // A fallback save can already own a new progress dialog.
                if (job === currentCoroutineContext()[Job]) dismissProgress()
            }
        }
    }

    private fun saveToDownloads() {
        save {
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, requireNotNull(fileName))
                    put(MediaStore.Downloads.MIME_TYPE, APK_MIME)
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = activity.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw IOException("Could not create Downloads entry")
                copyToDocument(uri, remove = {
                    activity.contentResolver.delete(uri, null, null)
                }) {
                    val published = activity.contentResolver.update(uri, ContentValues().apply {
                        put(MediaStore.Downloads.IS_PENDING, 0)
                    }, null, null)
                    if (published != 1) throw IOException("Could not publish APK")
                }
            } else {
                @Suppress("DEPRECATION")
                val directory = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!directory.exists() && !directory.mkdirs()) throw IOException("Downloads unavailable")
                val target = File(directory, requireNotNull(fileName))
                if (!target.createNewFile()) throw IOException("An APK with this name already exists")
                try {
                    target.outputStream().use { output ->
                        requireStagedFile().inputStream().use { it.copyTo(output) }
                    }
                    currentCoroutineContext().ensureActive()
                } catch (e: Exception) {
                    if (!target.delete()) Log.w(TAG, "Could not remove incomplete APK")
                    throw e
                }
            }
        }
    }

    private suspend fun copyToDocument(
        uri: Uri,
        remove: () -> Unit = {
            if (!android.provider.DocumentsContract.deleteDocument(activity.contentResolver, uri)) {
                Log.w(TAG, "Could not remove incomplete APK destination")
            }
        },
        publish: () -> Unit = {}
    ) {
        try {
            val output = activity.contentResolver.openOutputStream(uri, "wt")
                ?: throw IOException("Could not open APK destination")
            output.use { stream -> requireStagedFile().inputStream().use { it.copyTo(stream) } }
            currentCoroutineContext().ensureActive()
            publish()
        } catch (e: Exception) {
            try {
                remove()
            } catch (cleanupError: Exception) {
                Log.w(TAG, "Could not remove incomplete APK destination", cleanupError)
            }
            throw e
        }
    }

    private fun requireStagedFile(): File =
        stagedFile?.takeIf { it.isFile } ?: throw IOException("Downloaded APK is no longer available")

    private fun save(write: suspend () -> Unit) {
        showProgress(R.string.apk_saving)
        job = activity.lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { write() }
                message(R.string.apk_saved)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                fail(e)
            } catch (e: SecurityException) {
                fail(e)
            } catch (e: IllegalArgumentException) {
                fail(e)
            } finally {
                cleanup()
                dismissProgress()
            }
        }
    }

    fun saveState(state: Bundle) {
        if (waitingForDestination) {
            state.putString(KEY_FILE, stagedFile?.name)
            state.putString(KEY_NAME, fileName)
        }
    }

    fun onPause() {
        if (!waitingForDestination) job?.cancel()
        dismissProgress()
    }

    private fun showProgress(text: Int) {
        dismissProgress()
        progress = AlertDialog.Builder(activity)
            .setTitle(R.string.btn_check_updates)
            .setMessage(text)
            .setView(ProgressBar(activity))
            .setCancelable(false)
            .show()
    }

    private fun dismissProgress() {
        progress?.dismiss()
        progress = null
    }

    private fun fail(error: Exception) {
        Log.e(TAG, "APK download/save failed", error)
        cleanup()
        message(R.string.apk_save_failed)
    }

    private fun cleanup() {
        waitingForDestination = false
        stagedFile?.let {
            if (it.exists() && !it.delete()) Log.w(TAG, "Could not remove staged APK")
        }
        stagedFile = null
        fileName = null
    }

    private fun message(text: Int) = Toast.makeText(activity, text, Toast.LENGTH_LONG).show()

    companion object {
        private const val TAG = "ApkDownloadManager"
        private const val APK_MIME = "application/vnd.android.package-archive"
        private const val KEY_FILE = "apk_staged_file"
        private const val KEY_NAME = "apk_suggested_name"
    }
}
