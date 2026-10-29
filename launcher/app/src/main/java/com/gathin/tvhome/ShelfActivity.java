package com.gathin.tvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The Watch screen, opened from the Watch pill in Home's top bar: two tabs over one poster grid.
 *
 * <ul>
 *   <li><b>Continue watching</b>: every app's Watch Next rows in one place (WatchNext), newest first, with
 *   progress. OK resumes the episode through the link the app published.</li>
 *   <li><b>New on your apps</b>: recent movies and shows with new episodes on the services in watch_providers,
 *   from TMDB (NewArrivals). OK opens the title's search in its app, or the TV's search.</li>
 * </ul>
 *
 * Built in code rather than XML so the whole feature is these few files. Moving onto a tab switches to it.
 */
public final class ShelfActivity extends Activity {
    static final String EXTRA_TAB = "tab";
    static final int TAB_CONTINUE = 0;
    static final int TAB_NEW = 1;
    private static final String TAG = "TvHomeShelf";

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private float density;
    private Typeface font;
    private final TextView[] tabs = new TextView[2];
    private TextView status;
    private RecyclerView grid;
    private GridLayoutManager layout;
    private CardAdapter adapter;
    private int tab = -1;

    private List<Shelf.Item> continueItems;
    private String continueMessage;
    private List<Shelf.Item> newItems;
    private String newMessage;
    private boolean refreshing;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        density = getResources().getDisplayMetrics().density;
        try {
            font = getResources().getFont(R.font.outfit_regular);
        } catch (Exception e) {
            font = Typeface.SANS_SERIF;
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);
        root.setPadding(dp(56), dp(36), dp(56), 0);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        tabs[TAB_CONTINUE] = tab(getString(R.string.shelf_continue), TAB_CONTINUE);
        tabs[TAB_NEW] = tab(getString(R.string.shelf_new), TAB_NEW);
        header.addView(tabs[TAB_CONTINUE]);
        LinearLayout.LayoutParams second = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        second.setMarginStart(dp(12));
        header.addView(tabs[TAB_NEW], second);
        root.addView(header);

        status = new TextView(this);
        status.setTypeface(font);
        status.setTextColor(0x99FFFFFF);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        status.setPadding(dp(4), dp(14), 0, dp(10));
        root.addView(status);

        grid = new RecyclerView(this);
        grid.setId(View.generateViewId());
        grid.setClipToPadding(false);
        grid.setClipChildren(false);
        grid.setPadding(0, dp(10), 0, dp(48));
        grid.setItemAnimator(null);
        grid.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        layout = new GridLayoutManager(this, 4);
        grid.setLayoutManager(layout);
        adapter = new CardAdapter();
        grid.setAdapter(adapter);
        root.addView(grid, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        for (TextView t : tabs) t.setNextFocusDownId(grid.getId());

        setContentView(root);
        int start = getIntent().getIntExtra(EXTRA_TAB, TAB_CONTINUE);
        select(start == TAB_NEW ? TAB_NEW : TAB_CONTINUE);
        tabs[tab].requestFocus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Both lists are cheap to keep current: Watch Next is a local query, New on your apps a cached file
        // refreshed at most every twelve hours.
        loadContinue();
        loadNew();
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }

