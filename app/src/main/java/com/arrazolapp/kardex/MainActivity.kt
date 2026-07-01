package com.arrazolapp.kardex

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.MimeTypeMap
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.io.OutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    // Selector de archivos para los <input type="file"> (importar Excel / JSON)
    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val cb = filePathCallback
            filePathCallback = null
            if (cb == null) return@registerForActivityResult
            val uris: Array<Uri>? =
                WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
            cb.onReceiveValue(uris)
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this)
        setContentView(webView)

        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            javaScriptCanOpenWindowsAutomatically = true
            loadWithOverviewMode = true
            useWideViewPort = true
            // Firebase Auth/Firestore funcionan vía HTTPS; cargamos el HTML desde asset (file://)
            mediaPlaybackRequiresUserGesture = false
        }

        WebView.setWebContentsDebuggingEnabled(false)

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // Captura clicks en <a download> con blob: y los envía al puente nativo,
                // preservando el nombre de archivo definido en el HTML.
                view?.evaluateJavascript(BLOB_HOOK_JS, null)
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                view: WebView?,
                callback: ValueCallback<Array<Uri>>?,
                params: FileChooserParams?
            ): Boolean {
                filePathCallback?.onReceiveValue(null)
                filePathCallback = callback
                val intent = params?.createIntent()
                return try {
                    fileChooserLauncher.launch(intent)
                    true
                } catch (e: Exception) {
                    filePathCallback = null
                    false
                }
            }
        }

        // Puente nativo para guardar descargas (Excel/JSON) en la carpeta Descargas
        webView.addJavascriptInterface(NativeBridge(), "Android")

        // Respaldo: descargas que no pasen por el hook JS (URLs http/https reales)
        webView.setDownloadListener { url, _, contentDisposition, mimetype, _ ->
            if (url.startsWith("blob:")) return@setDownloadListener
            try {
                val name = guessName(contentDisposition, mimetype, url)
                val request = DownloadManager.Request(Uri.parse(url))
                    .setMimeType(mimetype)
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
                (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
                toast("Descargando $name")
            } catch (_: Exception) { }
        }

        // Botón atrás del sistema -> navegar atrás en el WebView si es posible
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack()
                else { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
            }
        })

        if (savedInstanceState == null) {
            webView.loadUrl("file:///android_asset/kardex.html")
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        webView.restoreState(savedInstanceState)
    }

    private fun guessName(contentDisposition: String?, mime: String?, url: String): String {
        // Intentar extraer filename del Content-Disposition
        contentDisposition?.let {
            val regex = Regex("filename\\*?=([^;]+)", RegexOption.IGNORE_CASE)
            regex.find(it)?.groupValues?.getOrNull(1)?.let { raw ->
                val clean = raw.trim().trim('"').substringAfterLast("''")
                if (clean.isNotBlank()) return clean
            }
        }
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "bin"
        return "descarga-${System.currentTimeMillis()}.$ext"
    }

    private fun toast(msg: String) =
        runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }

    /** Recibe desde JS un dataURL (base64) y lo guarda en Descargas. */
    inner class NativeBridge {
        @JavascriptInterface
        fun saveBase64(dataUrl: String, fileName: String, mime: String) {
            try {
                val comma = dataUrl.indexOf(',')
                val b64 = if (comma >= 0) dataUrl.substring(comma + 1) else dataUrl
                val bytes = Base64.decode(b64, Base64.DEFAULT)
                val safeName = if (fileName.isBlank()) "descarga.bin" else fileName
                val effMime = if (mime.isBlank()) "application/octet-stream" else mime

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val resolver = contentResolver
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, safeName)
                        put(MediaStore.Downloads.MIME_TYPE, effMime)
                        put(MediaStore.Downloads.IS_PENDING, 1)
                    }
                    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    if (uri != null) {
                        resolver.openOutputStream(uri)?.use { os: OutputStream -> os.write(bytes) }
                        values.clear()
                        values.put(MediaStore.Downloads.IS_PENDING, 0)
                        resolver.update(uri, values, null, null)
                    }
                } else {
                    val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    if (!dir.exists()) dir.mkdirs()
                    java.io.File(dir, safeName).outputStream().use { it.write(bytes) }
                }
                toast("Guardado en Descargas: $safeName")
            } catch (e: Exception) {
                toast("No se pudo guardar el archivo")
            }
        }
    }

    companion object {
        // Intercepta clicks en enlaces <a download> con blob: y los reenvía al puente nativo.
        private const val BLOB_HOOK_JS = """
            (function(){
              if (window.__kardexBlobHook) return; window.__kardexBlobHook = true;
              document.addEventListener('click', function(e){
                var a = e.target && e.target.closest ? e.target.closest('a[download]') : null;
                if(!a || !a.href || a.href.indexOf('blob:')!==0) return;
                e.preventDefault();
                var name = a.getAttribute('download') || 'descarga';
                var x = new XMLHttpRequest();
                x.open('GET', a.href, true); x.responseType = 'blob';
                x.onload = function(){
                  try{
                    var blob = x.response;
                    var r = new FileReader();
                    r.onloadend = function(){
                      try { Android.saveBase64(r.result, name, (blob.type||'application/octet-stream')); } catch(err){}
                    };
                    r.readAsDataURL(blob);
                  }catch(err){}
                };
                x.send();
              }, true);
            })();
        """
    }
}
