package com.marverenic.music;

import android.annotation.TargetApi;
import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.IBinder;
import android.os.RemoteException;
import android.support.v4.app.NotificationCompat;
import android.widget.RemoteViews;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.marverenic.music.activity.LibraryActivity;
import com.marverenic.music.instances.LibraryScanner;
import com.marverenic.music.instances.Song;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.List;

@SuppressWarnings("deprecation")
public class PlayerService extends Service {

    // Constants
    public static final int NOTIFICATION_ID = 1;
    public static final String ACTION_TOGGLE_PLAY = "toggle";
    public static final String ACTION_PAUSE = "pause";
    public static final String ACTION_PLAY = "play";
    public static final String ACTION_PREV = "previous";
    public static final String ACTION_NEXT = "next";
    public static final String ACTION_STOP = "stop";
    public static final String FILENAME_QUEUE_SAVE = ".queue";

    // Global instance values
    private static PlayerService instance; // The service instance in use (singleton)
    private static IBinder binder; // A binder for this service

    private NotificationManager notificationManager;
    private Player player; // The media player for the service
    private boolean finished = false;

    public PlayerService() {
    }

    @Override
    public void onCreate() {
        notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

        if (this != instance){
            if (instance != null) instance.stopSelf();
            instance = this;
        }

        if (player == null){
            player = new Player(this);
        }

        reload();
        if (player.getNowPlaying() != null) notifyNowPlaying();
    }

    public void reload(){
        // Attempt to read and restore the last player state
        try{
            File playlistJSON = new File(getExternalFilesDir(null), FILENAME_QUEUE_SAVE);
            if (playlistJSON.exists() && playlistJSON.length() > 0) {
                FileInputStream queueIn = new FileInputStream(playlistJSON);
                String queueGSON = LibraryScanner.convertStreamToString(queueIn);

                player.restoreState((Player.PlayerHolder)
                        new Gson().fromJson(
                                queueGSON,
                                new TypeToken<Player.PlayerHolder>() {}.getType()));
            }
        }
        catch (Exception e){
            e.printStackTrace();
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        if (binder == null) binder = new Stub(this);
        return binder;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        return super.onUnbind(intent);
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        notifyNowPlaying();
    }

    // Listens for commands from the notification
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null && (player == null || player.getNowPlaying() == null)){
            if (player == null) player = new Player(this);
            reload();
        }

        // Handle media control intents (usually from notifications)
        if (intent != null && intent.getAction() != null) {
            switch (intent.getAction()) {
                case ACTION_NEXT:
                    player.skip();
                    break;
                case ACTION_TOGGLE_PLAY:
                    saveState();
                    player.togglePlay();
                    break;
                case ACTION_PREV:
                    player.previous();
                    break;
                case ACTION_STOP:
                    stop();
                    return START_NOT_STICKY;
            }
        }

