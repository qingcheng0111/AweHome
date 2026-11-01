package com.gathin.tvhome;

import android.content.Context;
import android.util.AtomicFile;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * AweHome：应用文件夹（长按菜单里的「移到文件夹」），做法同 Aerial（apps/LayoutStore.kt），按图标的键存在
 * files/folders.json 里。自带一个「隐藏文件夹」：放进去的应用离开网格，待在网格末尾的这个文件夹里，直到用户再把它们移出来。
 */
final class Folders {
    private static final String TAG = "TvHome.Folders";
    private static final String FILE_NAME = "folders.json";
    static final String HIDDEN = "hidden";
    /** 文件夹图标的键里类名的前缀："com.gathin.tvhome/folder:f3"。 */
    static final String KEY_PREFIX = "folder:";

    static final class Folder {
        final String id;
        String name;
        final List<String> apps = new ArrayList<>();

        Folder(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    private Folders() {
    }

    static File file(Context ctx) {
        return new File(ctx.getFilesDir(), FILE_NAME);
    }

    static List<Folder> load(Context ctx) {
        List<Folder> out = new ArrayList<>();
        File f = file(ctx);
        if (!f.isFile()) return out;
        try {
            JSONArray a = new JSONObject(new String(new AtomicFile(f).readFully(), StandardCharsets.UTF_8)).optJSONArray("folders");
            for (int i = 0; a != null && i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Folder folder = new Folder(o.getString("id"), o.optString("name", ""));
                JSONArray apps = o.optJSONArray("apps");
                for (int j = 0; apps != null && j < apps.length(); j++) folder.apps.add(apps.getString(j));
                out.add(folder);
            }
        } catch (Exception e) {
            Log.w(TAG, "unreadable folders: " + e);
        }
        return out;
    }

    static boolean save(Context ctx, List<Folder> folders) {
        AtomicFile file = new AtomicFile(file(ctx));
        FileOutputStream out = null;
        try {
            JSONArray a = new JSONArray();
            for (Folder f : folders) {
                if (f.apps.isEmpty() && !HIDDEN.equals(f.id)) continue;   // 文件夹空了就删掉
                JSONArray apps = new JSONArray();
                for (String k : f.apps) apps.put(k);
                a.put(new JSONObject().put("id", f.id).put("name", f.name).put("apps", apps));
            }
            out = file.startWrite();
            out.write(new JSONObject().put("folders", a).toString().getBytes(StandardCharsets.UTF_8));
            file.finishWrite(out);
            return true;
        } catch (Exception e) {
            if (out != null) file.failWrite(out);
            Log.w(TAG, "could not save folders: " + e);
            return false;
        }
    }

    static Folder find(List<Folder> folders, String id) {
        for (Folder f : folders) if (f.id.equals(id)) return f;
        return null;
    }

    /** 应用所在的文件夹，不在任何文件夹里时为 null。 */
    static Folder of(List<Folder> folders, String appKey) {
        for (Folder f : folders) if (f.apps.contains(appKey)) return f;
        return null;
    }

    /** 把应用放进文件夹（同时移出其他文件夹）；id 为 null 时新建一个名为 {@code name} 的文件夹。返回文件夹的 id。 */
    static String put(Context ctx, String appKey, String folderId, String name) {
        List<Folder> folders = load(ctx);
        for (Folder f : folders) f.apps.remove(appKey);
        Folder target = folderId == null ? null : find(folders, folderId);
        if (target == null && HIDDEN.equals(folderId)) {
            target = new Folder(HIDDEN, "");
            folders.add(target);
        }
        if (target == null) {
            int n = 1;
            while (find(folders, "f" + n) != null) n++;
            target = new Folder("f" + n, name);
            folders.add(target);
        }
        target.apps.add(appKey);
        save(ctx, folders);
        return target.id;
    }

    /** 把应用移出它所在的文件夹，回到网格。 */
    static void remove(Context ctx, String appKey) {
        List<Folder> folders = load(ctx);
        for (Folder f : folders) f.apps.remove(appKey);
        save(ctx, folders);
    }

    static void rename(Context ctx, String folderId, String name) {
        List<Folder> folders = load(ctx);
        Folder f = find(folders, folderId);
        if (f == null) return;
        f.name = name;
        save(ctx, folders);
    }

    /** 文件夹里的应用全部回到网格，文件夹删掉。 */
    static void ungroup(Context ctx, String folderId) {
        List<Folder> folders = load(ctx);
        Folder f = find(folders, folderId);
        if (f == null) return;
        f.apps.clear();
        if (HIDDEN.equals(folderId)) folders.remove(f);
        save(ctx, folders);
    }

    /** 应用可以移到的文件夹：隐藏文件夹在前，然后是用户建的，按名称排序。 */
    static List<Folder> targets(Context ctx) {
        List<Folder> out = new ArrayList<>(load(ctx));
        Collections.sort(out, (a, b) -> HIDDEN.equals(a.id) ? -1 : HIDDEN.equals(b.id) ? 1 : a.name.compareToIgnoreCase(b.name));
        if (find(out, HIDDEN) == null) out.add(0, new Folder(HIDDEN, ""));
        return out;
    }

    static String displayName(Context ctx, Folder f) {
        if (HIDDEN.equals(f.id)) return ctx.getString(R.string.folder_hidden);
        return f.name.isEmpty() ? ctx.getString(R.string.folder_new) : f.name;
    }
}
