package com.mcagent.fabric;

import java.util.Set;

/**
 * Canonical set of hostile mob class simple-names used for threat detection.
 *
 * <p>Previously duplicated between {@link FabricBaritoneBridge} and
 * {@link com.mcagent.fabric.cache.WorldStateUpdater}. Both now reference this
 * single source of truth.</p>
 *
 * <p>Declared {@code public} so sub-packages ({@code cache}) can import it.
 * Not part of any external API — do not use outside {@code fabric-mod}.</p>
 */
public final class HostileMobs {

    private HostileMobs() {
        // constants class — not instantiable
    }

    /**
     * Hostile mob class simple-names. These must match the values returned by
     * {@code Entity.getClass().getSimpleName()} in the Minecraft client environment.
     */
    public static final Set<String> NAMES = Set.of(
            "Creeper", "Zombie", "Skeleton", "Spider", "Enderman", "Witch",
            "Drowned", "Husk", "Stray", "WitherSkeleton", "Blaze", "Ghast",
            "PiglinBrute", "Vindicator", "Evoker", "Ravager"
    );
}
