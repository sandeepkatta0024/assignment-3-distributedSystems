# Adelaide Suburbs Council Election - Paxos Implementation

## Overview
This project implements the Paxos consensus algorithm for the Adelaide Suburbs Council presidential election. Nine council members (M1-M9) use distributed consensus to elect a single president, even in the presence of network delays and failures.

## Prerequisites
- Java 17 or higher
- Unix-like environment (macOS, Linux) for shell script execution

## Project Structure
```
src/main/java/council/
├── CouncilMember.java      # Main member implementation (proposer/acceptor/learner)
├── Message.java            # Message serialization/deserialization
├── MessageType.java        # Paxos message types enum
├── AcceptorState.java      # Acceptor state management
├── ProposerRound.java      # Proposer round state tracking
├── Profile.java            # Network behavior profiles
├── NetUtil.java            # Network utility functions
└── NetworkConfig.java      # Configuration loader
network.config              # Member network configuration (ports 9001-9009)
run_tests.sh               # Automated test harness
```

## How to Compile
```bash
javac -d target/classes src/main/java/council/*.java
```

## How to Run Tests
The test script automatically runs all required scenarios:
```bash
./run_tests.sh
```

**Expected output**: Each scenario will print "✅ PASSED" with consensus messages from all 9 members.

## Test Scenarios

### Scenario 1 - Ideal Network
- **Setup**: All 9 members with reliable profile
- **Test**: M4 proposes M5
- **Expected**: M5 elected with unanimous consensus

### Scenario 2 - Concurrent Proposals
- **Setup**: All 9 members with reliable profile
- **Test**: M1 proposes M1, M8 proposes M8 simultaneously
- **Expected**: Single winner elected (M1 or M8) through Paxos conflict resolution

### Scenario 3a - Standard Member Proposes
- **Setup**: M1=reliable, M2=latent, M3=failure, M4-M9=standard
- **Test**: M4 proposes M1
- **Expected**: M1 elected despite network delays

### Scenario 3b - Latent Member Proposes
- **Setup**: Mixed profiles
- **Test**: M2 (latent) proposes M2
- **Expected**: M2 elected despite high latency (200-1000ms)

### Scenario 3c - Failing Member Crashes
- **Setup**: Mixed profiles
- **Test**: M3 (failure) proposes M3 and crashes, M1 recovers
- **Expected**: M1 elected, demonstrating fault tolerance

## Message Protocol Design

### Format
Text-based key=value format separated by semicolons:
```
type=PREPARE;from=M1;n=101
type=PROMISE;from=M2;n=101;acceptedN=50;acceptedV=M5
type=ACCEPT_REQUEST;from=M1;n=101;v=M5
```

### Message Types
- **PROPOSE**: Triggers a new proposal (control message from test script)
- **PREPARE**: Phase 1 - proposer requests promises from acceptors
- **PROMISE**: Phase 1 - acceptor promises not to accept lower proposals
- **REJECT**: Acceptor rejects due to higher promised number
- **ACCEPT_REQUEST**: Phase 2 - proposer requests acceptance
- **ACCEPTED**: Phase 2 - acceptor confirms acceptance
- **DECIDE**: Final consensus announcement

## Proposal Number Generation
**Format**: `counter * 100 + memberId`

**Example**:
- M1's first proposal: 101 (1 * 100 + 1)
- M2's first proposal: 102 (1 * 100 + 2)
- M1's second proposal: 201 (2 * 100 + 1)

**Benefits**:
- Ensures globally unique, monotonically increasing numbers
- Deterministic tie-breaking by member ID
- Simple comparison: 101 < 102 < 201

## Network Configuration
File: `network.config`
```
M1,localhost,9001
M2,localhost,9002
...
M9,localhost,9009
```

## Network Behavior Profiles

| Profile | Latency | Packet Loss | Special Behavior |
|---------|---------|-------------|------------------|
| reliable | 0ms | 0% | Ideal network |
| standard | 20-220ms | 2% | Normal network |
| latent | 200-1000ms | 5% | Poor connection |
| failure | 10-60ms | 25% | Crashes after first PREPARE |

## Key Design Decisions

### Communication
- **TCP Sockets**: Each member listens on unique port (9001-9009)
- **Point-to-point**: Direct connections between members
- **Fire-and-forget**: Network errors silently ignored to simulate unreliable network

### Fault Tolerance
- **Timeouts**: 2.5s for both PREPARE and ACCEPT phases
- **Automatic retry**: Bumps proposal number on timeout or rejection
- **Crash recovery**: Other members can initiate new proposals
- **DECIDE gossip**: Members relay DECIDE messages to help late learners

### Paxos Implementation
- **Single-decree**: One value decided per execution
- **Quorum**: 5 out of 9 members (majority)
- **Value selection**: If any acceptor previously accepted a value, proposer must use the value with highest proposal number
- **Optimization**: Acceptors immediately respond with DECIDE if already decided

### Concurrency
- **Thread-safe**: Synchronized access to shared state
- **Atomic operations**: AtomicLong for proposal numbers
- **Executor services**: Separate thread pools for message handling and timeouts

## Output Files
After running tests:
- `logs/M1.log` through `logs/M9.log`: Individual member logs
- Console output shows test results and consensus messages

## Troubleshooting

### Port Already in Use
If you see "Address already in use" errors:
```bash
# Kill any existing processes
pkill -9 -f "council.CouncilMember"

# Wait for ports to be released
sleep 2

# Run tests again
./run_tests.sh
```
