package com.krztechnologies.attend.privacy.service;

import com.krztechnologies.attend.common.media.FileStore;
import com.krztechnologies.attend.privacy.repository.RetentionQueries;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Deletes biometric files for retention and erasure. A crossing counts as purged only when all its crop files are
 * gone (missing files count as gone), so a file that could not be deleted is tried again on the next run.
 */
@Component
class MediaPurge {

    private static final Logger LOG = LoggerFactory.getLogger(MediaPurge.class);

    private final FileStore files;

    MediaPurge(FileStore files) {
        this.files = files;
    }

    /**
     * @param purgedCrossings crossings whose crop files are all gone now
     * @param filesDeleted files that existed and were deleted
     * @param failures files that could not be deleted (kept for the next run)
     */
    record CropResult(List<Long> purgedCrossings, int filesDeleted, int failures) {}

    CropResult deleteCrops(List<RetentionQueries.CrossingCrops> crossings) {
        List<Long> purged = new ArrayList<>();
        int deleted = 0;
        int failures = 0;
        for (RetentionQueries.CrossingCrops crossing : crossings) {
            boolean allGone = true;
            for (String path : crossing.paths()) {
                switch (delete(FileStore.Folder.CROPS, path)) {
                    case DELETED -> deleted++;
                    case FAILED -> {
                        failures++;
                        allGone = false;
                    }
                    case ABSENT -> {
                        // Already gone: nothing to do.
                    }
                }
            }
            if (allGone) {
                purged.add(crossing.id());
            }
        }
        return new CropResult(purged, deleted, failures);
    }

    /**
     * @param removedRows rows whose file is gone now (or that had none)
     * @param failedRows rows whose file could not be deleted (keep them for the next run)
     * @param filesDeleted files that existed and were deleted
     */
    record UploadResult(List<Long> removedRows, List<Long> failedRows, int filesDeleted) {}

    /** Deletes uploaded files (template thumbnails, enrolment images) of the given rows. */
    UploadResult deleteUploads(List<RetentionQueries.StoredFile> stored) {
        List<Long> removed = new ArrayList<>();
        List<Long> failed = new ArrayList<>();
        int deleted = 0;
        for (RetentionQueries.StoredFile file : stored) {
            Outcome outcome = file.path() == null ? Outcome.ABSENT : delete(FileStore.Folder.UPLOADS, file.path());
            if (outcome == Outcome.FAILED) {
                failed.add(file.id());
            } else {
                removed.add(file.id());
                if (outcome == Outcome.DELETED) {
                    deleted++;
                }
            }
        }
        return new UploadResult(removed, failed, deleted);
    }

    private Outcome delete(FileStore.Folder folder, String path) {
        try {
            return files.delete(folder, path) ? Outcome.DELETED : Outcome.ABSENT;
        } catch (UncheckedIOException e) {
            LOG.warn(
                    "Could not delete a {} file: {}",
                    folder.directory(),
                    e.getCause().getClass().getSimpleName());
            return Outcome.FAILED;
        } catch (IllegalArgumentException e) {
            // A stored path that leaves its folder is never followed; the row is purged all the same.
            LOG.warn("Refused a stored {} path outside its folder", folder.directory());
            return Outcome.ABSENT;
        }
    }

    private enum Outcome {
        DELETED,
        ABSENT,
        FAILED
    }
}
