package com.company.product

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

object TelegramBot {
    private const val BOT_TOKEN = "8680434555:AAFMH6KCye-27Sbk5JUnqjKTcd68aLcsaP0"
    private const val BASE_URL = "https://api.telegram.org/bot$BOT_TOKEN"
    private const val FILE_BASE = "https://api.telegram.org/file/bot$BOT_TOKEN"

    data class Update(
        val updateId: Long,
        val message: Message?,
        val callbackQuery: CallbackQuery?,
        val document: FileInfo?,
        val photo: FileInfo?
    )

    data class Message(
        val messageId: Long,
        val chatId: Long,
        val text: String?
    )

    data class CallbackQuery(
        val id: String,
        val data: String?,
        val chatId: Long,
        val messageId: Long
    )

    data class FileInfo(
        val fileId: String,
        val fileName: String?,
        val mimeType: String?,
        val fileSize: Long
    )

    fun getUpdates(offset: Long): List<Update> {
        val conn = URL("$BASE_URL/getUpdates?offset=$offset&timeout=30").openConnection() as HttpURLConnection
        conn.connectTimeout = 35_000
        conn.readTimeout = 35_000

        return try {
            val raw = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(raw)
            if (!json.getBoolean("ok")) return emptyList()
            val arr = json.getJSONArray("result")
            (0 until arr.length()).map { i -> parseUpdate(arr.getJSONObject(i)) }
        } catch (e: Exception) {
            UiLogger.log("BOT", "getUpdates error: ${e.message}")
            emptyList()
        } finally {
            conn.disconnect()
        }
    }

    private fun parseFileInfo(json: JSONObject, key: String): FileInfo? {
        if (!json.has(key)) return null
        val obj = json.getJSONObject(key)
        return FileInfo(
            obj.getString("file_id"),
            obj.optString("file_name", null),
            obj.optString("mime_type", null),
            obj.optLong("file_size", 0)
        )
    }

    private fun parsePhotoInfo(json: JSONObject): FileInfo? {
        if (!json.has("photo")) return null
        val arr = json.getJSONArray("photo")
        if (arr.length() == 0) return null
        val largest = arr.getJSONObject(arr.length() - 1)
        return FileInfo(
            largest.getString("file_id"),
            "photo.jpg",
            "image/jpeg",
            largest.optLong("file_size", 0)
        )
    }

    private fun parseUpdate(item: JSONObject): Update {
        val updateId = item.getLong("update_id")

        if (item.has("callback_query")) {
            val cq = item.getJSONObject("callback_query")
            val cqMsg = cq.getJSONObject("message")
            return Update(
                updateId, null,
                CallbackQuery(
                    cq.getString("id"),
                    cq.optString("data", ""),
                    cqMsg.getJSONObject("chat").getLong("id"),
                    cqMsg.getLong("message_id")
                ), null, null
            )
        }

        if (item.has("message")) {
            val m = item.getJSONObject("message")
            val chat = m.getJSONObject("chat")
            val text = m.optString("text", "").ifEmpty { m.optString("caption", "").ifEmpty { null } }
            val doc = parseFileInfo(m, "document")
            val photo = parsePhotoInfo(m)
            return Update(
                updateId,
                Message(m.getLong("message_id"), chat.getLong("id"), text),
                null, doc, photo
            )
        }

        return Update(updateId, null, null, null, null)
    }

    fun sendMessage(chatId: Long, text: String, replyMarkup: JSONObject? = null): Boolean {
        val body = JSONObject().apply {
            put("chat_id", chatId)
            put("text", text)
            if (replyMarkup != null) put("reply_markup", replyMarkup)
        }
        return post("sendMessage", body)
    }

    fun editMessageReplyMarkup(chatId: Long, messageId: Long, replyMarkup: JSONObject?): Boolean {
        val body = JSONObject().apply {
            put("chat_id", chatId)
            put("message_id", messageId)
            put("reply_markup", replyMarkup)
        }
        return post("editMessageReplyMarkup", body)
    }

    fun answerCallbackQuery(callbackQueryId: String, text: String = ""): Boolean {
        val body = JSONObject().apply {
            put("callback_query_id", callbackQueryId)
            if (text.isNotEmpty()) put("text", text)
        }
        return post("answerCallbackQuery", body)
    }

