package io.papermc.paper.lab.cplay;

import java.util.List;
import java.util.UUID;

public interface CPlayCaptureStream {
    UUID getAssetId();
    CPlayBlockRegion getRegion();
    boolean isClosed();
    void close();
    void writeTickEvents(List<CPlaySignalEvent> events);
}
