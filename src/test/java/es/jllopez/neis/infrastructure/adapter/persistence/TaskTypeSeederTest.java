package es.jllopez.neis.infrastructure.adapter.persistence;

import es.jllopez.neis.infrastructure.persistence.TaskTypeEntity;
import es.jllopez.neis.infrastructure.persistence.TaskTypeJpaRepository;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Task-type vocabulary seeding (US-05): idempotent, tolerant of a
 * concurrent boot seeding the same codes over one database (deferred
 * slice-3 review finding) — but ONLY of a genuine lost race: an
 * unrelated integrity violation must fail startup (fail-closed review
 * finding on 5a69d27).
 */
class TaskTypeSeederTest {

    @Test
    void aLostRaceAgainstAConcurrentBootDoesNotFailStartup() {
        TaskTypeJpaRepository taskTypes = mock(TaskTypeJpaRepository.class);
        // existsById answers false on the FIRST check of a code and true
        // afterwards — the other boot committed between our check and
        // our insert, so the catch's confirming re-check sees the row.
        Map<String, AtomicInteger> checks = new HashMap<>();
        when(taskTypes.existsById(any())).thenAnswer(inv -> {
            String code = inv.getArgument(0, String.class);
            return checks.computeIfAbsent(code, key -> new AtomicInteger())
                    .incrementAndGet() > 1;
        });
        // First save loses a race (PK refusal), later saves succeed.
        AtomicInteger calls = new AtomicInteger();
        doThrow(new DataIntegrityViolationException("PK task_types"))
                .doAnswer(inv -> {
                    calls.incrementAndGet();
                    return null;
                })
                .when(taskTypes).save(any(TaskTypeEntity.class));

        TaskTypeSeeder seeder = new TaskTypeSeeder(taskTypes);

        // Run(ApplicationArguments) accepts null args (unused).
        assertDoesNotThrow(() -> seeder.run(null));
        // The remaining vocabulary entries are still seeded after the lost race.
        assertEquals(TaskTypeSeeder.VOCABULARY.length - 1, calls.get());
    }

    @Test
    void anUnrelatedIntegrityViolationIsRethrownNotSwallowed() {
        // DataIntegrityViolationException covers EVERY integrity
        // violation, not just PK races: when the refused row is NOT
        // present afterwards, the refusal is a real integrity problem
        // (a future NOT NULL/CHECK constraint, say) and must fail
        // startup rather than be swallowed (fail-closed review finding
        // on 5a69d27).
        TaskTypeJpaRepository taskTypes = mock(TaskTypeJpaRepository.class);
        when(taskTypes.existsById(any())).thenReturn(false);
        doThrow(new DataIntegrityViolationException("NOT NULL constraint failed"))
                .when(taskTypes).save(any(TaskTypeEntity.class));

        DataIntegrityViolationException e = assertThrows(
                DataIntegrityViolationException.class,
                () -> new TaskTypeSeeder(taskTypes).run(null));
        assertEquals("NOT NULL constraint failed", e.getMessage());
    }

    @Test
    void aFullySeededDatabaseIsNotTouched() {
        TaskTypeJpaRepository taskTypes = mock(TaskTypeJpaRepository.class);
        when(taskTypes.existsById(any())).thenReturn(true);

        new TaskTypeSeeder(taskTypes).run(null);

        verify(taskTypes, times(0)).save(any(TaskTypeEntity.class));
        verify(taskTypes, times(0)).count();
    }
}
