package org.booklore.service.metadata.writer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.booklore.model.MetadataClearFlags;
import org.booklore.model.dto.settings.MetadataPersistenceSettings;
import org.booklore.model.entity.BookMetadataEntity;
import org.booklore.model.enums.BookFileType;
import org.booklore.service.appsettings.AppSettingService;
import org.booklore.util.AtomicFileWriter;
import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.audio.AudioHeader;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;
import org.jaudiotagger.tag.images.Artwork;
import org.jaudiotagger.tag.images.ArtworkFactory;
import org.jcodec.containers.mp4.MP4Util;
import org.jcodec.containers.mp4.boxes.TrakBox;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

import static java.nio.file.StandardOpenOption.READ;

@Slf4j
@Component
@RequiredArgsConstructor
public class AudiobookMetadataWriter implements MetadataWriter {

    static {
        Logger.getLogger("org.jaudiotagger").setLevel(Level.WARNING);
    }

    private final AppSettingService appSettingService;

    @Override
    public void saveMetadataToFile(File audioFile, BookMetadataEntity metadata, MetadataClearFlags clear) {
        if (!shouldSaveMetadataToFile(audioFile) || audioFile.isDirectory()) {
            return;
        }

        try {
            boolean updated = updateAudioFile(audioFile, f -> {
                Tag tag = f.getTagOrCreateAndSetDefault();

                boolean[] hasChanges = {false};
                MetadataCopyHelper helper = new MetadataCopyHelper(metadata);

                helper.copyTitle(clear != null && clear.isTitle(), val -> {
                    setTagField(tag, FieldKey.ALBUM, val, hasChanges);
                    setTagField(tag, FieldKey.TITLE, val, hasChanges);
                });

                helper.copyAuthors(clear != null && clear.isAuthors(), authors -> {
                    String authorStr = authors != null ? String.join("; ", authors) : null;
                    setTagField(tag, FieldKey.ALBUM_ARTIST, authorStr, hasChanges);
                    setTagField(tag, FieldKey.ARTIST, authorStr, hasChanges);
                });

                if (StringUtils.isNotBlank(metadata.getNarrator())) {
                    setTagField(tag, FieldKey.COMPOSER, metadata.getNarrator(), hasChanges);
                }

                helper.copyDescription(clear != null && clear.isDescription(), val -> {
                    setTagField(tag, FieldKey.COMMENT, val, hasChanges);
                });

                helper.copyPublisher(clear != null && clear.isPublisher(), val -> {
                    setTagField(tag, FieldKey.RECORD_LABEL, val, hasChanges);
                });

                helper.copyPublishedDate(clear != null && clear.isPublishedDate(), val -> {
                    String year = val != null ? String.valueOf(val.getYear()) : null;
                    setTagField(tag, FieldKey.YEAR, year, hasChanges);
                });

                helper.copyCategories(clear != null && clear.isCategories(), categories -> {
                    String genre = categories != null && !categories.isEmpty()
                            ? String.join("; ", categories)
                            : null;
                    setTagField(tag, FieldKey.GENRE, genre, hasChanges);
                });

                helper.copyLanguage(clear != null && clear.isLanguage(), val -> {
                    setTagField(tag, FieldKey.LANGUAGE, val, hasChanges);
                });

                helper.copySeriesName(clear != null && clear.isSeriesName(), val -> {
                    setTagField(tag, FieldKey.GROUPING, val, hasChanges);
                });

                helper.copySeriesNumber(clear != null && clear.isSeriesNumber(), val -> {
                    String trackNo = val != null ? String.format("%.0f", val) : null;
                    setTagField(tag, FieldKey.TRACK, trackNo, hasChanges);
                });

                helper.copySeriesTotal(clear != null && clear.isSeriesTotal(), val -> {
                    String trackTotal = val != null ? String.valueOf(val) : null;
                    setTagField(tag, FieldKey.TRACK_TOTAL, trackTotal, hasChanges);
                });

                return hasChanges[0];
            });
            if (updated) {
                log.info("Metadata updated in audiobook: {}", audioFile.getName());
            } else {
                log.debug("No changes detected. Skipping audiobook write for: {}", audioFile.getName());
            }

        } catch (Exception e) {
            log.warn("Failed to write metadata to audiobook file {}: {}", audioFile.getName(), e.getMessage(), e);
        }
    }

    public void saveCoverToFolder(Path folderPath, byte[] coverData) {
        if (coverData == null || coverData.length == 0 || folderPath == null) {
            return;
        }

        try {
            String extension = detectImageExtension(coverData);
            String filename = "cover" + extension;
            Path coverPath = folderPath.resolve(filename);

            deleteExistingCovers(folderPath);

            Files.write(coverPath, coverData);
            log.info("Cover image saved to folder: {}", coverPath);
        } catch (IOException e) {
            log.warn("Failed to save cover to folder {}: {}", folderPath, e.getMessage());
        }
    }

    private void deleteExistingCovers(Path folderPath) throws IOException {
        String[] coverNames = {"cover.jpg", "cover.jpeg", "cover.png", "folder.jpg", "folder.jpeg", "folder.png"};
        for (String name : coverNames) {
            Path existing = folderPath.resolve(name);
            Files.deleteIfExists(existing);
        }
    }

    private String detectImageExtension(byte[] data) {
        if (data.length > 3 && data[0] == (byte) 0x89 && data[1] == (byte) 0x50 && data[2] == (byte) 0x4E && data[3] == (byte) 0x47) {
            return ".png";
        }
        return ".jpg";
    }

