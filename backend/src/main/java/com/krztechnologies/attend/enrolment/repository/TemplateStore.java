package com.krztechnologies.attend.enrolment.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Face templates: the encrypted embedding with its model, quality and thumbnail (never read back here). */
@Repository
public class TemplateStore {

    private final JdbcClient jdbc;

    public TemplateStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * A template to store; {@code embeddingEnc} is already encrypted.
     *
     * @param crossingId the crossing whose face crop it is (source CROSSING), else null
     * @param light DAY, EVENING or NIGHT_IR for a template from a capture at an entry point, else null
     */
    public record NewTemplate(
            UUID employeeId,
            byte[] embeddingEnc,
            String keyId,
            String modelName,
            String modelVersion,
            int dim,
            Integer quality,
            Integer eyePx,
            String pose,
            String source,
            String thumbnailPath,
            Long reviewItemId,
            Long crossingId,
            String light,
            UUID createdBy) {}

    public long insert(NewTemplate t) {
        return jdbc.sql("""
                        INSERT INTO attend.face_template (employee_id, embedding_enc, key_id, model_name, model_version, dim,
                                                          quality, eye_px, pose, source, thumbnail_path, review_item_id,
                                                          crossing_id, light, created_by)
                        VALUES (:employee, :embedding, :key, :modelName, :modelVersion, :dim, :quality, :eyePx, :pose,
                                :source, :thumbnail, :item, :crossing, :light, :by)
                        RETURNING id
                        """)
                .param("employee", t.employeeId())
                .param("embedding", t.embeddingEnc())
                .param("key", t.keyId())
                .param("modelName", t.modelName())
                .param("modelVersion", t.modelVersion())
                .param("dim", t.dim())
                .param("quality", t.quality())
                .param("eyePx", t.eyePx())
                .param("pose", t.pose())
                .param("source", t.source())
                .param("thumbnail", t.thumbnailPath())
                .param("item", t.reviewItemId())
                .param("crossing", t.crossingId())
                .param("light", t.light())
                .param("by", t.createdBy())
                .query(Long.class)
                .single();
    }

    /** Whether the crossing's face crop is already an active template. */
    public boolean activeForCrossing(long crossingId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM attend.face_template WHERE crossing_id = :id AND active)")
                .param("id", crossingId)
                .query(Boolean.class)
                .single();
    }

    public int activeCount(UUID employeeId) {
        return jdbc.sql("SELECT count(*) FROM attend.face_template WHERE employee_id = :id AND active")
                .param("id", employeeId)
                .query(Integer.class)
                .single();
    }

    /** An active template as the wizard lists it. */
    public record Active(long id, String pose, Integer quality, boolean hasThumbnail) {}

    /** The employee's active templates, oldest first. */
    public List<Active> active(UUID employeeId) {
        return jdbc.sql("""
                        SELECT id, pose, quality, thumbnail_path IS NOT NULL AS has_thumbnail
                          FROM attend.face_template WHERE employee_id = :id AND active ORDER BY created_at, id
                        """)
                .param("id", employeeId)
                .query((rs, n) -> new Active(
                        rs.getLong("id"),
                        rs.getString("pose"),
                        (Integer) rs.getObject("quality"),
                        rs.getBoolean("has_thumbnail")))
                .list();
    }

    /** Re-enrolment replaces the active templates: they stop counting and the retention job deletes them later. */
    public int deactivateActive(UUID employeeId, Instant at) {
        return jdbc.sql("""
                        UPDATE attend.face_template SET active = false, deactivated_at = :at
                         WHERE employee_id = :id AND active
                        """)
                .param("at", Timestamp.from(at))
                .param("id", employeeId)
                .update();
    }

    /** The template's employee and thumbnail path, if it still exists. */
    public Optional<Owned> owned(long templateId) {
        return jdbc.sql("SELECT employee_id, thumbnail_path FROM attend.face_template WHERE id = :id")
                .param("id", templateId)
                .query((rs, n) -> new Owned(rs.getObject("employee_id", UUID.class), rs.getString("thumbnail_path")))
                .optional();
    }

    public void delete(long templateId) {
        jdbc.sql("DELETE FROM attend.face_template WHERE id = :id")
                .param("id", templateId)
                .update();
    }

    /** Whose template it is and where its thumbnail lies. */
    public record Owned(UUID employeeId, String thumbnailPath) {}
}
