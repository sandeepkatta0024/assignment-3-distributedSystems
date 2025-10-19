package council;

/**
 * Enumeration of all Paxos message types used in the council election protocol.
 */
public enum MessageType {
    /** Triggers a new proposal (control message from test script) */
    PROPOSE,

    /** Phase 1: Proposer requests promises from acceptors */
    PREPARE,

    /** Phase 1: Acceptor promises not to accept lower-numbered proposals */
    PROMISE,

    /** Acceptor rejects a proposal due to a higher promised number */
    REJECT,

    /** Phase 2: Proposer requests acceptors to accept a value */
    ACCEPT_REQUEST,

    /** Phase 2: Acceptor confirms acceptance of a proposal */
    ACCEPTED,

    /** Final announcement that consensus has been reached */
    DECIDE
}