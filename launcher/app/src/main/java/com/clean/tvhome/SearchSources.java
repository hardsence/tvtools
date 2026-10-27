package com.clean.tvhome;

import android.app.SearchManager;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.XmlResourceParser;
import android.database.Cursor;
import android.net.Uri;
import android.os.CancellationSignal;
import android.text.TextUtils;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * Aggregated search across installed apps, the way Android TV's global search does it: every
 * activity handling ACTION_SEARCH with android.app.searchable metadata that names a
 * searchSuggestAuthority is queried at content://authority[/path]/search_suggest_query.
 * (developer.android.com/training/tv/discovery/searchable)
 */
final class SearchSources {

    private static final String TAG = "CleanHomeSearch";
    private static final String NS = "http://schemas.android.com/apk/res/android";

    /** One app's searchable description. */
    static final class Source {
        String pkg, label, authority, path, selection, intentAction, intentData;
        ComponentName activity;
    }

    static final class Result {
        String title, subtitle, image, query;
        Intent intent;
    }

    private SearchSources() {}

    static List<Source> discover(Context c) {
        List<Source> out = new ArrayList<>();
        PackageManager pm = c.getPackageManager();
        for (ResolveInfo ri : pm.queryIntentActivities(new Intent(Intent.ACTION_SEARCH), PackageManager.GET_META_DATA)) {
            ActivityInfo ai = ri.activityInfo;
            if (ai.packageName.equals(c.getPackageName())) continue;
            try (XmlResourceParser x = ai.loadXmlMetaData(pm, "android.app.searchable")) {
                if (x == null) continue;
                int ev;
                while ((ev = x.next()) != XmlResourceParser.END_DOCUMENT) {
                    if (ev != XmlResourceParser.START_TAG || !"searchable".equals(x.getName())) continue;
                    Source s = new Source();
                    android.content.res.Resources res = pm.getResourcesForApplication(ai.applicationInfo);
                    s.authority = attr(x, res, "searchSuggestAuthority");
                    if (TextUtils.isEmpty(s.authority)) break;
                    s.pkg = ai.packageName;
                    s.label = ri.loadLabel(pm).toString();
                    s.path = attr(x, res, "searchSuggestPath");
                    s.selection = attr(x, res, "searchSuggestSelection");
                    s.intentAction = attr(x, res, "searchSuggestIntentAction");
                    s.intentData = attr(x, res, "searchSuggestIntentData");
                    s.activity = new ComponentName(ai.packageName, ai.name);
                    boolean dup = false;
                    for (Source o : out) dup |= o.authority.equals(s.authority);
                    if (!dup) out.add(s);
                    break;
                }
            } catch (Exception e) {
                Log.w(TAG, "searchable " + ai.packageName + ": " + e);
            }
        }
        return out;
    }

    /** An attribute's text, resolving "@string/..." references through the app's resources. */
    private static String attr(XmlResourceParser x, android.content.res.Resources res, String name) {
        int id = x.getAttributeResourceValue(NS, name, 0);
        if (id != 0) {
            try {
                return res.getString(id);
            } catch (Exception ignored) {
            }
        }
        String v = x.getAttributeValue(NS, name);
        return v != null && v.startsWith("@") ? null : v;
    }

    /** Same request SearchManager.getSuggestions() builds. Blocking; returns [] on any failure. */
    static List<Result> query(Context c, Source s, String q, int limit, CancellationSignal cancel) {
        List<Result> out = new ArrayList<>();
        Uri.Builder b = new Uri.Builder().scheme(ContentResolver.SCHEME_CONTENT).authority(s.authority);
        if (!TextUtils.isEmpty(s.path)) b.appendEncodedPath(s.path);
        b.appendPath(SearchManager.SUGGEST_URI_PATH_QUERY);
        String[] args = null;
        if (s.selection != null) args = new String[] {q};
        else b.appendPath(q);
        b.appendQueryParameter(SearchManager.SUGGEST_PARAMETER_LIMIT, String.valueOf(limit));
        try (Cursor cur = c.getContentResolver().query(b.build(), null, s.selection, args, null, cancel)) {
            if (cur == null) return out;
            while (cur.moveToNext() && out.size() < limit) {
                Result r = new Result();
                r.title = col(cur, SearchManager.SUGGEST_COLUMN_TEXT_1);
                if (TextUtils.isEmpty(r.title)) continue;
                r.subtitle = col(cur, SearchManager.SUGGEST_COLUMN_TEXT_2);
                String year = col(cur, SearchManager.SUGGEST_COLUMN_PRODUCTION_YEAR);
                if (TextUtils.isEmpty(r.subtitle) && !TextUtils.isEmpty(year)) r.subtitle = year;
                r.image = col(cur, SearchManager.SUGGEST_COLUMN_RESULT_CARD_IMAGE);
                if (TextUtils.isEmpty(r.image)) r.image = col(cur, SearchManager.SUGGEST_COLUMN_ICON_1);
                r.query = col(cur, SearchManager.SUGGEST_COLUMN_QUERY);
                r.intent = intent(s, cur, q);
                out.add(r);
            }
        } catch (Exception e) {
            Log.w(TAG, s.pkg + " (" + s.authority + "): " + e);
        }
        return out;
    }

    private static String col(Cursor c, String name) {
        int i = c.getColumnIndex(name);
        if (i < 0) return null;
        try {
            return c.getString(i);
        } catch (Exception e) {
            return null;
        }
    }

    /** The intent the app expects for a chosen suggestion (as SearchManager would send it). */
    private static Intent intent(Source s, Cursor cur, String q) {
        String action = col(cur, SearchManager.SUGGEST_COLUMN_INTENT_ACTION);
        if (action == null) action = s.intentAction != null ? s.intentAction : Intent.ACTION_SEARCH;
        String data = col(cur, SearchManager.SUGGEST_COLUMN_INTENT_DATA);
        if (data == null) data = s.intentData;
        String id = col(cur, SearchManager.SUGGEST_COLUMN_INTENT_DATA_ID);
        if (data != null && id != null) data = data + "/" + Uri.encode(id);
        Intent i = new Intent(action);
        if (data != null) i.setData(Uri.parse(data));
        String extra = col(cur, SearchManager.SUGGEST_COLUMN_INTENT_EXTRA_DATA);
        if (extra != null) i.putExtra(SearchManager.EXTRA_DATA_KEY, extra);
        String query = col(cur, SearchManager.SUGGEST_COLUMN_QUERY);
        i.putExtra(SearchManager.QUERY, query != null ? query : q);
        i.setComponent(s.activity);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return i;
    }
}
