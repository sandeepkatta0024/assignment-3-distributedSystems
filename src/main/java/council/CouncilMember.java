package council;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Represents a single council member participating in the Adelaide Suburbs Council election.
 * Each member acts as a proposer, acceptor, and learner in the Paxos consensus algorithm.
 * Members communicate via TCP sockets and can have different network behaviors (profiles).
 */
public class CouncilMember {
    private static final int MEMBERS = 9;
    private static final int QUORUM = MEMBERS / 2 + 1; // 5 members needed for majority
    private static final long PREPARE_TIMEOUT_MS = 2500;
    private static final long ACCEPT_TIMEOUT_MS = 2500;
    private static final long NO_ROUND = -1L;
    private static final long PROPOSAL_STRIDE = 100L;

    private final String myId;
    private final Profile profile;
    private final NetworkConfig cfg;

    // Acceptor state
    private final AcceptorState acceptor = new AcceptorState();

    // Proposer state
    private final ScheduledExecutorService sched = Executors.newScheduledThreadPool(2);
    private final ExecutorService workers = Executors.newFixedThreadPool(4);
    private final Object proposerLock = new Object();
    private long localCounter = 0;
    private ProposerRound currentRound = null;

    // Learner state
    private volatile String decidedValue = null;
    private final Set<String> relayedDecisions = ConcurrentHashMap.newKeySet();

    /**
     * Creates a new council member with specified ID, network behavior profile, and configuration.
     *
     * @param myId The unique identifier for this member (e.g., "M1", "M2")
     * @param profile The network behavior profile (reliable, latent, failure, or standard)
     * @param cfg The network configuration mapping member IDs to host:port addresses
     */
    public CouncilMember(String myId, Profile profile, NetworkConfig cfg) {
        this.myId = myId;
        this.profile = profile;
        this.cfg = cfg;
    }

    /**
     * Generates a unique, monotonically increasing proposal number for this member.
     * The format is: (counter * 100) + memberNumericId
     * This ensures total ordering with tie-breaking by member ID.
     *
     * @return A unique proposal number greater than all previous ones from this member
     */
    private long nextProposalNumber() {
        int idNum = Integer.parseInt(myId.substring(1));
        localCounter++;
        return localCounter * PROPOSAL_STRIDE + idNum;
    }

    /**
     * Starts the council member server, listening for incoming messages on the configured port.
     * Creates a daemon thread to accept incoming socket connections and process messages.
     *
     * @throws Exception if the server cannot bind to the configured port
     */
    public void start() throws Exception {
        NetworkConfig.Entry me = cfg.map.get(myId);
        if (me == null) throw new IllegalArgumentException("No config for " + myId);

        ServerSocket server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress(me.port));

        Thread acceptThread = new Thread(() -> {
            while (true) {
                try {
                    Socket s = server.accept();
                    workers.submit(() -> handleConnection(s));
                } catch (IOException e) { break; }
            }
        }, "listener-" + myId);
        acceptThread.setDaemon(true);
        acceptThread.start();