    private void setTagField(Tag tag, FieldKey key, String value, boolean[] hasChanges) {
        try {
            String existingValue = tag.getFirst(key);
            if (value == null || value.isBlank()) {
                if (StringUtils.isNotBlank(existingValue)) {
                    tag.deleteField(key);
                    hasChanges[0] = true;
                }
            } else if (!value.equals(existingValue)) {
                tag.setField(key, value);
                hasChanges[0] = true;
            }
        } catch (Exception e) {
            log.debug("Failed to set tag field {}: {}", key, e.getMessage());
        }
    }

    @Override
    public void replaceCoverImageFromBytes(File bookFile, byte[] coverData) {
        if (!shouldSaveMetadataToFile(bookFile)) {
            return;
        }

        if (bookFile.isDirectory()) {
            saveCoverToFolder(bookFile.toPath(), coverData);
            return;
        }

        try {
            updateAudioFile(bookFile, f -> {
                Tag tag = f.getTagOrCreateAndSetDefault();

                tag.deleteArtworkField();
                Artwork artwork = ArtworkFactory.getNew();
                artwork.setBinaryData(coverData);
                artwork.setMimeType(detectMimeType(coverData));
                tag.setField(artwork);
                return true;
            });

            log.info("Cover image updated in audiobook: {}", bookFile.getName());
        } catch (Exception e) {
            log.warn("Failed to update audiobook cover image: {}", e.getMessage(), e);
        }
    }

    @Override
    public BookFileType getSupportedBookType() {
        return BookFileType.AUDIOBOOK;
    }

    @FunctionalInterface
    private interface AudioUpdate {
        boolean apply(AudioFile audioFile) throws Exception;
    }

    private boolean updateAudioFile(File file, AudioUpdate update) throws Exception {
        String name = file.getName();
        String extension = name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        return AtomicFileWriter.update(file.toPath(), path -> AudioState.read(path, extension), working -> {
            // Keep the real format when the working copy has an unrecognized extension.
            AudioFile audioFile = AudioFileIO.readAs(working.toFile(), extension);
            if (!update.apply(audioFile)) {
                return false;
            }
            audioFile.commit();
            return true;
        }, AudioState::validate);
    }

    private record AudioState(double duration, int sampleRate, String channels, List<Track> tracks) {
        static AudioState read(Path path, String extension) throws Exception {
            AudioHeader header = AudioFileIO.readAs(path.toFile(), extension).getAudioHeader();
            if (header == null || !Double.isFinite(header.getPreciseTrackLength())
                    || header.getPreciseTrackLength() <= 0 || header.getSampleRateAsNumber() <= 0) {
                throw new IOException("Audiobook has no readable audio: " + path);
            }
            List<Track> tracks = List.of();
            if (List.of("m4b", "m4a", "mp4").contains(extension)) {
                try (FileChannel channel = FileChannel.open(path, READ)) {
                    var movie = MP4Util.parseFullMovieChannel(channel);
                    if (movie == null) {
                        throw new IOException("Audiobook has no MP4 movie: " + path);
                    }
                    tracks = Arrays.stream(movie.getMoov().getTracks()).map(Track::read).toList();
                    if (tracks.stream().noneMatch(track -> "soun".equals(track.handler()))) {
                        throw new IOException("Audiobook has no MP4 audio track: " + path);
                    }
                }
            }
            return new AudioState(header.getPreciseTrackLength(), header.getSampleRateAsNumber(),
                    header.getChannels(), tracks);
        }

        void validate(AudioState rewritten) throws IOException {
            // Allow sub-second rounding differences, not lost audio or chapter tracks.
            if (Math.abs(duration - rewritten.duration) > 1.0 || sampleRate != rewritten.sampleRate
                    || !Objects.equals(channels, rewritten.channels) || !tracks.equals(rewritten.tracks)) {
                throw new IOException("Metadata rewrite changed the audiobook's audio or media tracks");
            }
        }
    }

    private record Track(String handler, int id, long samples, long duration, int timescale) {
        static Track read(TrakBox track) {
            return new Track(track.getHandlerType(), track.getTrackHeader().getTrackId(),
                    track.getSampleCount(), track.getMediaDuration(), track.getTimescale());
        }
    }

    @Override
    public boolean shouldSaveMetadataToFile(File audioFile) {
        MetadataPersistenceSettings.SaveToOriginalFile settings = appSettingService.getAppSettings()
                .getMetadataPersistenceSettings().getSaveToOriginalFile();

        MetadataPersistenceSettings.FormatSettings audiobookSettings = settings.getAudiobook();
        if (audiobookSettings == null || !audiobookSettings.isEnabled()) {
            log.debug("Audiobook metadata writing is disabled. Skipping: {}", audioFile.getName());
            return false;
        }

        if (audioFile.isDirectory()) {
            return true;
        }

        long fileSizeInMb = audioFile.length() / (1024 * 1024);
        if (fileSizeInMb > audiobookSettings.getMaxFileSizeInMb()) {
            log.info("Audiobook file {} ({} MB) exceeds max size limit ({} MB). Skipping metadata write.",
                    audioFile.getName(), fileSizeInMb, audiobookSettings.getMaxFileSizeInMb());
            return false;
        }

        return true;
    }

    private String detectMimeType(byte[] data) {
        if (data.length > 3 && data[0] == (byte) 0x89 && data[1] == (byte) 0x50 && data[2] == (byte) 0x4E && data[3] == (byte) 0x47) {
            return "image/png";
        }
        return "image/jpeg";
    }
}
