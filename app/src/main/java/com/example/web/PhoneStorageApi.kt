package com.example.web

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.util.Locale

/**
 * Katalog media penyimpanan HP untuk Web Connect: daftar folder, streaming audio/video dengan
 * dukungan HTTP Range, metadata, sampul album, dan lirik. Akses dibatasi baca/putar/unduh.
 */
object PhoneStorageApi {

    private const val MAX_LIST_ITEMS = 3000
    private const val COPY_BUFFER_SIZE = 64 * 1024

    data class Root(
        val key: String,
        val label: String,
        val icon: String,
        val directory: File,
        val defaultKind: String?
    )

    class Resolved(
        val file: File?,
        val contentUri: Uri?,
        val name: String,
        val size: Long,
        val mimeType: String
    )

    val roots: List<Root> by lazy {
        val external = Environment.getExternalStorageDirectory()
        listOf(
            Root("music", "Musik", "bi-music-note-beamed", Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "audio"),
            Root("movies", "Film & Video", "bi-camera-reels-fill", Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "video"),
            Root("camera", "Kamera (DCIM)", "bi-camera-fill", File(external, "DCIM"), "video"),
            Root("photos", "Foto", "bi-images-fill", File(external, "Pictures"), "image"),
            Root("download", "Unduhan", "bi-download", Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), null),
            Root("documents", "Dokumen", "bi-folder2-open", Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), null),
            Root("podcast", "Podcast", "bi-broadcast", Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PODCASTS), "audio"),
            Root("internal", "Penyimpanan Internal", "bi-device-hdd-fill", external, null)
        ).filter { runCatching { it.directory.exists() }.getOrDefault(true) }
    }

    fun findRoot(key: String): Root? = roots.firstOrNull { it.key == key }

    /** Null bila path relatif berusaha keluar dari root pemanggil (path traversal). */
    fun resolveInRoot(rootKey: String, relativePath: String): Pair<Root, File>? {
        val root = findRoot(rootKey) ?: return null
        val cleanRelative = relativePath.replace('\\', '/').trimStart('/')
        if (cleanRelative.split('/').any { it == ".." }) return null
        val candidate = if (cleanRelative.isEmpty()) root.directory else File(root.directory, cleanRelative)
        return try {
            val canonicalBase = root.directory.canonicalPath
            val canonicalCandidate = candidate.canonicalPath
            val inside = canonicalCandidate == canonicalBase || canonicalCandidate.startsWith(canonicalBase + File.separator)
            if (!inside || !candidate.exists()) null else root to candidate
        } catch (e: Exception) {
            null
        }
    }

    fun list(context: Context, rootKey: String, relativeDir: String, kind: String?, query: String?): JSONObject {
        val root = findRoot(rootKey)
        val result = JSONObject()
        if (root == null) {
            result.put("error", "Root tidak dikenal")
            return result
        }
        result.put("root", root.key)
        result.put("rootLabel", root.label)

        val normalizedDir = relativeDir.replace('\\', '/').trim('/')
        val currentDir = resolveInRoot(rootKey, normalizedDir)?.second
        if (currentDir == null || !currentDir.isDirectory) {
            return listFromMediaStore(context, root, normalizedDir, kind, query, result)
        }

        val needle = query?.trim()?.lowercase(Locale.getDefault())
        val dirs = JSONArray()
        val files = JSONArray()
        var budget = MAX_LIST_ITEMS

        val children = runCatching { currentDir.listFiles()?.asList() }.getOrNull() ?: emptyList()
        for (child in children.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase(Locale.getDefault()) }))) {
            if (budget <= 0) break
            val rel = if (normalizedDir.isEmpty()) child.name else "$normalizedDir/${child.name}"
            if (child.isDirectory) {
                if (child.name.startsWith(".")) continue
                if (!kind.isNullOrEmpty() && kind != "all" && !bucketHasMediaKind(child, kind)) continue
                dirs.put(JSONObject().apply {
                    put("name", child.name)
                    put("path", rel)
                    put("isDir", true)
                    put("mtime", child.lastModified())
                })
                budget--
            } else {
                val mime = mimeTypeOf(child.name)
                val itemKind = kindOf(mime)
                if (!kind.isNullOrEmpty() && kind != "all" && itemKind != kind) continue
                if (needle != null && !child.name.lowercase(Locale.getDefault()).contains(needle)) continue
                files.put(fileJson(root.key, rel, child.name, child.length(), child.lastModified(), mime, itemKind))
                budget--
            }
        }

        result.put("dir", normalizedDir)
        result.put("parent", if (normalizedDir.contains('/')) normalizedDir.substringBeforeLast('/') else "")
        result.put("atRoot", normalizedDir.isEmpty())
        result.put("dirs", dirs)
        result.put("files", files)
        result.put("source", "file")
        return result
    }

    private fun bucketHasMediaKind(dir: File, kind: String): Boolean {
        return runCatching {
            dir.walkTopDown().maxDepth(2).any {
                it.isFile && !it.name.startsWith(".") && kindOf(mimeTypeOf(it.name)) == kind
            }
        }.getOrDefault(false)
    }

    /** Cadangan saat akses file langsung dibatasi (Android 13+ tanpa izin Semua File). */
    private fun listFromMediaStore(context: Context, root: Root, normalizedDir: String, kind: String?, query: String?, result: JSONObject): JSONObject {
        result.put("dir", normalizedDir)
        result.put("parent", if (normalizedDir.contains('/')) normalizedDir.substringBeforeLast('/') else "")
        result.put("atRoot", normalizedDir.isEmpty())
        val dirs = JSONArray()
        val files = JSONArray()
        result.put("dirs", dirs)
        result.put("files", files)
        result.put("source", "mediastore")

        val collection = mediaCollectionFor(root.key) ?: return result
        val wantedKind = if (!kind.isNullOrEmpty()) kind else root.defaultKind
        val needle = query?.trim()?.lowercase(Locale.getDefault())

        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.DATA
        )

        try {
            context.contentResolver.query(collection, projection, null, null, "${MediaStore.MediaColumns.DISPLAY_NAME} ASC")?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameCol = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                val sizeCol = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
                val dateCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
                val pathCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                var budget = MAX_LIST_ITEMS

                while (cursor.moveToNext() && budget > 0) {
                    val dataPath = if (pathCol >= 0) cursor.getString(pathCol) else null
                    val name = (if (nameCol >= 0) cursor.getString(nameCol) else null)
                        ?: dataPath?.substringAfterLast('/')
                        ?: continue
                    if (!isInRoot(root, dataPath)) continue

                    val relative = relativeToRoot(root, dataPath, name)
                    if (!normalizedDir.isEmpty() && !relative.startsWith("$normalizedDir/")) continue

                    val mime = mimeTypeOf(name)
                    val itemKind = kindOf(mime)
                    if (!wantedKind.isNullOrEmpty() && wantedKind != "all" && itemKind != wantedKind) continue
                    if (needle != null && !name.lowercase(Locale.getDefault()).contains(needle)) continue

                    if (relative.contains('/')) {
                        val sub = relative.substringBeforeLast('/')
                        if (dirs.indexOfPath(sub) < 0) {
                            dirs.put(JSONObject().apply {
                                put("name", sub.substringAfterLast('/'))
                                put("path", sub)
                                put("isDir", true)
                            })
                        }
                    }
                    files.put(
                        fileJson(root.key, relative, name, (if (sizeCol >= 0) cursor.getLong(sizeCol) else 0L),
                            (if (dateCol >= 0) cursor.getLong(dateCol) else 0L) * 1000L, mime, itemKind)
                            .put("mediaId", cursor.getLong(idCol))
                    )
                    budget--
                }
            }
        } catch (e: Exception) {
            result.put("error", "MediaStore tidak dapat dibaca: ${e.message}")
        }
        return result
    }

    private fun JSONArray.indexOfPath(path: String): Int {
        for (i in 0 until length()) {
            if (optJSONObject(i)?.optString("path") == path) return i
        }
        return -1
    }

    private fun isInRoot(root: Root, dataPath: String?): Boolean {
        if (dataPath.isNullOrEmpty()) return false
        val base = root.directory.absolutePath.replace('\\', '/').trimEnd('/')
        return dataPath.replace('\\', '/').startsWith("$base/")
    }

    private fun relativeToRoot(root: Root, dataPath: String?, name: String): String {
        val base = root.directory.absolutePath.replace('\\', '/').trimEnd('/')
        val normalized = dataPath?.replace('\\', '/') ?: return name
        return if (normalized.startsWith("$base/")) normalized.removePrefix("$base/") else name
    }

    private fun mediaCollectionFor(rootKey: String): Uri? = when (rootKey) {
        "music", "podcast" -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        "movies", "camera" -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        "photos" -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        "download" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Downloads.EXTERNAL_CONTENT_URI else null
        else -> null
    }

    private fun fileJson(rootKey: String, relPath: String, name: String, size: Long, mtime: Long, mime: String, kind: String): JSONObject {
        val encoded = Uri.encode(relPath)
        val streamBase = "/api/storage/stream?root=$rootKey&path=$encoded"
        return JSONObject().apply {
            put("name", name)
            put("path", relPath)
            put("isDir", false)
            put("size", size)
            put("formattedSize", formatSize(size))
            put("mtime", mtime)
            put("mime", mime)
            put("kind", kind)
            put("streamUrl", streamBase)
            put("downloadUrl", "$streamBase&download=1")
            put("propUrl", "/api/storage/prop?root=$rootKey&path=$encoded")
            put("lyricsUrl", "/api/storage/lyrics?root=$rootKey&path=$encoded")
            put("artUrl", "/api/storage/art?root=$rootKey&path=$encoded")
        }
    }

    /** Menyelesaikan path relatif atau mediaId MediaStore menjadi sumber byte. */
    fun resolve(context: Context, rootKey: String, relativePath: String?, mediaId: Long?): Resolved? {
        if (!relativePath.isNullOrEmpty()) {
            val file = resolveInRoot(rootKey, relativePath)?.second ?: return null
            if (!file.isFile) return null
            return Resolved(file, null, file.name, file.length(), mimeTypeOf(file.name))
        }
        if (mediaId != null && mediaId > 0) {
            val root = findRoot(rootKey) ?: return null
            val collection = mediaCollectionFor(root.key) ?: return null
            val uri = Uri.withAppendedPath(collection, mediaId.toString())
            val name = queryString(context, uri, MediaStore.MediaColumns.DISPLAY_NAME) ?: "berkas_$mediaId"
            val size = queryString(context, uri, MediaStore.MediaColumns.SIZE)?.toLongOrNull() ?: 0L
            return Resolved(null, uri, name, size, mimeTypeOf(name))
        }
        return null
    }

    private fun queryString(context: Context, uri: Uri, column: String): String? {
        return runCatching {
            context.contentResolver.query(uri, arrayOf(column), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()
    }

    private sealed class RangeSpec {
        object Full : RangeSpec()
        data class Partial(val start: Long, val length: Long) : RangeSpec()
        object Unsatisfiable : RangeSpec()
    }

    /**
     * Melayani GET/HEAD dengan pemenggalan byte. Dukungan Range wajib agar elemen
     * audio/video dapat melakukan seek tanpa mengunduh berkas penuh.
     */
    fun serveStream(context: Context, resolved: Resolved, rangeHeader: String?, headOnly: Boolean, asDownload: Boolean, output: OutputStream): Boolean {
        val total = resolved.size
        val spec = parseRange(rangeHeader, total)

        if (spec is RangeSpec.Unsatisfiable) {
            writeStreamHeaders(output, 416, "Range Not Satisfiable", resolved.mimeType, 0, "bytes */$total", null)
            output.flush()
            return true
        }

        val start = if (spec is RangeSpec.Partial) spec.start else 0L
        val length = if (spec is RangeSpec.Partial) spec.length else total
        val status = if (spec is RangeSpec.Partial) 206 else 200
        val contentRange = if (spec is RangeSpec.Partial) "bytes $start-${start + length - 1}/$total" else null

        writeStreamHeaders(
            output, status, if (status == 206) "Partial Content" else "OK", resolved.mimeType, length,
            contentRange, dispositionFor(resolved.name, asDownload)
        )
        if (headOnly || length <= 0) {
            output.flush()
            return true
        }

        return try {
            when {
                resolved.file != null -> RandomAccessFile(resolved.file, "r").use { raf ->
                    raf.seek(start)
                    copyLimited(raf, output, length)
                }
                resolved.contentUri != null -> context.contentResolver.openInputStream(resolved.contentUri)?.use { input ->
                    skipFully(input, start)
                    copyLimited(input, output, length)
                } ?: return false
                else -> return false
            }
            output.flush()
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun writeStreamHeaders(
        output: OutputStream,
        status: Int,
        statusText: String,
        contentType: String,
        contentLength: Long,
        contentRange: String?,
        disposition: String?
    ) {
        val builder = StringBuilder()
        builder.append("HTTP/1.1 $status $statusText\r\n")
        builder.append("Content-Type: $contentType\r\n")
        builder.append("Content-Length: $contentLength\r\n")
        builder.append("Accept-Ranges: bytes\r\n")
        if (contentRange != null) builder.append("Content-Range: $contentRange\r\n")
        if (disposition != null) builder.append("Content-Disposition: $disposition\r\n")
        builder.append("Cache-Control: no-cache\r\n")
        builder.append("Access-Control-Allow-Origin: *\r\n")
        builder.append("Access-Control-Allow-Headers: Content-Type, Range, X-Singghara-Token\r\n")
        builder.append("Access-Control-Expose-Headers: Content-Range, Accept-Ranges\r\n")
        builder.append("Connection: close\r\n\r\n")
        output.write(builder.toString().toByteArray(Charsets.UTF_8))
    }

    private fun dispositionFor(fileName: String, asDownload: Boolean): String {
        val safe = fileName.replace("\"", "").replace("\r", "").replace("\n", "")
        return if (asDownload) "attachment; filename=\"$safe\"" else "inline; filename=\"$safe\""
    }

    private fun parseRange(header: String?, total: Long): RangeSpec {
        if (header.isNullOrBlank() || total <= 0) return RangeSpec.Full
        val value = header.trim()
        if (!value.startsWith("bytes=", ignoreCase = true)) return RangeSpec.Full
        val spec = value.substring("bytes=".length).split(",").firstOrNull()?.trim() ?: return RangeSpec.Full
        val dash = spec.indexOf('-')
        if (dash < 0) return RangeSpec.Full
        val startText = spec.substring(0, dash).trim()
        val endText = spec.substring(dash + 1).trim()
        return runCatching {
            if (startText.isEmpty()) {
                val suffix = endText.toLongOrNull() ?: return RangeSpec.Full
                if (suffix <= 0) return RangeSpec.Full
                val length = suffix.coerceAtMost(total)
                RangeSpec.Partial(total - length, length)
            } else {
                val start = startText.toLongOrNull() ?: return RangeSpec.Full
                if (start >= total) return RangeSpec.Unsatisfiable
                val end = if (endText.isEmpty()) total - 1 else (endText.toLongOrNull() ?: (total - 1))
                val clampedEnd = end.coerceIn(start, total - 1)
                RangeSpec.Partial(start, clampedEnd - start + 1)
            }
        }.getOrDefault(RangeSpec.Full)
    }

    private fun copyLimited(raf: RandomAccessFile, output: OutputStream, length: Long) {
        val buffer = ByteArray(COPY_BUFFER_SIZE)
        var remaining = length
        while (remaining > 0) {
            val read = raf.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
            if (read <= 0) break
            output.write(buffer, 0, read)
            remaining -= read
        }
    }

    private fun copyLimited(input: InputStream, output: OutputStream, length: Long) {
        val buffer = ByteArray(COPY_BUFFER_SIZE)
        var remaining = length
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
            if (read <= 0) break
            output.write(buffer, 0, read)
            remaining -= read
        }
    }

    private fun skipFully(input: InputStream, count: Long) {
        var remaining = count
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
            } else if (input.read() == -1) {
                return
            } else {
                remaining -= 1
            }
        }
    }

    fun metadataOf(file: File): JSONObject {
        val info = JSONObject()
        var retriever: MediaMetadataRetriever? = null
        try {
            retriever = MediaMetadataRetriever()
            retriever.setDataSource(file.absolutePath)
            info.put("title", retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE) ?: file.nameWithoutExtension)
            info.put("artist", retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: "")
            info.put("album", retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM) ?: "")
            info.put("durationMs", retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L)
            info.put("hasEmbeddedLyrics", !retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LYRICS).isNullOrBlank())
            info.put("hasArtwork", retriever.getEmbeddedPicture() != null)
        } catch (e: Exception) {
            info.put("title", file.nameWithoutExtension)
            info.put("artist", "")
            info.put("album", "")
            info.put("durationMs", 0L)
        } finally {
            runCatching { retriever?.release() }
        }
        return info
    }

    fun artworkOf(file: File): ByteArray? {
        var retriever: MediaMetadataRetriever? = null
        return runCatching {
            retriever = MediaMetadataRetriever()
            retriever.setDataSource(file.absolutePath)
            retriever.getEmbeddedPicture()
        }.getOrNull().also { runCatching { retriever?.release() } }
    }

    /** Lirik dari berkas .lrc berdampingan, lalu metadata tertanam. */
    fun lyricsOf(file: File): Pair<String, String> {
        val sidecar = listOf("lrc", "lyrics", "txt").asSequence()
            .map { File(file.parentFile, file.nameWithoutExtension + ".$it") }
            .firstOrNull { runCatching { it.isFile && it.length() in 1..(4L * 1024 * 1024) }.getOrDefault(false) }
        if (sidecar != null) {
            val text = runCatching { sidecar.readText(Charsets.UTF_8) }.getOrNull()
            if (!text.isNullOrBlank()) return text to "sidecar"
        }
        var retriever: MediaMetadataRetriever? = null
        val embedded = runCatching {
            retriever = MediaMetadataRetriever()
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LYRICS)
        }.getOrNull().also { runCatching { retriever?.release() } }
        if (!embedded.isNullOrBlank()) return embedded to "embedded"
        return "" to "none"
    }

    fun kindOf(mimeType: String): String = when {
        mimeType.startsWith("audio/") -> "audio"
        mimeType.startsWith("video/") -> "video"
        mimeType.startsWith("image/") -> "image"
        else -> "file"
    }

    fun mimeTypeOf(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase(Locale.getDefault())
        return extraMimeTypes[ext]
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: "application/octet-stream"
    }

    private val extraMimeTypes = mapOf(
        "m4a" to "audio/mp4",
        "aac" to "audio/aac",
        "ogg" to "audio/ogg",
        "oga" to "audio/ogg",
        "opus" to "audio/opus",
        "flac" to "audio/flac",
        "wav" to "audio/wav",
        "amr" to "audio/amr",
        "mp4" to "video/mp4",
        "m4v" to "video/mp4",
        "mkv" to "video/x-matroska",
        "webm" to "video/webm",
        "avi" to "video/x-msvideo",
        "mov" to "video/quicktime",
        "3gp" to "video/3gpp",
        "ts" to "video/mp2t",
        "m3u8" to "application/vnd.apple.mpegurl",
        "heic" to "image/heic",
        "heif" to "image/heif",
        "avif" to "image/avif",
        "gif" to "image/gif",
        "bmp" to "image/bmp",
        "srt" to "application/x-subrip",
        "vtt" to "text/vtt",
        "ass" to "text/x-ssa",
        "lrc" to "text/plain; charset=utf-8",
        "csv" to "text/csv; charset=utf-8",
        "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "apk" to "application/vnd.android.package-archive"
    )

    fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val index = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt().coerceIn(0, units.size - 1)
        return String.format(Locale.US, "%.1f %s", bytes / Math.pow(1024.0, index.toDouble()), units[index])
    }
}
