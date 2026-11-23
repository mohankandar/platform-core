package com.documentplatform.outbound.spring;

import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Matches when any {@code platform.outbound.*} property is set, so a proxy or private CA set without
 * {@code installation} is refused with the missing setting named, never silently ignored. An app without any
 * outbound setting gets no outbound beans.
 */
final class OnOutboundSettings implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        return Binder.get(context.getEnvironment())
                .bind(OutboundProperties.PREFIX, Bindable.mapOf(String.class, Object.class))
                .map(m -> !m.isEmpty()).orElse(false);
    }
}
