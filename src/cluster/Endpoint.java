package cluster;

import java.io.Serializable;
import java.util.Objects;

/**
 * A cluster member's network identity, serialised over RMI during gossip.
 *
 * Equality is by host:port only — the nodeId is metadata that may be unknown
 * for a freshly-parsed seed and gets filled in once we successfully gossip.
 */
public final class Endpoint implements Serializable {

    private static final long serialVersionUID = 1L;

    public final String nodeId;   // "?" until learned via gossip
    public final String host;
    public final int    port;

    public Endpoint(String nodeId, String host, int port) {
        this.nodeId = (nodeId == null || nodeId.isEmpty()) ? "?" : nodeId;
        this.host   = host;
        this.port   = port;
    }

    /** Parses "host:port" into an Endpoint with an unknown nodeId. */
    public static Endpoint parse(String hostPort) {
        int idx = hostPort.lastIndexOf(':');
        if (idx <= 0 || idx == hostPort.length() - 1) {
            throw new IllegalArgumentException("Expected host:port, got: " + hostPort);
        }
        return new Endpoint("?", hostPort.substring(0, idx),
                Integer.parseInt(hostPort.substring(idx + 1)));
    }

    /** Stable string key for hashing in maps. */
    public String addr() { return host + ":" + port; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Endpoint)) return false;
        Endpoint e = (Endpoint) o;
        return port == e.port && host.equals(e.host);
    }

    @Override
    public int hashCode() { return Objects.hash(host, port); }

    @Override
    public String toString() { return nodeId + "@" + host + ":" + port; }
}
