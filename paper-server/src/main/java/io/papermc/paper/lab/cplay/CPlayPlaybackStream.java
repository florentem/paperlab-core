package io.papermc.paper.lab.cplay;

import java.util.List;
import java.util.UUID;

public interface CPlayPlaybackStream {
    UUID getAssetId();
    CPlayBlockRegion getRegion();
    boolean isClosed();
    void close();
    List<CPlaySignalEvent> readNextTickEvents();
}
