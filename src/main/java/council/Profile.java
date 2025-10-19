package council;

import java.util.Random;

public enum Profile {
    reliable, latent, failure, standard;

    private static final Random rnd = new Random();

    public void delayBeforeHandling() {
        switch (this) {
            case reliable -> {}
            case latent -> sleep(200 + rnd.nextInt(800));     // 200–1000ms
            case standard -> sleep(20 + rnd.nextInt(200));    // 20–220ms
            case failure -> sleep(10 + rnd.nextInt(50));      // light jitter
        }
    }

    public boolean shouldDrop() {
        return switch (this) {
            case reliable -> false;
            case latent -> rnd.nextDouble() < 0.05;
            case standard -> rnd.nextDouble() < 0.02;
            case failure -> rnd.nextDouble() < 0.25;          // simulate message loss
        };
    }

    public boolean shouldCrashAfterPrepare() {
        return this == failure && rnd.nextDouble() < 0.5;
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }
}
