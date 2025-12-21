import java.util.*;

public class DynamicTrafficManager {

    private static final double BLOCKED_THRESHOLD = 9999.0;
    private static final double INF = 1e12;

    public static class EdgeChange {
        public final Daa_smartCity.Edge edge;
        public final double oldWeight;
        public final double newWeight;

        public EdgeChange(Daa_smartCity.Edge e, double oldW, double newW) {
            this.edge = e;
            this.oldWeight = oldW;
            this.newWeight = newW;
        }
    }

    private final Map<Daa_smartCity.Node,
            Map<Daa_smartCity.Node, Daa_smartCity.PathResult>> dpCache = new HashMap<>();

    private int trafficVersion = 0;
    private int lastFullRecomputeVersion = -1;

    private List<EdgeChange> lastChanges = new ArrayList<>();

    private double fullRecomputeThreshold = 0.35;

    public void clearCache() { dpCache.clear(); }

    public Daa_smartCity.PathResult getCached(Daa_smartCity.Node src,
                                             Daa_smartCity.Node dst) {
        Map<Daa_smartCity.Node, Daa_smartCity.PathResult> inner = dpCache.get(src);
        if (inner == null) return null;
        return inner.get(dst);
    }

    private void putCache(Daa_smartCity.Node src, Daa_smartCity.Node dst,
                          Daa_smartCity.PathResult res) {
        dpCache.computeIfAbsent(src, k -> new HashMap<>()).put(dst, res);
    }

    // ✅ Improved cache invalidation: invalidate ONLY cached paths that actually use changed edges
    private void invalidateCacheForChanges(List<EdgeChange> changes) {
        if (changes == null || changes.isEmpty() || dpCache.isEmpty()) return;

        Set<Long> changed = new HashSet<>();
        for (EdgeChange ch : changes) {
            int u = System.identityHashCode(ch.edge.from);
            int v = System.identityHashCode(ch.edge.to);
            changed.add(pack(u, v));
        }

        for (Daa_smartCity.Node src : new ArrayList<>(dpCache.keySet())) {
            Map<Daa_smartCity.Node, Daa_smartCity.PathResult> inner = dpCache.get(src);
            if (inner == null) continue;

            for (Daa_smartCity.Node dst : new ArrayList<>(inner.keySet())) {
                Daa_smartCity.PathResult pr = inner.get(dst);
                if (pr == null || pr.path == null || pr.path.size() < 2) {
                    inner.remove(dst);
                    continue;
                }
                if (pathUsesChangedEdge(pr.path, changed)) inner.remove(dst);
            }

            if (inner.isEmpty()) dpCache.remove(src);
        }
    }

    private boolean pathUsesChangedEdge(List<Daa_smartCity.Node> path, Set<Long> changed) {
        for (int i = 0; i < path.size() - 1; i++) {
            int u = System.identityHashCode(path.get(i));
            int v = System.identityHashCode(path.get(i + 1));
            if (changed.contains(pack(u, v))) return true;
        }
        return false;
    }

    private long pack(int u, int v) {
        return (((long) u) << 32) ^ (v & 0xffffffffL);
    }

    private void registerTrafficChange(List<EdgeChange> changes) {
        this.trafficVersion++;
        this.lastChanges = changes;
        invalidateCacheForChanges(changes);
    }

   public List<EdgeChange> applyRandomTraffic(
        List<Daa_smartCity.Edge> edges,
        int count,
        double minIgnored,
        double maxIgnored,
        Random rand
) {
    if (edges == null || edges.isEmpty()) return List.of();

    List<EdgeChange> result = new ArrayList<>();

    Collections.shuffle(edges, rand);
    int limit = Math.min(count, edges.size());

    for (int i = 0; i < limit; i++) {
        Daa_smartCity.Edge e = edges.get(i);
        if (e.weight >= BLOCKED_THRESHOLD) continue;

        double oldW = e.weight;

        // 1️⃣ Mixed random traffic (mostly +ve, sometimes −ve)
        double delta;
        if (rand.nextDouble() < 0.3) {
            // 30% chance relief (negative)
            delta = -(1 + rand.nextDouble() * 3);   // [-1 , -4]
        } else {
            // 70% chance congestion (positive)
            delta = 1 + rand.nextDouble() * 6;      // [+1 , +7]
        }

        double newW = oldW + delta;

        // 2️⃣ Clamp to safe range (Random > Night, < Rush)
        if (newW < -10) newW = -10;   // allow small benefit
        if (newW > 25)  newW = 25;

        e.weight = newW;
        result.add(new EdgeChange(e, oldW, newW));
    }

    registerTrafficChange(result);
    return result;
}


