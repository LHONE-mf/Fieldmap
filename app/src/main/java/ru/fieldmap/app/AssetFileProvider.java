package ru.fieldmap.app;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import java.io.File;
import java.io.FileNotFoundException;

public class AssetFileProvider extends ContentProvider {
    public static Uri uriFor(Context c, String name) {
        return new Uri.Builder().scheme("content")
            .authority(c.getPackageName() + ".files").path(name).build();
    }
    private File file(Uri uri) {
        String n = uri.getLastPathSegment();
        if (n == null || n.contains("..")) return null;
        return new File(getContext().getCacheDir(), n);
    }
    @Override public boolean onCreate() { return true; }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File f = file(uri);
        if (f == null) throw new FileNotFoundException();
        int m = (mode != null && (mode.contains("w") || mode.contains("t")))
            ? ParcelFileDescriptor.MODE_READ_WRITE | ParcelFileDescriptor.MODE_CREATE
            : ParcelFileDescriptor.MODE_READ_ONLY;
        return ParcelFileDescriptor.open(f, m);
    }
    @Override public Cursor query(Uri uri, String[] p, String s, String[] a, String o) {
        File f = file(uri);
        MatrixCursor c = new MatrixCursor(new String[]{MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE});
        c.addRow(new Object[]{f != null ? f.getName() : "file", f != null ? f.length() : 0});
        return c;
    }
    @Override public String getType(Uri uri) {
        String n = String.valueOf(uri.getLastPathSegment()).toLowerCase();
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".mp4")) return "video/mp4";
        if (n.endsWith(".pdf")) return "application/pdf";
        if (n.endsWith(".doc") || n.endsWith(".docx")) return "application/msword";
        if (n.endsWith(".xls") || n.endsWith(".xlsx")) return "application/vnd.ms-excel";
        if (n.endsWith(".json")) return "application/json";
        return "application/octet-stream";
    }
    @Override public Uri insert(Uri u, ContentValues v) { return null; }
    @Override public int delete(Uri u, String s, String[] a) { return 0; }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { return 0; }
}
