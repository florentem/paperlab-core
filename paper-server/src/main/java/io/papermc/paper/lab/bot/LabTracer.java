package io.papermc.paper.lab.bot;

import java.util.Optional;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Прицеливание бота: блоки <b>и</b> сущности.
 *
 * <p>Штатный {@code Entity.pick(...)} трассирует только блоки — он сводится к
 * {@code level.clip(...)}. Если использовать его как «что под прицелом», бот будет
 * ломать блоки, но никогда не ударит ни одну сущность. Поэтому нужен собственный
 * трассировщик, как {@code carpet.script.utils.Tracer}: считаем попадание по блокам,
 * затем по сущностям, и берём то, что ближе.
 */
public final class LabTracer {

    private LabTracer() {
    }

    public static HitResult rayTrace(final Entity source, final double reach) {
        final BlockHitResult blockHit = rayTraceBlocks(source, reach);
        double maxSqDist = reach * reach;
        if (blockHit != null && blockHit.getType() != HitResult.Type.MISS) {
            maxSqDist = blockHit.getLocation().distanceToSqr(source.getEyePosition(1.0F));
        }
        final EntityHitResult entityHit = rayTraceEntities(source, reach, maxSqDist);
        return entityHit == null ? blockHit : entityHit;
    }

    public static BlockHitResult rayTraceBlocks(final Entity source, final double reach) {
        final Vec3 eye = source.getEyePosition(1.0F);
        final Vec3 view = source.getViewVector(1.0F);
        final Vec3 end = eye.add(view.x * reach, view.y * reach, view.z * reach);
        return source.level().clip(new ClipContext(
            eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, source));
    }

    public static @Nullable EntityHitResult rayTraceEntities(final Entity source,
                                                             final double reach,
                                                             final double maxSqDist) {
        final Vec3 eye = source.getEyePosition(1.0F);
        final Vec3 reachVec = source.getViewVector(1.0F).scale(reach);
        final Vec3 end = eye.add(reachVec);
        final AABB box = source.getBoundingBox().expandTowards(reachVec).inflate(1.0D);
        final Level level = source.level();

        double bestDist = maxSqDist;
        Entity best = null;
        Vec3 bestPos = null;

        for (final Entity current : level.getEntities(source, box,
            e -> !e.isSpectator() && e.isPickable())) {
            final AABB currentBox = current.getBoundingBox().inflate(current.getPickRadius());
            final Optional<Vec3> hit = currentBox.clip(eye, end);
            if (currentBox.contains(eye)) {
                if (bestDist >= 0.0D) {
                    best = current;
                    bestPos = hit.orElse(eye);
                    bestDist = 0.0D;
                }
            } else if (hit.isPresent()) {
                final Vec3 hitPos = hit.get();
                final double dist = eye.distanceToSqr(hitPos);
                if (dist < bestDist || bestDist == 0.0D) {
                    // Сущность в том же транспорте не перекрывает цель.
                    if (current.getRootVehicle() == source.getRootVehicle()) {
                        if (bestDist == 0.0D) {
                            best = current;
                            bestPos = hitPos;
                        }
                    } else {
                        best = current;
                        bestPos = hitPos;
                        bestDist = dist;
                    }
                }
            }
        }
        return best == null ? null : new EntityHitResult(best, bestPos);
    }
}
