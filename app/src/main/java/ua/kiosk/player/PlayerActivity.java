package ua.kiosk.player;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.provider.Settings;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Video kiosk for low-end Android TV boxes.
 * No AndroidX / no ExoPlayer on purpose: plain MediaPlayer + SurfaceView,
 * hardware decoding, ~60 KB APK, works fine with 1 GB RAM.
 */
public class PlayerActivity extends Activity
        implements SurfaceHolder.Callback,
        MediaPlayer.OnCompletionListener,
        MediaPlayer.OnErrorListener,
        MediaPlayer.OnPreparedListener,
        MediaPlayer.OnVideoSizeChangedListener {

    // ---------------- settings ----------------
    /** Folder with videos. First existing one is used. */
    private static final String[] DIRS = {
            "/sdcard/Movies",
            "/mnt/sdcard/Movies",
            "/storage/emulated/0/Movies",
            "/mnt/usb_storage/Movies",
            "/storage/external_storage/Movies"
    };
    private static final String[] EXT = {".mp4", ".mkv", ".avi", ".mov", ".m4v", ".ts", ".webm", ".3gp"};
    /** Set to true to play without sound. */
    private static final boolean MUTE = false;
    /** PIN for the hidden exit. */
    private static final String PIN = "1234";
    private static final int TAPS_TO_EXIT = 5;
    private static final long TAP_WINDOW_MS = 3000;
    // ------------------------------------------

    private static final int MSG_NEXT = 1;
    private static final int MSG_RESCAN = 2;

    private SurfaceView surface;
    private SurfaceHolder holder;
    private TextView hint;
    private FrameLayout root;

    private MediaPlayer mp;
    private final List<String> playlist = new ArrayList<String>();
    private int index = 0;
    private boolean surfaceReady = false;
    private boolean exiting = false;
    private int videoW = 0, videoH = 0;

    private int tapCount = 0;
    private long firstTapAt = 0;

    private final Handler handler = new Handler() {
        @Override public void handleMessage(Message msg) {
            if (exiting) return;
            if (msg.what == MSG_NEXT) {
                index++;
                playCurrent();
            } else if (msg.what == MSG_RESCAN) {
                scan();
                if (playlist.isEmpty()) {
                    handler.sendEmptyMessageDelayed(MSG_RESCAN, 10000);
                } else {
                    index = 0;
                    playCurrent();
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_FULLSCREEN
                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        setContentView(R.layout.activity_player);

        root = (FrameLayout) findViewById(R.id.root);
        surface = (SurfaceView) findViewById(R.id.surface);
        hint = (TextView) findViewById(R.id.hint);
        holder = surface.getHolder();
        holder.addCallback(this);
        holder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS); // needed on old Amlogic/Allwinner

        hideSystemUi();
        requestStoragePermissionIfNeeded();
    }

    private void requestStoragePermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 23) {
            String p = "android.permission.READ_EXTERNAL_STORAGE";
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{p}, 1);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        scanAndPlay();
    }

    // ---------------- playlist ----------------

    private File findDir() {
        for (String d : DIRS) {
            File f = new File(d);
            if (f.isDirectory()) return f;
        }
        File ext = new File(Environment.getExternalStorageDirectory(), "Movies");
        if (ext.isDirectory()) return ext;
        // last resort: Download folder
        File dl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if (dl != null && dl.isDirectory()) return dl;
        return null;
    }

    private void scan() {
        playlist.clear();
        File dir = findDir();
        if (dir == null) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        List<String> found = new ArrayList<String>();
        for (File f : files) {
            if (!f.isFile() || f.length() < 1024) continue;
            String n = f.getName().toLowerCase(Locale.US);
            for (String e : EXT) {
                if (n.endsWith(e)) { found.add(f.getAbsolutePath()); break; }
            }
        }
        Collections.sort(found);
        playlist.addAll(found);
    }

    private void scanAndPlay() {
        scan();
        if (playlist.isEmpty()) {
            showHint();
            handler.sendEmptyMessageDelayed(MSG_RESCAN, 10000);
        } else {
            hint.setVisibility(View.GONE);
            index = 0;
            playCurrent();
        }
    }

    private void showHint() {
        File dir = findDir();
        hint.setText("Нет видеофайлов.\nСкопируйте .mp4 в папку:\n"
                + (dir != null ? dir.getAbsolutePath() : "/sdcard/Movies")
                + "\n\nОжидание...");
        hint.setVisibility(View.VISIBLE);
    }

    // ---------------- playback ----------------

    private void playCurrent() {
        if (exiting || !surfaceReady) return;
        if (playlist.isEmpty()) { showHint(); return; }

        if (index >= playlist.size()) {
            index = 0;
            scan();                      // pick up newly copied files each loop
            if (playlist.isEmpty()) { showHint(); return; }
        }
        hint.setVisibility(View.GONE);

        try {
            if (mp == null) {
                mp = new MediaPlayer();
                mp.setOnCompletionListener(this);
                mp.setOnErrorListener(this);
                mp.setOnPreparedListener(this);
                mp.setOnVideoSizeChangedListener(this);
            } else {
                mp.reset();
            }
            mp.setDisplay(holder);
            mp.setDataSource(playlist.get(index));
            mp.setScreenOnWhilePlaying(true);
            mp.prepareAsync();
        } catch (Throwable t) {
            handler.sendEmptyMessageDelayed(MSG_NEXT, 1000);
        }
    }

    @Override
    public void onPrepared(MediaPlayer m) {
        if (MUTE) m.setVolume(0f, 0f);
        try { m.start(); } catch (Throwable t) { handler.sendEmptyMessageDelayed(MSG_NEXT, 1000); }
    }

    @Override
    public void onCompletion(MediaPlayer m) {
        index++;
        playCurrent();
    }

    @Override
    public boolean onError(MediaPlayer m, int what, int extra) {
        // broken/unsupported file — skip it
        try { m.reset(); } catch (Throwable ignored) {}
        handler.sendEmptyMessageDelayed(MSG_NEXT, 800);
        return true;
    }

    @Override
    public void onVideoSizeChanged(MediaPlayer m, int w, int h) {
        if (w > 0 && h > 0) { videoW = w; videoH = h; fitSurface(); }
    }

    /** Letterbox instead of stretching. */
    private void fitSurface() {
        if (videoW == 0 || videoH == 0 || root.getWidth() == 0) return;
        int sw = root.getWidth(), sh = root.getHeight();
        float vr = (float) videoW / videoH;
        float sr = (float) sw / sh;
        int w, h;
        if (vr > sr) { w = sw; h = (int) (sw / vr); }
        else { h = sh; w = (int) (sh * vr); }
        ViewGroup.LayoutParams lp = surface.getLayoutParams();
        if (lp instanceof FrameLayout.LayoutParams) {
            ((FrameLayout.LayoutParams) lp).gravity = android.view.Gravity.CENTER;
        }
        lp.width = w; lp.height = h;
        surface.setLayoutParams(lp);
    }

    // ---------------- surface ----------------

    @Override public void surfaceCreated(SurfaceHolder h) {
        surfaceReady = true;
        if (mp == null) scanAndPlay(); else { mp.setDisplay(h); }
    }

    @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int hh) { fitSurface(); }

    @Override public void surfaceDestroyed(SurfaceHolder h) {
        surfaceReady = false;
        if (mp != null) { try { mp.setDisplay(null); } catch (Throwable ignored) {} }
    }

    // ---------------- kiosk lock ----------------

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUi();
        if (mp != null && surfaceReady && !mp.isPlaying() && !exiting) {
            try { mp.start(); } catch (Throwable ignored) {}
        }
    }

    @Override
    public void onWindowFocusChanged(boolean has) {
        super.onWindowFocusChanged(has);
        if (has) hideSystemUi();
    }

    private void hideSystemUi() {
        if (Build.VERSION.SDK_INT >= 19) {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        } else if (Build.VERSION.SDK_INT >= 14) {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LOW_PROFILE);
        }
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        switch (code) {
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_HOME:
            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_APP_SWITCH:
            case KeyEvent.KEYCODE_SEARCH:
                return true;              // swallow
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_MEDIA_NEXT:
                index++; playCurrent(); return true;
            case KeyEvent.KEYCODE_VOLUME_UP:
            case KeyEvent.KEYCODE_VOLUME_DOWN:
                return super.onKeyDown(code, e);
        }
        return true;
    }

    @Override public void onBackPressed() { /* blocked */ }

    /** 5 taps in the top-left corner within 3 s -> PIN dialog. */
    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (ev.getAction() == MotionEvent.ACTION_DOWN) {
            boolean corner = ev.getX() < root.getWidth() * 0.2f && ev.getY() < root.getHeight() * 0.2f;
            long now = System.currentTimeMillis();
            if (corner) {
                if (now - firstTapAt > TAP_WINDOW_MS) { firstTapAt = now; tapCount = 1; }
                else tapCount++;
                if (tapCount >= TAPS_TO_EXIT) { tapCount = 0; askPin(); }
            } else {
                tapCount = 0;
            }
        }
        return true;
    }

    private void askPin() {
        final EditText in = new EditText(this);
        in.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        new AlertDialog.Builder(this)
                .setTitle("Служебный выход")
                .setView(in)
                .setPositiveButton("OK", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        if (PIN.equals(in.getText().toString())) doExit();
                    }
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void doExit() {
        exiting = true;
        release();
        try {
            startActivity(new Intent(Settings.ACTION_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Throwable ignored) {}
        finish();
    }

    private void release() {
        handler.removeMessages(MSG_NEXT);
        handler.removeMessages(MSG_RESCAN);
        if (mp != null) {
            try { mp.reset(); mp.release(); } catch (Throwable ignored) {}
            mp = null;
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mp != null && !exiting) { try { mp.pause(); } catch (Throwable ignored) {} }
    }

    @Override
    protected void onDestroy() {
        release();
        super.onDestroy();
    }
}
