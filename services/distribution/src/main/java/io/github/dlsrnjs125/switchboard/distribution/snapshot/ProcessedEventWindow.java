package io.github.dlsrnjs125.switchboard.distribution.snapshot;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class ProcessedEventWindow {
    private static final int MAX_ENTRIES = 10_000;
    private final Map<UUID, Boolean> processed = new LinkedHashMap<>();

    public synchronized boolean contains(UUID eventId) {
        return processed.containsKey(eventId);
    }

    public synchronized void add(UUID eventId) {
        processed.put(eventId, Boolean.TRUE);
        while (processed.size() > MAX_ENTRIES) {
            processed.remove(processed.keySet().iterator().next());
        }
    }
}
