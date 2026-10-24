package com.jiepi.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.KeyEvent
import android.widget.RemoteViews
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.Timeline
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

// 媒体播放前台服务：自管媒体会话 + 通知栏卡片 + 前台服务。
// 设计说明：原实现继承 MediaSessionService，依赖其 MediaNotificationManager 自动挂前台/发通知；
// 但该管理器要求会话 playbackState 为 PLAYING/BUFFERING 才 startForeground，而本项目用
// SimpleBasePlayer 动态代理（SimpleBasePlayer 状态机恒为 READY，播放态由 playWhenReady 表达），
// 导致 media3 判定"无需前台"永不 startForeground——日志直接报
// ForegroundServiceDidNotStartInTimeException（startForegroundService 后 5 秒未挂前台）。
// 现改为继承 Service 自管：前台服务在 onStartCommand 立即拉起；通知卡用系统 MediaStyle +
// 会话 token（播放/暂停/上下曲按钮由系统按会话可用命令自动渲染），播放状态变化时刷新内容。
// 车载/蓝牙媒体按键仍由 manifest 的 MediaButtonReceiver 转发到 onStartCommand，再交给会话。
@OptIn(UnstableApi::class)
class PlaybackService : Service() {

    companion object {
        private const val CHANNEL_ID = "jiepi_playback"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_PLAY_PAUSE = "com.jiepi.player.action.PLAY_PAUSE"
        private const val ACTION_NEXT = "com.jiepi.player.action.NEXT"
        private const val ACTION_PREVIOUS = "com.jiepi.player.action.PREVIOUS"
        const val ACTION_SEEK = "com.jiepi.player.action.SEEK"
        const val EXTRA_POSITION = "com.jiepi.player.extra.POSITION"
    }

    private var mediaSession: MediaSession? = null
    private var playerProxy: CarPlayerProxy? = null
    private var sessionActivity: PendingIntent? = null
    private var startedForeground = false

    // 出声状态变化（切歌/暂停/恢复/seek）时：刷会话状态给车载，同时更新通知卡内容
    private val playbackListener: () -> Unit = {
        playerProxy?.invalidateNow()
        if (startedForeground) refreshNotification()
    }
    private val mainHandler = Handler(Looper.getMainLooper())

