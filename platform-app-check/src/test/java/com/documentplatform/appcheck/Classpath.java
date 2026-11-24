package com.documentplatform.appcheck;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** The test's class path: an app's, with Boot's dependency management. */
final class Classpath {

    private Classpath() {
    }

    static List<Path> elements() {
        String cp = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        return Stream.of(cp.split(Pattern.quote(File.pathSeparator))).filter(s -> !s.isBlank()).map(Path::of).toList();
    }

    static List<String> versions(String artifact) {
        Pattern p = Pattern.compile(Pattern.quote(artifact) + "-(\\d.*)\\.jar");
        return elements().stream().map(e -> p.matcher(e.getFileName().toString())).filter(Matcher::matches)
                .map(m -> m.group(1)).distinct().toList();
    }

    static String version(String artifact) {
        List<String> v = versions(artifact);
        if (v.size() != 1) throw new AssertionError(artifact + ": " + v);
        return v.get(0);
    }
}
