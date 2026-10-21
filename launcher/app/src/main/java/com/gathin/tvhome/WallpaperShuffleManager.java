package com.gathin.tvhome;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.media.ExifInterface;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Manages background wallpapers with smooth crossfade and idle shuffling.
 * - Triggers on TV wake / return to Home (onResume).
 * - Shuffles every 30 seconds when idle.
 * - Pauses completely when in background (zero CPU/battery when in other apps).
 * - Resets 30s idle timer on remote key interaction.
 * - Decodes asynchronously on a background thread using RGB_565 to preserve RAM (~4 MB).
 * - Recycles previous bitmaps to prevent memory leaks / OOM.
 */
public class WallpaperShuffleManager {
    private static final String TAG = "WallpaperShuffle";
    private static final long DEFAULT_IDLE_INTERVAL_MS = 15_000L;
    private static final long FADE_DURATION_MS = 600L;

    private final Activity activity;
    private final FrameLayout container;
    private final ImageView wallA;
    private final ImageView wallB;
    private final View scrim;
    private final File singleWallpaper;
    private final long idleIntervalMs;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    public interface OnWallpaperChangeListener {
        void onWallpaperChanged(String caption);
    }

    private OnWallpaperChangeListener wallpaperChangeListener;
    private String currentCaption = null;

    private ImageView currentWall;
    private ImageView nextWall;
    private final List<File> playlist = new ArrayList<>();
    private int playlistIndex = 0;
    private boolean isPaused = true;
    private boolean isTransitioning = false;
    private File lastShownFile = null;

    public void setOnWallpaperChangeListener(OnWallpaperChangeListener listener) {
        this.wallpaperChangeListener = listener;
    }

    public String getCurrentCaption() {
        return currentCaption;
    }

    private final Runnable idleRunnable = new Runnable() {
        @Override
        public void run() {
            if (isPaused) return;
            shuffleNext(true);
            handler.postDelayed(this, idleIntervalMs);
        }
    };

    public WallpaperShuffleManager(Activity activity, FrameLayout root, File singleWallpaper, int backgroundColor) {
        this(activity, root, singleWallpaper, backgroundColor, 15);
    }

