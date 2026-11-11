package es.jllopez.neis.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * JPA mapping of a task type (US-05, epic #5) onto the
 * {@code task_types} table of schema V1.
 */
@Entity
@Table(name = "task_types")
public class TaskTypeEntity {

    @Id
    private String code;

    @Column(nullable = false)
    private String name;

    private String description;

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }
}
