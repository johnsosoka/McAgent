package com.mcagent.fabric.mixin;

import com.mcagent.fabric.ModEventHooks;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MessageSignature;
import net.minecraft.client.multiplayer.chat.GuiMessageTag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin that intercepts chat messages arriving at the client.
 * Fires {@link ModEventHooks#onChatMessage(Component)} for each message.
 */
@Mixin(ChatComponent.class)
public class ChatComponentMixin {

    @Inject(method = "addPlayerMessage", at = @At("HEAD"), remap = false)
    private void onAddPlayerMessage(Component message, MessageSignature signature, GuiMessageTag tag, CallbackInfo ci) {
        ModEventHooks.onChatMessage(message);
    }

    @Inject(method = "addServerSystemMessage", at = @At("HEAD"), remap = false)
    private void onAddServerSystemMessage(Component message, CallbackInfo ci) {
        ModEventHooks.onChatMessage(message);
    }
}
