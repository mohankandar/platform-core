package com.documentplatform.eraselog.testkit;

import com.documentplatform.eraselog.EraseEntry;
import com.documentplatform.eraselog.EraseHandler;
import com.documentplatform.eraselog.EraseHandlerException;
import com.documentplatform.eraselog.EraseRequest;

import java.util.HashSet;
import java.util.Set;

/**
 * A handler for one kind that "stores" record IDs in memory and erases them: {@link #store} puts one there,
 * {@link #apply} removes it ({@link Applied#ALREADY_GONE} when it was not there), {@link #verify} says whether it is
 * still there. A test can make it refuse before logging ({@link #refuse}), fail when applying ({@link #failWith}), or
 * {@link #crash} (stand for the process stopping right after the log write). {@link #present()} and
 * {@link #reset(Set)} stand for a backup and its restore. Test scope only.
 */
public final class RecordingEraseHandler implements EraseHandler {

    private final String kind;
    private final Set<String> present = new HashSet<>();
    private volatile String refuseCode;
    private volatile String failCode;
    private volatile boolean crash;
    private volatile int applied;

    public RecordingEraseHandler(String kind) {
        this.kind = kind;
    }

    /** Thrown by {@link #apply} when {@link #crash} is on: the process "stops" between the log write and the erase. */
    public static final class Crash extends RuntimeException {
        Crash() {
            super("the process stopped (test)", null, false, false);
        }
    }

    public synchronized RecordingEraseHandler store(String recordId) {
        present.add(recordId);
        return this;
    }

    public synchronized Set<String> present() {
        return Set.copyOf(present);
    }

    public synchronized void reset(Set<String> backup) {
        present.clear();
        present.addAll(backup);
    }

    public RecordingEraseHandler refuse(String code) {
        this.refuseCode = code;
        return this;
    }

    public RecordingEraseHandler failWith(String code) {
        this.failCode = code;
        return this;
    }

    public RecordingEraseHandler crash(boolean crash) {
        this.crash = crash;
        return this;
    }

    /** How many times {@link #apply} ran to the end. */
    public int appliedCount() {
        return applied;
    }

    @Override
    public String kind() {
        return kind;
    }

    @Override
    public void check(EraseRequest request) {
        if (refuseCode != null) {
            throw new EraseHandlerException(refuseCode, request.recordType() + " " + request.recordId()
                    + " may not be erased (test)");
        }
    }

    @Override
    public synchronized Applied apply(EraseEntry entry, Context ctx) {
        if (crash) {
            throw new Crash();
        }
        if (failCode != null) {
            throw new EraseHandlerException(failCode, "not erased (test)");
        }
        applied++;
        return present.remove(entry.request().recordId()) ? Applied.ERASED : Applied.ALREADY_GONE;
    }

    @Override
    public synchronized Verified verify(EraseEntry entry) {
        return present.contains(entry.request().recordId()) ? Verified.READABLE : Verified.GONE;
    }
}
