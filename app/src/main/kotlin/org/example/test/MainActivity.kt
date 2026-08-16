package org.example.test

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : AppCompatActivity() {

    private val session = LlamaSession()
    private lateinit var modelManager: ModelManager

    private lateinit var modelStatusText: TextView
    private lateinit var importButton: Button
    private lateinit var useCpuButton: Button
    private lateinit var hfUrlInput: EditText
    private lateinit var downloadButton: Button
    private lateinit var downloadProgress: ProgressBar
    private lateinit var downloadStatusText: TextView
    private lateinit var chatScroll: ScrollView
    private lateinit var chatLog: TextView
    private lateinit var promptInput: EditText
    private lateinit var sendButton: Button

    /** Toggled by "Use CPU only" — default is GPU-first via Vulkan. */
    private var gpuLayers = 999

    private var loadedModelFile: File? = null
    private var isGenerating = false

    private val openDocumentLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            val name = uri.lastPathSegment?.substringAfterLast('/') ?: "model.gguf"
            lifecycleScope.launch {
                setModelStatus("Importing $name…")
                try {
                    val file = modelManager.importFromUri(uri, name)
                    loadModelFile(file)
                } catch (e: Exception) {
                    setModelStatus("Import failed: ${e.message}")
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        modelManager = ModelManager(applicationContext)

        modelStatusText = findViewById(R.id.modelStatusText)
        importButton = findViewById(R.id.importButton)
        useCpuButton = findViewById(R.id.useCpuButton)
        hfUrlInput = findViewById(R.id.hfUrlInput)
        downloadButton = findViewById(R.id.downloadButton)
        downloadProgress = findViewById(R.id.downloadProgress)
        downloadStatusText = findViewById(R.id.downloadStatusText)
        chatScroll = findViewById(R.id.chatScroll)
        chatLog = findViewById(R.id.chatLog)
        promptInput = findViewById(R.id.promptInput)
        sendButton = findViewById(R.id.sendButton)

        lifecycleScope.launch { session.init() }

        importButton.setOnClickListener {
            // "*/*" because content providers rarely register a proper
            // .gguf mime type; we filter by extension in the callback.
            openDocumentLauncher.launch(arrayOf("*/*"))
        }

        useCpuButton.setOnClickListener {
            gpuLayers = if (gpuLayers == 0) 999 else 0
            useCpuButton.text = if (gpuLayers == 0) getString(R.string.use_gpu) else getString(R.string.cpu_only)
            loadedModelFile?.let { file ->
                Toast.makeText(this, "Reloading model on ${if (gpuLayers == 0) "CPU" else "GPU"}…", Toast.LENGTH_SHORT).show()
                lifecycleScope.launch { loadModelFile(file) }
            }
        }

        downloadButton.setOnClickListener { startDownload() }

        sendButton.setOnClickListener { onSendClicked() }
    }

    private fun startDownload() {
        val url = hfUrlInput.text.toString().trim()
        if (url.isEmpty()) {
            Toast.makeText(this, "Paste a Hugging Face .gguf URL first", Toast.LENGTH_SHORT).show()
            return
        }
        val fileName = url.substringAfterLast('/').substringBefore('?').ifBlank { "model.gguf" }

        downloadProgress.visibility = android.view.View.VISIBLE
        downloadStatusText.visibility = android.view.View.VISIBLE
        downloadButton.isEnabled = false

        lifecycleScope.launch {
            modelManager.downloadFromUrl(url, fileName).collect { progress ->
                when (progress) {
                    is DownloadProgress.InProgress -> {
                        if (progress.bytesTotal > 0) {
                            val pct = (100 * progress.bytesDone / progress.bytesTotal).toInt()
                            downloadProgress.progress = pct
                            downloadStatusText.text =
                                "${mb(progress.bytesDone)} / ${mb(progress.bytesTotal)} MB ($pct%)"
                        } else {
                            downloadStatusText.text = "${mb(progress.bytesDone)} MB downloaded"
                        }
                    }
                    is DownloadProgress.Done -> {
                        downloadButton.isEnabled = true
                        downloadStatusText.text = "Download complete"
                        loadModelFile(progress.file)
                    }
                    is DownloadProgress.Failed -> {
                        downloadButton.isEnabled = true
                        downloadStatusText.text = "Failed: ${progress.message}"
                    }
                }
            }
        }
    }

    private suspend fun loadModelFile(file: File) {
        setModelStatus("Loading ${file.name}…")
        try {
            val info = session.loadModel(file.absolutePath, gpuLayers)
            loadedModelFile = file
            val backend = if (info.gpuLayers > 0) "GPU (Vulkan) + CPU" else "CPU only"
            setModelStatus("Loaded: ${file.name}  •  ctx=${info.contextSize}  •  $backend")
        } catch (e: Exception) {
            setModelStatus("Load failed: ${e.message}")
        }
    }

    private fun onSendClicked() {
        if (isGenerating) {
            session.cancelGeneration()
            return
        }
        val prompt = promptInput.text.toString().trim()
        if (prompt.isEmpty()) return
        if (loadedModelFile == null) {
            Toast.makeText(this, "Load a model first", Toast.LENGTH_SHORT).show()
            return
        }

        appendToChat("\n\nYou: $prompt\n\nAssistant: ")
        promptInput.text.clear()
        isGenerating = true
        sendButton.text = "Stop"

        lifecycleScope.launch {
            // Minimal single-turn prompt formatting. For real multi-turn
            // chat, apply the model's chat template (llama.cpp exposes
            // llama_chat_apply_template) instead of raw string concat.
            session.complete(prompt).collect { piece -> appendToChat(piece) }
            isGenerating = false
            sendButton.text = getString(R.string.send)
        }
    }

    private fun appendToChat(text: String) {
        chatLog.append(text)
        chatScroll.post { chatScroll.fullScroll(android.view.View.FOCUS_DOWN) }
    }

    private fun setModelStatus(text: String) {
        modelStatusText.text = text
    }

    private fun mb(bytes: Long): String = "%.1f".format(bytes / 1_000_000.0)

    override fun onDestroy() {
        super.onDestroy()
        lifecycleScope.launch { session.unload() }
    }
}
