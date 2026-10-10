package com.viniison.axiom

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

/** Miniaturas privadas para os anexos de imagem enviados pelo usuário. */
object AxiomImageVault {
    private val valid = Regex("[a-f0-9-]{36}\\.jpg")
    fun file(context: Context, name: String): File? {
        if (!valid.matches(name)) return null
        return File(File(context.filesDir, "chat_images").apply { mkdirs() }, name)
    }
    suspend fun save(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Imagem inválida" }
        var sample = 1
        while (bounds.outWidth / sample > 1200 || bounds.outHeight / sample > 1200) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        val bitmap = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: error("Imagem inacessível")
        val name = UUID.randomUUID().toString() + ".jpg"
        try {
            FileOutputStream(file(context, name) ?: error("Nome inválido")).use {
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 78, it))
            }
        } finally { bitmap.recycle() }
        name
    }
    fun thumbnail(context: Context, name: String): Bitmap? {
        val source = file(context, name)?.takeIf { it.isFile } ?: return null
        return BitmapFactory.decodeFile(source.absolutePath, BitmapFactory.Options().apply {
            inSampleSize = 2
            inPreferredConfig = Bitmap.Config.RGB_565
        })
    }
}

data class CommonsImage(val title: String, val thumb: String, val source: String)

class AxiomCommonsSearch {
    suspend fun search(query: String): List<CommonsImage> = withContext(Dispatchers.IO) {
        val term = URLEncoder.encode(query.take(90), "UTF-8")
        val url = "https://commons.wikimedia.org/w/api.php?action=query&generator=search" +
            "&gsrnamespace=6&gsrsearch=" + term + "&gsrlimit=4" +
            "&prop=imageinfo&iiprop=url&iiurlwidth=420&format=json"
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 9000; conn.readTimeout = 11000
        conn.setRequestProperty("User-Agent", "AXIOM-Android/0.4 (visual search)")
        try {
            if (conn.responseCode != 200) error("Imagens HTTP " + conn.responseCode)
            val json = conn.inputStream.bufferedReader().use { it.readText() }
            val pages = JSONObject(json).optJSONObject("query")?.optJSONObject("pages")
                ?: return@withContext emptyList()
            val result = mutableListOf<CommonsImage>()
            for (id in pages.keys()) {
                val obj = pages.getJSONObject(id)
                val info = obj.optJSONArray("imageinfo")?.optJSONObject(0) ?: continue
                val thumb = info.optString("thumburl", info.optString("url"))
                val source = info.optString("descriptionurl")
                if (Uri.parse(thumb).host != "upload.wikimedia.org" ||
                    !thumb.startsWith("https://") ||
                    !source.startsWith("https://commons.wikimedia.org/wiki/")) continue
                result.add(CommonsImage(obj.optString("title").removePrefix("File:"), thumb, source))
            }
            result.take(4)
        } finally { conn.disconnect() }
    }

    suspend fun loadBitmap(url: String): Bitmap? = withContext(Dispatchers.IO) {
        if (Uri.parse(url).host != "upload.wikimedia.org" || !url.startsWith("https://")) return@withContext null
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 10000
        c.instanceFollowRedirects = false
        try {
            if (c.responseCode != 200 || c.contentLengthLong > 5_000_000) return@withContext null
            c.inputStream.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.RGB_565; inSampleSize = 2
                })
            }
        } finally { c.disconnect() }
    }
}

/** Sem autenticação: só lê dados públicos e não altera repositórios. */
class AxiomGithubPublicPlugin : AxiomPlugin {
    override val id = "github"
    override val title = "GitHub público"
    override val description = "Use /github dono/repositorio para ver descrição e commits."

    suspend fun inspect(name: String): String = withContext(Dispatchers.IO) {
        require(Regex("[A-Za-z0-9_.-]{1,60}/[A-Za-z0-9_.-]{1,100}").matches(name)) {
            "Formato: /github ViniIsOn/AXIOM"
        }
        fun api(suffix: String): String {
            val conn = URL("https://api.github.com/repos/" + name + suffix)
                .openConnection() as HttpURLConnection
            conn.connectTimeout = 9000; conn.readTimeout = 10000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "AXIOM-Android/0.4")
            try {
                if (conn.responseCode != 200) error("GitHub HTTP " + conn.responseCode)
                return conn.inputStream.bufferedReader().use { it.readText().take(90_000) }
            } finally { conn.disconnect() }
        }
        val obj = JSONObject(api(""))
        val out = StringBuilder("GitHub: " + obj.optString("full_name") + "\n")
        out.append("Descrição: ").append(obj.optString("description").ifBlank { "Sem descrição" }).append("\n")
        out.append("Linguagem: ").append(obj.optString("language")).append("\n")
        out.append("Estrelas: ").append(obj.optInt("stargazers_count")).append("\n")
        out.append("Branch: ").append(obj.optString("default_branch")).append("\n")
        out.append("Link: ").append(obj.optString("html_url")).append("\n")
        try {
            val commits = JSONArray(api("/commits?per_page=3"))
            out.append("\nÚltimos commits:\n")
            for (i in 0 until commits.length()) {
                val commit = commits.getJSONObject(i)
                val title = commit.optJSONObject("commit")?.optString("message")
                    ?.lineSequence()?.firstOrNull()?.take(90) ?: ""
                out.append("• ").append(commit.optString("sha").take(7))
                    .append(" ").append(title).append("\n")
            }
        } catch (_: Exception) { out.append("Commits não disponíveis.\n") }
        out.toString().take(2200)
    }
}
