package cluster;

import java.rmi.Remote;
import java.rmi.RemoteException;
import java.util.Set;

/**
 * The contract every cluster node exposes via RMI.
 *
 * Methods on a Remote interface must declare RemoteException. Arguments
 * and return types crossing the wire must be Serializable.
 */
public interface ComputeService extends Remote {

    /** Returns the unique ID of the node serving this call. */
    String getNodeId() throws RemoteException;

    /** Liveness probe: returns the server's current time (millis). */
    long ping() throws RemoteException;

    /** Example unit of work performed by the cluster. */
    double compute(double input) throws RemoteException;

    /** Procesa la telemetría enviada en formato Avro y realiza el pipeline ETL */
    void processTelemetry(byte[] avroPayload) throws RemoteException;

    /**
     * Anti-entropy exchange. The {@code sender} parameter is the caller's
     * own endpoint and counts as direct proof-of-life: the callee will
     * clear any tombstone for the sender. {@code theirView} carries the
     * rest of the caller's known members for merge. Clients (which are
     * not cluster members) pass {@code null} for sender and an empty set
     * for the view to read the current state without contributing.
     */
    Set<Endpoint> gossip(Endpoint sender, Set<Endpoint> theirView) throws RemoteException;
}
