package com.gathin.tvhome;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.tv.TvContract;
import android.util.Log;

import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Continue watching, across every app: the system's Watch Next table (docs/watch-next.md), the same list
 * Google TV's own "Continue watching" row reads. Needs READ_TV_LISTINGS, which build-and-install grants;
 * without it the query throws and the tab says so.
 *
 * Adapted from gwiersma/cleanyourtv's ContinueWatching (MIT): same filters, but every poster scheme is kept
 * because this fork may fetch https:// posters (see Posters).
 */
final class WatchNext {
    static final String TAG = "TvHomeWatchNext";
    /** Rows older than this are dropped: some apps leave them for months (docs/watch-next.md, TCL C6K). */
    private static final long MAX_AGE_MS = 90L * 24 * 60 * 60 * 1000;
    /** A "continue" row this far through is finished in all but name. */
    private static final double DONE_FRACTION = 0.95;
    static final int MAX_ITEMS = 40;

    /** Why the list came back empty, for the tab's empty state. */
    enum Status { OK, NO_PERMISSION, NO_PROVIDER }

    static final class Result {
        final Status status;
        final List<Shelf.Item> items;

        Result(Status status, List<Shelf.Item> items) {
            this.status = status;
            this.items = items;
        }
    }

    private WatchNext() {
    }

    /** Runs on a background thread. Never throws. */
    static Result load(Context context) {
        List<Shelf.Item> items = new ArrayList<>();
        ContentResolver cr = context.getContentResolver();
        PackageManager pm = context.getPackageManager();
        long now = System.currentTimeMillis();
        Set<String> seen = new HashSet<>();
        List<long[]> order = new ArrayList<>();   // {engagement, index into items}
        // No selection: a caller without ACCESS_ALL_EPG_DATA gets a SecurityException when it passes one.
        try (Cursor c = cr.query(TvContract.WatchNextPrograms.CONTENT_URI, null, null, null, null)) {
            if (c == null) return new Result(Status.NO_PROVIDER, items);
            while (c.moveToNext()) {
                String pkg = str(c, TvContract.WatchNextPrograms.COLUMN_PACKAGE_NAME);
                if (pkg == null) continue;
                if (lng(c, TvContract.WatchNextPrograms.COLUMN_BROWSABLE, 1) == 0) continue;
                long engaged = lng(c, TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS, 0);
                if (engaged > 0 && now - engaged > MAX_AGE_MS) continue;
                String title = str(c, TvContract.WatchNextPrograms.COLUMN_TITLE);
                if (title == null || title.isEmpty()) continue;
                if (!seen.add(pkg + '\n' + title)) continue;   // one card per show
                if (pm.getLeanbackLaunchIntentForPackage(pkg) == null && pm.getLaunchIntentForPackage(pkg) == null) continue;

                long pos = lng(c, TvContract.WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS, 0);
                long dur = lng(c, TvContract.WatchNextPrograms.COLUMN_DURATION_MILLIS, 0);
                float progress = pos > 0 && dur > 0 ? (float) Math.min(1.0, (double) pos / dur) : -1f;
                int type = (int) lng(c, TvContract.WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE, -1);
                if (type == TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE && progress >= DONE_FRACTION) continue;

                // Landscape art first: the tab's cards are 16:9.
                String art = str(c, TvContract.WatchNextPrograms.COLUMN_THUMBNAIL_URI);
                if (art == null) art = str(c, TvContract.WatchNextPrograms.COLUMN_POSTER_ART_URI);
                Shelf.Item item = new Shelf.Item(pkg, title, subtitle(c), art, progress);
                item.intentUri = str(c, TvContract.WatchNextPrograms.COLUMN_INTENT_URI);
                item.badge = Shelf.appLabel(pm, pkg);
                order.add(new long[]{engaged, items.size()});
                items.add(item);
            }
        } catch (SecurityException e) {
            Log.w(TAG, "Watch Next not readable: " + e);
            return new Result(Status.NO_PERMISSION, new ArrayList<>());
        } catch (Exception e) {
            Log.w(TAG, "Watch Next query failed: " + e);
            return new Result(Status.NO_PROVIDER, new ArrayList<>());
        }
        Collections.sort(order, (a, b) -> Long.compare(b[0], a[0]));
        List<Shelf.Item> sorted = new ArrayList<>();
        for (long[] o : order) {
            if (sorted.size() == MAX_ITEMS) break;
            sorted.add(items.get((int) o[1]));
        }
        return new Result(Status.OK, sorted);
    }

    /** "S2 · E5 · Episode title", or whatever part of it the app filled in. */
    private static String subtitle(Cursor c) {
        StringBuilder s = new StringBuilder();
        String season = str(c, TvContract.WatchNextPrograms.COLUMN_SEASON_DISPLAY_NUMBER);
        String episode = str(c, TvContract.WatchNextPrograms.COLUMN_EPISODE_DISPLAY_NUMBER);
        String name = str(c, TvContract.WatchNextPrograms.COLUMN_EPISODE_TITLE);
        if (season != null && !season.isEmpty()) s.append('S').append(season);
        if (episode != null && !episode.isEmpty()) s.append(s.length() > 0 ? " · " : "").append('E').append(episode);
        if (name != null && !name.isEmpty()) s.append(s.length() > 0 ? " · " : "").append(name);
        return s.toString();
    }

    /** The intent the app published, or its own launch intent when that is missing or unparseable. */
    static Intent intentFor(Context context, Shelf.Item item) {
        if (item.intentUri != null) {
            try {
                Intent i = Intent.parseUri(item.intentUri, Intent.URI_INTENT_SCHEME);
                // Only the app that published the row: an intent_uri pointing elsewhere is not followed.
                if (i.getPackage() == null && i.getComponent() == null) i.setPackage(item.pkg);
                String target = i.getComponent() != null ? i.getComponent().getPackageName() : i.getPackage();
                if (item.pkg.equals(target)) {
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    return i;
                }
            } catch (URISyntaxException e) {
                Log.w(TAG, "bad intent_uri from " + item.pkg + ": " + e);
            }
        }
        return Shelf.launchIntent(context.getPackageManager(), item.pkg);
    }

    private static String str(Cursor c, String column) {
        int i = c.getColumnIndex(column);
        return i < 0 || c.isNull(i) ? null : c.getString(i);
    }

    private static long lng(Cursor c, String column, long fallback) {
        int i = c.getColumnIndex(column);
        return i < 0 || c.isNull(i) ? fallback : c.getLong(i);
    }
}
