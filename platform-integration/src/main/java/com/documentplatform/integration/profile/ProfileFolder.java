package com.documentplatform.integration.profile;

import com.documentplatform.integration.IntegrationCode;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The folder where the installation's IT puts the profile files that APIs' owners publish
 * ({@code platform-integration-profile/1}, one {@code .json} file each). platform-core ships none. Each file is read
 * and checked when asked for ({@link ProfileLoader}), so a replaced file is picked up without a restart. Only plain
 * file names directly in the folder are read: no sub-folders, no {@code ..}.
 */
public final class ProfileFolder {

    private static final Pattern FILE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,99}\\.json");

    private final Path folder;

    public ProfileFolder(Path folder) {
        this.folder = Objects.requireNonNull(folder, "folder").toAbsolutePath().normalize();
    }

    public Path folder() {
        return folder;
    }

    /** The profile files in the folder ({@code *.json}), sorted; empty when the folder does not exist. */
    public List<String> files() {
        List<String> out = new ArrayList<>();
        if (!Files.isDirectory(folder)) return out;
        try (DirectoryStream<Path> s = Files.newDirectoryStream(folder, "*.json")) {
            for (Path p : s) {
                String name = p.getFileName().toString();
                if (FILE.matcher(name).matches() && Files.isRegularFile(p)) out.add(name);
            }
        } catch (IOException | SecurityException e) {
            return List.of();
        }
        out.sort(String::compareTo);
        return out;
    }

    /**
     * Reads and checks one profile file.
     *
     * @throws ProfileLoader.Refused when the name is not a plain profile file name, the file cannot be read, or the
     *                               profile has problems
     */
    public IntegrationProfile load(String fileName) {
        ProfileValidator.Result r = check(fileName);
        if (!r.valid()) throw new ProfileLoader.Refused(r.problems());
        return r.profile();
    }

    /** The validator's result for one file, without throwing for its problems. */
    public ProfileValidator.Result check(String fileName) {
        if (fileName == null || !FILE.matcher(fileName).matches()) {
            return new ProfileValidator.Result(null, List.of(new ProfileProblem(IntegrationCode.INT_PROFILE_UNREADABLE,
                    "", "not a profile file name in the profile folder")));
        }
        return ProfileLoader.check(folder.resolve(fileName));
    }

    @Override
    public String toString() {
        return "ProfileFolder[" + files().size() + " files]";
    }
}
