package com.documentplatform.integration.config;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * API polling (kind {@code API_POLL}): how the source is read. The calls are configured like outbound steps, with the
 * same templates, sign-in, safety, limits, rules and pinning (the integration's endpoint, sign-in and limits):
 * <ul>
 *   <li>a <b>list</b> step, read page by page ({@link Paging});</li>
 *   <li>a <b>download</b> step per item (its answer is the document, streamed, with the size limit and SHA-256);</li>
 *   <li>an optional <b>acknowledge</b> step per item, made only after the app took it.</li>
 * </ul>
 * The download and acknowledge templates may use {@code {item.id}} and {@code {item.<name>}} (the values read from
 * the item) and {@code {correlationId}}; the list step only {@code {correlationId}}. The paging parameters are added
 * to the list call's query string by the engine (encoded), never by a template.
 *
 * @param list          the list call (run once per page; no body, or a JSON body)
 * @param itemsPointer  where the list answer holds the array of items ({@code /items}; {@code ""} for an answer that
 *                      is the array itself)
 * @param idPointer     where an item holds its stable source item ID, relative to the item ({@code /id})
 * @param values        value name → pointer relative to the item, read and handed to the app ({@code fileName →
 *                      /file/name}); a missing value is left out
 * @param fileNameValue the read value holding the document's file name, or null (the item ID is used)
 * @param paging        how the next page is asked for
 * @param download      the per-item download call (its 2xx answer body is the document)
 * @param acknowledge   the per-item acknowledge call, or null when the source has none
 */
public record PollSource(CallStep list, String itemsPointer, String idPointer, Map<String, String> values,
                         String fileNameValue, Paging paging, CallStep download, CallStep acknowledge) {

    /** The step names used in results and logs. */
    public static final String LIST = "list";
    public static final String DOWNLOAD = "download";
    public static final String ACKNOWLEDGE = "acknowledge";

    public PollSource {
        Objects.requireNonNull(list, "list");
        Objects.requireNonNull(itemsPointer, "itemsPointer");
        Objects.requireNonNull(idPointer, "idPointer");
        Objects.requireNonNull(paging, "paging");
        Objects.requireNonNull(download, "download");
        values = values == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    /** A source with these calls, items at {@code itemsPointer}, IDs at {@code idPointer}; no values, no acknowledge. */
    public static PollSource of(CallStep list, String itemsPointer, String idPointer, Paging paging,
                                CallStep download) {
        return new PollSource(list, itemsPointer, idPointer, Map.of(), null, paging, download, null);
    }

    public PollSource reading(String value, String pointer) {
        Map<String, String> v = new LinkedHashMap<>(values);
        v.put(value, pointer);
        return new PollSource(list, itemsPointer, idPointer, v, fileNameValue, paging, download, acknowledge);
    }

    public PollSource withFileNameValue(String value) {
        return new PollSource(list, itemsPointer, idPointer, values, value, paging, download, acknowledge);
    }

    public PollSource withAcknowledge(CallStep step) {
        return new PollSource(list, itemsPointer, idPointer, values, fileNameValue, paging, download, step);
    }

    public PollSource withPaging(Paging p) {
        return new PollSource(list, itemsPointer, idPointer, values, fileNameValue, p, download, acknowledge);
    }

    /**
     * How the list is paged. The engine adds the parameters to the list call's query string.
     *
     * @param mode          NONE (one page), CURSOR, NEXT_LINK or PAGE_NUMBER
     * @param pointer       CURSOR: where the answer holds the next cursor; NEXT_LINK: where it holds the next page's
     *                      address, or null to take the {@code Link: <…>; rel="next"} header
     * @param param         CURSOR: the query parameter for the cursor; PAGE_NUMBER: for the page number
     * @param sizeParam     the query parameter for the page size, or null to send none
     * @param pageSize      items asked for per page (1–1000)
     * @param firstPage     PAGE_NUMBER: the first page's number (0 or 1)
     * @param maxPagesPerPass most pages one pass reads (1–1000)
     * @param resume        CONTINUE: the next pass starts where this one stopped; FROM_START: every pass starts at the
     *                      first page (for sources whose items leave the list once acknowledged)
     */
    public record Paging(Mode mode, String pointer, String param, String sizeParam, int pageSize, int firstPage,
                         int maxPagesPerPass, Resume resume) {

        public enum Mode { NONE, CURSOR, NEXT_LINK, PAGE_NUMBER }

        public enum Resume { CONTINUE, FROM_START }

        public static final int MAX_PAGE_SIZE = 1000;
        public static final int MAX_PAGES = 1000;

        public Paging {
            Objects.requireNonNull(mode, "mode");
            resume = resume == null ? Resume.CONTINUE : resume;
        }

        /** One page, nothing added to the list call. */
        public static Paging none() {
            return new Paging(Mode.NONE, null, null, null, 100, 0, 1, Resume.CONTINUE);
        }

        /** The answer holds the next cursor at {@code pointer}; it goes back as query parameter {@code param}. */
        public static Paging cursor(String pointer, String param, String sizeParam, int pageSize) {
            return new Paging(Mode.CURSOR, pointer, param, sizeParam, pageSize, 0, 10, Resume.CONTINUE);
        }

        /** The answer holds the next page's address at {@code pointer} (null: the Link header). */
        public static Paging nextLink(String pointer) {
            return new Paging(Mode.NEXT_LINK, pointer, null, null, 100, 0, 10, Resume.CONTINUE);
        }

        /** Page numbers from {@code firstPage}, {@code pageSize} items each. */
        public static Paging pageNumber(String param, String sizeParam, int pageSize, int firstPage) {
            return new Paging(Mode.PAGE_NUMBER, null, param, sizeParam, pageSize, firstPage, 10, Resume.CONTINUE);
        }

        public Paging withMaxPagesPerPass(int n) {
            return new Paging(mode, pointer, param, sizeParam, pageSize, firstPage, n, resume);
        }

        public Paging withResume(Resume r) {
            return new Paging(mode, pointer, param, sizeParam, pageSize, firstPage, maxPagesPerPass, r);
        }
    }
}
