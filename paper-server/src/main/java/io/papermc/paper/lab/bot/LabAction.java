package io.papermc.paper.lab.bot;

import java.util.Locale;

/** Действие бота, повторяющее нажатие клавиши игроком. */
public enum LabAction {

    /** Левая кнопка: удар по сущности либо добыча блока под прицелом. */
    ATTACK,
    /** Правая кнопка: использование предмета, блока или сущности. */
    USE,
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
     * Ритм повторения. Повторяет модель Carpet: {@code once} — один раз,
     * {@code continuous} — каждый тик с удержанием, {@code every} — раз в N тиков.
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
        private int countdown;
        boolean finished;

        Running(final Rhythm rhythm) {
            this.rhythm = rhythm;
            this.countdown = rhythm.periodTicks();
        }

        Rhythm rhythm() {
            return this.rhythm;
        }

        /** @return true, если в этом тике действие надо выполнить */
        boolean due() {
            if (this.finished) {
                return false;
            }
            if (--this.countdown > 0) {
                return false;
            }
            this.countdown = this.rhythm.periodTicks();
            return true;
        }

        void executed() {
            this.done++;
            if (this.rhythm.limit() > 0 && this.done >= this.rhythm.limit()) {
                this.finished = true;
            }
        }
    }
}
