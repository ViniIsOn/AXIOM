package com.viniison.axiom

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.provider.OpenableColumns
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.json.JSONObject

/** O modelo Qwen é apenas textual. Imagens/PDFs passam por OCR e rótulos locais,
 * não são enviados como pixels à LLM: descrição gerada é aproximada. */
class AxiomAttachmentReader(private val context: Context) {
    data class Attachment(val name: String, val summary: String, val kind: String)

    suspend fun read(uri: Uri): Attachment = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            ?: uri.lastPathSegment ?: "Arquivo"
        val mime = resolver.getType(uri) ?: ""
        val extension = name.substringAfterLast('.', "").lowercase()
        when {
            mime.startsWith("image/") || extension in listOf("jpg", "jpeg", "png", "webp", "bmp") -> {
                val image = InputImage.fromFilePath(context, uri)
                Attachment(name, imageSummary(image), "imagem")
            }
            mime == "application/pdf" || extension == "pdf" -> {
                // Renderiza a primeira página como imagem, depois executa OCR local.
                val pageText = try {
                    resolver.openFileDescriptor(uri, "r")?.use { pfd ->
                        PdfRenderer(pfd).use { renderer ->
                            if (renderer.pageCount < 1) "Documento PDF vazio."
                            else renderer.openPage(0).use { page ->
                                val width = 1024
                                val height = (page.height.toFloat() * width / page.width)
                                    .toInt().coerceIn(500, 2000)
                                val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                                try {
                                    bmp.eraseColor(android.graphics.Color.WHITE)
                                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                    readText(InputImage.fromBitmap(bmp, 0))
                                } finally { bmp.recycle() }
                            }
                        }
                    } ?: "PDF inacessível."
                } catch (e: Exception) { "Não foi possível ler o PDF: ${e.message}" }
                Attachment(name, "Texto da primeira página do PDF (OCR aproximado):\n${pageText.take(4000)}", "pdf")
            }
            extension in listOf("txt", "md", "csv", "json", "xml", "log", "kt", "java", "py", "js", "html", "css") ||
                mime.startsWith("text/") -> {
                val limited = ByteArrayOutputStream()
                resolver.openInputStream(uri)?.use { stream ->
                    val bytes = ByteArray(4096)
                    while (limited.size() < 16_384) {
                        val n = stream.read(bytes, 0, minOf(bytes.size, 16_384 - limited.size()))
                        if (n <= 0) break
                        limited.write(bytes, 0, n)
                    }
                } ?: error("Arquivo inacessível")
                Attachment(name, "Trecho de arquivo textual:\n" + limited.toString("UTF-8").take(12_000), "texto")
            }
            else -> Attachment(name, "Tipo de arquivo não interpretável pelo modelo textual. O arquivo foi selecionado, mas seu conteúdo não foi analisado.", "não suportado")
        }
    }

    private fun readText(input: InputImage): String {
        val client = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        return try { Tasks.await(client.process(input)).text.trim().ifEmpty { "(nenhum texto reconhecido)" } }
        finally { client.close() }
    }

    private fun imageSummary(image: InputImage): String {
        val recognizedText = try { readText(image) } catch (_: Exception) { "" }
        val labeler = ImageLabeling.getClient(ImageLabelerOptions.DEFAULT_OPTIONS)
        val labels = try {
            Tasks.await(labeler.process(image))
                .filter { it.confidence > 0.42f }
                .take(8).joinToString(", ") { "${it.text} (${(it.confidence * 100).toInt()}%)" }
        } catch (_: Exception) { "" } finally { labeler.close() }
        return buildString {
            append("Leitura aproximada da imagem feita por OCR e classificador local, NÃO visão completa:\n")
            append("Rótulos: "); append(labels.ifEmpty { "não identificados" }); append("\n")
            append("Texto visível: "); append(recognizedText.take(3000).ifEmpty { "não reconhecido" })
        }
    }
}

/** Plugins são opt-in e só podem obter contexto ao serem explicitamente ligados. */
interface AxiomPlugin {
    val id: String
    val title: String
    val description: String
}

class AxiomWikiPlugin : AxiomPlugin {
    override val id = "wiki"
    override val title = "Pesquisa Wikipédia"
    override val description = "Busca artigos atuais pela internet. Precisa de conexão; mostra fontes."

