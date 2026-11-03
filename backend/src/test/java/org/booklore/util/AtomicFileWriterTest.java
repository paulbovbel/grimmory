package org.booklore.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AtomicFileWriterTest {
    @TempDir
    Path tempDir;

    private Path original;

    @BeforeEach
    void setUp() throws IOException {
        original = Files.writeString(tempDir.resolve("book.epub"), "original content");
    }

    @Test
    void publishesOnlyAfterValidationAndPreservesPermissions() throws Exception {
        var permissions = PosixFilePermissions.fromString("rw-r-----");
        Files.setPosixFilePermissions(original, permissions);

        assertThat(AtomicFileWriter.update(original, Files::readString, working -> {
            assertThat(working).hasParent(tempDir).hasExtension("tmp");
            assertThat(working).hasContent("original content");
            Files.writeString(working, "updated content");
            return true;
        }, (before, after) -> {
            assertThat(before).isEqualTo("original content");
            assertThat(after).isEqualTo("updated content");
            assertThat(original).hasContent("original content");
        })).isTrue();

        assertThat(original).hasContent("updated content");
        assertThat(Files.getPosixFilePermissions(original)).isEqualTo(permissions);
        assertNoTemporaryFiles();
    }

    @Test
    void failedWriteLeavesOriginalUntouched() {
        assertThatThrownBy(() -> AtomicFileWriter.update(original, Files::readString, working -> {
            Files.writeString(working, "truncated");
            throw new IOException("write failed");
        }, (before, after) -> {})).hasMessageContaining("write failed");

        assertOriginalPreserved();
    }

    @Test
    void failedValidationLeavesOriginalUntouched() {
        assertThatThrownBy(() -> AtomicFileWriter.update(original, Files::readString, working -> {
            Files.writeString(working, "damaged content");
            return true;
        }, (before, after) -> {
            throw new IOException("content lost");
        })).hasMessageContaining("content lost");

        assertOriginalPreserved();
    }

    @Test
    void invalidOriginalIsRejectedBeforeWriting() {
        AtomicBoolean called = new AtomicBoolean();
        assertThatThrownBy(() -> AtomicFileWriter.update(original, path -> {
            throw new IOException("invalid original");
        }, working -> {
            called.set(true);
            return true;
        }, (before, after) -> {})).hasMessageContaining("invalid original");

        assertThat(called).isFalse();
        assertOriginalPreserved();
    }

    @Test
    void noChangesSkipsValidationAndReplacement() throws Exception {
        var modified = Files.getLastModifiedTime(original);
        assertThat(AtomicFileWriter.update(original, Files::readString, working -> false,
                (before, after) -> { throw new AssertionError("No-op writes must not be validated"); }))
                .isFalse();

        assertThat(Files.getLastModifiedTime(original)).isEqualTo(modified);
        assertOriginalPreserved();
    }

    @Test
    void refusesToOverwriteConcurrentChanges() {
        assertThatThrownBy(() -> AtomicFileWriter.update(original, Files::readString, working -> {
            Files.writeString(original, "concurrent modification");
            return true;
        }, (before, after) -> {})).hasMessageContaining("changed during metadata write");

        assertThat(original).hasContent("concurrent modification");
        assertNoTemporaryFiles();
    }

    @Test
    void refusesSymlinksWithoutModifyingTheirTargets() throws IOException {
        Path link = Files.createSymbolicLink(tempDir.resolve("link.epub"), original);
        assertThatThrownBy(() -> AtomicFileWriter.update(link, Files::readString,
                working -> true, (before, after) -> {})).hasMessageContaining("non-regular file");

        assertThat(Files.isSymbolicLink(link)).isTrue();
        assertThat(original).hasContent("original content");
    }

    @Test
    void formatConversionPublishesValidatedDestinationAndRetainsSource() throws Exception {
        Path target = tempDir.resolve("book.cbz");
        assertThat(AtomicFileWriter.update(original, target, Files::readString, working -> {
            Files.writeString(working, "converted content");
            return true;
        }, (before, after) -> assertThat(after).isEqualTo("converted content"))).isTrue();

        assertThat(original).hasContent("original content");
        assertThat(target).hasContent("converted content");
        try (var files = Files.list(tempDir)) {
            assertThat(files).containsExactlyInAnyOrder(original, target);
        }
    }

    @Test
    void conversionDoesNotOverwriteExistingDestination() throws IOException {
        Path target = Files.writeString(tempDir.resolve("book.cbz"), "another comic");
        assertThatThrownBy(() -> AtomicFileWriter.update(original, target, Files::readString,
                working -> true, (before, after) -> {})).hasMessageContaining("different file");

        assertThat(original).hasContent("original content");
        assertThat(target).hasContent("another comic");
    }

    private void assertOriginalPreserved() {
        assertThat(original).hasContent("original content");
        assertNoTemporaryFiles();
    }

    private void assertNoTemporaryFiles() {
        try (var files = Files.list(tempDir)) {
            assertThat(files).containsExactly(original);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }
}
