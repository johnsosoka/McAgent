package com.mcagent.core.task;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * A condition that a {@link WaitForStep} polls until satisfied or timed out.
 *
 * <p>Sealed so the executor's pattern-match is exhaustive and the Jackson
 * polymorphic type mapping is explicit rather than open-ended.</p>
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = InventoryHasCondition.class, name = "InventoryHas")
})
public sealed interface WaitCondition permits InventoryHasCondition {

    /** Human-readable description, used in framework messages. */
    String describe();
}
