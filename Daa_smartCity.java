
import java.awt.*;
import java.awt.event.*;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;
import java.util.Queue;

import javax.swing.*;
import javax.swing.table.*;
import javax.swing.text.*;
import javax.swing.border.EmptyBorder;

public class Daa_smartCity extends JFrame {

    // ======= Graph Node / Edge =======
    public static class Node {
        int x, y;
        String label;
        Color color = new Color(0x007BFF);

        public Node(int x, int y, String label) {
            this.x = x;
            this.y = y;
            this.label = label;
        }

        @Override
        public String toString() { return label; }
    }

    public static class Edge {
        public Node from, to;
        public double weight;

        public Edge(Node f, Node t, double w) {
            from = f;
            to = t;
            weight = w;
        }
    }

    // ======= Result row for table =======
    public static class Result {
        String name;
        double timeMs;
        int steps;
        double totalCost;
        int pathLen;
        boolean ok;
        String reason;
        String complexity;
        boolean optimal; // 

        public Result(String n, double t, int s, double tc, int pl,
                      boolean ok, String r, String c) {
            name = n;
            timeMs = t;
            steps = s;
            totalCost = tc;
            pathLen = pl;
            this.ok = ok;
            reason = r;
            complexity = c;
            optimal = false;
        }
    }

    // ======= Internal algorithm result =======
    public static class PathResult {
        public boolean found;
        public List<Node> path;
        public int steps;
        public boolean hasNegativeCycle;
        public List<Map<Node, Double>> distanceHistory;
        public double totalCost;

        public PathResult(boolean f, List<Node> p, int s,
                          boolean nc, List<Map<Node, Double>> hist, double tc) {
            found = f;
            path = p;
            steps = s;
            hasNegativeCycle = nc;
            distanceHistory = hist;
            totalCost = tc;
        }
    }

    static class Holder<T> { T value; Holder(T v){ value = v; } }

    // ===== Undo support =====
    private interface UndoableAction {
        void undo();
    }

    private final Deque<UndoableAction> undoStack = new ArrayDeque<>();

    // ======= Data =======
    private final List<Node> nodes = new ArrayList<Node>();
    private final List<Edge> edges = new ArrayList<Edge>();
    private int nodeId = 0;

    private Node first = null, hover = null;
    private Node src = null, dst = null;
    private List<Node> currentPath = null;

    private boolean directedMode = true;

    private GraphPanel canvas;

    // JTextPane for colored logs
    private JTextPane logArea;
    private Style normalStyle;
    private Style highlightStyle;

    private JTable table;
    private DefaultTableModel tblModel;
    private JTable bfTable;
    private DefaultTableModel bfModel;
    private JLabel bestLbl;
    private final List<Result> results = new ArrayList<Result>();

    // Dynamic traffic manager
    private final DynamicTrafficManager trafficManager = new DynamicTrafficManager();

    // ======= Background Image =======
    private Image bgImage;

    // ======= Blocked road rule =======
    private static final double BLOCKED_THRESHOLD = 9999.0;
    private static final double INF = 1e12;

    private boolean isBlocked(Edge e) {
        return e.weight >= BLOCKED_THRESHOLD || Double.isInfinite(e.weight);
    }

    private String getAnyBlockedRoadLabel() {
        for (Edge e : edges) {
            if (isBlocked(e)) {
                return e.from.label + " → " + e.to.label;
            }
        }
        return null;
    }

    private void pushUndo(UndoableAction action) {
        undoStack.push(action);
    }

    private void undoLastAction() {
        if (undoStack.isEmpty()) {
            JOptionPane.showMessageDialog(
                    this,
                    "Nothing to undo.",
                    "Undo",
                    JOptionPane.INFORMATION_MESSAGE
            );
            return;
        }
        UndoableAction act = undoStack.pop();
        act.undo();
        canvas.repaint();
    }

    // ===== Button style constants =====
    private static final Color BUTTON_BG = new Color(0xE3F2FD);
    private static final Color BUTTON_FG = new Color(0x0D47A1);
    private static final int BUTTON_RADIUS = 18;

    // ===== Vehicle Scheduling helper =====
    private static class SchedResult {
        double makespan;
        List<List<Integer>> assignment;
    }

    // ===== TSP Branch&Bound helper =====
    private static class TSPState {
        double bestCost = INF;
        int[] bestPath;
        boolean found = false;
    }

    // ===== BEST DECISION helper =====
    private static class BestDecision {
        Result best;
        String explanation;
    }