    public List<EdgeChange> applyRushHour(List<Daa_smartCity.Edge> edges, double factor) {
        if (edges == null || edges.isEmpty()) return List.of();

        List<EdgeChange> result = new ArrayList<>();
        for (Daa_smartCity.Edge e : edges) {
            if (e.weight >= BLOCKED_THRESHOLD) continue;

            double oldW = e.weight;
            double newW = oldW * factor;
            e.weight = newW;
            result.add(new EdgeChange(e, oldW, newW));
        }
        registerTrafficChange(result);
        return result;
    }

    public List<EdgeChange> applyNightMode(List<Daa_smartCity.Edge> edges, double factor) {
    if (edges == null || edges.isEmpty()) return List.of();

    List<EdgeChange> result = new ArrayList<>();
    Random rand = new Random();

    for (Daa_smartCity.Edge e : edges) {
        if (e.weight >= BLOCKED_THRESHOLD) continue;

        double oldW = e.weight;

        // 1️⃣ Night mode = low traffic (smooth reduction)
        double newW = oldW * factor;   // factor e.g. 0.6 – 0.7

        // 2️⃣ Small natural variation (NO negative)
        newW += rand.nextDouble() * 2.0;   // [0 , +2]

        // 3️⃣ Clamp strictly to [0 , 25]
        if (newW < 0) newW = 0;
        if (newW > 25) newW = 25;

        e.weight = newW;
        result.add(new EdgeChange(e, oldW, newW));
    }

    registerTrafficChange(result);
    return result;
}


    public List<EdgeChange> applyRoadBlock(List<Daa_smartCity.Edge> edges,
                                           int count,
                                           double blockedWeight,
                                           Random rng) {
        if (edges == null || edges.isEmpty()) return List.of();
        if (rng == null) rng = new Random();

        count = Math.min(count, edges.size());

        List<Daa_smartCity.Edge> shuffled = new ArrayList<>(edges);
        Collections.shuffle(shuffled, rng);

        List<EdgeChange> result = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            Daa_smartCity.Edge e = shuffled.get(i);
            double oldW = e.weight;
            e.weight = blockedWeight;
            result.add(new EdgeChange(e, oldW, blockedWeight));
        }

