package com.viniison.axiom

import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject

/**
 * Histórico persistente no SQLite privado do Android.
 * Nunca guarda pesos GGUF: modelos continuam em filesDir com mesmo applicationId.
 */
class ChatStore(context: Context) : SQLiteOpenHelper(context, "axiom_chats.db", null, 1) {
    data class Thread(val id: Long, val title: String, val updatedAt: Long)
    data class Entry(val id: Long, val role: String, val content: String)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE threads(id INTEGER PRIMARY KEY AUTOINCREMENT,title TEXT NOT NULL,created_at INTEGER NOT NULL,updated_at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE messages(id INTEGER PRIMARY KEY AUTOINCREMENT,thread_id INTEGER NOT NULL,role TEXT NOT NULL,content TEXT NOT NULL,created_at INTEGER NOT NULL,FOREIGN KEY(thread_id) REFERENCES threads(id) ON DELETE CASCADE)")
        db.execSQL("CREATE INDEX messages_thread_idx ON messages(thread_id,id)")
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Migrações futuras devem conservar tabelas e conteúdo.
    }

    fun newThread(title: String = "Nova conversa"): Long {
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put("title", title); put("created_at", now); put("updated_at", now)
        }
        return writableDatabase.insertOrThrow("threads", null, values)
    }

    fun hasThread(id: Long): Boolean {
        if (id < 0L) return false
        readableDatabase.rawQuery("SELECT id FROM threads WHERE id=?", arrayOf(id.toString())).use {
            return it.moveToFirst()
        }
    }

    fun threads(): List<Thread> {
        val result = mutableListOf<Thread>()
        readableDatabase.rawQuery("SELECT id,title,updated_at FROM threads ORDER BY updated_at DESC,id DESC", null).use { c ->
            while (c.moveToNext()) result.add(Thread(c.getLong(0), c.getString(1), c.getLong(2)))
        }
        return result
    }

    fun messages(threadId: Long): List<Entry> {
        val result = mutableListOf<Entry>()
        readableDatabase.rawQuery("SELECT id,role,content FROM messages WHERE thread_id=? ORDER BY id ASC", arrayOf(threadId.toString())).use { c ->
            while (c.moveToNext()) result.add(Entry(c.getLong(0), c.getString(1), c.getString(2)))
        }
        return result
    }

    fun addMessage(threadId: Long, role: String, content: String): Long {
        require(role == "user" || role == "assistant")
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put("thread_id", threadId); put("role", role); put("content", content); put("created_at", now)
        }
        val db = writableDatabase
        val id = db.insertOrThrow("messages", null, values)
        db.execSQL("UPDATE threads SET updated_at=? WHERE id=?", arrayOf(now, threadId))
        return id
    }

    fun updateMessage(id: Long, content: String) {
        writableDatabase.update("messages", ContentValues().apply { put("content", content) },
            "id=?", arrayOf(id.toString()))
    }

    /** Edita a mensagem escolhida, preservando as respostas antigas como histórico. */
    fun editUserMessage(threadId: Long, messageId: Long, newContent: String): Boolean {
        val value = newContent.trim()
        if (value.isBlank()) return false
        val db = writableDatabase
        val affected = db.update("messages", ContentValues().apply { put("content", value) },
            "id=? AND thread_id=? AND role='user'",
            arrayOf(messageId.toString(), threadId.toString()))
        if (affected > 0) {
            db.execSQL("UPDATE threads SET updated_at=? WHERE id=?",
                arrayOf(System.currentTimeMillis(), threadId))
        }
        return affected > 0
    }

    fun setTitle(threadId: Long, text: String) {
        val title = text.trim().replace(Regex("\\s+"), " ").take(46).ifBlank { "Nova conversa" }
        writableDatabase.update("threads", ContentValues().apply { put("title", title) },
            "id=?", arrayOf(threadId.toString()))
    }

    fun clearThread(threadId: Long) {
        writableDatabase.delete("messages", "thread_id=?", arrayOf(threadId.toString()))
        setTitle(threadId, "Nova conversa")
    }

    fun deleteThread(threadId: Long) {
        writableDatabase.delete("threads", "id=?", arrayOf(threadId.toString()))
    }

    /** Migração v0.1: SharedPreferences["conversation"] -> SQLite, só uma vez. */
    fun migrateLegacy(prefs: SharedPreferences) {
        if (prefs.getBoolean("migrated_history_v1", false)) return
        val legacy = prefs.getString("conversation", "[]") ?: "[]"
        try {
            val items = JSONArray(legacy)
            if (items.length() > 0) {
                val id = newThread("Conversa anterior")
                var first = ""
                for (i in 0 until items.length()) {
                    val obj = items.optJSONObject(i) ?: continue
                    val role = obj.optString("role")
                    val content = obj.optString("content")
                    if (role != "user" && role != "assistant") continue
                    if (content.isBlank()) continue
                    addMessage(id, role, content)
                    if (first.isEmpty() && role == "user") first = content
                }
                if (first.isNotBlank()) setTitle(id, first)
                prefs.edit().putLong("current_chat_id", id).remove("conversation")
                    .putBoolean("migrated_history_v1", true).apply()
            } else {
                prefs.edit().putBoolean("migrated_history_v1", true).apply()
            }
        } catch (_: Exception) {
            // Não descartar o legado em caso de problema: tentar outra vez no próximo início.
        }
    }

    fun exportJson(): String {
        val result = JSONObject().put("format", "axiom-chat-backup-v1")
        val threadsJson = JSONArray()
        for (thread in threads()) {
            val msgs = JSONArray()
            for (msg in messages(thread.id)) {
                msgs.put(JSONObject().put("role", msg.role).put("content", msg.content))
            }
            threadsJson.put(JSONObject().put("title", thread.title).put("messages", msgs))
        }
        return result.put("threads", threadsJson).toString(2)
    }

    /** Importa somente estrutura AXIOM válida. Mantém os chats existentes. */
    fun importJson(raw: String): Int {
        val root = JSONObject(raw)
        require(root.optString("format") == "axiom-chat-backup-v1") { "Formato de backup desconhecido" }
        val source = root.getJSONArray("threads")
        require(source.length() <= 300) { "Backup com conversas demais" }
        var count = 0
        for (i in 0 until source.length()) {
            val thread = source.getJSONObject(i)
            val sourceMessages = thread.getJSONArray("messages")
            require(sourceMessages.length() <= 3000) { "Conversa longa demais para importar" }
            val title = thread.optString("title").take(46).ifBlank { "Conversa importada" }
            val targetId = newThread(title)
            for (j in 0 until sourceMessages.length()) {
                val message = sourceMessages.getJSONObject(j)
                val role = message.optString("role")
                val content = message.optString("content")
                if (role in listOf("user", "assistant") && content.isNotBlank()) {
                    addMessage(targetId, role, content.take(100_000))
                }
            }
            count++
        }
        return count
    }
}
