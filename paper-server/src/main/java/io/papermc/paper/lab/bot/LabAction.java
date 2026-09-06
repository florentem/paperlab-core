package io.papermc.paper.lab.bot;

import java.util.Locale;

/**
 * A bot action mirroring a player's key press.
 *
 * <p><b>Declaration order is significant.</b> {@link LabActionPack} iterates actions in
 * ordinal order through an {@code EnumMap}, and Carpet relies on the same thing:
 * {@code USE} is handled before {@code ATTACK}, because a successful use cancels the
 * attack in that tick.
 */
public enum LabAction {

    /** Right button: using an item, a block or an entity. */
    USE,
    /** Left button: hitting an entity or mining the block under the crosshair. */
    ATTACK,
    JUMP,
    /** Drop one item from the selected slot. */
    DROP_ITEM,
    /** Drop the whole stack from the selected slot. */
    DROP_STACK,
    SWAP_HANDS;

    public String id() {
        return this.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    /**
     * Repetition rhythm — Carpet's {@code Action} model.
     *
     * <p>The component names deliberately differ from the factory names: in a record, any
     * method named after a component must be its accessor.
     *
     * @param limit       how many times to run; {@code -1} means unlimited
     * @param periodTicks period in ticks
     * @param hold        hold the button rather than press it repeatedly
     */
    public record Rhythm(int limit, int periodTicks, boolean hold) {

        public static Rhythm once() {
            return new Rhythm(1, 1, false);
        }

        public static Rhythm continuous() {
            return new Rhythm(-1, 1, true);
        }

        public static Rhythm every(final int ticks) {
            return new Rhythm(-1, Math.max(1, ticks), false);
        }

        /**
         * Whether the hold must be released in the same tick as the execution.
         *
         * <p>Carpet: {@code if (interval == 1 && !isContinuous) inactiveTick(...)} before
         * {@code execute}. Without it, at {@code interval 1} the item stays held,
         * {@code itemUseCooldown} never resets, and instead of one use per tick you get one
         * per four — exactly the symptom where {@code interval 1} is slower than
         * {@code interval 2}.
         */
        public boolean releaseBeforeExecute() {
            return this.periodTicks == 1 && !this.hold;
        }

        public String describe() {
            if (this.limit == 1) {
                return "once";
            }
            return this.hold ? "continuous" : "interval " + this.periodTicks;
        }
    }

    /** Repetition state of a single action. */
    static final class Running {

        private final Rhythm rhythm;
        private int done;
        /** Ticks until the next firing. Carpet: {@code next = interval + offset}. */
        private int next;
        boolean finished;

        Running(final Rhythm rhythm) {
            this.rhythm = rhythm;
            this.next = rhythm.periodTicks();
        }

        Rhythm rhythm() {
            return this.rhythm;
        }

        /** @return true if the action must run this tick */
        boolean due() {
            if (this.finished) {
                return false;
            }
            this.next--;
            if (this.next > 0) {
                return false;
            }
            this.next = this.rhythm.periodTicks();
            return true;
        }

        /** Account for a run and, if a limit is set, finish the action. */
        void executed() {
            this.done++;
            if (this.rhythm.limit() > 0 && this.done >= this.rhythm.limit()) {
                this.finished = true;
            }
        }
    }
}
