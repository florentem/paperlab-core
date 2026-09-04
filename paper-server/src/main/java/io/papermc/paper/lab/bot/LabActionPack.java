package io.papermc.paper.lab.bot;

import java.util.EnumMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Действия бота: имитация нажатий игрока.
 *
 * <p>Порядок вызовов и тайминги повторяют Carpet {@code EntityPlayerActionPack} —
 * это ровно то, что сервер делает при пакетах живого клиента:
 * {@code gameMode.handleBlockBreakAction}, {@code gameMode.useItemOn},
 * {@code entity.interact}, {@code player.attack}.
 *
 * <p>Три места, где отклонение от Carpet ломает поведение, поэтому воспроизведены буквально:
 * <ol>
 *   <li>прицеливание — блоки <b>и</b> сущности ({@link LabTracer}); штатный
 *       {@code Entity.pick} видит только блоки, из-за чего бот ломает блоки,
 *       но не бьёт сущности;</li>
 *   <li>при {@code interval 1} удержание сбрасывается <b>в том же тике</b>, до выполнения
 *       (см. {@link LabAction.Rhythm#releaseBeforeExecute()});</li>
 *   <li>успешное {@code use} в этом тике отменяет {@code attack}, а успешная атака
 *       при неуспешном use даёт use повторную попытку — как в
 *       {@code MinecraftClient.handleInputEvents}.</li>
 * </ol>
 */
public final class LabActionPack {

    private final LabBot bot;
    /** EnumMap обходится по ordinal: USE раньше ATTACK — как TreeMap у Carpet. */
    private final Map<LabAction, LabAction.Running> running = new EnumMap<>(LabAction.class);

    /**
     * Ход вперёд-назад и вбок, в долях от полной скорости.
     *
     * <p>Значения кладутся в {@code zza} и {@code xxa} — те же поля, куда живому игроку
     * пишет обработчик его пакетов движения. Дальше всё делает обычный тик сущности,
     * поэтому бот идёт, плывёт и управляет транспортом ровно как игрок.
     */
    private float forward;
    private float strafing;

    /** Блок, который бот сейчас копает, и накопленный прогресс. */
    private @Nullable BlockPos currentBlock;
    private float blockDamage;
    private int blockHitDelay;
    private int itemUseCooldown;

    LabActionPack(final LabBot bot) {
        this.bot = bot;
    }

    public void start(final LabAction action, final LabAction.Rhythm rhythm) {
        this.inactiveTick(action);
        this.running.put(action, new LabAction.Running(rhythm));
    }

    public void stop(final LabAction action) {
        this.running.remove(action);
        this.inactiveTick(action);
    }

    public void stopAll() {
        for (final LabAction action : LabAction.values()) {
            this.inactiveTick(action);
        }
        this.running.clear();
        this.bot.setShiftKeyDown(false);
        this.bot.setSprinting(false);
        this.stopMovement();
    }

    /**
     * Ход: {@code 1} — вперёд, {@code -1} — назад.
     *
     * <p>Значение держится, пока его не сменят: это «клавиша зажата», а не шаг.
     */
    public void setForward(final float value) {
        this.forward = value;
    }

    /** Ход вбок: {@code 1} — влево, {@code -1} — вправо. Как у живого игрока. */
    public void setStrafing(final float value) {
        this.strafing = value;
    }

    public void stopMovement() {
        this.forward = 0.0F;
        this.strafing = 0.0F;
        this.bot.zza = 0.0F;
        this.bot.xxa = 0.0F;
    }

    public float forward() {
        return this.forward;
    }

    public float strafing() {
        return this.strafing;
    }

    /**
     * Сесть в ближайший транспорт в радиусе трёх блоков.
     *
     * <p>{@code onlyRideables} — только лодки, вагонетки и лошади; иначе годится любая
     * сущность, на которую можно сесть. Лошадь сажает через {@code mobInteract}: у неё
     * посадка идёт через взаимодействие, а не через {@code startRiding}.
     *
     * @return {@code true}, если нашли, на что сесть
     */
    public boolean mount(final boolean onlyRideables) {
        final java.util.List<net.minecraft.world.entity.Entity> candidates = this.bot.level().getEntities(
            this.bot, this.bot.getBoundingBox().inflate(3.0D, 1.0D, 3.0D),
            entity -> !onlyRideables
                || entity instanceof net.minecraft.world.entity.vehicle.minecart.AbstractMinecart
                || entity instanceof net.minecraft.world.entity.vehicle.boat.AbstractBoat
                || entity instanceof net.minecraft.world.entity.animal.equine.AbstractHorse);

        final net.minecraft.world.entity.Entity vehicle = this.bot.getVehicle();
        net.minecraft.world.entity.Entity closest = null;
        double best = Double.POSITIVE_INFINITY;

        for (final net.minecraft.world.entity.Entity candidate : candidates) {
            if (candidate == this.bot || candidate == vehicle) {
                continue;
            }
            final double distance = this.bot.distanceToSqr(candidate);
            if (distance < best) {
                best = distance;
                closest = candidate;
            }
        }
        if (closest == null) {
            return false;
        }
        if (onlyRideables && closest instanceof final net.minecraft.world.entity.animal.equine.AbstractHorse horse) {
            horse.mobInteract(this.bot, net.minecraft.world.InteractionHand.MAIN_HAND);
        } else {
            this.bot.startRiding(closest, true, true);
        }
        return true;
    }

    public void dismount() {
        this.bot.stopRiding();
    }

    public Map<LabAction, LabAction.Rhythm> active() {
        final Map<LabAction, LabAction.Rhythm> out = new EnumMap<>(LabAction.class);
        this.running.forEach((action, state) -> out.put(action, state.rhythm()));
        return out;
    }

    void tick() {
        this.applyMovement();
        if (this.running.isEmpty()) {
            return;
        }
        this.running.entrySet().removeIf(entry -> entry.getValue().finished);

        // null — действие в этом тике не выполнялось; true/false — результат выполнения.
        final Map<LabAction, Boolean> attempts = new EnumMap<>(LabAction.class);

        for (final Map.Entry<LabAction, LabAction.Running> entry : this.running.entrySet()) {
            final LabAction action = entry.getKey();
            final LabAction.Running state = entry.getValue();

            // Успешное использование отменяет атаку в этом же тике.
            final boolean useSucceeded = Boolean.TRUE.equals(attempts.get(LabAction.USE));
            if (!(useSucceeded && action == LabAction.ATTACK)) {
                final Boolean result = this.tickAction(action, state);
                if (result != null) {
                    attempts.put(action, result);
                }
            }

            // Атака прошла, а use в этом тике не удался — даём use ещё попытку.
            if (action == LabAction.ATTACK
                && Boolean.TRUE.equals(attempts.get(LabAction.ATTACK))
                && Boolean.FALSE.equals(attempts.get(LabAction.USE))) {
                final LabAction.Running use = this.running.get(LabAction.USE);
                if (use != null) {
                    this.execute(LabAction.USE, use);
                    use.executed();
                }
            }
        }
    }

    /**
     * Перекладывает ход в поля ввода сущности.
     *
     * <p>Делается каждый тик, а не один раз при команде: движок обнуляет {@code zza} и
     * {@code xxa} по ходу тика, и без повторной записи бот делает один шаг и встаёт.
     *
     * <p>Приседание замедляет так же, как живого игрока.
     */
    private void applyMovement() {
        final float velocity = this.bot.isShiftKeyDown() ? 0.3F : 1.0F;
        this.bot.zza = this.forward * velocity;
        this.bot.xxa = this.strafing * velocity;
    }

    private @Nullable Boolean tickAction(final LabAction action, final LabAction.Running state) {
        if (!state.due()) {
            this.inactiveTick(action);
            return null;
        }
        if (state.rhythm().releaseBeforeExecute()) {
            // Освобождаем удержание до выполнения — иначе itemUseCooldown не обнулится
            // и interval 1 будет работать медленнее interval 2.
            this.inactiveTick(action);
        }
        final boolean result = this.execute(action, state);
        state.executed();
        return result;
    }

    /** Сброс удержания: прерывание добычи и отпускание предмета. */
    private void inactiveTick(final LabAction action) {
        switch (action) {
            case ATTACK -> this.abortMining();
            case USE -> {
                this.itemUseCooldown = 0;
                this.bot.releaseUsingItem();
            }
            default -> {
            }
        }
    }

    private void abortMining() {
        if (this.currentBlock == null) {
            return;
        }
        this.bot.level().destroyBlockProgress(-1, this.currentBlock, -1);
        this.bot.gameMode.handleBlockBreakAction(this.currentBlock,
            ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK,
            Direction.DOWN, this.bot.level().getMaxY(), -1);
        this.currentBlock = null;
        this.blockDamage = 0.0F;
    }

    private boolean execute(final LabAction action, final LabAction.Running state) {
        return switch (action) {
            case USE -> this.use();
            case ATTACK -> this.attack(state);
            case JUMP -> this.jump();
            case DROP_ITEM -> this.drop(false);
            case DROP_STACK -> this.drop(true);
            case SWAP_HANDS -> this.swapHands();
        };
    }

    /**
     * Дальность как у игрока: для блоков и сущностей она разная, поэтому трассируем
     * по большей, а решение о применимости оставляем самим вызовам движка.
     */
    private HitResult target() {
        final double reach = Math.max(this.bot.blockInteractionRange(), this.bot.entityInteractionRange());
        return LabTracer.rayTrace(this.bot, reach);
    }

    private boolean attack(final LabAction.Running state) {
        final HitResult hit = this.target();
        if (hit == null) {
            return false;
        }
        switch (hit.getType()) {
            case ENTITY -> {
                final Entity entity = ((EntityHitResult) hit).getEntity();
                // При удержании удары не спамятся: живой игрок тоже ждёт откат.
                if (!state.rhythm().hold()) {
                    this.bot.attack(entity);
                    this.bot.swing(InteractionHand.MAIN_HAND);
                }
                this.bot.resetAttackStrengthTicker();
                this.bot.resetLastActionTime();
                return true;
            }
            case BLOCK -> {
                return this.mine((BlockHitResult) hit);
            }
            default -> {
                return false;
            }
        }
    }

    /** @return true, если блок в этом тике был сломан */
    private boolean mine(final BlockHitResult hit) {
        if (this.blockHitDelay > 0) {
            this.blockHitDelay--;
            return false;
        }
        final ServerLevel level = this.bot.level();
        final BlockPos pos = hit.getBlockPos();
        final Direction side = hit.getDirection();
        if (this.bot.blockActionRestricted(level, pos, this.bot.gameMode.getGameModeForPlayer())) {
            return false;
        }
        if (this.currentBlock != null && level.getBlockState(this.currentBlock).isAir()) {
            this.currentBlock = null;
            return false;
        }
        final BlockState state = level.getBlockState(pos);
        boolean broken = false;

        if (this.bot.gameMode.getGameModeForPlayer().isCreative()) {
            this.bot.gameMode.handleBlockBreakAction(pos,
                ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, side, level.getMaxY(), -1);
            this.blockHitDelay = 5;
            broken = true;
        } else if (this.currentBlock == null || !this.currentBlock.equals(pos)) {
            if (this.currentBlock != null) {
                this.bot.gameMode.handleBlockBreakAction(this.currentBlock,
                    ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, side, level.getMaxY(), -1);
            }
            this.bot.gameMode.handleBlockBreakAction(pos,
                ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, side, level.getMaxY(), -1);
            final boolean solid = !state.isAir();
            if (solid && this.blockDamage == 0.0F) {
                state.attack(level, pos, this.bot);
            }
            if (solid && state.getDestroyProgress(this.bot, level, pos) >= 1.0F) {
                this.currentBlock = null;
                broken = true;
            } else {
                this.currentBlock = pos;
                this.blockDamage = 0.0F;
            }
        } else {
            this.blockDamage += state.getDestroyProgress(this.bot, level, pos);
            if (this.blockDamage >= 1.0F) {
                this.bot.gameMode.handleBlockBreakAction(pos,
                    ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, side, level.getMaxY(), -1);
                this.currentBlock = null;
                this.blockHitDelay = 5;
                this.blockDamage = 0.0F;
                broken = true;
            }
            level.destroyBlockProgress(-1, pos, (int) (this.blockDamage * 10.0F));
        }
        this.bot.resetLastActionTime();
        this.bot.swing(InteractionHand.MAIN_HAND);
        return broken;
    }

    private boolean use() {
        if (this.itemUseCooldown > 0) {
            this.itemUseCooldown--;
            return true;
        }
        if (this.bot.isUsingItem()) {
            return true;
        }
        final HitResult hit = this.target();
        final ServerLevel level = this.bot.level();

        for (final InteractionHand hand : InteractionHand.values()) {
            if (hit != null && hit.getType() == HitResult.Type.BLOCK) {
                final BlockHitResult blockHit = (BlockHitResult) hit;
                final BlockPos pos = blockHit.getBlockPos();
                this.bot.resetLastActionTime();
                if (pos.getY() < level.getMaxY() - (blockHit.getDirection() == Direction.UP ? 1 : 0)
                    && level.mayInteract(this.bot, pos)) {
                    final InteractionResult result = this.bot.gameMode.useItemOn(
                        this.bot, level, this.bot.getItemInHand(hand), hand, blockHit);
                    if (result instanceof final InteractionResult.Success success) {
                        if (success.swingSource() == InteractionResult.SwingSource.SERVER) {
                            this.bot.swing(hand);
                        }
                        this.itemUseCooldown = 3;
                        return true;
                    }
                }
            } else if (hit != null && hit.getType() == HitResult.Type.ENTITY) {
                final EntityHitResult entityHit = (EntityHitResult) hit;
                final Entity entity = entityHit.getEntity();
                this.bot.resetLastActionTime();
                final Vec3 relative = entityHit.getLocation()
                    .subtract(entity.getX(), entity.getY(), entity.getZ());
                if (entity.interact(this.bot, hand, relative).consumesAction()) {
                    this.itemUseCooldown = 3;
                    return true;
                }
                if (this.bot.interactOn(entity, hand, relative).consumesAction()) {
                    this.itemUseCooldown = 3;
                    return true;
                }
            }
            if (this.bot.gameMode.useItem(this.bot, level, this.bot.getItemInHand(hand), hand)
                .consumesAction()) {
                this.itemUseCooldown = 3;
                return true;
            }
        }
        return false;
    }

    private boolean jump() {
        if (!this.bot.onGround()) {
            return false;
        }
        this.bot.jumpFromGround();
        return true;
    }

    private boolean drop(final boolean whole) {
        final Inventory inventory = this.bot.getInventory();
        final int slot = inventory.getSelectedSlot();
        final ItemStack stack = inventory.getItem(slot);
        if (stack.isEmpty()) {
            return false;
        }
        this.bot.drop(inventory.removeItem(slot, whole ? stack.getCount() : 1), false, true);
        return true;
    }

    private boolean swapHands() {
        final ItemStack main = this.bot.getMainHandItem().copy();
        this.bot.setItemInHand(InteractionHand.MAIN_HAND, this.bot.getOffhandItem().copy());
        this.bot.setItemInHand(InteractionHand.OFF_HAND, main);
        return true;
    }
}
