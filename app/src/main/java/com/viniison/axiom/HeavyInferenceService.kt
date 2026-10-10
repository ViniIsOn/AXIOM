package com.viniison.axiom

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.codeshipping.llamakotlin.LlamaModel
import java.io.File

/**
 * O modelo grande roda em OUTRO processo Android.
 * Se a inferência nativa falhar, a Activity sobrevive e pode oferecer o modelo leve.
 * Não é garantia contra falta global de memória do sistema.
 */
class HeavyInferenceService : Service() {
    companion object {
        const val LOAD = 1
        const val GENERATE = 2
        const val CANCEL = 3
        const val READY = 4
        const val TOKEN = 5
        const val DONE = 6
        const val ERROR = 7
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var loaded: LlamaModel? = null
    private var loadJob: Job? = null
    private var generationJob: Job? = null
    private var client: Messenger? = null

    private val incoming = Messenger(Handler(Looper.getMainLooper()) { msg ->
        client = msg.replyTo ?: client
        when (msg.what) {
            LOAD -> beginLoad(msg.data.getString("path") ?: "")
            GENERATE -> beginGenerate(msg.data.getString("prompt") ?: "")
            CANCEL -> {
                loaded?.cancelGeneration()
                generationJob?.cancel()
            }
        }
        true
    })

    override fun onBind(intent: Intent?): IBinder = incoming.binder

    private fun reply(what: Int, text: String? = null) {
        val msg = Message.obtain(null, what)
        if (text != null) msg.data = Bundle().apply { putString("text", text) }
        try { client?.send(msg) } catch (_: RemoteException) {}
    }

    private fun beginLoad(path: String) {
        if (loadJob?.isActive == true || generationJob?.isActive == true) {
            reply(ERROR, "O processo de IA ainda está ocupado.")
            return
        }
        loadJob = scope.launch {
            try {
                require(File(path).isFile && path.startsWith(filesDir.absolutePath)) {
                    "Modelo não encontrado no armazenamento do app."
                }
                val old = loaded
                loaded = null
                withContext(Dispatchers.IO) { old?.close() }
                val engine = LlamaModel.load(path) {
                    contextSize = 512
                    batchSize = 16
                    threads = 2
                    threadsBatch = 2
                    maxTokens = 112
                    temperature = 0.45f
                    topP = 0.85f
                    gpuLayers = 0
                    useMmap = true
                    useMlock = false
                }
                loaded = engine
                reply(READY)
            } catch (e: Exception) {
                reply(ERROR, "Falha ao carregar modelo: " + (e.message ?: "desconhecida"))
            }
        }
    }

    private fun beginGenerate(prompt: String) {
        val engine = loaded
        if (engine == null) {
            reply(ERROR, "Modelo não carregado no processo isolado.")
            return
        }
        if (generationJob?.isActive == true) {
            reply(ERROR, "A IA já está respondendo.")
            return
        }
        generationJob = scope.launch {
            try {
                // Limitar texto ajuda a controlar tamanho do contexto no celular.
                val trimmed = prompt.takeLast(3000)
                engine.generateStream(trimmed).collect { token ->
                    reply(TOKEN, token)
                }
                reply(DONE)
            } catch (_: CancellationException) {
                reply(DONE)
            } catch (e: Exception) {
                reply(ERROR, "Erro durante geração: " + (e.message ?: "desconhecido"))
            }
        }
    }

    override fun onDestroy() {
        loaded?.cancelGeneration()
        generationJob?.cancel()
        loadJob?.cancel()
        val old = loaded
        loaded = null
        scope.launch(Dispatchers.IO) { runCatching { old?.close() } }
        scope.cancel()
        super.onDestroy()
    }
}
