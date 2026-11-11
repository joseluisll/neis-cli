package es.jllopez.neis.infrastructure.adapter.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import es.jllopez.neis.infrastructure.persistence.TaskTypeEntity;
import es.jllopez.neis.infrastructure.persistence.TaskTypeJpaRepository;

/**
 * Idempotent seeding of the task-type vocabulary (US-05, epic #5) into
 * the {@code task_types} table. This is a fixed CODE vocabulary, not
 * user data: the {@code executions.task_code} FK refuses records whose
 * task type does not exist, so it must be present before the first
 * execution record. (The no-seeding owner decision covers the ADDRESS
 * catalog; the task-type table is schema vocabulary.)
 *
 * <p>The codes mirror the MVP chain's steps (issue #90) plus the chain
 * itself as a runnable unit. New task types land here as the platform
 * grows; the scheduler's configurable tasks (US-05.2) must use one of
 * these codes.</p>
 */
@Component
public class TaskTypeSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(TaskTypeSeeder.class);

    /** code, name, description — the current task-type vocabulary.
     *  Package-private for the seeder's test. */
    static final String[][] VOCABULARY = {
            {"WEBSCRAPE", "Web scrape",
                    "Obtain the listing HTML5 of an address (US-06)"},
            {"SIMPLIFY", "Simplify",
                    "Cleanse the HTML to action/data elements under the size cap (US-07)"},
            {"ANALYZE", "Analyze",
                    "Classification, selector identification and capability flagging (US-12/US-13/US-17)"},
            {"FILTER", "Filter",
                    "Extract the exposed filter definitions and apply one criterion (US-17/US-06.6)"},
            {"PRINT", "Print",
                    "Selector-based item extraction and listing output (US-14)"},
            {"CHAIN", "MVP chain",
                    "The predefined static chain SCRAPE-SIMPLIFY-ANALYZE-FILTER-PRINT (issue #90)"},
    };

    private final TaskTypeJpaRepository taskTypes;

    public TaskTypeSeeder(TaskTypeJpaRepository taskTypes) {
        this.taskTypes = taskTypes;
    }

    @Override
    public void run(ApplicationArguments args) {
        int created = 0;
        for (String[] type : VOCABULARY) {
            if (taskTypes.existsById(type[0])) {
                continue;
            }
            TaskTypeEntity entity = new TaskTypeEntity();
            entity.setCode(type[0]);
            entity.setName(type[1]);
            entity.setDescription(type[2]);
            try {
                taskTypes.save(entity);
                created++;
            } catch (DataIntegrityViolationException refused) {
                // A concurrent boot's seeding may have committed between
                // this boot's existsById check and the insert, so the
                // PRIMARY KEY refuses ours — the vocabulary is present
                // either way and startup must not fail on it (deferred
                // slice-3 review finding). DataIntegrityViolationException
                // is Spring's translation for EVERY integrity violation,
                // though: only treat the refusal as a lost race when the
                // row is actually there now. An unrelated integrity
                // failure (a future NOT NULL or CHECK constraint on the
                // vocabulary constants, say) must fail startup, not be
                // swallowed (fail-closed review finding on 5a69d27).
                if (taskTypes.existsById(type[0])) {
                    log.info("Task-type {} was already seeded by a concurrent boot",
                            type[0]);
                } else {
                    throw refused;
                }
            }
        }
        if (created > 0) {
            log.info("Task-type vocabulary: {} entries added ({} total)",
                    created, taskTypes.count());
        }
    }
}