    // =====================================================
    //                    CONSTRUCTOR
    // =====================================================
    public Daa_smartCity() {
        super("Smart City Transportation Optimizer – Clean Version");
        setLayout(new BorderLayout());

        canvas = new GraphPanel();
        canvas.setPreferredSize(new Dimension(900, 600));

        // ===== Load background image from same package/folder =====
       try {
    java.net.URL imgUrl = getClass().getResource("/resources/map.jpg"); // note the leading /
    if (imgUrl != null) {
        bgImage = new ImageIcon(imgUrl).getImage();
    } else {
        System.out.println("map.jpg not found in classpath");
    }
} catch (Exception e) {
    e.printStackTrace();
}

        // ---------- Right side panel ----------
        JPanel side = new JPanel(new BorderLayout());
        side.setPreferredSize(new Dimension(420, 650));

        // ===== Controls (buttons) =====
        JPanel ctrl = new JPanel(new GridLayout(0, 1, 4, 4));
        ctrl.setBackground(new Color(200, 225, 255));

        String[] algs = {"Run All","BFS","DFS","Dijkstra","A*","Bellman-Ford","Greedy"};
        final JComboBox<String> box = new JComboBox<String>(algs);

        JButton run = btn("Run", new Color(0x007BFF));
        run.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                runAlgo((String) box.getSelectedItem());
            }
        });

        JButton rst = btn("Restart", new Color(0x333333));
        rst.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                restart();
            }
        });

        JButton modeBtn = btn("Switch Mode: DIRECTED", new Color(0x6A1B9A));
        modeBtn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                directedMode = !directedMode;
                modeBtn.setText("Switch Mode: " + (directedMode ? "DIRECTED" : "UNDIRECTED"));
                log("Graph mode: " + (directedMode ? "DIRECTED" : "UNDIRECTED"));
                canvas.repaint();
            }
        });

        // ===== Dynamic Traffic Buttons =====
        JButton randomTrafficBtn = btn("Random Traffic", new Color(0xFF7043));
        randomTrafficBtn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                final List<DynamicTrafficManager.EdgeChange> changes =
                        trafficManager.applyRandomTraffic(
                                edges,
                                Math.max(1, edges.size() / 4),
                                1, 6, new Random()
                        );

                if (changes != null && !changes.isEmpty()) {
                    for (DynamicTrafficManager.EdgeChange ch : changes) {
                        Edge ed = ch.edge;
                        String msg = String.format(
                                "Traffic update: %s → %s | old: %.1f, new: %.1f",
                                ed.from.label, ed.to.label,
                                ch.oldWeight, ch.newWeight
                        );
                        logHighlight(msg);
                    }
                }
                canvas.repaint();
            }
        });

        JButton rushHourBtn = btn("Rush Hour", new Color(0xD32F2F));
        rushHourBtn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                final List<DynamicTrafficManager.EdgeChange> changes =
                        trafficManager.applyRushHour(edges, 1.8);

                if (changes != null && !changes.isEmpty()) {
                    for (DynamicTrafficManager.EdgeChange ch : changes) {
                        Edge ed = ch.edge;
                        String msg = String.format(
                                "Traffic update: %s → %s | old: %.1f, new: %.1f",
                                ed.from.label, ed.to.label,
                                ch.oldWeight, ch.newWeight
                        );
                        logHighlight(msg);
                    }
                }
                canvas.repaint();
            }
        });

        JButton nightModeBtn = btn("Night Mode", new Color(0x388E3C));
        nightModeBtn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                final List<DynamicTrafficManager.EdgeChange> changes =
                        trafficManager.applyNightMode(edges, 0.7);

                if (changes != null && !changes.isEmpty()) {
                    for (DynamicTrafficManager.EdgeChange ch : changes) {
                        Edge ed = ch.edge;
                        String msg = String.format(
                                "Traffic update: %s → %s | old: %.1f, new: %.1f",
                                ed.from.label, ed.to.label,
                                ch.oldWeight, ch.newWeight
                        );
                        logHighlight(msg);
                    }
                }
                canvas.repaint();
            }
        });

        JButton roadBlockBtn = btn("Road Block", new Color(0x8E24AA));
        roadBlockBtn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                final List<DynamicTrafficManager.EdgeChange> changes =
                        trafficManager.applyRoadBlock(
                                edges,
                                Math.max(1, edges.size() / 5),
                                BLOCKED_THRESHOLD,
                                new Random()
                        );

                if (changes != null && !changes.isEmpty()) {
                    for (DynamicTrafficManager.EdgeChange ch : changes) {
                        Edge ed = ch.edge;
                        String msg = String.format(
                                "Traffic update: %s → %s | old: %.1f, new: %.1f",
                                ed.from.label, ed.to.label,
                                ch.oldWeight, ch.newWeight
                        );
                        logHighlight(msg);
                    }
                }
                canvas.repaint();
            }
        });

        JButton periodicBtn = btn("Periodic Traffic", new Color(0x039BE5));
        periodicBtn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                final List<DynamicTrafficManager.EdgeChange> changes =
                        trafficManager.applyPeriodicRandomTraffic(edges, new Random());

                if (changes != null && !changes.isEmpty()) {
                    for (DynamicTrafficManager.EdgeChange ch : changes) {
                        Edge ed = ch.edge;
                        String msg = String.format(
                                "Traffic update: %s → %s | old: %.1f, new: %.1f",
                                ed.from.label, ed.to.label,
                                ch.oldWeight, ch.newWeight
                        );
                        logHighlight(msg);
                    }
                }
                canvas.repaint();
            }
        });

        JButton dynRouteBtn = btn("Dynamic Route (Hybrid)", new Color(0x0097A7));
        dynRouteBtn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                if (src == null || dst == null) {
                    JOptionPane.showMessageDialog(
                            Daa_smartCity.this,
                            "Run any algorithm first to set Source and Destination.",
                            "No Source/Destination",
                            JOptionPane.WARNING_MESSAGE
                    );
                    return;
                }

                PathResult res = trafficManager.recomputeShortestPath(
                        src, dst, nodes, edges, directedMode
                );

                if (res != null && res.found && !res.hasNegativeCycle) {
                    currentPath = res.path;
                    log("Dynamic Routing (Hybrid) updated. New cost = " +
                            String.format("%.2f", res.totalCost));
                    canvas.repaint();
                } else {
                    String blockedRoad = getAnyBlockedRoadLabel();
                    if (blockedRoad != null) {
                        String msg = "Traffic cannot proceed — Road " + blockedRoad + " is blocked.";
                        logHighlight(msg);
                        JOptionPane.showMessageDialog(
                                Daa_smartCity.this,
                                msg,
                                "Routing Failed",
                                JOptionPane.WARNING_MESSAGE
                        );
                    } else {
                        log("Dynamic Routing: No valid path after traffic update.");
                    }
                }
            }
        });

        // ===== New module buttons =====
        JButton schedulingBtn = btn("Vehicle Scheduling", new Color(0x0277BD));
        schedulingBtn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                runVehicleSchedulingDialog();
            }
        });

        JButton tspBtn = btn("TSP (Exact + Heuristic)", new Color(0x7B1FA2));
        tspBtn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                runTSPModule();
            }
        });

        // Add controls
        ctrl.add(box);
        ctrl.add(run);
        ctrl.add(rst);
        ctrl.add(modeBtn);
        ctrl.add(randomTrafficBtn);
        ctrl.add(rushHourBtn);
        ctrl.add(nightModeBtn);
        ctrl.add(roadBlockBtn);
        ctrl.add(periodicBtn);
        ctrl.add(dynRouteBtn);
        ctrl.add(schedulingBtn);
        ctrl.add(tspBtn);

        JScrollPane ctrlScroll = new JScrollPane(ctrl);
        ctrlScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        ctrlScroll.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        ctrlScroll.setBorder(null);
        ctrlScroll.setPreferredSize(new Dimension(420, 210));

        // ===== Log / Tables =====
        logArea = new JTextPane();
        logArea.setEditable(false);
        logArea.setBackground(new Color(227, 242, 253));
        logArea.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));

        StyledDocument doc = logArea.getStyledDocument();
        normalStyle = doc.addStyle("normal", null);
        StyleConstants.setForeground(normalStyle, new Color(0x4E342E));
        StyleConstants.setFontFamily(normalStyle, "Monospaced");
        StyleConstants.setFontSize(normalStyle, 12);

        highlightStyle = doc.addStyle("highlight", null);
        StyleConstants.setForeground(highlightStyle, new Color(0xBF360C));
        StyleConstants.setBold(highlightStyle, true);
        StyleConstants.setFontFamily(highlightStyle, "Monospaced");
        StyleConstants.setFontSize(highlightStyle, 12);

        JScrollPane logSc = new JScrollPane(logArea);

        //  NEW COLUMN: "Optimal?"
        String[] cols = {"Algorithm","Time (ms)","Steps","Total Cost","Path Len","Optimal?","Status","Reason","Complexity"};
        tblModel = new DefaultTableModel(cols, 0);
        table = new JTable(tblModel);
        table.setFont(new Font("SansSerif", Font.PLAIN, 14));
        table.setRowHeight(30);
        table.setFillsViewportHeight(true);
        styleTable(table);
        JScrollPane tblSc = new JScrollPane(table);
        tblSc.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);
        tblSc.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);

        bfModel = new DefaultTableModel();
        bfTable = new JTable(bfModel);
        bfTable.setFont(new Font("Monospaced", Font.PLAIN, 13));
        bfTable.setRowHeight(26);
        bfTable.setFillsViewportHeight(true);
        styleTable(bfTable);
        JScrollPane bfSc = new JScrollPane(bfTable);
        bfSc.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);
        bfSc.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);

        bestLbl = new JLabel("Best: —", SwingConstants.CENTER);
        bestLbl.setFont(new Font("SansSerif", Font.BOLD, 14));
        bestLbl.setForeground(new Color(0x007BFF));

        JButton exp = btn("Export CSV", new Color(0x455A64));
        exp.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                exportCSV();
            }
        });

        JPanel bot = new JPanel(new BorderLayout());
        bot.add(bestLbl, BorderLayout.CENTER);
        bot.add(exp, BorderLayout.SOUTH);

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Log", logSc);
        tabs.addTab("Results", new JPanel(new BorderLayout()) {{
            add(tblSc, BorderLayout.CENTER);
            add(bot, BorderLayout.SOUTH);
        }});
        tabs.addTab("Bellman-Ford Steps", bfSc);

        side.add(ctrlScroll, BorderLayout.NORTH);
        side.add(tabs,      BorderLayout.CENTER);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, canvas, side);
        split.setDividerLocation(900);

        // ===== TOP NAV BAR BUTTONS =====
        JButton deleteNodeBtn = btn("Delete Node", new Color(0xE53935));
        deleteNodeBtn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                Node target = askNodeOnce("Enter Node to delete (A, B, C...):");
                if (target != null) {
                    deleteNode(target, true);
                }
            }
        });

        JButton deleteEdgeBtn = btn("Delete Edge", new Color(0xFB8C00));
        deleteEdgeBtn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                Node from = askNodeOnce("Enter FROM node of edge to delete:");
                if (from == null) return;
                Node to = askNodeOnce("Enter TO node of edge to delete:");
                if (to == null) return;

                Edge target = findEdge(from, to);
                if (target == null) {
                    JOptionPane.showMessageDialog(
                            Daa_smartCity.this,
                            "Edge " + from.label + " → " + to.label + " not found.",
                            "Delete Edge",
                            JOptionPane.WARNING_MESSAGE
                    );
                } else {
                    deleteEdge(target, true);
                }
            }
        });

        JButton undoBtn = btn("Undo", new Color(0x546E7A));
        undoBtn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                undoLastAction();
            }
        });

        JPanel topBar = new JPanel(new FlowLayout(FlowLayout.LEFT));
        topBar.add(deleteNodeBtn);
        topBar.add(deleteEdgeBtn);
        topBar.add(undoBtn);
        topBar.setBackground(new Color(200, 225, 255));

        add(topBar, BorderLayout.NORTH);
        add(split, BorderLayout.CENTER);

        setDefaultCloseOperation(EXIT_ON_CLOSE);
        pack();
        setLocationRelativeTo(null);
        setVisible(true);
    }

    // =====================================================
    //                 UI HELPERS
    // =====================================================
    private JButton btn(String txt, Color ignoredBg) {
        JButton b = new JButton(txt) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);

                g2.setColor(isEnabled() ? BUTTON_BG : BUTTON_BG.darker());
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), BUTTON_RADIUS, BUTTON_RADIUS);

                g2.dispose();
                super.paintComponent(g);
            }
        };

        b.setOpaque(false);
        b.setContentAreaFilled(false);
        b.setBorderPainted(false);
        b.setFocusPainted(false);
        b.setForeground(BUTTON_FG);
        b.setFont(new Font("SansSerif", Font.BOLD, 12));
        b.setBorder(new EmptyBorder(4, 10, 4, 10));
        return b;
    }

    private void styleTable(JTable t) {
        t.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value,
                                                           boolean isSelected, boolean hasFocus,
                                                           int row, int column) {
                Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);

                
                int statusCol = 6; // Algorithm,Time,Steps,TotalCost,PathLen,Optimal?,Status...
                int reasonCol = 7;

                if (table.getColumnCount() > reasonCol) {
                    Object status = table.getValueAt(row, statusCol);
                    Object reason = table.getValueAt(row, reasonCol);

                    if (reason != null && reason.toString().toLowerCase().contains("no path")) {
                        c.setForeground(Color.RED);
                        c.setFont(c.getFont().deriveFont(Font.BOLD));
                    } else if ("Success".equals(status)) {
                        c.setForeground(new Color(0x2E7D32));
                        c.setFont(c.getFont().deriveFont(Font.BOLD));
                    } else {
                        c.setForeground(table.getForeground());
                    }
                }
                return c;
            }
        });
    }

    // =====================================================
    //                GRAPH PANEL
    // =====================================================
    class GraphPanel extends JPanel {

        GraphPanel() {
            setBackground(Color.WHITE);

            addMouseListener(new MouseAdapter() {
                public void mouseClicked(MouseEvent e) {
                    click(e.getX(), e.getY());
                }
            });

            addMouseMotionListener(new MouseMotionAdapter() {
                public void mouseMoved(MouseEvent e) {
                    hover = (first != null) ? find(e.getX(), e.getY()) : null;
                    repaint();
                }
            });
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);

            Graphics2D g2 = (Graphics2D) g;
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);

            if (bgImage != null) {
                int w = getWidth();
                int h = getHeight();
                g2.drawImage(bgImage, 0, 0, w, h, this);
            }

            g2.setStroke(new BasicStroke(2));

            for (Edge e : edges) {
                g2.setColor(isBlocked(e) ? Color.RED : Color.GRAY);
                g2.drawLine(e.from.x, e.from.y, e.to.x, e.to.y);

                int mx = (e.from.x + e.to.x) / 2;
                int my = (e.from.y + e.to.y) / 2;

                double dx = e.to.x - e.from.x;
                double dy = e.to.y - e.from.y;
                double len = Math.hypot(dx, dy);
                int tx = mx;
                int ty = my;
                if (len != 0) {
                    double nx = -dy / len;
                    double ny = dx / len;
                    int offset = 12;
                    tx = (int) (mx + nx * offset);
                    ty = (int) (my + ny * offset);
                }

                g2.setFont(new Font("SansSerif", Font.BOLD, 12));
                g2.setColor(Color.BLACK);
                g2.drawString(String.format("%.1f", e.weight), tx, ty);

                if (directedMode) {
                    drawArrow(g2, e.from.x, e.from.y, e.to.x, e.to.y);
                }
            }

            if (first != null && hover != null && hover != first) {
                g2.setColor(new Color(0x80C7F9));
                g2.setStroke(new BasicStroke(
                        2, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND,
                        1, new float[]{10}, 0
                ));
                g2.drawLine(first.x, first.y, hover.x, hover.y);
                if (directedMode) {
                    drawArrow(g2, first.x, first.y, hover.x, hover.y);
                }
            }

            if (currentPath != null && currentPath.size() > 1) {
                g2.setStroke(new BasicStroke(3));
                g2.setColor(new Color(46, 139, 87));

                for (int i = 0; i < currentPath.size() - 1; i++) {
                    Node a = currentPath.get(i);
                    Node b = currentPath.get(i + 1);
                    g2.drawLine(a.x, a.y, b.x, b.y);
                    if (directedMode) {
                        drawArrow(g2, a.x, a.y, b.x, b.y);
                    }
                }
            }

            for (Node n : nodes) {
                g2.setColor(n.color);
                int r = 20;
                g2.fillOval(n.x - r, n.y - r, r * 2, r * 2);

                g2.setColor(Color.WHITE);
                g2.setFont(new Font("SansSerif", Font.BOLD, 14));
                int offset = (n.label.length() > 1 ? 8 : 5);
                g2.drawString(n.label, n.x - offset, n.y + 5);
            }

            if (src != null) {
                g2.setColor(Color.CYAN);
                g2.setStroke(new BasicStroke(3));
                g2.drawOval(src.x - 25, src.y - 25, 50, 50);
            }
            if (dst != null) {
                g2.setColor(Color.MAGENTA);
                g2.setStroke(new BasicStroke(3));
                g2.drawOval(dst.x - 25, dst.y - 25, 50, 50);
            }
        }
    }

    private void drawArrow(Graphics2D g2, int x1, int y1, int x2, int y2) {
        int nodeRadius = 20;
        double angle = Math.atan2(y2 - y1, x2 - x1);

        int tipX = (int) (x2 - nodeRadius * Math.cos(angle));
        int tipY = (int) (y2 - nodeRadius * Math.sin(angle));
        int len = 12;

        int ax = (int) (tipX - len * Math.cos(angle - Math.PI / 6));
        int ay = (int) (tipY - len * Math.sin(angle - Math.PI / 6));
        int bx = (int) (tipX - len * Math.cos(angle + Math.PI / 6));
        int by = (int) (tipY - len * Math.sin(angle + Math.PI / 6));

        g2.drawLine(tipX, tipY, ax, ay);
        g2.drawLine(tipX, tipY, bx, by);
    }

    // =====================================================
    //         MOUSE HANDLING (nodes/edges)
    // =====================================================
    private void click(int x, int y) {
        Node c = find(x, y);

        if (c != null) {
            if (first == null) {
                first = c;
                return;
            }
            if (first == c) {
                first = null;
                return;
            }

            String in = JOptionPane.showInputDialog(
                    this,
                    "Weight " + first.label + " → " + c.label + " :",
                    "10"
            );
            if (in == null) {
                first = null;
                return;
            }

            try {
                double w = Double.parseDouble(in.trim());
                final Edge newEdge = new Edge(first, c, w);
                edges.add(newEdge);
                log("Edge " + first.label + " → " + c.label + " (w=" + w + ")" +
                        (w < 0 ? " (NEGATIVE!)" : ""));
                canvas.repaint();

                pushUndo(new UndoableAction() {
                    public void undo() {
                        edges.remove(newEdge);
                        if (currentPath != null) currentPath = null;
                        log("Undo: removed edge " + newEdge.from.label + " → " + newEdge.to.label);
                    }
                });

                first = null;
                hover = null;
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, "Enter a valid number!");
            }

        } else {
            final Node newNode = new Node(x, y, String.valueOf((char) ('A' + nodeId++)));
            nodes.add(newNode);
            log("Node " + newNode.label + " added");
            canvas.repaint();

            pushUndo(new UndoableAction() {
                public void undo() {
                    deleteNode(newNode, false);
                    log("Undo: removed node " + newNode.label);
                }
            });
        }
    }

    private Node find(int x, int y) {
        for (Node n : nodes) {
            if (Math.hypot(n.x - x, n.y - y) < 25) return n;
        }
        return null;
    }

    private Edge findEdge(Node from, Node to) {
        for (Edge e : edges) {
            if (e.from == from && e.to == to) {
                return e;
            }
        }
        return null;
    }

    private Node askNodeOnce(String msg) {
        String in = JOptionPane.showInputDialog(this, msg);
        if (in == null) return null;
        in = in.trim().toUpperCase();
        Node n = findNodeByLabel(in);
        if (n == null) {
            JOptionPane.showMessageDialog(
                    this,
                    "Node not found!\nAvailable: " + getAllNodeLabels(),
                    "Error",
                    JOptionPane.ERROR_MESSAGE
            );
        }
        return n;
    }

    private void deleteNode(final Node n, boolean recordUndo) {
        if (n == null) return;

        final List<Edge> removedEdges = new ArrayList<Edge>();
        Iterator<Edge> it = edges.iterator();
        while (it.hasNext()) {
            Edge e = it.next();
            if (e.from == n || e.to == n) {
                removedEdges.add(e);
                it.remove();
            }
        }
        nodes.remove(n);

        if (src == n) src = null;
        if (dst == n) dst = null;
        if (first == n) first = null;
        if (hover == n) hover = null;
        if (currentPath != null && currentPath.contains(n)) currentPath = null;

        log("Node " + n.label + " deleted with " + removedEdges.size() + " connected edge(s).");
        canvas.repaint();

        if (recordUndo) {
            pushUndo(new UndoableAction() {
                public void undo() {
                    if (!nodes.contains(n)) {
                        nodes.add(n);
                    }
                    for (Edge e : removedEdges) {
                        if (!edges.contains(e)) {
                            edges.add(e);
                        }
                    }
                    log("Undo: restored node " + n.label + " and " + removedEdges.size() + " edge(s).");
                }
            });
        }
    }

    private void deleteEdge(final Edge e, boolean recordUndo) {
        if (e == null) return;
        edges.remove(e);
        if (currentPath != null) currentPath = null;
        log("Edge " + e.from.label + " → " + e.to.label + " deleted.");
        canvas.repaint();

        if (recordUndo) {
            pushUndo(new UndoableAction() {
                public void undo() {
                    if (!edges.contains(e)) {
                        edges.add(e);
                    }
                    log("Undo: restored edge " + e.from.label + " → " + e.to.label);
                }
            });
        }
    }

    // =====================================================
    //         GRAPH BUILD / CONNECTIVITY
    // =====================================================
    private Map<Node, List<Edge>> graph() {
        Map<Node, List<Edge>> g = new HashMap<Node, List<Edge>>();
        for (Node n : nodes) {
            g.put(n, new ArrayList<Edge>());
        }

        for (Edge e : edges) {
            if (isBlocked(e)) continue;
            g.get(e.from).add(e);
            if (!directedMode) {
                g.get(e.to).add(new Edge(e.to, e.from, e.weight));
            }
        }
        return g;
    }

    private boolean hasNegativeWeights() {
        for (Edge e : edges) {
            if (isBlocked(e)) continue;
            if (e.weight < 0) return true;
        }
        return false;
    }

    private boolean areAllWeightsSamePositive() {
        double w = -1;
        for (Edge e : edges) {
            if (isBlocked(e)) continue;
            if (e.weight <= 0) return false;
            if (w < 0) w = e.weight;
            else if (Math.abs(e.weight - w) > 1e-9) return false;
        }
        return w > 0;
    }

    private boolean connected(Node s, Node d) {
        Map<Node, List<Edge>> g = graph();
        Set<Node> vis = new HashSet<Node>();
        Queue<Node> q = new LinkedList<Node>();

        q.add(s);
        vis.add(s);

        while (!q.isEmpty()) {
            Node u = q.poll();
            if (u == d) return true;

            List<Edge> list = g.get(u);
            if (list != null) {
                for (Edge e : list) {
                    if (!vis.contains(e.to)) {
                        vis.add(e.to);
                        q.add(e.to);
                    }
                }
            }
        }
        return false;
    }

    // =====================================================
    //                   RUN LOGIC
    // =====================================================
    private void runAlgo(String algo) {
        if (nodes.isEmpty()) {
            JOptionPane.showMessageDialog(
                    this,
                    "No nodes! Add nodes first.",
                    "Empty Graph",
                    JOptionPane.WARNING_MESSAGE
            );
            return;
        }

        results.clear();
        tblModel.setRowCount(0);
        bfModel.setRowCount(0);
        bfModel.setColumnCount(0);
        resetColors();
        currentPath = null;

        src = askValidated("Enter Source Node (A, B, C...):");
        if (src == null) return;

        dst = askValidated("Enter Destination Node:");
        if (dst == null) return;

        if (src == dst) {
            JOptionPane.showMessageDialog(
                    this,
                    "Source and Destination cannot be the same!",
                    "Invalid",
                    JOptionPane.ERROR_MESSAGE
            );
            return;
        }

        boolean hasNeg = hasNegativeWeights();

        if ("Run All".equals(algo)) {

            if (!connected(src, dst)) {
                String road = getAnyBlockedRoadLabel();
                String msg;
                if (road != null) msg = "Traffic cannot proceed — Road " + road + " is blocked.";
                else msg = "No available route from " + src.label + " to " + dst.label + ".";
                logHighlight(msg);
                JOptionPane.showMessageDialog(this, msg, "Routing Failed", JOptionPane.WARNING_MESSAGE);
                return;
            }

            String[] all = {"BFS", "DFS", "Dijkstra", "A*", "Bellman-Ford", "Greedy"};
            for (int i = 0; i < all.length; i++) {
                runOne(all[i], false, hasNeg, true);
            }

            
            updateOptimalFlags();

            highlightBest();

            // ✅ animate ONLY (do NOT add duplicate row)
            BestDecision bd = decideBestAlgorithm();
            if (bd.best != null) {
                runOne(bd.best.name, true, hasNeg, false); // animation only
            }

        } else {
            if (!connected(src, dst)) {
                String road = getAnyBlockedRoadLabel();
                String msg;
                if (road != null) msg = "Traffic cannot proceed — Road " + road + " is blocked.";
                else msg = "No path from " + src.label + " to " + dst.label + "!";
                logHighlight(msg);
                JOptionPane.showMessageDialog(this, msg, "No Path", JOptionPane.ERROR_MESSAGE);
                return;
            }

            runOne(algo, true, hasNeg, true);
            updateOptimalFlags();
            highlightBest();
        }
    }

    private Node askValidated(String msg) {
        while (true) {
            String in = JOptionPane.showInputDialog(this, msg);
            if (in == null) return null;
            in = in.trim().toUpperCase();

            Node n = findNodeByLabel(in);
            if (n == null) {
                JOptionPane.showMessageDialog(
                        this,
                        "Node not found!\nAvailable: " + getAllNodeLabels(),
                        "Error",
                        JOptionPane.ERROR_MESSAGE
                );
            } else {
                return n;
            }
        }
    }

    private Node findNodeByLabel(String label) {
        for (Node n : nodes) {
            if (n.label.equalsIgnoreCase(label)) return n;
        }
        return null;
    }

    private String getAllNodeLabels() {
        if (nodes.isEmpty()) return "None";
        List<String> labels = new ArrayList<String>();
        for (Node n : nodes) labels.add(n.label);
        Collections.sort(labels);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < labels.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(labels.get(i));
        }
        return sb.toString();
    }

    // =====================================================
    //                    ALGORITHMS
    // =====================================================
    private PathResult bfs(Node start, Node goal, boolean anim) {
        Map<Node, List<Edge>> g = graph();
        Map<Node, Node> parent = new HashMap<Node, Node>();
        Set<Node> vis = new HashSet<Node>();
        Queue<Node> q = new LinkedList<Node>();

        q.add(start);
        vis.add(start);
        parent.put(start, null);
        int steps = 0;

        while (!q.isEmpty()) {
            Node cur = q.poll();
            visit(cur, anim);
            steps++;

            if (cur == goal) {
                List<Node> path = reconstruct(parent, start, goal);
                double cost = (path == null) ? Double.POSITIVE_INFINITY : computePathCost(path);
                return new PathResult(true, path, steps, false, null, cost);
            }

            List<Edge> list = g.get(cur);
            if (list != null) {
                for (Edge e : list) {
                    if (!vis.contains(e.to)) {
                        vis.add(e.to);
                        q.add(e.to);
                        parent.put(e.to, cur);
                    }
                }
            }
        }
        return new PathResult(false, null, steps, false, null, Double.POSITIVE_INFINITY);
    }

    private PathResult dfs(Node start, Node goal, boolean anim) {
        Map<Node, List<Edge>> g = graph();
        Map<Node, Node> parent = new HashMap<Node, Node>();
        Set<Node> vis = new HashSet<Node>();
        Stack<Node> st = new Stack<Node>();

        st.push(start);
        vis.add(start);
        parent.put(start, null);
        int steps = 0;

        while (!st.isEmpty()) {
            Node cur = st.pop();
            visit(cur, anim);
            steps++;

            if (cur == goal) {
                List<Node> path = reconstruct(parent, start, goal);
                double cost = (path == null) ? Double.POSITIVE_INFINITY : computePathCost(path);
                return new PathResult(true, path, steps, false, null, cost);
            }

            List<Edge> list = g.get(cur);
            if (list != null) {
                for (Edge e : list) {
                    if (!vis.contains(e.to)) {
                        vis.add(e.to);
                        st.push(e.to);
                        parent.put(e.to, cur);
                    }
                }
            }
        }
        return new PathResult(false, null, steps, false, null, Double.POSITIVE_INFINITY);
    }

    private PathResult dijkstra(Node start, Node goal, boolean anim) {
        Map<Node, List<Edge>> g = graph();
        Map<Node, Double> dist = new HashMap<Node, Double>();
        Map<Node, Node> parent = new HashMap<Node, Node>();

        for (Node n : nodes) dist.put(n, Double.POSITIVE_INFINITY);
        dist.put(start, 0.0);

        PriorityQueue<Node> pq = new PriorityQueue<Node>(11,
                new Comparator<Node>() {
                    public int compare(Node a, Node b) {
                        return Double.compare(dist.get(a), dist.get(b));
                    }
                });
        pq.add(start);

        int steps = 0;

        while (!pq.isEmpty()) {
            Node u = pq.poll();
            visit(u, anim);
            steps++;

            if (u == goal) {
                List<Node> path = reconstruct(parent, start, goal);
                double cost = dist.get(goal);
                return new PathResult(true, path, steps, false, null, cost);
            }

            List<Edge> list = g.get(u);
            if (list != null) {
                for (Edge e : list) {
                    steps++;
                    double alt = dist.get(u) + e.weight;
                    if (alt < dist.get(e.to)) {
                        dist.put(e.to, alt);
                        parent.put(e.to, u);
                        pq.add(e.to);
                    }
                }
            }
        }

        boolean found = dist.get(goal) < Double.POSITIVE_INFINITY;
        List<Node> path = found ? reconstruct(parent, start, goal) : null;
        double cost = found ? dist.get(goal) : Double.POSITIVE_INFINITY;
        return new PathResult(found, path, steps, false, null, cost);
    }

    private double heuristic(Node a, Node b) {
        return Math.hypot(a.x - b.x, a.y - b.y);
    }

    private PathResult aStar(Node start, Node goal, boolean anim) {
        Map<Node, List<Edge>> g = graph();
        Map<Node, Double> gScore = new HashMap<Node, Double>();
        Map<Node, Double> fScore = new HashMap<Node, Double>();
        Map<Node, Node> parent = new HashMap<Node, Node>();

        for (Node n : nodes) {
            gScore.put(n, Double.POSITIVE_INFINITY);
            fScore.put(n, Double.POSITIVE_INFINITY);
        }

        gScore.put(start, 0.0);
        fScore.put(start, heuristic(start, goal));

        PriorityQueue<Node> open = new PriorityQueue<Node>(11,
                new Comparator<Node>() {
                    public int compare(Node a, Node b) {
                        return Double.compare(fScore.get(a), fScore.get(b));
                    }
                });
        open.add(start);

        int steps = 0;

        while (!open.isEmpty()) {
            Node cur = open.poll();
            visit(cur, anim);
            steps++;

            if (cur == goal) {
                List<Node> path = reconstruct(parent, start, goal);
                double cost = gScore.get(goal);
                return new PathResult(true, path, steps, false, null, cost);
            }

            List<Edge> list = g.get(cur);
            if (list != null) {
                for (Edge e : list) {
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
        }
        return new PathResult(false, null, steps, false, null, Double.POSITIVE_INFINITY);
    }

    private PathResult bellmanFord(Node start, Node goal, boolean anim) {
        Map<Node, Double> dist = new HashMap<Node, Double>();
        Map<Node, Node> parent = new HashMap<Node, Node>();
        List<Map<Node, Double>> history = new ArrayList<Map<Node, Double>>();

        for (Node n : nodes) {
            dist.put(n, Double.POSITIVE_INFINITY);
            parent.put(n, null);
        }
        dist.put(start, 0.0);
        history.add(new HashMap<Node, Double>(dist));

        int steps = 0;

        List<Edge> list = new ArrayList<Edge>();
        for (Edge e : edges) {
            if (isBlocked(e)) continue;
            list.add(e);
            if (!directedMode) list.add(new Edge(e.to, e.from, e.weight));
        }

        for (int i = 0; i < nodes.size() - 1; i++) {
            boolean changed = false;
            for (Edge e : list) {
                if (dist.get(e.from) != Double.POSITIVE_INFINITY) {
                    double alt = dist.get(e.from) + e.weight;
                    if (alt < dist.get(e.to)) {
                        dist.put(e.to, alt);
                        parent.put(e.to, e.from);
                        changed = true;
                        steps++;

                        if (anim) {
                            e.from.color = Color.YELLOW;
                            e.to.color = Color.YELLOW;
                            canvas.repaint();
                            sleep(100);
                            e.from.color = new Color(0x4CAF50);
                            e.to.color = new Color(0x4CAF50);
                            canvas.repaint();
                            sleep(60);
                        }
                    }
                }
            }
            history.add(new HashMap<Node, Double>(dist));
            if (!changed) break;
        }

        boolean negCycle = false;
        for (Edge e : list) {
            if (dist.get(e.from) != Double.POSITIVE_INFINITY &&
                    dist.get(e.from) + e.weight < dist.get(e.to)) {
                negCycle = true;
                break;
            }
        }

        boolean found = dist.get(goal) < Double.POSITIVE_INFINITY;
        List<Node> path = found ? reconstruct(parent, start, goal) : null;
        double cost = found ? dist.get(goal) : Double.POSITIVE_INFINITY;

        return new PathResult(found, path, steps, negCycle, history, cost);
    }

    private PathResult greedy(Node start, Node goal, boolean anim) {
        Map<Node, List<Edge>> g = graph();
        Map<Node, Double> dist = new HashMap<Node, Double>();
        Map<Node, Node> parent = new HashMap<Node, Node>();
        Set<Node> visited = new HashSet<Node>();

        for (Node n : nodes) dist.put(n, Double.POSITIVE_INFINITY);
        dist.put(start, 0.0);
        parent.put(start, null);

        Node cur = start;
        int steps = 0;

        while (cur != null && !visited.contains(goal)) {
            visited.add(cur);
            visit(cur, anim);
            steps++;

            Edge best = null;
            double minW = Double.POSITIVE_INFINITY;

            List<Edge> list = g.get(cur);
            if (list != null) {
                for (Edge e : list) {
                    if (!visited.contains(e.to) && e.weight < minW) {
                        minW = e.weight;
                        best = e;
                    }
                }
            }

            if (best == null) break;

            dist.put(best.to, dist.get(cur) + best.weight);
            parent.put(best.to, cur);
            cur = best.to;
            steps++;

            if (anim) {
                best.from.color = Color.ORANGE;
                best.to.color = Color.ORANGE;
                canvas.repaint();
                sleep(120);
                best.from.color = new Color(0xFF9800);
                best.to.color = new Color(0xFF9800);
                canvas.repaint();
                sleep(80);
            }
        }

        boolean found = visited.contains(goal);
        List<Node> path = found ? reconstruct(parent, start, goal) : null;
        double cost = found ? dist.get(goal) : Double.POSITIVE_INFINITY;

        return new PathResult(found, path, steps, false, null, cost);
    }

    private List<Node> reconstruct(Map<Node, Node> parent, Node start, Node goal) {
        List<Node> path = new ArrayList<Node>();
        for (Node at = goal; at != null; at = parent.get(at)) {
            path.add(at);
        }
        if (path.isEmpty() || path.get(path.size() - 1) != start) return null;
        Collections.reverse(path);
        return path;
    }

    private double computePathCost(List<Node> path) {
        if (path == null || path.size() < 2) return Double.POSITIVE_INFINITY;
        double c = 0.0;
        for (int i = 0; i < path.size() - 1; i++) {
            Node a = path.get(i);
            Node b = path.get(i + 1);
            boolean foundEdge = false;
            for (Edge e : edges) {
                if (isBlocked(e)) continue;
                if (e.from == a && e.to == b) {
                    c += e.weight;
                    foundEdge = true;
                    break;
                } else if (!directedMode && e.from == b && e.to == a) {
                    c += e.weight;
                    foundEdge = true;
                    break;
                }
            }
            if (!foundEdge) return Double.POSITIVE_INFINITY;
        }
        return c;
    }

    private void updateBellmanFordTable(List<Map<Node, Double>> history) {
        if (history == null || history.isEmpty()) return;

        bfModel.setRowCount(0);
        bfModel.setColumnCount(0);

        bfModel.addColumn("Iter");
        for (Node n : nodes) bfModel.addColumn(n.label);

        for (int i = 0; i < history.size(); i++) {
            Object[] row = new Object[nodes.size() + 1];
            row[0] = Integer.valueOf(i);

            Map<Node, Double> dist = history.get(i);
            for (int j = 0; j < nodes.size(); j++) {
                Double d = dist.get(nodes.get(j));
                row[j + 1] = (d == null || d == Double.POSITIVE_INFINITY)
                        ? "∞" : String.format("%.0f", d);
            }
            bfModel.addRow(row);
        }
    }

    // Overload
    private void runOne(String name, boolean anim, boolean hasNeg) {
        runOne(name, anim, hasNeg, true);
    }

    // ✅ recordRow=false means "animate only" (no table duplicates)
    private void runOne(String name, boolean anim, boolean hasNeg, boolean recordRow) {
        boolean ok = false;
        int steps = 0;
        int plen = 0;
        double ms = 0;
        double cost = Double.POSITIVE_INFINITY;
        String reason = "";
        String complexity = "";

        if ("BFS".equals(name)) complexity = "O(V+E)";
        else if ("DFS".equals(name)) complexity = "O(V+E)";
        else if ("Dijkstra".equals(name)) complexity = "O((V+E)logV)";
        else if ("A*".equals(name)) complexity = "O((V+E)logV)";
        else if ("Bellman-Ford".equals(name)) complexity = "O(V×E)";
        else if ("Greedy".equals(name)) complexity = "O(E)";

        currentPath = null;
        final Holder<List<Map<Node, Double>>> hist = new Holder<List<Map<Node, Double>>>(null);

        try {
            if ((name.equals("Greedy") || name.equals("Dijkstra") ||
                    name.equals("A*")) && hasNeg) {
                reason = "Not executed (negative weights present)";
                ok = false;
            } else {
                PathResult res;
                long t0 = System.nanoTime();

                if ("BFS".equals(name)) {
                    res = bfs(src, dst, anim);
                } else if ("DFS".equals(name)) {
                    res = dfs(src, dst, anim);
                } else if ("Dijkstra".equals(name)) {
                    res = dijkstra(src, dst, anim);
                } else if ("A*".equals(name)) {
                    res = aStar(src, dst, anim);
                } else if ("Bellman-Ford".equals(name)) {
                    res = bellmanFord(src, dst, anim);
                    hist.value = res.distanceHistory;
                } else {
                    res = greedy(src, dst, anim);
                }

                long t1 = System.nanoTime();
                ms = (t1 - t0) / 1e6;

                ok = res.found && !res.hasNegativeCycle;
                steps = res.steps;
                cost = res.totalCost;
                plen = (res.path != null) ? res.path.size() : 0;

                if (res.hasNegativeCycle) {
                    reason = "Negative cycle detected";
                } else if (res.found) {
                    // ✅ small note for BFS/DFS on weighted graphs
                    if (("BFS".equals(name) || "DFS".equals(name)) && !areAllWeightsSamePositive()) {
                        reason = "Path found (Note: BFS/DFS not guaranteed optimal on weighted graphs)";
                    } else {
                        reason = "Path found";
                    }
                } else {
                    reason = "No path found.";
                }

                if (ok) currentPath = res.path;

                if ("Bellman-Ford".equals(name) && hist.value != null) {
                    final List<Map<Node, Double>> history = hist.value;
                    SwingUtilities.invokeLater(new Runnable() {
                        public void run() {
                            updateBellmanFordTable(history);
                        }
                    });
                }
            }

        } catch (Exception ex) {
            ok = false;
            reason = "Error: " + ex.getMessage();
        }

        if (recordRow) {
            // "Optimal?" placeholder now; will be filled by updateOptimalFlags()
            tblModel.addRow(new Object[]{
                    name,
                    String.format("%.2f", ms),
                    Integer.valueOf(steps),
                    (ok && ("BFS".equals(name) || "DFS".equals(name)))
                     ? "-"
                     : (ok ? String.format("%.2f", cost) : "N/A"),

                    Integer.valueOf(plen),
                    ok ? "—" : "—",
                    ok ? "Success" : "Failed",
                    reason,
                    complexity
            });

            results.add(new Result(name, ms, steps, cost, plen, ok, reason, complexity));
        }

        log(name + ": " + reason + (ok ? " (Cost=" + String.format("%.2f", cost) + ")" : ""));
    }

    // ✅ Mark optimal rows (min cost among successful algorithms)
    private void updateOptimalFlags() {
        double bestCost = Double.POSITIVE_INFINITY;
        for (Result r : results) {
            if (r.ok) bestCost = Math.min(bestCost, r.totalCost);
        }
        if (bestCost == Double.POSITIVE_INFINITY) return;

        // Update results flags
        for (Result r : results) {
            r.optimal = r.ok && Math.abs(r.totalCost - bestCost) < 1e-9;
        }

        // Update table column
        int optimalCol = 5;
        int statusCol = 6;

        for (int row = 0; row < tblModel.getRowCount(); row++) {
            Object algoObj = tblModel.getValueAt(row, 0);
            Object statusObj = tblModel.getValueAt(row, statusCol);

            if (algoObj == null || statusObj == null) continue;

            String algo = algoObj.toString();
            String status = statusObj.toString();

            if (!"Success".equals(status)) {
                tblModel.setValueAt("—", row, optimalCol);
                continue;
            }

            Result match = null;
            for (Result r : results) {
                if (r.name.equals(algo)) { match = r; break; }
            }

            if (match != null && match.optimal) tblModel.setValueAt("Yes", row, optimalCol);
            else tblModel.setValueAt("No", row, optimalCol);
        }
    }

    // ✅ best decision based on: case rules + optimal cost + steps + time
    private BestDecision decideBestAlgorithm() {
        BestDecision bd = new BestDecision();

        boolean hasNeg = hasNegativeWeights();
        boolean allSame = areAllWeightsSamePositive();

        List<Result> ok = new ArrayList<>();
       for (Result r : results) {
       if (!r.ok) continue;

       if (!areAllWeightsSamePositive()
            && ("BFS".equals(r.name) || "DFS".equals(r.name))) {
        continue;
    }

    ok.add(r);
}


        if (ok.isEmpty()) {
            bd.best = null;
            bd.explanation = "No valid successful result found.";
            return bd;
        }

        // Negative weights => Bellman-Ford is required
        if (hasNeg) {
            for (Result r : ok) {
                if ("Bellman-Ford".equals(r.name)) {
                    bd.best = r;
                    bd.explanation = "Negative weights detected → Bellman-Ford is required for correctness.";
                    return bd;
                }
            }
        }

        // Unweighted/equal weights => BFS best
        if (allSame) {
            for (Result r : ok) {
                if ("BFS".equals(r.name)) {
                    bd.best = r;
                    bd.explanation = "All edge weights equal → BFS is optimal (shortest-hop) and efficient.";
                    return bd;
                }
            }
        }

        // General: optimal cost first
        double bestCost = Double.POSITIVE_INFINITY;
        for (Result r : ok) bestCost = Math.min(bestCost, r.totalCost);

        List<Result> optimal = new ArrayList<>();
        for (Result r : ok) {
            if (Math.abs(r.totalCost - bestCost) < 1e-9) optimal.add(r);
        }

        // Prefer Dijkstra/A* over BFS/DFS on weighted graphs (academic correctness)
        List<Result> filtered = new ArrayList<>();
        if (!allSame) {
            for (Result r : optimal) {
                if (!"BFS".equals(r.name) && !"DFS".equals(r.name)) filtered.add(r);
            }
            if (filtered.isEmpty()) filtered = optimal; // fallback if only BFS/DFS succeeded
        } else {
            filtered = optimal;
        }

        Result best = null;
        for (Result r : filtered) {
            if (best == null) best = r;
            else if (r.steps < best.steps) best = r;
            else if (r.steps == best.steps && r.timeMs < best.timeMs) best = r;
        }

        bd.best = best;

        if (best == null) {
            bd.explanation = "Could not determine a best algorithm.";
            return bd;
        }

        if ("A*".equals(best.name)) {
            bd.explanation = "A* achieved optimal cost with fewer operations → best balance for this case.";
        } else if ("Dijkstra".equals(best.name)) {
            bd.explanation = "Dijkstra guarantees optimal shortest path for non-negative weights → best safe choice.";
        } else if ("Bellman-Ford".equals(best.name)) {
            bd.explanation = "Bellman-Ford handles negative weights (slower but correct) → best for this case.";
        } else if ("Greedy".equals(best.name)) {
            bd.explanation = "Greedy matched the optimal cost in this instance and was efficient (not guaranteed generally).";
        } else if ("BFS".equals(best.name)) {
            bd.explanation = "BFS is optimal on unweighted/equal-weight graphs and efficient here.";
        } else {
            bd.explanation = "Selected based on optimal cost, then steps/time tie-break.";
        }

        return bd;
    }

    private void highlightBest() {
        BestDecision bd = decideBestAlgorithm();
        if (bd.best != null) {
            bestLbl.setText("Best: " + bd.best.name +
                    " (cost " + String.format("%.2f", bd.best.totalCost) +
                    ", steps " + bd.best.steps +
                    ", " + String.format("%.2f ms", bd.best.timeMs) + ")");
            logHighlight("Best Decision: " + bd.best.name);
            log("Why: " + bd.explanation);
        } else {
            bestLbl.setText("Best: —");
            logHighlight("Best Decision: —");
            log("Why: " + (bd.explanation == null ? "" : bd.explanation));
        }
    }

    // =====================================================
    // VEHICLE SCHEDULING + TSP (UNCHANGED)
    // =====================================================
    private void runVehicleSchedulingDialog() {
        try {
            String vIn = JOptionPane.showInputDialog(
                    this,
                    "Enter number of vehicles (buses/taxis):",
                    "3"
            );
            if (vIn == null) return;
            int m = Integer.parseInt(vIn.trim());
            if (m <= 0) {
                JOptionPane.showMessageDialog(this, "Vehicles must be > 0");
                return;
            }

            String rIn = JOptionPane.showInputDialog(
                    this,
                    "Enter route times (comma-separated, e.g. 10,20,30):",
                    "10,20,15,25"
            );
            if (rIn == null) return;
            String[] parts = rIn.split(",");
            List<Double> ts = new ArrayList<Double>();
            for (String p : parts) {
                String s = p.trim();
                if (s.isEmpty()) continue;
                double val = Double.parseDouble(s);
                if (val <= 0) {
                    JOptionPane.showMessageDialog(this, "Route times must be positive.");
                    return;
                }
                ts.add(val);
            }
            if (ts.isEmpty()) {
                JOptionPane.showMessageDialog(this, "No valid routes provided.");
                return;
            }

            double[] times = new double[ts.size()];
            for (int i = 0; i < ts.size(); i++) times[i] = ts.get(i);

            log("========== Vehicle Scheduling ==========");
            log("Routes: " + times.length + ", Vehicles: " + m);

            SchedResult greedy = scheduleGreedy(times, m);
            logHighlight("Greedy makespan: " + String.format("%.2f", greedy.makespan));
            log(formatAssignment("Greedy assignment:", greedy, times));

            SchedResult optimal = null;
            if (times.length <= 12) {
                optimal = scheduleBranchAndBound(times, m, greedy);

                if (optimal != null) {
                    logHighlight("Optimal (Branch-and-Bound) makespan: " +
                            String.format("%.2f", optimal.makespan));
                    log(formatAssignment("Optimal assignment:", optimal, times));
                } else {
                    log("Branch-and-Bound could not improve greedy solution.");
                }
            } else {
                logHighlight("Optimal (Branch-and-Bound) skipped (routes > 12).");
            }

            JOptionPane.showMessageDialog(
                    this,
                    "Vehicle Scheduling completed.\nCheck logs for details.",
                    "Vehicle Scheduling",
                    JOptionPane.INFORMATION_MESSAGE
            );

        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Error: " + ex.getMessage());
        }
    }

    private SchedResult scheduleGreedy(double[] times, int m) {
        int n = times.length;
        SchedResult res = new SchedResult();
        res.assignment = new ArrayList<List<Integer>>();
        double[] loads = new double[m];
        for (int i = 0; i < m; i++) {
            res.assignment.add(new ArrayList<Integer>());
            loads[i] = 0.0;
        }

        Integer[] idx = new Integer[n];
        for (int i = 0; i < n; i++) idx[i] = i;
        Arrays.sort(idx, new Comparator<Integer>() {
            public int compare(Integer a, Integer b) {
                return -Double.compare(times[a], times[b]);
            }
        });

        for (int k = 0; k < n; k++) {
            int job = idx[k];
            int bestMachine = 0;
            double minLoad = loads[0];
            for (int i = 1; i < m; i++) {
                if (loads[i] < minLoad) {
                    minLoad = loads[i];
                    bestMachine = i;
                }
            }
            loads[bestMachine] += times[job];
            res.assignment.get(bestMachine).add(job);
        }

        double maxLoad = 0;
        for (int i = 0; i < m; i++) {
            if (loads[i] > maxLoad) maxLoad = loads[i];
        }
        res.makespan = maxLoad;
        return res;
    }

    private SchedResult scheduleBranchAndBound(double[] times, int m, SchedResult greedySeed) {
    // Seed best with greedy so even if BnB doesn't improve, we still have a valid assignment to print
    SchedResult best = new SchedResult();
    best.makespan = greedySeed.makespan;

    best.assignment = new ArrayList<List<Integer>>();
    for (int i = 0; i < m; i++) {
        best.assignment.add(new ArrayList<Integer>(greedySeed.assignment.get(i)));
    }

    // Start BnB search from empty assignment (BnB will try to improve best.makespan)
    double[] curLoads = new double[m];
    List<List<Integer>> currentAssign = new ArrayList<List<Integer>>();
    for (int i = 0; i < m; i++) {
        currentAssign.add(new ArrayList<Integer>());
        curLoads[i] = 0.0;
    }

    assignJobBB(0, times, curLoads, currentAssign, best, m);
    return best;
}

    private void assignJobBB(int jobIndex, double[] times, double[] loads,
                             List<List<Integer>> currentAssign,
                             SchedResult best, int m) {
        int n = times.length;
        if (jobIndex == n) {
            double maxLoad = 0;
            for (int i = 0; i < m; i++) if (loads[i] > maxLoad) maxLoad = loads[i];
            if (maxLoad < best.makespan) {
                best.makespan = maxLoad;
                best.assignment = new ArrayList<List<Integer>>();
                for (int i = 0; i < m; i++) {
                    best.assignment.add(new ArrayList<Integer>(currentAssign.get(i)));
                }
            }
            return;
        }

        for (int i = 0; i < m; i++) {
            currentAssign.get(i).add(jobIndex);
            loads[i] += times[jobIndex];

            double currentMax = 0;
            for (int k = 0; k < m; k++) if (loads[k] > currentMax) currentMax = loads[k];

            if (currentMax < best.makespan) assignJobBB(jobIndex + 1, times, loads, currentAssign, best, m);

            loads[i] -= times[jobIndex];
            currentAssign.get(i).remove(currentAssign.get(i).size() - 1);

            if (loads[i] == 0) break;
        }
    }

    private String formatAssignment(String title, SchedResult r, double[] times) {
        StringBuilder sb = new StringBuilder();
        sb.append(title).append("\n");
        for (int i = 0; i < r.assignment.size(); i++) {
            sb.append("Vehicle ").append(i + 1).append(": ");
            double sum = 0;
            for (int job : r.assignment.get(i)) {
                sb.append("R").append(job + 1)
                        .append("(").append(times[job]).append(") ");
                sum += times[job];
            }
            sb.append(" | total = ").append(String.format("%.2f", sum)).append("\n");
        }
        return sb.toString();
    }

    private void runTSPModule() {
        // (kept as in your code; no functional changes)
        if (nodes.size() < 3) {
            JOptionPane.showMessageDialog(this, "TSP requires at least 3 nodes.", "TSP", JOptionPane.WARNING_MESSAGE);
            return;
        }

        try {
            String in = JOptionPane.showInputDialog(this,
                    "Enter TSP node labels (comma-separated, or empty for all nodes):", "");
            if (in == null) return;

            List<Node> tspNodes = new ArrayList<Node>();
            if (in.trim().isEmpty()) tspNodes.addAll(nodes);
            else {
                String[] parts = in.split(",");
                for (String p : parts) {
                    String lbl = p.trim().toUpperCase();
                    if (lbl.isEmpty()) continue;
                    Node n = findNodeByLabel(lbl);
                    if (n == null) {
                        JOptionPane.showMessageDialog(this, "Node " + lbl + " not found.", "TSP",
                                JOptionPane.ERROR_MESSAGE);
                        return;
                    }
                    tspNodes.add(n);
                }
            }

            if (tspNodes.size() < 3) {
                JOptionPane.showMessageDialog(this, "TSP requires at least 3 valid nodes.", "TSP",
                        JOptionPane.WARNING_MESSAGE);
                return;
            }

            log("========== TSP Module ==========");
            log("TSP nodes: " + labelsOfNodes(tspNodes));

            List<Node> nnTour = tspNearestNeighbor(tspNodes);
            double nnCost = (nnTour == null) ? Double.POSITIVE_INFINITY : tourCost(nnTour);

            if (nnTour != null) {
                logHighlight("TSP Heuristic (Nearest Neighbor) cost: " + String.format("%.2f", nnCost));
                log("NN Tour: " + labelsOfNodes(nnTour));
            } else {
                logHighlight("TSP Heuristic failed (graph not fully connected for selected nodes).");
            }

            List<Node> exactTour = null;
            double exactCost = Double.POSITIVE_INFINITY;
            if (tspNodes.size() <= 10) {
                List<Node> et = tspExactBranchAndBound(tspNodes);
                if (et != null) {
                    exactTour = et;
                    exactCost = tourCost(exactTour);
                    logHighlight("TSP Exact (Branch-and-Bound) cost: " + String.format("%.2f", exactCost));
                    log("Exact Tour: " + labelsOfNodes(exactTour));
                } else {
                    logHighlight("TSP Exact could not find a full tour (disconnected edges).");
                }
            } else {
                logHighlight("TSP Exact skipped (nodes > 10).");
            }

            if (exactTour != null && exactCost <= nnCost) {
                currentPath = exactTour;
                log("Canvas path: Exact TSP tour.");
            } else if (nnTour != null) {
                currentPath = nnTour;
                log("Canvas path: Heuristic TSP tour.");
            } else {
                currentPath = null;
            }

            canvas.repaint();

            JOptionPane.showMessageDialog(this, "TSP computation completed.\nCheck logs for details.",
                    "TSP", JOptionPane.INFORMATION_MESSAGE);

        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Error in TSP: " + ex.getMessage());
        }
    }

    private String labelsOfNodes(List<Node> list) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(" → ");
            sb.append(list.get(i).label);
        }
        return sb.toString();
    }

    private double getEdgeWeight(Node a, Node b) {
        for (Edge e : edges) {
            if (isBlocked(e)) continue;
            if (e.from == a && e.to == b) return e.weight;
            if (!directedMode && e.from == b && e.to == a) return e.weight;
        }
        return INF;
    }

    private double tourCost(List<Node> tour) {
        if (tour == null || tour.size() < 2) return Double.POSITIVE_INFINITY;
        double cost = 0.0;
        for (int i = 0; i < tour.size() - 1; i++) {
            double w = getEdgeWeight(tour.get(i), tour.get(i + 1));
            if (w >= INF) return Double.POSITIVE_INFINITY;
            cost += w;
        }
        return cost;
    }

    private List<Node> tspNearestNeighbor(List<Node> tspNodes) {
        int n = tspNodes.size();
        boolean[] used = new boolean[n];
        List<Node> tour = new ArrayList<Node>();

        int current = 0;
        used[current] = true;
        tour.add(tspNodes.get(current));

        for (int step = 1; step < n; step++) {
            int next = -1;
            double best = INF;
            for (int j = 0; j < n; j++) {
                if (!used[j]) {
                    double w = getEdgeWeight(tspNodes.get(current), tspNodes.get(j));
                    if (w < best) {
                        best = w;
                        next = j;
                    }
                }
            }
            if (next == -1 || best >= INF) return null;
            used[next] = true;
            tour.add(tspNodes.get(next));
            current = next;
        }

        tour.add(tspNodes.get(0));
        return tour;
    }

    private List<Node> tspExactBranchAndBound(List<Node> tspNodes) {
        int n = tspNodes.size();
        double[][] cost = new double[n][n];

        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                if (i == j) cost[i][j] = INF;
                else cost[i][j] = getEdgeWeight(tspNodes.get(i), tspNodes.get(j));
            }
        }

        for (int i = 0; i < n; i++) {
            boolean hasOut = false;
            boolean hasIn = false;
            for (int j = 0; j < n; j++) {
                if (i == j) continue;
                if (cost[i][j] < INF) hasOut = true;
                if (cost[j][i] < INF) hasIn = true;
            }
            if (!hasOut || !hasIn) return null;
        }

        boolean[] used = new boolean[n];
        int[] path = new int[n];
        TSPState state = new TSPState();
        used[0] = true;
        path[0] = 0;

        tspBBRec(0, 1, 0.0, used, path, state, cost, n);

        if (!state.found || state.bestCost >= INF) return null;

        List<Node> tour = new ArrayList<Node>();
        for (int i = 0; i < n; i++) tour.add(tspNodes.get(state.bestPath[i]));
        tour.add(tspNodes.get(state.bestPath[0]));
        return tour;
    }

    private void tspBBRec(int current, int level, double currentCost,
                          boolean[] used, int[] path,
                          TSPState state, double[][] cost, int n) {
        if (currentCost >= state.bestCost) return;

        if (level == n) {
            double back = cost[current][path[0]];
            if (back >= INF) return;
            double total = currentCost + back;
            if (total < state.bestCost) {
                state.bestCost = total;
                state.bestPath = path.clone();
                state.found = true;
            }
            return;
        }

        for (int next = 1; next < n; next++) {
            if (!used[next] && cost[current][next] < INF) {
                used[next] = true;
                path[level] = next;
                tspBBRec(next, level + 1, currentCost + cost[current][next],
                        used, path, state, cost, n);
                used[next] = false;
            }
        }
    }

    private void visit(Node n, boolean anim) {
        try {
            n.color = Color.YELLOW;
            canvas.repaint();
            if (anim) Thread.sleep(150);

            n.color = new Color(0x4CAF50);
            canvas.repaint();
            if (anim) Thread.sleep(80);
        } catch (Exception ignored) {}
    }

    private void sleep(int ms) {
        try { Thread.sleep(ms); } catch (Exception ignored) {}
    }

    private void resetColors() {
        for (Node n : nodes) n.color = new Color(0x007BFF);
        if (src != null) src.color = Color.CYAN;
        if (dst != null) dst.color = Color.MAGENTA;
        canvas.repaint();
    }

    private void restart() {
        nodes.clear();
        edges.clear();
        first = hover = src = dst = null;
        currentPath = null;
        nodeId = 0;

        results.clear();
        tblModel.setRowCount(0);
        bfModel.setRowCount(0);
        bfModel.setColumnCount(0);

        try {
            StyledDocument doc = logArea.getStyledDocument();
            doc.remove(0, doc.getLength());
        } catch (BadLocationException ignored) {}

        bestLbl.setText("Best: —");
        trafficManager.clearCache();
        undoStack.clear();

        canvas.repaint();
        log("Restarted.");
    }

    private void exportCSV() {
        try {
            String fn = "results_" +
                    new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date()) +
                    ".csv";
            PrintWriter pw = new PrintWriter(fn);
            pw.println("Algorithm,Time(ms),Steps,TotalCost,PathLength,Optimal,Status,Reason,TimeComplexity");
            for (Result r : results) {
                pw.println(r.name + "," + r.timeMs + "," + r.steps + "," + r.totalCost
                        + "," + r.pathLen + "," + (r.optimal ? "Yes" : "No")
                        + "," + (r.ok ? "Success" : "Failed")
                        + "," + r.reason + "," + r.complexity);
            }
            pw.close();
            log("Exported: " + fn);
        } catch (Exception e) {
            log("Export error: " + e.getMessage());
        }
    }

    private void log(String s) {
        StyledDocument doc = logArea.getStyledDocument();
        try {
            doc.insertString(doc.getLength(), s + "\n", normalStyle);
            logArea.setCaretPosition(doc.getLength());
        } catch (BadLocationException ignored) {}
    }

    private void logHighlight(String s) {
        StyledDocument doc = logArea.getStyledDocument();
        try {
            doc.insertString(doc.getLength(), s + "\n", highlightStyle);
            logArea.setCaretPosition(doc.getLength());
        } catch (BadLocationException ignored) {}
    }

    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel("javax.swing.plaf.nimbus.NimbusLookAndFeel");
        } catch (Exception ignored) {}

        SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                new Daa_smartCity();
            }
        });
    }
}
=======
import java.awt.*;
import java.awt.event.*;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;
import java.util.Queue;