    suspend fun query(text: String): String = withContext(Dispatchers.IO) {
        val words = text.replace(Regex("[^\\p{L}0-9 ]"), " ").split(Regex("\\s+"))
            .filter { it.length >= 3 && it.lowercase() !in listOf(
                "como", "onde", "aonde", "para", "quais", "qual", "voce", "você", "quero",
                "sobre", "muito", "posso", "pode", "adoto", "adotar", "tipo", "site", "sites"
            ) }.take(7)
        val search = words.joinToString(" ").ifBlank { text.take(90) }
        val endpoint = "https://pt.wikipedia.org/w/api.php?action=query&generator=search" +
            "&gsrsearch=" + URLEncoder.encode(search, "UTF-8") +
            "&gsrlimit=3&prop=extracts&exintro=1&explaintext=1&exchars=700&format=json"
        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            connectTimeout = 9_000; readTimeout = 12_000
            setRequestProperty("User-Agent", "AXIOM-Android/0.3 (local research assistant)")
        }
        try {
            if (conn.responseCode != 200) error("Pesquisa HTTP ${conn.responseCode}")
            val json = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val pages = JSONObject(json).optJSONObject("query")?.optJSONObject("pages")
                ?: return@withContext "Nenhum resultado encontrado na Wikipédia em português."
            val result = StringBuilder("Resultados atuais da Wikipédia em português (verifique as fontes):\n")
            for (key in pages.keys()) {
                val page = pages.getJSONObject(key)
                val title = page.optString("title").take(120)
                val extract = page.optString("extract").take(650)
                val url = "https://pt.wikipedia.org/wiki/" + URLEncoder.encode(title.replace(' ', '_'), "UTF-8")
                result.append("\n- ").append(title).append(": ")
                    .append(extract.ifBlank { "(sem resumo disponível)" })
                    .append("\nFonte: ").append(url).append("\n")
            }
            result.toString().take(3500)
        } finally { conn.disconnect() }
    }
}

class AxiomWebPlugin : AxiomPlugin {
    override val id = "browser"
    override val title = "Busca no navegador"
    override val description = "Abre pesquisa completa na web; não lê páginas automaticamente."
}

class AxiomCalculatorPlugin : AxiomPlugin {
    override val id = "calc"
    override val title = "Calculadora"
    override val description = "Comando /calc 12*(3+2) executado localmente."
    /** Parser aritmético básico sem eval, scripts, rede ou chamadas externas. */
    fun evaluate(expression: String): String {
        val s = expression.replace(" ", "").replace(',', '.')
        require(s.length in 1..140) { "Expressão inválida ou extensa." }
        require(s.all { it.isDigit() || it in ".+-*/()" }) { "Use apenas números, +, -, *, / e parênteses." }
        class Parser {
            var index = 0
            fun parseExpr(): Double {
                var v = parseTerm()
                while (index < s.length && s[index] in "+-") {
                    val op = s[index++]; val right = parseTerm()
                    v = if (op == '+') v + right else v - right
                }
                return v
            }
            fun parseTerm(): Double {
                var v = parseFactor()
                while (index < s.length && s[index] in "*/") {
                    val op = s[index++]; val right = parseFactor()
                    v = if (op == '*') v * right else {
                        require(right != 0.0) { "Divisão por zero." }
                        v / right
                    }
                }
                return v
            }
            fun parseFactor(): Double {
                require(index < s.length) { "Faltou um número." }
                if (s[index] == '+' || s[index] == '-') {
                    val minus = s[index++] == '-'
                    val v = parseFactor(); return if (minus) -v else v
                }
                if (s[index] == '(') {
                    index++
                    val v = parseExpr()
                    require(index < s.length && s[index] == ')') { "Faltou fechar parênteses." }
                    index++; return v
                }
                val start = index
                while (index < s.length && (s[index].isDigit() || s[index] == '.')) index++
                require(index > start) { "Número inválido." }
                return s.substring(start,index).toDouble()
            }
        }
        val parser = Parser()
        val value = parser.parseExpr()
        require(parser.index == s.length && value.isFinite()) { "Expressão inválida." }
        return if (value == value.toLong().toDouble()) value.toLong().toString()
        else "%.8f".format(java.util.Locale.US,value).trimEnd('0').trimEnd('.')
    }
}
