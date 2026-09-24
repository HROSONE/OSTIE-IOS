package com.osone.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class ChatMessage(val role: String, val text: String)

/** Histórico privado ao app; falhas de disco não apagam a conversa em memória. */
class ConversationStore(context: Context) {
    private val file = java.io.File(context.filesDir, "conversa.json")

    fun read(): List<ChatMessage> = try {
        val array = JSONArray(file.readText())
        (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val role = item.optString("role")
            if (role != "user" && role != "model") return@mapNotNull null
            ChatMessage(role, item.optString("text"))
        }.takeLast(100)
    } catch (_: Exception) { emptyList() }

    fun save(messages: List<ChatMessage>) {
        val array = JSONArray()
        messages.takeLast(100).forEach { message ->
            array.put(JSONObject().put("role", message.role).put("text", message.text))
        }
        val temp = java.io.File(file.parentFile, "conversa.tmp")
        temp.writeText(array.toString())
        if (!temp.renameTo(file)) {
            temp.copyTo(file, overwrite = true)
            temp.delete()
        }
    }

    fun clear() { file.delete() }
}
