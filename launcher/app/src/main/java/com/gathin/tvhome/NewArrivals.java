package com.gathin.tvhome;

import android.content.Context;
import android.content.pm.PackageManager;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * New on your apps: recent movies and shows with new episodes on the streaming services you subscribe to,
 * from TMDB's free API (themoviedb.org; its streaming data comes from JustWatch).
 *
 * For each service in watch_providers it asks TMDB's discover endpoint for popular movies released in the last
 * {@link #MOVIE_DAYS} days and shows with an episode in the last {@link #TV_DAYS} days that stream on it in
 * watch_region. TMDB does not say on which day a title arrived on a service, so "new" means new release or new
 * episode. One request per service and type, at most every {@link #REFRESH_MS}; the answer is kept in
 * cacheDir/new_arrivals.json and shown at once on the next visit.
 */
final class NewArrivals {
    private static final String TAG = "TvHomeNew";
    private static final String API = "https://api.themoviedb.org/3";
    private static final String IMAGES = "https://image.tmdb.org/t/p/w342";
    private static final String CACHE = "new_arrivals.json";
    static final long REFRESH_MS = 12L * 60 * 60 * 1000;
    private static final int MOVIE_DAYS = 120;
    private static final int TV_DAYS = 21;
    private static final int MAX_ITEMS = 60;
    private static final int TIMEOUT_MS = 15_000;

    enum Status { OK, NO_KEY, OFFLINE, NO_PROVIDERS }

    static final class Result {
        final Status status;
        final List<Shelf.Item> items;
        final String detail;

        Result(Status status, List<Shelf.Item> items, String detail) {
            this.status = status;
            this.items = items;
            this.detail = detail;
        }
    }

    /** What to ask for: read from config.xml, overridden by config.json (see ShelfActivity.settings). */
    static final class Settings {
        final String apiKey;
        final String region;
        final String language;
        final List<String> providers;

        Settings(String apiKey, String region, String language, List<String> providers) {
            this.apiKey = apiKey == null ? "" : apiKey.trim();
            this.region = region == null || region.trim().isEmpty() ? "IN" : region.trim().toUpperCase(java.util.Locale.ROOT);
            this.language = language == null || language.trim().isEmpty() ? "en-US" : language.trim();
            this.providers = providers;
        }

        String signature() {
            return region + "|" + language + "|" + providers;
        }
    }

    private NewArrivals() {
    }

    /** The cached list, whatever its age, or null. Fast: for the first frame. */
    static List<Shelf.Item> cached(Context context, Settings s) {
        JSONObject json = readCache(context);
        if (json == null || !s.signature().equals(json.optString("signature"))) return null;
        return items(context, json.optJSONArray("items"));
    }

    static boolean fresh(Context context, Settings s) {
        JSONObject json = readCache(context);
        return json != null && s.signature().equals(json.optString("signature"))
                && System.currentTimeMillis() - json.optLong("fetched") < REFRESH_MS;
    }

    /** Runs on a background thread: asks TMDB, writes the cache, returns the list. Never throws. */
    static Result refresh(Context context, Settings s) {
        if (s.apiKey.isEmpty()) return new Result(Status.NO_KEY, new ArrayList<>(), null);
        try {
            // Every service TMDB knows in the region; a name in watch_providers matches by contains, so
            // "hotstar" finds "JioHotstar" and "prime video" finds "Amazon Prime Video".
            Map<Integer, String> known = new LinkedHashMap<>();
            for (String type : new String[]{"movie", "tv"}) {
                JSONArray list = get(s, "/watch/providers/" + type + "?watch_region=" + s.region).optJSONArray("results");
                for (int i = 0; list != null && i < list.length(); i++) {
                    JSONObject p = list.getJSONObject(i);
                    known.put(p.getInt("provider_id"), p.getString("provider_name"));
                }
            }
            List<Object[]> chosen = new ArrayList<>();   // {wanted keyword, provider id, provider name}
            for (String wanted : s.providers) {
                String key = Shelf.norm(wanted);
                if (key.isEmpty()) continue;
                Integer bestId = null;
                String bestName = null;
                for (Map.Entry<Integer, String> p : known.entrySet()) {
                    if (!Shelf.norm(p.getValue()).contains(key)) continue;
                    // "Netflix" over "Netflix basic with Ads": the shortest name is the main plan.
                    if (bestName == null || p.getValue().length() < bestName.length()) {
                        bestId = p.getKey();
                        bestName = p.getValue();
                    }
                }
                if (bestId != null) chosen.add(new Object[]{wanted, bestId, bestName});
                else Log.w(TAG, "no TMDB service in " + s.region + " matches \"" + wanted + "\"");
            }
            if (chosen.isEmpty()) {
                return new Result(Status.NO_PROVIDERS, new ArrayList<>(), String.join(", ", s.providers));
            }

            LocalDate today = LocalDate.now();
            Map<String, JSONObject> merged = new LinkedHashMap<>();
            for (Object[] c : chosen) {
                String q = "?watch_region=" + s.region + "&with_watch_providers=" + c[1]
                        + "&with_watch_monetization_types=" + enc("flatrate|free|ads")
                        + "&sort_by=popularity.desc&include_adult=false&language=" + enc(s.language);
                JSONArray movies = get(s, "/discover/movie" + q
                        + "&primary_release_date.gte=" + today.minusDays(MOVIE_DAYS)
                        + "&primary_release_date.lte=" + today).optJSONArray("results");
                JSONArray shows = get(s, "/discover/tv" + q
                        + "&air_date.gte=" + today.minusDays(TV_DAYS)
                        + "&air_date.lte=" + today).optJSONArray("results");
                add(merged, movies, "movie", (String) c[0], (String) c[2]);
                add(merged, shows, "tv", (String) c[0], (String) c[2]);
            }
            List<JSONObject> all = new ArrayList<>(merged.values());
            all.sort((a, b) -> Double.compare(b.optDouble("popularity"), a.optDouble("popularity")));
            JSONArray out = new JSONArray();
            for (JSONObject o : all) {
                if (out.length() == MAX_ITEMS) break;
                out.put(o);
            }
            JSONObject cache = new JSONObject()
                    .put("signature", s.signature())
                    .put("fetched", System.currentTimeMillis())
                    .put("items", out);
            writeCache(context, cache);
            return new Result(Status.OK, items(context, out), null);
        } catch (IOException e) {
            Log.w(TAG, "TMDB not reachable: " + e);
            return new Result(Status.OFFLINE, new ArrayList<>(), e.getMessage());
        } catch (JSONException e) {
            Log.w(TAG, "TMDB answer not understood: " + e);
            return new Result(Status.OFFLINE, new ArrayList<>(), e.getMessage());
        }
    }

    /** Adds TMDB results to {@code merged}, one entry per title; a title on two services lists both. */
    private static void add(Map<String, JSONObject> merged, JSONArray results, String type, String wanted,
                            String service) throws JSONException {
        for (int i = 0; results != null && i < results.length(); i++) {
            JSONObject r = results.getJSONObject(i);
            String poster = r.optString("poster_path", "");
            if (poster.isEmpty() || "null".equals(poster)) continue;   // no art: usually an obscure listing
            String id = type + ":" + r.getInt("id");
            JSONObject seen = merged.get(id);
            if (seen != null) {
                if (!seen.getString("service").contains(service)) seen.put("service", seen.getString("service") + " · " + service);
                continue;
            }
            boolean movie = type.equals("movie");
            String date = r.optString(movie ? "release_date" : "first_air_date", "");
            String year = date.length() >= 4 ? date.substring(0, 4) : "";
            merged.put(id, new JSONObject()
                    .put("title", r.optString(movie ? "title" : "name"))
                    .put("subtitle", movie ? (year.isEmpty() ? "Movie" : "Movie · " + year) : "New episodes")
                    .put("poster", IMAGES + poster)
                    .put("popularity", r.optDouble("popularity"))
                    .put("wanted", wanted)
                    .put("service", service));
        }
    }

    /** Cache entries to cards; the app that opens each card is looked up now, so a new install counts. */
    private static List<Shelf.Item> items(Context context, JSONArray array) {
        List<Shelf.Item> items = new ArrayList<>();
        if (array == null) return items;
        PackageManager pm = context.getPackageManager();
        Map<String, String> apps = new HashMap<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject o = array.optJSONObject(i);
            if (o == null) continue;
            String wanted = o.optString("wanted");
            if (!apps.containsKey(wanted)) apps.put(wanted, Shelf.findApp(pm, wanted));
            Shelf.Item item = new Shelf.Item(apps.get(wanted), o.optString("title"), o.optString("subtitle"),
                    o.optString("poster", null), -1f);
            item.badge = o.optString("service");
            items.add(item);
        }
        return items;
    }

    private static JSONObject get(Settings s, String path) throws IOException, JSONException {
        // A v4 "API Read Access Token" is a JWT (eyJ...) sent as a bearer token; a v3 API key goes in the query.
        boolean bearer = s.apiKey.startsWith("eyJ");
        String url = API + path + (bearer ? "" : (path.contains("?") ? "&" : "?") + "api_key=" + enc(s.apiKey));
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(TIMEOUT_MS);
        conn.setReadTimeout(TIMEOUT_MS);
        conn.setRequestProperty("Accept", "application/json");
        if (bearer) conn.setRequestProperty("Authorization", "Bearer " + s.apiKey);
        try {
            int code = conn.getResponseCode();
            if (code == 401) throw new IOException("TMDB refused the API key (401)");
            if (code != 200) throw new IOException("TMDB answered " + code);
            try (InputStream in = conn.getInputStream()) {
                return new JSONObject(new String(Posters.read(in), StandardCharsets.UTF_8));
            }
        } finally {
            conn.disconnect();
        }
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            return s;
        }
    }

    private static JSONObject readCache(Context context) {
        File f = new File(context.getCacheDir(), CACHE);
        if (!f.isFile()) return null;
        try (InputStream in = new FileInputStream(f)) {
            return new JSONObject(new String(Posters.read(in), StandardCharsets.UTF_8));
        } catch (IOException | JSONException e) {
            return null;
        }
    }

    private static void writeCache(Context context, JSONObject json) {
        try (FileOutputStream out = new FileOutputStream(new File(context.getCacheDir(), CACHE))) {
            out.write(json.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            Log.w(TAG, "cache not written: " + e);
        }
    }
}