        if (!finished && player.getNowPlaying() != null){
            notifyNowPlaying();
        }
        return START_NOT_STICKY;
    }

    private void stop() {
        // If the UI process is still running, don't kill the process -- only remove its notification
        ActivityManager activityManager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        List<ActivityManager.RunningAppProcessInfo> procInfos = activityManager.getRunningAppProcesses();
        for(int i = 0; i < procInfos.size(); i++){
            if(procInfos.get(i).processName.equals(BuildConfig.APPLICATION_ID)){
                player.pause();
                stopForeground(true);
                return;
            }
        }

        // If the UI process has already ended, kill the service and close the player
        finish();
        stopSelf();
    }

    public void finish() {
        // If you run this method twice, you're going to have a bad time
        if (!finished) {
            // Save the player state, then release all important objects
            saveState();
            player.finish();
            player = null;
            stopForeground(true);
            notificationManager.cancel(NOTIFICATION_ID);
            instance = null;
            binder = null;
            finished = true;
        }
    }

    public void saveState(){
        if (player.getNowPlaying() != null) {
            try {
                FileWriter playerStateWriter = new FileWriter(new File(getExternalFilesDir(null), FILENAME_QUEUE_SAVE));
                playerStateWriter.write(new Gson().toJson(player.getSaveState(), Player.PlayerHolder.class));
                playerStateWriter.close();
            }
            catch (Exception ignored) {
                ignored.printStackTrace();
            }
        }
    }

    // Generate a notification, choosing the proper API level notification builder
    public void notifyNowPlaying() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN)
            startForeground(NOTIFICATION_ID, getNotification());
        else
            startForeground(NOTIFICATION_ID, getNotificationCompat());
    }

    // Build a notification on API 15 devices
    @TargetApi(15)
    public Notification getNotificationCompat() {
        // Ensures extra compatibility

        Notification notification;

        // The intent for the button actions
        Intent intent = new Intent(this, PlayerService.class);

        // Use a custom view
        RemoteViews notificationView = new RemoteViews(getPackageName(), R.layout.notification);

        // Set the artwork for the notification
        if (player.getArt() != null) {
            notificationView.setImageViewBitmap(R.id.notificationIcon, player.getArt());
        }
        else {
            notificationView.setImageViewResource(R.id.notificationIcon, R.drawable.art_default);
        }

        // If the player is playing music, set the track info and the button intents
        if (player.getNowPlaying() != null) {
            notificationView.setTextViewText(R.id.notificationContentTitle, player.getNowPlaying().songName);
            notificationView.setTextViewText(R.id.notificationContentText, player.getNowPlaying().albumName);
            notificationView.setTextViewText(R.id.notificationSubText, player.getNowPlaying().artistName);

            notificationView.setOnClickPendingIntent(R.id.notificationSkipPrevious, PendingIntent.getService(this, 1, intent.setAction(ACTION_PREV), 0));
            notificationView.setOnClickPendingIntent(R.id.notificationSkipNext, PendingIntent.getService(this, 1, intent.setAction(ACTION_NEXT), 0));
            notificationView.setOnClickPendingIntent(R.id.notificationPause, PendingIntent.getService(this, 1, intent.setAction(ACTION_TOGGLE_PLAY), 0));
            notificationView.setOnClickPendingIntent(R.id.notificationStop, PendingIntent.getService(this, 1, intent.setAction(ACTION_STOP), 0));

        }
        else{
            // If the player isn't playing music, set the notification text to a hardcoded set of strings
            notificationView.setTextViewText(R.id.notificationContentTitle, "Nothing is playing");
            notificationView.setTextViewText(R.id.notificationContentText, "");
            notificationView.setTextViewText(R.id.notificationSubText, "");
        }

        // Update the play/pause button icon to reflect the player status
        if (!(player.isPlaying() || player.isPreparing())) {
            notificationView.setImageViewResource(R.id.notificationPause, R.drawable.ic_play);
        } else{
            notificationView.setImageViewResource(R.id.notificationPause, R.drawable.ic_pause);
        }

        // Build the notification
        notification = new NotificationCompat.Builder(this)
                .setSmallIcon(
                        ((player.isPlaying() || player.isPreparing())
                                ? R.drawable.ic_play_notification
                                : R.drawable.ic_pause_notification))
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(PendingIntent.getActivity(
                        getInstance(),
                        0,
                        new Intent(this, LibraryActivity.class).putExtra(LibraryActivity.START_NOW_PLAYING, true),
                        PendingIntent.FLAG_UPDATE_CURRENT))
                .build();

        // Manually set the expanded and compact views
        notification.contentView = notificationView;

        return notification;
    }

    // Build a notification on API <21, >15 devices
    @TargetApi(16)
    public Notification getNotification() {
        // Creates a notification on pre-Lollipop devices
        // The notification that will be returned
        Notification notification;

        // The intent for the button actions
        Intent intent = new Intent(this, PlayerService.class);

        // Create the compact view
        RemoteViews notificationView = new RemoteViews(this.getPackageName(), R.layout.notification);
        // Create the expanded view
        RemoteViews notificationViewExpanded = new RemoteViews(this.getPackageName(), R.layout.notification_expanded);

        // Set the artwork for the notification
        if (player.getArt() != null) {
            notificationView.setImageViewBitmap(R.id.notificationIcon, player.getArt());
            notificationViewExpanded.setImageViewBitmap(R.id.notificationIcon, player.getArt());
        }
        else {
            notificationView.setImageViewResource(R.id.notificationIcon, R.drawable.art_default);
            notificationViewExpanded.setImageViewResource(R.id.notificationIcon, R.drawable.art_default);
        }

        // If the player is playing music, set the track info and the button intents
        if (player.getNowPlaying() != null) {
            // Update the info for the compact view
            notificationView.setTextViewText(R.id.notificationContentTitle, player.getNowPlaying().songName);
            notificationView.setTextViewText(R.id.notificationContentText, player.getNowPlaying().albumName);
            notificationView.setTextViewText(R.id.notificationSubText, player.getNowPlaying().artistName);

            // Update the info for the expanded view
            notificationViewExpanded.setTextViewText(R.id.notificationContentTitle, player.getNowPlaying().songName);
            notificationViewExpanded.setTextViewText(R.id.notificationContentText, player.getNowPlaying().albumName);
            notificationViewExpanded.setTextViewText(R.id.notificationSubText, player.getNowPlaying().artistName);

            // Set the button intents for the compact view
            notificationView.setOnClickPendingIntent(R.id.notificationSkipPrevious, PendingIntent.getService(this, 1, intent.setAction(ACTION_PREV), 0));
            notificationView.setOnClickPendingIntent(R.id.notificationSkipNext, PendingIntent.getService(this, 1, intent.setAction(ACTION_NEXT), 0));
            notificationView.setOnClickPendingIntent(R.id.notificationPause, PendingIntent.getService(this, 1, intent.setAction(ACTION_TOGGLE_PLAY), 0));
            notificationView.setOnClickPendingIntent(R.id.notificationStop, PendingIntent.getService(this, 1, intent.setAction(ACTION_STOP), 0));

            // Set the button intents for the expanded view
            notificationViewExpanded.setOnClickPendingIntent(R.id.notificationSkipPrevious, PendingIntent.getService(this, 1, intent.setAction(ACTION_PREV), 0));
            notificationViewExpanded.setOnClickPendingIntent(R.id.notificationSkipNext, PendingIntent.getService(this, 1, intent.setAction(ACTION_NEXT), 0));
            notificationViewExpanded.setOnClickPendingIntent(R.id.notificationPause, PendingIntent.getService(this, 1, intent.setAction(ACTION_TOGGLE_PLAY), 0));
            notificationViewExpanded.setOnClickPendingIntent(R.id.notificationStop, PendingIntent.getService(this, 1, intent.setAction(ACTION_STOP), 0));

        }
        else{
            // If the player isn't playing music, set the notification text to a hardcoded set of strings
            notificationView.setTextViewText(R.id.notificationContentTitle, "Nothing is playing");
            notificationView.setTextViewText(R.id.notificationContentText, "");
            notificationView.setTextViewText(R.id.notificationSubText, "");

            notificationViewExpanded.setTextViewText(R.id.notificationContentTitle, "Nothing is playing");
            notificationViewExpanded.setTextViewText(R.id.notificationContentText, "");
            notificationViewExpanded.setTextViewText(R.id.notificationSubText, "");
        }

        boolean isLollipop = Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP;

        // Update the play/pause button icon to reflect the player status
        if (!(player.isPlaying() || player.isPreparing())) {
            if (isLollipop){
                notificationView.setImageViewResource(R.id.notificationPause, R.drawable.ic_vector_play_notification);
                notificationViewExpanded.setImageViewResource(R.id.notificationPause, R.drawable.ic_vector_play_notification);
            }
            else {
                notificationView.setImageViewResource(R.id.notificationPause, R.drawable.ic_play);
                notificationViewExpanded.setImageViewResource(R.id.notificationPause, R.drawable.ic_play);
            }
        } else{
            if (isLollipop){
                notificationView.setImageViewResource(R.id.notificationPause, R.drawable.ic_vector_pause_notification);
                notificationViewExpanded.setImageViewResource(R.id.notificationPause, R.drawable.ic_vector_pause_notification);
            }
            else {
                notificationView.setImageViewResource(R.id.notificationPause, R.drawable.ic_pause);
                notificationViewExpanded.setImageViewResource(R.id.notificationPause, R.drawable.ic_pause);
            }
        }

        // Build the notification
        Notification.Builder builder = new Notification.Builder(this)
                .setSmallIcon(
                        ((player.isPlaying() || player.isPreparing())
                                ? (isLollipop) ? R.drawable.ic_vector_play_notification_icon : R.drawable.ic_play_notification
                                : (isLollipop) ? R.drawable.ic_vector_pause_notification_icon : R.drawable.ic_pause_notification))
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(PendingIntent.getActivity(
                        getInstance(),
                        0,
                        new Intent(this, LibraryActivity.class).putExtra(LibraryActivity.START_NOW_PLAYING, true),
                        PendingIntent.FLAG_UPDATE_CURRENT));

        notification = builder.build();

        // Manually set the expanded and compact views
        notification.contentView = notificationView;
        notification.bigContentView = notificationViewExpanded;

        return notification;
    }

    // Get the active instance of the player service
    public static PlayerService getInstance() {
        return instance;
    }

    public static final class Stub extends IPlayerService.Stub {

        PlayerService service;

        private Stub (PlayerService service){
            this.service = service;
        }

        private static ArrayList<Song> cloneSongList(List<Song> songList){
            ArrayList<Song> clonedList = new ArrayList<>();
            for (Song s : songList) clonedList.add(new Song(s));
            return clonedList;
        }

        @Override
        public void setQueue(List<Song> newQueue, int newPosition) throws RemoteException {
            service.player.setQueue(cloneSongList(newQueue), newPosition);
        }

        @Override
        public void changeQueue(List<Song> newQueue, int newPosition) throws RemoteException {
            service.player.changeQueue(cloneSongList(newQueue), newPosition);
        }

        @Override
        public void queueNext(Song song) throws RemoteException {
            service.player.queueNext(new Song(song));
        }

        @Override
        public void queueLast(Song song) throws RemoteException {
            service.player.queueNext(new Song(song));
        }

        @Override
        public void queueNextList(List<Song> songs) throws RemoteException {
            service.player.queueNext(cloneSongList(songs));
        }

        @Override
        public void queueLastList(List<Song> songs) throws RemoteException {
            service.player.queueLast(cloneSongList(songs));
        }

        @Override
        public void begin() throws RemoteException {
            service.player.begin();
        }

        @Override
        public Song getNowPlaying() throws RemoteException {
            return service.player.getNowPlaying();
        }

        @Override
        public void togglePlay() throws RemoteException {
            service.saveState();
            service.player.togglePlay();
        }

        @Override
        public void play() throws RemoteException {
            service.player.play();
        }

        @Override
        public void pause() throws RemoteException {
            service.player.pause();
        }

        @Override
        public void stop() throws RemoteException {
            service.finish();
            service.stopSelf();
        }

        @Override
        public void previous() throws RemoteException {
            service.player.previous();
        }

        @Override
        public void skip() throws RemoteException {
            service.player.skip();
        }

        @Override
        public void seek(int position) throws RemoteException {
            service.player.seek(position);
        }

        @Override
        public void changeSong(int newPosition) throws RemoteException {
            service.player.changeSong(newPosition);
        }

        @Override
        public void toggleShuffle() throws RemoteException {
            service.player.toggleShuffle();
        }

        @Override
        public void toggleRepeat() throws RemoteException {
            service.player.toggleRepeat();
        }

        @Override
        public Bitmap getArt() throws RemoteException {
            return service.player.getArt();
        }

        @Override
        public Bitmap getFullArt() throws RemoteException {
            return service.player.getFullArt();
        }

        @Override
        public boolean isPlaying() throws RemoteException {
            return service.player.isPlaying();
        }

        @Override
        public boolean isPreparing() throws RemoteException {
            return service.player.isPreparing();
        }

        @Override
        public int getCurrentPosition() throws RemoteException {
            return service.player.getCurrentPosition();
        }

        @Override
        public int getDuration() throws RemoteException {
            return service.player.getDuration();
        }

        @Override
        public boolean isShuffle() throws RemoteException {
            return service.player.isShuffle();
        }

        @Override
        public boolean isRepeat() throws RemoteException {
            return service.player.isRepeat();
        }

        @Override
        public boolean isRepeatOne() throws RemoteException {
            return service.player.isRepeatOne();
        }

        @Override
        public List<Song> getQueue() throws RemoteException {
            return service.player.getQueue();
        }

        @Override
        public int getPosition() throws RemoteException {
            return service.player.getPosition();
        }
    }
}

