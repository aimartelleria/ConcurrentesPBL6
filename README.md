# Java RMI Cluster (no single point of failure)

A peer-to-peer RMI cluster. Every node runs its own RMI registry, members
discover and track each other via gossip, and clients bootstrap from a
list of seeds (any one alive is enough). Killing any subset short of
"all of them" leaves the rest functional.

## Files

```
src/cluster/
  Endpoint.java         Serializable (host, port, nodeId); equality by host:port
  ComputeService.java   Remote interface: getNodeId, ping, compute, gossip
  ClusterNode.java      Self-registering, gossip-driven peer
  Client.java           Multi-seed bootstrap + round-robin + failover
```

## Design

**No shared infrastructure.** Each node calls `LocateRegistry.createRegistry(port)`
on its own port and binds itself locally as `"ComputeNode"`. There is no
shared registry, no leader, no coordinator. The only thing every actor
(node or client) needs is a list of *seed* addresses — at least one of
which is reachable at bootstrap time.

**Gossip-based membership.** Each node holds a map of known members keyed
by `host:port`. Every 3 seconds it iterates the map, attempts a `gossip`
RPC with each peer, and merges the returned view. The RPC carries the
caller's `self` plus its view of the membership.

**Failure detection.** Three consecutive failed gossip rounds against a
peer (≈9 seconds of unreachability) eject the peer and record a 30-second
tombstone on its address, suppressing re-introduction via stale gossip
from third parties.

**First-hand proof of life beats tombstones.** When a peer directly
contacts us via gossip, its `self` parameter clears any tombstone we
hold on it. This is what makes restarts work: a node that comes back at
the same address rejoins immediately rather than waiting 30 seconds.
Second-hand "I heard X is alive" gossip still respects tombstones.

**Bootstrap.** If a node's member map ever drops to empty (lost partition,
fresh start, every peer evicted), it re-adds its seed list and retries.
A node can boot before any of its seeds are up — it'll keep trying.

**Client.** The client takes a seed list, attempts `gossip(null, {})`
against each in turn until one responds, caches the returned membership,
and round-robins. Per-call `RemoteException` falls through to the next
cached member; if every cached member fails, it refreshes from seeds
and retries once.

## What's *actually* SPoF-free here

| Failure                          | Result                                               |
|----------------------------------|------------------------------------------------------|
| Any one node dies                | Survivors evict, keep serving. Client fails over.    |
| Majority dies                    | The lone survivor still serves.                      |
| A killed node restarts           | Direct contact clears its tombstone, instant rejoin. |
| A seed in the client list is down| Client falls through to the next seed.               |
| Network partition                | Each side keeps operating on the members it can see. |

The only invariant you need is: **at boot time, at least one address
in each actor's seed list resolves to a live node.** That's "any-of-N",
not "this-specific-one", so list 3+ stable hosts and you're done.

## Build and run

```
javac -d build src/cluster/*.java
```

Three peer nodes, each pointing at the other two:

```
java -cp build cluster.ClusterNode node-A localhost 1099 localhost:1100 localhost:1101
java -cp build cluster.ClusterNode node-B localhost 1100 localhost:1099 localhost:1101
java -cp build cluster.ClusterNode node-C localhost 1101 localhost:1099 localhost:1100
```

Client with the same three as seeds:

```
java -cp build cluster.Client localhost:1099 localhost:1100 localhost:1101
```

Kill any combination of nodes mid-stream. Within ~9 seconds the survivors
log `Evicted (no response after 3 rounds): X` and drop the dead node.
The client keeps going against whoever's left, refreshing its view if
needed. Restart a node and it rejoins on first gossip; you'll see
`Tombstone cleared by direct contact: X` on the peer that admits it back.

## Real-world deployment notes

- **Pin RMI's stub hostname.** RMI embeds the server's hostname in the
  stub returned from a registry lookup. By default it uses
  `InetAddress.getLocalHost()`, which is often unreachable from other
  machines. Set `-Djava.rmi.server.hostname=<reachable-name-or-ip>` on
  every node.
- **Pin the export port for firewalls.** The constructor calls `super(0)`,
  which exports the remote object on an anonymous port. For firewalled
  networks, pass a fixed port to `super()` and open it.
- **Seed list lives in config, not in a registry.** Put 3+ seeds (or
  ideally a DNS A-record / SRV record returning multiple hosts) in your
  client config. The seed list is the only "I have to know something"
  step; everything after is discovered.
- **Wire encryption.** Add `SslRMI{Client,Server}SocketFactory` to
  `UnicastRemoteObject` if traffic crosses untrusted networks.

## Known limits

- **No indirect probing.** If node A can't reach node B but C can, A
  will still evict B. Real gossip protocols (SWIM) ask K third parties
  to probe before declaring dead. For a small LAN cluster this is
  usually fine; on a flaky network you'd want to add it.
- **Split-brain is silent.** If the cluster partitions, each side keeps
  serving on the assumption the other side is dead. For a stateless
  service like this one, that's the right behaviour. For anything that
  mutates state, you need a consensus protocol (Raft, Paxos) on top.
- **`compute()` is stateless.** Replace it with whatever real work you
  do — the membership mechanics don't care, but if your work has state,
  you also need a shared store.
- **O(N²) gossip.** Every node pings every other peer every round.
  Fine up to dozens of nodes; beyond that, sample a random K peers per
  round instead.
