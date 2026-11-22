package com.documentplatform.integration.config;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Where a folder export writes (kind {@code FOLDER_EXPORT}): the target folder and how the package folder and the
 * document files are named. Checked when saved ({@link ConfigCheck}); the folder's own rights are the service
 * account's (no sign-in bundle).
 *
 * @param folder             the target folder: an absolute local path ({@code D:\exports}, {@code /srv/exports}) or a
 *                           network share path ({@code \\fileserver\exports\in}); no {@code .} or {@code ..} parts
 * @param folderNameTemplate the package folder's name: one path segment of literal text and fixed placeholders
 *                           ({@code {package.id}}, {@code {value.<name>}}, {@code {field:<key>}} not sensitive),
 *                           each value percent-encoded as in a path segment
 * @param fileNameTemplate   each document's file name, or null for the document's own file name made safe for
 *                           Windows and Unix file systems; a template may also use {@code {doc.sourceDocumentId}},
 *                           {@code {doc.fileName}} and the other document placeholders
 */
public record FolderTarget(String folder, String folderNameTemplate, String fileNameTemplate) {

    public static final String DEFAULT_FOLDER_NAME = "{package.id}";
    /** The suffix of a package folder while it is written. A final name never ends with it. */
    public static final String PARTIAL_SUFFIX = ".partial";
    public static final String MANIFEST_FILE = "manifest.json";
    public static final int MAX_NAME = 200;

    private static final Pattern DRIVE = Pattern.compile("[A-Za-z]:[\\\\/].*");
    private static final Pattern UNC = Pattern.compile("\\\\\\\\[^\\\\/]+[\\\\/][^\\\\/]+([\\\\/].*)?");
    private static final Set<String> RESERVED = Set.of("con", "prn", "aux", "nul", "com1", "com2", "com3", "com4",
            "com5", "com6", "com7", "com8", "com9", "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8",
            "lpt9");

    public FolderTarget {
        Objects.requireNonNull(folder, "folder");
        folderNameTemplate = folderNameTemplate == null ? DEFAULT_FOLDER_NAME : folderNameTemplate;
    }

    /** The package folder named after the package ID, each document under its own (safe) name. */
    public static FolderTarget of(String folder) {
        return new FolderTarget(folder, DEFAULT_FOLDER_NAME, null);
    }

    public FolderTarget withFolderName(String template) {
        return new FolderTarget(folder, template, fileNameTemplate);
    }

    public FolderTarget withFileName(String template) {
        return new FolderTarget(folder, folderNameTemplate, template);
    }

    /** Whether the folder is a network share path ({@code \\server\share\...}). */
    public boolean share() {
        return folder.startsWith("\\\\");
    }

    /**
     * The path rules: absolute (a drive path, a share path or a Unix path), at most 1000 characters, no control
     * characters, no {@code * ? " < > |}, no {@code .} or {@code ..} part.
     */
    public static boolean validFolder(String f) {
        if (f == null || f.isEmpty() || f.length() > 1000) return false;
        if (f.chars().anyMatch(c -> c < 0x20 || c == 0x7f || "*?\"<>|".indexOf(c) >= 0)) return false;
        boolean absolute = DRIVE.matcher(f).matches() || UNC.matcher(f).matches()
                || f.startsWith("/") && !f.startsWith("//");
        if (!absolute) return false;
        // No ':' after a drive letter (alternate data streams), none at all in share or Unix paths.
        String rest = DRIVE.matcher(f).matches() ? f.substring(2) : f;
        if (rest.contains(":")) return false;
        for (String part : rest.split("[\\\\/]")) {
            if (part.equals(".") || part.equals("..")) return false;
        }
        return true;
    }

    /**
     * Whether a rendered name is one safe path segment on Windows and Unix: 1–{@value #MAX_NAME} characters, no
     * separator, control or {@code : * ? " < > |} characters, not {@code .}/{@code ..}, not ending with a dot or a
     * space, not a Windows device name, not ending with {@value #PARTIAL_SUFFIX}.
     */
    public static boolean validName(String n) {
        if (n == null || n.isEmpty() || n.length() > MAX_NAME) return false;
        if (n.chars().anyMatch(c -> c < 0x20 || c == 0x7f || "/\\:*?\"<>|".indexOf(c) >= 0)) return false;
        if (n.equals(".") || n.equals("..") || n.endsWith(".") || n.endsWith(" ") || n.startsWith(" ")) return false;
        String base = n.contains(".") ? n.substring(0, n.indexOf('.')) : n;
        if (RESERVED.contains(base.toLowerCase(Locale.ROOT))) return false;
        return !n.toLowerCase(Locale.ROOT).endsWith(PARTIAL_SUFFIX);
    }

    /**
     * A document's own file name made safe: characters not allowed on Windows or Unix become {@code _}, leading and
     * trailing dots and spaces go, a device name gets a leading {@code _}, an empty result is {@code document}, and
     * the name is cut to {@value #MAX_NAME} characters keeping its extension.
     */
    public static String safeName(String name) {
        StringBuilder b = new StringBuilder();
        name.codePoints().forEach(c -> b.appendCodePoint(c < 0x20 || c == 0x7f || "/\\:*?\"<>|".indexOf(c) >= 0
                ? '_' : c));
        String s = b.toString().strip();
        while (s.startsWith(".")) s = s.substring(1);
        while (s.endsWith(".") || s.endsWith(" ")) s = s.substring(0, s.length() - 1);
        if (s.isEmpty()) s = "document";
        String base = s.contains(".") ? s.substring(0, s.indexOf('.')) : s;
        if (RESERVED.contains(base.toLowerCase(Locale.ROOT))) s = "_" + s;
        if (s.toLowerCase(Locale.ROOT).endsWith(PARTIAL_SUFFIX)) s = s + "_";
        if (s.length() > MAX_NAME) {
            int dot = s.lastIndexOf('.');
            String ext = dot > 0 && s.length() - dot <= 20 ? s.substring(dot) : "";
            s = s.substring(0, MAX_NAME - ext.length()).strip() + ext;
        }
        return s;
    }

    @Override
    public String toString() {
        return "FolderTarget[" + (share() ? "share" : "local") + ", " + folderNameTemplate + "]";
    }
}
