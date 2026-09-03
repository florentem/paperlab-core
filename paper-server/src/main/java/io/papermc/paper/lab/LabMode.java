package io.papermc.paper.lab;

/**
 * Режим работы инструментария Technical Lab.
 *
 * <p>Разделение вмешательства и наблюдения — основной инвариант проекта. Ни один режим
 * не обещает нулевых накладных расходов: это измеряемая величина, а не гарантия.
 */
public enum LabMode {

    /**
     * Подсистемы выключены. Горячие хуки обязаны возвращаться немедленно, без подписок,
     * сканирований и фоновой записи. Не обещает абсолютно нулевого overhead без замера.
     */
    OFF,

    /**
     * Только чтение уже полученных результатов и ограниченные снимки.
     *
     * <p>В этом режиме запрещено: дополнительные вызовы RNG, добавление chunk tickets,
     * загрузка чанков, вызов AI/loot ради получения ответа, подмена {@code SpawnReason},
     * перестановка коллекций движка, создание marker-сущностей и исполнение дополнительных
     * physics/neighbour updates.
     */
    OBSERVE,

    /**
     * Управляемые вмешательства: боты, уничтожающий counter-sink, управление тиками.
     * Прогон в этом режиме не эквивалентен нетронутому survival-прогону и не сравнивается
     * напрямую с вариантами A/B/C.
     */
    CONTROL,

    /**
     * Просмотр записи и восстановление. Восстановление допускается только в отдельной копии
     * мира и не возвращает в прошлое RNG, очереди задач, состояние плагинов и историю.
     */
    REPLAY;

    /**
     * Системное свойство, задающее режим при старте: {@code -Dlab.mode=OBSERVE}.
     */
    public static final String PROPERTY = "lab.mode";

    /**
     * Разбирает значение режима. Нераспознанное или отсутствующее значение даёт {@link #OFF} —
     * инструментарий никогда не включается по умолчанию.
     */
    public static LabMode parse(final String raw) {
        if (raw == null || raw.isBlank()) {
            return OFF;
        }
        try {
            return valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (final IllegalArgumentException ignored) {
            return OFF;
        }
    }

    /**
     * @return true, если режим вообще собирает данные
     */
    public boolean collecting() {
        return this != OFF;
    }

    /**
     * @return true, если режиму разрешено менять состояние мира
     */
    public boolean mayIntervene() {
        return this == CONTROL || this == REPLAY;
    }
}