        System.out.printf("%s listening on %d at %s%n", myId, me.port, Instant.now());
    }

    /**
     * Handles an incoming socket connection by reading and processing a single message.
     * Simulates network delays and packet loss based on the member's profile.
     *
     * @param s The socket connection to handle
     */
    private void handleConnection(Socket s) {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(s.getInputStream()))) {
            String line = br.readLine();
            if (line == null) return;
            profile.delayBeforeHandling();
            if (profile.shouldDrop()) return;
            Message m = Message.parse(line);
            switch (m.type) {
                case PROPOSE -> onProposeRequest(m);
                case PREPARE -> onPrepare(m);
                case PROMISE -> onPromise(m);
                case REJECT -> onReject(m);
                case ACCEPT_REQUEST -> onAcceptRequest(m);
                case ACCEPTED -> onAccepted(m);
                case DECIDE -> onDecide(m);
                default -> {}
            }
        } catch (Exception e) {
            log("ERROR parsing message: " + e.getMessage());
        }
    }

    /**
     * Handles an incoming PROPOSE message from an external trigger (e.g., test script).
     * This initiates a new Paxos round with the specified candidate value.
     *
     * @param m The PROPOSE message containing the candidate to nominate
     */
    private void onProposeRequest(Message m) {
        log("Received PROPOSE request for candidate: " + m.v);
        propose(m.v);
    }

    /**
     * Initiates Phase 1 of the Paxos algorithm by broadcasting PREPARE messages.
     * If this member has the failure profile, it may crash after sending PREPARE.
     *
     * @param value The candidate value to propose (e.g., "M5")
     */
    public void propose(String value) {
        synchronized (proposerLock) {
            if (decidedValue != null) {
                System.out.printf("%s: already decided %s, ignoring new proposal%n", myId, decidedValue);
                return;
            }
            long n = nextProposalNumber();
            currentRound = new ProposerRound(n, myId, value);
            broadcast(Message.prepare(myId, n));
            logSend("BROADCAST", MessageType.PREPARE, "*", n, value, null, null, null);

            // Simulate crash for failure profile
            if (profile.shouldCrashAfterPrepare()) {
                log("CRASH: Failure profile terminating after PREPARE");
                sched.schedule(() -> {
                    log("EXITING NOW");
                    System.exit(1);
                }, 100, TimeUnit.MILLISECONDS);
                return;
            }

            schedulePrepareTimeout(n);
        }
    }

    /**
     * Schedules a timeout for Phase 1 (PREPARE). If quorum is not reached within the timeout,
     * the proposer retries with a higher proposal number.
     *
     * @param n The proposal number to monitor
     */
    private void schedulePrepareTimeout(long n) {
        sched.schedule(() -> {
            synchronized (proposerLock) {
                if (currentRound == null || currentRound.n != n || decidedValue != null) return;
                if (currentRound.promisesFrom.size() < QUORUM) {
                    long bump = Math.max(currentRound.highestRejectionN + 1, n + PROPOSAL_STRIDE);
                    localCounter = Math.max(localCounter, bump / PROPOSAL_STRIDE);
                    log("PREPARE timeout; retry with higher n");
                    long jitter = ThreadLocalRandom.current().nextInt(50, 200);
                    sched.schedule(() -> propose(currentRound.proposedV), jitter, TimeUnit.MILLISECONDS);
                }
            }
        }, PREPARE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * Schedules a timeout for Phase 2 (ACCEPT). If quorum is not reached within the timeout,
     * the proposer retries with a higher proposal number.
     *
     * @param n The proposal number to monitor
     */
    private void scheduleAcceptTimeout(long n) {
        sched.schedule(() -> {
            synchronized (proposerLock) {
                if (currentRound == null || currentRound.n != n || decidedValue != null) return;
                if (!currentRound.reachedAcceptQuorum(QUORUM)) {
                    long bump = Math.max(currentRound.highestRejectionN + 1, n + PROPOSAL_STRIDE);
                    localCounter = Math.max(localCounter, bump / PROPOSAL_STRIDE);
                    log("ACCEPT timeout; retry with higher n");
                    long jitter = ThreadLocalRandom.current().nextInt(50, 200);
                    sched.schedule(() -> propose(currentRound.proposedV), jitter, TimeUnit.MILLISECONDS);
                }
            }
        }, ACCEPT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * Handles an incoming PREPARE message as an acceptor in Phase 1.
     * If a decision has already been reached, sends DECIDE to inform the proposer.
     * Otherwise, responds with PROMISE if the proposal number is acceptable, or REJECT if not.
     *
     * @param m The PREPARE message from a proposer
     */
    private void onPrepare(Message m) {
        if (decidedValue != null) {
            sendTo(m.from, Message.decide(myId, decidedValue));
            logSend("SEND", MessageType.DECIDE, m.from, NO_ROUND, decidedValue, null, null, null);
            return;
        }
        Object[] res = (Object[]) acceptor.onPrepare(m.n);
        boolean ok = (Boolean) res[0];
        if (ok) {
            Long aN = (Long) res[1];
            String aV = (String) res[2];
            sendTo(m.from, Message.promise(myId, m.n, aN, aV));
            logSend("SEND", MessageType.PROMISE, m.from, m.n, null, aN, aV, null);
        } else {
            long higher = (Long) res[1];
            sendTo(m.from, Message.reject(myId, higher));
            logSend("SEND", MessageType.REJECT, m.from, NO_ROUND, null, null, null, higher);
        }
    }

    /**
     * Handles an incoming ACCEPT_REQUEST message as an acceptor in Phase 2.
     * If a decision has already been reached, sends DECIDE to inform the proposer.
     * Otherwise, accepts or rejects the proposal and broadcasts ACCEPTED if successful.
     *
     * @param m The ACCEPT_REQUEST message from a proposer
     */
    private void onAcceptRequest(Message m) {
        if (decidedValue != null) {
            sendTo(m.from, Message.decide(myId, decidedValue));
            logSend("SEND", MessageType.DECIDE, m.from, NO_ROUND, decidedValue, null, null, null);
            return;
        }
        Object[] res = (Object[]) acceptor.onAcceptRequest(m.n, m.v);
        boolean ok = (Boolean) res[0];
        if (ok) {
            broadcast(Message.accepted(myId, m.n, m.v));
            logSend("BROADCAST", MessageType.ACCEPTED, "*", m.n, m.v, null, null, null);
            synchronized (proposerLock) {
                if (currentRound != null && currentRound.n == m.n) {
                    currentRound.recordAccepted(myId, m.v);
                    if (!currentRound.decided && currentRound.reachedAcceptQuorum(QUORUM)) {
                        currentRound.decided = true;
                        currentRound.decidedV = m.v;
                        broadcast(Message.decide(myId, m.v));
                        decideLocal(m.v);
                        logSend("BROADCAST", MessageType.DECIDE, "*", NO_ROUND, m.v, null, null, null);
                    }
                }
            }
        } else {
            long higher = (Long) res[1];
            sendTo(m.from, Message.reject(myId, higher));
            logSend("SEND", MessageType.REJECT, m.from, NO_ROUND, null, null, null, higher);
        }
    }

    /**
     * Handles an incoming PROMISE message from an acceptor in Phase 1.
     * Records the promise and checks if quorum has been reached to proceed to Phase 2.
     *
     * @param m The PROMISE message from an acceptor
     */
    private void onPromise(Message m) {
        synchronized (proposerLock) {
            if (currentRound == null || m.n != currentRound.n || decidedValue != null) return;
            currentRound.recordPromise(m.from, m.acceptedN, m.acceptedV);
            Optional<String> val = currentRound.chooseValueIfQuorum(QUORUM);
            val.ifPresent(v -> {
                broadcast(Message.acceptRequest(myId, currentRound.n, v));
                scheduleAcceptTimeout(currentRound.n);
                logSend("BROADCAST", MessageType.ACCEPT_REQUEST, "*", currentRound.n, v, null, null, null);
            });
        }
    }

    /**
     * Handles an incoming REJECT message from an acceptor.
     * Records the higher proposal number to use when retrying.
     *
     * @param m The REJECT message from an acceptor
     */
    private void onReject(Message m) {
        if (m.higherN != null && m.higherN >= 0) {
            onRejectedCore(m.higherN);
        }
    }

    /**
     * Records a rejection with a higher proposal number for future retry attempts.
     *
     * @param higherN The higher proposal number that caused the rejection
     */
    private void onRejectedCore(long higherN) {
        synchronized (proposerLock) {
            if (currentRound == null || decidedValue != null) return;
            currentRound.recordReject(higherN);
        }
    }

    /**
     * Handles an incoming ACCEPTED message from an acceptor in Phase 2.
     * Records the acceptance and checks if quorum has been reached to finalize the decision.
     *
     * @param m The ACCEPTED message from an acceptor
     */
    private void onAccepted(Message m) {
        synchronized (proposerLock) {
            if (currentRound != null && m.n == currentRound.n) {
                currentRound.recordAccepted(m.from, m.v);
                if (!currentRound.decided && currentRound.reachedAcceptQuorum(QUORUM)) {
                    currentRound.decided = true;
                    currentRound.decidedV = m.v;
                    broadcast(Message.decide(myId, m.v));
                    decideLocal(m.v);
                    logSend("BROADCAST", MessageType.DECIDE, "*", NO_ROUND, m.v, null, null, null);
                }
            }
        }
    }

    /**
     * Handles an incoming DECIDE message as a learner.
     * Records the final decision and relays it to other members to ensure consistency.
     *
     * @param m The DECIDE message containing the final elected value
     */
    private void onDecide(Message m) {
        decideLocal(m.v);
        if (relayedDecisions.add(m.v)) {
            broadcast(Message.decide(myId, m.v));
            logSend("BROADCAST", MessageType.DECIDE, "*", NO_ROUND, m.v, null, null, null);
        }
        log("LEARN v=" + m.v);
    }

    /**
     * Records the final consensus decision locally and prints the result.
     * This is called only once when consensus is reached.
     *
     * @param v The candidate that has been elected as council president
     */
    private void decideLocal(String v) {
        if (decidedValue == null) {
            decidedValue = v;
            System.out.printf("CONSENSUS: %s has been elected Council President!%n", v);
        }
    }

    /**
     * Broadcasts a message to all other council members (excluding self).
     *
     * @param m The message to broadcast
     */
    private void broadcast(Message m) {
        for (String id : cfg.map.keySet()) {
            if (!id.equals(myId)) {
                NetUtil.sendTo(id, cfg, m);
            }
        }
    }

    /**
     * Sends a message to a specific council member.
     *
     * @param id The member ID to send to
     * @param m The message to send
     */
    private void sendTo(String id, Message m) {
        NetUtil.sendTo(id, cfg, m);
    }

    /**
     * Logs a general message with timestamp.
     *
     * @param msg The message to log
     */
    private void log(String msg) {
        System.out.printf("[%s] %s %s%n", myId, Instant.now(), msg);
    }

    /**
     * Logs a detailed message send/broadcast event with all Paxos parameters.
     *
     * @param action The action type (SEND, BROADCAST)
     * @param t The message type
     * @param to The recipient (or "*" for broadcast)
     * @param n The proposal number
     * @param v The proposed value
     * @param aN The previously accepted proposal number (for PROMISE)
     * @param aV The previously accepted value (for PROMISE)
     * @param higherN The higher proposal number (for REJECT)
     */
    private void logSend(String action, MessageType t, String to, long n, String v,
                         Long aN, String aV, Long higherN) {
        System.out.printf("[%s] %s %s to=%s n=%s v=%s aN=%s aV=%s higherN=%s @%s%n",
                myId, action, t, to,
                n >= 0 ? n : "-", v != null ? v : "-",
                aN != null ? aN : "-", aV != null ? aV : "-",
                higherN != null ? higherN : "-",
                Instant.now());
    }

    /**
     * Main entry point for launching a council member process.
     * Expects command-line arguments: memberId --profile profileName [--config configPath]
     *
     * @param args Command-line arguments
     * @throws Exception if member cannot be started
     */
    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("Usage: java council.CouncilMember <MemberId> --profile <reliable|latent|failure|standard> [--config network.config]");
            System.exit(1);
        }
        String myId = args[0];
        Profile profile = Profile.reliable;
        String configPath = "network.config";
        for (int i = 1; i < args.length; i++) {
            if ("--profile".equals(args[i]) && i+1 < args.length) {
                profile = Profile.valueOf(args[++i]);
            } else if ("--config".equals(args[i]) && i+1 < args.length) {
                configPath = args[++i];
            }
        }
        NetworkConfig cfg = NetworkConfig.load(configPath);
        CouncilMember m = new CouncilMember(myId, profile, cfg);
        m.start();
        Thread.currentThread().join();
    }
}