package com.documentplatform.identity.store;

import com.documentplatform.identity.mapping.Grant;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.documentplatform.identity.store.GrantChange.Kind.*;
import static org.junit.jupiter.api.Assertions.*;

/** P031 B4: which grant history rows one change writes; and who may write them. */
class GrantStepTest {

    @Test
    void addsAndRemovesAreOneRowPerGrant() {
        assertEquals(List.of(new GrantStep("a", null, "READER", ADDED), new GrantStep("b", "EDITOR", null, REMOVED)),
                GrantStep.of(List.of(new Grant("a", "READER")), List.of(new Grant("b", "EDITOR"))));
    }

    @Test
    void oneRoleOutAndOneRoleInForAGroupIsOneChangedRow() {
        assertEquals(List.of(new GrantStep("a", "READER", "EDITOR", CHANGED)),
                GrantStep.of(List.of(new Grant("a", "EDITOR")), List.of(new Grant("a", "READER"))));
    }

    @Test
    void moreRolesForOneGroupStaySeparateRows() {
        List<GrantStep> steps = GrantStep.of(List.of(new Grant("a", "EDITOR"), new Grant("a", "AUDITOR")),
                List.of(new Grant("a", "READER")));
        assertEquals(3, steps.size());
        assertTrue(steps.stream().noneMatch(s -> s.kind() == CHANGED));
        assertEquals(List.of(), GrantStep.of(List.of(), List.of()));
    }

    @Test
    void anAuthorSaysWhoAndSetupNamesItsRun() {
        assertEquals(ChangeSource.APP, GrantAuthor.app("user-1").source());
        assertEquals("run-1", GrantAuthor.setup("setup", "run-1").setupRunId());
        assertThrows(IllegalArgumentException.class, () -> new GrantAuthor(ChangeSource.SETUP, "setup", null));
        assertThrows(IllegalArgumentException.class, () -> new GrantAuthor(ChangeSource.APP, "u", "run-1"));
        assertThrows(IllegalArgumentException.class, () -> new GrantAuthor(ChangeSource.MIGRATION, "u", null));
        assertThrows(IllegalArgumentException.class, () -> GrantAuthor.app(" "));
    }
}
