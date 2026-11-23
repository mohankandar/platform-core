package com.documentplatform.integration.scan;

import com.documentplatform.integration.IntegrationCode;
import com.documentplatform.integration.intake.IntakeFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

/**
 * The scan step of intake: the installation's scanner (or none: scanning off) and where infected files are told.
 * Used by API polling and the watched folder before anything is handed to the app.
 */
public final class Scanning {

    /** No scanner: every file is let through unscanned. */
    public static final Scanning OFF = new Scanning(null, ScanEvents.NONE);

    private static final System.Logger LOG = System.getLogger(Scanning.class.getName());

    private final FileScanner scanner;
    private final ScanEvents events;

    /** @param scanner the scanner, or null for none (off) */
    public Scanning(FileScanner scanner, ScanEvents events) {
        this.scanner = scanner;
        this.events = Objects.requireNonNull(events, "events");
    }

    public boolean on() {
        return scanner != null;
    }

    /** The scanner, or null when off. */
    public FileScanner scanner() {
        return scanner;
    }

    public ScanEvents events() {
        return events;
    }

    /** Scans one file: CLEAN when off. A file that cannot be opened is SCAN_FAILED (held). Never throws. */
    public ScanResult scan(IntakeFile.Content content, long size) {
        if (scanner == null) return ScanResult.clean();
        try (InputStream in = content.open()) {
            ScanResult r;
            try {
                r = scanner.scan(in, size);
            } catch (RuntimeException e) {
                r = ScanResult.failed(IntegrationCode.INT_SCAN_FAILED);
            }
            if (r.verdict() == ScanResult.Verdict.SCAN_FAILED) {
                LOG.log(System.Logger.Level.WARNING, "Virus scan ({0}) failed: {1}", scanner.kind(), r.code());
            }
            return r;
        } catch (IOException e) {
            return ScanResult.failed(IntegrationCode.INT_SCAN_FAILED);
        }
    }

    /** The scanner's health, or null when off. */
    public FileScanner.Health health() {
        if (scanner == null) return null;
        try {
            return scanner.health();
        } catch (RuntimeException e) {
            return new FileScanner.Health(false, IntegrationCode.INT_SCAN_FAILED);
        }
    }
}
