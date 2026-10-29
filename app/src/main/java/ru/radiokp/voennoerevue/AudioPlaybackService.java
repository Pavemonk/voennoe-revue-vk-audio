package ru.radiokp.voennoerevue;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/** Foreground audio-only playback service. The video track is explicitly disabled. */
public final class AudioPlaybackService extends Service {
    public static final String ACTION_PLAY = "ru.radiokp.voennoerevue.PLAY";
    public static final String ACTION_TOGGLE = "ru.radiokp.voennoerevue.TOGGLE";
    public static final String ACTION_STOP = "ru.radiokp.voennoerevue.STOP";
    public static final String EXTRA_URL = "stream_url";
    public static final String EXTRA_REFERER = "referer";

    private static final String CHANNEL_ID = "radio_kp_live_audio_v2";
    private static final int NOTIFICATION_ID = 6107;
    private static final String USER_AGENT = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36";

    public interface PlaybackStateListener {
        void onPlaybackStateChanged(boolean playing, String message);
    }

    public final class LocalBinder extends Binder {
        public AudioPlaybackService getService() { return AudioPlaybackService.this; }
    }

    private final IBinder binder = new LocalBinder();
    private ExoPlayer player;
    private DefaultTrackSelector trackSelector;
    private String currentUrl = "";
    private String currentReferer = "https://vkvideo.ru/";
    private PlaybackStateListener listener;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        trackSelector = new DefaultTrackSelector(this);
        trackSelector.setParameters(trackSelector.buildUponParameters()
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
                .build());
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
                notifyListener(isPlaying, isPlaying ? "Радио КП: идёт прямой эфир — только аудио" : "Воспроизведение приостановлено");
            }

            @Override public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_BUFFERING) {
                    notifyListener(player.isPlaying(), "Подключение к трансляции VK…");
                } else if (state == Player.STATE_READY) {
                    notifyListener(player.isPlaying(), player.isPlaying() ? "Радио КП: идёт прямой эфир — только аудио" : "Поток готов");
                } else if (state == Player.STATE_ENDED) {
                    notifyListener(false, "Трансляция завершена");
                }
                updateNotification();
            }

            @Override public void onPlayerError(PlaybackException error) {
                notifyListener(false, "Ошибка потока VK. Откройте эфир заново.");
                updateNotification();
            }
        });
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        String action = intent.getAction();
        if (ACTION_PLAY.equals(action)) {
            String url = intent.getStringExtra(EXTRA_URL);
            String referer = intent.getStringExtra(EXTRA_REFERER);
            if (!isAllowedStream(url)) {
                notifyListener(false, "VK не вернул распознаваемый аудиопоток");
                stopSelf(startId);
                return START_NOT_STICKY;
            }
            currentUrl = url;
            if (referer != null && !referer.trim().isEmpty()) currentReferer = referer;
            startAsForeground();
            playStream(currentUrl);
        } else if (ACTION_TOGGLE.equals(action)) {
            if (player != null && player.getMediaItemCount() > 0) {
                if (player.isPlaying()) player.pause(); else player.play();
            }
            updateNotification();
        } else if (ACTION_STOP.equals(action)) {
            stopPlaybackAndService();
        }
        return START_NOT_STICKY;
    }

    public boolean isPlaying() { return player != null && player.isPlaying(); }
    public boolean hasStream() { return player != null && player.getMediaItemCount() > 0; }
    public String getCurrentUrl() { return currentUrl; }
    public void setPlaybackStateListener(PlaybackStateListener value) { listener = value; }
    public void clearPlaybackStateListener() { listener = null; }

    public void togglePlayback() {
        if (player == null || player.getMediaItemCount() == 0) return;
        if (player.isPlaying()) player.pause(); else player.play();
        updateNotification();
    }

    public void stopPlayback() { stopPlaybackAndService(); }

    private void playStream(String url) {
        try {
            Map<String, String> headers = new HashMap<>();
            headers.put("Referer", currentReferer);
            headers.put("Origin", "https://vkvideo.ru");
            headers.put("Accept", "*/*");
            DefaultHttpDataSource.Factory httpFactory = new DefaultHttpDataSource.Factory()
                    .setUserAgent(USER_AGENT)
                    .setAllowCrossProtocolRedirects(true)
                    .setDefaultRequestProperties(headers);
            MediaItem item = new MediaItem.Builder().setUri(url).build();
            HlsMediaSource source = new HlsMediaSource.Factory(httpFactory).createMediaSource(item);
            player.setMediaSource(source);
            player.prepare();
            player.play();
            updateNotification();
            notifyListener(false, "Подключение к трансляции VK…");
        } catch (Exception e) {
            notifyListener(false, "Не удалось запустить поток VK: " + e.getClass().getSimpleName());
        }
    }

    private boolean isAllowedStream(String url) {
        if (url == null || !url.startsWith("https://") || !url.toLowerCase().contains(".m3u8")) return false;
        try {
            String host = android.net.Uri.parse(url).getHost();
            if (host == null) return false;
            host = host.toLowerCase();
            return host.endsWith(".okcdn.ru") || host.endsWith(".vkuservideo.net")
                    || host.endsWith(".userapi.com") || host.endsWith(".vkvideo.ru")
                    || host.endsWith(".vk.com");
        } catch (Exception e) { return false; }
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
        if (player != null) {
            player.stop();
            player.clearMediaItems();
        }
        currentUrl = "";
        notifyListener(false, "Остановлено");
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE);
        else stopForeground(true);
        stopSelf();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Радио КП — Прямой эфир", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Управление аудиотрансляцией VK Видео");
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
                .setContentText(playing ? "Идёт трансляция VK" : (currentUrl.isEmpty() ? "Остановлено" : "Пауза"))
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
        if (currentUrl.isEmpty()) return;
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) manager.notify(NOTIFICATION_ID, buildNotification());
    }

    private void notifyListener(boolean playing, String message) {
        PlaybackStateListener target = listener;
        if (target != null) {
            new android.os.Handler(getMainLooper()).post(() -> {
                if (listener == target) target.onPlaybackStateChanged(playing, message);
            });
        }
    }

    @Override
    public void onDestroy() {
        if (player != null) player.release();
        player = null;
        super.onDestroy();
    }
}
