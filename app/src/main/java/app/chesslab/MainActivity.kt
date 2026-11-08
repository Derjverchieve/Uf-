package app.chesslab

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.ByteArrayInputStream
import java.io.File

class MainActivity : Activity() {
    private lateinit var web: WebView
    private var proc: Process? = null
    private var out: BufferedWriter? = null
    private var picker: ValueCallback<Array<Uri>>? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        web = WebView(this)
        web.setBackgroundColor(Color.parseColor("#0f1214"))
        web.settings.javaScriptEnabled = true
        web.addJavascriptInterface(Bridge(), "Android")
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(v: WebView?, r: WebResourceRequest?): WebResourceResponse? {
                val u = r?.url ?: return null
                if (u.host != "appassets.local") return null
                val path = (u.path ?: "").trimStart('/').ifEmpty { "index.html" }
                return try {
                    val mime = when (path.substringAfterLast('.', "")) {
                        "html" -> "text/html"; "js", "mjs" -> "text/javascript"; "wasm" -> "application/wasm"
                        "svg" -> "image/svg+xml"; "css" -> "text/css"; "tsv" -> "text/plain"
                        else -> "application/octet-stream"
                    }
                    WebResourceResponse(mime, "utf-8", 200, "OK", mapOf("Access-Control-Allow-Origin" to "*"), assets.open(path))
                } catch (e: Exception) {
                    WebResourceResponse("text/plain", "utf-8", 404, "Not found", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                }
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(w: WebView?, cb: ValueCallback<Array<Uri>>?, p: FileChooserParams?): Boolean {
                if (cb == null || p == null) return false
                picker?.onReceiveValue(null)
                picker = cb
                startActivityForResult(p.createIntent(), 1)
                return true
            }
        }
        setContentView(web)
        startEngine()
        web.loadUrl("https://appassets.local/index.html")
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        picker?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data))
        picker = null
    }

    private fun startEngine() {
        val dir = applicationInfo.nativeLibraryDir
        val dot = try { File("/proc/cpuinfo").readText().contains("asimddp") } catch (e: Exception) { false }
        for (name in if (dot) listOf("libsf_dot.so", "libsf_base.so") else listOf("libsf_base.so")) {
            try {
                val p = ProcessBuilder(File(dir, name).path).redirectErrorStream(true).start()
                proc = p; out = p.outputStream.bufferedWriter()
                Thread {
                    p.inputStream.bufferedReader().forEachLine { l ->
                        if (l.startsWith("bestmove") || (l.startsWith("info") && l.contains(" pv ")))
                            runOnUiThread { web.evaluateJavascript("onEng(" + JSONObject.quote(l) + ")", null) }
                    }
                }.start()
                send("uci")
                send("setoption name Threads value " + maxOf(1, Runtime.getRuntime().availableProcessors() / 2))
                send("setoption name Hash value 64")
                return
            } catch (e: Exception) { }
        }
    }

    private fun send(c: String) { try { out?.apply { write(c); newLine(); flush() } } catch (e: Exception) { } }

    inner class Bridge {
        @JavascriptInterface fun send(c: String) = this@MainActivity.send(c)
        @JavascriptInterface fun copy(t: String) {
            (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("chess", t))
        }
    }

    override fun onDestroy() { send("quit"); proc?.destroy(); super.onDestroy() }
}
