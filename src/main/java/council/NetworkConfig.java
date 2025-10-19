package council;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.LinkedHashMap;
import java.util.Map;

public class NetworkConfig {
    public static class Entry { public final String host; public final int port; Entry(String h,int p){host=h;port=p;} }
    public final Map<String, Entry> map = new LinkedHashMap<>();

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
