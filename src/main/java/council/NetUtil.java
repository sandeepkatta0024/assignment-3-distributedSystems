package council;

import java.io.OutputStream;
import java.net.Socket;

public class NetUtil {
    public static void send(String host, int port, Message m) {
        try (Socket s = new Socket(host, port)) {
            s.setTcpNoDelay(true);
            s.setSoTimeout(2000);
            OutputStream os = s.getOutputStream();
            os.write(m.serialize().getBytes());
            os.flush();
        } catch (Exception ignored) {}
    }

    public static void sendTo(String memberId, NetworkConfig cfg, Message m) {
        NetworkConfig.Entry e = cfg.map.get(memberId);
        if (e != null) send(e.host, e.port, m);
    }
}
