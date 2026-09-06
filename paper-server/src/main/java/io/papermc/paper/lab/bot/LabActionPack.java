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
 * Bot actions: simulating a player's key presses.
 *
 * <p>Call order and timings follow Carpet's {@code EntityPlayerActionPack} — which is
 * exactly what the server does for a live client's packets:
 * {@code gameMode.handleBlockBreakAction}, {@code gameMode.useItemOn},
 * {@code entity.interact}, {@code player.attack}.
 *
 * <p>Three places where deviating from Carpet breaks behaviour, so they are reproduced
 * literally:
 * <ol>
 *   <li>aiming covers blocks <b>and</b> entities ({@link LabTracer}); the stock
 *       {@code Entity.pick} sees blocks only, which makes the bot break blocks but never
 *       hit entities;</li>
 *   <li>at {@code interval 1} the hold is released <b>in the same tick</b>, before
 *       execution (see {@link LabAction.Rhythm#releaseBeforeExecute()});</li>
 *   <li>a successful {@code use} this tick cancels {@code attack}, while a successful
 *       attack with a failed use gives use another try — as in
 *       {@code MinecraftClient.handleInputEvents}.</li>
 * </ol>
 */
public final class LabActionPack {

    private final LabBot bot;
    /** EnumMap iterates by ordinal: USE before ATTACK, like Carpet's TreeMap. */
    private final Map<LabAction, LabAction.Running> running = new EnumMap<>(LabAction.class);

    /**
     * Forward/back and sideways movement, as a fraction of full speed.
     *
     * <p>The values go into {@code zza} and {@code xxa} — the same fields the movement
     * packet handler writes for a live player. The ordinary entity tick does the rest, so
     * the bot walks, swims and steers vehicles exactly as a player does.
     */
    private float forward;
    private float strafing;

    /** The block the bot is currently mining, and the progress accumulated. */
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
     * Movement: {@code 1} forward, {@code -1} back.
     *
     * <p>The value holds until changed: this is a held key, not a step.
     */
    public void setForward(final float value) {
        this.forward = value;
    }

    /** Strafing: {@code 1} left, {@code -1} right. Same as for a live player. */
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
     * Mount the nearest vehicle within three blocks.
     *
     * <p>{@code onlyRideables} restricts to boats, minecarts and horses; otherwise any
     * mountable entity qualifies. Horses are mounted via {@code mobInteract}: for them
     * mounting goes through interaction rather than {@code startRiding}.
     *
     * @return {@code true} if something to ride was found
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

        // null means the action did not run this tick; true/false is its result.
        final Map<LabAction, Boolean> attempts = new EnumMap<>(LabAction.class);

        for (final Map.Entry<LabAction, LabAction.Running> entry : this.running.entrySet()) {
            final LabAction action = entry.getKey();
            final LabAction.Running state = entry.getValue();

            // A successful use cancels the attack in the same tick.
            final boolean useSucceeded = Boolean.TRUE.equals(attempts.get(LabAction.USE));
            if (!(useSucceeded && action == LabAction.ATTACK)) {
                final Boolean result = this.tickAction(action, state);
                if (result != null) {
                    attempts.put(action, result);
                }
            }

            // The attack landed but use failed this tick — give use another try.
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
     * Copies movement into the entity's input fields.
     *
     * <p>Done every tick rather than once per command: the engine zeroes {@code zza} and
     * {@code xxa} as the tick proceeds, and without rewriting them the bot takes one step
     * and stops.
     *
     * <p>Sneaking slows it down the same way it slows a live player.
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
            // Release the hold before executing — otherwise itemUseCooldown never resets
            // and interval 1 ends up slower than interval 2.
            this.inactiveTick(action);
        }
        final boolean result = this.execute(action, state);
        state.executed();
        return result;
    }

    /** Release the hold: abort mining and stop using the item. */
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
        this.bot.level().destroyBlockProgress(this.bot.getId(), this.currentBlock, -1);
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
     * Reach as a player has it: blocks and entities use different distances, so we trace
     * by the larger one and leave the applicability decision to the engine calls.
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
                // Hits are not spammed while held: a live player waits for the cooldown too.
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

    /** @return true if a block was broken this tick */
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
            level.destroyBlockProgress(this.bot.getId(), pos, (int) (this.blockDamage * 10.0F));
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
