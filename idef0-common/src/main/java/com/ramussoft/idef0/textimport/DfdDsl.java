package com.ramussoft.idef0.textimport;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser for the small DFD text DSL:
 *
 * <pre>
 * diagram: DFD "Обработка заявки"
 * external E1: "Клиент"
 * process P1: "Принять заявку"
 * store D1: "База заявок"
 * flow: E1 -&gt; P1 "Новая заявка"
 * flow: P1 -&gt; D1 "Сохранить заявку"
 * </pre>
 */
public class DfdDsl {

    public static class Node {
        public final String id;
        public final String name;
        public final String kind; // "process" | "external" | "store"
        public double cx, cy, w, h;

        Node(String id, String name, String kind) {
            this.id = id;
            this.name = name;
            this.kind = kind;
        }
    }

    public static class Flow {
        public final String fromId;
        public final String toId;
        public final String text;

        Flow(String fromId, String toId, String text) {
            this.fromId = fromId;
            this.toId = toId;
            this.text = text;
        }
    }

    public static class Model {
        public String title;
        public final Map<String, Node> nodes = new LinkedHashMap<>();
        public final List<Flow> flows = new ArrayList<>();
    }

    public static class ParseException extends RuntimeException {
        public ParseException(String message) {
            super(message);
        }
    }

    private static final Pattern DIAGRAM_RE =
            Pattern.compile("diagram:\\s*DFD\\s*\"([^\"]*)\"\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern NODE_RE =
            Pattern.compile("(process|external|store)\\s+(\\w+)\\s*:\\s*\"([^\"]*)\"\\s*$",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern FLOW_RE =
            Pattern.compile("flow:\\s*(\\w+)\\s*->\\s*(\\w+)\\s*\"([^\"]*)\"\\s*$",
                    Pattern.CASE_INSENSITIVE);

    public static Model parse(String text) {
        Model model = new Model();
        String[] lines = text.split("\r\n|\r|\n");
        int lineno = 0;
        for (String rawLine : lines) {
            lineno++;
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#"))
                continue;

            Matcher m = DIAGRAM_RE.matcher(line);
            if (m.matches()) {
                model.title = m.group(1);
                continue;
            }

            m = NODE_RE.matcher(line);
            if (m.matches()) {
                String kind = m.group(1).toLowerCase();
                String id = m.group(2);
                String name = m.group(3);
                if (model.nodes.containsKey(id))
                    throw new ParseException("line " + lineno + ": duplicate node id '" + id + "'");
                model.nodes.put(id, new Node(id, name, kind));
                continue;
            }

            m = FLOW_RE.matcher(line);
            if (m.matches()) {
                model.flows.add(new Flow(m.group(1), m.group(2), m.group(3)));
                continue;
            }

            throw new ParseException("line " + lineno + ": unrecognized line: " + rawLine);
        }

        if (model.title == null)
            throw new ParseException("missing header line: diagram: DFD \"...\"");
        if (model.nodes.isEmpty())
            throw new ParseException("model has no nodes");
        for (Flow f : model.flows) {
            if (!model.nodes.containsKey(f.fromId))
                throw new ParseException("flow references unknown node '" + f.fromId + "'");
            if (!model.nodes.containsKey(f.toId))
                throw new ParseException("flow references unknown node '" + f.toId + "'");
        }
        return model;
    }
}
