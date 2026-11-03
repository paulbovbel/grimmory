package org.booklore.service.metadata.writer;

import org.booklore.model.dto.settings.AppSettings;
import org.booklore.model.dto.settings.MetadataPersistenceSettings;
import org.booklore.model.entity.BookMetadataEntity;
import org.booklore.service.appsettings.AppSettingService;
import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.audio.AudioHeader;
import org.jaudiotagger.tag.Tag;
import org.jcodec.containers.mp4.MP4Util;
import org.jcodec.containers.mp4.boxes.MovieBox;
import org.jcodec.containers.mp4.boxes.TrackHeaderBox;
import org.jcodec.containers.mp4.boxes.TrakBox;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;

import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AudiobookMetadataWriterSafetyTest {
    @TempDir
    Path tempDir;

    private AudiobookMetadataWriter writer;
    private BookMetadataEntity metadata;
    private Path original;
    private AudioFile source;
    private AudioFile editable;
    private AudioFile rewritten;
    private AudioHeader header;
    private final AtomicBoolean committed = new AtomicBoolean();
    private final AtomicReference<Path> working = new AtomicReference<>();

    @BeforeEach
    void setUp() throws Exception {
        AppSettingService settingsService = mock(AppSettingService.class);
        var format = MetadataPersistenceSettings.FormatSettings.builder().enabled(true).maxFileSizeInMb(100).build();
        var persistence = new MetadataPersistenceSettings();
        persistence.setSaveToOriginalFile(MetadataPersistenceSettings.SaveToOriginalFile.builder()
                .audiobook(format).build());
        AppSettings settings = mock(AppSettings.class);
        when(settings.getMetadataPersistenceSettings()).thenReturn(persistence);
        when(settingsService.getAppSettings()).thenReturn(settings);
        writer = new AudiobookMetadataWriter(settingsService);
        metadata = new BookMetadataEntity();
        metadata.setTitle("Updated title");

        original = Files.writeString(tempDir.resolve("book.mp3"), "original audio");
        source = mock(AudioFile.class);
        editable = mock(AudioFile.class);
        rewritten = mock(AudioFile.class);
        header = mock(AudioHeader.class);
        when(header.getPreciseTrackLength()).thenReturn(60.0);
        when(header.getSampleRateAsNumber()).thenReturn(44100);
        when(header.getChannels()).thenReturn("2");
        when(source.getAudioHeader()).thenReturn(header);
        when(rewritten.getAudioHeader()).thenReturn(header);
        when(editable.getTagOrCreateAndSetDefault()).thenReturn(mock(Tag.class));
        doAnswer(invocation -> {
            assertThat(original).hasContent("original audio");
            assertThat(working.get()).hasParent(tempDir).hasExtension("tmp");
            Files.writeString(working.get(), "updated tags and original audio");
            committed.set(true);
            return null;
        }).when(editable).commit();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void metadataAndCoverPublishOnlyValidatedCopies(boolean cover) throws Exception {
        try (var reads = mockReads()) {
            if (cover) {
                writer.replaceCoverImageFromBytes(original.toFile(), new byte[]{1, 2, 3});
            } else {
                writeMetadata();
            }
        }
        assertThat(original).hasContent("updated tags and original audio");
        assertNoTemporaryFiles();
    }

    @Test
    void unchangedMetadataDoesNotCommitOrReplaceOriginal() throws Exception {
        metadata.setTitle(null);
        var modified = Files.getLastModifiedTime(original);
        try (var reads = mockReads()) {
            writeMetadata();
        }
        verify(editable, never()).commit();
        assertThat(Files.getLastModifiedTime(original)).isEqualTo(modified);
        assertOriginalPreserved();
    }

    @Test
    void commitThatTruncatesThenThrowsCannotDamageOriginal() throws Exception {
        doAnswer(invocation -> {
            Files.writeString(working.get(), "truncated");
            throw new IOException("disk full during commit");
        }).when(editable).commit();
        try (var reads = mockReads()) {
            writeMetadata();
        }
        assertOriginalPreserved();
    }

    @Test
    void successfulCommitWithUnreadableResultCannotDamageOriginal() throws Exception {
        when(rewritten.getAudioHeader()).thenReturn(null);
        try (var reads = mockReads()) {
            writeMetadata();
        }
        assertOriginalPreserved();
    }

    @Test
    void rejectsDurationLossEvenWhenCommitSucceeds() throws Exception {
        AudioHeader damaged = mock(AudioHeader.class);
        when(damaged.getPreciseTrackLength()).thenReturn(20.0);
        when(damaged.getSampleRateAsNumber()).thenReturn(44100);
        when(damaged.getChannels()).thenReturn("2");
        when(rewritten.getAudioHeader()).thenReturn(damaged);
        try (var reads = mockReads()) {
            writeMetadata();
        }
        assertOriginalPreserved();
    }

    @Test
    void rejectsSourceWithoutReadableAudioBeforeCommitting() throws Exception {
        when(header.getPreciseTrackLength()).thenReturn(0.0);
        try (var reads = mockReads()) {
            writeMetadata();
        }
        verify(editable, never()).commit();
        assertOriginalPreserved();
    }

    @Test
    void rejectsLostMp4AudioEvenIfReaderStillReportsOriginalDuration() throws Exception {
        assertTracksPreserved(new TrakBox[]{track("text", 2, 10)});
    }

    @Test
    void rejectsLostChapterTrackEvenIfAudioSurvives() throws Exception {
        assertTracksPreserved(new TrakBox[]{track("soun", 1, 1000)});
    }

    @Test
    void rejectsChangedMp4SampleCount() throws Exception {
        assertTracksPreserved(new TrakBox[]{track("soun", 1, 999), track("text", 2, 10)});
    }

    private void writeMetadata() {
        writer.saveMetadataToFile(original.toFile(), metadata, null);
    }

    private void assertTracksPreserved(TrakBox[] remaining) throws Exception {
        original = Files.move(original, tempDir.resolve("book.m4b"));
        var before = movie(new TrakBox[]{track("soun", 1, 1000), track("text", 2, 10)});
        var after = movie(remaining);
        try (var reads = mockReads(); var movies = mockStatic(MP4Util.class)) {
            movies.when(() -> MP4Util.parseFullMovieChannel(any(FileChannel.class))).thenReturn(before, after);
            writeMetadata();
        }
        assertOriginalPreserved();
    }

    private MP4Util.Movie movie(TrakBox[] tracks) {
        MovieBox moov = mock(MovieBox.class);
        when(moov.getTracks()).thenReturn(tracks);
        MP4Util.Movie movie = mock(MP4Util.Movie.class);
        when(movie.getMoov()).thenReturn(moov);
        return movie;
    }

    private TrakBox track(String handler, int id, long samples) {
        TrakBox track = mock(TrakBox.class);
        TrackHeaderBox tkhd = mock(TrackHeaderBox.class);
        when(tkhd.getTrackId()).thenReturn(id);
        when(track.getTrackHeader()).thenReturn(tkhd);
        when(track.getHandlerType()).thenReturn(handler);
        when(track.getSampleCount()).thenReturn(samples);
        when(track.getMediaDuration()).thenReturn(60000L);
        when(track.getTimescale()).thenReturn(1000);
        return track;
    }

    private MockedStatic<AudioFileIO> mockReads() {
        var reads = mockStatic(AudioFileIO.class);
        reads.when(() -> AudioFileIO.readAs(any(File.class), anyString())).thenAnswer(invocation -> {
            Path path = invocation.<File>getArgument(0).toPath();
            assertThat(invocation.<String>getArgument(1))
                    .isEqualTo(original.toString().endsWith(".m4b") ? "m4b" : "mp3");
            if (path.equals(original)) {
                return source;
            }
            working.set(path);
            return committed.get() ? rewritten : editable;
        });
        return reads;
    }

    private void assertOriginalPreserved() throws IOException {
        assertThat(original).hasContent("original audio");
        assertNoTemporaryFiles();
    }

    private void assertNoTemporaryFiles() throws IOException {
        try (var paths = Files.list(tempDir)) {
            assertThat(paths).containsExactly(original);
        }
    }
}
