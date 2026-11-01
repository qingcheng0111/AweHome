package com.gathin.tvhome;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * AweHome：在图标上长按确认键打开的菜单，同视频：顶部是应用名称和它的图标，下面是「添加到程序坞」、
 * 向左、右、上、下移一格（图标在菜单后面移动，菜单不关，可以接着移）、「移到文件夹」和「应用信息」。
 */
final class TileMenu {
    static final int LEFT = 0, RIGHT = 1, UP = 2, DOWN = 3;

    interface Host {
        Bitmap glassBackdrop();

        /** 图标能不能往那个方向移一格。 */
        boolean canMove(String key, int direction);

        void move(String key, int direction);

        boolean inDock(String key);

        void addToDock(String key);

        void removeFromDock(String key);

        /** 移进一个文件夹，或新建一个（{@code folderId} 为 null）。 */
        void toFolder(String key, String folderId);

        void outOfFolder(String key);

        void appInfo(String key);

        void renameFolder(String folderId);

        void ungroup(String folderId);
    }

    private final Activity activity;
    private final Host host;
    private final String key, label, folderId;
    private final boolean inFolder;
    private final Drawable art, icon;
    private final PanelWindow window;
    private PanelUi ui;
    private LinearLayout column;
    private TextView header;
    private android.widget.ScrollView scroll;
    private boolean folderPage;

    /**
     * @param folderId 这个图标本身就是该文件夹（应用为 null）
     * @param inFolder 应用显示在打开的文件夹里：菜单改为把它移出文件夹，而不是移动它
     */
    TileMenu(Activity activity, Host host, String key, String label, Drawable art, Drawable icon, String folderId, boolean inFolder) {
        this.activity = activity;
        this.host = host;
        this.key = key;
        this.label = label;
        this.art = art;
        this.icon = icon;
        this.folderId = folderId;
        this.inFolder = inFolder;
        this.window = new PanelWindow(activity);
    }

