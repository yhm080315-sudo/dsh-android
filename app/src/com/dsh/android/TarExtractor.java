package com.dsh.android;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Minimal ustar reader: enough of the format to unpack the bundled runtime.
 *
 * Handles regular files, directories, symlinks and hard links, long names via
 * the GNU 'L' typeflag, and GNU base-256 numeric fields. Deliberately does not
 * touch mtimes, ownership or extended attributes.
 */
final class TarExtractor {

    private static final int BLOCK = 512;

    interface Progress {
        void onEntry(String name);
    }

    /** Checkpoint callback: entry index plus the uncompressed stream offset. */
    interface Checkpoint {
        void onCheckpoint(int entries, long offset);
    }

    /** Wraps a stream and reports how many bytes have been consumed. */
    static final class Counting extends java.io.FilterInputStream {
        long count;

        Counting(InputStream in) {
            super(in);
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0) count++;
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            if (n > 0) count += n;
            return n;
        }

        @Override
        public long skip(long n) throws IOException {
            long skipped = super.skip(n);
            if (skipped > 0) count += skipped;
            return skipped;
        }
    }

    private TarExtractor() {
    }

    static void extract(InputStream in, File destRoot, Progress progress) throws IOException {
        extract(in, destRoot, progress, null, 0L);
    }

    /**
     * @param checkpoint  called every so often with the resume offset; may be null
     * @param skipBytes   uncompressed offset to fast-forward to before unpacking
     */
    static void extract(InputStream in, File destRoot, Progress progress,
                        Checkpoint checkpoint, long skipBytes) throws IOException {
        destRoot = new File(destRoot.getCanonicalPath());
        if (skipBytes > 0) {
            skipFully(in, skipBytes);
        }
        byte[] block = new byte[BLOCK];
        String longName = null;
        String longLink = null;
        String paxPath = null;
        String paxLink = null;
        int entries = 0;

        while (true) {
            if (!readBlock(in, block)) {
                return;
            }
            if (isEmptyBlock(block)) {
                if (!readBlock(in, block) || isEmptyBlock(block)) {
                    return;
                }
            }

            long size = number(block, 124, 12);
            int type = block[156] & 0xFF;

            // PAX extended header describes the entry that follows.
            if (type == 'x' || type == 'g') {
                byte[] data = readData(in, size);
                if (type == 'x' && data != null) {
                    String[] kv = parsePax(data);
                    if (kv[0] != null) {
                        paxPath = kv[0];
                    }
                    if (kv[1] != null) {
                        paxLink = kv[1];
                    }
                }
                longName = null;
                longLink = null;
                continue;
            }

            // GNU long name/link: the payload is the name of the next entry.
            if (type == 'L') {
                byte[] data = readData(in, size);
                longName = trimNul(data);
                continue;
            }
            if (type == 'K') {
                byte[] data = readData(in, size);
                longLink = trimNul(data);
                continue;
            }

            String rawName = string(block, 0, 100);
            String rawLink = string(block, 157, 100);
            String prefix = string(block, 345, 155);

            String name = longName != null ? longName : rawName;
            String linkName = longLink != null ? longLink : rawLink;
            longName = null;
            longLink = null;
            if (paxPath != null) {
                name = paxPath;
                paxPath = null;
            }
            if (paxLink != null) {
                linkName = paxLink;
                paxLink = null;
            }

            if (prefix.length() > 0 && !name.startsWith("/") && !name.contains("/")) {
                name = prefix + "/" + name;
            }
            name = normalize(name);
            if (name.isEmpty()) {
                skipFully(in, size + padding(size));
                continue;
            }

            File target = childOf(destRoot, name);
            switch (type) {
                case '0':
                case '7':
                case 0:
                    writeFile(in, target, size);
                    break;
                case '5':
                    if (!target.isDirectory() && !target.mkdirs() && !target.isDirectory()) {
                        throw new IOException("mkdirs failed: " + target);
                    }
                    skipFully(in, size + padding(size));
                    break;
                case '2':
                    skipFully(in, size + padding(size));
                    makeSymlink(target, linkName);
                    break;
                case '1':
                    skipFully(in, size + padding(size));
                    makeHardLink(target, childOf(destRoot, normalize(linkName)));
                    break;
                default:
                    skipFully(in, size + padding(size));
                    break;
            }
            entries++;
            if (progress != null) {
                progress.onEntry(name);
            }
            if (checkpoint != null && (entries & 0x7FF) == 0) {
                checkpoint.onCheckpoint(entries,
                        in instanceof Counting ? ((Counting) in).count : 0L);
            }
        }
    }

    /** Resolve an archive member inside destRoot, refusing traversal. */
    private static File childOf(File destRoot, String name) throws IOException {
        File target = new File(destRoot, name);
        File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IOException("mkdirs failed: " + parent);
        }
        String rootPath = destRoot.getCanonicalPath();
        String targetPath = target.getCanonicalPath();
        if (!targetPath.equals(rootPath) && !targetPath.startsWith(rootPath + File.separator)) {
            throw new IOException("tar member escapes destination: " + name);
        }
        return target;
    }

    private static String[] parsePax(byte[] data) {
        String path = null;
        String link = null;
        String text = new String(data, 0, data.length);
        for (String record : text.split("\n")) {
            int sp = record.indexOf(' ');
            if (sp <= 0) {
                continue;
            }
            String kv = record.substring(sp + 1);
            int eq = kv.indexOf('=');
            if (eq < 0) {
                continue;
            }
            String key = kv.substring(0, eq);
            String value = kv.substring(eq + 1);
            if (key.equals("path")) {
                path = value;
            } else if (key.equals("linkpath")) {
                link = value;
            }
        }
        return new String[]{path, link};
    }

    private static byte[] readData(InputStream in, long size) throws IOException {
        if (size < 0 || size > (1 << 22)) {
            skipFully(in, size + padding(size));
            return null;
        }
        byte[] data = new byte[(int) size];
        int off = 0;
        while (off < data.length) {
            int n = in.read(data, off, data.length - off);
            if (n < 0) {
                return null;
            }
            off += n;
        }
        skipFully(in, padding(size));
        return data;
    }

    private static String trimNul(byte[] data) {
        if (data == null) {
            return null;
        }
        int end = data.length;
        while (end > 0 && data[end - 1] == 0) {
            end--;
        }
        return new String(data, 0, end);
    }

    private static void makeSymlink(File target, String linkName) throws IOException {
        File linkTarget = linkName.startsWith("/") ? new File(linkName)
                : new File(target.getParentFile(), linkName);
        deleteQuietly(target);
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"/system/bin/ln", "-sf",
                    linkTarget.getAbsolutePath(), target.getAbsolutePath()});
            p.waitFor();
        } catch (Exception e) {
            throw new IOException("symlink failed: " + target + " -> " + linkName, e);
        }
    }

    private static void makeHardLink(File target, File linkSource) throws IOException {
        deleteQuietly(target);
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"/system/bin/ln", "-f",
                    linkSource.getAbsolutePath(), target.getAbsolutePath()});
            p.waitFor();
        } catch (Exception e) {
            throw new IOException("hardlink failed: " + target, e);
        }
    }

    /**
     * Read a PAX extended header block and return {path, linkpath}. Modern tar
     * writers put anything past the 100-byte ustar name here, which is routine
     * for deep npm trees.
     */
    private static String[] parsePax(InputStream in, long size) throws IOException {
        String path = null;
        String link = null;
        if (size > 0 && size < (1 << 22)) {
            byte[] data = new byte[(int) size];
            if (readFully(in, data)) {
                skipFully(in, padding(size));
                String text = new String(data, "UTF-8");
                for (String record : text.split("\n")) {
                    int sp = record.indexOf(' ');
                    if (sp <= 0) {
                        continue;
                    }
                    String key = record.substring(sp + 1);
                    String value = null;
                    int eq = key.indexOf('=');
                    if (eq >= 0) {
                        value = key.substring(eq + 1);
                        key = key.substring(0, eq);
                    }
                    if (value == null) {
                        continue;
                    }
                    if (key.equals("path")) {
                        path = value;
                    } else if (key.equals("linkpath")) {
                        link = value;
                    }
                }
                return new String[]{path, link};
            }
        }
        skipFully(in, size + padding(size));
        return new String[]{null, null};
    }

    /** Read exactly one 512-byte header block; false at a clean end of stream. */
    private static boolean readBlock(InputStream in, byte[] block) throws IOException {
        int off = 0;
        while (off < block.length) {
            int n = in.read(block, off, block.length - off);
            if (n < 0) {
                return false;
            }
            off += n;
        }
        return true;
    }

    /** Normalize a member path to a relative, traversal-free form. */
    private static String normalize(String path) {
        path = path.replace('\\', '/');
        while (path.startsWith("/")) {
            path = path.substring(1);
        }
        while (path.startsWith("./")) {
            path = path.substring(2);
        }
        if (path.isEmpty() || path.equals(".")) {
            return "";
        }
        for (String segment : path.split("/")) {
            if (segment.equals("..")) {
                return "";
            }
        }
        return path;
    }

    private static void writeFile(InputStream in, File target, long size) throws IOException {
        deleteQuietly(target);
        OutputStream out = new FileOutputStream(target);
        try {
            byte[] buf = new byte[1 << 18]; // 256 KiB: fewer syscalls on flash storage
            long left = size;
            while (left > 0) {
                int want = (int) Math.min(buf.length, left);
                int n = in.read(buf, 0, want);
                if (n < 0) {
                    throw new IOException("truncated tar entry: " + target);
                }
                out.write(buf, 0, n);
                left -= n;
            }
            out.flush();
        } finally {
            out.close();
        }
        skipFully(in, padding(size));
        target.setReadable(true, false);
        target.setExecutable(true, false);
    }

    private static long padding(long size) {
        long rem = size % BLOCK;
        return rem == 0 ? 0 : BLOCK - rem;
    }


    static void skipFully(InputStream in, long count) throws IOException {
        byte[] buf = new byte[1 << 16];
        long left = count;
        while (left > 0) {
            int n = in.read(buf, 0, (int) Math.min(buf.length, left));
            if (n < 0) {
                return;
            }
            left -= n;
        }
    }

    private static boolean readFully(InputStream in, byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n < 0) {
                return false;
            }
            off += n;
        }
        return true;
    }

    private static boolean isEmptyBlock(byte[] block) {
        for (byte b : block) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }

    private static String string(byte[] block, int offset, int length) {
        int end = offset;
        int limit = offset + length;
        while (end < limit && block[end] != 0) {
            end++;
        }
        return new String(block, offset, end - offset);
    }

    private static long number(byte[] block, int offset, int length) {
        if ((block[offset] & 0x80) != 0) { // GNU base-256
            long value = block[offset] & 0x7F;
            for (int i = 1; i < length; i++) {
                value = (value << 8) | (block[offset + i] & 0xFF);
            }
            return value;
        }
        long value = 0;
        for (int i = offset; i < offset + length; i++) {
            int c = block[i] & 0xFF;
            if (c == 0 || c == ' ') {
                if (value != 0) break;
                continue;
            }
            if (c < '0' || c > '7') break;
            value = value * 8 + (c - '0');
        }
        return value;
    }




    private static void deleteQuietly(File f) {
        if (f.exists() && !f.delete()) {
            // best effort; the open below will fail loudly if it matters
        }
    }
}
