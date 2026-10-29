package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Loads card art for the Watch screen: content:// and friends through the content resolver, https:// over the
 * network with a small disk cache (cacheDir/posters, trimmed to {@link #DISK_FILES} files), decoded down to
 * the card's width in RGB_565. Only the Watch screen uses it, so Home itself still makes no request.
 */
final class Posters {
    private static final String TAG = "TvHomePosters";
    private static final int DISK_FILES = 300;
    private static final int TIMEOUT_MS = 10_000;

    private static LruCache<String, Bitmap> memory;
    private static ExecutorService pool;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private Posters() {
    }

    private static synchronized void init() {
        if (memory != null) return;
        // An eighth of the heap: on a 1 GB TV that is still a few dozen cards.
        int kb = (int) (Runtime.getRuntime().maxMemory() / 1024 / 8);
        memory = new LruCache<String, Bitmap>(kb) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value.getByteCount() / 1024;
            }
        };
        pool = Executors.newFixedThreadPool(3);
    }

    /** Shows {@code uri} in {@code view}, decoded to about {@code widthPx}; a recycled view never gets stale art. */
    static void load(Context context, ImageView view, String uri, int widthPx) {
        init();
        view.setTag(uri);
        view.setImageDrawable(null);
        if (uri == null) return;
        Bitmap hit = memory.get(uri);
        if (hit != null) {
            view.setImageBitmap(hit);
            return;
        }
        Context app = context.getApplicationContext();
        pool.execute(() -> {
            Bitmap b = fetch(app, uri, widthPx);
            if (b == null) return;
            memory.put(uri, b);
            MAIN.post(() -> {
                if (uri.equals(view.getTag())) view.setImageBitmap(b);
            });
        });
    }

    private static Bitmap fetch(Context context, String uri, int widthPx) {
        try {
            byte[] data = bytes(context, uri);
            if (data == null) return null;
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = 1;
            while (bounds.outWidth / (opts.inSampleSize * 2) >= widthPx) opts.inSampleSize *= 2;
            opts.inPreferredConfig = Bitmap.Config.RGB_565;   // half the memory; posters have no alpha
            return BitmapFactory.decodeByteArray(data, 0, data.length, opts);
        } catch (Exception | OutOfMemoryError e) {
            Log.w(TAG, "art not loaded: " + e);
            return null;
        }
    }

    private static byte[] bytes(Context context, String uri) throws IOException {
        String scheme = Uri.parse(uri).getScheme();
        if ("https".equals(scheme)) return remote(context, uri);
        if ("content".equals(scheme) || "android.resource".equals(scheme) || "file".equals(scheme)) {
            try (InputStream in = context.getContentResolver().openInputStream(Uri.parse(uri))) {
                return in == null ? null : read(in);
            }
        }
        return null;   // http:// and anything else: not fetched
    }

    private static byte[] remote(Context context, String uri) throws IOException {
        File dir = new File(context.getCacheDir(), "posters");
        File file = new File(dir, Integer.toHexString(uri.hashCode()) + "_" + uri.length());
        if (file.isFile()) {
            try (InputStream in = new FileInputStream(file)) {
                return read(in);
            }
        }
        HttpURLConnection conn = (HttpURLConnection) new URL(uri).openConnection();
        conn.setConnectTimeout(TIMEOUT_MS);
        conn.setReadTimeout(TIMEOUT_MS);
        try {
            if (conn.getResponseCode() != 200) return null;
            byte[] data;
            try (InputStream in = conn.getInputStream()) {
                data = read(in);
            }
            if (dir.isDirectory() || dir.mkdirs()) {
                try (FileOutputStream out = new FileOutputStream(file)) {
                    out.write(data);
                }
                trim(dir);
            }
            return data;
        } finally {
            conn.disconnect();
        }
    }

    /** Keeps the poster cache to DISK_FILES files, dropping the oldest. */
    private static void trim(File dir) {
        File[] files = dir.listFiles();
        if (files == null || files.length <= DISK_FILES) return;
        java.util.Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (int i = 0; i < files.length - DISK_FILES; i++) {
            if (!files[i].delete()) Log.w(TAG, "could not trim " + files[i]);
        }
    }

    static byte[] read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }
}