    // 通知栏/车载进度条兜底刷新：ExoPlayer 的 position 事件不会都透传到 SimpleBasePlayer，
    // 每秒拉一次真实位置保证进度不卡（1Hz 开销可忽略，timeline 为懒构建）。
    private val ticker = object : Runnable {
        override fun run() {
            playerProxy?.invalidateNow()
            if (startedForeground) refreshNotification()
            mainHandler.postDelayed(this, 1000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val proxy = CarPlayerProxy(this)
        playerProxy = proxy
        PlayerManager.addPlaybackListener(playbackListener)
        // 点通知/车载图标回应用主界面
        val sa = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        sessionActivity = sa
        mediaSession = MediaSession.Builder(this, proxy)
            .setSessionActivity(sa)
            .build()
        mainHandler.post(ticker)
    }

    // 启动入口：播放/恢复（startForegroundService）与媒体按键（MediaButtonReceiver）都走这里。
    // 媒体按键直接交给会话（MediaSession 内置 KEYCODE 映射，无需自己解析）；
    // 普通启动立即挂前台通知，满足 startForegroundService 的 5 秒时限。
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY_PAUSE -> {
                PlayerManager.pauseOrResume()
                return START_STICKY
            }
            ACTION_NEXT -> {
                PlayerManager.next(this)
                return START_STICKY
            }
            ACTION_PREVIOUS -> {
                PlayerManager.previous(this)
                return START_STICKY
            }
            ACTION_SEEK -> {
                val pos = intent?.getIntExtra(EXTRA_POSITION, -1) ?: -1
                if (pos >= 0) PlayerManager.seekTo(pos)
                return START_STICKY
            }
        }
        if (intent?.action == Intent.ACTION_MEDIA_BUTTON) {
            // 蓝牙耳机/车载媒体键：解析 KeyEvent 后直接驱动 PlayerManager（会话状态经
            // playbackListener -> invalidateNow 自动跟随，无需绕 controller）
            val keyEvent = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
            if (keyEvent != null && keyEvent.action == KeyEvent.ACTION_DOWN) {
                when (keyEvent.keyCode) {
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> PlayerManager.pauseOrResume()
                    KeyEvent.KEYCODE_MEDIA_PLAY -> PlayerManager.resume()
                    KeyEvent.KEYCODE_MEDIA_PAUSE -> PlayerManager.pause()
                    KeyEvent.KEYCODE_MEDIA_NEXT -> PlayerManager.next(this)
                    KeyEvent.KEYCODE_MEDIA_PREVIOUS -> PlayerManager.previous(this)
                    KeyEvent.KEYCODE_MEDIA_STOP -> PlayerManager.pause()
                }
            }
            return START_NOT_STICKY
        }
        if (!startedForeground) {
            startForeground(NOTIFICATION_ID, buildNotification())
            startedForeground = true
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        mainHandler.removeCallbacks(ticker)
        PlayerManager.removePlaybackListener(playbackListener)
        mediaSession?.run {
            player.release() // 先释放 player（代理），再释放 session
            release()
        }
        mediaSession = null
        playerProxy = null
        startedForeground = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "播放控制", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    // 前台服务运行中：状态变化时刷新通知（MediaStyle 按钮状态由系统随会话自动更新）
    private fun refreshNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val title = PlayerManager.currentDisplayName().ifBlank { "洁癖播放器" }
        val artist = PlayerManager.currentArtist().ifBlank { "未在播放" }
        val playing = PlayerManager.activePlayer?.isPlaying == true
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_logo_baiselogo)
            .setContentTitle(title)
            .setContentText(artist)
            .setContentIntent(sessionActivity!!)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setColor(0xFF000000.toInt())
            .setColorized(true)
            .setStyle(
                androidx.media.app.NotificationCompat.DecoratedMediaCustomViewStyle()
                    .setMediaSession(mediaSession!!.sessionCompatToken)
            )
            .setCustomContentView(buildCollapsedViews(playing))
            .setCustomBigContentView(buildExpandedViews(playing))
            .build()
    }

    private fun buildCollapsedViews(playing: Boolean): RemoteViews {
        val v = RemoteViews(packageName, R.layout.notification_player_collapsed)
        bindControls(v, playing)
        return v
    }

    private fun buildExpandedViews(playing: Boolean): RemoteViews {
        val v = RemoteViews(packageName, R.layout.notification_player_expanded)
        v.setString(R.id.np_slider, "setSeekServiceAction", ACTION_SEEK)
        bindControls(v, playing)
        val exo = PlayerManager.activePlayer
        val duration = exo?.duration?.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
        val position = (exo?.currentPosition ?: 0L).coerceIn(0L, duration)
        if (duration > 0L) {
            v.setInt(R.id.np_slider, "setMax", duration.toInt().coerceAtLeast(1))
            v.setInt(R.id.np_slider, "setProgress", position.toInt().coerceIn(0, duration.toInt()))
        } else {
            v.setInt(R.id.np_slider, "setMax", 100)
            v.setInt(R.id.np_slider, "setProgress", 0)
        }
        return v
    }

    private fun bindControls(v: RemoteViews, playing: Boolean) {
        v.setImageViewResource(R.id.np_play_pause, if (playing) R.drawable.ic_play_pause else R.drawable.ic_play_play)
        v.setOnClickPendingIntent(R.id.np_prev, actionPendingIntent(ACTION_PREVIOUS, 1))
        v.setOnClickPendingIntent(R.id.np_play_pause, actionPendingIntent(ACTION_PLAY_PAUSE, 2))
        v.setOnClickPendingIntent(R.id.np_next, actionPendingIntent(ACTION_NEXT, 3))
    }

    private fun actionPendingIntent(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            this, requestCode,
            Intent(this, PlaybackService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE
        )
}

// SimpleBasePlayer 动态代理：把 MediaSession 的标准播放命令桥接到 PlayerManager
@OptIn(UnstableApi::class)
class CarPlayerProxy(context: Context) : SimpleBasePlayer(context.mainLooper) {