import javax.swing.*;
import javax.swing.table.*;
import javax.swing.text.*;
import javax.swing.border.EmptyBorder;

public class Daa_smartCity extends JFrame {

    // ======= Graph Node / Edge =======
    public static class Node {
        int x, y;
        String label;
        Color color = new Color(0x007BFF);

        public Node(int x, int y, String label) {
            this.x = x;
            this.y = y;
            this.label = label;
        }

        @Override
        public String toString() { return label; }
    }

    public static class Edge {
        public Node from, to;
        public double weight;

        public Edge(Node f, Node t, double w) {
            from = f;
            to = t;
            weight = w;
        }
    }

    // ======= Result row for table =======
    public static class Result {
        String name;
        double timeMs;
        int steps;
        double totalCost;
        int pathLen;
        boolean ok;
        String reason;
        String complexity;
        boolean optimal; // 

        public Result(String n, double t, int s, double tc, int pl,
                      boolean ok, String r, String c) {
            name = n;
            timeMs = t;
            steps = s;
            totalCost = tc;
            pathLen = pl;
            this.ok = ok;
            reason = r;
            complexity = c;
            optimal = false;
        }
    }

    // ======= Internal algorithm result =======
    public static class PathResult {
        public boolean found;
        public List<Node> path;
        public int steps;
        public boolean hasNegativeCycle;
        public List<Map<Node, Double>> distanceHistory;
        public double totalCost;

