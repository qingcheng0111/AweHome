package com.gathin.tvhome;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * AweHome：打开的文件夹，样子同视频：背后是模糊的首页，文件夹里的应用按网格的图标尺寸在上方排成几行，
 * 选中的那个下面显示名称。返回键关闭；在应用上长按确认键打开它的菜单（移出文件夹、应用信息）。
 */
final class FolderOverlay {
    interface Host {
        void open(AppAdapter.Entry app);

        void longPress(AppAdapter.Entry app, View card);

        void closed(String folderId);
    }

    private final Activity activity;
    private final FrameLayout root;
    private final Host host;
    private final String folderId;
    private final FrameLayout layer;
    private final List<View> cards = new ArrayList<>();

    FolderOverlay(Activity activity, FrameLayout root, Host host, AppAdapter.Entry folder, Bitmap blurred,
                  int tileW, int tileH, int columns) {
        this.activity = activity;
        this.root = root;
        this.host = host;
        this.folderId = folder.folderId;
        float dp = activity.getResources().getDisplayMetrics().density;
        layer = new FrameLayout(activity);
        layer.setId(R.id.folderOverlay);
        layer.setClickable(true);
        ImageView back = new ImageView(activity);
        back.setScaleType(ImageView.ScaleType.FIT_XY);
        back.setImageBitmap(blurred);
        layer.addView(back, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        View veil = new View(activity);
        veil.setBackgroundColor(0x4DE6E6EA);
        layer.addView(veil, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout rows = new LinearLayout(activity);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setClipChildren(false);
        rows.setClipToPadding(false);
        LinearLayout row = null;
        int gap = Math.round(30 * dp);
        for (int i = 0; i < folder.members.size(); i++) {
            if (i % columns == 0) {
                row = new LinearLayout(activity);
                row.setGravity(Gravity.CENTER_HORIZONTAL);
                row.setClipChildren(false);
                rows.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            AppAdapter.Entry app = folder.members.get(i);
            View item = tile(app, tileW, tileH, dp);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(tileW + gap, ViewGroup.LayoutParams.WRAP_CONTENT);
            row.addView(item, lp);
        }
        FrameLayout.LayoutParams rlp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP);
        rlp.topMargin = Math.round(70 * dp);
        layer.addView(rows, rlp);
        layer.setAlpha(0f);
        root.addView(layer, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        layer.animate().alpha(1f).setDuration(200).start();
        if (!cards.isEmpty()) cards.get(0).requestFocus();
    }

    private View tile(AppAdapter.Entry app, int w, int h, float dp) {
        View item = LayoutInflater.from(activity).inflate(R.layout.item_app, root, false);
        View card = item.findViewById(R.id.card);
        ViewGroup.LayoutParams clp = card.getLayoutParams();
        clp.width = w;
        clp.height = h;
        card.setLayoutParams(clp);
        TextView name = item.findViewById(R.id.name);
        ViewGroup.MarginLayoutParams nlp = (ViewGroup.MarginLayoutParams) name.getLayoutParams();
        nlp.width = w + Math.round(40 * dp);
        nlp.topMargin = h + Math.round(12 * dp);
        name.setLayoutParams(nlp);
        name.setText(app.label);
        name.setAlpha(0f);
        ImageView banner = item.findViewById(R.id.banner);
        ImageView icon = item.findViewById(R.id.icon);
        TextView label = item.findViewById(R.id.label);
        if (app.banner != null) {
            banner.setImageDrawable(copy(app.banner));
            icon.setVisibility(View.GONE);
            label.setVisibility(View.GONE);
        } else {
            banner.setVisibility(View.GONE);
            icon.setImageDrawable(copy(app.icon));
            icon.setVisibility(View.VISIBLE);
            label.setText(app.label);
            label.setVisibility(View.VISIBLE);
        }
        card.setClipToOutline(true);
        GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(AppAdapter.cornerRadius(w, dp));
        ring.setStroke(Math.round(3 * dp), Color.WHITE);
        ring.setColor(Color.TRANSPARENT);
        StateListDrawable fg = new StateListDrawable();
        fg.addState(new int[]{android.R.attr.state_focused}, ring);
        card.setForeground(fg);
        card.setOnFocusChangeListener((v, focused) -> {
            float s = focused ? 1.1f : 1f;
            v.animate().scaleX(s).scaleY(s).setDuration(160).start();
            name.animate().alpha(focused ? 1f : 0f).setDuration(160).start();
            if (focused) KeySounds.focusMoved();
        });
        card.setOnClickListener(v -> host.open(app));
        card.setOnLongClickListener(v -> {
            host.longPress(app, v);
            return true;
        });
        cards.add(card);
        return item;
    }

    private Drawable copy(Drawable d) {
        if (d == null) return null;
        Drawable.ConstantState cs = d.getConstantState();
        return cs != null ? cs.newDrawable(activity.getResources()) : d;
    }

    String folderId() {
        return folderId;
    }

    /** 打开的文件夹里的按键：返回键关闭；方向键只在文件夹里的应用之间移动。 */
    boolean onKey(KeyEvent event) {
        int code = event.getKeyCode();
        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE) {
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) close();
            return true;
        }
        return false;
    }

    void close() {
        layer.animate().alpha(0f).setDuration(160).withEndAction(() -> root.removeView(layer)).start();
        host.closed(folderId);
    }

    boolean contains(View v) {
        while (v != null) {
            if (v == layer) return true;
            v = v.getParent() instanceof View ? (View) v.getParent() : null;
        }
        return false;
    }
}
