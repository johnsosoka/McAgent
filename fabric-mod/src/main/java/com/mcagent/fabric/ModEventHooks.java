package com.mcagent.fabric;

import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

/**
 * Static hooks that Mixins call into. McAgentFabricMod registers callbacks here.
 */
public final class ModEventHooks {
    private static Consumer<Component> chatMessageCallback;
    private static Runnable tickCallback;

    private ModEventHooks() {
        // utility class
    }

    public static void setChatMessageCallback(Consumer<Component> callback) {
        chatMessageCallback = callback;
    }

    public static void setTickCallback(Runnable callback) {
        tickCallback = callback;
    }

    public static void clearCallbacks() {
        chatMessageCallback = null;
        tickCallback = null;
    }

    public static void onChatMessage(Component message) {
        if (chatMessageCallback != null) {
            chatMessageCallback.accept(message);
        }
    }

    public static void onClientTick() {
        if (tickCallback != null) {
            tickCallback.run();
        }
    }
}