        public PathResult(boolean f, List<Node> p, int s,
                          boolean nc, List<Map<Node, Double>> hist, double tc) {
            found = f;
            path = p;
            steps = s;
            hasNegativeCycle = nc;
            distanceHistory = hist;
            totalCost = tc;
        }
    }

    static class Holder<T> { T value; Holder(T v){ value = v; } }

    // ===== Undo support =====
    private interface UndoableAction {
        void undo();
    }

    private final Deque<UndoableAction> undoStack = new ArrayDeque<>();

    // ======= Data =======
    private final List<Node> nodes = new ArrayList<Node>();
    private final List<Edge> edges = new ArrayList<Edge>();
    private int nodeId = 0;

    private Node first = null, hover = null;
    private Node src = null, dst = null;
    private List<Node> currentPath = null;

    private boolean directedMode = true;

    private GraphPanel canvas;

    // JTextPane for colored logs
    private JTextPane logArea;
    private Style normalStyle;
    private Style highlightStyle;

    private JTable table;
    private DefaultTableModel tblModel;
    private JTable bfTable;
    private DefaultTableModel bfModel;
    private JLabel bestLbl;
    private final List<Result> results = new ArrayList<Result>();

    // Dynamic traffic manager
    private final DynamicTrafficManager trafficManager = new DynamicTrafficManager();