    void show() {
        String mode = HomePrefs.glass(activity);
        ui = new PanelUi(activity, HomePrefs.textShadow(mode));
        GlassPanel glass = new GlassPanel(activity);
        glass.setBackdrop(host.glassBackdrop(), !HomePrefs.GLASS_FROSTED.equals(mode));
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        header = ui.header(label);
        box.addView(header);
        // 焦点往下走时，图标和各行在标题下面一起滚动，同视频里的菜单。
        scroll = new android.widget.ScrollView(activity);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.addView(preview(), previewParams());
        column = ui.column();
        content.addView(column);
        scroll.addView(content);
        box.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        glass.addView(box, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        window.setKeyHandler(this::onKey);
        build(0);
        int top = ui.px(86);
        int height = activity.getWindow().getDecorView().getHeight() - top - ui.px(14);
        window.show(glass, Gravity.TOP | Gravity.END, ui.px(16), top, ui.px(PanelUi.PANEL_WIDTH_DP), height);
        glass.setPivotY(0);
        glass.setAlpha(0f);
        glass.setScaleY(0.94f);
        glass.animate().alpha(1f).scaleY(1f).setDuration(200).start();
    }

    void dismiss() {
        window.dismiss();
    }

    private LinearLayout.LayoutParams previewParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ui.px(176), ui.px(99));
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        lp.setMargins(0, ui.px(2), 0, ui.px(14));
        return lp;
    }

    /** 首页上的样子的图标，放在白色卡片上：它的横幅，或它的图标。 */
    private View preview() {
        FrameLayout card = new FrameLayout(activity);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(art == null ? 0xFF2A2A2E : Color.WHITE);
        bg.setCornerRadius(14 * ui.dp);
        card.setBackground(bg);
        card.setClipToOutline(true);
        ImageView img = new ImageView(activity);
        if (art != null) {
            Drawable copy = art.getConstantState() != null ? art.getConstantState().newDrawable(activity.getResources()) : art;
            img.setImageDrawable(copy);
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            card.addView(img, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        } else if (icon != null) {
            img.setImageDrawable(icon.getConstantState() != null ? icon.getConstantState().newDrawable(activity.getResources()) : icon);
            card.addView(img, new FrameLayout.LayoutParams(ui.px(56), ui.px(56), Gravity.CENTER));
        }
        return card;
    }

    private void build(int focus) {
        column.removeAllViews();
        if (folderPage) {
            header.setText(R.string.menu_to_folder);
            for (Folders.Folder f : Folders.targets(activity)) {
                if (f.id.equals(folderIdOfApp())) continue;
                column.addView(ui.navRow(R.drawable.ic_folder, Folders.displayName(activity, f), null, false, () -> {
                    dismiss();
                    host.toFolder(key, f.id);
                }));
            }
            column.addView(ui.navRow(R.drawable.ic_plus, activity.getString(R.string.menu_new_folder), null, false, () -> {
                dismiss();
                host.toFolder(key, null);
            }));
        } else {
            header.setText(label);
            List<View> rows = new ArrayList<>();
            if (inFolder) {
                rows.add(row("out", R.drawable.ic_minus, R.string.menu_out_of_folder, null, () -> {
                    dismiss();
                    host.outOfFolder(key);
                }, false));
            } else {
                boolean dock = host.inDock(key);
                if (folderId == null) {
                    rows.add(dock ? row("undock", R.drawable.ic_minus, R.string.menu_remove_dock, null, () -> {
                        host.removeFromDock(key);
                        rebuild("undock");
                    }, false) : row("dock", R.drawable.ic_plus, R.string.tile_add_to_dock, null, () -> {
                        host.addToDock(key);
                        rebuild("dock");
                    }, false));
                }
                int[] dirs = {LEFT, RIGHT, UP, DOWN};
                int[] icons = {R.drawable.ic_arrow_left, R.drawable.ic_arrow_right, R.drawable.ic_arrow_up, R.drawable.ic_arrow_down};
                int[] names = {R.string.menu_move_left, R.string.menu_move_right, R.string.menu_move_up, R.string.menu_move_down};
                for (int i = 0; i < 4; i++) {
                    int dir = dirs[i];
                    if (!host.canMove(key, dir)) continue;
                    rows.add(row("move" + dir, icons[i], names[i], null, () -> {
                        host.move(key, dir);
                        rebuild("move" + dir);
                    }, false));
                }
                if (folderId == null) {
                    rows.add(row("folder", R.drawable.ic_folder, R.string.menu_to_folder, null, () -> {
                        folderPage = true;
                        build(0);
                    }, true));
                } else {
                    rows.add(row("rename", R.drawable.ic_p_title, R.string.menu_rename_folder, null, () -> {
                        dismiss();
                        host.renameFolder(folderId);
                    }, false));
                    rows.add(row("ungroup", R.drawable.ic_minus, R.string.menu_ungroup, null, () -> {
                        dismiss();
                        host.ungroup(folderId);
                    }, false));
                }
            }
            for (View r : rows) column.addView(r);
            if (folderId == null) {
                column.addView(ui.divider());
                column.addView(row("info", R.drawable.ic_info, R.string.library_app_info,
                        activity.getString(R.string.menu_info_detail), () -> {
                            dismiss();
                            host.appInfo(key);
                        }, false));
            }
        }
        List<View> all = focusables();
        if (!all.isEmpty()) {
            all.get(0).setOnFocusChangeListener((v, f) -> {
                if (f && scroll != null) scroll.smoothScrollTo(0, 0);
            });
            all.get(Math.max(0, Math.min(focus, all.size() - 1))).requestFocus();
        }
    }

    private View row(String id, int icon, int title, CharSequence detail, Runnable action, boolean chevron) {
        LinearLayout r = ui.navRow(icon, activity.getString(title), detail, chevron, action);
        r.setTag(R.id.menu_action, id);
        return r;
    }

    /** 移了一格之后：重新生成各行（这一步可能让别的方向变得可移或不可移），焦点留在同一个操作上。 */
    private void rebuild(String keepFocusOn) {
        build(0);
        for (View v : focusables()) {
            if (keepFocusOn.equals(v.getTag(R.id.menu_action))) {
                v.requestFocus();
                return;
            }
        }
    }

    private String folderIdOfApp() {
        Folders.Folder f = Folders.of(Folders.load(activity), key);
        return f == null ? "" : f.id;
    }

    private List<View> focusables() {
        List<View> out = new ArrayList<>();
        for (int i = 0; i < column.getChildCount(); i++) {
            View v = column.getChildAt(i);
            if (v.isFocusable()) out.add(v);
        }
        return out;
    }

    private boolean onKey(KeyEvent event) {
        KeySounds.onKey(event);
        int code = event.getKeyCode();
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE || code == KeyEvent.KEYCODE_MENU) {
            if (!down && !event.isCanceled()) {
                if (folderPage) {
                    folderPage = false;
                    build(0);
                } else {
                    dismiss();
                }
            }
            return true;
        }
        if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) return true;
        if (down && (code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_DPAD_UP)) {
            List<View> all = focusables();
            if (all.isEmpty()) return true;
            View edge = all.get(code == KeyEvent.KEYCODE_DPAD_DOWN ? all.size() - 1 : 0);
            if (edge.isFocused()) {
                KeySounds.limit();
                return true;
            }
            column.post(KeySounds::focusMoved);
        }
        return false;
    }
}
