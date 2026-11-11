package es.jllopez.neis.infrastructure.adapter.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import es.jllopez.neis.domain.model.Chain;
import es.jllopez.neis.domain.model.ChainTask;
import es.jllopez.neis.domain.port.ChainStorePort;
import es.jllopez.neis.infrastructure.persistence.ChainEntity;
import es.jllopez.neis.infrastructure.persistence.ChainJpaRepository;
import es.jllopez.neis.infrastructure.persistence.ChainTaskEntity;
import es.jllopez.neis.infrastructure.persistence.ChainTaskJpaRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Driven adapter implementing {@link ChainStorePort} over the Spring
 * Data JPA repositories (US-08.1, issue #33): chains and their tasks
 * in the SQLite {@code chains}/{@code chain_tasks} tables of schema
 * V1. Saving a chain replaces its task rows wholesale (the ordered
 * list is one value); per-task parameters (US-08.3) round-trip as the
 * schema's {@code parameters_json} JSON object.
 */
@Component
public class ChainStoreConnector implements ChainStorePort {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, String>> PARAMS =
            new TypeReference<>() {
            };

    private final ChainJpaRepository chains;
    private final ChainTaskJpaRepository tasks;

    public ChainStoreConnector(ChainJpaRepository chains, ChainTaskJpaRepository tasks) {
        this.chains = chains;
        this.tasks = tasks;
    }

    @Override
    @Transactional
    public Chain save(Chain chain) {
        ChainEntity entity = chains.findById(chain.id()).orElseGet(ChainEntity::new);
        entity.setId(chain.id());
        entity.setName(chain.name());
        entity.setDescription(chain.description());
        entity.setCreatedAt(chain.createdAt() == null ? null : chain.createdAt().toString());
        chains.save(entity);
        // The task list is replaced wholesale: positions are the chain's
        // order, so an update re-writes all of them.
        tasks.deleteByChainId(chain.id());
        for (ChainTask task : chain.tasks()) {
            ChainTaskEntity taskEntity = new ChainTaskEntity();
            taskEntity.setChainId(chain.id());
            taskEntity.setPosition(task.position());
            taskEntity.setTaskCode(task.taskCode());
            taskEntity.setParametersJson(toJson(task.parameters()));
            tasks.save(taskEntity);
        }
        return chain;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Chain> findById(String id) {
        return chains.findById(id).map(this::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Chain> findByName(String name) {
        return chains.findByName(name).map(this::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Chain> findAll() {
        return chains.findAll().stream()
                .map(this::toDomain)
                .sorted(java.util.Comparator.comparing(Chain::name))
                .toList();
    }

    private Chain toDomain(ChainEntity e) {
        List<ChainTask> chainTasks = tasks.findByChainIdOrderByPositionAsc(e.getId())
                .stream()
                .map(t -> new ChainTask(t.getPosition(), t.getTaskCode(),
                        fromJson(t.getParametersJson())))
                .toList();
        return new Chain(e.getId(), e.getName(), e.getDescription(),
                e.createdAtInstant(), chainTasks);
    }

    private String toJson(Map<String, String> parameters) {
        if (parameters == null || parameters.isEmpty()) {
            return null;
        }
        try {
            return JSON.writeValueAsString(parameters);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize chain task parameters", e);
        }
    }

    private Map<String, String> fromJson(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return JSON.readValue(json, PARAMS);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(
                    "Could not read chain task parameters: " + json, e);
        }
    }
}
