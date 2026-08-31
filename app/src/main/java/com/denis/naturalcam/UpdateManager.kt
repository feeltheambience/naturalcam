package com.denis.naturalcam

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Простой OTA-апдейтер для сайдлоад-версии.
 *
 * На сервере лежит version.json:
 *   { "versionCode": 4, "versionName": "0.4", "url": "https://.../NaturalCam-0.4.apk", "notes": "что нового" }
 *
 * Приложение при запуске сравнивает versionCode с BuildConfig.VERSION_CODE,
 * если на сервере новее — показывает баннер, качает APK и запускает системный установщик.
 * (Полностью тихая установка на несистемном приложении невозможна — юзер один раз жмёт «Установить».)
 */
object UpdateManager {

    // Где лежит манифест версии. Прямой хостинг на сервере Дениса (без посредников).
    const val MANIFEST_URL = "http://188.227.86.219:8404/version.json"

    data class Release(
        val versionCode: Int,
        val versionName: String,
        val url: String,
        val notes: String
    )

    /** Вернёт Release, если на сервере версия новее текущей; иначе null (в т.ч. при любой ошибке/оффлайне). */
    fun check(currentCode: Int): Release? {
        return try {
            val conn = (URL(MANIFEST_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
                requestMethod = "GET"
            }
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            val o = JSONObject(text)
            val rel = Release(
                versionCode = o.getInt("versionCode"),
                versionName = o.getString("versionName"),
                url = o.getString("url"),
                notes = o.optString("notes", "")
            )
            if (rel.versionCode > currentCode) rel else null
        } catch (e: Throwable) {
            null
        }
    }

    /**
     * Качает APK с ВОЗОБНОВЛЕНИЕМ (HTTP Range) и ретраями — устойчиво к обрывам на медленном канале.
     * onProgress — проценты (0..100), -1 если размер неизвестен.
     * Возвращает файл только если докачан полностью (иначе null).
     */
    fun download(context: Context, rel: Release, onProgress: (Int) -> Unit): File? {
        val dir = File(context.getExternalFilesDir(null), "apk").apply { mkdirs() }
        // Оставляем только файл текущей версии (для докачки), чистим прочее
        val out = File(dir, "NaturalCam-${rel.versionName}.apk")
        dir.listFiles()?.forEach { if (it != out) it.delete() }

        var total = -1L
        repeat(6) { attempt ->
            try {
                val existing = if (out.exists()) out.length() else 0L
                // Если уже скачали сколько нужно — готово
                if (total > 0 && existing >= total) return out

                val conn = (URL(rel.url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15000
                    readTimeout = 20000
                    instanceFollowRedirects = true
                    if (existing > 0) setRequestProperty("Range", "bytes=$existing-")
                }
                conn.connect()
                val code = conn.responseCode
                val partial = code == HttpURLConnection.HTTP_PARTIAL // 206
                // Если попросили Range, а сервер отдал 200 (не поддержал) — начинаем заново
                val startAt = if (partial) existing else 0L
                val append = partial
                if (!partial && existing > 0) { out.delete() }

                val remaining = conn.contentLengthLong
                if (remaining > 0) total = startAt + remaining

                var sum = startAt
                conn.inputStream.use { ins ->
                    java.io.FileOutputStream(out, append).use { fos ->
                        val buf = ByteArray(1 shl 16)
                        var read: Int
                        while (ins.read(buf).also { read = it } >= 0) {
                            fos.write(buf, 0, read)
                            sum += read
                            onProgress(if (total > 0) (sum * 100 / total).toInt().coerceIn(0, 100) else -1)
                        }
                        fos.flush()
                    }
                }
                conn.disconnect()

                if (total <= 0 || out.length() >= total) return out
                // иначе — недокачали, следующий заход докачает с Range
            } catch (e: Throwable) {
                // пауза перед ретраем
                try { Thread.sleep(1500) } catch (_: Throwable) {}
            }
        }
        return if (total > 0 && out.exists() && out.length() >= total) out else null
    }

    /** Запускает системный установщик для скачанного APK. */
    fun install(context: Context, apk: File) {
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Throwable) {
            // молча — баннер останется, можно повторить
        }
    }
}
