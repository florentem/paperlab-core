package io.papermc.paper.lab;

/**
 * Точка входа инструментария Technical Lab.
 *
 * <p>Живёт в {@code paper-server/src/main/java}, а не в {@code src/minecraft}: так вся логика
 * остаётся обычным кодом Paper и не участвует в патч-системе. В {@code src/minecraft} должны
 * попадать только минимальные вызовы-хуки в точках, где результат уже получен движком.
 *
 * <p>Все горячие проверки идут через {@link #collecting()}: одно чтение {@code volatile}
 * поля, чтобы в режиме {@link LabMode#OFF} стоимость хука была минимальной. Фактическая
 * стоимость — предмет замера, а не обещание.
 */
public final class Lab {

    private static volatile LabMode mode = LabMode.parse(System.getProperty(LabMode.PROPERTY));

    private Lab() {
    }

    public static LabMode mode() {
        return mode;
    }

    /**
     * Быстрая проверка для горячих путей.
     */
    public static boolean collecting() {
        return mode != LabMode.OFF;
    }

    /**
     * Смена режима во время работы сервера. Переход обязан инвалидировать все накопленные
     * снимки: числа, снятые в другом режиме, несопоставимы.
     */
    public static void setMode(final LabMode next) {
        if (next == null) {
            throw new IllegalArgumentException("mode");
        }
        final LabMode previous = mode;
        if (previous == next) {
            return;
        }
        mode = next;
        SpawnPhaseTracker.invalidate();
    }

    /**
     * Обёртка для телеметрии: сбой инструментария не должен подавлять исключения
     * оригинального игрового кода, но и не должен ломать игру.
     *
     * @return true, если блок выполнился без исключения
     */
    public static boolean guarded(final Runnable telemetry) {
        try {
            telemetry.run();
            return true;
        } catch (final Throwable t) {
            // Ошибка телеметрии отключает телеметрию, а не игру.
            mode = LabMode.OFF;
            org.apache.logging.log4j.LogManager.getLogger("PaperLab")
                .error("Инструментарий отключён из-за внутренней ошибки; игровая логика не затронута", t);
            return false;
        }
    }
}
