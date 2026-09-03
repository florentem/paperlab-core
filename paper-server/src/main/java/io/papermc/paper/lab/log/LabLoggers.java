package io.papermc.paper.lab.log;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import org.checkerframework.checker.nullness.qual.Nullable;

/** Реестр логгеров {@code /log}. */
public final class LabLoggers {

    private static final Map<String, LabLogger> LOGGERS = new LinkedHashMap<>();

    public static final LabLogger TPS = register(new LabLogger("tps", false));
    /**
     * Опция — ник игрока или бота, чей локальный кап показывать. Без опции — свой.
     *
     * <p>Опции измерения (overworld/nether/end) здесь бессмысленны: локальный кап
     * привязан к позиции конкретного игрока, а не к миру. Мир берётся из того, где
     * этот игрок находится.
     */
    public static final LabLogger MOBCAPS = register(new LabLogger("mobcaps", true));
    /**
     * Опция — цвет шерсти. Подписок может быть несколько: в Carpet приходилось
     * перечислять цвета через запятую в одной опции, здесь каждый цвет — своя строка,
     * которую можно включать и выключать независимо.
     */
    public static final LabLogger COUNTER = register(new LabLogger("counter", true));
    /**
     * Трасса спавна: где останавливаются попытки естественного спавна в мире игрока.
     * Опция — категория ({@code monster} по умолчанию).
     */
    public static final LabLogger SPAWN = register(new LabLogger("spawn", true));

    private LabLoggers() {
    }

    private static LabLogger register(final LabLogger logger) {
        LOGGERS.put(logger.name(), logger);
        return logger;
    }

    public static @Nullable LabLogger get(final String name) {
        return LOGGERS.get(name);
    }

    public static Collection<LabLogger> all() {
        return LOGGERS.values();
    }

    public static Collection<String> names() {
        return LOGGERS.keySet();
    }

    /** Снять все подписки игрока. */
    public static int unsubscribeAll(final String playerName) {
        int count = 0;
        for (final LabLogger logger : LOGGERS.values()) {
            if (logger.unsubscribeAll(playerName)) {
                count++;
            }
        }
        return count;
    }

    public static boolean anySubscribers() {
        for (final LabLogger logger : LOGGERS.values()) {
            if (logger.hasSubscribers()) {
                return true;
            }
        }
        return false;
    }
}
