package io.papermc.paper.lab.rules;

/**
 * Rule values that have to be read from the core.
 *
 * <p><b>Rules live in the plugin</b> — the registry, parsing, permissions, default storage
 * and the {@code /carpet} command are all there. Only what is read where a plugin cannot
 * reach ends up here: engine command code, an entity constructor, a hot path.
 *
 * <p>The fields are deliberately plain and {@code volatile}: read often, written rarely and
 * only from the main thread. There is no logic here and there must not be — otherwise a
 * rule would be smeared across plugin and core, and the next change would mean rebuilding
 * the jar.
 */
public final class LabRuleState {

    /**
     * Whether {@code /fill}, {@code /setblock} and {@code /clone} cause neighbour updates.
     *
     * <p>{@code false} places blocks quietly: observers do not fire, torches and repeaters
     * do not pop off, redstone does not start. Needed to assemble a contraption from a
     * template and switch it on once, instead of watching it start itself mid-fill.
     */
    public static volatile boolean fillUpdates = true;

    /**
     * Suffix appended to bot names, for example {@code _bot}.
     *
     * <p>The point: a bot named after a live player takes their UUID, and that player can
     * no longer log in. A suffix makes the names diverge while the skin is still fetched
     * for the name <b>without</b> it — the bot looks like the intended player and blocks
     * nobody.
     */
    public static volatile String fakePlayerNameSuffix = "";

    /**
     * Whether our additions to vanilla {@code /tick} ({@code toggle}, {@code warp}) apply.
     *
     * <p>The name follows Carpet. Turning it off does not remove the nodes from the tree —
     * Brigadier builds it once at startup — but makes them unavailable via {@code requires}.
     */
    public static volatile boolean tickCommandCarpetfied = false;

    /**
     * Whether the {@code /player} command (bots) is available.
     *
     * <p>Off by default: without the plugin the core must not change the set of available
     * commands. The plugin sets it to {@code true} when it enables.
     */
    public static volatile boolean playerCommandEnabled = false;

    /**
     * Sets the TNT random explosion range to a fixed value.
     * -1.0 for vanilla random behavior.
     */
    public static volatile double tntRandomRange = -1.0D;

    /**
     * If true, item entity drops have fixed zero momentum and centered position.
     */
    public static volatile boolean hardcodeItemDrops = false;

    /**
     * Whether block updates, neighbour updates, and shape updates occur.
     * When false, all neighbour updates and physics updates are suppressed.
     */
    public static volatile boolean blockUpdates = true;

    private LabRuleState() {
    }
}
