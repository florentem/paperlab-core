package io.papermc.paper.lab.item;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.item.ItemEntity;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Tracks the lifecycle of item entities (ItemEntity) for /log item.
 */
public final class LabItemTracker {

    public interface Listener {
        void onItemCreated(ItemEntity item);
        void onItemDespawned(ItemEntity item);
        void onItemDied(ItemEntity item, @Nullable DamageSource source, float damage);
    }

    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();

    private LabItemTracker() {
    }

    public static void addListener(final Listener listener) {
        LISTENERS.add(listener);
    }

    public static void removeListener(final Listener listener) {
        LISTENERS.remove(listener);
    }

    public static boolean hasListeners() {
        return !LISTENERS.isEmpty();
    }

    public static void onItemCreated(final ItemEntity item) {
        if (!LISTENERS.isEmpty()) {
            for (final Listener listener : LISTENERS) {
                listener.onItemCreated(item);
            }
        }
    }

    public static void onItemDespawned(final ItemEntity item) {
        if (!LISTENERS.isEmpty()) {
            for (final Listener listener : LISTENERS) {
                listener.onItemDespawned(item);
            }
        }
    }

    public static void onItemDied(final ItemEntity item, final @Nullable DamageSource source, final float damage) {
        if (!LISTENERS.isEmpty()) {
            for (final Listener listener : LISTENERS) {
                listener.onItemDied(item, source, damage);
            }
        }
    }
}
