package com.stickerbridge.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.SystemClock;
import android.provider.Settings;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONObject;
import org.json.JSONTokener;

public final class AppUpdates {
    private static final int MAX_MANIFEST_BYTES = 32 * 1024;
    private static final long MAX_APK_BYTES = 32L * 1024 * 1024;
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();

    private static final class Release {
        final long versionCode;
        final String versionName, notes, sha256;
        final URI apkUrl;
        final long sizeBytes;

        Release(URI origin, JSONObject json) throws Exception {
            versionCode = integer(json, "versionCode");
            versionName = string(json, "versionName").trim();
            notes = string(json, "notes").trim();
            sha256 = string(json, "sha256").toLowerCase(Locale.ROOT);
            apkUrl = httpsUri(string(json, "apkUrl"));
            sizeBytes = integer(json, "sizeBytes");
            if (versionCode <= 0 || versionCode > 2100000000L || versionName.isEmpty()
                    || versionName.length() > 128 || notes.length() > 4000 || !sha256.matches("[0-9a-f]{64}")
                    || sizeBytes <= 0 || sizeBytes > MAX_APK_BYTES || !origin.getHost().equalsIgnoreCase(apkUrl.getHost())
                    || port(origin) != port(apkUrl)) throw new IOException("更新信息无效。");
        }
    }

    private interface Task<T> { T run(Progress progress) throws Exception; }
    private interface Result<T> { void show(T value); }

    private static final class Progress {
        final AtomicBoolean cancelled = new AtomicBoolean();
        final Activity activity;
        final AlertDialog dialog;

        Progress(Activity activity, AlertDialog dialog) {
            this.activity = activity;
            this.dialog = dialog;
        }

        void check(long deadline) throws IOException {
            if (cancelled.get()) throw new IOException("已取消。");
            if (SystemClock.elapsedRealtime() > deadline) throw new IOException("连接更新服务器超时。");
        }

        void percent(int percent) {
            activity.runOnUiThread(() -> {
                if (!cancelled.get() && usable(activity)) dialog.setMessage("正在下载更新… " + percent + "%");
            });
        }
    }

    public static void show(Activity activity) {
        String raw = activity.getString(R.string.app_update_manifest).trim();
        if (raw.isEmpty()) {
            message(activity, "检查更新", "此安装包还未配置更新服务器。");
            return;
        }
        run(activity, "正在检查更新…", "暂时连不上更新服务器，过会儿再试。", progress -> readRelease(raw, progress), release -> {
            String installedVersion;
            try {
                PackageInfo current = installed(activity);
                if (release.versionCode <= current.getLongVersionCode()) {
                    message(activity, "已是最新版本", "当前版本：" + current.versionName);
                    return;
                }
                installedVersion = current.versionName;
            } catch (PackageManager.NameNotFoundException error) {
                message(activity, "无法检查更新", "暂时无法读取当前版本。");
                return;
            }
            String instructions = "当前安装版本：" + installedVersion + "\n可更新版本：" + release.versionName
                    + "\n下载完成后，请在安卓系统中确认安装。";
            new AlertDialog.Builder(activity).setTitle("发现新版本 " + release.versionName)
                    .setMessage(instructions + (release.notes.isEmpty() ? "" : "\n\n" + release.notes))
                    .setNegativeButton("稍后", null)
                    .setPositiveButton("下载更新", (dialog, button) -> download(activity, release)).show();
        });
    }

    private static void download(Activity activity, Release release) {
        Context context = activity.getApplicationContext();
        run(activity, "正在准备更新…", "下载或校验未完成，未安装任何文件。请重试。",
                progress -> verifiedApk(context, release, progress), file -> ready(activity, file, release.versionName));
    }

