package com.documentplatform.integration.scan;

import com.documentplatform.integration.IntegrationCode;

import java.io.InputStream;

/**
 * The virus scanner the customer provides (requirement 11, optional). When one is configured, every incoming file,
 * from polling or a watched folder, is scanned <b>before the app sees it</b>: an infected file is quarantined and never
 * handed over (and {@link ScanEvents} tells the app, for its IT alert); a file that could not be scanned is held and
 * scanned again at the next pass, never let through. When none is configured, scanning is off.
 *
 * <p>Built: {@link ClamdScanner} (clamd {@code INSTREAM}) and {@link IcapScanner} (ICAP {@code RESPMOD}), both over the
 * outbound safety rules (an internal address is allowed on premises). A scanner never throws for a scan problem: it
 * answers {@link ScanResult.Verdict#SCAN_FAILED} with a code.
 */
public interface FileScanner {

    /** What the scanner's health check found. */
    record Health(boolean up, IntegrationCode code) {
    }

    /**
     * Scans one file's content (read to its end here; the caller closes it).
     *
     * @param size the content's size in bytes
     */
    ScanResult scan(InputStream content, long size);

    /** Whether the scanner answers (clamd {@code PING}, ICAP {@code OPTIONS}). */
    Health health();

    /** For logs and health: {@code clamd} or {@code icap}. */
    String kind();
}
