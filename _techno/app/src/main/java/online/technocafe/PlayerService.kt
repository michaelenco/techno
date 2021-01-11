package online.technocafe

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioManager.OnAudioFocusChangeListener
import android.net.Uri
import android.os.*
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.media.MediaBrowserServiceCompat
import androidx.media.session.MediaButtonReceiver
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.SimpleExoPlayer
import com.google.android.exoplayer2.source.TrackGroupArray
import com.google.android.exoplayer2.trackselection.TrackSelectionArray
import org.json.JSONObject
import java.lang.Thread.sleep
import java.net.URL


private const val NOTIFICATION_DEFAULT_CHANNEL_ID = "default_channel"
private val NOTIFICATION_ID = 404

class PlayerService : MediaBrowserServiceCompat() {
    private lateinit var exoPlayer: SimpleExoPlayer
    private var audioFocusRequested = false
    private lateinit var audioFocusRequest: AudioFocusRequest
    private lateinit var audioManager: AudioManager
    private lateinit var mediaSession: MediaSessionCompat
    private val metadataBuilder = MediaMetadataCompat.Builder()
    private val stateBuilder = PlaybackStateCompat.Builder().setActions(
        PlaybackStateCompat.ACTION_PLAY
                or PlaybackStateCompat.ACTION_STOP
                or PlaybackStateCompat.ACTION_PAUSE
                or PlaybackStateCompat.ACTION_PLAY_PAUSE
    )
    private var _streamUrl = ""
    private var _streamInfoUrl = ""
    private var _channel = ""
    private var title = ""
    var currentState = PlaybackStateCompat.STATE_STOPPED

    private var streamInfoHandler = Handler(Looper.myLooper()!!)
    private var pollingService = object: Runnable {
        override fun run() {
            if (currentState == PlaybackStateCompat.STATE_PLAYING) {
                val info = URL(_streamInfoUrl).readText()
                val jsonObject = JSONObject(info)
                val attributes = jsonObject.getJSONObject("@attributes")
                var newTitle = attributes.getString("CASTTITLE")
                if (newTitle != title) {
                    title = newTitle
                    Handler(Looper.getMainLooper()).post(Runnable {
                        refreshNotificationAndForegroundStatus(currentState)
                    })
                }
                sleep(5000)
                this.run()
            }
        }
    }


    public inner class PlayerServiceBinder : Binder() {
        val mediaSessionToken: MediaSessionCompat.Token
            get() = mediaSession.sessionToken
        var streamUrl: String
            get() = _streamUrl
            set(value) {
                _streamUrl = value
            }
        var streamInfoUrl: String
            get() = _streamInfoUrl
            set(value) {
                _streamInfoUrl = value
            }
        var channel: String
            get() = _channel
            set(value) {
                _channel = value
            }
        val isPlaying: Boolean
            get() = currentState == PlaybackStateCompat.STATE_PLAYING
    }

