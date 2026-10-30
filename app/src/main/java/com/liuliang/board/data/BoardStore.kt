package com.liuliang.board.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.liuliang.board.model.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface BoardStore {
    fun read(): BoardData
    fun write(data: BoardData)
}

/** Entire payload is encrypted; Android's no-backup directory also excludes device transfer. */
class EncryptedBoardStore(context: Context) : BoardStore {
    private val file = AtomicFile(File(context.noBackupFilesDir, "board.v1.enc"))
    private val alias = "traffic-board-data-v1"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    @Synchronized override fun read(): BoardData {
        val payload = try { file.readFully() } catch (e: java.io.FileNotFoundException) {
            if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return BoardData()
            throw e
        }
        require(payload.size >= 30 && payload[0] == 1.toByte()) { "Invalid storage format" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, payload.copyOfRange(1, 13)))
        return BoardCodec.decode(cipher.doFinal(payload.copyOfRange(13, payload.size)).toString(Charsets.UTF_8))
    }
    @Synchronized override fun write(data: BoardData) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val payload = byteArrayOf(1) + cipher.iv + cipher.doFinal(BoardCodec.encode(data).toByteArray(Charsets.UTF_8))
        val stream = file.startWrite()
        try { stream.write(payload); file.finishWrite(stream) }
        catch (e: Exception) { file.failWrite(stream); throw e }
    }
}

object BoardCodec {
    fun encode(data: BoardData): String = JSONObject().apply {
        put("version", 1)
        put("preferences", JSONObject().apply {
            put("themeMode", data.preferences.themeMode.name)
            put("dynamicColors", data.preferences.dynamicColors)
            put("hapticsEnabled", data.preferences.hapticsEnabled)
        })
        put("settings", JSONObject().apply {
            put("usageEnabled", data.settings.usageEnabled)
            put("expiryEnabled", data.settings.expiryEnabled)
            put("usageThresholds", JSONArray(data.settings.usageThresholds))
            put("expiryDays", JSONArray(data.settings.expiryDays))
        })
        put("subscriptions", JSONArray().apply { data.subscriptions.forEach { sub ->
            put(JSONObject().apply {
                put("id", sub.id); put("name", sub.name); put("url", sub.url); put("userAgent", sub.userAgent)
                put("sourceRevision", sub.sourceRevision)
                put("lastSuccessAt", sub.lastSuccessAt); put("lastAttemptAt", sub.lastAttemptAt); put("status", sub.status.name)
                sub.info?.let { t -> put("info", JSONObject().apply {
                    put("upload", t.upload); put("download", t.download); put("total", t.total); put("expire", t.expire)
                }) }
                put("alerts", JSONObject().apply {
                    put("usage", JSONArray(sub.alerts.usage.toList())); put("expiry", JSONArray(sub.alerts.expiry.toList()))
                    put("expireIdentity", sub.alerts.expireIdentity)
                })
            })
        } })
    }.toString()

    fun decode(json: String): BoardData {
        val root = JSONObject(json)
        require(root.getInt("version") == 1)
        val settings = root.getJSONObject("settings")
        val preferences = root.optJSONObject("preferences")
        val array = root.getJSONArray("subscriptions")
        return BoardData((0 until array.length()).map { index ->
            val obj = array.getJSONObject(index)
            val info = obj.optJSONObject("info")
            val alerts = obj.optJSONObject("alerts")
            Subscription(obj.getString("id"), obj.getString("name"), obj.getString("url"), obj.getString("userAgent"),
                info?.let { TrafficInfo(it.longOrNull("upload"), it.longOrNull("download"), it.longOrNull("total"), it.longOrNull("expire")) },
                obj.longOrNull("lastSuccessAt"), obj.longOrNull("lastAttemptAt"), RefreshStatus.valueOf(obj.getString("status")),
                alerts?.let { AlertMemory(it.optJSONArray("usage").ints().toSet(), it.optJSONArray("expiry").ints().toSet(), it.longOrNull("expireIdentity")) } ?: AlertMemory(), obj.optLong("sourceRevision", 0))
        }, AlertSettings(settings.getBoolean("usageEnabled"), settings.getBoolean("expiryEnabled"),
            settings.getJSONArray("usageThresholds").ints(), settings.getJSONArray("expiryDays").ints()),
            UiPreferences(ThemeMode.entries.find { it.name == preferences?.optString("themeMode") } ?: ThemeMode.SYSTEM,
                preferences?.optBoolean("dynamicColors", true) ?: true, preferences?.optBoolean("hapticsEnabled", true) ?: true))
    }
    private fun JSONObject.longOrNull(key: String): Long? = if (has(key) && !isNull(key)) getLong(key) else null
    private fun JSONArray?.ints(): List<Int> = if (this == null) emptyList() else (0 until length()).map { getInt(it) }
}
