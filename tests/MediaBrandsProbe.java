package com.stickerbridge.app;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

public final class MediaBrandsProbe {
    private static byte[] box(boolean extended, String major, String minor, String... compatible)
            throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream stream = new DataOutputStream(bytes)) {
            int size = (extended ? 24 : 16) + compatible.length * 4;
            stream.writeInt(extended ? 1 : size);
            stream.writeBytes("ftyp");
            if (extended) stream.writeLong(size);
            stream.writeBytes(major);
            stream.writeBytes(minor);
            for (String brand : compatible) stream.writeBytes(brand);
        }
        return bytes.toByteArray();
    }

    private static byte[] followingBox(byte[] head) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.write(head);
        try (DataOutputStream stream = new DataOutputStream(bytes)) {
            stream.writeInt(12);
            stream.writeBytes("mdat");
            stream.writeBytes("msf1");
        }
        return bytes.toByteArray();
    }

    private static void check(Path path, String label, byte[] input, IsoMediaBrands.Kind expected)
            throws Exception {
        Files.write(path, input);
        IsoMediaBrands.Kind found = IsoMediaBrands.inspect(path.toFile());
        if (found != expected) throw new AssertionError(label + ": " + expected + " != " + found);
    }

    public static void main(String[] args) throws Exception {
        Path path = Path.of(args[0], "brands.bin");
        check(path, "static HEIC", box(false, "heic", "0000", "mif1"), IsoMediaBrands.Kind.STILL);
        check(path, "static AVIF", box(false, "avif", "0000", "mif1"), IsoMediaBrands.Kind.STILL);
        check(path, "mixed static and sequence", box(false, "mif1", "0000", "mif1", "msf1"),
                IsoMediaBrands.Kind.SEQUENCE);
        check(path, "sequence with still-compatible brand", box(false, "msf1", "0000", "mif1"),
                IsoMediaBrands.Kind.SEQUENCE);
        check(path, "HEVC sequence profile", box(false, "heic", "0000", "hevs"),
                IsoMediaBrands.Kind.SEQUENCE);
        check(path, "AVIF sequence classification", box(false, "avis", "0000", "avif", "mif1"),
                IsoMediaBrands.Kind.SEQUENCE);
        check(path, "regular MP4", box(false, "isom", "0000", "mp42", "avc1"),
                IsoMediaBrands.Kind.VIDEO);
        check(path, "minor version is not a brand", box(false, "mif1", "msf1", "heic"),
                IsoMediaBrands.Kind.STILL);
        check(path, "following box is not part of the brand list", followingBox(box(false, "mif1", "0000")),
                IsoMediaBrands.Kind.STILL);
        check(path, "extended size", box(true, "mif1", "0000", "mif1", "hevm"),
                IsoMediaBrands.Kind.SEQUENCE);
        String[] brands = new String[32];
        java.util.Arrays.fill(brands, "mif1");
        brands[brands.length - 1] = "msf1";
        check(path, "sequence brand beyond old 64-byte probe", box(false, "mif1", "0000", brands),
                IsoMediaBrands.Kind.SEQUENCE);
        byte[] invalid = box(false, "mif1", "0000", "msf1");
        invalid[3]++;
        check(path, "invalid declared box length", invalid, IsoMediaBrands.Kind.UNRECOGNIZED);
        check(path, "truncated extended header", java.util.Arrays.copyOf(box(true, "mif1", "0000"), 16),
                IsoMediaBrands.Kind.UNRECOGNIZED);
        System.out.println("Media brand regression passed (13 cases)");
    }
}
