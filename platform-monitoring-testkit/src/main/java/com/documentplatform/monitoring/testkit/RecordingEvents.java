package com.documentplatform.monitoring.testkit;

import com.documentplatform.monitoring.MonitoringEvent;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** Records the monitoring events a test's DependencyStatus sends. */
public final class RecordingEvents implements Consumer<MonitoringEvent> {

    private final List<MonitoringEvent> events = new CopyOnWriteArrayList<>();

    @Override
    public void accept(MonitoringEvent e) {
        events.add(e);
    }

    public List<MonitoringEvent> events() {
        return List.copyOf(events);
    }

    /** "KIND:check" for each event, in order. */
    public List<String> summary() {
        return events.stream().map(e -> e.kind() + ":" + e.now().name()).toList();
    }

    public void clear() {
        events.clear();
    }
}
