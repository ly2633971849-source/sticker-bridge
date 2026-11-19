package com.stickerbridge.app;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.util.Arrays;
import java.util.List;

public final class SourceIndexFilesProbe {
    public static void main(String[] args) throws Exception {
        Path root = Paths.get(args[0]);
        SourceIndexFiles index = new SourceIndexFiles(root.toFile());
        check(index.newestRecords().isEmpty(), "fresh installs start with an empty index");

        Path previous = Files.createDirectory(root.resolve("media_index_v3"));
        File oldGif = record(previous, "gif.json", 2000);
        File oldWebp = record(previous, "shared.json", 4000);
        File oldHeif = record(previous, "heif.json", 1000);
        check(index.newestRecords().equals(Arrays.asList(oldWebp, oldGif, oldHeif)), "upgrade sees old records by recency");
        check(index.accepts(oldGif, "gif") && index.accepts(oldWebp, "webp"), "reusable animated records survive upgrade");
        check(index.accepts(oldGif, "jpeg") && index.accepts(oldGif, "live_photo"), "unaffected still and motion JPEG formats survive upgrade");
        check(!index.accepts(oldHeif, "heif") && !index.accepts(oldHeif, "video"), "legacy ISO labels must be recomputed");
        check(!index.accepts(oldGif, "unknown"), "reject unrecognized legacy classification");
        check(!index.accepts(root.resolve("other.json").toFile(), "gif"), "limit reads to the two index directories");

        Path current = Files.createDirectory(index.writeDirectory().toPath());
        check(current.equals(root.resolve("media_index_v4")), "all new entries are written into v4");
        File currentWebp = record(current, "shared.json", 3000);
        File currentSequence = record(current, "sequence.json", 5000);
        check(index.newestRecords().equals(Arrays.asList(currentSequence, currentWebp, oldGif, oldHeif)),
                "read only the current copy for duplicates while keeping legacy-only records");
        check(index.previousVersion(currentWebp).equals(oldWebp), "find the old entry by the same cache key");
        check(index.previousVersion(oldWebp) == null, "a legacy cache miss cannot fall back to itself");
        check(index.accepts(currentSequence, "video"), "accept a newly classified animation");
        List<File> all = index.allRecords();
        check(all.containsAll(Arrays.asList(currentWebp, oldWebp)), "invalidation receives both copies");
        System.out.println("Source index upgrade regression passed");
    }

    private static File record(Path directory, String name, long modified) throws Exception {
        Path path = Files.createFile(directory.resolve(name));
        Files.setLastModifiedTime(path, FileTime.fromMillis(modified));
        return path.toFile();
    }

    private static void check(boolean condition, String failure) {
        if (!condition) throw new AssertionError(failure);
    }
}
