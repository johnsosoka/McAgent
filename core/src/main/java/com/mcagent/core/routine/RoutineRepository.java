package com.mcagent.core.routine;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data repository for {@link RoutineDefinition} entities.
 */
public interface RoutineRepository extends JpaRepository<RoutineDefinition, UUID> {

    /** Find a routine by its unique camelCase name. */
    Optional<RoutineDefinition> findByName(String name);

    /** True when a routine with the given name already exists. */
    boolean existsByName(String name);
}
