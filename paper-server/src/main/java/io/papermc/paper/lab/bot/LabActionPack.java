package io.papermc.paper.lab.bot;

import java.util.EnumMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
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
 * <p>Модель и последовательность вызовов взяты из Carpet {@code EntityPlayerActionPack} —
 * это ровно те вызовы, которые делает сервер при получении пакетов от живого клиента:
 * {@code gameMode.handleBlockBreakAction}, {@code gameMode.useItemOn}, {@code entity.interact},
 * {@code player.attack}. Ничего своего в игровую логику не добавляется.
 *
 * <p>Тикается в фазе соединений <b>до</b> {@code doTick()} — там, где у живого игрока
 * обрабатываются входящие пакеты.
 *
 * <p>Дальность берётся из атрибутов {@code blockInteractionRange}/{@code entityInteractionRange},
 * а не из константы: так бот подчиняется тем же правилам, что игрок.
 */
public final class LabActionPack {

    private final LabBot bot;
    private final Map<LabAction, LabAction.Running> running = new EnumMap<>(LabAction.class);

    /** Блок, который бот сейчас копает, и накопленный прогресс. */
    private @Nullable BlockPos currentBlock;
    private float blockDamage;
    private int blockHitDelay;
    private int itemUseCooldown;

    LabActionPack(final LabBot bot) {
        this.bot = bot;
    }

    public void start(final LabAction action, final LabAction.Rhythm rhythm) {
        this.stopSideEffects(action);
        this.running.put(action, new LabAction.Running(rhythm));
    }

    public void stop(final LabAction action) {
        this.running.remove(action);
        this.stopSideEffects(action);
    }

    public void stopAll() {
        for (final LabAction action : LabAction.values()) {
            this.stopSideEffects(action);
        }
        this.running.clear();
        this.bot.setShiftKeyDown(false);
        this.bot.setSprinting(false);
    }

    public Map<LabAction, LabAction.Rhythm> active() {
        final Map<LabAction, LabAction.Rhythm> out = new EnumMap<>(LabAction.class);
        this.running.forEach((action, state) -> out.put(action, state.rhythm()));
        return out;
    }

    /** Прерывание добычи блока и использования предмета при остановке действия. */
    private void stopSideEffects(final LabAction action) {
        if (action == LabAction.ATTACK) {
            this.abortMining();
        } else if (action == LabAction.USE) {
            this.itemUseCooldown = 0;
            this.bot.releaseUsingItem();
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

    void tick() {
        if (this.running.isEmpty()) {
            return;
        }
        this.running.entrySet().removeIf(entry -> entry.getValue().finished);

        for (final Map.Entry<LabAction, LabAction.Running> entry : this.running.entrySet()) {
            final LabAction action = entry.getKey();
            final LabAction.Running state = entry.getValue();
            if (!state.due()) {
                // Между срабатываниями удержание сбрасывается — иначе лук и еда
                // никогда не отпустятся.
                if (action == LabAction.ATTACK) {
                    this.abortMining();
                } else if (action == LabAction.USE) {
                    this.itemUseCooldown = 0;
                    this.bot.releaseUsingItem();
                }
                continue;
            }
            switch (action) {
                case ATTACK -> this.attack(state);
                case USE -> this.use();
                case JUMP -> this.jump();
                case DROP_ITEM -> this.drop(false);
                case DROP_STACK -> this.drop(true);
                case SWAP_HANDS -> this.swapHands();
            }
            state.executed();
        }
    }

    private HitResult target() {
        final double reach = Math.max(this.bot.blockInteractionRange(), this.bot.entityInteractionRange());
        return this.bot.pick(reach, 1.0F, false);
    }

    private void attack(final LabAction.Running state) {
        final HitResult hit = this.target();
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
            }
            case BLOCK -> this.mine((BlockHitResult) hit);
            default -> {
            }
        }
    }

    private void mine(final BlockHitResult hit) {
        if (this.blockHitDelay > 0) {
            this.blockHitDelay--;
            return;
        }
        final ServerLevel level = this.bot.level();
        final BlockPos pos = hit.getBlockPos();
        final Direction side = hit.getDirection();
        if (this.bot.blockActionRestricted(level, pos, this.bot.gameMode.getGameModeForPlayer())) {
            return;
        }
        if (this.currentBlock != null && level.getBlockState(this.currentBlock).isAir()) {
            this.currentBlock = null;
            return;
        }
        final BlockState state = level.getBlockState(pos);

        if (this.bot.gameMode.getGameModeForPlayer().isCreative()) {
            this.bot.gameMode.handleBlockBreakAction(pos,
                ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, side, level.getMaxY(), -1);
            this.blockHitDelay = 5;
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
            }
            level.destroyBlockProgress(-1, pos, (int) (this.blockDamage * 10.0F));
        }
        this.bot.resetLastActionTime();
        this.bot.swing(InteractionHand.MAIN_HAND);
    }

    private void use() {
        if (this.itemUseCooldown > 0) {
            this.itemUseCooldown--;
            return;
        }
        if (this.bot.isUsingItem()) {
            return;
        }
        final HitResult hit = this.target();
        final ServerLevel level = this.bot.level();

        for (final InteractionHand hand : InteractionHand.values()) {
            if (hit.getType() == HitResult.Type.BLOCK) {
                final BlockHitResult blockHit = (BlockHitResult) hit;
                final BlockPos pos = blockHit.getBlockPos();
                this.bot.resetLastActionTime();
                if (level.mayInteract(this.bot, pos)) {
                    final InteractionResult result = this.bot.gameMode.useItemOn(
                        this.bot, level, this.bot.getItemInHand(hand), hand, blockHit);
                    if (result instanceof final InteractionResult.Success success) {
                        if (success.swingSource() == InteractionResult.SwingSource.SERVER) {
                            this.bot.swing(hand);
                        }
                        this.itemUseCooldown = 3;
                        return;
                    }
                }
            } else if (hit.getType() == HitResult.Type.ENTITY) {
                final EntityHitResult entityHit = (EntityHitResult) hit;
                final Entity entity = entityHit.getEntity();
                this.bot.resetLastActionTime();
                final Vec3 relative = entityHit.getLocation()
                    .subtract(entity.getX(), entity.getY(), entity.getZ());
                if (entity.interact(this.bot, hand, relative).consumesAction()) {
                    this.itemUseCooldown = 3;
                    return;
                }
                if (this.bot.interactOn(entity, hand, relative).consumesAction()) {
                    this.itemUseCooldown = 3;
                    return;
                }
            }
            if (this.bot.gameMode.useItem(this.bot, level, this.bot.getItemInHand(hand), hand)
                .consumesAction()) {
                this.itemUseCooldown = 3;
                return;
            }
        }
    }

    private void jump() {
        if (this.bot.onGround()) {
            this.bot.jumpFromGround();
        }
    }

    private void drop(final boolean whole) {
        final Inventory inventory = this.bot.getInventory();
        final int slot = inventory.getSelectedSlot();
        final ItemStack stack = inventory.getItem(slot);
        if (stack.isEmpty()) {
            return;
        }
        this.bot.drop(inventory.removeItem(slot, whole ? stack.getCount() : 1), false, true);
    }

    private void swapHands() {
        final ItemStack main = this.bot.getMainHandItem().copy();
        this.bot.setItemInHand(InteractionHand.MAIN_HAND, this.bot.getOffhandItem().copy());
        this.bot.setItemInHand(InteractionHand.OFF_HAND, main);
    }
}