    private static void ready(Activity activity, File file, String versionName) {
        PackageManager manager = activity.getPackageManager();
        AlertDialog ready = new AlertDialog.Builder(activity).setTitle("安装包已校验")
                .setMessage("已下载并校验 " + versionName + "。点「继续安装」，再在安卓系统弹窗中确认覆盖安装。")
                .setNegativeButton("稍后", null)
                .setPositiveButton("继续安装", null).create();
        ready.setOnShowListener(dialog -> ready.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            if (!manager.canRequestPackageInstalls()) {
                new AlertDialog.Builder(activity).setTitle("首次安装更新")
                        .setMessage("请在系统设置允许「表情直达」安装应用，再返回这里点「继续安装」。")
                        .setNegativeButton("稍后", null)
                        .setPositiveButton("打开系统设置", (notice, button) -> {
                            Uri packageUri = Uri.fromParts("package", activity.getPackageName(), null);
                            startSystem(activity, new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, packageUri),
                                    "这台手机未提供安装来源设置入口。");
                        }).show();
                return;
            }
            Uri uri = UpdateFileProvider.uri(file);
            Intent intent = new Intent(Intent.ACTION_INSTALL_PACKAGE).setDataAndType(uri, UpdateFileProvider.MIME)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.setClipData(ClipData.newRawUri("表情直达更新", uri));
            if (startSystem(activity, intent, "这台手机未提供可用的系统安装器。")) ready.dismiss();
        }));
        ready.show();
    }

    private static boolean startSystem(Activity activity, Intent intent, String unavailable) {
        PackageManager manager = activity.getPackageManager();
        for (ResolveInfo match : manager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)) {
            if (match.activityInfo == null || !match.activityInfo.exported || match.activityInfo.applicationInfo == null) continue;
            int systemFlags = ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP;
            if ((match.activityInfo.applicationInfo.flags & systemFlags) == 0) continue;
            try {
                intent.setComponent(new ComponentName(match.activityInfo.packageName, match.activityInfo.name));
                activity.startActivity(intent);
                return true;
            } catch (android.content.ActivityNotFoundException | SecurityException ignored) {}
        }
        message(activity, "无法打开", unavailable);
        return false;
    }

    private static <T> void run(Activity activity, String title, String errorMessage, Task<T> task, Result<T> result) {
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle("应用更新").setMessage(title)
                .setNegativeButton("取消", null).create();
        Progress progress = new Progress(activity, dialog);
        dialog.setOnDismissListener(ignored -> progress.cancelled.set(true));
        dialog.show();
        WORKER.execute(() -> {
            if (progress.cancelled.get()) return;
            try {
                T value = task.run(progress);
                activity.runOnUiThread(() -> {
                    if (progress.cancelled.get() || !usable(activity) || !dialog.isShowing()) return;
                    dialog.dismiss();
                    result.show(value);
                });
            } catch (Exception error) {
                activity.runOnUiThread(() -> {
                    if (progress.cancelled.get() || !usable(activity) || !dialog.isShowing()) return;
                    dialog.dismiss();
                    message(activity, "更新未完成", error instanceof ValidationFailure ? error.getMessage() : errorMessage);
                });
            }
        });
    }

    private static void message(Activity activity, String title, String message) {
        if (usable(activity)) new AlertDialog.Builder(activity).setTitle(title).setMessage(message)
                .setPositiveButton("知道了", null).show();
    }

    private static boolean usable(Activity activity) { return !activity.isFinishing() && !activity.isDestroyed(); }

    private static Release readRelease(String raw, Progress progress) throws Exception {
        URI origin = httpsUri(raw);
        long deadline = SystemClock.elapsedRealtime() + 15000;
        HttpsURLConnection connection = open(origin);
        try (InputStream input = body(connection, MAX_MANIFEST_BYTES); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) {
                progress.check(deadline);
                if (output.size() + count > MAX_MANIFEST_BYTES) throw new IOException("更新信息过大。");
                output.write(buffer, 0, count);
            }
            progress.check(deadline);
            try {
                JSONTokener parser = new JSONTokener(new String(output.toByteArray(), StandardCharsets.UTF_8));
                Object value = parser.nextValue();
                if (value instanceof JSONObject && parser.nextClean() == 0) return new Release(origin, (JSONObject) value);
            } catch (Exception ignored) {}
            throw new ValidationFailure("服务器上的更新信息无效，请稍后再试。");
        } finally { connection.disconnect(); }
    }

    private static File verifiedApk(Context context, Release release, Progress progress) throws Exception {
        File directory = UpdateFileProvider.directory(context);
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("无法准备下载目录。");
        File verified = new File(directory, "sticker-bridge-" + release.sha256 + ".apk");
        long deadline = SystemClock.elapsedRealtime() + 90000;
        File[] oldFiles = directory.listFiles();
        if (oldFiles != null) for (File old : oldFiles) {
            if (!old.equals(verified) && old.isFile() && old.lastModified() < System.currentTimeMillis() - 86400000L) old.delete();
        }
        if (verified.isFile() && verified.length() == release.sizeBytes) {
            try (InputStream input = new FileInputStream(verified)) {
                if (copyAndVerify(input, null, release, progress, deadline)) {
                    verifyArchive(context, verified, release);
                    return verified;
                }
            }
        }
        File pending = File.createTempFile("pending-update-", ".apk", directory);
        try {
            HttpsURLConnection connection = open(release.apkUrl);
            try (InputStream input = body(connection, release.sizeBytes); FileOutputStream output = new FileOutputStream(pending)) {
                if (!copyAndVerify(input, output, release, progress, deadline))
                    throw new ValidationFailure("安装包大小或校验值不符，已停止更新。");
            } finally { connection.disconnect(); }
            verifyArchive(context, pending, release);
            progress.check(deadline);
            Files.move(pending.toPath(), verified.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return verified;
        } finally {
            pending.delete();
        }
    }

    private static boolean copyAndVerify(InputStream input, FileOutputStream output, Release release,
                                         Progress progress, long deadline) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[32768];
        long total = 0;
        int count, lastPercent = 0;
        while ((count = input.read(buffer)) != -1) {
            progress.check(deadline);
            total += count;
            if (total > release.sizeBytes) return false;
            digest.update(buffer, 0, count);
            if (output != null) {
                output.write(buffer, 0, count);
                int percent = Math.min(99, (int) (total * 100 / release.sizeBytes));
                if (percent - lastPercent >= 5) {
                    lastPercent = percent;
                    progress.percent(percent);
                }
            }
        }
        progress.check(deadline);
        StringBuilder hex = new StringBuilder(64);
        for (byte value : digest.digest()) hex.append(Character.forDigit((value >>> 4) & 15, 16)).append(Character.forDigit(value & 15, 16));
        return total == release.sizeBytes && release.sha256.contentEquals(hex);
    }

    private static final class ValidationFailure extends IOException {
        ValidationFailure(String message) { super(message); }
    }

    private static void verifyArchive(Context context, File apk, Release release) throws Exception {
        PackageInfo current = installed(context);
        PackageInfo candidate = context.getPackageManager().getPackageArchiveInfo(apk.getAbsolutePath(), PackageManager.GET_SIGNING_CERTIFICATES);
        if (candidate == null || !context.getPackageName().equals(candidate.packageName)
                || candidate.getLongVersionCode() != release.versionCode || release.versionCode <= current.getLongVersionCode()
                || !release.versionName.equals(candidate.versionName))
            throw new ValidationFailure("安装包的应用或版本信息不符，已停止更新。");
        Signature[] own = current.signingInfo == null ? null : current.signingInfo.getApkContentsSigners();
        Signature[] next = candidate.signingInfo == null ? null : candidate.signingInfo.getApkContentsSigners();
        if (own == null || next == null || own.length == 0 || own.length != next.length
                || !new HashSet<>(Arrays.asList(own)).equals(new HashSet<>(Arrays.asList(next))))
            throw new ValidationFailure("安装包与当前版本的签名不同，已停止更新。");
    }

    private static PackageInfo installed(Context context) throws PackageManager.NameNotFoundException {
        return context.getPackageManager().getPackageInfo(context.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
    }

    private static long integer(JSONObject json, String key) throws Exception {
        Object value = json.get(key);
        if (!(value instanceof Integer) && !(value instanceof Long)) throw new IOException("更新信息无效。");
        return ((Number) value).longValue();
    }

    private static String string(JSONObject json, String key) throws Exception {
        Object value = json.get(key);
        if (!(value instanceof String)) throw new IOException("更新信息无效。");
        return (String) value;
    }

    private static URI httpsUri(String raw) throws Exception {
        URI uri = new URI(raw);
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getFragment() != null || uri.getPort() == 0 || uri.getPort() > 65535) throw new IOException("更新地址无效。");
        return uri;
    }

    private static int port(URI url) { return url.getPort() == -1 ? 443 : url.getPort(); }

    private static HttpsURLConnection open(URI url) throws IOException {
        HttpsURLConnection connection = (HttpsURLConnection) url.toURL().openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(7000);
        connection.setReadTimeout(7000);
        connection.setUseCaches(false);
        connection.setRequestProperty("Accept-Encoding", "identity");
        return connection;
    }

    private static InputStream body(HttpsURLConnection connection, long max) throws IOException {
        if (connection.getResponseCode() != HttpsURLConnection.HTTP_OK || connection.getContentLengthLong() > max)
            throw new IOException("服务器没有返回完整更新文件。");
        return connection.getInputStream();
    }
}
