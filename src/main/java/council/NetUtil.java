package council;

import java.io.OutputStream;
import java.net.Socket;

/**
 * Utility class for sending messages over TCP to other council members.
 */
public class NetUtil {
    /**
     * Sends a message to a specific host and port via TCP.
     * Sets TCP_NODELAY for immediate sending and a 2-second timeout.
     *
     * @param host The hostname or IP address
     * @param port The port number
     * @param m The message to send
     */
    public static void send(String host, int port, Message m) {
        try (Socket s = new Socket(host, port)) {
            s.setTcpNoDelay(true);
            s.setSoTimeout(2000);
            OutputStream os = s.getOutputStream();
            os.write(m.serialize().getBytes());
            os.flush();
        } catch (Exception ignored) {
            // Silently ignore network errors (simulates unreliable network)
        }
    }

    /**
     * Sends a message to a council member by looking up their address in the config.
     *
     * @param memberId The target member's ID (e.g., "M1", "M2")
     * @param cfg The network configuration containing member addresses
     * @param m The message to send
     */
    public static void sendTo(String memberId, NetworkConfig cfg, Message m) {
        NetworkConfig.Entry e = cfg.map.get(memberId);
        if (e != null) send(e.host, e.port, m);
    }
}
