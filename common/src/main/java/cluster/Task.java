package cluster;

import java.io.Serializable;

/**
 * A generic task that can be transmitted over the network and executed on any node.
 */
public interface Task<T> extends Serializable {
    T execute();
}
