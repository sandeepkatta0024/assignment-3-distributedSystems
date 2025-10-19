package council;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Acceptor state for single-decree Paxos.
 */
public class AcceptorState {
    private static final long NO_ROUND = -1L;

    private final AtomicLong promisedN = new AtomicLong(NO_ROUND);
    private volatile long acceptedN = NO_ROUND;
    private volatile String acceptedV = null;

    // Phase 1: PREPARE
    public synchronized Object onPrepare(long n) {
        long p = promisedN.get();
        if (n > p) {
            promisedN.set(n);
            return new Object[]{true, acceptedN >= 0 ? acceptedN : null, acceptedV};
        } else {
            return new Object[]{false, p}; // return current promised round for reject
        }
    }

    // Phase 2: ACCEPT_REQUEST
    public synchronized Object onAcceptRequest(long n, String v) {
        long p = promisedN.get();
        if (n >= p) { // allow equal n to accept after promise
            promisedN.set(n);
            acceptedN = n;
            acceptedV = v;
            return new Object[]{true, n, v};
        } else {
            return new Object[]{false, p}; // return promised round for reject
        }
    }

    public synchronized Long getAcceptedN() { return acceptedN >= 0 ? acceptedN : null; }
    public synchronized String getAcceptedV() { return acceptedV; }
}
