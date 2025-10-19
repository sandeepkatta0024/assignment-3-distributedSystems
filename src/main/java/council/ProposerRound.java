package council;

import java.util.*;

public class ProposerRound {
    public final long n;
    public final String myId;
    public final Set<String> promisesFrom = new HashSet<>();
    public final Map<String, Long> acceptedNs = new HashMap<>();
    public final Map<String, String> acceptedVs = new HashMap<>();
    public final Set<String> acceptedFrom = new HashSet<>();
    public String proposedV;
    public boolean decided = false;
    public String decidedV = null;
    public long highestRejectionN = -1;

    public ProposerRound(long n, String myId, String initialV) {
        this.n = n;
        this.myId = myId;
        this.proposedV = initialV;
    }

    public void recordPromise(String from, Long aN, String aV) {
        promisesFrom.add(from);
        if (aN != null && aV != null) {
            acceptedNs.put(from, aN);
            acceptedVs.put(from, aV);
        }
    }

    public void recordReject(long higherN) {
        if (higherN >= 0) {
            highestRejectionN = Math.max(highestRejectionN, higherN);
        }
    }

    public void recordAccepted(String from, String v) {
        if (v != null) acceptedFrom.add(from);
    }

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
            if (bestV != null) proposedV = bestV;
            return Optional.of(proposedV);
        }
        return Optional.empty();
    }

    public boolean reachedAcceptQuorum(int quorum) {
        return acceptedFrom.size() >= quorum;
    }
}
