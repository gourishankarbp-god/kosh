package com.prash.kosh.sync

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class RemoteFile(val id: String, val name: String, val size: Long = 0)

/** Where sync files live. Google Drive in the app; a local folder in tests. */
interface RemoteStore {
    fun list(prefix: String): List<RemoteFile>
    fun download(id: String): ByteArray
    /** Creates the file, or replaces its content when [existingId] is given. */
    fun upload(name: String, bytes: ByteArray, existingId: String? = null): RemoteFile
    fun delete(id: String)
}

class AuthRequired : Exception("Google sign-in needed")
class RemoteError(val code: Int, msg: String) : Exception(msg)

/** Test and cross-platform store: files in a folder; the file name is the id. */
class FolderRemoteStore(private val dir: File) : RemoteStore {
    init { dir.mkdirs() }
    override fun list(prefix: String) = dir.listFiles().orEmpty().filter { it.name.startsWith(prefix) }.sortedBy { it.name }
        .map { RemoteFile(it.name, it.name, it.length()) }
    override fun download(id: String) = File(dir, id).readBytes()
    override fun upload(name: String, bytes: ByteArray, existingId: String?): RemoteFile {
        File(dir, existingId ?: name).writeBytes(bytes); return RemoteFile(existingId ?: name, name, bytes.size.toLong())
    }
    override fun delete(id: String) { File(dir, id).delete() }
}

/**
 * Google Drive REST v3, limited to the app's hidden appDataFolder (scope drive.appdata).
 * [token] returns a fresh OAuth access token or throws [AuthRequired].
 */
class DriveRemoteStore(private val token: () -> String) : RemoteStore {
    private val api = "https://www.googleapis.com/drive/v3/files"
    private val upload = "https://www.googleapis.com/upload/drive/v3/files"

    private fun open(url: String, method: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = method; connectTimeout = 20_000; readTimeout = 60_000
        setRequestProperty("Authorization", "Bearer ${token()}")
    }

    private fun HttpURLConnection.check(): HttpURLConnection {
        val code = responseCode
        if (code == 401) throw AuthRequired()
        if (code !in 200..299) throw RemoteError(code, "Drive error $code: " + (errorStream?.bufferedReader()?.readText()?.take(300) ?: ""))
        return this
    }

    override fun list(prefix: String): List<RemoteFile> {
        val out = ArrayList<RemoteFile>()
        var page: String? = null
        do {
            val q = URLEncoder.encode("name contains '$prefix' and trashed = false", "UTF-8")
            val url = "$api?spaces=appDataFolder&pageSize=1000&fields=nextPageToken,files(id,name,size)&q=$q" + (page?.let { "&pageToken=$it" } ?: "")
            val body = open(url, "GET").check().inputStream.bufferedReader().readText()
            val json = kotlinx.serialization.json.Json.parseToJsonElement(body) as kotlinx.serialization.json.JsonObject
            (json["files"] as? kotlinx.serialization.json.JsonArray)?.forEach { f ->
                val o = f as kotlinx.serialization.json.JsonObject
                val name = o["name"].toString().trim('"')
                if (name.startsWith(prefix)) out += RemoteFile(o["id"].toString().trim('"'), name, o["size"]?.toString()?.trim('"')?.toLongOrNull() ?: 0)
            }
            page = json["nextPageToken"]?.toString()?.trim('"')
        } while (page != null)
        return out.sortedBy { it.name }
    }

    override fun download(id: String): ByteArray = open("$api/$id?alt=media", "GET").check().inputStream.use { it.readBytes() }

    override fun upload(name: String, bytes: ByteArray, existingId: String?): RemoteFile {
        if (existingId != null) {
            val c = open("$upload/$existingId?uploadType=media&fields=id,name", "PATCH")
            c.doOutput = true; c.setRequestProperty("Content-Type", "application/octet-stream")
            c.outputStream.use { it.write(bytes) }; c.check()
            return RemoteFile(existingId, name, bytes.size.toLong())
        }
        val boundary = "kosh" + System.nanoTime()
        val meta = """{"name":"$name","parents":["appDataFolder"]}"""
        val c = open("$upload?uploadType=multipart&fields=id,name", "POST")
        c.doOutput = true; c.setRequestProperty("Content-Type", "multipart/related; boundary=$boundary")
        c.outputStream.use { o ->
            o.write("--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$meta\r\n--$boundary\r\nContent-Type: application/octet-stream\r\n\r\n".toByteArray())
            o.write(bytes); o.write("\r\n--$boundary--\r\n".toByteArray())
        }
        val body = c.check().inputStream.bufferedReader().readText()
        val id = Regex("\"id\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1) ?: error("No file id from Drive")
        return RemoteFile(id, name, bytes.size.toLong())
    }

    override fun delete(id: String) {
        val c = open("$api/$id", "DELETE"); val code = c.responseCode
        if (code == 401) throw AuthRequired()
        if (code !in 200..299 && code != 404) throw RemoteError(code, "Drive delete failed $code")
    }
}
