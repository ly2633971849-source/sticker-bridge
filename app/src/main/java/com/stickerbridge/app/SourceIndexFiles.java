package com.stickerbridge.app;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Keeps compatible fingerprints from upgrades without trusting obsolete container labels. */
final class SourceIndexFiles {
    private final File current;
    private final File previous;

    SourceIndexFiles(File cache) {
        current = new File(cache, "media_index_v4");
        previous = new File(cache, "media_index_v3");
    }

    File writeDirectory() {
        return current;
    }

    File previousVersion(File record) {
        return current.equals(record.getParentFile()) ? new File(previous, record.getName()) : null;
    }

    List<File> newestRecords() {
        Map<String, File> unique = new LinkedHashMap<>();
        for (File record : allRecords()) unique.putIfAbsent(record.getName(), record);
        List<File> records = new ArrayList<>(unique.values());
        Map<File, Long> modified = new HashMap<>();
        for (File record : records) modified.put(record, record.lastModified());
        records.sort(Comparator.<File>comparingLong(modified::get).reversed());
        return records;
    }

    List<File> allRecords() {
        List<File> records = new ArrayList<>();
        for (File directory : new File[]{current, previous}) {
            File[] found = directory.listFiles((dir, name) -> name.endsWith(".json"));
            if (found != null) Collections.addAll(records, found);
        }
        return records;
    }

    boolean accepts(File record, String container) {
        if (current.equals(record.getParentFile())) return true;
        // The v4 classifier can change the type of files with ISO BMFF headers. Reinspect those.
        if (!previous.equals(record.getParentFile())) return false;
        switch (container) {
            case "gif":
            case "webp":
            case "png":
            case "jpeg":
            case "live_photo":
            case "bmp":
                return true;
            default:
                return false;
        }
    }
}
