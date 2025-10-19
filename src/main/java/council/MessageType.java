package council;

public enum MessageType {
    PROPOSE,        // NEW: fields: from, v (triggers proposal)
    PREPARE,
    PROMISE,
    REJECT,
    ACCEPT_REQUEST,
    ACCEPTED,
    DECIDE
}