    // ======= Background Image =======
    private Image bgImage;

    // ======= Blocked road rule =======
    private static final double BLOCKED_THRESHOLD = 9999.0;
    private static final double INF = 1e12;

    private boolean isBlocked(Edge e) {
        return e.weight >= BLOCKED_THRESHOLD || Double.isInfinite(e.weight);
    }

    private String getAnyBlockedRoadLabel() {
        for (Edge e : edges) {
            if (isBlocked(e)) {
                return e.from.label + " → " + e.to.label;
            }
        }
        return null;
    }

    private void pushUndo(UndoableAction action) {
        undoStack.push(action);
    }

    private void undoLastAction() {
        if (undoStack.isEmpty()) {
            JOptionPane.showMessageDialog(
                    this,
                    "Nothing to undo.",
                    "Undo",
                    JOptionPane.INFORMATION_MESSAGE
            );
            return;
        }
        UndoableAction act = undoStack.pop();
        act.undo();
        canvas.repaint();
    }

    // ===== Button style constants =====
    private static final Color BUTTON_BG = new Color(0xE3F2FD);
    private static final Color BUTTON_FG = new Color(0x0D47A1);
    private static final int BUTTON_RADIUS = 18;

    // ===== Vehicle Scheduling helper =====
    private static class SchedResult {
        double makespan;
        List<List<Integer>> assignment;
    }

    // ===== TSP Branch&Bound helper =====
    private static class TSPState {
        double bestCost = INF;
        int[] bestPath;
        boolean found = false;
    }

    // ===== BEST DECISION helper =====
    private static class BestDecision {
        Result best;
        String explanation;
    }

    // =====================================================
    //                    CONSTRUCTOR
    // =====================================================
    public Daa_smartCity() {
        super("Smart City Transportation Optimizer – Clean Version");
        setLayout(new BorderLayout());

        canvas = new GraphPanel();
        canvas.setPreferredSize(new Dimension(900, 600));

        // ===== Load background image from same package/folder =====
        try {
            java.net.URL imgUrl = getClass().getResource("map.jpg");
            if (imgUrl != null) {
                bgImage = new ImageIcon(imgUrl).getImage();
            } else {
                System.out.println("map.jpg not found in classpath");
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        // ---------- Right side panel ----------
        JPanel side = new JPanel(new BorderLayout());
        side.setPreferredSize(new Dimension(420, 650));

        // ===== Controls (buttons) =====
        JPanel ctrl = new JPanel(new GridLayout(0, 1, 4, 4));
        ctrl.setBackground(new Color(200, 225, 255));

        String[] algs = {"Run All","BFS","DFS","Dijkstra","A*","Bellman-Ford","Greedy"};
        final JComboBox<String> box = new JComboBox<String>(algs);

        JButton run = btn("Run", new Color(0x007BFF));
        run.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                runAlgo((String) box.getSelectedItem());
            }
        });