    public WallpaperShuffleManager(Activity activity, FrameLayout root, File singleWallpaper, int backgroundColor, int shuffleSeconds) {
        this.activity = activity;
        this.singleWallpaper = singleWallpaper;
        this.idleIntervalMs = shuffleSeconds > 0 ? (shuffleSeconds * 1000L) : DEFAULT_IDLE_INTERVAL_MS;

        container = new FrameLayout(activity);
        container.setBackgroundColor(backgroundColor);

        wallA = new ImageView(activity);
        wallA.setScaleType(ImageView.ScaleType.FIT_CENTER);
        wallA.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        wallB = new ImageView(activity);
        wallB.setScaleType(ImageView.ScaleType.FIT_CENTER);
        wallB.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        wallB.setAlpha(0f);

        // Apple TV-style dark scrim so white clock, date, and pill buttons stay crisp and readable
        scrim = new View(activity);
        scrim.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        scrim.setBackgroundColor(0x40000000); // 25% black scrim

        container.addView(wallA);
        container.addView(wallB);
        container.addView(scrim);

        currentWall = wallA;
        nextWall = wallB;

        // Insert behind all launcher UI views (index 0 of root)
        root.addView(container, 0, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        scanWallpapers();
    }

    /** Discovers photos in external files dir and Pictures/wallpapers. */
    public void scanWallpapers() {
        playlist.clear();
        playlistIndex = 0;

        List<File> searchDirs = new ArrayList<>();
        File appDir = Config.dir(activity);
        if (appDir != null) {
            searchDirs.add(new File(appDir, "wallpapers"));
        }
        File picturesDir = new File(Environment.getExternalStorageDirectory(), "Pictures/wallpapers");
        searchDirs.add(picturesDir);

        for (File dir : searchDirs) {
            if (dir.exists() && dir.isDirectory()) {
                File[] files = dir.listFiles();
                if (files != null) {
                    for (File f : files) {
                        String name = f.getName().toLowerCase();
                        if (f.isFile() && f.length() > 0 &&
                                (name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png") || name.endsWith(".webp"))) {
                            playlist.add(f);
                        }
                    }
                }
            }
        }

        if (!playlist.isEmpty()) {
            Collections.shuffle(playlist);
            if (playlist.size() > 1 && playlist.get(0).equals(lastShownFile)) {
                Collections.swap(playlist, 0, 1);
            }
            scrim.setVisibility(View.VISIBLE);
        } else if (singleWallpaper != null && singleWallpaper.isFile() && singleWallpaper.length() > 0) {
            playlist.add(singleWallpaper);
            scrim.setVisibility(View.VISIBLE);
        } else {
            scrim.setVisibility(View.GONE);
        }
    }

    public boolean hasWallpapers() {
        return !playlist.isEmpty();
    }

    /** Triggered on TV wake / return to Home screen. */
    public void onResume() {
        isPaused = false;
        scanWallpapers();

        if (!playlist.isEmpty()) {
            shuffleNext(lastShownFile != null);
            resetIdleTimer();
        }
    }

    /** Triggered when opening an app or going into standby. */
    public void onPause() {
        isPaused = true;
        handler.removeCallbacks(idleRunnable);
    }

    /** Triggered on remote D-pad / button interaction: reset the 30-second idle countdown. */
    public void onUserInteraction() {
        if (!isPaused && playlist.size() > 1) {
            resetIdleTimer();
        }
    }

    public void onDestroy() {
        isPaused = true;
        handler.removeCallbacks(idleRunnable);
        executor.shutdownNow();
        wallA.animate().cancel();
        wallB.animate().cancel();
        recycleView(wallA);
        recycleView(wallB);
    }

    private void resetIdleTimer() {
        handler.removeCallbacks(idleRunnable);
        if (playlist.size() > 1) {
            handler.postDelayed(idleRunnable, idleIntervalMs);
        }
    }

    private static class DecodedResult {
        final Bitmap bitmap;
        final String caption;

        DecodedResult(Bitmap bitmap, String caption) {
            this.bitmap = bitmap;
            this.caption = caption;
        }
    }

    private synchronized void shuffleNext(boolean animate) {
        if (playlist.isEmpty() || isTransitioning) return;
        if (playlist.size() == 1 && lastShownFile != null) return;

        if (playlistIndex >= playlist.size()) {
            Collections.shuffle(playlist);
            if (playlist.size() > 1 && playlist.get(0).equals(lastShownFile)) {
                Collections.swap(playlist, 0, 1);
            }
            playlistIndex = 0;
        }

        File fileToLoad = playlist.get(playlistIndex++);
        lastShownFile = fileToLoad;
        isTransitioning = true;

        DisplayMetrics dm = activity.getResources().getDisplayMetrics();
        int screenW = dm.widthPixels;
        int screenH = dm.heightPixels;
        if (is4kDisplay()) {
            screenW = Math.max(screenW, 3840);
            screenH = Math.max(screenH, 2160);
        }

        final int targetW = screenW;
        final int targetH = screenH;

        executor.execute(() -> {
            DecodedResult result = decodeSampled(fileToLoad, targetW, targetH);
            activity.runOnUiThread(() -> {
                if (isPaused || activity.isFinishing() || activity.isDestroyed()) {
                    if (result != null && result.bitmap != null) result.bitmap.recycle();
                    isTransitioning = false;
                    return;
                }

                if (result == null || result.bitmap == null) {
                    isTransitioning = false;
                    return;
                }

                applyBitmap(result.bitmap, result.caption, animate);
            });
        });
    }

    private boolean is4kDisplay() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Display display = activity.getWindowManager().getDefaultDisplay();
                if (display != null && display.getSupportedModes() != null) {
                    for (Display.Mode mode : display.getSupportedModes()) {
                        if (mode.getPhysicalWidth() >= 3840 || mode.getPhysicalHeight() >= 2160) {
                            return true;
                        }
                    }
                }
            }
            String model = Build.MODEL;
            if (model != null && (model.contains("4K") || model.contains("4k"))) {
                return true;
            }
            String product = Build.PRODUCT;
            if (product != null && (product.contains("4K") || product.contains("4k"))) {
                return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private void applyBitmap(Bitmap newBmp, String caption, boolean animate) {
        currentCaption = caption;
        if (wallpaperChangeListener != null) {
            wallpaperChangeListener.onWallpaperChanged(caption);
        }

        if (!animate) {
            recycleView(currentWall);
            currentWall.setImageBitmap(newBmp);
            currentWall.setAlpha(1f);
            nextWall.setAlpha(0f);
            recycleView(nextWall);
            isTransitioning = false;
            return;
        }

        currentWall.animate().alpha(0f).setDuration(FADE_DURATION_MS).start();
        nextWall.bringToFront();
        scrim.bringToFront();
        nextWall.setImageBitmap(newBmp);
        nextWall.setAlpha(0f);
        nextWall.animate()
                .alpha(1f)
                .setDuration(FADE_DURATION_MS)
                .withEndAction(() -> {
                    recycleView(currentWall);

                    ImageView temp = currentWall;
                    currentWall = nextWall;
                    nextWall = temp;

                    isTransitioning = false;
                })
                .start();
    }

    private void recycleView(ImageView iv) {
        if (iv == null) return;
        Drawable d = iv.getDrawable();
        iv.setImageDrawable(null);
        if (d instanceof BitmapDrawable) {
            Bitmap b = ((BitmapDrawable) d).getBitmap();
            if (b != null && !b.isRecycled()) {
                b.recycle();
            }
        }
    }

    private DecodedResult decodeSampled(File file, int reqWidth, int reqHeight) {
        try {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getPath(), opts);

            int sample = 1;
            while ((opts.outWidth / (sample * 2)) >= reqWidth || (opts.outHeight / (sample * 2)) >= reqHeight) {
                sample *= 2;
            }
            opts.inJustDecodeBounds = false;
            opts.inSampleSize = sample;
            opts.inPreferredConfig = Bitmap.Config.RGB_565;
            Bitmap bmp = BitmapFactory.decodeFile(file.getPath(), opts);
            if (bmp == null) return null;

            String caption = null;
            try {
                ExifInterface exif = new ExifInterface(file.getPath());
                int orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
                int rotation = 0;
                switch (orientation) {
                    case ExifInterface.ORIENTATION_ROTATE_90:
                        rotation = 90;
                        break;
                    case ExifInterface.ORIENTATION_ROTATE_180:
                        rotation = 180;
                        break;
                    case ExifInterface.ORIENTATION_ROTATE_270:
                        rotation = 270;
                        break;
                }
                if (rotation != 0) {
                    Matrix matrix = new Matrix();
                    matrix.postRotate(rotation);
                    Bitmap rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), matrix, true);
                    if (rotated != bmp) {
                        bmp.recycle();
                        bmp = rotated;
                    }
                }

                String dateStr = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL);
                if (dateStr == null || dateStr.trim().isEmpty()) {
                    dateStr = exif.getAttribute(ExifInterface.TAG_DATETIME);
                }
                if (dateStr != null && dateStr.length() >= 10) {
                    caption = formatExifDate(dateStr);
                }
                String desc = exif.getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION);
                if (desc != null && !desc.trim().isEmpty()) {
                    desc = desc.trim();
                    if (caption != null) {
                        caption = desc + " • " + caption;
                    } else {
                        caption = desc;
                    }
                }
            } catch (Throwable t) {
                // If EXIF reading fails, continue with original bitmap
            }

            return new DecodedResult(bmp, caption);
        } catch (Throwable t) {
            Log.w(TAG, "Failed to decode wallpaper " + file.getName() + ": " + t);
            return null;
        }
    }

    private static String formatExifDate(String rawDate) {
        if (rawDate == null || rawDate.length() < 10) return null;
        try {
            SimpleDateFormat parser = new SimpleDateFormat("yyyy:MM:dd", Locale.US);
            Date date = parser.parse(rawDate.substring(0, 10));
            if (date != null) {
                SimpleDateFormat formatter = new SimpleDateFormat("d MMMM yyyy", Locale.getDefault());
                return formatter.format(date);
            }
        } catch (Exception ignored) {}
        return null;
    }
}
