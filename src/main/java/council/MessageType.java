package council;

public enum MessageType {
    PREPARE,        // fields: from, n
    PROMISE,        // fields: from, n, acceptedN, acceptedV
    REJECT,         // fields: from, higherN
    ACCEPT_REQUEST, // fields: from, n, v
    ACCEPTED,       // fields: from, n, v
    DECIDE,         // fields: from, v
    PING,
    PONG
}