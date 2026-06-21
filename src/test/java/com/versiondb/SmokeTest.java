package com.versiondb;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Proves the build and JUnit 5 test harness are wired up correctly. */
class SmokeTest {

    @Test
    void harnessRuns() {
        assertEquals(4, 2 + 2);
    }
}
