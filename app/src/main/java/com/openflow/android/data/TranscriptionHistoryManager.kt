package com.openflow.android.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class TranscriptionItem(
    val id: String = System.currentTimeMillis().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val text: String,
    val provider: String,
    val preset: String
)

class TranscriptionHistoryManager(private val context: Context) {
    private val file = File(context.filesDir, "transcriptions_history.json")

    fun getAll(): List<TranscriptionItem> {
        if (!file.exists()) return emptyList()
        return try {
            val content = file.readText()
            val array = JSONArray(content)
            val list = mutableListOf<TranscriptionItem>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    TranscriptionItem(
                        id = obj.optString("id"),
                        timestamp = obj.optLong("timestamp"),
                        text = obj.optString("text"),
                        provider = obj.optString("provider"),
                        preset = obj.optString("preset")
                    )
                )
            }
            list.sortedByDescending { it.timestamp }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun add(text: String, provider: String, preset: String) {
        if (text.isBlank()) return
        val current = getAll().toMutableList()
        current.add(0, TranscriptionItem(text = text.trim(), provider = provider, preset = preset))
        val trimmed = current.take(50)
        save(trimmed)
    }

    fun clear() {
        if (file.exists()) file.delete()
    }

    fun delete(id: String) {
        val current = getAll().filter { it.id != id }
        save(current)
    }

    private fun save(list: List<TranscriptionItem>) {
        try {
            val array = JSONArray()
            for (item in list) {
                val obj = JSONObject().apply {
                    put("id", item.id)
                    put("timestamp", item.timestamp)
                    put("text", item.text)
                    put("provider", item.provider)
                    put("preset", item.preset)
                }
                array.put(obj)
            }
            file.writeText(array.toString())
        } catch (_: Exception) {}
    }
}
