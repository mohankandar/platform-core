package com.documentplatform.fields.values;

import com.documentplatform.crypto.CryptoException;
import com.documentplatform.crypto.CustomerId;
import com.documentplatform.crypto.KeyService;
import com.documentplatform.crypto.envelope.RecordCipher;
import com.documentplatform.crypto.fingerprint.Fingerprinter;
import com.documentplatform.fields.FieldKey;
import com.documentplatform.fields.FieldScope;
import com.documentplatform.fields.FieldSet;
import com.documentplatform.fields.FieldSpec;
import com.documentplatform.fields.FieldStore;
import com.documentplatform.fields.FieldType;
import com.documentplatform.fields.FieldValidator;
import com.documentplatform.fields.FieldValue;
import com.documentplatform.fields.FieldValuesCheck;
import com.documentplatform.fields.SearchMode;
import com.documentplatform.fields.ValidValue;
import com.documentplatform.fields.ValidatedFields;
import com.documentplatform.fields.ValueError;
import com.documentplatform.fields.ValueProblem;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Field values of the app's records: checked against the entity's field definitions, stored, looked up by primary
 * value, searched and read back. One instance serves one customer (the key owner); apps make one per customer.
 *
 * <p><b>Sensitive values</b> (case fields rule 6) are never stored readably. All sensitive values of one record are
 * sealed under one data key, the <b>record envelope</b> ({@link RecordCipher}): opening a record is one key service
 * call, and no plaintext key is kept. A sensitive value of a searchable or primary field also gets a keyed
 * fingerprint ({@link Fingerprinter}, field as context, capitals and extra spaces ignored), for exact-match search
 * only; prefix and partial search on a sensitive field are refused ({@code VALUES_SENSITIVE_EXACT_ONLY}). Search
 * covers every usable fingerprint key version.
 *
 * <p><b>The primary rule:</b> a record's <em>identity</em> is its primary field values together (one field in most
 * set-ups, several where a record is identified by a combination). There is one record per identity in each entity,
 * product and record type, enforced by the store, also with concurrent writers. {@link #claim} is how the app finds
 * or makes the record for incoming values: the same primary value(s) always lead to the same record, and concurrent
 * claims of a new value end up on one record. A record without primary values is a record of its own.
 *
 * <p><b>Reading:</b> {@link #read} never decrypts; {@link #readSensitive(RecordRef)} is the explicit call that opens sensitive
 * values, which the app makes only for roles allowed to see them. No value ever appears in a {@code toString()}, a
 * log (this class logs nothing) or an exception message.
 *
 * <p>Key service calls: a new record with sensitive values takes one call for its data key, plus two for
 * fingerprints when any value needs one (the key versions, then one batch); {@link #readSensitive(RecordRef)}
 * takes one, and {@link #readSensitive(List)} one for the whole list.
 */
public final class FieldValues {

    /** The one class that may call {@link #erase}: the erase log's built-in case-fields handler. */
    public static final String ERASE_LOG_HANDLER = "com.documentplatform.eraselog.fields.CaseFieldsEraseHandler";

    private static final int ATTEMPTS = 5;
    private static final int MAX_LIMIT = 1000;

    private final CustomerId customer;
    private final FieldStore definitions;
    private final ValueStore store;
    private final KeyService keys;
    private final RecordCipher cipher;
    private final Fingerprinter fingerprinter;
    private final Clock clock;
    private final LegalHolds holds;
    /** In the app's transaction ({@link #in}): erasing is refused. */
    private final boolean bound;

    /** Without legal holds: {@link #erase} is refused for no record. */
    public FieldValues(CustomerId customer, FieldStore definitions, ValueStore store, KeyService keys, Clock clock) {
        this(customer, definitions, store, keys, clock, LegalHolds.NONE);
    }

    /** With the app's legal hold check, asked by {@link #erase}. */
    public FieldValues(CustomerId customer, FieldStore definitions, ValueStore store, KeyService keys, Clock clock,
                       LegalHolds holds) {
        this(customer, definitions, store, clock, holds, Objects.requireNonNull(keys, "keys"), false);
    }

    private FieldValues(CustomerId customer, FieldStore definitions, ValueStore store, Clock clock, LegalHolds holds,
                        KeyService keys, boolean bound) {
        this.bound = bound;
        this.customer = Objects.requireNonNull(customer, "customer");
        this.definitions = Objects.requireNonNull(definitions, "definitions");
        this.store = Objects.requireNonNull(store, "store");
        this.keys = keys;
        this.cipher = keys == null ? null : new RecordCipher(keys);
        this.fingerprinter = keys == null ? null : new Fingerprinter(keys);
        this.clock = Objects.requireNonNull(clock, "clock");
        this.holds = Objects.requireNonNull(holds, "legal holds");
    }

    /**
     * For an app none of whose fields is sensitive: no key service is needed. A call that would seal, open or
     * fingerprint a sensitive value is refused with {@code VALUES_KEY_SERVICE_REQUIRED}, and nothing is written.
     */
    public static FieldValues withoutKeyService(CustomerId customer, FieldStore definitions, ValueStore store,
                                                Clock clock) {
        return withoutKeyService(customer, definitions, store, clock, LegalHolds.NONE);
    }

    /** {@link #withoutKeyService(CustomerId, FieldStore, ValueStore, Clock)} with the app's legal hold check. */
    public static FieldValues withoutKeyService(CustomerId customer, FieldStore definitions, ValueStore store,
                                                Clock clock, LegalHolds holds) {
        return new FieldValues(customer, definitions, store, clock, holds, (KeyService) null, false);
    }

    private KeyService keys() {
        return need(keys);
    }

    private RecordCipher cipher() {
        return need(cipher);
    }

    private Fingerprinter fingerprinter() {
        return need(fingerprinter);
    }

    private static <T> T need(T part) {
        if (part == null) {
            throw new FieldValuesException(FieldValuesError.VALUES_KEY_SERVICE_REQUIRED, "A sensitive field was used, "
                    + "but the app runs its field values without a key service; configure the key service, or make "
                    + "the field not sensitive before it has values");
        }
        return part;
    }

    /**
     * What {@link #erase} removed, for the caller's audit entry: the record, how many values, whether it had sealed
     * (sensitive) values, and when. Holds no value.
     */
    public record Erased(RecordRef record, int values, boolean hadSensitiveValues, Instant at) {
    }

    /** What {@link #claim} did: the record the values belong to, whether it is new, whether anything was written. */
    public record Claim(RecordRef record, boolean created, boolean changed) {
    }

    /** Whether partial search can run; when not, a note for the app's health page naming what the DBA can do. */
    public record SearchStatus(boolean partialSearch, String code, String note) {
    }

    /**
     * These field values with every store call in the app's transaction on {@code connection} (P059, LV-25.2):
     * the same customer, key service, clock and legal holds. What a claim or update writes is committed or rolled
     * back with the app's own rows, by the app; the store never commits, rolls back the whole transaction, changes
     * auto-commit or the isolation, or closes the connection.
     *
     * <ul>
     *   <li>The connection must have auto-commit off and READ COMMITTED isolation; otherwise every call is refused
     *       with {@code VALUES_TRANSACTION_UNSUITABLE} before anything is written.</li>
     *   <li>Each write runs in a savepoint: a refusal ({@code VALUES_PRIMARY_TAKEN}, {@code VALUES_CONFLICT},
     *       {@code VALUES_CHANGED_MEANWHILE}) is thrown as usual and leaves the app's work and transaction usable.
     *       The app decides whether to roll back all.</li>
     *   <li>Row locks are held until the app commits: two claims of the same new primary value wait for each
     *       other that long. A deadlock (SQL state 40P01, {@code VALUES_STORE_FAILED}) means the app retries its
     *       whole transaction.</li>
     *   <li>Key service calls (sealing, fingerprints, opening) run while the transaction is open.</li>
     *   <li>{@link #erase} is refused ({@code VALUES_ERASE_OUTSIDE_LOG}): an erasure has its own transaction,
     *       through the erase log.</li>
     * </ul>
     *
     * <p>The definitions are read in the same transaction when their store can join it, otherwise as they are. Use
     * the copy only in that transaction and on its thread. With Spring: the connection of the current transaction is
     * {@code DataSourceUtils.getConnection(dataSource)} (docs/install/fields.md).
     *
     * @throws UnsupportedOperationException when the value store cannot join a transaction (the in-memory store)
     */
    public FieldValues in(java.sql.Connection connection) {
        Objects.requireNonNull(connection, "connection");
        ValueStore joined = store.joined(connection);
        FieldStore fields;
        try {
            fields = definitions.joined(connection);
        } catch (UnsupportedOperationException e) {
            fields = definitions;
        }
        return new FieldValues(customer, fields, joined, clock, holds, keys, true);
    }

    /** For {@code FieldDefinitions}: values exist for a field once any record holds one. */
    public FieldValuesCheck valuesCheck() {
        return store;
    }

    // ---- writing ----

    /**
     * Finds or makes the record for these values. The values are checked as a whole (required fields, rules). Then:
     * <ul>
     *   <li>a record with the same primary value(s) exists: that record (whatever ID {@code candidate} has). Fields it
     *       has no value for get the given ones; fields with an equal value (capitals and extra spaces ignored)
     *       stay; a field with a different value
     *       refuses the claim ({@code VALUES_CONFLICT}, naming the keys only) and nothing is written;</li>
     *   <li>none exists but a record with {@code candidate}'s ID does: the same, with that record;</li>
     *   <li>otherwise the values are stored as a new record under {@code candidate}. Two writers claiming the same new
     *       primary value(s) at once end up on one record.</li>
     * </ul>
     *
     * @param values per field key, the values given (one, or several for a multi-value field)
     */
    public Claim claim(RecordRef candidate, Map<String, ? extends List<? extends FieldValue>> values) {
        Objects.requireNonNull(candidate, "candidate");
        FieldSet set = fields(candidate.scope());
        ValidatedFields checked = set.validate(values);
        if (!checked.accepted()) {
            throw invalid(candidate, checked.problems());
        }
        Drafts drafts = draft(candidate.scope(), set, checked.values(), true);
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            Optional<StoredRecord> existing = drafts.identities.isEmpty() ? Optional.empty()
                    : store.findByIdentity(candidate.scope(), candidate.recordType(), drafts.identities);
            if (existing.isEmpty()) {
                existing = store.find(candidate);
            }
            if (existing.isEmpty()) {
                Instant now = clock.instant();
                Sealed sealed = seal(candidate, null, drafts.list);
                StoredRecord record = new StoredRecord(UUID.randomUUID(), customer.value(), candidate,
                        drafts.identities.isEmpty() ? null : drafts.identities.get(0), sealed.envelope, 1, now, now,
                        sealed.rows);
                ValueStore.Inserted inserted = store.insert(record);
                if (inserted.created()) {
                    return new Claim(candidate, true, true);
                }
                existing = Optional.of(inserted.record());
            }
            StoredRecord current = mine(existing.get());
            try {
                return merge(set, current, drafts);
            } catch (FieldValuesException e) {
                if (e.code() != FieldValuesError.VALUES_CHANGED_MEANWHILE) {
                    throw e;
                }
            }
        }
        throw new FieldValuesException(FieldValuesError.VALUES_CHANGED_MEANWHILE, "Record " + candidate
                + " kept changing while it was written; try again");
    }

    /**
     * Sets the values of the given fields of an existing record, replacing what those fields held; an empty list
     * clears a field (refused for a required one). Other fields keep their values. Changing a primary value to one
     * another record has is refused ({@code VALUES_PRIMARY_TAKEN}).
     *
     * @return the record's new version
     */
    public int update(RecordRef ref, Map<String, ? extends List<? extends FieldValue>> values) {
        Objects.requireNonNull(ref, "ref");
        FieldSet set = fields(ref.scope());
        ValidatedFields checked = set.validate(values);
        Set<FieldKey> given = new HashSet<>();
        for (String k : values.keySet()) {
            if (FieldKey.isValid(k)) {
                given.add(FieldKey.of(k));
            }
        }
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            StoredRecord current = mine(store.find(ref).orElseThrow(() -> unknown(ref)));
            Set<FieldKey> held = new HashSet<>();
            current.values().forEach(v -> held.add(v.key()));
            List<ValueProblem> problems = checked.problems().stream()
                    .filter(p -> !(p.code() == ValueError.VALUE_REQUIRED && p.key() != null
                            && !given.contains(p.key()) && held.contains(p.key())))
                    .toList();
            if (!problems.isEmpty()) {
                throw invalid(ref, problems);
            }
            Drafts drafts = draft(ref.scope(), set, checked.values(), false);
            List<StoredValue> kept = current.values().stream().filter(v -> !given.contains(v.key())).toList();
            Sealed sealed = sealOnto(set, current, kept, drafts.list);
            if (sealed.rows.isEmpty()) {
                throw new FieldValuesException(FieldValuesError.VALUES_ERASE_OUTSIDE_LOG, "Record " + ref + " was not "
                        + "changed: clearing every value of a record is an erasure; erase it through the erase log "
                        + "(Eraser, kind case-fields)");
            }
            String identity = identityOf(set, sealed.rows);
            try {
                return store.replace(current.with(identity, sealed.envelope, clock.instant(), sealed.rows)).version();
            } catch (FieldValuesException e) {
                if (e.code() != FieldValuesError.VALUES_CHANGED_MEANWHILE) {
                    throw e;
                }
            }
        }
        throw new FieldValuesException(FieldValuesError.VALUES_CHANGED_MEANWHILE, "Record " + ref
                + " kept changing while it was written; try again");
    }

    /**
     * Moves the record's envelope to the latest version of the customer's wrapping key (before an old version is
     * switched off). The sealed values stay as they are. Returns false if there was nothing to do.
     */
    public boolean rewrap(RecordRef ref) {
        StoredRecord current = mine(store.find(ref).orElseThrow(() -> unknown(ref)));
        if (current.envelope() == null
                || cipher().keyVersion(current.envelope()) == keys().wrappingKey(customer).latest()) {
            return false;
        }
        String again = cipher().rewrap(customer, current.envelope());
        store.replace(current.with(current.identity(), again, clock.instant(), current.values()));
        return true;
    }

    /**
     * Erases the record: deletes its value rows, its envelope (the wrapped record key) and the record itself, so it
     * is no longer read, found by search or by its primary value(s); the same primary value(s) arriving later make a
     * new record. Explicit only, never automatic; the caller writes the audit entry (who, why) from the result.
     *
     * <p>Refused for a record the app's {@link LegalHolds} says is held ({@code VALUES_LEGAL_HOLD}), and when the
     * check cannot answer ({@code VALUES_LEGAL_HOLD_UNKNOWN}); nothing is erased then. Calls no key service.
     *
     * <p>What this does <b>not</b> do: copies of the rows in database backups keep the envelope, and the customer's
     * wrapping key still opens it. See {@code docs/design/case-fields.md}, "Erasing a record".
     *
     * <p><b>Only through the erase log.</b> Apps never call this: they erase with the erase log's {@code Eraser}
     * (kind {@code case-fields}), which logs the erasure outside the database first, so a restored backup can be
     * erased again. The only caller allowed is the erase log's built-in handler ({@value #ERASE_LOG_HANDLER}); any
     * other caller is refused with {@code VALUES_ERASE_OUTSIDE_LOG} before anything is read or erased.
     */
    public Erased erase(RecordRef ref) {
        Objects.requireNonNull(ref, "ref");
        Class<?> caller = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE).getCallerClass();
        if (!ERASE_LOG_HANDLER.equals(caller.getName())) {
            throw new FieldValuesException(FieldValuesError.VALUES_ERASE_OUTSIDE_LOG, "Record " + ref + " was not "
                    + "erased: case field values are erased only through the erase log (Eraser, kind case-fields)");
        }
        return eraseRecord(ref);
    }

    /** The erase itself, without the caller check; for this package's tests. */
    Erased eraseRecord(RecordRef ref) {
        Objects.requireNonNull(ref, "ref");
        if (bound) {
            throw new FieldValuesException(FieldValuesError.VALUES_ERASE_OUTSIDE_LOG, "Record " + ref + " was not "
                    + "erased: an erasure runs in its own transaction through the erase log, never in the app's "
                    + "transaction (FieldValues.in)");
        }
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            StoredRecord current = mine(store.find(ref).orElseThrow(() -> unknown(ref)));
            boolean held;
            try {
                held = holds.held(ref);
            } catch (RuntimeException e) {
                throw new FieldValuesException(FieldValuesError.VALUES_LEGAL_HOLD_UNKNOWN, "Record " + ref
                        + " was not erased: the legal hold check could not answer");
            }
            if (held) {
                throw new FieldValuesException(FieldValuesError.VALUES_LEGAL_HOLD, "Record " + ref
                        + " is under legal hold, so its values may not be erased");
            }
            try {
                store.delete(current, new EraseTicket(ref));
                return new Erased(ref, current.values().size(), current.envelope() != null, clock.instant());
            } catch (FieldValuesException e) {
                if (e.code() != FieldValuesError.VALUES_CHANGED_MEANWHILE) {
                    throw e;
                }
            }
        }
        throw new FieldValuesException(FieldValuesError.VALUES_CHANGED_MEANWHILE, "Record " + ref
                + " kept changing while it was erased; try again");
    }

    // ---- reading ----

    /** The record's values; sensitive values hidden (no key service call). */
    public RecordValues read(RecordRef ref) {
        return read(ref, false);
    }

    /**
     * The record's values with the sensitive ones opened: one key service call. The app calls this only for a role
     * allowed to see sensitive values.
     */
    public RecordValues readSensitive(RecordRef ref) {
        return read(ref, true);
    }

    /**
     * {@link #read(RecordRef)} of many records (a results page), one result per record in the order given, with
     * <b>one</b> query for all of them and no key service call. What the single read would refuse for one record is
     * that record's result; a store failure throws, and nothing is returned. At most {@link KeyService#MAX_BATCH}
     * records; an empty list calls nothing.
     */
    public List<SensitiveRead> read(List<RecordRef> refs) {
        return readAll(refs, false);
    }

    /**
     * {@link #readSensitive(RecordRef)} of many records (a results page), one result per record in the order given,
     * with <b>one</b> query for all of them and <b>one</b> batched key service call (none when no record has a
     * sensitive value). What the
     * single read would refuse or fail for one record is that record's result; a failure that is not about one record
     * (key service away or refusing, customer's key missing, store failure) throws, and nothing is returned. At most
     * {@link KeyService#MAX_BATCH} records; an empty list calls nothing.
     */
    public List<SensitiveRead> readSensitive(List<RecordRef> refs) {
        return readAll(refs, true);
    }

    private List<SensitiveRead> readAll(List<RecordRef> refs, boolean open) {
        List<RecordRef> all = List.copyOf(refs);
        if (all.size() > KeyService.MAX_BATCH) {
            throw new IllegalArgumentException("A batch has at most " + KeyService.MAX_BATCH + " records");
        }
        if (all.isEmpty()) {
            return List.of();
        }
        Map<RecordRef, StoredRecord> stored = store.findAll(all);
        SensitiveRead[] out = new SensitiveRead[all.size()];
        StoredRecord[] records = new StoredRecord[all.size()];
        Map<FieldScope, FieldSet> sets = new HashMap<>();
        List<Integer> sealed = new ArrayList<>();
        List<RecordCipher.StoredKey> toOpen = new ArrayList<>();
        for (int i = 0; i < out.length; i++) {
            RecordRef ref = all.get(i);
            try {
                records[i] = mine(Optional.ofNullable(stored.get(ref)).orElseThrow(() -> unknown(ref)));
            } catch (FieldValuesException e) {
                if (e.code() != FieldValuesError.VALUES_RECORD_UNKNOWN
                        && e.code() != FieldValuesError.VALUES_WRONG_CUSTOMER) {
                    throw e;
                }
                out[i] = SensitiveRead.failed(ref, e);
                continue;
            }
            sets.computeIfAbsent(ref.scope(), this::fields);
            if (open && anySensitive(records[i])) {
                sealed.add(i);
                toOpen.add(new RecordCipher.StoredKey(recordContext(ref), records[i].envelope()));
            }
        }
        List<RecordCipher.OpenedKey> opened = toOpen.isEmpty() ? List.of() : cipher().openKeys(customer, toOpen);
        try {
            RecordCipher.OpenedKey[] keyOf = new RecordCipher.OpenedKey[out.length];
            for (int n = 0; n < opened.size(); n++) {
                keyOf[sealed.get(n)] = opened.get(n);
            }
            for (int i = 0; i < out.length; i++) {
                if (out[i] != null) {
                    continue;
                }
                RecordRef ref = all.get(i);
                try (RecordCipher.OpenedKey key = keyOf[i]) {
                    out[i] = SensitiveRead.read(ref, values(records[i], sets.get(ref.scope()),
                            key == null ? null : key.key()));
                } catch (CryptoException | FieldValuesException | IllegalStateException
                         | IllegalArgumentException e) {
                    // Opening and decoding one record's own rows; neither the key service nor the store is called.
                    out[i] = SensitiveRead.failed(ref, e);
                }
            }
        } finally {
            opened.forEach(RecordCipher.OpenedKey::close);
        }
        return List.of(out);
    }

    /**
     * The record whose primary value(s) are these, if any.
     *
     * @param primary per primary field key, its value; together the record's identity (every primary field the record
     *                has a value for)
     */
    public Optional<RecordRef> findByPrimary(FieldScope scope, String recordType,
                                             Map<String, ? extends FieldValue> primary) {
        RecordRef.requireType(recordType);
        FieldSet set = fields(scope);
        if (primary.isEmpty()) {
            throw new FieldValuesException(FieldValuesError.VALUES_NOT_PRIMARY, "A lookup by primary value needs at "
                    + "least one primary value");
        }
        Map<FieldKey, List<ValidValue>> valid = new LinkedHashMap<>();
        for (Map.Entry<String, ? extends FieldValue> e : primary.entrySet()) {
            FieldSpec spec = set.find(e.getKey()).orElseThrow(() -> new FieldValuesException(
                    FieldValuesError.VALUES_FIELD_UNKNOWN, "No field has the key " + safeKey(e.getKey())));
            if (!spec.primary()) {
                throw new FieldValuesException(FieldValuesError.VALUES_NOT_PRIMARY, spec.label() + " ("
                        + spec.key() + ") is not a primary field");
            }
            FieldValidator.Checked c = FieldValidator.check(spec, e.getValue());
            if (!c.ok()) {
                return Optional.empty();
            }
            valid.put(spec.key(), List.of(c.valid()));
        }
        Drafts drafts = draft(scope, set, valid, true);
        return store.findByIdentity(scope, recordType, drafts.identities).map(this::mine).map(StoredRecord::ref);
    }

    /**
     * The records of the entity, product and record type with a value of the field that matches the term.
     * <ul>
     *   <li>EXACT: the term is checked and normalised like a value; a sensitive field is matched by fingerprint over
     *       every usable fingerprint key version. A term the field could never hold matches nothing.</li>
     *   <li>PREFIX, PARTIAL: only for fields that are not sensitive and offer that mode; the term is normalised like a
     *       value (capitals and extra spaces ignored). Without the trigram index partial runs as prefix and the result
     *       says so ({@link SearchResult.Note#PARTIAL_AS_PREFIX}).</li>
     * </ul>
     */
    public SearchResult search(FieldScope scope, String recordType, String key, SearchMode mode, FieldValue term,
                               int limit) {
        RecordRef.requireType(recordType);
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(term, "term");
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("A search limit is 1 to " + MAX_LIMIT);
        }
        FieldSpec spec = fields(scope).find(key).orElseThrow(() -> new FieldValuesException(
                FieldValuesError.VALUES_FIELD_UNKNOWN, "No field has the key " + safeKey(key)));
        if (!spec.searchable()) {
            throw new FieldValuesException(FieldValuesError.VALUES_NOT_SEARCHABLE, spec.label() + " (" + spec.key()
                    + ") is not searchable");
        }
        if (spec.sensitive() && mode != SearchMode.EXACT) {
            throw new FieldValuesException(FieldValuesError.VALUES_SENSITIVE_EXACT_ONLY, spec.label() + " ("
                    + spec.key() + ") is sensitive, so it is searchable by exact match only");
        }
        if (mode.ordinal() > spec.searchMode().ordinal()) {
            throw new FieldValuesException(FieldValuesError.VALUES_SEARCH_MODE_NOT_ALLOWED, spec.label() + " ("
                    + spec.key() + ") offers " + spec.searchMode() + " search, not " + mode);
        }
        SearchMatch match;
        SearchResult.Note note = SearchResult.Note.NONE;
        if (mode == SearchMode.EXACT) {
            FieldValidator.Checked c = FieldValidator.check(spec, term);
            if (!c.ok()) {
                return new SearchResult(List.of(), note);
            }
            String form = ValueCodec.search(c.valid());
            if (spec.sensitive()) {
                List<String> terms = new ArrayList<>();
                fingerprinter().searchTerms(customer, fingerprintContext(scope, spec.key()), form)
                        .forEach(f -> terms.add(f.text()));
                match = new SearchMatch.Fingerprints(terms);
            } else {
                match = new SearchMatch.Exact(List.of(form));
            }
        } else {
            if (!(term instanceof FieldValue.Text t)) {
                throw new IllegalArgumentException("Prefix and partial search take text");
            }
            String form = Fingerprinter.canonical(spec.normalisation().apply(Fingerprinter.canonical(t.value())));
            if (form.isEmpty()) {
                return new SearchResult(List.of(), note);
            }
            if (mode == SearchMode.PARTIAL && !store.partialSearchAvailable()) {
                note = SearchResult.Note.PARTIAL_AS_PREFIX;
                match = new SearchMatch.Prefix(form);
            } else {
                match = mode == SearchMode.PREFIX ? new SearchMatch.Prefix(form) : new SearchMatch.Partial(form);
            }
        }
        return new SearchResult(store.search(customer.value(), scope, recordType, spec.key(), match, limit), note);
    }

    /** {@link #search} with at most 100 results. */
    public SearchResult search(FieldScope scope, String recordType, String key, SearchMode mode, FieldValue term) {
        return search(scope, recordType, key, mode, term, 100);
    }

    /** For the app's health page: whether partial search runs, and if not, what to do. */
    public SearchStatus searchStatus() {
        if (store.partialSearchAvailable()) {
            return new SearchStatus(true, null, "partial search available");
        }
        return new SearchStatus(false, FieldValuesError.FLD_PARTIAL_SEARCH_UNAVAILABLE.name(), "partial search works as "
                + "prefix search: the "
                + "trigram index is missing; the DBA makes the pg_trgm extension available and runs "
                + "db/platform/fields-optional/partial-search.sql as the owner of the fld_ tables");
    }

    // ---- inside ----

    /** A checked value ready to store: everything but the sealing. */
    private record Draft(FieldSpec spec, int position, ValidValue value, String search, String fingerprint) {
    }

    private record Drafts(List<Draft> list, List<String> identities) {
    }

    private record Sealed(String envelope, List<StoredValue> rows) {
    }

    private FieldSet fields(FieldScope scope) {
        Objects.requireNonNull(scope, "scope");
        try {
            return FieldSet.ofDefinitions(definitions.list(scope));
        } catch (com.documentplatform.fields.FieldDefinitionException e) {
            if (e.code() != com.documentplatform.fields.FieldDefinitionException.Code.FIELD_TRANSACTION_UNSUITABLE) {
                throw e;
            }
            // In the app's transaction the definitions are read first: the refusal speaks of values here.
            throw new FieldValuesException(FieldValuesError.VALUES_TRANSACTION_UNSUITABLE, e.getMessage());
        }
    }

    /**
     * Drafts of the values, with the fingerprints sensitive values need (latest key version, one batch) and, if
     * asked, the identities to look up: the latest first, then one per older usable fingerprint key version.
     */
    private Drafts draft(FieldScope scope, FieldSet set, Map<FieldKey, List<ValidValue>> values,
                         boolean lookupIdentities) {
        List<Draft> out = new ArrayList<>();
        List<Fingerprinter.Input> inputs = new ArrayList<>();
        List<Integer> needing = new ArrayList<>();
        List<Fingerprinter.Input> primaryInputs = new ArrayList<>();
        for (Map.Entry<FieldKey, List<ValidValue>> e : values.entrySet()) {
            FieldSpec spec = set.find(e.getKey()).orElseThrow();
            for (int i = 0; i < e.getValue().size(); i++) {
                ValidValue v = e.getValue().get(i);
                String search = ValueCodec.search(v);
                out.add(new Draft(spec, i, v, search, null));
                if (spec.sensitive() && (spec.searchable() || spec.primary())) {
                    Fingerprinter.Input in = new Fingerprinter.Input(fingerprintContext(scope, spec.key()), search);
                    inputs.add(in);
                    needing.add(out.size() - 1);
                    if (spec.primary()) {
                        primaryInputs.add(in);
                    }
                }
            }
        }
        List<Integer> versions = inputs.isEmpty() ? List.of() : fingerprinter().usableVersions(customer);
        if (!inputs.isEmpty()) {
            List<Fingerprinter.Fingerprint> fps = fingerprinter().ofAll(customer, versions.get(0), inputs);
            for (int i = 0; i < needing.size(); i++) {
                Draft d = out.get(needing.get(i));
                out.set(needing.get(i), new Draft(d.spec, d.position, d.value, d.search, fps.get(i).text()));
            }
        }
        List<String> identities = new ArrayList<>();
        if (lookupIdentities) {
            String latest = identity(set, parts(out));
            if (latest != null) {
                identities.add(latest);
                for (int v = 1; v < versions.size() && !primaryInputs.isEmpty(); v++) {
                    List<Fingerprinter.Fingerprint> older = fingerprinter().ofAll(customer, versions.get(v),
                            primaryInputs);
                    List<Draft> again = new ArrayList<>(out);
                    int n = 0;
                    for (int i = 0; i < again.size(); i++) {
                        Draft d = again.get(i);
                        if (d.spec.primary() && d.spec.sensitive()) {
                            again.set(i, new Draft(d.spec, d.position, d.value, d.search, older.get(n++).text()));
                        }
                    }
                    identities.add(identity(set, parts(again)));
                }
            }
        }
        return new Drafts(out, List.copyOf(new LinkedHashSet<>(identities)));
    }

    /** The values of a new record: sealed under a new record key when any is sensitive. */
    private Sealed seal(RecordRef ref, String envelope, List<Draft> drafts) {
        boolean anySensitive = drafts.stream().anyMatch(d -> d.spec.sensitive());
        if (!anySensitive) {
            return new Sealed(envelope, plainRows(drafts));
        }
        try (RecordCipher.RecordKey key = cipher().newKey(customer, recordContext(ref))) {
            return new Sealed(key.envelope(), rows(drafts, key));
        }
    }

    /** New values added to a stored record's kept rows, sealed under the record's own key. */
    private Sealed sealOnto(FieldSet set, StoredRecord current, List<StoredValue> kept, List<Draft> drafts) {
        boolean newSensitive = drafts.stream().anyMatch(d -> d.spec.sensitive());
        List<StoredValue> needFingerprint = kept.stream().filter(v -> v.sensitive() && v.fingerprint() == null
                && set.find(v.key()).map(s -> s.searchable() || s.primary()).orElse(false)).toList();
        if (!newSensitive && needFingerprint.isEmpty()) {
            List<StoredValue> rows = new ArrayList<>(kept);
            rows.addAll(plainRows(drafts));
            return new Sealed(current.envelope(), sorted(rows));
        }
        try (RecordCipher.RecordKey key = current.envelope() == null
                ? cipher().newKey(customer, recordContext(current.ref()))
                : cipher().openKey(customer, recordContext(current.ref()), current.envelope())) {
            List<StoredValue> rows = new ArrayList<>(fingerprinted(current.ref().scope(), kept, needFingerprint, key));
            rows.addAll(rows(drafts, key));
            return new Sealed(key.envelope(), sorted(rows));
        }
    }

    /** Kept sensitive rows of fields that became searchable or primary, with the fingerprint they now need. */
    private List<StoredValue> fingerprinted(FieldScope scope, List<StoredValue> kept, List<StoredValue> need,
                                            RecordCipher.RecordKey key) {
        if (need.isEmpty()) {
            return kept;
        }
        List<Fingerprinter.Input> inputs = new ArrayList<>();
        for (StoredValue v : need) {
            ValidValue value = ValueCodec.fromSealedForm(key.openText(itemContext(v), v.sealed()));
            inputs.add(new Fingerprinter.Input(fingerprintContext(scope, v.key()), ValueCodec.search(value)));
        }
        List<Fingerprinter.Fingerprint> fps = fingerprinter().fingerprintAll(customer, inputs);
        List<StoredValue> out = new ArrayList<>();
        for (StoredValue v : kept) {
            int at = need.indexOf(v);
            out.add(at < 0 ? v : new StoredValue(v.key(), v.position(), true, null, null, null, v.sealed(),
                    fps.get(at).text()));
        }
        return out;
    }

    private static List<StoredValue> plainRows(List<Draft> drafts) {
        List<StoredValue> rows = new ArrayList<>();
        for (Draft d : drafts) {
            rows.add(plain(d));
        }
        return rows;
    }

    private static StoredValue plain(Draft d) {
        return new StoredValue(d.spec.key(), d.position, false, ValueCodec.encode(d.value.display()),
                ValueCodec.encode(d.value.normalised()), d.search, null, null);
    }

    private static List<StoredValue> rows(List<Draft> drafts, RecordCipher.RecordKey key) {
        List<StoredValue> rows = new ArrayList<>();
        for (Draft d : drafts) {
            if (!d.spec.sensitive()) {
                rows.add(plain(d));
                continue;
            }
            String sealed = key.seal(itemContext(d.spec.key(), d.position), ValueCodec.sealedForm(d.value));
            rows.add(new StoredValue(d.spec.key(), d.position, true, null, null, null, sealed, d.fingerprint));
        }
        return rows;
    }

    private static List<StoredValue> sorted(List<StoredValue> rows) {
        List<StoredValue> s = new ArrayList<>(rows);
        s.sort(Comparator.comparing((StoredValue v) -> v.key().folded()).thenComparingInt(StoredValue::position));
        return s;
    }

    /** Claim on an existing record: add what it lacks, keep what is equal, refuse what differs. */
    private Claim merge(FieldSet set, StoredRecord current, Drafts drafts) {
        Map<FieldKey, List<StoredValue>> held = new TreeMap<>();
        current.values().forEach(v -> held.computeIfAbsent(v.key(), k -> new ArrayList<>()).add(v));
        List<Draft> added = new ArrayList<>();
        List<FieldKey> compareSensitive = new ArrayList<>();
        Set<FieldKey> conflicts = new LinkedHashSet<>();
        Map<FieldKey, List<Draft>> given = new LinkedHashMap<>();
        drafts.list.forEach(d -> given.computeIfAbsent(d.spec.key(), k -> new ArrayList<>()).add(d));
        for (Map.Entry<FieldKey, List<Draft>> e : given.entrySet()) {
            List<StoredValue> have = held.get(e.getKey());
            if (have == null) {
                added.addAll(e.getValue());
            } else if (have.stream().anyMatch(StoredValue::sensitive)) {
                compareSensitive.add(e.getKey());
            } else if (!sameValues(have.stream().map(StoredValue::search).toList(), e.getValue())) {
                conflicts.add(e.getKey());
            }
        }
        if (!compareSensitive.isEmpty()) {
            try (RecordCipher.RecordKey key = cipher().openKey(customer, recordContext(current.ref()),
                    current.envelope())) {
                for (FieldKey k : compareSensitive) {
                    List<String> normalised = new ArrayList<>();
                    for (StoredValue v : held.get(k)) {
                        normalised.add(v.sensitive()
                                ? ValueCodec.search(ValueCodec.fromSealedForm(key.openText(itemContext(v), v.sealed())))
                                : v.search());
                    }
                    if (!sameValues(normalised, given.get(k))) {
                        conflicts.add(k);
                    }
                }
            }
        }
        if (!conflicts.isEmpty()) {
            throw FieldValuesException.conflict("Record " + current.ref() + " already holds other values for "
                    + conflicts + "; nothing was written", conflicts);
        }
        if (added.isEmpty()) {
            return new Claim(current.ref(), false, false);
        }
        Sealed sealed = sealOnto(set, current, current.values(), added);
        store.replace(current.with(identityOf(set, sealed.rows), sealed.envelope, clock.instant(), sealed.rows));
        return new Claim(current.ref(), false, true);
    }

    private static boolean sameValues(List<String> stored, List<Draft> given) {
        if (stored.size() != given.size()) {
            return false;
        }
        Set<String> a = new HashSet<>(stored);
        for (Draft d : given) {
            if (!a.contains(d.search)) {
                return false;
            }
        }
        return true;
    }

    private RecordValues read(RecordRef ref, boolean open) {
        StoredRecord r = mine(store.find(Objects.requireNonNull(ref, "ref")).orElseThrow(() -> unknown(ref)));
        FieldSet set = fields(ref.scope());
        RecordCipher.RecordKey key = open && anySensitive(r)
                ? cipher().openKey(customer, recordContext(ref), r.envelope()) : null;
        try {
            return values(r, set, key);
        } finally {
            if (key != null) {
                key.close();
            }
        }
    }

    private static boolean anySensitive(StoredRecord r) {
        return r.values().stream().anyMatch(StoredValue::sensitive);
    }

    /** The record's values, the sensitive ones opened with the key, or hidden without one. */
    private static RecordValues values(StoredRecord r, FieldSet set, RecordCipher.RecordKey key) {
        Map<FieldKey, List<StoredValue>> byKey = new TreeMap<>();
        r.values().forEach(v -> byKey.computeIfAbsent(v.key(), k -> new ArrayList<>()).add(v));
        Map<FieldKey, RecordValues.Field> out = new LinkedHashMap<>();
        for (Map.Entry<FieldKey, List<StoredValue>> e : byKey.entrySet()) {
            boolean address = set.find(e.getKey()).map(s -> s.type() == FieldType.ADDRESS).orElse(false);
            boolean sensitive = e.getValue().stream().anyMatch(StoredValue::sensitive);
            List<ValidValue> values = new ArrayList<>();
            if (!sensitive || key != null) {
                for (StoredValue v : e.getValue()) {
                    values.add(v.sensitive()
                            ? ValueCodec.fromSealedForm(key.openText(itemContext(v), v.sealed()))
                            : new ValidValue(ValueCodec.decode(v.display(), address),
                            ValueCodec.decode(v.normalised(), address)));
                }
            }
            out.put(e.getKey(), new RecordValues.Field(e.getKey(), sensitive, e.getValue().size(), values));
        }
        return new RecordValues(r.ref(), r.version(), key != null, out);
    }

    /** The record, if it is this customer's. */
    private StoredRecord mine(StoredRecord r) {
        if (!r.customer().equals(customer.value())) {
            throw new FieldValuesException(FieldValuesError.VALUES_WRONG_CUSTOMER, "Record " + r.ref()
                    + " belongs to another customer than " + customer);
        }
        return r;
    }

    /** One part of the identity: a primary field's value as compared. */
    private record Part(FieldKey key, boolean sensitive, String search, String fingerprint) {
    }

    private static List<Part> parts(List<Draft> drafts) {
        return drafts.stream().map(d -> new Part(d.spec.key(), d.spec.sensitive(), d.search, d.fingerprint)).toList();
    }

    private static String identityOf(FieldSet set, List<StoredValue> rows) {
        return identity(set, rows.stream().map(v -> new Part(v.key(), v.sensitive(), v.search(), v.fingerprint()))
                .toList());
    }

    /**
     * The record's identity: SHA-256 over its primary fields' values in key order (a non-sensitive value as compared
     * by search, a sensitive one by its fingerprint), or null when it has no primary value.
     */
    private static String identity(FieldSet set, List<Part> parts) {
        TreeMap<String, String> components = new TreeMap<>();
        for (Part p : parts) {
            Optional<FieldSpec> spec = set.find(p.key());
            if (spec.isEmpty() || !spec.get().primary()) {
                continue;
            }
            if (p.sensitive() && p.fingerprint() == null) {
                throw new IllegalStateException("A sensitive primary value without its fingerprint");
            }
            components.put(p.key().folded(), p.sensitive() ? "f:" + p.fingerprint() : "v:" + p.search());
        }
        if (components.isEmpty()) {
            return null;
        }
        StringBuilder b = new StringBuilder("platform-fields/identity/1");
        components.forEach((k, v) -> b.append('\n').append(k).append('=').append(v));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(sha256(b.toString()));
    }

    /** Binds sealed values to the record: a hash of entity, product, record type and ID. */
    static String recordContext(RecordRef ref) {
        String name = ref.scope().entity() + "|" + ref.scope().product() + "|" + ref.recordType() + "|"
                + ref.recordId();
        return "fld/" + Base64.getUrlEncoder().withoutPadding().encodeToString(sha256(name));
    }

    private static String itemContext(StoredValue v) {
        return itemContext(v.key(), v.position());
    }

    private static String itemContext(FieldKey key, int position) {
        return key.folded() + "/" + position;
    }

    static String fingerprintContext(FieldScope scope, FieldKey key) {
        return "fld:" + scope.entity() + ":" + scope.product() + ":" + key.folded();
    }

    private static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static FieldValuesException invalid(RecordRef ref, List<ValueProblem> problems) {
        return new FieldValuesException(FieldValuesError.VALUES_INVALID, "Values for record " + ref + " refused: "
                + problems.size() + " problem(s)", problems);
    }

    private static FieldValuesException unknown(RecordRef ref) {
        return new FieldValuesException(FieldValuesError.VALUES_RECORD_UNKNOWN, "No record " + ref);
    }

    private static String safeKey(String key) {
        if (key == null) {
            return "(none)";
        }
        String s = key.replaceAll("[^A-Za-z0-9_.-]", "?");
        return s.length() > 100 ? s.substring(0, 100) + "..." : s;
    }
}
