package ru.radiokp.voennoerevue;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.media3.datasource.DefaultHttpDataSource;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public final class AudioPlaybackService extends Service {
    public static final String ACTION_PLAY = "ru.radiokp.voennoerevue.PLAY";
    public static final String ACTION_TOGGLE = "ru.radiokp.voennoerevue.TOGGLE";
    public static final String ACTION_STOP = "ru.radiokp.voennoerevue.STOP";
    public static final String ACTION_SET_QUALITY = "ru.radiokp.voennoerevue.SET_QUALITY";
    public static final String EXTRA_URL = "stream_url";
    public static final String EXTRA_REFERER = "referer";
    public static final String EXTRA_SOURCE_NAME = "source_name";
    public static final String EXTRA_MAX_AUDIO_BITRATE = "max_audio_bitrate_kbps";
    public static final String EXTRA_IS_MP3 = "is_mp3";

    private static final String CHANNEL_ID = "radio_kp_live_audio_v3";
    private static final int NOTIFICATION_ID = 6107;
    private static final String USER_AGENT = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36";

    public interface PlaybackStateListener {
        void onPlaybackStateChanged(boolean playing, String message);
    }

    public final class LocalBinder extends Binder {
        public AudioPlaybackService getService() { return AudioPlaybackService.this; }
    }

    private final IBinder binder = new LocalBinder();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private ExoPlayer player;
    private DefaultTrackSelector trackSelector;
    private String currentUrl = "";
    private String currentReferer = "https://vkvideo.ru/";
    private String currentSourceName = "Радио КП";
    private boolean currentIsMp3 = false;
    private int maxAudioBitrateKbps = 64;
    private static final int MAX_RADIO_RECONNECT_ATTEMPTS = 2;
    private int radioReconnectAttempts = 0;
    private PlaybackStateListener listener;

    private final Runnable connectionTimeout = () -> {
        if (player != null && !currentUrl.isEmpty() && player.getPlaybackState() == Player.STATE_BUFFERING) {
            handlePlaybackFailure("источник слишком долго не отвечает");
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        trackSelector = new DefaultTrackSelector(this);
        applyTrackSelectionParameters();
        player = new ExoPlayer.Builder(this)
                .setTrackSelector(trackSelector)
                .setWakeMode(C.WAKE_MODE_NETWORK)
                .build();
        player.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                .build(), true);
        player.setHandleAudioBecomingNoisy(true);
        player.addListener(new Player.Listener() {
            @Override public void onIsPlayingChanged(boolean isPlaying) {
                updateNotification();
                notifyListener(isPlaying, isPlaying
                        ? "Идёт эфир · " + currentSourceName
                        : (player != null && player.getPlaybackState() == Player.STATE_BUFFERING
                            ? "Подключение к " + currentSourceName + "…" : "Пауза · " + currentSourceName));
            }

            @Override public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_BUFFERING) {
                    notifyListener(false, "Подключение к " + currentSourceName + "…");
                } else if (state == Player.STATE_READY) {
                    handler.removeCallbacks(connectionTimeout);
                    // Reset retry budget after every successful reconnect, so a long live programme can recover again later.
                    if (currentIsMp3) radioReconnectAttempts = 0;
                    notifyListener(player.isPlaying(), player.isPlaying()
                            ? "Идёт эфир · " + currentSourceName : "Поток готов · " + currentSourceName);
                } else if (state == Player.STATE_ENDED) {
                    handlePlaybackFailure("поток завершился");
                }
                updateNotification();
            }

            @Override public void onPlayerError(PlaybackException error) {
                handlePlaybackFailure(error.getErrorCodeName());
            }
        });
    }

    @Nullable @Override public IBinder onBind(Intent intent) { return binder; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        String action = intent.getAction();
        if (ACTION_PLAY.equals(action)) {
            String url = intent.getStringExtra(EXTRA_URL);
            String referer = intent.getStringExtra(EXTRA_REFERER);
            String sourceName = intent.getStringExtra(EXTRA_SOURCE_NAME);
            int quality = intent.getIntExtra(EXTRA_MAX_AUDIO_BITRATE, maxAudioBitrateKbps);
            boolean isMp3 = intent.getBooleanExtra(EXTRA_IS_MP3, false);
            if (!isAllowedStream(url, isMp3)) {
                notifyListener(false, "SOURCE_ERROR: недопустимый адрес потока");
                return START_NOT_STICKY;
            }
            if (!isMp3 || !url.equals(currentUrl)) radioReconnectAttempts = 0;
            currentUrl = url;
            currentIsMp3 = isMp3;
            maxAudioBitrateKbps = quality;
            if (referer != null && !referer.trim().isEmpty()) currentReferer = referer;
            if (sourceName != null && !sourceName.trim().isEmpty()) currentSourceName = sourceName;
            applyTrackSelectionParameters();
            startAsForeground();
            playStream(currentUrl);
        } else if (ACTION_TOGGLE.equals(action)) {
            togglePlayback();
        } else if (ACTION_STOP.equals(action)) {
            stopPlaybackAndService();
        } else if (ACTION_SET_QUALITY.equals(action)) {
            maxAudioBitrateKbps = intent.getIntExtra(EXTRA_MAX_AUDIO_BITRATE, maxAudioBitrateKbps);
            applyTrackSelectionParameters();
        }
        return START_NOT_STICKY;
    }

    public boolean isPlaying() { return player != null && player.isPlaying(); }
    public boolean hasStream() { return player != null && player.getMediaItemCount() > 0 && !currentUrl.isEmpty(); }
    public void setPlaybackStateListener(PlaybackStateListener value) { listener = value; }
    public void clearPlaybackStateListener() { listener = null; }

    public void setMaxAudioBitrate(int kbps) {
        maxAudioBitrateKbps = kbps;
        applyTrackSelectionParameters();
    }

    public void togglePlayback() {
        if (player == null || player.getMediaItemCount() == 0) return;
        if (player.isPlaying()) player.pause(); else player.play();
        updateNotification();
    }

    public void stopPlayback() { stopPlaybackAndService(); }

    private void applyTrackSelectionParameters() {
        if (trackSelector == null) return;
        int maxBitrate = (currentIsMp3 || maxAudioBitrateKbps <= 0)
                ? Integer.MAX_VALUE : maxAudioBitrateKbps * 1000;
        DefaultTrackSelector.Parameters parameters = trackSelector.buildUponParameters()
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
                .setMaxAudioBitrate(maxBitrate)
                .build();
        trackSelector.setParameters(parameters);
    }

    private void playStream(String url) {
        try {
            handler.removeCallbacks(connectionTimeout);
            Map<String, String> headers = new HashMap<>();
            headers.put("Referer", currentReferer);
            headers.put("Origin", currentReferer.startsWith("https://rutube.ru") ? "https://rutube.ru" : (currentReferer.startsWith("https://radiokp.ru") ? "https://radiokp.ru" : "https://vkvideo.ru"));
            headers.put("Accept", "*/*");
            DefaultHttpDataSource.Factory httpFactory = new DefaultHttpDataSource.Factory()
                    .setUserAgent(USER_AGENT)
                    .setAllowCrossProtocolRedirects(true)
                    .setDefaultRequestProperties(headers);
            if (currentIsMp3) {
                MediaItem item = new MediaItem.Builder().setUri(url).setMimeType(MimeTypes.AUDIO_MPEG).build();
                ProgressiveMediaSource source = new ProgressiveMediaSource.Factory(httpFactory).createMediaSource(item);
                player.setMediaSource(source);
            } else {
                MediaItem item = new MediaItem.Builder().setUri(url).build();
                HlsMediaSource source = new HlsMediaSource.Factory(httpFactory).createMediaSource(item);
                player.setMediaSource(source);
            }
            player.prepare();
            player.play();
            updateNotification();
            notifyListener(false, "Подключение к " + currentSourceName + "…");
            handler.postDelayed(connectionTimeout, 35000L);
        } catch (Exception e) {
            handlePlaybackFailure(e.getClass().getSimpleName());
        }
    }

    private boolean isAllowedStream(String url, boolean mp3) {
        if (url == null) return false;
        try {
            android.net.Uri uri = android.net.Uri.parse(url);
            String host = uri.getHost();
            String scheme = uri.getScheme();
            if (host == null || scheme == null) return false;
            host = host.toLowerCase(Locale.ROOT);
            if (mp3) {
                boolean allowedRadioHost = host.equals("kpradio32.streamr.ru") || host.equals("kpradio64.streamr.ru")
                        || host.equals("kpradio128.streamr.ru") || host.equals("kpradio.hostingradio.ru");
                return allowedRadioHost && (scheme.equals("http") || scheme.equals("https"));
            }
            if (!scheme.equals("https") || !url.toLowerCase(Locale.ROOT).contains(".m3u8")) return false;
            return host.endsWith(".okcdn.ru") || host.endsWith(".vkuservideo.net") || host.endsWith(".userapi.com")
                    || host.equals("vkvideo.ru") || host.endsWith(".vkvideo.ru") || host.equals("vk.com") || host.endsWith(".vk.com")
                    || host.equals("rutube.ru") || host.endsWith(".rutube.ru")
                    || host.equals("rtbcdn.ru") || host.endsWith(".rtbcdn.ru") || host.equals("rtbcdn.net") || host.endsWith(".rtbcdn.net");
        } catch (Exception e) { return false; }
    }

    private void handlePlaybackFailure(String reason) {
        if (currentIsMp3 && !currentUrl.isEmpty() && radioReconnectAttempts < MAX_RADIO_RECONNECT_ATTEMPTS) {
            radioReconnectAttempts++;
            final String retryUrl = currentUrl;
            notifyListener(false, "Радиопоток прервался. Пробую переподключиться…");
            if (player != null) {
                try { player.stop(); } catch (Exception ignored) { }
                try { player.clearMediaItems(); } catch (Exception ignored) { }
            }
            handler.removeCallbacks(connectionTimeout);
            long retryDelayMs = Math.min(1800L * radioReconnectAttempts, 5000L);
            handler.postDelayed(() -> {
                if (retryUrl.equals(currentUrl)) playStream(retryUrl);
            }, retryDelayMs);
            return;
        }
        failCurrentSource(reason);
    }

    private void failCurrentSource(String reason) {
        handler.removeCallbacks(connectionTimeout);
        String message = "SOURCE_ERROR: " + (reason == null ? "stream error" : reason);
        notifyListener(false, message);
        if (player != null) {
            try { player.stop(); } catch (Exception ignored) { }
            try { player.clearMediaItems(); } catch (Exception ignored) { }
        }
        currentUrl = "";
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE); else stopForeground(true);
        updateNotification();
        stopSelf();
    }

    private void startAsForeground() {
        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void stopPlaybackAndService() {
        handler.removeCallbacks(connectionTimeout);
        if (player != null) {
            player.stop();
            player.clearMediaItems();
        }
        currentUrl = "";
        notifyListener(false, "Остановлено");
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE); else stopForeground(true);
        stopSelf();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Радио КП — Прямой эфир", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Управление аудиотрансляцией Радио КП");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    private PendingIntent serviceAction(String action, int requestCode) {
        Intent intent = new Intent(this, AudioPlaybackService.class).setAction(action);
        return PendingIntent.getService(this, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent content = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        boolean playing = player != null && player.isPlaying();
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(playing ? android.R.drawable.ic_media_play : android.R.drawable.ic_media_pause)
                .setContentTitle("Радио КП — Прямой эфир")
                .setContentText(currentUrl.isEmpty() ? "Остановлено" : (playing ? "Идёт эфир · " + currentSourceName : "Пауза · " + currentSourceName))
                .setContentIntent(content)
                .setOnlyAlertOnce(true)
                .setOngoing(!currentUrl.isEmpty())
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .addAction(playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                        playing ? "Пауза" : "Продолжить", serviceAction(ACTION_TOGGLE, 1))
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Стоп", serviceAction(ACTION_STOP, 2));
        return builder.build();
    }

    private void updateNotification() {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null && !currentUrl.isEmpty()) manager.notify(NOTIFICATION_ID, buildNotification());
        else if (manager != null) manager.cancel(NOTIFICATION_ID);
    }

    private void notifyListener(boolean playing, String message) {
        PlaybackStateListener target = listener;
        if (target != null) {
            handler.post(() -> {
                if (listener == target) target.onPlaybackStateChanged(playing, message);
            });
        }
    }

    @Override public void onDestroy() {
        handler.removeCallbacks(connectionTimeout);
        if (player != null) player.release();
        player = null;
        super.onDestroy();
    }
}