    private val appContext: Context = context.applicationContext

    // 供外部（PlaybackService）触发状态刷新：invalidateState 是 protected，需在子类内暴露
    fun invalidateNow() {
        invalidateState()
    }

    // 暴露给车机/蓝牙/通知栏的命令集合
    private val sessionCommands: Player.Commands
        get() = Player.Commands.Builder()
            .add(Player.COMMAND_PLAY_PAUSE)
            .add(Player.COMMAND_PREPARE)
            .add(Player.COMMAND_STOP)
            .add(Player.COMMAND_SEEK_TO_NEXT)
            .add(Player.COMMAND_SEEK_TO_PREVIOUS)
            .add(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
            .add(Player.COMMAND_SEEK_BACK)
            .add(Player.COMMAND_SEEK_FORWARD)
            .add(Player.COMMAND_GET_CURRENT_MEDIA_ITEM)
            .add(Player.COMMAND_GET_TIMELINE)
            .add(Player.COMMAND_GET_METADATA)
            .build()

    override fun getState(): State {
        val exo = PlayerManager.activePlayer
        val size = PlayerManager.songPaths.size
        val idx = if (size > 0) PlayerManager.currentIndex.coerceIn(0, size - 1) else C.INDEX_UNSET
        val hasMedia = exo != null && size > 0
        val playing = exo?.isPlaying == true
        val suppression = exo?.playbackSuppressionReason ?: Player.PLAYBACK_SUPPRESSION_REASON_NONE

        val builder = State.Builder()
            .setAvailableCommands(sessionCommands)
            .setPlayWhenReady(if (suppression != Player.PLAYBACK_SUPPRESSION_REASON_NONE) false else playing, suppression)
            .setPlaybackState(
                when {
                    !hasMedia -> Player.STATE_IDLE
                    exo?.playbackState == Player.STATE_BUFFERING || exo?.isLoading == true -> Player.STATE_BUFFERING
                    exo?.playbackState == Player.STATE_ENDED -> Player.STATE_ENDED
                    else -> Player.STATE_READY
                }
            )
            .setIsLoading(exo?.isLoading == true)
            .setPlaybackSuppressionReason(suppression)
            .setPlayerError(exo?.playerError)
            .setPlaybackParameters(exo?.playbackParameters ?: PlaybackParameters.DEFAULT)
            .setRepeatMode(exo?.repeatMode ?: Player.REPEAT_MODE_OFF)
            .setShuffleModeEnabled(exo?.shuffleModeEnabled ?: false)
            .setContentPositionMs(if (hasMedia) (exo?.currentPosition ?: 0L).coerceAtLeast(0L) else 0L)
            .setSeekBackIncrementMs(10_000L)
            .setSeekForwardIncrementMs(10_000L)
            .setMaxSeekToPreviousPositionMs(0L) // 上一曲：直接切到上一窗口，不从当前曲目回跳
            .setContentBufferedPositionMs(SimpleBasePlayer.PositionSupplier.getConstant((exo?.bufferedPosition ?: 0L).coerceAtLeast(0L)))
            .setTotalBufferedDurationMs(SimpleBasePlayer.PositionSupplier.getConstant(exo?.totalBufferedDuration ?: 0L))

        val currentMetadata = if (hasMedia) {
            val d = exo?.duration ?: C.TIME_UNSET
            MediaMetadata.Builder()
                .setTitle(PlayerManager.currentDisplayName())
                .setArtist(PlayerManager.currentArtist().ifBlank { null })
                .setDurationMs(if (d == C.TIME_UNSET || d <= 0) null else d)
                .build()
        } else {
            MediaMetadata.EMPTY
        }
        if (hasMedia) {
            builder.setPlaylist(SongsTimeline(PlayerManager.songPaths.toList()), Tracks.EMPTY, currentMetadata)
                .setCurrentMediaItemIndex(idx)
        } else {
            builder.setPlaylist(Timeline.EMPTY, Tracks.EMPTY, MediaMetadata.EMPTY)
                .setCurrentMediaItemIndex(C.INDEX_UNSET)
        }
        return builder.build()
    }

    // 播放/暂停：方向盘 PLAY/PAUSE、蓝牙、通知栏按钮
    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (playWhenReady) {
            ensurePlaying()
        } else {
            PlayerManager.pause()
        }
        return Futures.immediateVoidFuture()
    }

