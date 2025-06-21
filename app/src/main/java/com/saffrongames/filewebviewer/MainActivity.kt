package com.saffrongames.filewebviewer

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.view.View
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.widget.ImageButton
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.documentfile.provider.DocumentFile
import java.io.*
import androidx.core.content.edit

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView

    private val openFolderLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            val htmlFile = copyFolderToCache(it)
            if (htmlFile != null) {
                webView.loadUrl("file://${htmlFile.absolutePath}")
                // Save picked folder URI to preferences
                getSharedPreferences("prefs", MODE_PRIVATE).edit {
                    putString("last_folder_uri", uri.toString())
                }
            } else {
                Toast.makeText(this, "No single .html file found in folder", Toast.LENGTH_LONG).show()
            }
        }
    }

    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        val openFileButton = findViewById<Button>(R.id.openFileButton)

        val infoButton = findViewById<ImageButton>(R.id.infoButton)
        infoButton.setOnClickListener {
            val downloadsDir = File(getExternalFilesDir(null), "Downloads")
            val path = downloadsDir.absolutePath
            AlertDialog.Builder(this)
                .setTitle("Download Folder Path")
                .setMessage("Files are saved to:\n\n$path")
                .setPositiveButton("OK", null)
                .show()
        }

        // WebView setup
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.allowFileAccess = true
        webView.settings.allowContentAccess = true
        webView.webViewClient = WebViewClient()
        webView.settings.cacheMode = WebSettings.LOAD_NO_CACHE

        webView.addJavascriptInterface(JSBridge(), "AndroidBridge")

        val consoleOutput = findViewById<TextView>(R.id.consoleOutput)

        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                val level = consoleMessage.messageLevel()
                val message = consoleMessage.message()
                val source = consoleMessage.sourceId()
                val line = consoleMessage.lineNumber()

                consoleOutput.append("[$level] $message\n    at $source:$line\n")
                return true
            }

            override fun onShowFileChooser(
                webView: WebView,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams
            ): Boolean {
                fileChooserCallback?.onReceiveValue(null) // cancel any previous

                fileChooserCallback = filePathCallback

                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                }

                filePickerLauncher.launch(intent)
                return true
            }
        }

        val injectionScript = """
    (function() {
        const originalCreateObjectURL = URL.createObjectURL;
        const originalRevokeObjectURL = URL.revokeObjectURL;
        const blobRegistry = new Map();

        URL.createObjectURL = function(blob) {
            const id = 'blob:' + Math.random().toString(36).substr(2, 9);
            blobRegistry.set(id, blob);
            return id;
        };

        URL.revokeObjectURL = function(id) {
            blobRegistry.delete(id);
        };

        document.addEventListener('click', function(e) {
            const a = e.target.closest('a');
            if (!a || !a.hasAttribute('download')) return;

            const href = a.getAttribute('href');
            if (!href || !blobRegistry.has(href)) return;

            e.preventDefault();
            const blob = blobRegistry.get(href);
            const reader = new FileReader();
            reader.onloadend = function () {
                const base64 = reader.result.split(',')[1];
                AndroidBridge.saveFile(base64, a.getAttribute('download') || 'downloaded_file');
            };
            reader.readAsDataURL(blob);
        }, true);
    })();
""".trimIndent()

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                webView.evaluateJavascript(injectionScript, null)
            }
        }

        val consoleScrollView = findViewById<ScrollView>(R.id.consoleScrollView) // set an id if missing
        consoleScrollView.visibility = View.GONE
        val toggleConsoleButton = findViewById<Button>(R.id.toggleConsoleButton)

        toggleConsoleButton.setOnClickListener {
            consoleScrollView.visibility = if (consoleScrollView.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        val exportConsoleButton = findViewById<Button>(R.id.exportConsoleButton)

        exportConsoleButton.setOnClickListener {
            val logs = consoleOutput.text.toString()
            val fileName = "webview_console_${System.currentTimeMillis()}.txt"
            val file = File(getExternalFilesDir(null), fileName)
            file.writeText(logs)
            Toast.makeText(this, "Saved to ${file.absolutePath}", Toast.LENGTH_LONG).show()
        }

        openFileButton.setOnClickListener {
            openFolderLauncher.launch(null)
        }

        val lastUriStr = getSharedPreferences("prefs", MODE_PRIVATE).getString("last_folder_uri", null)
        if (lastUriStr != null) {
            val uri = Uri.parse(lastUriStr)
            val htmlFile = copyFolderToCache(uri)
            if (htmlFile != null) {
                webView.loadUrl("file://${htmlFile.absolutePath}")
            }
        }
    }

    private val filePickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val uri = result.data?.data
            if (uri != null) {
                fileChooserCallback?.onReceiveValue(arrayOf(uri))
            } else {
                fileChooserCallback?.onReceiveValue(null)
            }
        } else {
            fileChooserCallback?.onReceiveValue(null)
        }
        fileChooserCallback = null
    }

    private fun copyFolderToCache(treeUri: Uri): File? {
        val pickedDir = DocumentFile.fromTreeUri(this, treeUri) ?: return null
        val tempDir = File(cacheDir, "imported_site").apply {
            deleteRecursively()
            mkdirs()
        }

        var htmlFile: File? = null

        for (docFile in pickedDir.listFiles()) {
            if (!docFile.isFile) continue

            val name = docFile.name ?: continue
            val inputStream = contentResolver.openInputStream(docFile.uri) ?: continue
            val targetFile = File(tempDir, name)

            FileOutputStream(targetFile).use { out ->
                inputStream.copyTo(out)
            }

            if (name.endsWith(".html", ignoreCase = true)) {
                if (htmlFile == null) {
                    htmlFile = targetFile
                } else {
                    // More than one HTML file found
                    return null
                }
            }
        }

        return htmlFile
    }

    inner class JSBridge {
        @JavascriptInterface
        fun saveFile(base64: String, filename: String) {
            runOnUiThread {
                try {
                    val bytes = Base64.decode(base64, Base64.DEFAULT)
                    val downloadsDir = File(getExternalFilesDir(null), "Downloads").apply { mkdirs() }
                    val file = File(downloadsDir, filename)
                    FileOutputStream(file).use { it.write(bytes) }

                    Toast.makeText(this@MainActivity, "Saved to app Downloads: $filename", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "Failed to save file: ${e.message}", Toast.LENGTH_LONG).show()
                    e.printStackTrace()
                }
            }
        }
    }

}