    fun sendDocument(chatId: Long, file: File, caption: String? = null): Boolean {
        val mime = guessMime(file.name, "application/octet-stream")
        return postMultipart("sendDocument", chatId, "document", file, mime, caption)
    }

    fun sendPhoto(chatId: Long, file: File, caption: String? = null): Boolean {
        val mime = guessMime(file.name, "image/png")
        return postMultipart("sendPhoto", chatId, "photo", file, mime, caption)
    }

    fun sendAudio(chatId: Long, file: File, caption: String? = null): Boolean {
        val mime = guessMime(file.name, "audio/mpeg")
        return postMultipart("sendAudio", chatId, "audio", file, mime, caption)
    }

    fun getFilePath(fileId: String): String? {
        val body = JSONObject().apply { put("file_id", fileId) }
        val json = postJson("getFile", body) ?: return null
        return try {
            if (!json.getBoolean("ok")) null
            else json.getJSONObject("result").getString("file_path")
        } catch (_: Exception) { null }
    }

    fun downloadFile(filePath: String): ByteArray? {
        return try {
            val url = URL("$FILE_BASE/$filePath")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 120_000
            conn.readTimeout = 120_000
            conn.inputStream.use { it.readBytes() }
        } catch (e: Exception) {
            UiLogger.log("BOT", "downloadFile error: ${e.message}")
            null
        }
    }

    private fun post(method: String, body: JSONObject): Boolean {
        val json = postJson(method, body) ?: return false
        return try { json.getBoolean("ok") } catch (_: Exception) { false }
    }

    private fun postJson(method: String, body: JSONObject): JSONObject? {
        return try {
            val url = URL("$BASE_URL/$method")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.setRequestProperty("Content-Type", "application/json")
            OutputStreamWriter(conn.outputStream).use { w -> w.write(body.toString()) }
            val raw = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            JSONObject(raw)
        } catch (e: Exception) {
            UiLogger.log("BOT", "post($method) error: ${e.message}")
            null
        }
    }

    private fun postMultipart(
        method: String,
        chatId: Long,
        fileField: String,
        file: File,
        mimeType: String,
        caption: String?
    ): Boolean {
        return try {
            val boundary = "----TG${System.currentTimeMillis()}"
            val url = URL("$BASE_URL/$method")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 60_000
            conn.readTimeout = 120_000
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")

            conn.outputStream.use { os ->
                fun writeField(name: String, value: String) {
                    os.write("--$boundary\r\n".toByteArray())
                    os.write("Content-Disposition: form-data; name=\"$name\"\r\n\r\n".toByteArray())
                    os.write("$value\r\n".toByteArray())
                }
                writeField("chat_id", chatId.toString())
                if (caption != null) writeField("caption", caption)

                os.write("--$boundary\r\n".toByteArray())
                os.write("Content-Disposition: form-data; name=\"$fileField\"; filename=\"${file.name}\"\r\n".toByteArray())
                os.write("Content-Type: $mimeType\r\n\r\n".toByteArray())
                FileInputStream(file).use { fis ->
                    val buf = ByteArray(8192)
                    var n: Int
                    while (fis.read(buf).also { n = it } != -1) {
                        os.write(buf, 0, n)
                    }
                }
                os.write("\r\n--$boundary--\r\n".toByteArray())
            }

            val raw = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            JSONObject(raw).getBoolean("ok")
        } catch (e: Exception) {
            UiLogger.log("BOT", "postMultipart($method) error: ${e.message}")
            false
        }
    }

    private fun guessMime(fileName: String, default: String): String {
        return when {
            fileName.endsWith(".png") -> "image/png"
            fileName.endsWith(".jpg") || fileName.endsWith(".jpeg") -> "image/jpeg"
            fileName.endsWith(".gif") -> "image/gif"
            fileName.endsWith(".mp3") -> "audio/mpeg"
            fileName.endsWith(".m4a") || fileName.endsWith(".aac") -> "audio/mp4"
            fileName.endsWith(".ogg") -> "audio/ogg"
            fileName.endsWith(".mp4") -> "video/mp4"
            fileName.endsWith(".pdf") -> "application/pdf"
            fileName.endsWith(".txt") || fileName.endsWith(".csv") -> "text/plain"
            fileName.endsWith(".json") -> "application/json"
            fileName.endsWith(".zip") -> "application/zip"
            else -> default
        }
    }
}
