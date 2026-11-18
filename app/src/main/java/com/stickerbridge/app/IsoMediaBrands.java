package com.stickerbridge.app;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

final class IsoMediaBrands {
    enum Kind { UNRECOGNIZED, STILL, SEQUENCE, VIDEO }

    private static final int FTYP = 0x66747970;
    private static final int MIF1 = 0x6d696631;
    private static final int MAX_FTYP_BYTES = 4096;

    private IsoMediaBrands() {}

    static Kind inspect(File file) {
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            if (input.length() < 16) return Kind.UNRECOGNIZED;
            long size = Integer.toUnsignedLong(input.readInt());
            if (input.readInt() != FTYP) return Kind.UNRECOGNIZED;
            int headerSize = 8;
            if (size == 1) {
                size = input.readLong();
                headerSize = 16;
            }
            if (size < headerSize + 8 || size > MAX_FTYP_BYTES || size > input.length()
                    || (size - headerSize) % 4 != 0) return Kind.UNRECOGNIZED;
            int major = input.readInt();
            input.readInt();
            boolean still = isStillMajor(major);
            boolean sequence = isSequenceBrand(major);
            byte[] compatible = new byte[(int) size - headerSize - 8];
            input.readFully(compatible);
            for (int offset = 0; offset < compatible.length; offset += 4) {
                int brand = ((compatible[offset] & 255) << 24) | ((compatible[offset + 1] & 255) << 16)
                        | ((compatible[offset + 2] & 255) << 8) | (compatible[offset + 3] & 255);
                sequence |= isSequenceBrand(brand);
                still |= brand == MIF1;
            }
            return sequence ? Kind.SEQUENCE : still ? Kind.STILL : Kind.VIDEO;
        } catch (IOException ignored) {
            return Kind.UNRECOGNIZED;
        }
    }

    private static boolean isStillMajor(int brand) {
        switch (brand) {
            case MIF1:
            case 0x68656963:
            case 0x68656978:
            case 0x61766966:
            case 0x76766963:
                return true;
            default:
                return false;
        }
    }

    private static boolean isSequenceBrand(int brand) {
        switch (brand) {
            case 0x6d736631:
            case 0x68657673:
            case 0x6865766d:
            case 0x61766973:
                return true;
            default:
                return false;
        }
    }
}
