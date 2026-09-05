package io.papermc.paper.lab.cplay;

public enum CPlayTickPhase {
    IMMEDIATE(0),
    BLOCK_EVENTS(1);

    private final int index;

    CPlayTickPhase(int index) {
        this.index = index;
    }

    public int getIndex() {
        return index;
    }

    public boolean isBefore(CPlayTickPhase other) {
        return this.index < other.index;
    }

    public static CPlayTickPhase fromIndex(int index) {
        return (index == 0) ? IMMEDIATE : BLOCK_EVENTS;
    }
}