    private TextView tab(String label, int index) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTypeface(font, Typeface.BOLD);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        t.setPadding(dp(22), dp(10), dp(22), dp(10));
        t.setFocusable(true);
        t.setClickable(true);
        t.setId(View.generateViewId());
        t.setTextColor(new ColorStateList(
                new int[][]{{android.R.attr.state_focused}, {android.R.attr.state_selected}, {}},
                new int[]{Color.BLACK, Color.WHITE, 0x80FFFFFF}));
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_focused}, pill(Color.WHITE));
        bg.addState(new int[]{android.R.attr.state_selected}, pill(0x33FFFFFF));
        bg.addState(new int[]{}, new ColorDrawable(Color.TRANSPARENT));
        t.setBackground(bg);
        // Apple TV style: landing on a tab shows it, no OK needed.
        t.setOnFocusChangeListener((v, focused) -> {
            if (focused) select(index);
        });
        t.setOnClickListener(v -> select(index));
        return t;
    }

    private Drawable pill(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(24));
        return d;
    }

    private void select(int index) {
        if (tab == index) return;
        tab = index;
        for (int i = 0; i < tabs.length; i++) tabs[i].setSelected(i == index);
        layout.setSpanCount(index == TAB_CONTINUE ? 4 : 6);
        show();
    }

    /** Puts the current tab's list (or its empty-state message) on screen. */
    private void show() {
        List<Shelf.Item> items = tab == TAB_CONTINUE ? continueItems : newItems;
        String message = tab == TAB_CONTINUE ? continueMessage : newMessage;
        adapter.set(items == null ? new ArrayList<>() : items, tab == TAB_CONTINUE);
        if (items == null) status.setText(R.string.shelf_loading);
        else if (message != null) status.setText(message);
        else status.setText(null);
        status.setVisibility(TextUtils.isEmpty(status.getText()) ? View.GONE : View.VISIBLE);
    }

    private void loadContinue() {
        worker.execute(() -> {
            WatchNext.Result r = WatchNext.load(this);
            main.post(() -> {
                if (isDestroyed()) return;
                continueItems = r.items;
                if (r.status == WatchNext.Status.NO_PERMISSION) continueMessage = getString(R.string.shelf_continue_permission);
                else if (r.status == WatchNext.Status.NO_PROVIDER) continueMessage = getString(R.string.shelf_continue_unsupported);
                else if (r.items.isEmpty()) continueMessage = getString(R.string.shelf_continue_empty);
                else continueMessage = null;
                if (tab == TAB_CONTINUE) show();
            });
        });
    }

    private void loadNew() {
        NewArrivals.Settings s = settings();
        if (newItems == null) {
            worker.execute(() -> {
                List<Shelf.Item> cached = NewArrivals.cached(this, s);
                if (cached == null) return;
                main.post(() -> {
                    if (isDestroyed() || newItems != null) return;
                    newItems = cached;
                    if (tab == TAB_NEW) show();
                });
            });
        }
        if (refreshing) return;
        refreshing = true;
        worker.execute(() -> {
            NewArrivals.Result r = NewArrivals.fresh(this, s) ? null : NewArrivals.refresh(this, s);
            List<Shelf.Item> cached = r == null ? NewArrivals.cached(this, s) : null;
            main.post(() -> {
                refreshing = false;
                if (isDestroyed()) return;
                if (r == null) {
                    if (cached != null) newItems = cached;
                    newMessage = newItems == null || newItems.isEmpty() ? getString(R.string.shelf_new_empty) : null;
                } else if (r.status == NewArrivals.Status.OK) {
                    newItems = r.items;
                    newMessage = r.items.isEmpty() ? getString(R.string.shelf_new_empty) : null;
                } else {
                    // Keep showing the last good list when a refresh fails; say why only when there is none.
                    if (newItems == null) newItems = new ArrayList<>();
                    if (r.status == NewArrivals.Status.NO_KEY) newMessage = getString(R.string.shelf_new_no_key);
                    else if (r.status == NewArrivals.Status.NO_PROVIDERS) newMessage = getString(R.string.shelf_new_no_providers, r.detail);
                    else if (newItems.isEmpty()) newMessage = getString(R.string.shelf_new_offline, r.detail);
                }
                if (tab == TAB_NEW) show();
            });
        });
    }

    /**
     * TMDB settings: config.xml's watch_region and watch_providers, the key built in from TMDB_API_KEY at
     * build time, and any of tmdb_api_key, watch_region, watch_language or watch_providers in config.json
     * (pushed by tools/theme.sh) on top.
     */
    private NewArrivals.Settings settings() {
        String key = BuildConfig.TMDB_API_KEY;
        String region = getString(R.string.watch_region);
        String language = getString(R.string.watch_language);
        List<String> providers = new ArrayList<>(Arrays.asList(getResources().getStringArray(R.array.watch_providers)));
        File file = new File(getExternalFilesDir(null), Config.FILE_NAME);
        if (file.isFile()) {
            try (InputStream in = new FileInputStream(file)) {
                JSONObject json = new JSONObject(new String(Posters.read(in), StandardCharsets.UTF_8));
                key = json.optString("tmdb_api_key", key);
                region = json.optString("watch_region", region);
                language = json.optString("watch_language", language);
                JSONArray list = json.optJSONArray("watch_providers");
                if (list != null) {
                    providers.clear();
                    for (int i = 0; i < list.length(); i++) providers.add(list.optString(i));
                }
            } catch (Exception e) {
                Log.w(TAG, "config.json not read: " + e);
            }
        }
        return new NewArrivals.Settings(key, region, language, providers);
    }

    private void open(Shelf.Item item) {
        Intent intent = tab == TAB_CONTINUE ? WatchNext.intentFor(this, item) : Shelf.searchIntent(this, item);
        if (intent == null) {
            Toast.makeText(this, getString(R.string.shelf_no_app, item.badge), Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            Log.w(TAG, "could not open " + item.title + ": " + e);
            Intent fallback = Shelf.launchIntent(getPackageManager(), item.pkg);
            if (fallback != null) {
                try {
                    startActivity(fallback);
                    return;
                } catch (ActivityNotFoundException | SecurityException ignored) {
                    // fall through to the toast
                }
            }
            Toast.makeText(this, getString(R.string.shelf_no_app, item.badge), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        // Up from the grid's first row goes to the current tab, not whichever tab sits above the card.
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getKeyCode() == KeyEvent.KEYCODE_DPAD_UP) {
            View focused = getCurrentFocus();
            View cell = focused == null ? null : grid.findContainingItemView(focused);
            if (cell != null) {
                int position = grid.getChildAdapterPosition(cell);
                if (position >= 0 && position < layout.getSpanCount()) {
                    tabs[tab].requestFocus();
                    return true;
                }
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private int dp(float v) {
        return Math.round(v * density);
    }

    private final class CardAdapter extends RecyclerView.Adapter<CardHolder> {
        private List<Shelf.Item> items = new ArrayList<>();
        private boolean landscape = true;

        @SuppressWarnings("NotifyDataSetChanged")   // the whole list changes with the tab
        void set(List<Shelf.Item> list, boolean wide) {
            items = list;
            landscape = wide;
            notifyDataSetChanged();
            grid.scrollToPosition(0);
        }

        @Override
        public int getItemViewType(int position) {
            return landscape ? 0 : 1;
        }

        @NonNull
        @Override
        public CardHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new CardHolder(parent, viewType == 0);
        }

        @Override
        public void onBindViewHolder(@NonNull CardHolder holder, int position) {
            holder.bind(items.get(position));
        }

        @Override
        public int getItemCount() {
            return items.size();
        }
    }

    private final class CardHolder extends RecyclerView.ViewHolder {
        private final boolean landscape;
        private final ImageView art;
        private final TextView fallback;
        private final TextView title;
        private final TextView subtitle;
        private final View progressTrack;
        private final View progress;
        private final int widthPx;
        private Shelf.Item item;

        CardHolder(ViewGroup parent, boolean landscape) {
            super(new FrameLayout(parent.getContext()));
            this.landscape = landscape;
            int columns = landscape ? 4 : 6;
            int gap = dp(20);
            int available = parent.getWidth() > 0 ? parent.getWidth() : getResources().getDisplayMetrics().widthPixels - dp(112);
            widthPx = available / columns - gap;
            int heightPx = landscape ? widthPx * 9 / 16 : widthPx * 3 / 2;

            FrameLayout cell = (FrameLayout) itemView;
            cell.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            cell.setPadding(gap / 2, gap / 2, gap / 2, gap / 2);
            cell.setClipChildren(false);
            cell.setClipToPadding(false);

            LinearLayout column = new LinearLayout(parent.getContext());
            column.setOrientation(LinearLayout.VERTICAL);
            cell.addView(column, new FrameLayout.LayoutParams(widthPx, ViewGroup.LayoutParams.WRAP_CONTENT));

            FrameLayout card = new FrameLayout(parent.getContext());
            card.setFocusable(true);
            card.setClickable(true);
            GradientDrawable base = new GradientDrawable();
            base.setColor(0xFF1C1C1E);
            base.setCornerRadius(dp(12));
            card.setBackground(base);
            GradientDrawable ring = new GradientDrawable();
            ring.setColor(Color.TRANSPARENT);
            ring.setCornerRadius(dp(12));
            ring.setStroke(dp(3), Color.WHITE);
            StateListDrawable front = new StateListDrawable();
            front.addState(new int[]{android.R.attr.state_focused}, ring);
            front.addState(new int[]{}, new ColorDrawable(Color.TRANSPARENT));
            card.setForeground(front);
            card.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(12));
                }
            });
            card.setClipToOutline(true);
            column.addView(card, new LinearLayout.LayoutParams(widthPx, heightPx));

            fallback = new TextView(parent.getContext());
            fallback.setTypeface(font, Typeface.BOLD);
            fallback.setTextColor(Color.WHITE);
            fallback.setTextSize(TypedValue.COMPLEX_UNIT_SP, landscape ? 18 : 15);
            fallback.setGravity(Gravity.CENTER);
            fallback.setPadding(dp(12), dp(12), dp(12), dp(12));
            card.addView(fallback, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            art = new ImageView(parent.getContext());
            art.setScaleType(ImageView.ScaleType.CENTER_CROP);
            card.addView(art, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            progressTrack = new View(parent.getContext());
            progressTrack.setBackgroundColor(0x66000000);
            FrameLayout.LayoutParams trackParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(5), Gravity.BOTTOM);
            card.addView(progressTrack, trackParams);
            progress = new View(parent.getContext());
            progress.setBackgroundColor(0xFFE50914);
            card.addView(progress, new FrameLayout.LayoutParams(0, dp(5), Gravity.BOTTOM));

            title = new TextView(parent.getContext());
            title.setTypeface(font, Typeface.BOLD);
            title.setTextColor(Color.WHITE);
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            title.setPadding(dp(2), dp(10), dp(2), 0);
            column.addView(title);

            subtitle = new TextView(parent.getContext());
            subtitle.setTypeface(font);
            subtitle.setTextColor(0x99FFFFFF);
            subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            subtitle.setSingleLine(true);
            subtitle.setEllipsize(TextUtils.TruncateAt.END);
            subtitle.setPadding(dp(2), dp(2), dp(2), 0);
            column.addView(subtitle);

            card.setOnClickListener(v -> {
                if (item != null) open(item);
            });
            card.setOnFocusChangeListener((v, focused) -> {
                float scale = focused ? 1.08f : 1f;
                column.animate().scaleX(scale).scaleY(scale).translationZ(focused ? dp(8) : 0).setDuration(150).start();
            });
            // RecyclerView focus search lands on the cell; hand it to the card.
            cell.setFocusable(false);
            cell.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        }

        void bind(Shelf.Item it) {
            item = it;
            fallback.setText(it.title);
            title.setText(it.title);
            String sub = it.subtitle == null ? "" : it.subtitle;
            if (it.badge != null && !it.badge.isEmpty()) sub = sub.isEmpty() ? it.badge : it.badge + "  ·  " + sub;
            subtitle.setText(sub);
            Posters.load(ShelfActivity.this, art, it.art, widthPx);
            if (landscape && it.progress > 0) {
                progressTrack.setVisibility(View.VISIBLE);
                progress.setVisibility(View.VISIBLE);
                ViewGroup.LayoutParams lp = progress.getLayoutParams();
                lp.width = Math.max(dp(6), Math.round(widthPx * it.progress));
                progress.setLayoutParams(lp);
            } else {
                progressTrack.setVisibility(View.GONE);
                progress.setVisibility(View.GONE);
            }
        }
    }
}