    private val audioFocusChangeListener = OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> mediaSessionCallback.onPlay()
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> mediaSessionCallback.onPause()
            else -> mediaSessionCallback.onPause()
        }
    }

    private val becomingNoisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // Disconnecting headphones - stop playback
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY == intent.action) {
                mediaSessionCallback.onPause()
            }
        }
    }

    private val exoPlayerListener = object : Player.EventListener {
        override fun onTracksChanged(
            trackGroups: TrackGroupArray,
            trackSelections: TrackSelectionArray
        ) {
        }

        override fun onLoadingChanged(isLoading: Boolean) {}
        override fun onPlayerStateChanged(
            playWhenReady: Boolean,
            playbackState: Int
        ) {
            if (playWhenReady && playbackState == ExoPlayer.STATE_ENDED) {
                mediaSessionCallback.onSkipToNext()
            }
        }

//        override fun onPlayerError(error: ExoPlaybackException) {}
//        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {}
    }

    private val mediaSessionCallback: MediaSessionCompat.Callback = object : MediaSessionCompat.Callback() {
        private var currentUri: Uri? = null
        override fun onPlay() {
            if (currentState == PlaybackStateCompat.STATE_PLAYING) {
                return
            }
            if (!exoPlayer.playWhenReady) {
                prepareToPlay(_streamUrl)
                if (!audioFocusRequested) {
                    audioFocusRequested = true
                    val audioFocusResult: Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        audioManager.requestAudioFocus(audioFocusRequest)
                    } else {
                        audioManager.requestAudioFocus(
                            audioFocusChangeListener,
                            AudioManager.STREAM_MUSIC,
                            AudioManager.AUDIOFOCUS_GAIN
                        )
                    }
                    if (audioFocusResult != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return
                }
                mediaSession.isActive = true
                registerReceiver(
                    becomingNoisyReceiver,
                    IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
                )
                exoPlayer.playWhenReady = true
            }
            mediaSession.setPlaybackState(
                stateBuilder.setState(
                    PlaybackStateCompat.STATE_PLAYING,
                    PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN,
                    1F
                ).build()
            )
            currentState = PlaybackStateCompat.STATE_PLAYING
            refreshNotificationAndForegroundStatus(currentState)
            Thread(pollingService).start()
        }

        override fun onPause() {
            if (currentState == PlaybackStateCompat.STATE_PAUSED) {
                return
            }
            if (exoPlayer.playWhenReady) {
                exoPlayer.playWhenReady = false
                unregisterReceiver(becomingNoisyReceiver)
            }
            mediaSession.setPlaybackState(
                stateBuilder.setState(
                    PlaybackStateCompat.STATE_PAUSED,
                    PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN,
                    1F
                ).build()
            )
            currentState = PlaybackStateCompat.STATE_PAUSED
            refreshNotificationAndForegroundStatus(currentState)
        }

        override fun onStop() {
            if (exoPlayer.playWhenReady) {
                exoPlayer.playWhenReady = false
                unregisterReceiver(becomingNoisyReceiver)
            }
            if (audioFocusRequested) {
                audioFocusRequested = false
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    audioManager.abandonAudioFocusRequest(audioFocusRequest)
                } else {
                    audioManager.abandonAudioFocus(audioFocusChangeListener)
                }
            }
            mediaSession.isActive = false
            mediaSession.setPlaybackState(
                stateBuilder.setState(
                    PlaybackStateCompat.STATE_STOPPED,
                    PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN,
                    1F
                ).build()
            )
            currentState = PlaybackStateCompat.STATE_STOPPED
            refreshNotificationAndForegroundStatus(currentState)
            stopSelf()
        }

        private fun prepareToPlay(uri: String) {
            exoPlayer.setMediaItem(MediaItem.fromUri(uri))
            exoPlayer.prepare()
        }
    }

    private fun updateMetadataFromTrack() {
        metadataBuilder.putBitmap(
            MediaMetadataCompat.METADATA_KEY_ART,
            BitmapFactory.decodeResource(resources, android.R.drawable.ic_dialog_info)
        );
        metadataBuilder.putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
        metadataBuilder.putString(
            MediaMetadataCompat.METADATA_KEY_ARTIST,
            _channel
        )
        metadataBuilder.putLong(
            MediaMetadataCompat.METADATA_KEY_DURATION,
            -1L
        )
        mediaSession.setMetadata(metadataBuilder.build())
    }

    private fun refreshNotificationAndForegroundStatus(playbackState: Int) {
        updateMetadataFromTrack()
        when (playbackState) {
            PlaybackStateCompat.STATE_PLAYING -> {
                startForeground(NOTIFICATION_ID, getNotification(playbackState))
            }
            PlaybackStateCompat.STATE_PAUSED -> {
                NotificationManagerCompat.from(this)
                    .notify(NOTIFICATION_ID, getNotification(playbackState))
                stopForeground(false)
            }
            else -> {
                stopForeground(true)
            }
        }
    }

    private fun getNotification(playbackState: Int): Notification {
        val builder = MediaStyleHelper.from(this, mediaSession)

        if (playbackState == PlaybackStateCompat.STATE_PLAYING) {
            builder.addAction(
                android.R.drawable.ic_media_pause,
                "pause",
                MediaButtonReceiver.buildMediaButtonPendingIntent(
                    this,
                    PlaybackStateCompat.ACTION_PLAY_PAUSE
                )
            )
        } else {
            builder.addAction(
                android.R.drawable.ic_media_play,
                "play",
                MediaButtonReceiver.buildMediaButtonPendingIntent(
                    this,
                    PlaybackStateCompat.ACTION_PLAY_PAUSE
                )

            )
        }

        builder.setStyle(
            androidx.media.app.NotificationCompat.MediaStyle()
                .setShowActionsInCompactView(0)
                .setShowCancelButton(true)
                .setCancelButtonIntent(
                    MediaButtonReceiver.buildMediaButtonPendingIntent(
                        this,
                        PlaybackStateCompat.ACTION_STOP
                    )
                )
                .setMediaSession(mediaSession.sessionToken)
        )
        val options = BitmapFactory.Options()
        val bitmap = BitmapFactory.decodeResource(resources, R.mipmap.ic_techno_square, options)
        builder.setSmallIcon(R.mipmap.ic_techno_round) //ic_launcher
        builder.setLargeIcon(bitmap)
        //builder.color = ContextCompat.getColor(this, R.color.black)
        builder.setShowWhen(false)
        builder.priority = NotificationCompat.PRIORITY_DEFAULT
        builder.setOnlyAlertOnce(true)
        builder.setChannelId(NOTIFICATION_DEFAULT_CHANNEL_ID)
        return builder.build()
    }

    override fun onCreate() {
        super.onCreate()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationChannel = NotificationChannel(
                NOTIFICATION_DEFAULT_CHANNEL_ID,
                "TECHNOCAFE",
                NotificationManager.IMPORTANCE_DEFAULT
            )

            val notificationManager =
                    getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(notificationChannel)

            val audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()

            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setOnAudioFocusChangeListener(audioFocusChangeListener)
                    .setAcceptsDelayedFocusGain(false)
                    .setWillPauseWhenDucked(true)
                    .setAudioAttributes(audioAttributes)
                    .build()
        }

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        mediaSession = MediaSessionCompat(this, "PlayerService")
        mediaSession.setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS)
        mediaSession.setCallback(mediaSessionCallback)

        val appContext = applicationContext

        val activityIntent = Intent(appContext, MainActivity::class.java)
        mediaSession.setSessionActivity(PendingIntent.getActivity(appContext, 0, activityIntent, 0))

        val mediaButtonIntent = Intent(
            Intent.ACTION_MEDIA_BUTTON, null, appContext,
            MediaButtonReceiver::class.java
        )
        mediaSession.setMediaButtonReceiver(
            PendingIntent.getBroadcast(
                appContext,
                0,
                mediaButtonIntent,
                0
            )
        )

        exoPlayer = SimpleExoPlayer.Builder(this).build()

        exoPlayer.addListener(exoPlayerListener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        MediaButtonReceiver.handleIntent(mediaSession, intent)
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onLoadChildren(
        parentId: String,
        result: Result<MutableList<MediaBrowserCompat.MediaItem>>
    ) {
        result.sendResult(null)
    }

    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: Bundle?
    ): BrowserRoot? {
        return null
    }

    override fun onBind(intent: Intent?): IBinder {
        return PlayerServiceBinder()
    }

    internal object MediaStyleHelper {
        fun from(context: Context, mediaSession: MediaSessionCompat): NotificationCompat.Builder {
            val controller = mediaSession.controller
            val mediaMetadata: MediaMetadataCompat? = controller.metadata
            val description = mediaMetadata?.description
            val builder = NotificationCompat.Builder(context, NOTIFICATION_DEFAULT_CHANNEL_ID)
            builder
                    .setContentTitle(description?.title)
                    .setContentText(description?.subtitle)
                    .setSubText(description?.description)
                    .setLargeIcon(description?.iconBitmap)
                    .setContentIntent(controller.sessionActivity)
                    .setDeleteIntent(
                        MediaButtonReceiver.buildMediaButtonPendingIntent(
                            context,
                            PlaybackStateCompat.ACTION_STOP
                        )
                    )
                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            return builder
        }
    }
}