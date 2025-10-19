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
 * Single-decree Paxos participant implementing proposer, acceptor, and learner roles.
 */
public class CouncilMember {
    private static final int MEMBERS = 9;
    private static final int QUORUM = MEMBERS / 2 + 1; // 5
    private static final long PREPARE_TIMEOUT_MS = 2500;
    private static final long ACCEPT_TIMEOUT_MS = 2500;
    private static final long NO_ROUND = -1L;
    private static final long PROPOSAL_STRIDE = 100L;

    private final String myId;
    private final Profile profile;
    private final NetworkConfig cfg;

    // acceptor
    private final AcceptorState acceptor = new AcceptorState();

    // proposer
    private final ScheduledExecutorService sched = Executors.newScheduledThreadPool(2);
    private final ExecutorService workers = Executors.newFixedThreadPool(4);
    private final Object proposerLock = new Object();
    private long localCounter = 0;
    private ProposerRound currentRound = null;

    // learner
    private volatile String decidedValue = null;
    private final Set<String> relayedDecisions = ConcurrentHashMap.newKeySet();

    public CouncilMember(String myId, Profile profile, NetworkConfig cfg) {
        this.myId = myId;
        this.profile = profile;
        this.cfg = cfg;
    }

    private long nextProposalNumber() {
        int idNum = Integer.parseInt(myId.substring(1));
        localCounter++;
        return localCounter * PROPOSAL_STRIDE + idNum;
    }

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

    private void handleConnection(Socket s) {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(s.getInputStream()))) {
            String line = br.readLine();
            if (line == null) return;
            profile.delayBeforeHandling();
            if (profile.shouldDrop()) return;
            Message m = Message.parse(line);
            switch (m.type) {
                case PROPOSE -> onProposeRequest(m);  // NEW
                case PREPARE -> onPrepare(m);
                case PROMISE -> onPromise(m);
                case REJECT -> onReject(m);
                case ACCEPT_REQUEST -> onAcceptRequest(m);
                case ACCEPTED -> onAccepted(m);
                case DECIDE -> onDecide(m);
                default -> {}
            }
        } catch (Exception e) {
            // Log parse errors for debugging
            log("ERROR parsing message: " + e.getMessage());
        }
    }

    /**
     * Handle incoming PROPOSE request from external trigger.
     * @param m The PROPOSE message containing candidate value
     */
    private void onProposeRequest(Message m) {
        log("Received PROPOSE request for candidate: " + m.v);
        propose(m.v);
    }

    /**
     * Initiate Phase 1 for a value.
     * @param value The candidate to propose for election
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

            // Crash simulation for failure profile
            if (profile.shouldCrashAfterPrepare()) {
                log("CRASH: Failure profile terminating after PREPARE");
                // Schedule crash after a brief delay to allow PREPARE messages to be sent
                sched.schedule(() -> {
                    log("EXITING NOW");
                    System.exit(1);
                }, 100, TimeUnit.MILLISECONDS);
                return; // Don't schedule timeout - we're crashing
            }

            schedulePrepareTimeout(n);
        }
    }

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

    private void onReject(Message m) {
        if (m.higherN != null && m.higherN >= 0) {
            onRejectedCore(m.higherN);
        }
    }

    private void onRejectedCore(long higherN) {
        synchronized (proposerLock) {
            if (currentRound == null || decidedValue != null) return;
            currentRound.recordReject(higherN);
        }
    }

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

    private void onDecide(Message m) {
        decideLocal(m.v);
        if (relayedDecisions.add(m.v)) {
            broadcast(Message.decide(myId, m.v));
            logSend("BROADCAST", MessageType.DECIDE, "*", NO_ROUND, m.v, null, null, null);
        }
        log("LEARN v=" + m.v);
    }

    private void decideLocal(String v) {
        if (decidedValue == null) {
            decidedValue = v;
            System.out.printf("CONSENSUS: %s has been elected Council President!%n", v);
        }
    }

    private void broadcast(Message m) {
        for (String id : cfg.map.keySet()) {
            if (!id.equals(myId)) {
                NetUtil.sendTo(id, cfg, m);
            }
        }
    }

    private void sendTo(String id, Message m) {
        NetUtil.sendTo(id, cfg, m);
    }

    private void log(String msg) {
        System.out.printf("[%s] %s %s%n", myId, Instant.now(), msg);
    }

    private void logSend(String action, MessageType t, String to, long n, String v,
                         Long aN, String aV, Long higherN) {
        System.out.printf("[%s] %s %s to=%s n=%s v=%s aN=%s aV=%s higherN=%s @%s%n",
                myId, action, t, to,
                n >= 0 ? n : "-", v != null ? v : "-",
                aN != null ? aN : "-", aV != null ? aV : "-",
                higherN != null ? higherN : "-",
                Instant.now());
    }

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