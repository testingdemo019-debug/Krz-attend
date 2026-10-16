package com.krztechnologies.attend.common.media;

import com.krztechnologies.attend.common.app.AppProperties;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * The files the backend keeps under DATA_DIR. Every stored path is relative to one {@link Folder} (the only folders the
 * backend container may write) and is resolved with a normalisation check, so a stored or crafted {@code ../}, an
 * absolute path or a symbolic link can never leave that folder. Writes are atomic (a temp file in the same directory,
 * then a rename), so a reader never sees half a file.
 */
@Component
public class FileStore {

    private final Path dataDir;

    public FileStore(AppProperties app) {
        this.dataDir = app.dataPath().toAbsolutePath().normalize();
    }

    /** The DATA_DIR folders the backend writes (security §13: only these are mounted read-write). */
    public enum Folder {
        /** Entrance crops written by the worker; paths as in the crossing message (relative to {@code crops/}). */
        CROPS("crops"),
        /** Files uploaded through the app (logo, consent documents, enrolment images, template thumbnails). */
        UPLOADS("uploads");

        private final String directory;

        Folder(String directory) {
            this.directory = directory;
        }

        public String directory() {
            return directory;
        }
    }

    /**
     * The absolute path of a stored relative path. Refuses (IllegalArgumentException) anything that is empty, absolute,
     * contains a backslash, a drive letter or a NUL, or resolves outside the folder, also through a symbolic link.
     */
    public Path resolve(Folder folder, String relativePath) {
        if (relativePath == null
                || relativePath.isBlank()
                || relativePath.startsWith("/")
                || relativePath.indexOf('\\') >= 0
                || relativePath.indexOf(':') >= 0
                || relativePath.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Not a relative file path: " + printable(relativePath));
        }
        Path base = root(folder);
        Path resolved;
        try {
            resolved = base.resolve(relativePath).normalize();
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException("Not a valid file path: " + printable(relativePath), e);
        }
        if (!resolved.startsWith(base) || resolved.equals(base)) {
            throw new IllegalArgumentException("Path leaves the " + folder.directory() + " folder");
        }
        ensureNoLinkEscape(base, resolved, folder);
        return resolved;
    }

    /** Writes the file atomically (replacing an existing one), creating its directories. */
    public void write(Folder folder, String relativePath, byte[] content) {
        Path target = resolve(folder, relativePath);
        Path temp = null;
        try {
            Files.createDirectories(target.getParent());
            temp = Files.createTempFile(target.getParent(), ".tmp-", ".part");
            Files.write(temp, content);
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            temp = null;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write " + folder.directory() + "/" + relativePath, e);
        } finally {
            deleteQuietly(temp);
        }
    }

    /** The file's content, or empty when it does not exist. */
    public Optional<byte[]> read(Folder folder, String relativePath) {
        Path file = resolve(folder, relativePath);
        try {
            return Optional.of(Files.readAllBytes(file));
        } catch (NoSuchFileException e) {
            return Optional.empty();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + folder.directory() + "/" + relativePath, e);
        }
    }

    /** Deletes the file. Returns false when there was nothing to delete. */
    public boolean delete(Folder folder, String relativePath) {
        Path file = resolve(folder, relativePath);
        try {
            return Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not delete " + folder.directory() + "/" + relativePath, e);
        }
    }

    private Path root(Folder folder) {
        return dataDir.resolve(folder.directory()).normalize();
    }

    /** A symbolic link inside the folder must not lead out of it: the nearest existing ancestor is checked for real. */
    private static void ensureNoLinkEscape(Path base, Path resolved, Folder folder) {
        if (!Files.exists(base)) {
            return;
        }
        Path existing = resolved;
        while (existing != null && !Files.exists(existing)) {
            existing = existing.getParent();
        }
        if (existing == null) {
            return;
        }
        try {
            if (!existing.toRealPath().startsWith(base.toRealPath())) {
                throw new IllegalArgumentException("Path leaves the " + folder.directory() + " folder");
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not check " + folder.directory() + " path", e);
        }
    }

    private static void deleteQuietly(Path temp) {
        if (temp == null) {
            return;
        }
        try {
            Files.deleteIfExists(temp);
        } catch (IOException ignored) {
            // The temp file name starts with ".tmp-"; a leftover is harmless and overwritten by nothing.
        }
    }

    private static String printable(String path) {
        return path == null ? "null" : path.replace('\0', '?');
    }
}
