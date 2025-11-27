/*
 * Copyright (c) 2018 Ha Duy Trung
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.hidroh.materialistic.data

import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.CursorWrapper
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.annotation.AnyThread
import androidx.annotation.WorkerThread
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.github.hidroh.materialistic.DataModule
import io.github.hidroh.materialistic.FavoriteActivity
import io.github.hidroh.materialistic.R
import io.github.hidroh.materialistic.ktx.closeQuietly
import io.github.hidroh.materialistic.ktx.getUri
import io.github.hidroh.materialistic.ktx.setChannel
import io.github.hidroh.materialistic.ktx.toSendIntentChooser
import okio.Okio
import rx.Observable
import rx.Scheduler
import rx.android.schedulers.AndroidSchedulers
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * Data repository for {@link Favorite}
 */
@Singleton
class FavoriteManager @Inject constructor(
    private val cache: LocalCache,
    @Named(DataModule.IO_THREAD) private val ioScheduler: Scheduler,
    private val dao: MaterialisticDatabase.SavedStoriesDao) : LocalItemManager<Favorite> {

  enum class ExportFormat(val extension: String, val mimeType: String) {
    CSV("csv", "text/csv"),
    TXT("txt", "text/plain"),
    HTML("html", "text/html"),
    MARKDOWN("md", "text/markdown"),
    JSON("json", "application/json")
  }

  companion object {
    private const val CHANNEL_EXPORT = "export"
    private const val URI_PATH_ADD = "add"
    private const val URI_PATH_REMOVE = "remove"
    private const val URI_PATH_CLEAR = "clear"
    private const val PATH_SAVED = "saved"
    private const val FILE_AUTHORITY = "io.github.hidroh.materialistic.fileprovider"

    fun isAdded(uri: Uri) = uri.toString().startsWith(buildAdded().toString())

    fun isRemoved(uri: Uri) = uri.toString().startsWith(buildRemoved().toString())

    fun isCleared(uri: Uri) = uri.toString().startsWith(buildCleared().toString())

    private fun buildAdded(): Uri.Builder =
        MaterialisticDatabase.getBaseSavedUri().buildUpon().appendPath(URI_PATH_ADD)

    private fun buildCleared(): Uri.Builder =
        MaterialisticDatabase.getBaseSavedUri().buildUpon().appendPath(URI_PATH_CLEAR)

    private fun buildRemoved(): Uri.Builder =
        MaterialisticDatabase.getBaseSavedUri().buildUpon().appendPath(URI_PATH_REMOVE)
  }

  private val notificationId = System.currentTimeMillis().toInt()
  private val syncScheduler = SyncScheduler()
  private var cursor: Cursor? = null
  private var loader: FavoriteRoomLoader? = null
  private var currentExportFormat: ExportFormat = ExportFormat.CSV

  override fun getSize() = cursor?.count ?: 0

  override fun getItem(position: Int) = if (cursor?.moveToPosition(position) == true) {
      cursor!!.favorite
    } else {
      null
    }

  override fun attach(observer: LocalItemManager.Observer, filter: String?) {
    loader = FavoriteRoomLoader(filter, observer)
    loader!!.load()
  }

  override fun detach() {
    if (cursor != null) {
      cursor = null
    }
    loader = null
  }

  /**
   * Exports all favorites matched given query to file
   * @param context   an instance of {@link android.content.Context}
   * @param query     query to filter stories to be retrieved
   * @param format    export format (CSV, TXT, HTML, MARKDOWN, JSON)
   */
  fun export(context: Context, query: String?, format: ExportFormat = ExportFormat.CSV) {
    val appContext = context.applicationContext
    currentExportFormat = format
    Log.d("FavoriteManager", "Starting export in ${format.name} format...")
    notifyExportStart(appContext)
    Observable.defer { Observable.just(query) }
        .map { query(it) }
        .filter { it != null && it.moveToFirst() }
        .map {
          try {
            Log.d("FavoriteManager", "Writing to file, count: ${it.count}")
            toFile(appContext, Cursor(it))
          } catch (e: IOException) {
            Log.e("FavoriteManager", "Export failed", e)
            null
          } finally {
            it.close()
          }
        }
        .onErrorReturn { 
          Log.e("FavoriteManager", "Export error", it)
          null 
        }
        .defaultIfEmpty(null)
        .subscribeOn(ioScheduler)
        .observeOn(AndroidSchedulers.mainThread())
        .subscribe { uri ->
          Log.d("FavoriteManager", "Export done, uri: $uri")
          notifyExportDone(appContext, uri)
          if (uri != null) {
            // Save to Downloads folder in background
            Thread {
              val savedPath = saveToDownloads(appContext, uri)
              android.os.Handler(android.os.Looper.getMainLooper()).post {
                if (savedPath != null) {
                  Toast.makeText(appContext, "✓ Saved to Downloads folder:\n$savedPath", Toast.LENGTH_LONG).show()
                  Log.d("FavoriteManager", "Saved to Downloads: $savedPath")
                } else {
                  Toast.makeText(appContext, "Could not save to Downloads. Use share option.", Toast.LENGTH_LONG).show()
                  Log.e("FavoriteManager", "Failed to save to Downloads")
                }
              }
            }.start()
            
            // Open share dialog after a short delay so user can see the toast
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
              try {
                appContext.startActivity(
                  uri.toSendIntentChooser(appContext)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
              } catch (e: Exception) {
                Log.e("FavoriteManager", "Failed to open share dialog", e)
              }
            }, 1500)
          } else {
            Toast.makeText(appContext, "Export failed - no saved stories or error occurred", Toast.LENGTH_LONG).show()
          }
        }
  }

  /**
   * Exports all favorites to a user-selected URI
   * @param context   an instance of {@link android.content.Context}
   * @param query     query to filter stories to be retrieved
   * @param format    export format
   * @param uri       destination URI selected by user
   */
  fun exportToUri(context: Context, query: String?, format: ExportFormat, uri: Uri) {
    val appContext = context.applicationContext
    currentExportFormat = format
    Log.d("FavoriteManager", "Starting export to URI in ${format.name} format...")
    Toast.makeText(appContext, "Exporting...", Toast.LENGTH_SHORT).show()
    
    Observable.defer { Observable.just(query) }
        .map { query(it) }
        .filter { it != null && it.moveToFirst() }
        .map {
          try {
            Log.d("FavoriteManager", "Writing to URI, count: ${it.count}")
            writeToUri(appContext, Cursor(it), uri)
          } catch (e: IOException) {
            Log.e("FavoriteManager", "Export to URI failed", e)
            false
          } finally {
            it.close()
          }
        }
        .onErrorReturn { 
          Log.e("FavoriteManager", "Export to URI error", it)
          false 
        }
        .defaultIfEmpty(false)
        .subscribeOn(ioScheduler)
        .observeOn(AndroidSchedulers.mainThread())
        .subscribe { success ->
          if (success) {
            Toast.makeText(appContext, "✓ Export saved successfully!", Toast.LENGTH_LONG).show()
          } else {
            Toast.makeText(appContext, "Export failed - no saved stories or error occurred", Toast.LENGTH_LONG).show()
          }
        }
  }

  @WorkerThread
  private fun writeToUri(context: Context, cursor: Cursor, uri: Uri): Boolean {
    if (cursor.count == 0) return false
    
    val content = when (currentExportFormat) {
      ExportFormat.CSV -> buildCsvContent(cursor)
      ExportFormat.TXT -> buildTxtContent(cursor)
      ExportFormat.HTML -> buildHtmlContent(cursor)
      ExportFormat.MARKDOWN -> buildMarkdownContent(cursor)
      ExportFormat.JSON -> buildJsonContent(cursor)
    }
    
    context.contentResolver.openOutputStream(uri)?.use { outputStream ->
      outputStream.write(content.toByteArray())
      outputStream.flush()
    } ?: return false
    
    return true
  }

  /**
   * Adds given story as favorite
   * @param context   an instance of {@link android.content.Context}
   * @param story     story to be added as favorite
   */
  fun add(context: Context, story: WebItem) {
    Observable.defer { Observable.just(story) }
        .doOnNext { insert(it) }
        .map { it.id }
        .map { buildAdded().appendPath(story.id).build() }
        .subscribeOn(ioScheduler)
        .observeOn(AndroidSchedulers.mainThread())
        .subscribe { MaterialisticDatabase.getInstance(context).setLiveValue(it) }
    syncScheduler.scheduleSync(context, story.id)
  }

  /**
   * Clears all stories matched given query from favorites
   * will be sent upon completion
   * @param context   an instance of {@link android.content.Context}
   * @param query     query to filter stories to be cleared
   */
  fun clear(context: Context, query: String?) {
    Observable.defer { Observable.just(query) }
        .map { deleteMultiple(it) }
        .subscribeOn(ioScheduler)
        .observeOn(AndroidSchedulers.mainThread())
        .subscribe { MaterialisticDatabase.getInstance(context).setLiveValue(buildCleared().build()) }
  }

  /**
   * Removes story with given ID from favorites
   * upon completion
   * @param context   an instance of {@link android.content.Context}
   * @param itemId    story ID to be removed from favorites
   */
  fun remove(context: Context, itemId: String?) {
    if (itemId == null) return
    Observable.defer { Observable.just(itemId) }
        .doOnNext { delete(it) }
        .map { buildRemoved().appendPath(it).build() }
        .subscribeOn(ioScheduler)
        .observeOn(AndroidSchedulers.mainThread())
        .subscribe { MaterialisticDatabase.getInstance(context).setLiveValue(it) }
  }

  /**
   * Removes multiple stories with given IDs from favorites
   * be sent upon completion
   * @param context   an instance of {@link android.content.Context}
   * @param itemIds   array of story IDs to be removed from favorites
   */
  fun remove(context: Context, itemIds: Collection<String>?) {
    if (itemIds.orEmpty().isEmpty()) return
    Observable.defer { Observable.from(itemIds) }
        .subscribeOn(ioScheduler)
        .doOnNext { delete(it) }
        .map { buildRemoved().appendPath(it).build() }
        .observeOn(AndroidSchedulers.mainThread())
        .subscribe { MaterialisticDatabase.getInstance(context).setLiveValue(it) }
  }

  @WorkerThread
  fun check(itemId: String?) = Observable.just(if (itemId.isNullOrEmpty()) {
    false
  } else {
    cache.isFavorite(itemId)
  })!!

  @WorkerThread
  private fun toFile(context: Context, cursor: Cursor): Uri? {
    if (cursor.count == 0) return null
    val dir = File(context.filesDir, PATH_SAVED)
    if (!dir.exists() && !dir.mkdir()) return null
    val filename = "materialistic-export.${currentExportFormat.extension}"
    val file = File(dir, filename)
    if (file.exists()) file.delete()
    if (!file.createNewFile()) return null
    
    val content = when (currentExportFormat) {
      ExportFormat.CSV -> buildCsvContent(cursor)
      ExportFormat.TXT -> buildTxtContent(cursor)
      ExportFormat.HTML -> buildHtmlContent(cursor)
      ExportFormat.MARKDOWN -> buildMarkdownContent(cursor)
      ExportFormat.JSON -> buildJsonContent(cursor)
    }
    
    val bufferedSink = Okio.buffer(Okio.sink(file))
    with(bufferedSink) {
      writeUtf8(content)
      flush()
      closeQuietly()
    }
    return file.getUri(context, FILE_AUTHORITY)
  }

  private fun buildCsvContent(cursor: Cursor): String {
    val sb = StringBuilder()
    sb.appendLine("Title,URL,Hacker News Link,Saved Date")
    do {
      val item = cursor.favorite
      val escapedTitle = item.displayedTitle.replace("\"", "\"\"")
      val date = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
        .format(java.util.Date(item.time * 1000))
      sb.appendLine("\"$escapedTitle\",${item.url},${HackerNewsClient.WEB_ITEM_PATH.format(item.id)},$date")
    } while (cursor.moveToNext())
    return sb.toString()
  }

  private fun buildTxtContent(cursor: Cursor): String {
    val sb = StringBuilder()
    sb.appendLine("=== Materialistic Saved Stories ===")
    sb.appendLine()
    var index = 1
    do {
      val item = cursor.favorite
      val date = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
        .format(java.util.Date(item.time * 1000))
      sb.appendLine("${index}. ${item.displayedTitle}")
      sb.appendLine("   URL: ${item.url}")
      sb.appendLine("   HN: ${HackerNewsClient.WEB_ITEM_PATH.format(item.id)}")
      sb.appendLine("   Saved: $date")
      sb.appendLine()
      index++
    } while (cursor.moveToNext())
    return sb.toString()
  }

  private fun buildHtmlContent(cursor: Cursor): String {
    val sb = StringBuilder()
    sb.appendLine("<!DOCTYPE html>")
    sb.appendLine("<html><head><meta charset=\"UTF-8\">")
    sb.appendLine("<title>Materialistic Saved Stories</title>")
    sb.appendLine("<style>body{font-family:Arial,sans-serif;max-width:800px;margin:0 auto;padding:20px}")
    sb.appendLine(".story{margin-bottom:20px;padding:15px;border:1px solid #ddd;border-radius:8px}")
    sb.appendLine(".title{font-size:18px;font-weight:bold;margin-bottom:8px}")
    sb.appendLine(".meta{color:#666;font-size:14px}</style></head><body>")
    sb.appendLine("<h1>Saved Stories</h1>")
    do {
      val item = cursor.favorite
      val date = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
        .format(java.util.Date(item.time * 1000))
      sb.appendLine("<div class=\"story\">")
      sb.appendLine("<div class=\"title\"><a href=\"${item.url}\">${item.displayedTitle}</a></div>")
      sb.appendLine("<div class=\"meta\">")
      sb.appendLine("<a href=\"${HackerNewsClient.WEB_ITEM_PATH.format(item.id)}\">HN Discussion</a> | Saved: $date")
      sb.appendLine("</div></div>")
    } while (cursor.moveToNext())
    sb.appendLine("</body></html>")
    return sb.toString()
  }

  private fun buildMarkdownContent(cursor: Cursor): String {
    val sb = StringBuilder()
    sb.appendLine("# Materialistic Saved Stories")
    sb.appendLine()
    do {
      val item = cursor.favorite
      val date = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
        .format(java.util.Date(item.time * 1000))
      sb.appendLine("## ${item.displayedTitle}")
      sb.appendLine()
      sb.appendLine("- **URL:** [Link](${item.url})")
      sb.appendLine("- **HN:** [Discussion](${HackerNewsClient.WEB_ITEM_PATH.format(item.id)})")
      sb.appendLine("- **Saved:** $date")
      sb.appendLine()
      sb.appendLine("---")
      sb.appendLine()
    } while (cursor.moveToNext())
    return sb.toString()
  }

  private fun buildJsonContent(cursor: Cursor): String {
    val sb = StringBuilder()
    sb.appendLine("{")
    sb.appendLine("  \"exported\": \"${java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}\",")
    sb.appendLine("  \"stories\": [")
    val items = mutableListOf<String>()
    do {
      val item = cursor.favorite
      val date = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.getDefault())
        .format(java.util.Date(item.time * 1000))
      val escapedTitle = item.displayedTitle.replace("\"", "\\\"")
      items.add("""    {
      "id": "${item.id}",
      "title": "$escapedTitle",
      "url": "${item.url}",
      "hnUrl": "${HackerNewsClient.WEB_ITEM_PATH.format(item.id)}",
      "savedAt": "$date"
    }""")
    } while (cursor.moveToNext())
    sb.appendLine(items.joinToString(",\n"))
    sb.appendLine("  ]")
    sb.appendLine("}")
    return sb.toString()
  }

  private fun saveToDownloads(context: Context, uri: Uri): String? {
    return try {
      val inputStream = context.contentResolver.openInputStream(uri) ?: return null
      val timestamp = java.text.SimpleDateFormat("yyyy-MM-dd_HHmm", java.util.Locale.getDefault()).format(java.util.Date())
      val fileName = "materialistic-export-$timestamp.${currentExportFormat.extension}"
      
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        // Android 10+ use MediaStore
        val contentValues = ContentValues().apply {
          put(MediaStore.Downloads.DISPLAY_NAME, fileName)
          put(MediaStore.Downloads.MIME_TYPE, currentExportFormat.mimeType)
          put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val downloadUri = context.contentResolver.insert(
          MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues
        ) ?: return null
        
        context.contentResolver.openOutputStream(downloadUri)?.use { outputStream ->
          inputStream.copyTo(outputStream)
        }
        inputStream.close()
        fileName
      } else {
        // Android 9 and below - direct file access
        @Suppress("DEPRECATION")
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val destFile = File(downloadsDir, fileName)
        destFile.outputStream().use { outputStream ->
          inputStream.copyTo(outputStream)
        }
        inputStream.close()
        destFile.absolutePath
      }
    } catch (e: Exception) {
      Log.e("FavoriteManager", "Failed to save to Downloads", e)
      null
    }
  }

  private fun notifyExportStart(context: Context) {
    NotificationManagerCompat.from(context)
      .notify(
        notificationId, createNotificationBuilder(context)
          .setCategory(NotificationCompat.CATEGORY_PROGRESS)
          .setProgress(0, 0, true)
          .setContentIntent(
            PendingIntent.getActivity(
              context, 0,
              Intent(context, FavoriteActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
              when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                else -> PendingIntent.FLAG_UPDATE_CURRENT
              }
            )
          )
          .build()
      )
  }

  private fun notifyExportDone(context: Context, uri: Uri?) {
    val manager = NotificationManagerCompat.from(context)
    with(manager) {
      cancel(notificationId)
      if (uri == null) return
      context.grantUriPermission(context.packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
      notify(
        notificationId, createNotificationBuilder(context)
          .setPriority(NotificationCompat.PRIORITY_HIGH)
          .setVibrate(longArrayOf(0L))
          .setContentText(context.getString(R.string.export_notification))
          .setContentIntent(
            PendingIntent.getActivity(
              context, 0,
              uri.toSendIntentChooser(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
              when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                else -> PendingIntent.FLAG_UPDATE_CURRENT
              }
            )
          )
          .build()
      )
    }
  }

  private fun createNotificationBuilder(context: Context) =
      NotificationCompat.Builder(context, CHANNEL_EXPORT)
          .setChannel(context, CHANNEL_EXPORT, context.getString(R.string.export_saved_stories))
          .setSmallIcon(R.drawable.ic_notification)
          .setContentTitle(context.getString(R.string.export_saved_stories))
          .setAutoCancel(true)

  @WorkerThread
  private fun query(filter: String?): android.database.Cursor = if (filter.isNullOrEmpty()) {
    dao.selectAllToCursor()
  } else {
    dao.searchToCursor(filter)
  }

  @WorkerThread
  private fun insert(story: WebItem) {
    dao.insert(MaterialisticDatabase.SavedStory.from(story))
    loader?.load()
  }

  @WorkerThread
  private fun delete(itemId: String?) {
    dao.deleteByItemId(itemId)
    loader?.load()
  }

  @WorkerThread
  private fun deleteMultiple(query: String?): Int {
    val deleted = if (query.isNullOrEmpty()) dao.deleteAll() else dao.deleteByTitle(query)
    loader?.load()
    return deleted
  }

  /**
   * A cursor wrapper to retrieve associated {@link Favorite}
   */
  private class Cursor(cursor: android.database.Cursor) : CursorWrapper(cursor) {
    val favorite: Favorite
      get() = Favorite(
          getString(getColumnIndexOrThrow(MaterialisticDatabase.FavoriteEntry.COLUMN_NAME_ITEM_ID)),
          getString(getColumnIndexOrThrow(MaterialisticDatabase.FavoriteEntry.COLUMN_NAME_URL)),
          getString(getColumnIndex(MaterialisticDatabase.FavoriteEntry.COLUMN_NAME_TITLE)),
          getString(getColumnIndex(MaterialisticDatabase.FavoriteEntry.COLUMN_NAME_TIME)).toLong())
  }

  inner class FavoriteRoomLoader(private val filter: String?,
                                 private val observer: LocalItemManager.Observer) {
    @AnyThread
    fun load() {
      Observable.defer { Observable.just(filter) }
          .map { query(it) }
          .subscribeOn(ioScheduler)
          .observeOn(AndroidSchedulers.mainThread())
          .subscribe {
            cursor = if (it == null) null else Cursor(it)
            observer.onChanged()
          }
    }
  }
}
