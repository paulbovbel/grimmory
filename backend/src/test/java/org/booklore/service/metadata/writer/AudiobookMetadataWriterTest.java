package org.booklore.service.metadata.writer;

import org.booklore.model.dto.settings.AppSettings;
import org.booklore.model.dto.settings.MetadataPersistenceSettings;
import org.booklore.model.entity.BookMetadataEntity;
import org.booklore.service.appsettings.AppSettingService;
import org.booklore.util.AtomicFileWriter;
import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;
import org.jaudiotagger.tag.images.Artwork;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AudiobookMetadataWriterTest {
    @TempDir
    Path tempDir;

    private AudiobookMetadataWriter writer;
    private BookMetadataEntity metadata;
    private MetadataPersistenceSettings.FormatSettings format;

    @BeforeEach
    void setUp() {
        AppSettingService settingsService = mock(AppSettingService.class);
        format = MetadataPersistenceSettings.FormatSettings.builder().enabled(true).maxFileSizeInMb(100).build();
        MetadataPersistenceSettings persistence = new MetadataPersistenceSettings();
        persistence.setSaveToOriginalFile(MetadataPersistenceSettings.SaveToOriginalFile.builder()
                .audiobook(format).build());
        AppSettings settings = mock(AppSettings.class);
        when(settings.getMetadataPersistenceSettings()).thenReturn(persistence);
        when(settingsService.getAppSettings()).thenReturn(settings);
        writer = new AudiobookMetadataWriter(settingsService);
        metadata = new BookMetadataEntity();
        metadata.setTitle("New title");
    }

    @Test
    void metadataAndCoverBothUseValidatedCopyWrites() throws Exception {
        Path file = Files.writeString(tempDir.resolve("book.mp3"), "original audio");
        AudioFile audio = mock(AudioFile.class);
        Tag tag = mock(Tag.class);
        when(audio.getTagOrCreateAndSetDefault()).thenReturn(tag);
        when(tag.getFirst(any(FieldKey.class))).thenReturn("");
        try (var guard = mockStatic(AtomicFileWriter.class); var reads = mockStatic(AudioFileIO.class)) {
            reads.when(() -> AudioFileIO.readAs(file.toFile(), "mp3")).thenReturn(audio);
            guard.when(() -> AtomicFileWriter.update(eq(file), any(), any(), any())).thenAnswer(invocation -> {
                AtomicFileWriter.Update update = invocation.getArgument(2);
                assertThat(update.apply(file)).isTrue();
                return true;
            });
            writer.replaceCoverImageFromBytes(file.toFile(), new byte[]{1, 2, 3});
            writer.saveMetadataToFile(file.toFile(), metadata, null);
            guard.verify(() -> AtomicFileWriter.update(eq(file), any(), any(), any()), times(2));
        }
        verify(tag).setField(FieldKey.TITLE, "New title");
        verify(tag).setField(any(Artwork.class));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failedWritePreservesOriginalAndDoesNotDeleteExistingBackup(boolean cover) throws Exception {
        Path file = Files.writeString(tempDir.resolve("book.mp3"), "original audio");
        Path backup = Files.writeString(tempDir.resolve("book.mp3.bak"), "previous recovery backup");
        try (var guard = mockStatic(AtomicFileWriter.class)) {
            guard.when(() -> AtomicFileWriter.update(eq(file), any(), any(), any()))
                    .thenThrow(new IOException("rewrite failed validation"));
            assertDoesNotThrow(() -> {
                if (cover) {
                    writer.replaceCoverImageFromBytes(file.toFile(), new byte[]{1, 2, 3});
                } else {
                    writer.saveMetadataToFile(file.toFile(), metadata, null);
                }
            });
            guard.verify(() -> AtomicFileWriter.update(eq(file), any(), any(), any()));
        }
        assertThat(file).hasContent("original audio");
        assertThat(backup).hasContent("previous recovery backup");
    }

    @Test
    void disabledPersistenceDoesNotRewriteFiles() throws Exception {
        format.setEnabled(false);
        Path file = Files.writeString(tempDir.resolve("book.mp3"), "original audio");
        try (var guard = mockStatic(AtomicFileWriter.class)) {
            writer.replaceCoverImageFromBytes(file.toFile(), new byte[]{1, 2, 3});
            writer.saveMetadataToFile(file.toFile(), metadata, null);
            guard.verifyNoInteractions();
        }
        assertThat(file).hasContent("original audio");
    }

    @Test
    void folderAudiobooksUseSidecarCoverAndDoNotRewriteDirectory() throws Exception {
        try (var guard = mockStatic(AtomicFileWriter.class)) {
            writer.replaceCoverImageFromBytes(tempDir.toFile(), new byte[]{1, 2, 3});
            writer.saveMetadataToFile(tempDir.toFile(), metadata, null);
            guard.verifyNoInteractions();
        }
        assertThat(Files.readAllBytes(tempDir.resolve("cover.jpg"))).containsExactly(1, 2, 3);
    }
}
