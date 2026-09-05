package io.papermc.paper.lab.cplay;

import net.minecraft.core.BlockPos;

public class CPlaySignalEvent implements Comparable<CPlaySignalEvent> {
    private final CPlayTickPhase phase;
    private final int microtick;
    private final int index;
    private final CPlaySignalEdge edge;
    private final BlockPos pos;
    private final boolean shadow;

    public CPlaySignalEvent(CPlayTickPhase phase, int microtick, int index, CPlaySignalEdge edge, BlockPos pos, boolean shadow) {
        this.phase = phase;
        this.microtick = microtick;
        this.index = index;
        this.edge = edge;
        this.pos = pos;
        this.shadow = shadow;
    }

    public CPlayTickPhase getPhase() { return phase; }
    public int getMicrotick() { return microtick; }
    public int getIndex() { return index; }
    public CPlaySignalEdge getEdge() { return edge; }
    public BlockPos getPos() { return pos; }
    public boolean isShadow() { return shadow; }

    @Override
    public int compareTo(CPlaySignalEvent other) {
        if (this.phase != other.phase) {
            return this.phase.getIndex() - other.phase.getIndex();
        }
        if (this.microtick != other.microtick) {
            return Integer.compare(this.microtick, other.microtick);
        }
        return Integer.compare(this.index, other.index);
    }
}
