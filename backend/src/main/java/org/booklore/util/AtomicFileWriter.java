package org.booklore.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;

import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.COPY_ATTRIBUTES;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

/** Rewrite a private copy, validate it, then publish it without truncating the original. */
public final class AtomicFileWriter {
    private AtomicFileWriter() {
    }

    @FunctionalInterface
    public interface Reader<S> {
        S read(Path file) throws Exception;
    }

    @FunctionalInterface
    public interface Update {
        boolean apply(Path working) throws Exception;
    }

    @FunctionalInterface
    public interface Validator<S> {
        void validate(S original, S rewritten) throws Exception;
    }

    public static <S> boolean update(Path file, Reader<S> reader, Update update,
                                     Validator<S> validator) throws Exception {
        return update(file, file, reader, update, validator);
    }

    /** A different destination supports format conversions; the caller owns source cleanup. */
    public static <S> boolean update(Path file, Path destination, Reader<S> reader,
                                     Update update, Validator<S> validator) throws Exception {
        Path original = file.toAbsolutePath().normalize();
        Path target = destination.toAbsolutePath().normalize();
        if (!Files.isRegularFile(original) || Files.isSymbolicLink(original)) {
            throw new IOException("Refusing to rewrite a non-regular file: " + original);
        }
        if (!original.equals(target) && Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Refusing to overwrite a different file: " + target);
        }
        BasicFileAttributes attributes = Files.readAttributes(original, BasicFileAttributes.class);
        S before = reader.read(original);
        // Unknown extensions prevent library watchers from importing unfinished working copies.
        Path working = Files.createTempFile(target.getParent(), ".metadata-", ".tmp");
        try {
            Files.copy(original, working, REPLACE_EXISTING, COPY_ATTRIBUTES);
            if (!update.apply(working)) {
                return false;
            }
            validator.validate(before, reader.read(working));

            BasicFileAttributes current = Files.readAttributes(original, BasicFileAttributes.class);
            if (Files.isSymbolicLink(original) || attributes.size() != current.size()
                    || !attributes.lastModifiedTime().equals(current.lastModifiedTime())
                    || !Objects.equals(attributes.fileKey(), current.fileKey())) {
                throw new IOException("File changed during metadata write: " + original);
            }
            if (!original.equals(target) && Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Destination appeared during metadata write: " + target);
            }
            // Fail closed: a non-atomic fallback could leave the only good copy truncated.
            Files.move(working, target, ATOMIC_MOVE, REPLACE_EXISTING);
            return true;
        } finally {
            Files.deleteIfExists(working);
        }
    }
}