        registerTrafficChange(result);
        return result;
    }

    public List<EdgeChange> applyPeriodicRandomTraffic(List<Daa_smartCity.Edge> edges,
                                                       Random rng) {
        if (edges == null || edges.isEmpty()) return List.of();
        int count = Math.max(1, (int) (edges.size() * 0.1));
        return applyRandomTraffic(edges, count, 1, 3, rng);
    }

    public Daa_smartCity.PathResult recomputeShortestPath(
            Daa_smartCity.Node src,
            Daa_smartCity.Node dst,
            List<Daa_smartCity.Node> nodes,
            List<Daa_smartCity.Edge> edges,
            boolean directedMode
    ) {
        if (src == null || dst == null) return null;

        Daa_smartCity.PathResult cached = getCached(src, dst);
        if (cached != null && cached.found && !cached.hasNegativeCycle) return cached;

        boolean fullRecompute = shouldFullRecompute(edges.size(), lastChanges.size());

        Daa_smartCity.PathResult res;
        if (fullRecompute) {
            res = runDijkstra(src, dst, nodes, edges, directedMode);
            lastFullRecomputeVersion = trafficVersion;
        } else {
            res = runAStar(src, dst, nodes, edges, directedMode);
        }

        if (res != null && res.found) putCache(src, dst, res);
        return res;
    }

    private boolean shouldFullRecompute(int totalEdges, int changedEdges) {
        if (totalEdges == 0) return false;
        if (lastFullRecomputeVersion < 0) return true;

        double fraction = (double) changedEdges / (double) totalEdges;
        return fraction >= fullRecomputeThreshold;
    }

    private Map<Daa_smartCity.Node, List<Daa_smartCity.Edge>> buildGraph(
            List<Daa_smartCity.Node> nodes,
            List<Daa_smartCity.Edge> edges,
            boolean directed
    ) {
        Map<Daa_smartCity.Node, List<Daa_smartCity.Edge>> g = new HashMap<>();
        for (Daa_smartCity.Node n : nodes) g.put(n, new ArrayList<>());

        for (Daa_smartCity.Edge e : edges) {
            if (e.weight >= BLOCKED_THRESHOLD) continue;
            g.get(e.from).add(e);
            if (!directed) g.get(e.to).add(new Daa_smartCity.Edge(e.to, e.from, e.weight));
        }
        return g;
    }

    private double heuristic(Daa_smartCity.Node a, Daa_smartCity.Node b) {
        return Math.hypot(a.x - b.x, a.y - b.y);
    }

    private Daa_smartCity.PathResult runDijkstra(
            Daa_smartCity.Node start,
            Daa_smartCity.Node goal,
            List<Daa_smartCity.Node> nodes,
            List<Daa_smartCity.Edge> edges,
            boolean directed
    ) {
        Map<Daa_smartCity.Node, List<Daa_smartCity.Edge>> g = buildGraph(nodes, edges, directed);

        Map<Daa_smartCity.Node, Double> dist = new HashMap<>();
        Map<Daa_smartCity.Node, Daa_smartCity.Node> parent = new HashMap<>();

        for (Daa_smartCity.Node n : nodes) dist.put(n, Double.POSITIVE_INFINITY);
        dist.put(start, 0.0);

        PriorityQueue<Daa_smartCity.Node> pq =
                new PriorityQueue<>(Comparator.comparingDouble(dist::get));
        pq.add(start);

        int steps = 0;

        while (!pq.isEmpty()) {
            Daa_smartCity.Node u = pq.poll();
            steps++;

            if (u == goal) {
                List<Daa_smartCity.Node> path = reconstruct(parent, start, goal);
                double cost = dist.get(goal);
                return new Daa_smartCity.PathResult(true, path, steps, false, null, cost);
            }

            List<Daa_smartCity.Edge> out = g.get(u);
            if (out == null) continue;

            for (Daa_smartCity.Edge e : out) {
                steps++;
                double alt = dist.get(u) + e.weight;
                if (alt < dist.get(e.to)) {
                    dist.put(e.to, alt);
                    parent.put(e.to, u);
                    pq.add(e.to);
                }
            }
        }

        boolean found = dist.get(goal) < Double.POSITIVE_INFINITY;
        List<Daa_smartCity.Node> path = found ? reconstruct(parent, start, goal) : null;
        double cost = found ? dist.get(goal) : Double.POSITIVE_INFINITY;

        return new Daa_smartCity.PathResult(found, path, steps, false, null, cost);
    }

    private Daa_smartCity.PathResult runAStar(
            Daa_smartCity.Node start,
            Daa_smartCity.Node goal,
            List<Daa_smartCity.Node> nodes,
            List<Daa_smartCity.Edge> edges,
            boolean directed
    ) {
        Map<Daa_smartCity.Node, List<Daa_smartCity.Edge>> g = buildGraph(nodes, edges, directed);

        Map<Daa_smartCity.Node, Double> gScore = new HashMap<>();
        Map<Daa_smartCity.Node, Double> fScore = new HashMap<>();
        Map<Daa_smartCity.Node, Daa_smartCity.Node> parent = new HashMap<>();

        for (Daa_smartCity.Node n : nodes) {
            gScore.put(n, Double.POSITIVE_INFINITY);
            fScore.put(n, Double.POSITIVE_INFINITY);
        }

        gScore.put(start, 0.0);
        fScore.put(start, heuristic(start, goal));

        PriorityQueue<Daa_smartCity.Node> open =
                new PriorityQueue<>(Comparator.comparingDouble(fScore::get));
        open.add(start);

        int steps = 0;

        while (!open.isEmpty()) {
            Daa_smartCity.Node cur = open.poll();
            steps++;

            if (cur == goal) {
                List<Daa_smartCity.Node> path = reconstruct(parent, start, goal);
                double cost = gScore.get(goal);
                return new Daa_smartCity.PathResult(true, path, steps, false, null, cost);
            }

            List<Daa_smartCity.Edge> out = g.get(cur);
            if (out == null) continue;

            for (Daa_smartCity.Edge e : out) {
                steps++;
                double tentative = gScore.get(cur) + e.weight;
                if (tentative < gScore.get(e.to)) {
                    parent.put(e.to, cur);
                    gScore.put(e.to, tentative);
                    fScore.put(e.to, tentative + heuristic(e.to, goal));
                    open.add(e.to);
                }
            }
        }

        return new Daa_smartCity.PathResult(false, null, steps, false, null, Double.POSITIVE_INFINITY);
    }

    private List<Daa_smartCity.Node> reconstruct(
            Map<Daa_smartCity.Node, Daa_smartCity.Node> parent,
            Daa_smartCity.Node start,
            Daa_smartCity.Node goal
    ) {
        List<Daa_smartCity.Node> path = new ArrayList<>();
        for (Daa_smartCity.Node at = goal; at != null; at = parent.get(at)) path.add(at);

        if (path.isEmpty() || path.get(path.size() - 1) != start) return null;
        Collections.reverse(path);
        return path;
    }
}
