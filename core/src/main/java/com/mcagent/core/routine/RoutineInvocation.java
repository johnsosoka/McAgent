package com.mcagent.core.routine;

import java.util.Map;

/**
 * A request to invoke a named {@link RoutineDefinition} with concrete argument values.
 *
 * <p>Returned by {@link AutoModeDriver#pickRoutine} when the world state warrants
 * autonomous action. The caller is responsible for looking up and submitting the
 * routine via {@link RoutineService#invoke}.</p>
 *
 * @param routineName Name of the routine to invoke.
 * @param params      Concrete argument values keyed by parameter name.
 */
public record RoutineInvocation(String routineName, Map<String, Object> params) {}
