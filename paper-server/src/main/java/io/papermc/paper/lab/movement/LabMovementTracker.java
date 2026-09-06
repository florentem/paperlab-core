package io.papermc.paper.lab.movement;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Tracks the stages of an entity's movement calculation (move) for /log movement.
 */
public final class LabMovementTracker {

    public enum Modification {
        PISTON("Piston Limit"),
        SNEAKING("Sneaking"),
        COLLISION("Collision");

        private final String displayName;

        Modification(final String displayName) {
            this.displayName = displayName;
        }

        public String displayName() {
            return this.displayName;
        }
    }

    public record Step(Vec3 oldDelta, Vec3 newDelta, Modification modification) {
    }

    public interface Listener {
        void onMovementReport(Entity entity, MoverType moverType, Vec3 originalPos, Vec3 originalMovement,
                              List<Step> modifications, Vec3 finalMovement, Vec3 finalPos);
    }

    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();

    private final Entity entity;
    private final MoverType moverType;
    private final Vec3 originalPos;
    private final Vec3 originalMovement;
    private Vec3 currentDelta;
    private final List<Step> steps = new ArrayList<>(3);

    private LabMovementTracker(final Entity entity, final MoverType moverType, final Vec3 originalMovement) {
        this.entity = entity;
        this.moverType = moverType;
        this.originalPos = entity.position();
        this.originalMovement = originalMovement;
        this.currentDelta = originalMovement;
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

    public static @Nullable LabMovementTracker onStart(final Entity entity, final MoverType moverType, final Vec3 delta) {
        if (LISTENERS.isEmpty() || entity.level().isClientSide()) {
            return null;
        }
        return new LabMovementTracker(entity, moverType, delta);
    }

    public void record(final Modification modification, final Vec3 newDelta) {
        if (this.currentDelta.subtract(newDelta).lengthSqr() >= 1e-12) {
            this.steps.add(new Step(this.currentDelta, newDelta, modification));
            this.currentDelta = newDelta;
        }
    }

    public void onEnd(final Entity entity, final Vec3 finalMovement) {
        if (!LISTENERS.isEmpty()) {
            final Vec3 finalPos = entity.position();
            final List<Step> copy = List.copyOf(this.steps);
            for (final Listener listener : LISTENERS) {
                listener.onMovementReport(this.entity, this.moverType, this.originalPos, this.originalMovement,
                    copy, finalMovement, finalPos);
            }
        }
    }
}
