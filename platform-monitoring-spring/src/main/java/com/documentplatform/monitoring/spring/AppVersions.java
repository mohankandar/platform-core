package com.documentplatform.monitoring.spring;

import java.util.Map;
import java.util.function.Supplier;

/** The app's own versions for the diagnostic bundle (name to version), as an optional bean. */
@FunctionalInterface
public interface AppVersions extends Supplier<Map<String, String>> {
}
