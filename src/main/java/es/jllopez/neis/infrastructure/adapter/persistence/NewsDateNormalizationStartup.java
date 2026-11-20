package es.jllopez.neis.infrastructure.adapter.persistence;

import es.jllopez.neis.domain.model.NewsEventQuery;
import es.jllopez.neis.infrastructure.persistence.NewsEventEntity;
import es.jllopez.neis.infrastructure.persistence.NewsEventJpaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The US-14.9 (issue #123) legacy-row date backfill: rows captured
 * BEFORE the normalized {@code date_normalized} column existed (V21)
 * get their normalized ISO date computed once, from the raw text,
 * through the ONE shared domain normalizer
 * ({@link NewsEventQuery#normalizeDate}) — a pure-SQL backfill cannot
 * run the lenient Java parser without duplicating it, which is exactly
 * the connector/test-double drift this requirement forbids.
 *
 * <p>Runs as a {@link SmartLifecycle} at phase 0 (before the web
 * server serves), SERVER MODE ONLY — the same gate rationale as
 * {@code JobStartupReconciliation}: CLI one-shot runs share the live
 * service's SQLite file, so a write sweep on every CLI boot would
 * contend with the running service for no benefit (the query filter
 * falls back to the lenient raw parse for un-backfilled rows, so a
 * CLI read is correct before the backfill has run). The sweep is
 * idempotent: only rows with a raw date and no normalized value are
 * selected, so after one pass the per-boot cost is the empty select.</p>
 *
 * <p>Rows whose raw date does not parse KEEP a null normalized value
 * (the observable flag the requirement demands) and are LOGGED with
 * their id and raw text — never silently dropped or defaulted. They
 * are re-examined on each boot, so an improved parser picks them up
 * without a second migration.</p>
 */
@Component
@ConditionalOnProperty(prefix = "neis.server", name = "enabled",
        havingValue = "true")
public class NewsDateNormalizationStartup implements SmartLifecycle {

    public static final int NORMALIZE_PHASE = 0;

    private static final Logger log = LoggerFactory.getLogger(
            NewsDateNormalizationStartup.class);

    private final NewsEventJpaRepository jpa;

    private volatile boolean running;

    public NewsDateNormalizationStartup(NewsEventJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public int getPhase() {
        return NORMALIZE_PHASE;
    }

    @Override
    public void start() {
        running = true;
        normalize();
    }

    /** The backfill sweep: backfills every legacy row and logs the
     * outcome. Public for tests (the SmartLifecycle shell is thin —
     * the sweep IS the component); idempotent. */
    public void normalize() {
        List<NewsEventEntity> legacy =
                jpa.findByDateNormalizedIsNullAndDateIsNotNull();
        if (legacy.isEmpty()) {
            return;
        }
        int backfilled = 0;
        int unparseable = 0;
        for (NewsEventEntity entity : legacy) {
            String normalized = NewsEventQuery.normalizeDate(entity.getDate());
            if (normalized != null) {
                entity.setDateNormalized(normalized);
                jpa.save(entity);
                backfilled++;
            } else {
                // The flag is the null value itself; the log line makes
                // it visible (id + raw text) without inventing a date.
                unparseable++;
                log.warn("News item date NOT normalizable — flagged with "
                                + "null normalized value (id={}, raw date='{}'); "
                                + "the row is excluded from date-range queries "
                                + "until the raw text is corrected",
                        entity.getId(), entity.getDate());
            }
        }
        log.info("[NewsDateBackfill] legacy rows examined: {}, backfilled "
                + "with normalized ISO dates: {}, flagged unparseable: {}",
                legacy.size(), backfilled, unparseable);
    }

    @Override
    public void stop() {
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