        JButton rst = btn("Restart", new Color(0x333333));
        rst.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                restart();
            }
        });

        JButton modeBtn = btn("Switch Mode: DIRECTED", new Color(0x6A1B9A));
        modeBtn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                directedMode = !directedMode;
                modeBtn.setText("Switch Mode: " + (directedMode ? "DIRECTED" : "UNDIRECTED"));
                log("Graph mode: " + (directedMode ? "DIRECTED" : "UNDIRECTED"));
                canvas.repaint();
            }
        });

        // ===== Dynamic Traffic Buttons =====
        JButton randomTrafficBtn = btn("Random Traffic", new Color(0xFF7043));
        randomTrafficBtn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                final List<DynamicTrafficManager.EdgeChange> changes =
                        trafficManager.applyRandomTraffic(
                                edges,
                                Math.max(1, edges.size() / 4),
                                1, 6, new Random()
                        );

                if (changes != null && !changes.isEmpty()) {
                    for (DynamicTrafficManager.EdgeChange ch : changes) {
                        Edge ed = ch.edge;
                        String msg = String.format(
                                "Traffic update: %s → %s | old: %.1f, new: %.1f",
                                ed.from.label, ed.to.label,
                                ch.oldWeight, ch.newWeight
                        );
                        logHighlight(msg);
                    }
                }
                canvas.repaint();
            }
        });

        JButton rushHourBtn = btn("Rush Hour", new Color(0xD32F2F));
        rushHourBtn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                final List<DynamicTrafficManager.EdgeChange> changes =
                        trafficManager.applyRushHour(edges, 1.8);

                if (changes != null && !changes.isEmpty()) {
                    for (DynamicTrafficManager.EdgeChange ch : changes) {
                        Edge ed = ch.edge;
                        String msg = String.format(
                                "Traffic update: %s → %s | old: %.1f, new: %.1f",
                                ed.from.label, ed.to.label,
                                ch.oldWeight, ch.newWeight
                        );
                        logHighlight(msg);
                    }
                }
                canvas.repaint();
            }
        });

        JButton nightModeBtn = btn("Night Mode", new Color(0x388E3C));
        nightModeBtn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                final List<DynamicTrafficManager.EdgeChange> changes =
                        trafficManager.applyNightMode(edges, 0.7);

                if (changes != null && !changes.isEmpty()) {
                    for (DynamicTrafficManager.EdgeChange ch : changes) {
                        Edge ed = ch.edge;
                        String msg = String.format(
                                "Traffic update: %s → %s | old: %.1f, new: %.1f",
                                ed.from.label, ed.to.label,
                                ch.oldWeight, ch.newWeight
                        );
                        logHighlight(msg);
                    }
                }
                canvas.repaint();
            }
        });

        JButton roadBlockBtn = btn("Road Block", new Color(0x8E24AA));
        roadBlockBtn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                final List<DynamicTrafficManager.EdgeChange> changes =
                        trafficManager.applyRoadBlock(
                                edges,
                                Math.max(1, edges.size() / 5),
                                BLOCKED_THRESHOLD,
                                new Random()
                        );

                if (changes != null && !changes.isEmpty()) {
                    for (DynamicTrafficManager.EdgeChange ch : changes) {
                        Edge ed = ch.edge;
                        String msg = String.format(
                                "Traffic update: %s → %s | old: %.1f, new: %.1f",
                                ed.from.label, ed.to.label,
                                ch.oldWeight, ch.newWeight
                        );
                        logHighlight(msg);
                    }
                }
                canvas.repaint();
            }
        });

        JButton periodicBtn = btn("Periodic Traffic", new Color(0x039BE5));
        periodicBtn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                final List<DynamicTrafficManager.EdgeChange> changes =
                        trafficManager.applyPeriodicRandomTraffic(edges, new Random());

                if (changes != null && !changes.isEmpty()) {
                    for (DynamicTrafficManager.EdgeChange ch : changes) {
                        Edge ed = ch.edge;
                        String msg = String.format(
                                "Traffic update: %s → %s | old: %.1f, new: %.1f",
                                ed.from.label, ed.to.label,
                                ch.oldWeight, ch.newWeight
                        );
                        logHighlight(msg);
                    }
                }
                canvas.repaint();
            }
        });

        JButton dynRouteBtn = btn("Dynamic Route (Hybrid)", new Color(0x0097A7));
        dynRouteBtn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                if (src == null || dst == null) {
                    JOptionPane.showMessageDialog(
                            Daa_smartCity.this,
                            "Run any algorithm first to set Source and Destination.",
                            "No Source/Destination",
                            JOptionPane.WARNING_MESSAGE
                    );
                    return;
                }

                PathResult res = trafficManager.recomputeShortestPath(
                        src, dst, nodes, edges, directedMode
                );

                if (res != null && res.found && !res.hasNegativeCycle) {
                    currentPath = res.path;
                    log("Dynamic Routing (Hybrid) updated. New cost = " +
                            String.format("%.2f", res.totalCost));
                    canvas.repaint();
                } else {
                    String blockedRoad = getAnyBlockedRoadLabel();
                    if (blockedRoad != null) {
                        String msg = "Traffic cannot proceed — Road " + blockedRoad + " is blocked.";
                        logHighlight(msg);
                        JOptionPane.showMessageDialog(
                                Daa_smartCity.this,
                                msg,
                                "Routing Failed",
                                JOptionPane.WARNING_MESSAGE
                        );
                    } else {
                        log("Dynamic Routing: No valid path after traffic update.");
                    }
                }
            }
        });

        // ===== New module buttons =====
        JButton schedulingBtn = btn("Vehicle Scheduling", new Color(0x0277BD));
        schedulingBtn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                runVehicleSchedulingDialog();
            }
        });

        JButton tspBtn = btn("TSP (Exact + Heuristic)", new Color(0x7B1FA2));
        tspBtn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                runTSPModule();
            }
        });

        // Add controls
        ctrl.add(box);
        ctrl.add(run);
        ctrl.add(rst);
        ctrl.add(modeBtn);
        ctrl.add(randomTrafficBtn);
        ctrl.add(rushHourBtn);
        ctrl.add(nightModeBtn);
        ctrl.add(roadBlockBtn);
        ctrl.add(periodicBtn);
        ctrl.add(dynRouteBtn);
        ctrl.add(schedulingBtn);
        ctrl.add(tspBtn);

        JScrollPane ctrlScroll = new JScrollPane(ctrl);
        ctrlScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        ctrlScroll.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        ctrlScroll.setBorder(null);
        ctrlScroll.setPreferredSize(new Dimension(420, 210));

        // ===== Log / Tables =====
        logArea = new JTextPane();
        logArea.setEditable(false);
        logArea.setBackground(new Color(227, 242, 253));
        logArea.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));

        StyledDocument doc = logArea.getStyledDocument();
        normalStyle = doc.addStyle("normal", null);
        StyleConstants.setForeground(normalStyle, new Color(0x4E342E));
        StyleConstants.setFontFamily(normalStyle, "Monospaced");
        StyleConstants.setFontSize(normalStyle, 12);

        highlightStyle = doc.addStyle("highlight", null);
        StyleConstants.setForeground(highlightStyle, new Color(0xBF360C));
        StyleConstants.setBold(highlightStyle, true);
        StyleConstants.setFontFamily(highlightStyle, "Monospaced");
        StyleConstants.setFontSize(highlightStyle, 12);

        JScrollPane logSc = new JScrollPane(logArea);

        //  NEW COLUMN: "Optimal?"
        String[] cols = {"Algorithm","Time (ms)","Steps","Total Cost","Path Len","Optimal?","Status","Reason","Complexity"};
        tblModel = new DefaultTableModel(cols, 0);
        table = new JTable(tblModel);
        table.setFont(new Font("SansSerif", Font.PLAIN, 14));
        table.setRowHeight(30);
        table.setFillsViewportHeight(true);
        styleTable(table);
        JScrollPane tblSc = new JScrollPane(table);
        tblSc.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);
        tblSc.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);

        bfModel = new DefaultTableModel();
        bfTable = new JTable(bfModel);
        bfTable.setFont(new Font("Monospaced", Font.PLAIN, 13));
        bfTable.setRowHeight(26);
        bfTable.setFillsViewportHeight(true);
        styleTable(bfTable);
        JScrollPane bfSc = new JScrollPane(bfTable);
        bfSc.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);
        bfSc.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);

        bestLbl = new JLabel("Best: —", SwingConstants.CENTER);
        bestLbl.setFont(new Font("SansSerif", Font.BOLD, 14));
        bestLbl.setForeground(new Color(0x007BFF));

        JButton exp = btn("Export CSV", new Color(0x455A64));
        exp.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                exportCSV();
            }
        });

        JPanel bot = new JPanel(new BorderLayout());
        bot.add(bestLbl, BorderLayout.CENTER);
        bot.add(exp, BorderLayout.SOUTH);

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Log", logSc);
        tabs.addTab("Results", new JPanel(new BorderLayout()) {{
            add(tblSc, BorderLayout.CENTER);
            add(bot, BorderLayout.SOUTH);
        }});
        tabs.addTab("Bellman-Ford Steps", bfSc);

        side.add(ctrlScroll, BorderLayout.NORTH);
        side.add(tabs,      BorderLayout.CENTER);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, canvas, side);
        split.setDividerLocation(900);

        // ===== TOP NAV BAR BUTTONS =====
        JButton deleteNodeBtn = btn("Delete Node", new Color(0xE53935));
        deleteNodeBtn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                Node target = askNodeOnce("Enter Node to delete (A, B, C...):");
                if (target != null) {
                    deleteNode(target, true);
                }
            }
        });

        JButton deleteEdgeBtn = btn("Delete Edge", new Color(0xFB8C00));
        deleteEdgeBtn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                Node from = askNodeOnce("Enter FROM node of edge to delete:");
                if (from == null) return;
                Node to = askNodeOnce("Enter TO node of edge to delete:");
                if (to == null) return;

                Edge target = findEdge(from, to);
                if (target == null) {
                    JOptionPane.showMessageDialog(
                            Daa_smartCity.this,
                            "Edge " + from.label + " → " + to.label + " not found.",
                            "Delete Edge",
                            JOptionPane.WARNING_MESSAGE
                    );
                } else {
                    deleteEdge(target, true);
                }
            }
        });

        JButton undoBtn = btn("Undo", new Color(0x546E7A));
        undoBtn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                undoLastAction();
            }
        });

        JPanel topBar = new JPanel(new FlowLayout(FlowLayout.LEFT));
        topBar.add(deleteNodeBtn);
        topBar.add(deleteEdgeBtn);
        topBar.add(undoBtn);
        topBar.setBackground(new Color(200, 225, 255));

        add(topBar, BorderLayout.NORTH);
        add(split, BorderLayout.CENTER);

        setDefaultCloseOperation(EXIT_ON_CLOSE);
        pack();
        setLocationRelativeTo(null);
        setVisible(true);
    }

    // =====================================================
    //                 UI HELPERS
    // =====================================================
    private JButton btn(String txt, Color ignoredBg) {
        JButton b = new JButton(txt) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);

                g2.setColor(isEnabled() ? BUTTON_BG : BUTTON_BG.darker());
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), BUTTON_RADIUS, BUTTON_RADIUS);

                g2.dispose();
                super.paintComponent(g);
            }
        };

        b.setOpaque(false);
        b.setContentAreaFilled(false);
        b.setBorderPainted(false);
        b.setFocusPainted(false);
        b.setForeground(BUTTON_FG);
        b.setFont(new Font("SansSerif", Font.BOLD, 12));
        b.setBorder(new EmptyBorder(4, 10, 4, 10));
        return b;
    }

    private void styleTable(JTable t) {
        t.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value,
                                                           boolean isSelected, boolean hasFocus,
                                                           int row, int column) {
                Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);

                
                int statusCol = 6; // Algorithm,Time,Steps,TotalCost,PathLen,Optimal?,Status...
                int reasonCol = 7;

                if (table.getColumnCount() > reasonCol) {
                    Object status = table.getValueAt(row, statusCol);
                    Object reason = table.getValueAt(row, reasonCol);

                    if (reason != null && reason.toString().toLowerCase().contains("no path")) {
                        c.setForeground(Color.RED);
                        c.setFont(c.getFont().deriveFont(Font.BOLD));
                    } else if ("Success".equals(status)) {
                        c.setForeground(new Color(0x2E7D32));
                        c.setFont(c.getFont().deriveFont(Font.BOLD));
                    } else {
                        c.setForeground(table.getForeground());
                    }
                }
                return c;
            }
        });
    }

    // =====================================================
    //                GRAPH PANEL
    // =====================================================
    class GraphPanel extends JPanel {

        GraphPanel() {
            setBackground(Color.WHITE);

            addMouseListener(new MouseAdapter() {
                public void mouseClicked(MouseEvent e) {
                    click(e.getX(), e.getY());
                }
            });

            addMouseMotionListener(new MouseMotionAdapter() {
                public void mouseMoved(MouseEvent e) {
                    hover = (first != null) ? find(e.getX(), e.getY()) : null;
                    repaint();
                }
            });
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);

            Graphics2D g2 = (Graphics2D) g;
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);

            if (bgImage != null) {
                int w = getWidth();
                int h = getHeight();
                g2.drawImage(bgImage, 0, 0, w, h, this);
            }

            g2.setStroke(new BasicStroke(2));

            for (Edge e : edges) {
                g2.setColor(isBlocked(e) ? Color.RED : Color.GRAY);
                g2.drawLine(e.from.x, e.from.y, e.to.x, e.to.y);

                int mx = (e.from.x + e.to.x) / 2;
                int my = (e.from.y + e.to.y) / 2;

                double dx = e.to.x - e.from.x;
                double dy = e.to.y - e.from.y;
                double len = Math.hypot(dx, dy);
                int tx = mx;
                int ty = my;
                if (len != 0) {
                    double nx = -dy / len;
                    double ny = dx / len;
                    int offset = 12;
                    tx = (int) (mx + nx * offset);
                    ty = (int) (my + ny * offset);
                }

                g2.setFont(new Font("SansSerif", Font.BOLD, 12));
                g2.setColor(Color.BLACK);
                g2.drawString(String.format("%.1f", e.weight), tx, ty);

                if (directedMode) {
                    drawArrow(g2, e.from.x, e.from.y, e.to.x, e.to.y);
                }
            }

            if (first != null && hover != null && hover != first) {
                g2.setColor(new Color(0x80C7F9));
                g2.setStroke(new BasicStroke(
                        2, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND,
                        1, new float[]{10}, 0
                ));
                g2.drawLine(first.x, first.y, hover.x, hover.y);
                if (directedMode) {
                    drawArrow(g2, first.x, first.y, hover.x, hover.y);
                }
            }

            if (currentPath != null && currentPath.size() > 1) {
                g2.setStroke(new BasicStroke(3));
                g2.setColor(new Color(46, 139, 87));

                for (int i = 0; i < currentPath.size() - 1; i++) {
                    Node a = currentPath.get(i);
                    Node b = currentPath.get(i + 1);
                    g2.drawLine(a.x, a.y, b.x, b.y);
                    if (directedMode) {
                        drawArrow(g2, a.x, a.y, b.x, b.y);
                    }
                }
            }

            for (Node n : nodes) {
                g2.setColor(n.color);
                int r = 20;
                g2.fillOval(n.x - r, n.y - r, r * 2, r * 2);

                g2.setColor(Color.WHITE);
                g2.setFont(new Font("SansSerif", Font.BOLD, 14));
                int offset = (n.label.length() > 1 ? 8 : 5);
                g2.drawString(n.label, n.x - offset, n.y + 5);
            }

            if (src != null) {
                g2.setColor(Color.CYAN);
                g2.setStroke(new BasicStroke(3));
                g2.drawOval(src.x - 25, src.y - 25, 50, 50);
            }
            if (dst != null) {
                g2.setColor(Color.MAGENTA);
                g2.setStroke(new BasicStroke(3));
                g2.drawOval(dst.x - 25, dst.y - 25, 50, 50);
            }
        }
    }

    private void drawArrow(Graphics2D g2, int x1, int y1, int x2, int y2) {
        int nodeRadius = 20;
        double angle = Math.atan2(y2 - y1, x2 - x1);

        int tipX = (int) (x2 - nodeRadius * Math.cos(angle));
        int tipY = (int) (y2 - nodeRadius * Math.sin(angle));
        int len = 12;

        int ax = (int) (tipX - len * Math.cos(angle - Math.PI / 6));
        int ay = (int) (tipY - len * Math.sin(angle - Math.PI / 6));
        int bx = (int) (tipX - len * Math.cos(angle + Math.PI / 6));
        int by = (int) (tipY - len * Math.sin(angle + Math.PI / 6));

        g2.drawLine(tipX, tipY, ax, ay);
        g2.drawLine(tipX, tipY, bx, by);
    }

    // =====================================================
    //         MOUSE HANDLING (nodes/edges)
    // =====================================================
    private void click(int x, int y) {
        Node c = find(x, y);

        if (c != null) {
            if (first == null) {
                first = c;
                return;
            }
            if (first == c) {
                first = null;
                return;
            }

            String in = JOptionPane.showInputDialog(
                    this,
                    "Weight " + first.label + " → " + c.label + " :",
                    "10"
            );
            if (in == null) {
                first = null;
                return;
            }

            try {
                double w = Double.parseDouble(in.trim());
                final Edge newEdge = new Edge(first, c, w);
                edges.add(newEdge);
                log("Edge " + first.label + " → " + c.label + " (w=" + w + ")" +
                        (w < 0 ? " (NEGATIVE!)" : ""));
                canvas.repaint();

                pushUndo(new UndoableAction() {
                    public void undo() {
                        edges.remove(newEdge);
                        if (currentPath != null) currentPath = null;
                        log("Undo: removed edge " + newEdge.from.label + " → " + newEdge.to.label);
                    }
                });

                first = null;
                hover = null;
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, "Enter a valid number!");
            }

        } else {
            final Node newNode = new Node(x, y, String.valueOf((char) ('A' + nodeId++)));
            nodes.add(newNode);
            log("Node " + newNode.label + " added");
            canvas.repaint();

            pushUndo(new UndoableAction() {
                public void undo() {
                    deleteNode(newNode, false);
                    log("Undo: removed node " + newNode.label);
                }
            });
        }
    }

    private Node find(int x, int y) {
        for (Node n : nodes) {
            if (Math.hypot(n.x - x, n.y - y) < 25) return n;
        }
        return null;
    }

    private Edge findEdge(Node from, Node to) {
        for (Edge e : edges) {
            if (e.from == from && e.to == to) {
                return e;
            }
        }
        return null;
    }

    private Node askNodeOnce(String msg) {
        String in = JOptionPane.showInputDialog(this, msg);
        if (in == null) return null;
        in = in.trim().toUpperCase();
        Node n = findNodeByLabel(in);
        if (n == null) {
            JOptionPane.showMessageDialog(
                    this,
                    "Node not found!\nAvailable: " + getAllNodeLabels(),
                    "Error",
                    JOptionPane.ERROR_MESSAGE
            );
        }
        return n;
    }

    private void deleteNode(final Node n, boolean recordUndo) {
        if (n == null) return;

        final List<Edge> removedEdges = new ArrayList<Edge>();
        Iterator<Edge> it = edges.iterator();
        while (it.hasNext()) {
            Edge e = it.next();
            if (e.from == n || e.to == n) {
                removedEdges.add(e);
                it.remove();
            }
        }
        nodes.remove(n);

        if (src == n) src = null;
        if (dst == n) dst = null;
        if (first == n) first = null;
        if (hover == n) hover = null;
        if (currentPath != null && currentPath.contains(n)) currentPath = null;

        log("Node " + n.label + " deleted with " + removedEdges.size() + " connected edge(s).");
        canvas.repaint();

        if (recordUndo) {
            pushUndo(new UndoableAction() {
                public void undo() {
                    if (!nodes.contains(n)) {
                        nodes.add(n);
                    }
                    for (Edge e : removedEdges) {
                        if (!edges.contains(e)) {
                            edges.add(e);
                        }
                    }
                    log("Undo: restored node " + n.label + " and " + removedEdges.size() + " edge(s).");
                }
            });
        }
    }

    private void deleteEdge(final Edge e, boolean recordUndo) {
        if (e == null) return;
        edges.remove(e);
        if (currentPath != null) currentPath = null;
        log("Edge " + e.from.label + " → " + e.to.label + " deleted.");
        canvas.repaint();

        if (recordUndo) {
            pushUndo(new UndoableAction() {
                public void undo() {
                    if (!edges.contains(e)) {
                        edges.add(e);
                    }
                    log("Undo: restored edge " + e.from.label + " → " + e.to.label);
                }
            });
        }
    }

    // =====================================================
    //         GRAPH BUILD / CONNECTIVITY
    // =====================================================
    private Map<Node, List<Edge>> graph() {
        Map<Node, List<Edge>> g = new HashMap<Node, List<Edge>>();
        for (Node n : nodes) {
            g.put(n, new ArrayList<Edge>());
        }

        for (Edge e : edges) {
            if (isBlocked(e)) continue;
            g.get(e.from).add(e);
            if (!directedMode) {
                g.get(e.to).add(new Edge(e.to, e.from, e.weight));
            }
        }
        return g;
    }

    private boolean hasNegativeWeights() {
        for (Edge e : edges) {
            if (isBlocked(e)) continue;
            if (e.weight < 0) return true;
        }
        return false;
    }

    private boolean areAllWeightsSamePositive() {
        double w = -1;
        for (Edge e : edges) {
            if (isBlocked(e)) continue;
            if (e.weight <= 0) return false;
            if (w < 0) w = e.weight;
            else if (Math.abs(e.weight - w) > 1e-9) return false;
        }
        return w > 0;
    }

    private boolean connected(Node s, Node d) {
        Map<Node, List<Edge>> g = graph();
        Set<Node> vis = new HashSet<Node>();
        Queue<Node> q = new LinkedList<Node>();

        q.add(s);
        vis.add(s);

        while (!q.isEmpty()) {
            Node u = q.poll();
            if (u == d) return true;

            List<Edge> list = g.get(u);
            if (list != null) {
                for (Edge e : list) {
                    if (!vis.contains(e.to)) {
                        vis.add(e.to);
                        q.add(e.to);
                    }
                }
            }
        }
        return false;
    }

    // =====================================================
    //                   RUN LOGIC
    // =====================================================
    private void runAlgo(String algo) {
        if (nodes.isEmpty()) {
            JOptionPane.showMessageDialog(
                    this,
                    "No nodes! Add nodes first.",
                    "Empty Graph",
                    JOptionPane.WARNING_MESSAGE
            );
            return;
        }

        results.clear();
        tblModel.setRowCount(0);
        bfModel.setRowCount(0);
        bfModel.setColumnCount(0);
        resetColors();
        currentPath = null;

        src = askValidated("Enter Source Node (A, B, C...):");
        if (src == null) return;

        dst = askValidated("Enter Destination Node:");
        if (dst == null) return;

        if (src == dst) {
            JOptionPane.showMessageDialog(
                    this,
                    "Source and Destination cannot be the same!",
                    "Invalid",
                    JOptionPane.ERROR_MESSAGE
            );
            return;
        }

        boolean hasNeg = hasNegativeWeights();

        if ("Run All".equals(algo)) {

            if (!connected(src, dst)) {
                String road = getAnyBlockedRoadLabel();
                String msg;
                if (road != null) msg = "Traffic cannot proceed — Road " + road + " is blocked.";
                else msg = "No available route from " + src.label + " to " + dst.label + ".";
                logHighlight(msg);
                JOptionPane.showMessageDialog(this, msg, "Routing Failed", JOptionPane.WARNING_MESSAGE);
                return;
            }

            String[] all = {"BFS", "DFS", "Dijkstra", "A*", "Bellman-Ford", "Greedy"};
            for (int i = 0; i < all.length; i++) {
                runOne(all[i], false, hasNeg, true);
            }

            
            updateOptimalFlags();

            highlightBest();

            // ✅ animate ONLY (do NOT add duplicate row)
            BestDecision bd = decideBestAlgorithm();
            if (bd.best != null) {
                runOne(bd.best.name, true, hasNeg, false); // animation only
            }

        } else {
            if (!connected(src, dst)) {
                String road = getAnyBlockedRoadLabel();
                String msg;
                if (road != null) msg = "Traffic cannot proceed — Road " + road + " is blocked.";
                else msg = "No path from " + src.label + " to " + dst.label + "!";
                logHighlight(msg);
                JOptionPane.showMessageDialog(this, msg, "No Path", JOptionPane.ERROR_MESSAGE);
                return;
            }

            runOne(algo, true, hasNeg, true);
            updateOptimalFlags();
            highlightBest();
        }
    }

    private Node askValidated(String msg) {
        while (true) {
            String in = JOptionPane.showInputDialog(this, msg);
            if (in == null) return null;
            in = in.trim().toUpperCase();

            Node n = findNodeByLabel(in);
            if (n == null) {
                JOptionPane.showMessageDialog(
                        this,
                        "Node not found!\nAvailable: " + getAllNodeLabels(),
                        "Error",
                        JOptionPane.ERROR_MESSAGE
                );
            } else {
                return n;
            }
        }
    }

    private Node findNodeByLabel(String label) {
        for (Node n : nodes) {
            if (n.label.equalsIgnoreCase(label)) return n;
        }
        return null;
    }

    private String getAllNodeLabels() {
        if (nodes.isEmpty()) return "None";
        List<String> labels = new ArrayList<String>();
        for (Node n : nodes) labels.add(n.label);
        Collections.sort(labels);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < labels.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(labels.get(i));
        }
        return sb.toString();
    }

    // =====================================================
    //                    ALGORITHMS
    // =====================================================
    private PathResult bfs(Node start, Node goal, boolean anim) {
        Map<Node, List<Edge>> g = graph();
        Map<Node, Node> parent = new HashMap<Node, Node>();
        Set<Node> vis = new HashSet<Node>();
        Queue<Node> q = new LinkedList<Node>();

        q.add(start);
        vis.add(start);
        parent.put(start, null);
        int steps = 0;

        while (!q.isEmpty()) {
            Node cur = q.poll();
            visit(cur, anim);
            steps++;

            if (cur == goal) {
                List<Node> path = reconstruct(parent, start, goal);
                double cost = (path == null) ? Double.POSITIVE_INFINITY : computePathCost(path);
                return new PathResult(true, path, steps, false, null, cost);
            }

            List<Edge> list = g.get(cur);
            if (list != null) {
                for (Edge e : list) {
                    if (!vis.contains(e.to)) {
                        vis.add(e.to);
                        q.add(e.to);
                        parent.put(e.to, cur);
                    }
                }
            }
        }
        return new PathResult(false, null, steps, false, null, Double.POSITIVE_INFINITY);
    }

    private PathResult dfs(Node start, Node goal, boolean anim) {
        Map<Node, List<Edge>> g = graph();
        Map<Node, Node> parent = new HashMap<Node, Node>();
        Set<Node> vis = new HashSet<Node>();
        Stack<Node> st = new Stack<Node>();

        st.push(start);
        vis.add(start);
        parent.put(start, null);
        int steps = 0;

        while (!st.isEmpty()) {
            Node cur = st.pop();
            visit(cur, anim);
            steps++;

            if (cur == goal) {
                List<Node> path = reconstruct(parent, start, goal);
                double cost = (path == null) ? Double.POSITIVE_INFINITY : computePathCost(path);
                return new PathResult(true, path, steps, false, null, cost);
            }

            List<Edge> list = g.get(cur);
            if (list != null) {
                for (Edge e : list) {
                    if (!vis.contains(e.to)) {
                        vis.add(e.to);
                        st.push(e.to);
                        parent.put(e.to, cur);
                    }
                }
            }
        }
        return new PathResult(false, null, steps, false, null, Double.POSITIVE_INFINITY);
    }

    private PathResult dijkstra(Node start, Node goal, boolean anim) {
        Map<Node, List<Edge>> g = graph();
        Map<Node, Double> dist = new HashMap<Node, Double>();
        Map<Node, Node> parent = new HashMap<Node, Node>();

        for (Node n : nodes) dist.put(n, Double.POSITIVE_INFINITY);
        dist.put(start, 0.0);

        PriorityQueue<Node> pq = new PriorityQueue<Node>(11,
                new Comparator<Node>() {
                    public int compare(Node a, Node b) {
                        return Double.compare(dist.get(a), dist.get(b));
                    }
                });
        pq.add(start);

        int steps = 0;

        while (!pq.isEmpty()) {
            Node u = pq.poll();
            visit(u, anim);
            steps++;

            if (u == goal) {
                List<Node> path = reconstruct(parent, start, goal);
                double cost = dist.get(goal);
                return new PathResult(true, path, steps, false, null, cost);
            }

            List<Edge> list = g.get(u);
            if (list != null) {
                for (Edge e : list) {
                    steps++;
                    double alt = dist.get(u) + e.weight;
                    if (alt < dist.get(e.to)) {
                        dist.put(e.to, alt);
                        parent.put(e.to, u);
                        pq.add(e.to);
                    }
                }
            }
        }

        boolean found = dist.get(goal) < Double.POSITIVE_INFINITY;
        List<Node> path = found ? reconstruct(parent, start, goal) : null;
        double cost = found ? dist.get(goal) : Double.POSITIVE_INFINITY;
        return new PathResult(found, path, steps, false, null, cost);
    }

    private double heuristic(Node a, Node b) {
        return Math.hypot(a.x - b.x, a.y - b.y);
    }

    private PathResult aStar(Node start, Node goal, boolean anim) {
        Map<Node, List<Edge>> g = graph();
        Map<Node, Double> gScore = new HashMap<Node, Double>();
        Map<Node, Double> fScore = new HashMap<Node, Double>();
        Map<Node, Node> parent = new HashMap<Node, Node>();

        for (Node n : nodes) {
            gScore.put(n, Double.POSITIVE_INFINITY);
            fScore.put(n, Double.POSITIVE_INFINITY);
        }

        gScore.put(start, 0.0);
        fScore.put(start, heuristic(start, goal));

        PriorityQueue<Node> open = new PriorityQueue<Node>(11,
                new Comparator<Node>() {
                    public int compare(Node a, Node b) {
                        return Double.compare(fScore.get(a), fScore.get(b));
                    }
                });
        open.add(start);

        int steps = 0;

        while (!open.isEmpty()) {
            Node cur = open.poll();
            visit(cur, anim);
            steps++;

            if (cur == goal) {
                List<Node> path = reconstruct(parent, start, goal);
                double cost = gScore.get(goal);
                return new PathResult(true, path, steps, false, null, cost);
            }

            List<Edge> list = g.get(cur);
            if (list != null) {
                for (Edge e : list) {
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
        }
        return new PathResult(false, null, steps, false, null, Double.POSITIVE_INFINITY);
    }

    private PathResult bellmanFord(Node start, Node goal, boolean anim) {
        Map<Node, Double> dist = new HashMap<Node, Double>();
        Map<Node, Node> parent = new HashMap<Node, Node>();
        List<Map<Node, Double>> history = new ArrayList<Map<Node, Double>>();

        for (Node n : nodes) {
            dist.put(n, Double.POSITIVE_INFINITY);
            parent.put(n, null);
        }
        dist.put(start, 0.0);
        history.add(new HashMap<Node, Double>(dist));

        int steps = 0;

        List<Edge> list = new ArrayList<Edge>();
        for (Edge e : edges) {
            if (isBlocked(e)) continue;
            list.add(e);
            if (!directedMode) list.add(new Edge(e.to, e.from, e.weight));
        }

        for (int i = 0; i < nodes.size() - 1; i++) {
            boolean changed = false;
            for (Edge e : list) {
                if (dist.get(e.from) != Double.POSITIVE_INFINITY) {
                    double alt = dist.get(e.from) + e.weight;
                    if (alt < dist.get(e.to)) {
                        dist.put(e.to, alt);
                        parent.put(e.to, e.from);
                        changed = true;
                        steps++;

                        if (anim) {
                            e.from.color = Color.YELLOW;
                            e.to.color = Color.YELLOW;
                            canvas.repaint();
                            sleep(100);
                            e.from.color = new Color(0x4CAF50);
                            e.to.color = new Color(0x4CAF50);
                            canvas.repaint();
                            sleep(60);
                        }
                    }
                }
            }
            history.add(new HashMap<Node, Double>(dist));
            if (!changed) break;
        }

        boolean negCycle = false;
        for (Edge e : list) {
            if (dist.get(e.from) != Double.POSITIVE_INFINITY &&
                    dist.get(e.from) + e.weight < dist.get(e.to)) {
                negCycle = true;
                break;
            }
        }

        boolean found = dist.get(goal) < Double.POSITIVE_INFINITY;
        List<Node> path = found ? reconstruct(parent, start, goal) : null;
        double cost = found ? dist.get(goal) : Double.POSITIVE_INFINITY;

        return new PathResult(found, path, steps, negCycle, history, cost);
    }

    private PathResult greedy(Node start, Node goal, boolean anim) {
        Map<Node, List<Edge>> g = graph();
        Map<Node, Double> dist = new HashMap<Node, Double>();
        Map<Node, Node> parent = new HashMap<Node, Node>();
        Set<Node> visited = new HashSet<Node>();

        for (Node n : nodes) dist.put(n, Double.POSITIVE_INFINITY);
        dist.put(start, 0.0);
        parent.put(start, null);

        Node cur = start;
        int steps = 0;

        while (cur != null && !visited.contains(goal)) {
            visited.add(cur);
            visit(cur, anim);
            steps++;

            Edge best = null;
            double minW = Double.POSITIVE_INFINITY;

            List<Edge> list = g.get(cur);
            if (list != null) {
                for (Edge e : list) {
                    if (!visited.contains(e.to) && e.weight < minW) {
                        minW = e.weight;
                        best = e;
                    }
                }
            }

            if (best == null) break;

            dist.put(best.to, dist.get(cur) + best.weight);
            parent.put(best.to, cur);
            cur = best.to;
            steps++;

            if (anim) {
                best.from.color = Color.ORANGE;
                best.to.color = Color.ORANGE;
                canvas.repaint();
                sleep(120);
                best.from.color = new Color(0xFF9800);
                best.to.color = new Color(0xFF9800);
                canvas.repaint();
                sleep(80);
            }
        }

        boolean found = visited.contains(goal);
        List<Node> path = found ? reconstruct(parent, start, goal) : null;
        double cost = found ? dist.get(goal) : Double.POSITIVE_INFINITY;

        return new PathResult(found, path, steps, false, null, cost);
    }

    private List<Node> reconstruct(Map<Node, Node> parent, Node start, Node goal) {
        List<Node> path = new ArrayList<Node>();
        for (Node at = goal; at != null; at = parent.get(at)) {
            path.add(at);
        }
        if (path.isEmpty() || path.get(path.size() - 1) != start) return null;
        Collections.reverse(path);
        return path;
    }

    private double computePathCost(List<Node> path) {
        if (path == null || path.size() < 2) return Double.POSITIVE_INFINITY;
        double c = 0.0;
        for (int i = 0; i < path.size() - 1; i++) {
            Node a = path.get(i);
            Node b = path.get(i + 1);
            boolean foundEdge = false;
            for (Edge e : edges) {
                if (isBlocked(e)) continue;
                if (e.from == a && e.to == b) {
                    c += e.weight;
                    foundEdge = true;
                    break;
                } else if (!directedMode && e.from == b && e.to == a) {
                    c += e.weight;
                    foundEdge = true;
                    break;
                }
            }
            if (!foundEdge) return Double.POSITIVE_INFINITY;
        }
        return c;
    }

    private void updateBellmanFordTable(List<Map<Node, Double>> history) {
        if (history == null || history.isEmpty()) return;

        bfModel.setRowCount(0);
        bfModel.setColumnCount(0);

        bfModel.addColumn("Iter");
        for (Node n : nodes) bfModel.addColumn(n.label);

        for (int i = 0; i < history.size(); i++) {
            Object[] row = new Object[nodes.size() + 1];
            row[0] = Integer.valueOf(i);

            Map<Node, Double> dist = history.get(i);
            for (int j = 0; j < nodes.size(); j++) {
                Double d = dist.get(nodes.get(j));
                row[j + 1] = (d == null || d == Double.POSITIVE_INFINITY)
                        ? "∞" : String.format("%.0f", d);
            }
            bfModel.addRow(row);
        }
    }

    // Overload
    private void runOne(String name, boolean anim, boolean hasNeg) {
        runOne(name, anim, hasNeg, true);
    }

    // ✅ recordRow=false means "animate only" (no table duplicates)
    private void runOne(String name, boolean anim, boolean hasNeg, boolean recordRow) {
        boolean ok = false;
        int steps = 0;
        int plen = 0;
        double ms = 0;
        double cost = Double.POSITIVE_INFINITY;
        String reason = "";
        String complexity = "";

        if ("BFS".equals(name)) complexity = "O(V+E)";
        else if ("DFS".equals(name)) complexity = "O(V+E)";
        else if ("Dijkstra".equals(name)) complexity = "O((V+E)logV)";
        else if ("A*".equals(name)) complexity = "O((V+E)logV)";
        else if ("Bellman-Ford".equals(name)) complexity = "O(V×E)";
        else if ("Greedy".equals(name)) complexity = "O(E)";

        currentPath = null;
        final Holder<List<Map<Node, Double>>> hist = new Holder<List<Map<Node, Double>>>(null);

        try {
            if ((name.equals("Greedy") || name.equals("Dijkstra") ||
                    name.equals("A*")) && hasNeg) {
                reason = "Not executed (negative weights present)";
                ok = false;
            } else {
                PathResult res;
                long t0 = System.nanoTime();

                if ("BFS".equals(name)) {
                    res = bfs(src, dst, anim);
                } else if ("DFS".equals(name)) {
                    res = dfs(src, dst, anim);
                } else if ("Dijkstra".equals(name)) {
                    res = dijkstra(src, dst, anim);
                } else if ("A*".equals(name)) {
                    res = aStar(src, dst, anim);
                } else if ("Bellman-Ford".equals(name)) {
                    res = bellmanFord(src, dst, anim);
                    hist.value = res.distanceHistory;
                } else {
                    res = greedy(src, dst, anim);
                }

                long t1 = System.nanoTime();
                ms = (t1 - t0) / 1e6;

                ok = res.found && !res.hasNegativeCycle;
                steps = res.steps;
                cost = res.totalCost;
                plen = (res.path != null) ? res.path.size() : 0;

                if (res.hasNegativeCycle) {
                    reason = "Negative cycle detected";
                } else if (res.found) {
                    // ✅ small note for BFS/DFS on weighted graphs
                    if (("BFS".equals(name) || "DFS".equals(name)) && !areAllWeightsSamePositive()) {
                        reason = "Path found (Note: BFS/DFS not guaranteed optimal on weighted graphs)";
                    } else {
                        reason = "Path found";
                    }
                } else {
                    reason = "No path found.";
                }

                if (ok) currentPath = res.path;

                if ("Bellman-Ford".equals(name) && hist.value != null) {
                    final List<Map<Node, Double>> history = hist.value;
                    SwingUtilities.invokeLater(new Runnable() {
                        public void run() {
                            updateBellmanFordTable(history);
                        }
                    });
                }
            }

        } catch (Exception ex) {
            ok = false;
            reason = "Error: " + ex.getMessage();
        }

        if (recordRow) {
            // "Optimal?" placeholder now; will be filled by updateOptimalFlags()
            tblModel.addRow(new Object[]{
                    name,
                    String.format("%.2f", ms),
                    Integer.valueOf(steps),
                    (ok && ("BFS".equals(name) || "DFS".equals(name)))
                     ? "-"
                     : (ok ? String.format("%.2f", cost) : "N/A"),

                    Integer.valueOf(plen),
                    ok ? "—" : "—",
                    ok ? "Success" : "Failed",
                    reason,
                    complexity
            });

            results.add(new Result(name, ms, steps, cost, plen, ok, reason, complexity));
        }

        log(name + ": " + reason + (ok ? " (Cost=" + String.format("%.2f", cost) + ")" : ""));
    }

    // ✅ Mark optimal rows (min cost among successful algorithms)
    private void updateOptimalFlags() {
        double bestCost = Double.POSITIVE_INFINITY;
        for (Result r : results) {
            if (r.ok) bestCost = Math.min(bestCost, r.totalCost);
        }
        if (bestCost == Double.POSITIVE_INFINITY) return;

        // Update results flags
        for (Result r : results) {
            r.optimal = r.ok && Math.abs(r.totalCost - bestCost) < 1e-9;
        }

        // Update table column
        int optimalCol = 5;
        int statusCol = 6;

        for (int row = 0; row < tblModel.getRowCount(); row++) {
            Object algoObj = tblModel.getValueAt(row, 0);
            Object statusObj = tblModel.getValueAt(row, statusCol);

            if (algoObj == null || statusObj == null) continue;

            String algo = algoObj.toString();
            String status = statusObj.toString();

            if (!"Success".equals(status)) {
                tblModel.setValueAt("—", row, optimalCol);
                continue;
            }

            Result match = null;
            for (Result r : results) {
                if (r.name.equals(algo)) { match = r; break; }
            }

            if (match != null && match.optimal) tblModel.setValueAt("Yes", row, optimalCol);
            else tblModel.setValueAt("No", row, optimalCol);
        }
    }

    // ✅ best decision based on: case rules + optimal cost + steps + time
    private BestDecision decideBestAlgorithm() {
        BestDecision bd = new BestDecision();

        boolean hasNeg = hasNegativeWeights();
        boolean allSame = areAllWeightsSamePositive();

        List<Result> ok = new ArrayList<>();
       for (Result r : results) {
       if (!r.ok) continue;

       if (!areAllWeightsSamePositive()
            && ("BFS".equals(r.name) || "DFS".equals(r.name))) {
        continue;
    }

    ok.add(r);
}


        if (ok.isEmpty()) {
            bd.best = null;
            bd.explanation = "No valid successful result found.";
            return bd;
        }

        // Negative weights => Bellman-Ford is required
        if (hasNeg) {
            for (Result r : ok) {
                if ("Bellman-Ford".equals(r.name)) {
                    bd.best = r;
                    bd.explanation = "Negative weights detected → Bellman-Ford is required for correctness.";
                    return bd;
                }
            }
        }

        // Unweighted/equal weights => BFS best
        if (allSame) {
            for (Result r : ok) {
                if ("BFS".equals(r.name)) {
                    bd.best = r;
                    bd.explanation = "All edge weights equal → BFS is optimal (shortest-hop) and efficient.";
                    return bd;
                }
            }
        }

        // General: optimal cost first
        double bestCost = Double.POSITIVE_INFINITY;
        for (Result r : ok) bestCost = Math.min(bestCost, r.totalCost);

        List<Result> optimal = new ArrayList<>();
        for (Result r : ok) {
            if (Math.abs(r.totalCost - bestCost) < 1e-9) optimal.add(r);
        }

        // Prefer Dijkstra/A* over BFS/DFS on weighted graphs (academic correctness)
        List<Result> filtered = new ArrayList<>();
        if (!allSame) {
            for (Result r : optimal) {
                if (!"BFS".equals(r.name) && !"DFS".equals(r.name)) filtered.add(r);
            }
            if (filtered.isEmpty()) filtered = optimal; // fallback if only BFS/DFS succeeded
        } else {
            filtered = optimal;
        }

        Result best = null;
        for (Result r : filtered) {
            if (best == null) best = r;
            else if (r.steps < best.steps) best = r;
            else if (r.steps == best.steps && r.timeMs < best.timeMs) best = r;
        }

        bd.best = best;

        if (best == null) {
            bd.explanation = "Could not determine a best algorithm.";
            return bd;
        }

        if ("A*".equals(best.name)) {
            bd.explanation = "A* achieved optimal cost with fewer operations → best balance for this case.";
        } else if ("Dijkstra".equals(best.name)) {
            bd.explanation = "Dijkstra guarantees optimal shortest path for non-negative weights → best safe choice.";
        } else if ("Bellman-Ford".equals(best.name)) {
            bd.explanation = "Bellman-Ford handles negative weights (slower but correct) → best for this case.";
        } else if ("Greedy".equals(best.name)) {
            bd.explanation = "Greedy matched the optimal cost in this instance and was efficient (not guaranteed generally).";
        } else if ("BFS".equals(best.name)) {
            bd.explanation = "BFS is optimal on unweighted/equal-weight graphs and efficient here.";
        } else {
            bd.explanation = "Selected based on optimal cost, then steps/time tie-break.";
        }

        return bd;
    }

    private void highlightBest() {
        BestDecision bd = decideBestAlgorithm();
        if (bd.best != null) {
            bestLbl.setText("Best: " + bd.best.name +
                    " (cost " + String.format("%.2f", bd.best.totalCost) +
                    ", steps " + bd.best.steps +
                    ", " + String.format("%.2f ms", bd.best.timeMs) + ")");
            logHighlight("Best Decision: " + bd.best.name);
            log("Why: " + bd.explanation);
        } else {
            bestLbl.setText("Best: —");
            logHighlight("Best Decision: —");
            log("Why: " + (bd.explanation == null ? "" : bd.explanation));
        }
    }

    // =====================================================
    // VEHICLE SCHEDULING + TSP (UNCHANGED)
    // =====================================================
    private void runVehicleSchedulingDialog() {
        try {
            String vIn = JOptionPane.showInputDialog(
                    this,
                    "Enter number of vehicles (buses/taxis):",
                    "3"
            );
            if (vIn == null) return;
            int m = Integer.parseInt(vIn.trim());
            if (m <= 0) {
                JOptionPane.showMessageDialog(this, "Vehicles must be > 0");
                return;
            }

            String rIn = JOptionPane.showInputDialog(
                    this,
                    "Enter route times (comma-separated, e.g. 10,20,30):",
                    "10,20,15,25"
            );
            if (rIn == null) return;
            String[] parts = rIn.split(",");
            List<Double> ts = new ArrayList<Double>();
            for (String p : parts) {
                String s = p.trim();
                if (s.isEmpty()) continue;
                double val = Double.parseDouble(s);
                if (val <= 0) {
                    JOptionPane.showMessageDialog(this, "Route times must be positive.");
                    return;
                }
                ts.add(val);
            }
            if (ts.isEmpty()) {
                JOptionPane.showMessageDialog(this, "No valid routes provided.");
                return;
            }

            double[] times = new double[ts.size()];
            for (int i = 0; i < ts.size(); i++) times[i] = ts.get(i);

            log("========== Vehicle Scheduling ==========");
            log("Routes: " + times.length + ", Vehicles: " + m);

            SchedResult greedy = scheduleGreedy(times, m);
            logHighlight("Greedy makespan: " + String.format("%.2f", greedy.makespan));
            log(formatAssignment("Greedy assignment:", greedy, times));

            SchedResult optimal = null;
            if (times.length <= 12) {
                optimal = scheduleBranchAndBound(times, m, greedy);

                if (optimal != null) {
                    logHighlight("Optimal (Branch-and-Bound) makespan: " +
                            String.format("%.2f", optimal.makespan));
                    log(formatAssignment("Optimal assignment:", optimal, times));
                } else {
                    log("Branch-and-Bound could not improve greedy solution.");
                }
            } else {
                logHighlight("Optimal (Branch-and-Bound) skipped (routes > 12).");
            }

            JOptionPane.showMessageDialog(
                    this,
                    "Vehicle Scheduling completed.\nCheck logs for details.",
                    "Vehicle Scheduling",
                    JOptionPane.INFORMATION_MESSAGE
            );

        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Error: " + ex.getMessage());
        }
    }

    private SchedResult scheduleGreedy(double[] times, int m) {
        int n = times.length;
        SchedResult res = new SchedResult();
        res.assignment = new ArrayList<List<Integer>>();
        double[] loads = new double[m];
        for (int i = 0; i < m; i++) {
            res.assignment.add(new ArrayList<Integer>());
            loads[i] = 0.0;
        }

        Integer[] idx = new Integer[n];
        for (int i = 0; i < n; i++) idx[i] = i;
        Arrays.sort(idx, new Comparator<Integer>() {
            public int compare(Integer a, Integer b) {
                return -Double.compare(times[a], times[b]);
            }
        });

        for (int k = 0; k < n; k++) {
            int job = idx[k];
            int bestMachine = 0;
            double minLoad = loads[0];
            for (int i = 1; i < m; i++) {
                if (loads[i] < minLoad) {
                    minLoad = loads[i];
                    bestMachine = i;
                }
            }
            loads[bestMachine] += times[job];
            res.assignment.get(bestMachine).add(job);
        }

        double maxLoad = 0;
        for (int i = 0; i < m; i++) {
            if (loads[i] > maxLoad) maxLoad = loads[i];
        }
        res.makespan = maxLoad;
        return res;
    }

    private SchedResult scheduleBranchAndBound(double[] times, int m, SchedResult greedySeed) {
    // Seed best with greedy so even if BnB doesn't improve, we still have a valid assignment to print
    SchedResult best = new SchedResult();
    best.makespan = greedySeed.makespan;

    best.assignment = new ArrayList<List<Integer>>();
    for (int i = 0; i < m; i++) {
        best.assignment.add(new ArrayList<Integer>(greedySeed.assignment.get(i)));
    }

    // Start BnB search from empty assignment (BnB will try to improve best.makespan)
    double[] curLoads = new double[m];
    List<List<Integer>> currentAssign = new ArrayList<List<Integer>>();
    for (int i = 0; i < m; i++) {
        currentAssign.add(new ArrayList<Integer>());
        curLoads[i] = 0.0;
    }

    assignJobBB(0, times, curLoads, currentAssign, best, m);
    return best;
}

    private void assignJobBB(int jobIndex, double[] times, double[] loads,
                             List<List<Integer>> currentAssign,
                             SchedResult best, int m) {
        int n = times.length;
        if (jobIndex == n) {
            double maxLoad = 0;
            for (int i = 0; i < m; i++) if (loads[i] > maxLoad) maxLoad = loads[i];
            if (maxLoad < best.makespan) {
                best.makespan = maxLoad;
                best.assignment = new ArrayList<List<Integer>>();
                for (int i = 0; i < m; i++) {
                    best.assignment.add(new ArrayList<Integer>(currentAssign.get(i)));
                }
            }
            return;
        }

        for (int i = 0; i < m; i++) {
            currentAssign.get(i).add(jobIndex);
            loads[i] += times[jobIndex];

            double currentMax = 0;
            for (int k = 0; k < m; k++) if (loads[k] > currentMax) currentMax = loads[k];

            if (currentMax < best.makespan) assignJobBB(jobIndex + 1, times, loads, currentAssign, best, m);

            loads[i] -= times[jobIndex];
            currentAssign.get(i).remove(currentAssign.get(i).size() - 1);

            if (loads[i] == 0) break;
        }
    }

    private String formatAssignment(String title, SchedResult r, double[] times) {
        StringBuilder sb = new StringBuilder();
        sb.append(title).append("\n");
        for (int i = 0; i < r.assignment.size(); i++) {
            sb.append("Vehicle ").append(i + 1).append(": ");
            double sum = 0;
            for (int job : r.assignment.get(i)) {
                sb.append("R").append(job + 1)
                        .append("(").append(times[job]).append(") ");
                sum += times[job];
            }
            sb.append(" | total = ").append(String.format("%.2f", sum)).append("\n");
        }
        return sb.toString();
    }

    private void runTSPModule() {
        // (kept as in your code; no functional changes)
        if (nodes.size() < 3) {
            JOptionPane.showMessageDialog(this, "TSP requires at least 3 nodes.", "TSP", JOptionPane.WARNING_MESSAGE);
            return;
        }

        try {
            String in = JOptionPane.showInputDialog(this,
                    "Enter TSP node labels (comma-separated, or empty for all nodes):", "");
            if (in == null) return;

            List<Node> tspNodes = new ArrayList<Node>();
            if (in.trim().isEmpty()) tspNodes.addAll(nodes);
            else {
                String[] parts = in.split(",");
                for (String p : parts) {
                    String lbl = p.trim().toUpperCase();
                    if (lbl.isEmpty()) continue;
                    Node n = findNodeByLabel(lbl);
                    if (n == null) {
                        JOptionPane.showMessageDialog(this, "Node " + lbl + " not found.", "TSP",
                                JOptionPane.ERROR_MESSAGE);
                        return;
                    }
                    tspNodes.add(n);
                }
            }

            if (tspNodes.size() < 3) {
                JOptionPane.showMessageDialog(this, "TSP requires at least 3 valid nodes.", "TSP",
                        JOptionPane.WARNING_MESSAGE);
                return;
            }

            log("========== TSP Module ==========");
            log("TSP nodes: " + labelsOfNodes(tspNodes));

            List<Node> nnTour = tspNearestNeighbor(tspNodes);
            double nnCost = (nnTour == null) ? Double.POSITIVE_INFINITY : tourCost(nnTour);

            if (nnTour != null) {
                logHighlight("TSP Heuristic (Nearest Neighbor) cost: " + String.format("%.2f", nnCost));
                log("NN Tour: " + labelsOfNodes(nnTour));
            } else {
                logHighlight("TSP Heuristic failed (graph not fully connected for selected nodes).");
            }

            List<Node> exactTour = null;
            double exactCost = Double.POSITIVE_INFINITY;
            if (tspNodes.size() <= 10) {
                List<Node> et = tspExactBranchAndBound(tspNodes);
                if (et != null) {
                    exactTour = et;
                    exactCost = tourCost(exactTour);
                    logHighlight("TSP Exact (Branch-and-Bound) cost: " + String.format("%.2f", exactCost));
                    log("Exact Tour: " + labelsOfNodes(exactTour));
                } else {
                    logHighlight("TSP Exact could not find a full tour (disconnected edges).");
                }
            } else {
                logHighlight("TSP Exact skipped (nodes > 10).");
            }

            if (exactTour != null && exactCost <= nnCost) {
                currentPath = exactTour;
                log("Canvas path: Exact TSP tour.");
            } else if (nnTour != null) {
                currentPath = nnTour;
                log("Canvas path: Heuristic TSP tour.");
            } else {
                currentPath = null;
            }

            canvas.repaint();

            JOptionPane.showMessageDialog(this, "TSP computation completed.\nCheck logs for details.",
                    "TSP", JOptionPane.INFORMATION_MESSAGE);

        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Error in TSP: " + ex.getMessage());
        }
    }

    private String labelsOfNodes(List<Node> list) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(" → ");
            sb.append(list.get(i).label);
        }
        return sb.toString();
    }

    private double getEdgeWeight(Node a, Node b) {
        for (Edge e : edges) {
            if (isBlocked(e)) continue;
            if (e.from == a && e.to == b) return e.weight;
            if (!directedMode && e.from == b && e.to == a) return e.weight;
        }
        return INF;
    }

    private double tourCost(List<Node> tour) {
        if (tour == null || tour.size() < 2) return Double.POSITIVE_INFINITY;
        double cost = 0.0;
        for (int i = 0; i < tour.size() - 1; i++) {
            double w = getEdgeWeight(tour.get(i), tour.get(i + 1));
            if (w >= INF) return Double.POSITIVE_INFINITY;
            cost += w;
        }
        return cost;
    }

    private List<Node> tspNearestNeighbor(List<Node> tspNodes) {
        int n = tspNodes.size();
        boolean[] used = new boolean[n];
        List<Node> tour = new ArrayList<Node>();

        int current = 0;
        used[current] = true;
        tour.add(tspNodes.get(current));

        for (int step = 1; step < n; step++) {
            int next = -1;
            double best = INF;
            for (int j = 0; j < n; j++) {
                if (!used[j]) {
                    double w = getEdgeWeight(tspNodes.get(current), tspNodes.get(j));
                    if (w < best) {
                        best = w;
                        next = j;
                    }
                }
            }
            if (next == -1 || best >= INF) return null;
            used[next] = true;
            tour.add(tspNodes.get(next));
            current = next;
        }

        tour.add(tspNodes.get(0));
        return tour;
    }

    private List<Node> tspExactBranchAndBound(List<Node> tspNodes) {
        int n = tspNodes.size();
        double[][] cost = new double[n][n];

        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                if (i == j) cost[i][j] = INF;
                else cost[i][j] = getEdgeWeight(tspNodes.get(i), tspNodes.get(j));
            }
        }

        for (int i = 0; i < n; i++) {
            boolean hasOut = false;
            boolean hasIn = false;
            for (int j = 0; j < n; j++) {
                if (i == j) continue;
                if (cost[i][j] < INF) hasOut = true;
                if (cost[j][i] < INF) hasIn = true;
            }
            if (!hasOut || !hasIn) return null;
        }

        boolean[] used = new boolean[n];
        int[] path = new int[n];
        TSPState state = new TSPState();
        used[0] = true;
        path[0] = 0;

        tspBBRec(0, 1, 0.0, used, path, state, cost, n);

        if (!state.found || state.bestCost >= INF) return null;

        List<Node> tour = new ArrayList<Node>();
        for (int i = 0; i < n; i++) tour.add(tspNodes.get(state.bestPath[i]));
        tour.add(tspNodes.get(state.bestPath[0]));
        return tour;
    }

    private void tspBBRec(int current, int level, double currentCost,
                          boolean[] used, int[] path,
                          TSPState state, double[][] cost, int n) {
        if (currentCost >= state.bestCost) return;

        if (level == n) {
            double back = cost[current][path[0]];
            if (back >= INF) return;
            double total = currentCost + back;
            if (total < state.bestCost) {
                state.bestCost = total;
                state.bestPath = path.clone();
                state.found = true;
            }
            return;
        }

        for (int next = 1; next < n; next++) {
            if (!used[next] && cost[current][next] < INF) {
                used[next] = true;
                path[level] = next;
                tspBBRec(next, level + 1, currentCost + cost[current][next],
                        used, path, state, cost, n);
                used[next] = false;
            }
        }
    }

    private void visit(Node n, boolean anim) {
        try {
            n.color = Color.YELLOW;
            canvas.repaint();
            if (anim) Thread.sleep(150);

            n.color = new Color(0x4CAF50);
            canvas.repaint();
            if (anim) Thread.sleep(80);
        } catch (Exception ignored) {}
    }

    private void sleep(int ms) {
        try { Thread.sleep(ms); } catch (Exception ignored) {}
    }

    private void resetColors() {
        for (Node n : nodes) n.color = new Color(0x007BFF);
        if (src != null) src.color = Color.CYAN;
        if (dst != null) dst.color = Color.MAGENTA;
        canvas.repaint();
    }

    private void restart() {
        nodes.clear();
        edges.clear();
        first = hover = src = dst = null;
        currentPath = null;
        nodeId = 0;

        results.clear();
        tblModel.setRowCount(0);
        bfModel.setRowCount(0);
        bfModel.setColumnCount(0);

        try {
            StyledDocument doc = logArea.getStyledDocument();
            doc.remove(0, doc.getLength());
        } catch (BadLocationException ignored) {}

        bestLbl.setText("Best: —");
        trafficManager.clearCache();
        undoStack.clear();

        canvas.repaint();
        log("Restarted.");
    }

    private void exportCSV() {
        try {
            String fn = "results_" +
                    new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date()) +
                    ".csv";
            PrintWriter pw = new PrintWriter(fn);
            pw.println("Algorithm,Time(ms),Steps,TotalCost,PathLength,Optimal,Status,Reason,TimeComplexity");
            for (Result r : results) {
                pw.println(r.name + "," + r.timeMs + "," + r.steps + "," + r.totalCost
                        + "," + r.pathLen + "," + (r.optimal ? "Yes" : "No")
                        + "," + (r.ok ? "Success" : "Failed")
                        + "," + r.reason + "," + r.complexity);
            }
            pw.close();
            log("Exported: " + fn);
        } catch (Exception e) {
            log("Export error: " + e.getMessage());
        }
    }

    private void log(String s) {
        StyledDocument doc = logArea.getStyledDocument();
        try {
            doc.insertString(doc.getLength(), s + "\n", normalStyle);
            logArea.setCaretPosition(doc.getLength());
        } catch (BadLocationException ignored) {}
    }

    private void logHighlight(String s) {
        StyledDocument doc = logArea.getStyledDocument();
        try {
            doc.insertString(doc.getLength(), s + "\n", highlightStyle);
            logArea.setCaretPosition(doc.getLength());
        } catch (BadLocationException ignored) {}
    }

    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel("javax.swing.plaf.nimbus.NimbusLookAndFeel");
        } catch (Exception ignored) {}

        SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                new Daa_smartCity();
            }
        });
    }
}
>>>>>>> 42f386a4733fd25e1a4a88ad7dbbc790df9efee9
