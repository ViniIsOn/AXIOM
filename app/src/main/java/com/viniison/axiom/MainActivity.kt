package com.viniison.axiom

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.codeshipping.llamakotlin.LlamaModel
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** AXIOM: chat offline real via llama.cpp; o modelo fica no armazenamento privado. */
class MainActivity : ComponentActivity() {
    private data class Message(val role: String, val content: String)

    private lateinit var status: TextView
    private lateinit var panel: LinearLayout
    private lateinit var progress: ProgressBar
    private lateinit var messagesView: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var send: Button
    private lateinit var stop: Button
    private lateinit var download: Button
    private lateinit var import: Button

    private var model: LlamaModel? = null
    private var generation: Job? = null
    private var busy = false
    private val history = mutableListOf<Message>()
    private val prefs by lazy { getSharedPreferences("axiom_local", MODE_PRIVATE) }

    private val defaultModelUrl =
        "https://huggingface.co/bartowski/Qwen2.5-0.5B-Instruct-GGUF/resolve/eadd1b868ae382c989d828189bdb8e5d34dc675c/Qwen2.5-0.5B-Instruct-Q4_K_M.gguf"
    private val defaultSha256 = "6eb923e7d26e9cea28811e1a8e852009b21242fb157b26149d3b188f3a8c8653"

    private val pickModel = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importModel(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.tvStatus)
        panel = findViewById(R.id.modelPanel)
        progress = findViewById(R.id.progressModel)
        messagesView = findViewById(R.id.chatContainer)
        scroll = findViewById(R.id.chatScroll)
        input = findViewById(R.id.inputPrompt)
        send = findViewById(R.id.btnSend)
        stop = findViewById(R.id.btnStop)
        download = findViewById(R.id.btnDownload)
        import = findViewById(R.id.btnImport)

