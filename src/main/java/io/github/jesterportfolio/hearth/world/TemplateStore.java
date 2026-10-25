package io.github.jesterportfolio.hearth.world;

import java.io.IOException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Copies spatial data only. World keys/UUIDs and player data are never cloned. */
public final class TemplateStore {
    private static final Set<String> SPATIAL = Set.of("region", "entities", "poi");

    public String digest(Path source) throws IOException {
        var files = files(source);
        if (files.stream().noneMatch(p -> source.relativize(p).startsWith("region"))) {
            throw new IOException("Template has no region data: " + source);
        }
        try {
            var hash = MessageDigest.getInstance("SHA-256");
            for (Path file : files) {
                hash.update(source.relativize(file).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                hash.update((byte) 0);
                // Stream region files: do not retain whole worlds in memory.
                try (var in = Files.newInputStream(file)) {
                    byte[] buffer = new byte[65536];
                    int count;
                    while ((count = in.read(buffer)) != -1) hash.update(buffer, 0, count);
                }
            }
            return HexFormat.of().formatHex(hash.digest());
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    /** Destination must be a newly allocated, unloaded world. No rollback/deletion on failure. */
    public void copySpatial(Path source, Path destination, String expectedDigest) throws IOException {
        Path src = source.toRealPath();
        Path dst = destination.toRealPath();
        if (src.startsWith(dst) || dst.startsWith(src)) throw new IOException("Overlapping template/destination");
        if (Files.isSymbolicLink(source) || Files.isSymbolicLink(destination)) throw new IOException("Symlink root");
        if (!digest(src).equals(expectedDigest)) throw new IOException("Template checksum mismatch");
        // Inspect all inputs and destination parents before copying any data.
        var inputs = files(src);
        for (Path file : inputs) {
            Path relative = src.relativize(file);
            Path target = dst.resolve(relative);
            for (Path parent = target; parent != null && parent.startsWith(dst); parent = parent.getParent()) {
                if (Files.isSymbolicLink(parent)) throw new IOException("Symlink destination: " + parent);
            }
        }
        for (Path file : inputs) {
            Path target = dst.resolve(src.relativize(file));
            Files.createDirectories(target.getParent());
            Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
        }
        if (!digest(src).equals(expectedDigest)) throw new IOException("Template changed while copying");
        if (!digest(dst).equals(expectedDigest)) throw new IOException("Destination spatial checksum mismatch");
    }

    private List<Path> files(Path root) throws IOException {
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(root)) {
            throw new IOException("Missing/unsafe template directory: " + root);
        }
        var result = new ArrayList<Path>();
        for (String folder : SPATIAL) {
            Path dir = root.resolve(folder);
            if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) continue;
            try (var paths = Files.walk(dir)) {
                for (Path path : paths.toList()) {
                    if (Files.isSymbolicLink(path)) throw new IOException("Symlink in spatial data: " + path);
                    if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) result.add(path);
                }
            }
        }
        result.sort(Comparator.comparing(p -> root.relativize(p).toString()));
        return result;
    }
}
