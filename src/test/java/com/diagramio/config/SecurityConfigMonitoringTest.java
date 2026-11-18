package com.diagramio.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SecurityConfigMonitoringTest {
    @Test
    void requiresConfiguredAndMatchingSecret() {
        assertFalse(SecurityConfig.monitoringKeyMatches(null, "some-key"));
        assertFalse(SecurityConfig.monitoringKeyMatches("", "some-key"));
        assertFalse(SecurityConfig.monitoringKeyMatches("correct-key", null));
        assertFalse(SecurityConfig.monitoringKeyMatches("correct-key", "wrong-key"));
        assertTrue(SecurityConfig.monitoringKeyMatches("correct-key", "correct-key"));
    }
}
