package com.example.aistudyplanner;

import org.junit.Test;
import static org.junit.Assert.*;

public class ConnectionAttemptTest {
    @Test public void completedAttemptCannotLaunchTwice() {
        var gate = new ConnectionAttempt(); long id = gate.begin();
        assertTrue(gate.finish(id)); assertFalse(gate.finish(id));
    }
    @Test public void retryRejectsOldNetworkOrBrowserCallback() {
        var gate = new ConnectionAttempt(); long old = gate.begin(), retry = gate.begin();
        assertFalse(gate.isCurrent(old)); assertFalse(gate.finish(old)); assertTrue(gate.finish(retry));
    }
    @Test public void timeoutRejectsLateSuccess() {
        var gate = new ConnectionAttempt(); long id = gate.begin();
        assertTrue(gate.finish(id)); assertFalse(gate.isCurrent(id));
        assertTrue(gate.finish(gate.begin()));
    }
    @Test public void destructionOrBrowserFallbackCancelsPendingAttempt() {
        var gate = new ConnectionAttempt(); long id = gate.begin();
        gate.cancel(); assertFalse(gate.finish(id)); assertTrue(gate.isCurrent(gate.begin()));
    }
    @Test public void serverErrorsAndMissingLoginPageAreNotReady() {
        for (int code : new int[]{0, 404, 429, 500, 503}) assertFalse(ConnectionAttempt.reachableStatus(code));
        for (int code : new int[]{200, 204, 302, 401, 403}) assertTrue(ConnectionAttempt.reachableStatus(code));
    }
}
