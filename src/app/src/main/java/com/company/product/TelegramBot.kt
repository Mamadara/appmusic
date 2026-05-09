package com.company.product

import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

object TelegramBot {
    // ⚠️ Replace this with your actual bot token from @BotFather
    private const val BOT_TOKEN = "YOUR_BOT_TOKEN_HERE"
    private const val BASE_URL = "https://api.telegram.org/bot$BOT_TOKEN"

    data class Update(
        val updateId: Long,
        val message: Message?,
        val callbackQuery: CallbackQuery?
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
            emptyList()
        } finally {
            conn.disconnect()
        }
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
                )
            )
        }

        if (item.has("message")) {
            val m = item.getJSONObject("message")
            val chat = m.getJSONObject("chat")
            return Update(
                updateId,
                Message(
                    m.getLong("message_id"),
                    chat.getLong("id"),
                    m.optString("text", "")
                ), null
            )
        }

        return Update(updateId, null, null)
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

    private fun post(method: String, body: JSONObject): Boolean {
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
            JSONObject(raw).getBoolean("ok")
        } catch (e: Exception) {
            false
        }
    }
}
