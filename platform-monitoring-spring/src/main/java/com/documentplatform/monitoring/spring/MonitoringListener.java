package com.documentplatform.monitoring.spring;

import com.documentplatform.monitoring.MonitoringEvent;

/**
 * An app bean told of every state change of a check (became down, became having problems, recovered), for example to
 * raise a notification. The continuous-failure and summary rules are the notifications module's.
 */
@FunctionalInterface
public interface MonitoringListener {
    void changed(MonitoringEvent event);
}
