package com.gathin.tvhome;

import android.app.SearchManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Shared pieces of the Watch screen (ShelfActivity): one card's data and the ways a card opens an app. */
final class Shelf {
    private Shelf() {
    }

    static final class Item {
        /** The app that opens the card; null when no installed app matched (New on your apps). */
        final String pkg;
        final String title;
        final String subtitle;
        /** content://, android.resource://, file:// or https:// art; null for a text card. */
        final String art;
        /** 0..1, or -1 when there is nothing to show. */
        final float progress;
        /** Small label on the card: the app's name. */
        String badge;
        /** Watch Next only: the deep link the app published. */
        String intentUri;

        Item(String pkg, String title, String subtitle, String art, float progress) {
            this.pkg = pkg;
            this.title = title;
            this.subtitle = subtitle;
            this.art = art;
            this.progress = progress;
        }
    }

    static String appLabel(PackageManager pm, String pkg) {
        try {
            ApplicationInfo info = pm.getApplicationInfo(pkg, 0);
            return String.valueOf(info.loadLabel(pm));
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    static Intent launchIntent(PackageManager pm, String pkg) {
        if (pkg == null) return null;
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(pkg);
        if (i != null) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return i;
    }

    /** Lower case, letters and digits only: "Amazon Prime Video" and "prime video" both contain "primevideo". */
    static String norm(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    /**
     * The installed TV app for a streaming service: the first launchable app whose label or package name
     * contains the keyword ("netflix", "primevideo", "hotstar"). Null when none is installed.
     */
    static String findApp(PackageManager pm, String keyword) {
        String key = norm(keyword);
        if (key.isEmpty()) return null;
        List<ResolveInfo> apps = new ArrayList<>();
        for (String category : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            apps.addAll(pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(category), 0));
        }
        for (ResolveInfo r : apps) {
            String pkg = r.activityInfo.packageName;
            if (norm(String.valueOf(r.loadLabel(pm))).contains(key) || norm(pkg).contains(key)) return pkg;
        }
        // Prime Video's TV package is com.amazon.amazonvideo.livingroom, its label just "Prime Video".
        if (key.equals("primevideo")) return findApp(pm, "amazonvideo");
        return null;
    }

    /**
     * Where a New on your apps card goes. Nobody publishes a "play this title" link for TMDB ids, so: the app's
     * own search for the title when it has one, else the TV's global search (Google TV / Android TV search
     * shows the title with a button per app), else the app's start screen.
     */
    static Intent searchIntent(Context context, Item item) {
        PackageManager pm = context.getPackageManager();
        if (item.pkg != null) {
            Intent search = new Intent(Intent.ACTION_SEARCH).setPackage(item.pkg)
                    .putExtra(SearchManager.QUERY, item.title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (pm.resolveActivity(search, 0) != null) return search;
        }
        Intent global = new Intent(SearchManager.INTENT_ACTION_GLOBAL_SEARCH)
                .putExtra(SearchManager.QUERY, item.title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (pm.resolveActivity(global, 0) != null) return global;
        return launchIntent(pm, item.pkg);
    }
}
