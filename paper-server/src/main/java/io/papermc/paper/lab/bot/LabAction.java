package io.papermc.paper.lab.bot;

import java.util.Locale;

/**
 * Действие бота, повторяющее нажатие клавиши игроком.
 *
 * <p><b>Порядок объявления значим.</b> {@link LabActionPack} обходит действия
 * в порядке ordinal через {@code EnumMap}, и Carpet опирается на то же:
 * {@code USE} обрабатывается раньше {@code ATTACK}, потому что успешное
 * использование в этом тике отменяет атаку.
 */
public enum LabAction {

    /** Правая кнопка: использование предмета, блока или сущности. */
    USE,
    /** Левая кнопка: удар по сущности либо добыча блока под прицелом. */
    ATTACK,
    JUMP,
    /** Выбросить один предмет из выбранного слота. */
    DROP_ITEM,
    /** Выбросить весь стек из выбранного слота. */
    DROP_STACK,
    SWAP_HANDS;

    public String id() {
        return this.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    /**
     * Ритм повторения — модель Carpet {@code Action}.
     *
     * <p>Имена компонентов специально не совпадают с именами фабрик: в record любой
     * метод с именем компонента обязан быть его аксессором.
     *
     * @param limit       сколько раз выполнить; {@code -1} — без ограничения
     * @param periodTicks период в тиках
     * @param hold        удержание кнопки, а не отдельные нажатия
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
         * Нужно ли сбрасывать удержание в том же тике, что и выполнение.
         *
         * <p>Carpet: {@code if (interval == 1 && !isContinuous) inactiveTick(...)} перед
         * {@code execute}. Без этого при {@code interval 1} предмет остаётся «зажатым»,
         * {@code itemUseCooldown} не обнуляется, и вместо одного использования за тик
         * получается одно за четыре — ровно тот симптом, когда {@code interval 1}
         * работает медленнее {@code interval 2}.
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

    /** Состояние повторения одного действия. */
    static final class Running {

        private final Rhythm rhythm;
        private int done;
        /** Тиков до следующего срабатывания. Carpet: {@code next = interval + offset}. */
        private int next;
        boolean finished;

        Running(final Rhythm rhythm) {
            this.rhythm = rhythm;
            this.next = rhythm.periodTicks();
        }

        Rhythm rhythm() {
            return this.rhythm;
        }

        /** @return true, если в этом тике действие надо выполнить */
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

        /** Учесть выполнение и, если задан лимит, завершить действие. */
        void executed() {
            this.done++;
            if (this.rhythm.limit() > 0 && this.done >= this.rhythm.limit()) {
                this.finished = true;
            }
        }
    }
}
