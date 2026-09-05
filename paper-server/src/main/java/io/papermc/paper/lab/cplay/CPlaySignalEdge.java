package io.papermc.paper.lab.cplay;

public enum CPlaySignalEdge {
    RISING_EDGE(0),
    FALLING_EDGE(1);

    private final int index;

    CPlaySignalEdge(int index) {
        this.index = index;
    }

    public int getIndex() {
        return index;
    }

    public static CPlaySignalEdge fromIndex(int index) {
        return (index == 0) ? RISING_EDGE : FALLING_EDGE;
    }
}
