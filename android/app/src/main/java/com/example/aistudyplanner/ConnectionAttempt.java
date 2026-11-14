package com.example.aistudyplanner;

/** UI-thread state: callbacks from timed-out, replaced or destroyed attempts cannot launch a tab. */
final class ConnectionAttempt {
    private long generation;
    private boolean pending;
    long begin() { pending = true; return ++generation; }
    boolean isCurrent(long id) { return pending && id == generation; }
    boolean finish(long id) {
        if (!isCurrent(id)) return false;
        pending = false;
        return true;
    }
    void cancel() { pending = false; generation++; }
    static boolean reachableStatus(int code) {
        // An access gateway may require browser authentication. Missing/rate-limited/error pages are not ready.
        return (code >= 200 && code < 400) || code == 401 || code == 403;
    }
}
