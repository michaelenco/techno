package online.technocafe

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.Network
import android.os.*
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.view.View
import android.webkit.*
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import online.technocafe.PlayerService.PlayerServiceBinder

const val TECHNO_STREAM_URL = "http://212.109.198.36:8000"
const val MINI_STREAM_URL = "http://212.109.198.36:9000"
const val TECHNO_STREAM_INFO_URL = "http://technocafe.online/json.php"
const val MINI_STREAM_INFO_URL = "http://technocafe.online/json2.php"

class WebAppInterface(
        private var playerServiceBinder: PlayerService.PlayerServiceBinder,
        private var mediaController: MediaControllerCompat) {

    @JavascriptInterface
    fun play(channel: String) {
        if (channel == "techno") {
            playerServiceBinder.streamUrl = TECHNO_STREAM_URL
            playerServiceBinder.streamInfoUrl = TECHNO_STREAM_INFO_URL
            playerServiceBinder.channel = "TECHNOCAFE"
        } else {
            playerServiceBinder.streamUrl = MINI_STREAM_URL
            playerServiceBinder.streamInfoUrl = MINI_STREAM_INFO_URL
            playerServiceBinder.channel = "MINIBAR"
        }
        val mainThreadHandler = Handler(Looper.getMainLooper())
        mainThreadHandler.post {
            mediaController.transportControls.play()
        }
    }
    @JavascriptInterface
    fun stop() {
        val mainThreadHandler = Handler(Looper.getMainLooper())
        mainThreadHandler.post { mediaController.transportControls.pause() }
    }
    @JavascriptInterface
    fun getActivePlayer(): String? {
        return if (playerServiceBinder.isPlaying) {
            if (playerServiceBinder.streamUrl == TECHNO_STREAM_URL) {
                "techno"
            } else {
                "mini"
            }
        } else {
            null
        }
    }
}

class MainActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private lateinit var connectionLost: TextView
    private var playerServiceBinder: PlayerService.PlayerServiceBinder? = null
    private var mediaController: MediaControllerCompat? = null
    private lateinit var connectivityManager: ConnectivityManager
    private var callback = object : MediaControllerCompat.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackStateCompat?) {
            if (state == null) return
            val playing = state.state == PlaybackStateCompat.STATE_PLAYING
            val channel = if (playing) {
                if (playerServiceBinder!!.streamUrl == TECHNO_STREAM_URL) {
                    "'techno'"
                } else {
                    "'mini'"
                }
            } else {
                "null"
            }
            webView.loadUrl("javascript:window.set_active_channel($channel)")
        }
    }
    private var serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            playerServiceBinder = service as PlayerServiceBinder
            mediaController = MediaControllerCompat(
                this@MainActivity,
                playerServiceBinder!!.mediaSessionToken
            )
            mediaController!!.registerCallback(callback)
            val webAppInterface = WebAppInterface(playerServiceBinder!!, mediaController!!)
            webView.addJavascriptInterface(webAppInterface, "Android")
            webView.loadUrl("http://technocafe.online/")
        }
        override fun onServiceDisconnected(name: ComponentName) {
            playerServiceBinder = null
            mediaController?.unregisterCallback(callback)
            mediaController = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webview)!!
        webView.settings.javaScriptEnabled = true
        webView.visibility = View.GONE
        webView.setBackgroundColor(Color.TRANSPARENT)
        webView.webViewClient = object : WebViewClient() {
            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                webView.visibility = View.GONE
                connectionLost.visibility = View.VISIBLE
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                connectionLost.visibility = View.GONE
                webView.visibility = View.VISIBLE
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val intent = Intent(Intent.ACTION_VIEW, request!!.url)
                startActivity(intent)
                return true
            }

        }

        connectionLost = findViewById(R.id.no_internet)!!
        connectionLost.visibility = View.GONE

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(Intent(applicationContext, PlayerService::class.java))
        } else {
            startService(Intent(applicationContext, PlayerService::class.java))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            window.navigationBarColor = resources.getColor(R.color.black)
        }
        bindService(
            Intent(this, PlayerService::class.java),
            serviceConnection,
            Context.BIND_AUTO_CREATE
        )

        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        connectivityManager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (connectionLost.visibility == View.VISIBLE) {
                    runOnUiThread {
                        webView.loadUrl("http://technocafe.online/")
                    }
                    super.onAvailable(network)
                }
            }
            override fun onLost(network: Network) {
                if (webView.visibility == View.VISIBLE) {
                    runOnUiThread {
                        webView.visibility = View.GONE
                        connectionLost.visibility = View.VISIBLE
                    }
                    super.onLost(network)
                }
            }
        })
    }

    override fun onDestroy() {
        super.onDestroy()
        playerServiceBinder = null
        mediaController?.unregisterCallback(callback)
        mediaController = null
        unbindService(serviceConnection)
    }
}