        findViewById<Button>(R.id.btnSettings).setOnClickListener {
            panel.visibility = if (panel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        download.setOnClickListener { downloadDefault() }
        import.setOnClickListener { if (!busy) pickModel.launch(arrayOf("*/*")) }
        send.setOnClickListener { sendMessage() }
        stop.setOnClickListener {
            model?.cancelGeneration()
            generation?.cancel()
        }
        findViewById<TextView>(R.id.btnClear).setOnClickListener {
            if (!busy) {
                history.clear()
                prefs.edit().remove("conversation").apply()
                messagesView.removeAllViews()
                welcome()
                setStatus("Conversa limpa.")
            }
        }

        restoreHistory()
        if (history.isEmpty()) welcome()
        val savedPath = prefs.getString("model_path", null)
        if (savedPath != null && File(savedPath).exists()) {
            loadModel(File(savedPath))
        } else {
            setStatus("Modelo não instalado • baixe gratuitamente ou importe um GGUF.")
        }
    }

    private fun welcome() {
        bubble("Olá! Eu sou a AXIOM. 🤖\n\nBaixe meu cérebro local para começarmos. Depois disso, posso responder mesmo sem internet. Minhas respostas podem conter erros.", "assistant")
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun bubble(content: String, role: String): TextView {
        val text = TextView(this).apply {
            this.text = content
            textSize = 15f
            setTextColor(Color.WHITE)
            setPadding(dp(14), dp(11), dp(14), dp(11))
            setTextIsSelectable(true)
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(Color.parseColor(if (role == "user") "#174E64" else "#172743"))
            }
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = if (role == "user") Gravity.END else Gravity.START
            setPadding(0, dp(5), 0, dp(5))
        }
        row.addView(text, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { width = dp(320).coerceAtMost(resources.displayMetrics.widthPixels - dp(64)) })
        messagesView.addView(row)
        toBottom()
        return text
    }

    private fun toBottom() {
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun setStatus(value: String) { status.text = value }

    private fun setBusy(value: Boolean) {
        busy = value
        send.isEnabled = !value && model != null
        download.isEnabled = !value
        import.isEnabled = !value
    }

    private fun modelFile() = File(filesDir, "qwen2.5-0.5b-q4km.gguf")

    private fun downloadDefault() {
        if (busy) return
        val existing = modelFile()
        if (existing.exists() && existing.length() > 300_000_000L) {
            loadModel(existing)
            return
        }
        setBusy(true)
        progress.visibility = View.VISIBLE
        progress.progress = 0
        lifecycleScope.launch {
            val part = File(filesDir, "axiom-model.part")
            try {
                setStatus("Baixando cérebro da AXIOM… conecte-se ao Wi-Fi.")
                val finalFile = withContext(Dispatchers.IO) {
                    val conn = (URL(defaultModelUrl).openConnection() as HttpURLConnection).apply {
                        instanceFollowRedirects = true
                        connectTimeout = 30_000
                        readTimeout = 60_000
                        setRequestProperty("User-Agent", "AXIOM-Android/0.1")
                        connect()
                    }
                    try {
                        if (conn.responseCode != 200) error("Servidor HTTP ${conn.responseCode}")
                        val length = conn.contentLengthLong.takeIf { it > 0 } ?: 398_000_000L
                        val digest = MessageDigest.getInstance("SHA-256")
                        conn.inputStream.use { source ->
                            FileOutputStream(part).use { target ->
                                val buffer = ByteArray(64 * 1024)
                                var received = 0L
                                var lastTick = 0L
                                while (true) {
                                    val n = source.read(buffer)
                                    if (n < 0) break
                                    target.write(buffer, 0, n)
                                    digest.update(buffer, 0, n)
                                    received += n
                                    if (received - lastTick >= 2_000_000L) {
                                        lastTick = received
                                        val percentage = ((received * 100L / length).toInt()).coerceIn(0, 100)
                                        withContext(Dispatchers.Main) {
                                            progress.progress = percentage
                                            setStatus("Baixando IA… ${received / 1_000_000} MB (${percentage}%)")
                                        }
                                    }
                                }
                            }
                        }
                        val hash = digest.digest().joinToString("") { "%02x".format(it) }
                        if (hash != defaultSha256) error("Verificação SHA-256 não corresponde. Faça novo download.")
                        if (!part.renameTo(existing)) error("Não foi possível salvar o modelo.")
                        existing
                    } finally {
                        conn.disconnect()
                    }
                }
                progress.progress = 100
                progress.visibility = View.GONE
                setBusy(false)
                loadModel(finalFile)
            } catch (e: Exception) {
                part.delete()
                progress.visibility = View.GONE
                setBusy(false)
                setStatus("Erro no download: ${e.message}")
            }
        }
    }

    private fun importModel(uri: Uri) {
        if (busy) return
        val filename = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            } ?: ""
        if (!filename.endsWith(".gguf", ignoreCase = true)) {
            Toast.makeText(this, "Escolha um arquivo .gguf", Toast.LENGTH_LONG).show()
            return
        }
        setBusy(true)
        progress.visibility = View.VISIBLE
        progress.progress = 0
        lifecycleScope.launch {
            val destination = File(filesDir, "imported-model.gguf")
            val temp = File(filesDir, "imported-model.part")
            try {
                setStatus("Copiando modelo…")
                withContext(Dispatchers.IO) {
                    val inStream = contentResolver.openInputStream(uri) ?: error("Falha ao ler GGUF")
                    inStream.use { src ->
                        FileOutputStream(temp).use { dst -> src.copyTo(dst, bufferSize = 64 * 1024) }
                    }
                    if (temp.length() < 1024 * 1024) error("Arquivo GGUF pequeno demais.")
                    if (destination.exists() && !destination.delete()) error("Não foi possível substituir GGUF anterior")
                    if (!temp.renameTo(destination)) error("Falha ao concluir importação")
                }
                setBusy(false)
                progress.visibility = View.GONE
                loadModel(destination)
            } catch (e: Exception) {
                temp.delete()
                progress.visibility = View.GONE
                setBusy(false)
                setStatus("Erro ao importar: ${e.message}")
            }
        }
    }

    private fun loadModel(file: File) {
        if (busy) return
        setBusy(true)
        setStatus("Carregando IA na memória… pode levar alguns segundos.")
        lifecycleScope.launch {
            try {
                val previous = model
                model = null
                withContext(Dispatchers.Default) { previous?.close() }
                val loaded = LlamaModel.load(file.absolutePath) {
                    contextSize = 2048
                    batchSize = 256
                    threads = 4
                    temperature = 0.55f
                    topP = 0.9f
                    maxTokens = 220
                    gpuLayers = 0
                    useMmap = true
                }
                model = loaded
                prefs.edit().putString("model_path", file.absolutePath).apply()
                panel.visibility = View.GONE
                setStatus("● AXIOM pronta • ${file.name} • processamento local")
            } catch (e: Throwable) {
                setStatus("Falha ao carregar IA: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                setBusy(false)
            }
        }
    }

    private fun sendMessage() {
        val activeModel = model ?: run {
            setStatus("Instale um modelo antes de conversar.")
            return
        }
        if (busy) return
        val message = input.text.toString().trim()
        if (message.isEmpty()) return
        input.text.clear()
        history.add(Message("user", message))
        bubble(message, "user")
        val out = bubble("Pensando…", "assistant")
        setBusy(true)
        stop.visibility = View.VISIBLE
        send.visibility = View.GONE
        setStatus("AXIOM está pensando no celular…")
        val generated = StringBuilder()
        generation = lifecycleScope.launch {
            try {
                val turns = history.takeLast(6)
                val json = JSONArray()
                json.put(JSONObject().put("role", "system").put(
                    "content", "Você é AXIOM AI, uma assistente local em português brasileiro. " +
                        "Seja gentil, útil, objetiva e racional. Nunca finja acessar internet, " +
                        "conhecer o presente ou executar ferramentas. Admita incerteza. " +
                        "Não peça dados sensíveis."
                ))
                for (item in turns) {
                    json.put(JSONObject().put("role", item.role).put("content", item.content.takeLast(1200)))
                }
                val prompt = withContext(Dispatchers.Default) {
                    activeModel.applyChatTemplate(json.toString(), true)
                }
                if (prompt.isBlank()) error("Não foi possível aplicar o template de conversa.")
                activeModel.generateStream(prompt).collect { chunk ->
                    generated.append(chunk)
                    out.text = generated.toString()
                    toBottom()
                }
                setStatus("● AXIOM pronta • resposta local")
            } catch (_: CancellationException) {
                setStatus("Geração interrompida.")
            } catch (e: Exception) {
                setStatus("Falha ao responder: ${e.message}")
                if (generated.isEmpty()) out.text = "Não consegui responder: ${e.message}"
            } finally {
                if (generated.isNotEmpty()) {
                    history.add(Message("assistant", generated.toString().trim()))
                    saveHistory()
                } else {
                    saveHistory()
                }
                stop.visibility = View.GONE
                send.visibility = View.VISIBLE
                setBusy(false)
            }
        }
    }

    private fun saveHistory() {
        val json = JSONArray()
        history.takeLast(36).forEach {
            json.put(JSONObject().put("role", it.role).put("content", it.content))
        }
        prefs.edit().putString("conversation", json.toString()).apply()
    }

    private fun restoreHistory() {
        try {
            val saved = JSONArray(prefs.getString("conversation", "[]") ?: "[]")
            for (i in 0 until saved.length()) {
                val item = saved.getJSONObject(i)
                val role = item.optString("role")
                val text = item.optString("content")
                if (role in listOf("user", "assistant") && text.isNotBlank()) {
                    history.add(Message(role, text))
                    bubble(text, role)
                }
            }
        } catch (_: Exception) {
            history.clear()
        }
    }

    override fun onDestroy() {
        model?.cancelGeneration()
        generation?.cancel()
        // O processo libera o runtime ao encerrar a Activity.
        super.onDestroy()
    }
}
