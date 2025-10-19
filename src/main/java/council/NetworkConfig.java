package council;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Loads and stores network configuration mapping member IDs to host:port addresses.
 * Configuration file format: memberId,hostname,port (one per line)
 */
public class NetworkConfig {
    /**
     * Represents a single network configuration entry with host and port.
     */
    public static class Entry {
        public final String host;
        public final int port;

        /**
         * Creates a new network configuration entry.
         *
         * @param h The hostname or IP address
         * @param p The port number
         */
        Entry(String h, int p) {
            host = h;
            port = p;
        }
    }

    /** Map from member ID to network configuration entry */
    public final Map<String, Entry> map = new LinkedHashMap<>();

    /**
     * Loads network configuration from a file.
     * Expected format: memberId,hostname,port (e.g., M1,localhost,9001)
     * Lines starting with # are treated as comments and ignored.
     *
     * @param path The path to the configuration file
     * @return A NetworkConfig object containing the parsed configuration
     * @throws Exception if the file cannot be read or has invalid format
     */
    public static NetworkConfig load(String path) throws Exception {
        NetworkConfig cfg = new NetworkConfig();
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] parts = line.split(",");
                if (parts.length != 3) throw new IllegalArgumentException("Bad config line: " + line);
                cfg.map.put(parts[0].trim(), new Entry(parts[1].trim(), Integer.parseInt(parts[2].trim())));
            }
        }
        return cfg;
    }
}