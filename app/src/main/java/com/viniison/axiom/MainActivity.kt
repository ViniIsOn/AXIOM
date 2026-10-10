package com.viniison.axiom

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
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
    private val store by lazy { ChatStore(this) }
    private var currentChatId = -1L
    private val prefs by lazy { getSharedPreferences("axiom_local", MODE_PRIVATE) }

    private val defaultModelUrl =
        "https://huggingface.co/bartowski/Qwen2.5-0.5B-Instruct-GGUF/resolve/eadd1b868ae382c989d828189bdb8e5d34dc675c/Qwen2.5-0.5B-Instruct-Q4_K_M.gguf"
    private val defaultSha256 = "6eb923e7d26e9cea28811e1a8e852009b21242fb157b26149d3b188f3a8c8653"

    private val pickModel = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importModel(uri)
    }

    private val exportBackup = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) lifecycleScope.launch {
            try {
                val json = withContext(Dispatchers.IO) { store.exportJson() }
                withContext(Dispatchers.IO) {
                    contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                        ?: error("Arquivo inacessível")
                }
                setStatus("Backup exportado com sucesso.")
            } catch (e: Exception) {
                setStatus("Falha ao exportar backup: ${e.message}")
            }
        }
    }

    private val importBackup = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) lifecycleScope.launch {
            try {
                val data = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use { stream ->
                        val buffer = java.io.ByteArrayOutputStream()
                        val bytes = ByteArray(16_384)
                        while (true) {
                            val n = stream.read(bytes)
                            if (n < 0) break
                            if (buffer.size() + n > 4_000_000) error("Backup acima de 4 MB")
                            buffer.write(bytes, 0, n)
                        }
                        buffer.toString("UTF-8")
                    } ?: error("Não foi possível abrir o backup")
                }
                val count = withContext(Dispatchers.IO) { store.importJson(data) }
                setStatus("Backup importado: ${count} conversa(s).")
                store.threads().firstOrNull()?.id?.let { openChat(it) }
            } catch (e: Exception) {
                setStatus("Falha ao importar backup: ${e.message}")
            }
        }
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

        findViewById<TextView>(R.id.btnSettings).setOnClickListener {
            panel.visibility = if (panel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        findViewById<TextView>(R.id.btnHistory).setOnClickListener { if (!busy) showHistory() }
        findViewById<TextView>(R.id.btnNew).setOnClickListener { if (!busy) newChat() }
        download.setOnClickListener { downloadDefault() }
        import.setOnClickListener { if (!busy) pickModel.launch(arrayOf("*/*")) }
        send.setOnClickListener { sendMessage() }
        stop.setOnClickListener {
            model?.cancelGeneration()
            generation?.cancel()
        }

        store.migrateLegacy(prefs)
        val previous = prefs.getLong("current_chat_id", -1L)
        val threadId = if (store.hasThread(previous)) previous
            else store.threads().firstOrNull()?.id ?: store.newThread()
        openChat(threadId)
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (currentChatId > 0L) {
                    prefs.edit().putString("draft_$currentChatId", s?.toString() ?: "").apply()
                }
            }
        })

        val savedPath = prefs.getString("model_path", null)
        if (savedPath != null && File(savedPath).exists()) {
            panel.visibility = View.GONE
            loadModel(File(savedPath))
        } else {
            panel.visibility = View.VISIBLE
            setStatus("Configure o modelo local para iniciar.")
        }
    }

    private fun welcome() {
        val greeting = TextView(this).apply {
            text = "✦\n\nO que vamos criar hoje?\n\nSou a AXIOM, sua assistente de IA local.\nAs conversas ficam salvas neste aparelho."
            setTextColor(Color.parseColor("#E9F2F1"))
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            textSize = 17f
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(75), dp(14), dp(16))
        }
        messagesView.addView(greeting)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun bubble(content: String, role: String): TextView {
        val mine = role == "user"
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = if (mine) Gravity.END else Gravity.START
            setPadding(dp(7), dp(9), dp(7), dp(9))
        }

        if (!mine) {
            val identity = TextView(this).apply {
                text = "✦  AXIOM"
                textSize = 12f
                setTextColor(Color.parseColor("#55D5C5"))
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setPadding(0, 0, 0, dp(7))
            }
            row.addView(identity)
        }

        val text = TextView(this).apply {
            this.text = content
            textSize = 15f
            setTextColor(Color.parseColor("#F0F2F6"))
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            setTextIsSelectable(true)
            setLineSpacing(dp(3).toFloat(), 1f)
            if (mine) {
                setPadding(dp(16), dp(12), dp(16), dp(12))
                background = GradientDrawable().apply {
                    cornerRadius = dp(22).toFloat()
                    setColor(Color.parseColor("#29313B"))
                }
                maxWidth = resources.displayMetrics.widthPixels - dp(85)
            } else {
                setPadding(0, 0, dp(5), dp(4))
            }
        }
        row.addView(text, LinearLayout.LayoutParams(
            if (mine) LinearLayout.LayoutParams.WRAP_CONTENT else LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        text.setOnLongClickListener {
            val clip = ClipData.newPlainText("AXIOM", text.text)
            (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
            Toast.makeText(this, "Texto copiado", Toast.LENGTH_SHORT).show()
            true
        }
        messagesView.addView(row)
        toBottom()
        return text
    }

    private fun openChat(id: Long) {
        if (busy || !store.hasThread(id)) return
        currentChatId = id
        prefs.edit().putLong("current_chat_id", id).apply()
        history.clear()
        messagesView.removeAllViews()
        store.messages(id).forEach {
            history.add(Message(it.role, it.content))
            bubble(it.content, it.role)
        }
        if (history.isEmpty()) welcome()
        input.setText(prefs.getString("draft_$id", "") ?: "")
        toBottom()
    }

    private fun newChat() {
        if (busy) return
        openChat(store.newThread())
        setStatus(if (model != null) "● Modelo local pronto" else "Configure o modelo local")
    }

    private fun showHistory() {
        val items = store.threads()
        AlertDialog.Builder(this)
            .setTitle("Suas conversas")
            .setItems(items.map { it.title }.toTypedArray()) { _, index ->
                openChat(items[index].id)
            }
            .setPositiveButton("Nova conversa") { _, _ -> newChat() }
            .setNeutralButton("Opções") { _, _ -> showChatOptions() }
            .setNegativeButton("Fechar", null)
            .show()
    }

    private fun showChatOptions() {
        val options = arrayOf(
            "Limpar conversa atual", "Excluir conversa atual",
            "Exportar backup (.json)", "Importar backup (.json)"
        )
        AlertDialog.Builder(this).setTitle("Conversas e dados")
            .setItems(options) { _, choice ->
                when (choice) {
                    0 -> confirmClear()
                    1 -> confirmDelete()
                    2 -> exportBackup.launch("axiom-backup.json")
                    3 -> importBackup.launch(arrayOf("application/json", "text/plain", "*/*"))
                }
            }.setNegativeButton("Cancelar", null).show()
    }

    private fun confirmClear() {
        AlertDialog.Builder(this).setTitle("Limpar conversa?")
            .setMessage("As mensagens desta conversa serão apagadas do aparelho. Outras conversas serão mantidas.")
            .setPositiveButton("Limpar") { _, _ ->
                store.clearThread(currentChatId)
                prefs.edit().remove("draft_$currentChatId").apply()
                openChat(currentChatId)
                setStatus("Conversa limpa.")
            }
            .setNegativeButton("Cancelar", null).show()
    }

    private fun confirmDelete() {
        AlertDialog.Builder(this).setTitle("Excluir esta conversa?")
            .setMessage("A exclusão é permanente, a menos que você tenha exportado um backup.")
            .setPositiveButton("Excluir") { _, _ ->
                val deleted = currentChatId
                store.deleteThread(deleted)
                prefs.edit().remove("draft_$deleted").apply()
                openChat(store.threads().firstOrNull()?.id ?: store.newThread())
                setStatus("Conversa excluída.")
            }
            .setNegativeButton("Cancelar", null).show()
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
        val thread = currentChatId
        val isFirst = history.none { it.role == "user" }
        if (isFirst) store.setTitle(thread, message)
        store.addMessage(thread, "user", message)
        history.add(Message("user", message))
        if (isFirst) messagesView.removeAllViews()
        bubble(message, "user")
        val out = bubble("Pensando…", "assistant")
        setBusy(true)
        stop.visibility = View.VISIBLE
        send.visibility = View.GONE
        setStatus("AXIOM está pensando…")
        val generated = StringBuilder()
        var answerId = -1L
        var lastSave = SystemClock.elapsedRealtime()
        generation = lifecycleScope.launch {
            try {
                val turns = history.takeLast(6)
                // Formato ChatML nativo do modelo Qwen2.5 Instruct.
                val prompt = buildString {
                    append("<|im_start|>system\n")
                    append("Você é AXIOM AI, assistente local em português brasileiro. ")
                    append("Seja gentil, objetiva e racional. Não invente acesso à internet nem ferramentas. ")
                    append("Admita incerteza e nunca peça dados sensíveis.")
                    append("<|im_end|>\n")
                    for (item in turns) {
                        append("<|im_start|>")
                        append(item.role)
                        append("\n")
                        append(item.content.takeLast(1000))
                        append("<|im_end|>\n")
                    }
                    append("<|im_start|>assistant\n")
                }
                activeModel.generateStream(prompt).collect { chunk ->
                    generated.append(chunk)
                    out.text = generated.toString()
                    val now = SystemClock.elapsedRealtime()
                    if (answerId < 0L) {
                        answerId = store.addMessage(thread, "assistant", generated.toString())
                        lastSave = now
                    } else if (now - lastSave > 450L) {
                        store.updateMessage(answerId, generated.toString())
                        lastSave = now
                    }
                    toBottom()
                }
                setStatus("● AXIOM pronta • conversa salva")
            } catch (_: CancellationException) {
                setStatus("Geração interrompida • texto salvo")
            } catch (e: Exception) {
                setStatus("Falha ao responder: ${e.message}")
                if (generated.isEmpty()) out.text = "Não consegui responder: ${e.message}"
            } finally {
                if (generated.isNotEmpty()) {
                    if (answerId < 0L) answerId = store.addMessage(thread, "assistant", generated.toString())
                    else store.updateMessage(answerId, generated.toString())
                    history.add(Message("assistant", generated.toString().trim()))
                }
                stop.visibility = View.GONE
                send.visibility = View.VISIBLE
                setBusy(false)
            }
        }
    }

    override fun onDestroy() {
        model?.cancelGeneration()
        generation?.cancel()
        // O processo libera o runtime ao encerrar a Activity.
        super.onDestroy()
    }
}
