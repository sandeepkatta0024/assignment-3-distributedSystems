package council;

import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Defines network behavior profiles for simulating different member characteristics.
 * Each profile simulates different network conditions (latency, packet loss, failures).
 */
public enum Profile {
    /** No delays, no packet loss - ideal network conditions */
    reliable,

    /** High latency (200-1000ms), occasional packet loss (5%) */
    latent,

    /** Crashes after first PREPARE, high packet loss (25%) */
    failure,

    /** Moderate latency (20-220ms), minimal packet loss (2%) */
    standard;

    private static final Random rnd = new Random();
    private final AtomicBoolean hasCrashed = new AtomicBoolean(false);

    /**
     * Simulates network delay before handling an incoming message.
     * The delay varies based on the profile type.
     */
    public void delayBeforeHandling() {
        switch (this) {
            case reliable -> {}
            case latent -> sleep(200 + rnd.nextInt(800));
            case standard -> sleep(20 + rnd.nextInt(200));
            case failure -> sleep(10 + rnd.nextInt(50));
        }
    }

    /**
     * Determines if an incoming message should be dropped (simulating packet loss).
     *
     * @return true if the message should be dropped, false otherwise
     */
    public boolean shouldDrop() {
        return switch (this) {
            case reliable -> false;
            case latent -> rnd.nextDouble() < 0.05;
            case standard -> rnd.nextDouble() < 0.02;
            case failure -> rnd.nextDouble() < 0.25;
        };
    }

    /**
     * For failure profile only: determines if the process should crash after sending PREPARE.
     * This is deterministic - crashes on the first proposal only to ensure testable behavior.
     *
     * @return true if the process should crash after sending PREPARE, false otherwise
     */
    public boolean shouldCrashAfterPrepare() {
        if (this == failure) {
            return hasCrashed.compareAndSet(false, true);
        }
        return false;
    }

    /**
     * Helper method to sleep for a specified duration.
     *
     * @param ms The number of milliseconds to sleep
     */
    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }
}