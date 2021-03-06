package com.liadpaz.amp.service;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.Icon;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaDescription;
import android.media.MediaMetadata;
import android.media.MediaPlayer;
import android.media.browse.MediaBrowser;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.ResultReceiver;
import android.provider.MediaStore;
import android.service.media.MediaBrowserService;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;
import androidx.lifecycle.Observer;

import com.liadpaz.amp.MainActivity;
import com.liadpaz.amp.R;
import com.liadpaz.amp.livedatautils.ColorUtil;
import com.liadpaz.amp.livedatautils.QueueUtil;
import com.liadpaz.amp.livedatautils.SongsUtil;
import com.liadpaz.amp.notification.MediaNotification;
import com.liadpaz.amp.utils.Constants;
import com.liadpaz.amp.utils.Utilities;
import com.liadpaz.amp.viewmodels.Song;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class MediaPlayerService extends MediaBrowserService {
    private static final String TAG = "AmpApp.MediaPlayerService";
    private static final String LOG_TAG = "AmpApp2.MediaSessionLog";

    private static final int NOTIFICATION_ID = 273;

    // task executor instance
    private Executor TASK_EXECUTOR;

    // mediaplayer, media session and becoming noisy receiver instances
    private MediaPlayer mediaPlayer;
    private MediaSession mediaSession;
    private BecomingNoisyReceiver becomingNoisyReceiver;

    // media session playback state/metadata variables
    private MediaMetadata.Builder metadataBuilder;
    private PlaybackState.Builder playbackBuilder;

    // audio focus related variables
    private AudioManager audioManager;
    private AudioFocusRequest audioFocusRequest;
    private AudioAttributes audioAttributes;
    private boolean resumeOnFocusGain = false;

    private boolean isLooping = false;

    // media sources variables
    private Song currentSource;
    private int queuePosition = -1;
    private ArrayList<Song> queue = new ArrayList<>();

    // observers
    private Observer<ArrayList<Song>> observerQueue;
    private Observer<Integer> observerPosition;

    private void setQueuePosition(int position) {
        QueueUtil.setPosition(position);
    }

    @Override
    public void onCreate() {
        super.onCreate();

        // initialize the task executor
        TASK_EXECUTOR = Executors.newSingleThreadExecutor();

        // observe to queue and queue position changes and act accordingly
        QueueUtil.observeQueue(observerQueue = songs -> {
            Log.d(TAG, "queue changed: " + queue.size());
            queue = songs;
            if (queue.size() > 0 && currentSource == null && queuePosition != -1) {
                TASK_EXECUTOR.execute(() -> setSource(queue.get(queuePosition)));
                TASK_EXECUTOR.execute(this::onPlay);
            }
            if (QueueUtil.getIsChanging()) {
                QueueUtil.setIsChanging(false);
            }
        });
        QueueUtil.observePosition(observerPosition = queuePosition -> {
            if (queuePosition != -1) {
                Log.d(TAG, "queuePosition changed: " + queuePosition);
                this.queuePosition = queuePosition;
                if (QueueUtil.getIsChanging()) {
                    QueueUtil.setIsChanging(false);
                } else {
                    TASK_EXECUTOR.execute(() -> setSource(queue.get(queuePosition)));
                    TASK_EXECUTOR.execute(this::onPlay);
                }
            }
        });

        // initializes the audio manager (for audio focus requests) and the audio attributes for the media player
        audioManager = (AudioManager)getSystemService(Context.AUDIO_SERVICE);
        audioAttributes = new AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).setUsage(AudioAttributes.USAGE_MEDIA).build();

        // initializes the media session
        mediaSession = new MediaSession(getApplicationContext(), LOG_TAG);
        mediaSession.setSessionActivity(PendingIntent.getActivity(getApplicationContext(), 10, new Intent(getApplicationContext(), MainActivity.class).putExtra(Constants.PREFERENCES_SHOW_CURRENT, 0), PendingIntent.FLAG_UPDATE_CURRENT));
        mediaSession.setCallback(new MediaSession.Callback() {
            @Override
            public void onCommand(@NonNull String command, Bundle extras, ResultReceiver cb) {
                switch (command) {
                    case Constants.LOOP_EXTRA: {
                        setLooping(extras.getBoolean(Constants.LOOP_EXTRA));
                        sendPlaybackState(mediaPlayer.getCurrentPosition(), mediaPlayer.isPlaying() ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED);
                        break;
                    }

                    case Constants.ACTION_RESET: {
                        mediaPlayer.pause();
                        ColorUtil.setColor(Color.BLACK);
                        sendMetadata(null);
                        sendPlaybackState(0, PlaybackState.STATE_STOPPED);
                        stopForeground(true);
                        break;
                    }
                }
            }

            @Override
            public void onPlay() {
                TASK_EXECUTOR.execute(MediaPlayerService.this::onPlay);
            }

            @Override
            public void onPause() {
                TASK_EXECUTOR.execute(MediaPlayerService.this::onPause);
            }

            @Override
            public void onStop() {
                resumeOnFocusGain = false;
                mediaPlayer.stop();
                mediaSession.setActive(false);
                if (audioFocusRequest != null) {
                    audioManager.abandonAudioFocusRequest(audioFocusRequest);
                }
                currentSource = null;
                sendPlaybackState(0, PlaybackState.STATE_STOPPED);
                sendMetadata(null);
            }

            @SuppressWarnings("ConstantConditions")
            @Override
            public void onSkipToNext() {
                if (mediaSession.getController().getPlaybackState().getState() != PlaybackState.STATE_NONE) {
                    if (queue.size() > 0) {
                        if (queuePosition == queue.size() - 1) {
                            setQueuePosition(0);
                        } else {
                            setQueuePosition(queuePosition + 1);
                        }
                    }
                }
            }

            @SuppressWarnings("ConstantConditions")
            @Override
            public void onSkipToPrevious() {
                if (mediaSession.getController().getPlaybackState().getState() != PlaybackState.STATE_NONE) {
                    if (TimeUnit.MILLISECONDS.toSeconds(mediaPlayer.getCurrentPosition()) > 2) {
                        onSeekTo(0);
                    } else if (queue.size() > 0) {
                        if (queuePosition == 0) {
                            setQueuePosition(queue.size() - 1);
                        } else {
                            setQueuePosition(queuePosition - 1);
                        }
                    }
                }
            }

            @SuppressWarnings("ConstantConditions")
            @Override
            public void onSeekTo(long pos) {
                if (mediaSession.getController().getPlaybackState().getState() != PlaybackState.STATE_NONE) {
                    mediaPlayer.seekTo((int)pos);
                    if (resumeOnFocusGain) {
                        mediaPlayer.start();
                    }
                    sendPlaybackState(mediaPlayer.getCurrentPosition(), mediaPlayer.isPlaying() ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED);
                }
            }
        });

        // initializes the metadata builder (for sending metadata to the media browser)
        metadataBuilder = new MediaMetadata.Builder();
        mediaSession.setMetadata(metadataBuilder.build());

        // initializes the playback state builder (for sending playback state to the media browser)
        playbackBuilder = new PlaybackState.Builder();
        sendPlaybackState(0, PlaybackState.STATE_STOPPED);

        // sets the session token of the current service
        setSessionToken(mediaSession.getSessionToken());

        // register the becoming noisy receiver
        becomingNoisyReceiver = new BecomingNoisyReceiver(getApplicationContext(), mediaSession.getController());

        // initializes the media player and set the on complete listener and audio attributes
        mediaPlayer = new MediaPlayer();
        mediaPlayer.setOnCompletionListener(mp -> {
            if (!isLooping) {
                mediaSession.getController().getTransportControls().skipToNext();
            } else {
                mediaSession.getController().getTransportControls().seekTo(0);
            }
        });
        mediaPlayer.setAudioAttributes(audioAttributes);
        mediaPlayer.setOnErrorListener((mp, what, extra) -> {
            Log.d(TAG, "MediaPlayer Error:\nwhat = " + what + "\nextra = " + extra);
            return false;
        });
    }

    /**
     * This function starts the playback and request an audio focus and registers an audio focus
     * change listener.
     * <p>
     * On an event of audio focus change the function will pause/start/lower the volume depends on
     * the new audio focus state.
     */
    private void onPlay() {
        audioManager.requestAudioFocus(audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setOnAudioFocusChangeListener(focusChange -> {
            switch (focusChange) {
                case AudioManager.AUDIOFOCUS_GAIN: {
                    if (resumeOnFocusGain) {
                        synchronized (this) {
                            resumeOnFocusGain = false;
                            mediaPlayer.setVolume(1F, 1F);
                            play();
                        }
                    }
                    break;
                }

                case AudioManager.AUDIOFOCUS_LOSS: {
                    synchronized (this) {
                        resumeOnFocusGain = false;
                    }
                    pause();
                    break;
                }

                case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK: {
                    mediaPlayer.setVolume(0.2F, 0.2F);
                    break;
                }

                case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT: {
                    synchronized (this) {
                        resumeOnFocusGain = mediaPlayer.isPlaying();
                    }
                    pause();
                    break;
                }
            }
        }).setAudioAttributes(audioAttributes).build());
        play();
    }

    /**
     * This function starts the playback and notifies the needed listeners.
     */
    private void play() {
        if (mediaPlayer != null) {
            mediaPlayer.setVolume(1F, 1F);
            synchronized (this) {
                resumeOnFocusGain = true;
            }
            becomingNoisyReceiver.register();
            mediaPlayer.start();
            sendPlaybackState(mediaPlayer.getCurrentPosition(), PlaybackState.STATE_PLAYING);
            startNotification();
        }
    }

    /**
     * This function abandons audio focus and pauses the playback.
     */
    private void onPause() {
        resumeOnFocusGain = false;
        pause();
    }

    /**
     * This function pauses the playback and notifies the needed listeners.
     */
    private void pause() {
        becomingNoisyReceiver.unregister();
        if (mediaPlayer != null) {
            mediaPlayer.pause();
            sendPlaybackState(mediaPlayer.getCurrentPosition(), PlaybackState.STATE_PAUSED);
            startNotification();
        }
    }

    /**
     * This function sets the looping state of the player to either <i>REPEAT_ONE</i> or
     * <i>REPEAT</i> and notifies the needed listeners.
     *
     * @param isLooping {@code true} to <i>REPEAT_ONE</i>, otherwise {@code false}
     */
    private void setLooping(boolean isLooping) {
        this.isLooping = isLooping;
        sendPlaybackState(mediaPlayer.getCurrentPosition(), mediaPlayer.isPlaying() ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED);
        startNotification();
    }

    /**
     * This function sends the metadata to all active {@link MediaBrowser}s.
     *
     * @param song the source of the metadata.
     */
    private void sendMetadata(@Nullable Song song) {
        if (song != null) {
            metadataBuilder.putText(MediaMetadata.METADATA_KEY_TITLE, song.title).putText(MediaMetadata.METADATA_KEY_ARTIST, Utilities.joinArtists(song.artists)).putText(MediaMetadata.METADATA_KEY_ALBUM, song.album).putString(MediaMetadata.METADATA_KEY_ART_URI, Utilities.getCoverUri(song).toString()).putLong(MediaMetadata.METADATA_KEY_DURATION, mediaPlayer.getDuration());
            mediaSession.setMetadata(metadataBuilder.build());
        } else {
            mediaSession.setMetadata(null);
        }
    }

    /**
     * This function send the playback to all active {@link MediaBrowser}s.
     *
     * @param position the position of the playback
     * @param state    the state of the playback
     * @see PlaybackState
     */
    private void sendPlaybackState(int position, int state) {
        playbackBuilder.setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_SEEK_TO | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS).setState(state, position, 1F);
        Bundle bundle = new Bundle();
        bundle.putBoolean(Constants.LOOP_EXTRA, isLooping);
        playbackBuilder.setExtras(bundle);
        mediaSession.setPlaybackState(playbackBuilder.build());
    }

    @SuppressWarnings("ConstantConditions")
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            if (intent.hasExtra(Constants.ACTION)) {
                switch (intent.getStringExtra(Constants.ACTION)) {
                    case Constants.ACTION_SKIP_PREV: {
                        mediaSession.getController().getTransportControls().skipToPrevious();
                        break;
                    }

                    case Constants.ACTION_PAUSE: {
                        mediaSession.getController().getTransportControls().pause();
                        break;
                    }

                    case Constants.ACTION_PLAY: {
                        mediaSession.getController().getTransportControls().play();
                        break;
                    }

                    case Constants.ACTION_SKIP_NEXT: {
                        mediaSession.getController().getTransportControls().skipToNext();
                        break;
                    }
                }
            } else if (intent.hasExtra(Constants.LOOP_EXTRA)) {
                setLooping(intent.getExtras().getBoolean(Constants.LOOP_EXTRA, false));
            }
        }
        return START_STICKY;
    }

    /**
     * This function sets the source of the {@link MediaPlayer} instance of the service.
     *
     * @param song the {@link Song} to set the source as.
     */
    @WorkerThread
    private void setSource(Song song) {
        try {
            if (audioFocusRequest != null) {
                audioManager.abandonAudioFocusRequest(audioFocusRequest);
            }
            mediaPlayer.reset();
            mediaPlayer.setDataSource(getApplicationContext(), ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, (currentSource = song).songId));
            mediaPlayer.prepare();
            sendMetadata(currentSource);
            Log.d(TAG, "setSource: " + song.title + "[" + song.songId + "]");
        } catch (Exception e) {
            Log.e(TAG, "setSource: ", e);
        }
    }

    /**
     * This function starts the notification for the service, it's taking the state of the player to
     * show the user the correct notification.
     */
    private void startNotification() {
        final boolean isPlaying = mediaPlayer.isPlaying();

        final Notification.Builder builder = MediaNotification.from(getApplicationContext(), mediaSession);

        if (builder != null) {
            CompletableFuture.supplyAsync(() -> BitmapFactory.decodeStream(Utilities.getInputStream(getApplicationContext(), currentSource))).thenAcceptAsync(cover -> {
                if (cover != null) {
                    startForeground(NOTIFICATION_ID, builder.setLargeIcon(cover).build());
                } else {
                    startForeground(NOTIFICATION_ID, builder.setLargeIcon(Icon.createWithResource(getApplicationContext(), R.drawable.song)).build());
                }
                if (!isPlaying) {
                    stopForeground(false);
                }
            });
        }
    }

    @NonNull
    @Override
    public BrowserRoot onGetRoot(@NonNull String clientPackageName, int clientUid, @Nullable Bundle rootHints) { return new BrowserRoot(getString(R.string.app_name), null); }

    @Override
    public void onLoadChildren(@NonNull String parentId, @NonNull Result<List<MediaBrowser.MediaItem>> result) {
        List<MediaBrowser.MediaItem> mediaItems = new ArrayList<>();
        for (Song song : SongsUtil.getSongs()) {
            mediaItems.add(new MediaBrowser.MediaItem(new MediaDescription.Builder().setIconUri(Utilities.getCoverUri(song)).setTitle(song.title).setSubtitle(Utilities.joinArtists(song.artists)).setDescription(song.album).build(), MediaBrowser.MediaItem.FLAG_PLAYABLE));
        }
        result.sendResult(mediaItems);
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "onDestroy: destroying");
        sendMetadata(null);
        sendPlaybackState(0, PlaybackState.STATE_STOPPED);
        if (audioFocusRequest != null) {
            audioManager.abandonAudioFocusRequest(audioFocusRequest);
        }
        becomingNoisyReceiver.unregister();
        mediaPlayer.reset();
        mediaPlayer.release();
        mediaSession.release();
        if (observerPosition != null) {
            QueueUtil.removePositionObserver(observerPosition);
            observerPosition = null;
        }
        if (observerQueue != null) {
            QueueUtil.removeQueueObserver(observerQueue);
            observerQueue = null;
        }
        QueueUtil.setPosition(-1);
        QueueUtil.setQueue(new ArrayList<>());
    }

    /**
     * This class is for the <i>Becoming Noisy</i> broadcast, eg. when the user hears music with
     * earphones and the earphones disconnects.
     * <p>
     * It's stopping the playback when it receives that broadcast.
     */
    private static class BecomingNoisyReceiver extends BroadcastReceiver {
        private IntentFilter noisyFilter = new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);

        private Context context;
        private MediaController controller;

        private boolean registered = false;

        public BecomingNoisyReceiver(@NonNull Context context, @NonNull MediaController controller) {
            this.context = context;
            this.controller = controller;
        }

        @Override
        public void onReceive(@NonNull Context context, @NonNull Intent intent) {
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction())) {
                controller.getTransportControls().pause();
            }
        }

        public void register() {
            if (!registered) {
                context.registerReceiver(this, noisyFilter);
                registered = true;
            }
        }

        public void unregister() {
            if (registered) {
                context.unregisterReceiver(this);
                registered = false;
            }
        }
    }
}
