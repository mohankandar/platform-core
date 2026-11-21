package com.documentplatform.identity.store;

import com.documentplatform.identity.EntityUuid;

import java.time.Clock;
import java.util.Objects;
import java.util.Optional;

import static com.documentplatform.identity.store.ActivationOutcome.Result.*;

/**
 * The Business Admin activates and names an entity slot, renames an activated one, or suspends and resumes it. This is
 * the app's API for its activation screen.
 *
 * <ul>
 *   <li>Only a slot listed in the licence in force can be activated or resumed; without a valid licence nothing can.
 *       The licence must allow intake: valid, expiring or in its grace period. Before its start date
 *       ({@code LICENCE_NOT_STARTED}) and after grace ({@code LICENCE_EXPIRED}) activation and resume are refused.</li>
 *   <li>A slot is activated once; it is never deleted here. Suspend keeps it activated, but nobody may sign in to it;
 *       a suspended slot that leaves the licence and comes back stays suspended.</li>
 *   <li>The name is trimmed, 1 to {@value #MAX_NAME_LENGTH} characters, without control characters, and unique within
 *       the customer ignoring case (so nobody confuses two entities).</li>
 *   <li>The caller audits every call, refused or not, from the returned outcome. After ACTIVATED the app creates its
 *       own entity row keyed by {@link EntityRecord#entityUuid()} (in the same transaction where it can).</li>
 * </ul>
 */
public final class SlotActivation {

    public static final int MAX_NAME_LENGTH = 200;

    private final EntityStore store;
    private final LicensedSlots licence;
    private final Clock clock;

    public SlotActivation(EntityStore store, LicensedSlots licence, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.licence = Objects.requireNonNull(licence, "licence");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * @param actor the actor ID of the Business Admin (stored as activated_by)
     */
    public ActivationOutcome activate(String slotId, String name, String actor) {
        requireActor(actor);
        if (!EntityUuid.isSlotId(slotId)) return new ActivationOutcome(NOT_A_SLOT_ID, null, null);
        Optional<String> clean = cleanName(name);
        if (clean.isEmpty()) return new ActivationOutcome(NAME_INVALID, slotId, null);
        Optional<ActivationOutcome> refused = licenceRefusal(slotId);
        if (refused.isPresent()) return refused.get();
        return store.activate(slotId, clean.get(), actor, clock.instant());
    }

    /**
     * Suspends an ACTIVE slot: nobody may sign in to it until it is resumed. It stays activated, keeps its name and is
     * still counted by licensing. Allowed whatever the licence state (suspending never grants anything).
     */
    public ActivationOutcome suspend(String slotId, String actor) {
        requireActor(actor);
        if (!EntityUuid.isSlotId(slotId)) return new ActivationOutcome(NOT_A_SLOT_ID, null, null);
        return store.suspend(slotId);
    }

    /** Makes a SUSPENDED slot ACTIVE again. The same licence rule as activation applies. */
    public ActivationOutcome resume(String slotId, String actor) {
        requireActor(actor);
        if (!EntityUuid.isSlotId(slotId)) return new ActivationOutcome(NOT_A_SLOT_ID, null, null);
        Optional<ActivationOutcome> refused = licenceRefusal(slotId);
        if (refused.isPresent()) return refused.get();
        return store.resume(slotId);
    }

    /** The refusal when the licence in force does not allow the slot to be used now. */
    private Optional<ActivationOutcome> licenceRefusal(String slotId) {
        Optional<LicensedSlots.Current> current = licence.current();
        if (current.isEmpty()) return Optional.of(new ActivationOutcome(NO_LICENCE, slotId, null));
        Optional<EntityRecord> stored = store.entity(slotId);
        if (!current.get().slotIds().contains(slotId)
                || stored.isPresent() && !stored.get().tenantId().equals(current.get().tenantId())) {
            return Optional.of(new ActivationOutcome(NOT_IN_LICENCE, slotId, stored.orElse(null)));
        }
        return switch (current.get().standing()) {
            case IN_FORCE -> Optional.empty();
            case NOT_STARTED -> Optional.of(new ActivationOutcome(LICENCE_NOT_STARTED, slotId, stored.orElse(null)));
            case GRACE_OVER -> Optional.of(new ActivationOutcome(LICENCE_EXPIRED, slotId, stored.orElse(null)));
        };
    }

    /** Renames an activated slot. The licence is not checked: a name is not a licensed thing. */
    public ActivationOutcome rename(String slotId, String name, String actor) {
        requireActor(actor);
        if (!EntityUuid.isSlotId(slotId)) return new ActivationOutcome(NOT_A_SLOT_ID, null, null);
        Optional<String> clean = cleanName(name);
        if (clean.isEmpty()) return new ActivationOutcome(NAME_INVALID, slotId, null);
        return store.rename(slotId, clean.get());
    }

    /** The name as stored: trimmed; empty when it breaks the name rule. */
    public static Optional<String> cleanName(String name) {
        if (name == null) return Optional.empty();
        String s = name.strip();
        if (s.isEmpty() || s.length() > MAX_NAME_LENGTH) return Optional.empty();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isISOControl(c) || c == '​' || c == '‌' || c == '‍' || c == '⁠'
                    || c == '﻿') {
                return Optional.empty();
            }
        }
        return Optional.of(s);
    }

    private static void requireActor(String actor) {
        if (actor == null || actor.isBlank()) throw new IllegalArgumentException("actor is required");
    }
}
