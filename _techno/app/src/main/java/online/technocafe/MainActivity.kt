package online.technocafe

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.*
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import online.technocafe.PlayerService.PlayerServiceBinder


const val MSG_STARTED = 1
const val MSG_STOPPED = 2
const val MSG_STATUS = 3

const val TECHNO_STREAM_URL = "http://212.109.198.36:8000"
const val MINI_STREAM_URL = "http://212.109.198.36:9000"
const val TECHNO_STREAM_INFO_URL = "http://technocafe.online/json.php"
const val MINI_STREAM_INFO_URL = "http://technocafe.online/json2.php"

class WebAppInterface(
        private var playerServiceBinder: PlayerService.PlayerServiceBinder,
        private var mediaController: MediaControllerCompat) {
    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
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
    private var playerServiceBinder: PlayerService.PlayerServiceBinder? = null
    private var mediaController: MediaControllerCompat? = null
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

            webView = findViewById(R.id.webview)!!
            webView.settings.javaScriptEnabled = true
            val webAppInterface = WebAppInterface(playerServiceBinder!!, mediaController!!)
            webView.addJavascriptInterface(webAppInterface, "Android")
            webView.loadUrl("http://technocafe.online/")
            //callback.onPlaybackStateChanged(mediaController!!.playbackState)
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
    }

    override fun onDestroy() {
        super.onDestroy()
        playerServiceBinder = null
        mediaController?.unregisterCallback(callback)
        mediaController = null
        unbindService(serviceConnection)
    }
}