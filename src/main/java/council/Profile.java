package council;

import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Network behavior profiles for simulating different member characteristics.
 */
public enum Profile {
    reliable, latent, failure, standard;

    private static final Random rnd = new Random();
    private final AtomicBoolean hasCrashed = new AtomicBoolean(false);

    /**
     * Simulate network delay before handling incoming messages.
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
     * Determine if a message should be dropped (simulating packet loss).
     * @return true if message should be dropped
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
     * For failure profile: determines if process should crash after sending PREPARE.
     * Made deterministic for testing - crashes on first proposal only.
     * @return true if should crash after sending PREPARE
     */
    public boolean shouldCrashAfterPrepare() {
        if (this == failure) {
            return hasCrashed.compareAndSet(false, true);  // Crash only once
        }
        return false;
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }
}