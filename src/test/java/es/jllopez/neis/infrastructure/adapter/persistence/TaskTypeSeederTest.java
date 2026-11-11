package es.jllopez.neis.infrastructure.adapter.persistence;

import es.jllopez.neis.infrastructure.persistence.TaskTypeEntity;
import es.jllopez.neis.infrastructure.persistence.TaskTypeJpaRepository;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Task-type vocabulary seeding (US-05): idempotent, and tolerant of a
 * concurrent boot seeding the same codes over one database (deferred
 * slice-3 review finding) — the PRIMARY KEY refusal of a lost race
 * must not fail startup.
 */
class TaskTypeSeederTest {

    @Test
    void aLostRaceAgainstAConcurrentBootDoesNotFailStartup() {
        TaskTypeJpaRepository taskTypes = mock(TaskTypeJpaRepository.class);
        when(taskTypes.existsById(any())).thenReturn(false);
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
    void aFullySeededDatabaseIsNotTouched() {
        TaskTypeJpaRepository taskTypes = mock(TaskTypeJpaRepository.class);
        when(taskTypes.existsById(any())).thenReturn(true);

        new TaskTypeSeeder(taskTypes).run(null);

        verify(taskTypes, times(0)).save(any(TaskTypeEntity.class));
        verify(taskTypes, times(0)).count();
    }
}
