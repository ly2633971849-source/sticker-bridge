package com.stickerbridge.app;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.SystemClock;
import java.util.EnumMap;
import org.json.JSONObject;

final class Diagnostics {
    enum Operation { NONE, PICK, IMPORT, LIBRARY }
    enum Phase { IDLE, CAPTURE, SOURCE_LOOKUP, INPUT_READ, MEDIA_INSPECT, CONVERT, ALBUM_WRITE }
    enum Result { IDLE, RUNNING, SAVED, COMPLETED, FAILED }
    enum Failure { NONE, CANCELLED, CAPTURE_UNAVAILABLE, NO_TARGET, MOTION_UNAVAILABLE, NO_ORIGINAL, NO_PERMISSION, CONNECTION, INVALID_INPUT, IO, MEMORY, UNEXPECTED }

    private static final EnumMap<Phase, Long> timings = new EnumMap<>(Phase.class);
    private static Operation operation = Operation.NONE;
    private static Phase phase = Phase.IDLE;
    private static Result result = Result.IDLE;
    private static Failure failure = Failure.NONE;
    private static long phaseStarted;

    static boolean debug(Context context) {
        return (context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
    }

    static synchronized void begin(Operation next) {
        operation = next;
        phase = Phase.IDLE;
        result = Result.RUNNING;
        failure = Failure.NONE;
        timings.clear();
        phaseStarted = SystemClock.elapsedRealtime();
    }

    static synchronized void phase(Phase next) {
        if (result != Result.RUNNING || phase == next) return;
        long now = SystemClock.elapsedRealtime();
        record(now);
        phase = next;
        phaseStarted = now;
    }

    static synchronized void end(Result next, Failure code) {
        if (result != Result.RUNNING) return;
        record(SystemClock.elapsedRealtime());
        result = next;
        failure = code;
    }

    private static void record(long now) {
        if (phase != Phase.IDLE) timings.put(phase, timings.getOrDefault(phase, 0L) + Math.max(0L, now - phaseStarted));
    }

    static synchronized String summary(Context context) {
        JSONObject info = new JSONObject();
        try {
            PackageInfo app = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            JSONObject elapsed = new JSONObject();
            for (Phase key : Phase.values()) {
                long ms = timings.getOrDefault(key, 0L);
                if (key == phase && key != Phase.IDLE && result == Result.RUNNING) ms += Math.max(0L, SystemClock.elapsedRealtime() - phaseStarted);
                if (ms > 0L) elapsed.put(key.name(), ms);
            }
            info.put("schema", 1).put("version", app.versionName).put("versionCode", app.getLongVersionCode())
                    .put("android", Build.VERSION.RELEASE).put("api", Build.VERSION.SDK_INT)
                    .put("manufacturer", Build.MANUFACTURER).put("model", Build.MODEL)
                    .put("pickerEnabled", PickerSettings.enabled(context)).put("pickerConnected", BridgeAccessibility.live != null)
                    .put("operation", operation.name()).put("phase", phase.name()).put("result", result.name())
                    .put("failure", failure.name()).put("phaseMs", elapsed);
            return info.toString(2);
        } catch (Exception ignored) { return "{\"schema\":1,\"error\":\"unavailable\"}"; }
    }
}
