package com.documentplatform.licensing.usage;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UsageLevelTest {

    @ParameterizedTest(name = "{0} of {1} -> {2}")
    @CsvSource({
            "0, 100, WITHIN", "79, 100, WITHIN", "80, 100, NEAR_LIMIT", "99, 100, NEAR_LIMIT", "100, 100, AT_LIMIT",
            "101, 100, OVER_LIMIT", "100000, 100, OVER_LIMIT",
            "5, 7, WITHIN", "6, 7, NEAR_LIMIT", "7, 7, AT_LIMIT", "8, 7, OVER_LIMIT",
            "0, 1, WITHIN", "1, 1, AT_LIMIT",
            "0, 0, WITHIN", "1, 0, OVER_LIMIT",
            "7378697629483820645, 9223372036854775807, WITHIN",
            "7378697629483820646, 9223372036854775807, NEAR_LIMIT"})
    void levels(long used, long limit, UsageLevel level) {
        assertEquals(level, UsageLevel.of(used, limit));
    }
}
