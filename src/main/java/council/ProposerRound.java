package council;

import java.util.*;

/**
 * Tracks the state of a single Paxos proposal round for a proposer.
 * Contains information about promises received, values accepted, and the final decision.
 */
public class ProposerRound {
    /** The proposal number for this round */
    public final long n;

    /** The ID of the proposing member */
    public final String myId;

    /** Set of member IDs that have sent PROMISE messages */
    public final Set<String> promisesFrom = new HashSet<>();

    /** Map of member ID to previously accepted proposal numbers from PROMISE messages */
    public final Map<String, Long> acceptedNs = new HashMap<>();

    /** Map of member ID to previously accepted values from PROMISE messages */
    public final Map<String, String> acceptedVs = new HashMap<>();

    /** Set of member IDs that have sent ACCEPTED messages in Phase 2 */
    public final Set<String> acceptedFrom = new HashSet<>();

    /** The value being proposed (may change based on PROMISE responses) */
    public String proposedV;

    /** Whether consensus has been reached for this round */
    public boolean decided = false;

    /** The final decided value (if consensus reached) */
    public String decidedV = null;

    /** The highest proposal number seen in REJECT messages */
    public long highestRejectionN = -1;

    /**
     * Creates a new proposer round with the specified parameters.
     *
     * @param n The proposal number for this round
     * @param myId The member ID of the proposer
     * @param initialV The initial value to propose
     */
    public ProposerRound(long n, String myId, String initialV) {
        this.n = n;
        this.myId = myId;
        this.proposedV = initialV;
    }

    /**
     * Records a PROMISE message from an acceptor.
     * If the acceptor had previously accepted a value, that information is stored
     * to potentially override the initial proposal value.
     *
     * @param from The member ID that sent the PROMISE
     * @param aN The previously accepted proposal number (null if none)
     * @param aV The previously accepted value (null if none)
     */
    public void recordPromise(String from, Long aN, String aV) {
        promisesFrom.add(from);
        if (aN != null && aV != null) {
            acceptedNs.put(from, aN);
            acceptedVs.put(from, aV);
        }
    }

    /**
     * Records a REJECT message with a higher proposal number.
     * This information is used when retrying with a bumped proposal number.
     *
     * @param higherN The higher proposal number that caused the rejection
     */
    public void recordReject(long higherN) {
        if (higherN >= 0) {
            highestRejectionN = Math.max(highestRejectionN, higherN);
        }
    }

    /**
     * Records an ACCEPTED message from an acceptor in Phase 2.
     *
     * @param from The member ID that accepted the proposal
     * @param v The accepted value
     */
    public void recordAccepted(String from, String v) {
        if (v != null) acceptedFrom.add(from);
    }

    /**
     * Checks if a quorum of promises has been received and determines the value to propose.
     * According to Paxos, if any acceptor had previously accepted a value, the proposer
     * must use the value with the highest proposal number.
     *
     * @param quorum The number of acceptors needed for a majority
     * @return Optional containing the value to propose if quorum reached, empty otherwise
     */
    public Optional<String> chooseValueIfQuorum(int quorum) {
        if (promisesFrom.size() >= quorum) {
            long bestN = -1;
            String bestV = null;
            for (Map.Entry<String, Long> e : acceptedNs.entrySet()) {
                Long aN = e.getValue();
                String aV = acceptedVs.get(e.getKey());
                if (aN != null && aV != null && aN > bestN) {
                    bestN = aN;
                    bestV = aV;
                }
            }
            // If any acceptor had previously accepted a value, use that
            if (bestV != null) proposedV = bestV;
            return Optional.of(proposedV);
        }
        return Optional.empty();
    }

    /**
     * Checks if a quorum of acceptances has been received in Phase 2.
     *
     * @param quorum The number of acceptors needed for a majority
     * @return true if quorum of acceptances received, false otherwise
     */
    public boolean reachedAcceptQuorum(int quorum) {
        return acceptedFrom.size() >= quorum;
    }
}