package com.mcagent.core.routine;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * A named, parameterized Plan template that can be invoked by name.
 *
 * <p>Routines are the Sprint 14 A4 "macro library" — reusable sequences of steps
 * with {@code ${param}} placeholders that are substituted at invocation time.
 * System-seeded routines (e.g. {@code returnHome}, {@code mineUntilFull}) are
 * created by {@link RoutineSeeder}. LLM-authored routines are created via the
 * {@code defineRoutine} tool and stored alongside the seeded ones.</p>
 *
 * <p>The {@code planTemplate} field is a JSON array of Step objects using the
 * same format accepted by {@code submitPlan}. Placeholders take the form
 * {@code "${paramName}"} and are replaced with provided argument values before
 * the template is deserialized into a {@link com.mcagent.core.task.Plan}.</p>
 */
@Entity
@Table(name = "routine_definition")
public class RoutineDefinition {

    @Id
    @GeneratedValue
    private UUID id;

    /** Unique camelCase name used to invoke this routine (e.g. "returnHome"). */
    @Column(unique = true, nullable = false)
    private String name;

    /** Short human-readable description shown in listRoutines() output. */
    @Column(length = 500)
    private String description;

    /**
     * JSON array of steps with {@code ${param}} placeholders.
     * Example: {@code [{"type":"Navigate","x":"${baseX}","y":"${baseY}","z":"${baseZ}"}]}
     */
    @Column(columnDefinition = "TEXT", nullable = false)
    private String planTemplate;

    /**
     * JSON array of parameter names expected by this routine.
     * Example: {@code ["baseX","baseY","baseZ"]}
     */
    @Column(length = 500)
    private String parametersJson;

    /** True for routines created by {@link RoutineSeeder}; false for LLM-authored ones. */
    private boolean systemSeeded;

    private Instant createdAt;

    protected RoutineDefinition() {
        // Required by JPA
    }

    public RoutineDefinition(String name,
                             String description,
                             String planTemplate,
                             String parametersJson,
                             boolean systemSeeded) {
        this.name = name;
        this.description = description;
        this.planTemplate = planTemplate;
        this.parametersJson = parametersJson;
        this.systemSeeded = systemSeeded;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getPlanTemplate() { return planTemplate; }
    public String getParametersJson() { return parametersJson; }
    public boolean isSystemSeeded() { return systemSeeded; }
    public Instant getCreatedAt() { return createdAt; }
}
