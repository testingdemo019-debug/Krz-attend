package com.krztechnologies.attend.recognition.repository;

import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The impact preview's read-only aggregates over recent crossings with a face score (the crossing table belongs to
 * the crossing module; plain SQL keeps the modules independent). Nothing is loaded row by row.
 */
@Repository
public class ImpactQueries {

    /** The prototype's histogram: 40 bins from 0.10 to 1.00 (scores outside are counted in the first or last bin). */
    public static final int BINS = 40;

    public static final double LOW = 0.10;
    public static final double HIGH = 1.00;

    private final JdbcClient jdbc;

    public ImpactQueries(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * How the scores split under one set of thresholds (R14 step 2; K-of-N agreement is not stored, so it is left
     * out).
     *
     * @param accepted top ≥ accept and top − second ≥ margin
     * @param review not accepted, but top ≥ accept − reviewBand
     * @param unknown the rest
     */
    public record Split(long accepted, long review, long unknown) {}

    public long[] histogram(Instant from, Instant to) {
        long[] bins = new long[BINS];
        jdbc.sql("""
                        SELECT least(greatest(floor((score - :low) / (:high - :low) * :bins), 0), :bins - 1)::int AS bin,
                               count(*) AS n
                          FROM attend.crossing
                         WHERE score IS NOT NULL AND occurred_at >= :from AND occurred_at < :to
                         GROUP BY 1
                        """)
                .param("low", LOW)
                .param("high", HIGH)
                .param("bins", BINS)
                .param("from", Timestamp.from(from))
                .param("to", Timestamp.from(to))
                .query(rs -> {
                    bins[rs.getInt("bin")] = rs.getLong("n");
                });
        return bins;
    }

    public Split split(Instant from, Instant to, double accept, double reviewBand, double margin) {
        return jdbc.sql("""
                        SELECT count(*) FILTER (WHERE auto) AS accepted,
                               count(*) FILTER (WHERE NOT auto AND score >= :accept - :band) AS review,
                               count(*) FILTER (WHERE NOT auto AND score < :accept - :band) AS unknown
                          FROM (SELECT score,
                                       score >= :accept
                                       AND coalesce(margin, score - second_score, 1) >= :margin AS auto
                                  FROM attend.crossing
                                 WHERE score IS NOT NULL AND occurred_at >= :from AND occurred_at < :to) AS scored
                        """)
                .param("accept", accept)
                .param("band", reviewBand)
                .param("margin", margin)
                .param("from", Timestamp.from(from))
                .param("to", Timestamp.from(to))
                .query((rs, n) -> new Split(rs.getLong("accepted"), rs.getLong("review"), rs.getLong("unknown")))
                .single();
    }
}