    // 切歌与拖动：SimpleBasePlayer 内部把 next/previous/seekTo 统一换算成窗口索引+位置
    override fun handleSeek(windowIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        if (windowIndex != PlayerManager.currentIndex && windowIndex in PlayerManager.songPaths.indices) {
            PlayerManager.play(windowIndex, appContext)
        } else if (windowIndex == PlayerManager.currentIndex && PlayerManager.activePlayer != null) {
            PlayerManager.seekTo(positionMs.toInt())
        }
        return Futures.immediateVoidFuture()
    }

    // 通知栏"停止"：暂停即可（保持后台待命，可继续播放）
    override fun handleStop(): ListenableFuture<*> {
        PlayerManager.pause()
        return Futures.immediateVoidFuture()
    }

    // "继续播放当前曲目"：无实例/自然播完 → 重播当前歌；已暂停 → 干净恢复
    private fun ensurePlaying() {
        val exo = PlayerManager.activePlayer
        when {
            exo == null -> if (PlayerManager.songPaths.isNotEmpty()) {
                PlayerManager.play(PlayerManager.currentIndex.coerceAtLeast(0), appContext)
            }
            exo.playbackState == Player.STATE_ENDED -> PlayerManager.play(
                PlayerManager.currentIndex.coerceAtLeast(0), appContext
            )
            else -> PlayerManager.resume()
        }
    }
}

// 歌曲队列时间线：让车载/通知栏能识别整个队列，SimpleBasePlayer 据此换算上下曲窗口
private class SongsTimeline(private val paths: List<String>) : Timeline() {

    override fun getWindowCount(): Int = paths.size

    override fun getPeriodCount(): Int = paths.size

    override fun getWindow(index: Int, window: Window, defaultPositionProjectionUs: Long): Window {
        val path = paths.getOrNull(index) ?: return window
        val item = mediaItemFor(index, path)
        val durationUs = if (index == PlayerManager.currentIndex) currentDurationUs() else C.TIME_UNSET
        window.set(
            Window.SINGLE_WINDOW_UID, item, null,
            0L, 0L, 0L, false, false, null, 0L, durationUs, 0, 0, 0L
        )
        return window
    }

    override fun getPeriod(index: Int, period: Period, setIds: Boolean): Period {
        val path = paths.getOrNull(index) ?: return period
        val item = mediaItemFor(index, path)
        val durationUs = if (index == PlayerManager.currentIndex) currentDurationUs() else C.TIME_UNSET
        period.set(item.mediaId, Window.SINGLE_WINDOW_UID, index, durationUs, 0L)
        return period
    }

    override fun getIndexOfPeriod(uid: Any): Int {
        val path = uid as? String ?: return C.INDEX_UNSET
        return paths.indexOf(path).takeIf { it >= 0 } ?: C.INDEX_UNSET
    }

    override fun getUidOfPeriod(index: Int): Any =
        paths.getOrNull(index) ?: C.INDEX_UNSET

    private fun mediaItemFor(index: Int, path: String): MediaItem {
        val title = if (index == PlayerManager.currentIndex) {
            PlayerManager.currentDisplayName()
        } else {
            path.substringAfterLast('/').substringBeforeLast('.')
        }
        val artist = if (index == PlayerManager.currentIndex) PlayerManager.currentArtist().ifBlank { null } else null
        return MediaItem.Builder()
            .setMediaId(path)
            .setUri(path)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(artist)
                    .build()
            )
            .build()
    }

    private fun currentDurationUs(): Long {
        val d = PlayerManager.activePlayer?.duration ?: return C.TIME_UNSET
        return if (d == C.TIME_UNSET || d <= 0) C.TIME_UNSET else d * 1000L
    }
}
