package es.jllopez.neis.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * JPA mapping of one chain task (US-08.1, issue #33) onto the
 * {@code chain_tasks} table of schema V1: position (1..10 per the
 * schema CHECK), the task type (FK to {@code task_types}) and the
 * per-task parameters (US-08.3) as a JSON object string.
 */
@Entity
@Table(name = "chain_tasks")
public class ChainTaskEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "chain_id", nullable = false)
    private String chainId;

    @Column(nullable = false)
    private int position;

    @Column(name = "task_code", nullable = false)
    private String taskCode;

    @Column(name = "parameters_json")
    private String parametersJson;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getChainId() {
        return chainId;
    }

    public void setChainId(String chainId) {
        this.chainId = chainId;
    }

    public int getPosition() {
        return position;
    }

    public void setPosition(int position) {
        this.position = position;
    }

    public String getTaskCode() {
        return taskCode;
    }

    public void setTaskCode(String taskCode) {
        this.taskCode = taskCode;
    }

    public String getParametersJson() {
        return parametersJson;
    }

    public void setParametersJson(String parametersJson) {
        this.parametersJson = parametersJson;
    }
}
