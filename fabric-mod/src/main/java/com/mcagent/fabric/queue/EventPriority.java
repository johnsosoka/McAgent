package com.mcagent.fabric.queue;

/**
 * Priority levels for inbound player commands.
 * Lower ordinal = higher priority.
 */
public enum EventPriority {
    CANCEL,     // Stop / cancel commands
    EMERGENCY,  // Safety / health alerts directed at the bot
    NORMAL      // Regular chat commands
}
