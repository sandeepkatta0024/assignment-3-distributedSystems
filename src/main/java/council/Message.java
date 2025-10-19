package council;

import java.util.HashMap;
import java.util.Map;

/**
 * Represents a Paxos protocol message exchanged between council members.
 * Uses a simple text-based key=value format for serialization.
 */
public class Message {
    public final MessageType type;
    public final String from;
    public final long n;
    public final String v;
    public final Long acceptedN;
    public final String acceptedV;
    public final Long higherN;

    /**
     * Private constructor - use factory methods to create messages.
     *
     * @param type The type of message
     * @param from The sender's member ID
     * @param n The proposal number
     * @param v The proposed value
     * @param acceptedN Previously accepted proposal number (for PROMISE)
     * @param acceptedV Previously accepted value (for PROMISE)
     * @param higherN Higher proposal number (for REJECT)
     */
    private Message(MessageType type, String from, long n, String v, Long acceptedN, String acceptedV, Long higherN) {
        this.type = type;
        this.from = from;
        this.n = n;
        this.v = v;
        this.acceptedN = acceptedN;
        this.acceptedV = acceptedV;
        this.higherN = higherN;
    }

    /**
     * Creates a PROPOSE message to trigger a new proposal.
     *
     * @param from The sender's member ID
     * @param v The candidate to propose
     * @return A PROPOSE message
     */
    public static Message propose(String from, String v) {
        return new Message(MessageType.PROPOSE, from, -1, v, null, null, null);
    }

    /**
     * Creates a PREPARE message for Phase 1 of Paxos.
     *
     * @param from The proposer's member ID
     * @param n The proposal number
     * @return A PREPARE message
     */
    public static Message prepare(String from, long n) {
        return new Message(MessageType.PREPARE, from, n, null, null, null, null);
    }

    /**
     * Creates a PROMISE message in response to PREPARE.
     *
     * @param from The acceptor's member ID
     * @param n The proposal number being promised
     * @param acceptedN Previously accepted proposal number (null if none)
     * @param acceptedV Previously accepted value (null if none)
     * @return A PROMISE message
     */
    public static Message promise(String from, long n, Long acceptedN, String acceptedV) {
        return new Message(MessageType.PROMISE, from, n, null, acceptedN, acceptedV, null);
    }

    /**
     * Creates a REJECT message when an acceptor rejects a proposal.
     *
     * @param from The acceptor's member ID
     * @param higherN The higher proposal number that caused the rejection
     * @return A REJECT message
     */
    public static Message reject(String from, long higherN) {
        return new Message(MessageType.REJECT, from, -1, null, null, null, higherN);
    }

    /**
     * Creates an ACCEPT_REQUEST message for Phase 2 of Paxos.
     *
     * @param from The proposer's member ID
     * @param n The proposal number
     * @param v The value to accept
     * @return An ACCEPT_REQUEST message
     */
    public static Message acceptRequest(String from, long n, String v) {
        return new Message(MessageType.ACCEPT_REQUEST, from, n, v, null, null, null);
    }

    /**
     * Creates an ACCEPTED message when an acceptor accepts a proposal.
     *
     * @param from The acceptor's member ID
     * @param n The accepted proposal number
     * @param v The accepted value
     * @return An ACCEPTED message
     */
    public static Message accepted(String from, long n, String v) {
        return new Message(MessageType.ACCEPTED, from, n, v, null, null, null);
    }

    /**
     * Creates a DECIDE message to announce the final consensus.
     *
     * @param from The sender's member ID
     * @param v The decided value
     * @return A DECIDE message
     */
    public static Message decide(String from, String v) {
        return new Message(MessageType.DECIDE, from, -1, v, null, null, null);
    }

    /**
     * Serializes this message to a text-based format suitable for sending over TCP.
     * Format: key1=value1;key2=value2;...\n
     *
     * @return The serialized message as a string with newline terminator
     */
    public String serialize() {
        Map<String, String> m = new HashMap<>();
        m.put("type", type.name());
        m.put("from", from);
        if (n >= 0) m.put("n", Long.toString(n));
        if (v != null) m.put("v", v);
        if (acceptedN != null) m.put("acceptedN", Long.toString(acceptedN));
        if (acceptedV != null) m.put("acceptedV", acceptedV);
        if (higherN != null) m.put("higherN", Long.toString(higherN));
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String,String> e : m.entrySet()) {
            if (!first) sb.append(';');
            first = false;
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        sb.append('\n');
        return sb.toString();
    }

    /**
     * Parses a serialized message string back into a Message object.
     *
     * @param line The serialized message line (without trailing newline)
     * @return The parsed Message object
     * @throws IllegalArgumentException if the message format is invalid
     */
    public static Message parse(String line) {
        String[] parts = line.trim().split(";");
        Map<String,String> m = new HashMap<>();
        for (String p : parts) {
            int i = p.indexOf('=');
            if (i > 0) m.put(p.substring(0, i), p.substring(i+1));
        }
        MessageType type = MessageType.valueOf(m.get("type"));
        String from = m.get("from");
        long n = m.containsKey("n") ? Long.parseLong(m.get("n")) : -1L;
        String v = m.get("v");
        Long aN = m.containsKey("acceptedN") ? Long.parseLong(m.get("acceptedN")) : null;
        String aV = m.get("acceptedV");
        Long hN = m.containsKey("higherN") ? Long.parseLong(m.get("higherN")) : null;
        switch (type) {
            case PROPOSE: return propose(from, v);
            case PREPARE: return prepare(from, n);
            case PROMISE: return promise(from, n, aN, aV);
            case REJECT: return reject(from, hN != null ? hN : -1L);
            case ACCEPT_REQUEST: return acceptRequest(from, n, v);
            case ACCEPTED: return accepted(from, n, v);
            case DECIDE: return decide(from, v);
            default: throw new IllegalArgumentException("Unknown type: " + type);
        }
    }
}