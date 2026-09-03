package io.papermc.paper.lab.log;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Логгер HUD. Модель Carpet: игрок подписывается, значение постоянно висит в таб-листе.
 *
 * <p>Отличие от Carpet: у одного игрока может быть <b>несколько</b> подписок на один
 * логгер — по одной на цель. Это нужно, чтобы держать в HUD мобкапы сразу нескольких
 * игроков и ботов, а позже так же несколько счётчиков.
 *
 * <p>Опция хранится строкой вида {@code "[цель] [флаги]"}. «Цель» — первый токен,
 * не являющийся флагом; по ней подписки дедуплицируются, поэтому повторная подписка
 * на ту же цель с другим флагом заменяет прежнюю, а не добавляет вторую строку.
 */
public final class LabLogger {

    /** Токены-модификаторы: не считаются целью при дедупликации. */
    private static final Set<String> FLAGS = Set.of("full");

    private final String name;
    private final List<String> options;
    /** Опция задаётся свободным текстом (ником) и по списку не проверяется. */
    private final boolean freeform;

    /** ник подписчика → упорядоченный набор его опций. */
    private final Map<String, LinkedHashSet<String>> subscribers = new LinkedHashMap<>();

    LabLogger(final String name, final boolean freeform, final String... options) {
        this.name = name;
        this.freeform = freeform;
        this.options = List.of(options);
    }

    public String name() {
        return this.name;
    }

    public List<String> options() {
        return this.options;
    }

    public boolean freeform() {
        return this.freeform;
    }

    public boolean hasSubscribers() {
        return !this.subscribers.isEmpty();
    }

    /** Все опции игрока в порядке подписки. Пустая коллекция — не подписан. */
    public Collection<String> optionsFor(final String playerName) {
        final LinkedHashSet<String> set = this.subscribers.get(playerName);
        return set == null ? List.of() : List.copyOf(set);
    }

    public boolean subscribed(final String playerName) {
        return this.subscribers.containsKey(playerName);
    }

    /**
     * Переключает подписку на конкретную цель.
     *
     * <ul>
     *   <li>цель ещё не подписана → добавить;</li>
     *   <li>подписана с той же строкой → убрать;</li>
     *   <li>подписана с другими флагами → заменить (например включить {@code full}).</li>
     * </ul>
     *
     * @return {@code true}, если после вызова подписка на эту цель есть
     */
    public boolean toggle(final String playerName, final @Nullable String option) {
        final String normalized = option == null ? "" : option.trim();
        final String target = targetOf(normalized);

        final LinkedHashSet<String> set =
            this.subscribers.computeIfAbsent(playerName, k -> new LinkedHashSet<>());

        String existing = null;
        for (final String current : set) {
            if (targetOf(current).equals(target)) {
                existing = current;
                break;
            }
        }

        if (existing != null) {
            set.remove(existing);
            if (existing.equals(normalized)) {
                // Та же самая подписка — это выключение.
                if (set.isEmpty()) {
                    this.subscribers.remove(playerName);
                }
                return false;
            }
        }

        set.add(normalized);
        return true;
    }

    /** Снять все подписки игрока на этот логгер. */
    public boolean unsubscribeAll(final String playerName) {
        return this.subscribers.remove(playerName) != null;
    }

    /** Цель подписки: первый токен, не являющийся флагом. Пустая строка — сам подписчик. */
    static String targetOf(final String option) {
        for (final String token : option.split(" ")) {
            if (!token.isEmpty() && !FLAGS.contains(token.toLowerCase(Locale.ROOT))) {
                return token;
            }
        }
        return "";
    }

    static boolean hasFlag(final String option, final String flag) {
        for (final String token : option.split(" ")) {
            if (token.equalsIgnoreCase(flag)) {
                return true;
            }
        }
        return false;
    }
}
