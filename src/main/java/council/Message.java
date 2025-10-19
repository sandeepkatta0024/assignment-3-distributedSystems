package council;

import java.util.HashMap;
import java.util.Map;

public class Message {
    public final MessageType type;
    public final String from;
    public final long n;
    public final String v;
    public final Long acceptedN;
    public final String acceptedV;
    public final Long higherN;

    private Message(MessageType type, String from, long n, String v, Long acceptedN, String acceptedV, Long higherN) {
        this.type = type;
        this.from = from;
        this.n = n;
        this.v = v;
        this.acceptedN = acceptedN;
        this.acceptedV = acceptedV;
        this.higherN = higherN;
    }

    // NEW: Create a PROPOSE message
    public static Message propose(String from, String v) {
        return new Message(MessageType.PROPOSE, from, -1, v, null, null, null);
    }

    public static Message prepare(String from, long n) {
        return new Message(MessageType.PREPARE, from, n, null, null, null, null);
    }
    public static Message promise(String from, long n, Long acceptedN, String acceptedV) {
        return new Message(MessageType.PROMISE, from, n, null, acceptedN, acceptedV, null);
    }
    public static Message reject(String from, long higherN) {
        return new Message(MessageType.REJECT, from, -1, null, null, null, higherN);
    }
    public static Message acceptRequest(String from, long n, String v) {
        return new Message(MessageType.ACCEPT_REQUEST, from, n, v, null, null, null);
    }
    public static Message accepted(String from, long n, String v) {
        return new Message(MessageType.ACCEPTED, from, n, v, null, null, null);
    }
    public static Message decide(String from, String v) {
        return new Message(MessageType.DECIDE, from, -1, v, null, null, null);
    }

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
            case PROPOSE: return propose(from, v);  // NEW
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