package jp.stylopp;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
import android.util.AtomicFile;
import java.io.*;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Selected app UIDs get a fixed pen endpoint, never a shell or arbitrary file interface. */
public final class InputBridge extends ContentProvider {
    static final String AUTHORITY = "jp.stylopp.input", DESCRIPTOR = "jp.stylopp.Input.v1";
    static final int START = 1, KEEP = 2, STOP = 3, LOAD = 4, SAVE = 5;
    static final int MAX_DATA = 2 * 1024 * 1024;
    static final int MAX_WIRE = 500000;
    @Override public boolean onCreate() { return true; }
    boolean allowed(int uid, String pkg) {
        return uid >= 10000 && pkg != null && Arrays.asList(getContext().getPackageManager().getPackagesForUid(uid) == null ? new String[0] : getContext().getPackageManager().getPackagesForUid(uid)).contains(pkg)
            && StyloppApp.preferences(getContext()).getBoolean("enabled", false)
            && StyloppApp.preferences(getContext()).getStringSet("apps", Collections.emptySet()).contains(pkg);
    }
    @Override public Bundle call(String method, String pkg, Bundle extra) {
        int uid = Binder.getCallingUid();
        if (!("open".equals(method) || "new".equals(method)) || !allowed(uid, pkg)) throw new SecurityException("App is not selected");
        String document = extra == null ? null : extra.getString("document");
        if (document == null || document.length() > 300) throw new IllegalArgumentException("Invalid document key");
        File directory = new File(getContext().getFilesDir(), "overlays"); if (!directory.isDirectory() && !directory.mkdirs()) throw new IllegalStateException("Cannot open overlays");
        String identity = hash(pkg + ":" + document);
        android.content.SharedPreferences sheets = getContext().getSharedPreferences("sheets", 0);
        String current = sheets.getString(identity, identity);
        if ("new".equals(method)) current = java.util.UUID.randomUUID().toString();
        if (!current.matches("[0-9a-f-]{36,64}")) throw new IllegalStateException("Invalid sheet index");
        if (!sheets.edit().putString(identity, current).putString("label." + current, pkg + " · " + System.currentTimeMillis()).commit()) throw new IllegalStateException("Cannot save sheet index");
        File file = new File(directory, current + ".json");
        RootPenController controller = RootPenController.get(getContext()); controller.connect();
        Binder endpoint = new Binder() {
            @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
                if (code == INTERFACE_TRANSACTION) { if (reply != null) reply.writeString(DESCRIPTOR); return true; }
                if (Binder.getCallingUid() != uid || !allowed(uid, pkg)) { controller.stop(this); throw new SecurityException("App is not selected"); }
                data.enforceInterface(DESCRIPTOR);
                switch (code) {
                    case START: int type = data.readInt(); if (type < 0 || type > 4) throw new IllegalArgumentException(); controller.start(this, type); return true;
                    case KEEP: controller.keep(this); return true;
                    case STOP: controller.stop(this); return true;
                    case LOAD:
                        if (reply == null) throw new IllegalArgumentException();
                        String json = "[]";
                        if (file.exists() || new File(file.getPath() + ".bak").exists()) {
                            if (file.length() > MAX_DATA) throw new IllegalStateException("Overlay is too large");
                            try (InputStream input = new AtomicFile(file).openRead(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                                byte[] buffer = new byte[8192]; int count;
                                while ((count = input.read(buffer)) > 0) { if (bytes.size() + count > MAX_DATA) throw new IOException("Too large"); bytes.write(buffer, 0, count); }
                                json = bytes.toString("UTF-8");
                            } catch (IOException e) { throw new IllegalStateException("Cannot restore overlay", e); }
                        }
                        reply.writeNoException(); reply.writeByteArray(pack(json)); return true;
                    case SAVE:
                        if (reply == null || data.dataSize() > MAX_WIRE + 1024) throw new IllegalArgumentException("Overlay is too large");
                        String content = unpack(data.createByteArray());
                        try { new org.json.JSONArray(content); } catch (org.json.JSONException e) { throw new IllegalArgumentException("Invalid overlay"); }
                        synchronized (InputBridge.this) {
                            AtomicFile atomic = new AtomicFile(file); FileOutputStream out = null;
                            try { out = atomic.startWrite(); out.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8)); atomic.finishWrite(out); }
                            catch (IOException e) { if (out != null) atomic.failWrite(out); throw new IllegalStateException("Cannot save overlay", e); }
                        }
                        reply.writeNoException(); return true;
                    default: return false;
                }
            }
        };
        Bundle result = new Bundle(); result.putInt("version", 1); result.putBinder("input", endpoint); return result;
    }
    static byte[] pack(String text) {
        byte[] plain = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (plain.length > MAX_DATA) throw new IllegalArgumentException("Overlay is too large");
        try { ByteArrayOutputStream bytes = new ByteArrayOutputStream(); try (java.util.zip.GZIPOutputStream zip = new java.util.zip.GZIPOutputStream(bytes)) { zip.write(plain); }
            byte[] result = bytes.toByteArray(); if (result.length > MAX_WIRE) throw new IllegalArgumentException("Overlay is too large"); return result;
        } catch (IOException e) { throw new IllegalStateException(e); }
    }
    static String unpack(byte[] bytes) {
        if (bytes == null || bytes.length > MAX_WIRE) throw new IllegalArgumentException("Invalid overlay");
        try (java.util.zip.GZIPInputStream zip = new java.util.zip.GZIPInputStream(new ByteArrayInputStream(bytes)); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int count; while ((count = zip.read(buffer)) > 0) { if (out.size() + count > MAX_DATA) throw new IllegalArgumentException("Overlay is too large"); out.write(buffer, 0, count); }
            return out.toString("UTF-8");
        } catch (IOException e) { throw new IllegalArgumentException("Invalid overlay", e); }
    }
    private static String hash(String value) { try { byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)); StringBuilder result = new StringBuilder(); for (byte b : bytes) result.append(String.format("%02x", b)); return result.toString(); } catch (Exception e) { throw new IllegalStateException(e); } }
    @Override public String getType(Uri uri) { return null; }
    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) { throw new UnsupportedOperationException(); }
    @Override public Uri insert(Uri u, ContentValues v) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri u, String s, String[] a) { throw new UnsupportedOperationException(); }
}
