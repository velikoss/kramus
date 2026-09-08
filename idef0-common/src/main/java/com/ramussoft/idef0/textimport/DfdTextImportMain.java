package com.ramussoft.idef0.textimport;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.dsoft.pb.types.FRectangle;
import com.ramussoft.common.AccessRules;
import com.ramussoft.common.Attribute;
import com.ramussoft.common.AttributeType;
import com.ramussoft.common.Engine;
import com.ramussoft.common.PluginProvider;
import com.ramussoft.common.Qualifier;
import com.ramussoft.common.journal.Journaled;
import com.ramussoft.core.impl.FileIEngineImpl;
import com.ramussoft.database.MemoryDatabase;
import com.ramussoft.idef0.IDEF0Plugin;
import com.ramussoft.idef0.IDEF0PluginProvider;
import com.ramussoft.pb.DataPlugin;
import com.ramussoft.pb.Function;
import com.ramussoft.pb.Sector;
import com.ramussoft.pb.Stream;
import com.ramussoft.pb.data.negine.NSectorBorder;
import com.ramussoft.pb.idef.elements.Ordinate;
import com.ramussoft.pb.idef.elements.PaintSector;
import com.ramussoft.pb.idef.elements.Point;
import com.ramussoft.pb.idef.elements.ReplaceStreamType;
import com.ramussoft.pb.idef.elements.SectorRefactor;
import com.ramussoft.pb.idef.visual.MovingArea;
import com.ramussoft.pb.idef.visual.MovingFunction;

/**
 * Headless: reads a DFD text DSL file and adds a new DFD diagram to an
 * existing .rms model file (create the empty model first via Ramus's own
 * File > New, then point this tool at it).
 *
 * Usage: DfdTextImportMain &lt;model.rms&gt; &lt;diagram.txt&gt;
 *
 * Uses the same underlying API the GUI uses to add function/external/store
 * boxes and connect them ({@link DataPlugin#createFunction}, {@link MovingArea}
 * + {@link SectorRefactor} + {@link PaintSector} for arrow sectors) — the
 * identical mid-level path {@code IDLImporter} uses for IDEF0 .idl import,
 * just driven by our own simpler DSL instead of parsed IDL text, and with no
 * text round-trip since we call the Java API directly.
 */
public class DfdTextImportMain {

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("usage: DfdTextImportMain <model.rms> <diagram.txt>");
            System.exit(1);
        }
        File modelFile = new File(args[0]);
        boolean createNew = !modelFile.exists();
        String dslText = new String(Files.readAllBytes(new File(args[1]).toPath()), StandardCharsets.UTF_8);

        DfdDsl.Model model;
        try {
            model = DfdDsl.parse(dslText);
        } catch (DfdDsl.ParseException e) {
            System.err.println("error: " + e.getMessage());
            System.exit(1);
            return;
        }
        DfdLayout.layout(model);

        final File file = createNew ? null : modelFile;
        MemoryDatabase database = new MemoryDatabase(false) {
            @Override
            protected File getFile() {
                return file;
            }

            @Override
            protected Collection<? extends PluginProvider> getAdditionalSuits() {
                ArrayList<PluginProvider> ps = new ArrayList<>(1);
                ps.add(new IDEF0PluginProvider());
                return ps;
            }
        };

        Engine engine = database.getEngine(null);
        AccessRules accessRules = database.getAccessRules(null);

        Journaled journaled = (Journaled) engine;
        journaled.startUserTransaction();
        try {
            buildDiagram(engine, accessRules, model);
            journaled.commitUserTransaction();
        } catch (RuntimeException e) {
            journaled.rollbackUserTransaction();
            throw e;
        }

        FileIEngineImpl impl = (FileIEngineImpl) engine.getDeligate();
        impl.saveToFile(modelFile);
        impl.close();

        System.out.println("added DFD diagram \"" + model.title + "\" (" + model.nodes.size()
                + " node(s), " + model.flows.size() + " flow(s)) to " + modelFile);
    }

    private static void buildDiagram(Engine engine, AccessRules accessRules, DfdDsl.Model model) {
        Qualifier qualifier = engine.createQualifier();

        Attribute nameAttr = findOrCreateNameAttribute(engine);
        qualifier.getAttributes().add(nameAttr);
        qualifier.setAttributeForName(nameAttr.getId());
        qualifier.setName(model.title);
        engine.updateQualifier(qualifier);

        IDEF0Plugin.installFunctionAttributes(qualifier, engine);

        DataPlugin plugin = com.ramussoft.idef0.NDataPluginFactory.getDataPlugin(qualifier, engine, accessRules);

        Function base = plugin.getBaseFunction();
        base.setDecompositionType(MovingArea.DIAGRAM_TYPE_DFD);

        Map<String, Function> functionsById = new HashMap<>();
        for (DfdDsl.Node node : model.nodes.values()) {
            int type;
            switch (node.kind) {
                case "external":
                    type = Function.TYPE_EXTERNAL_REFERENCE;
                    break;
                case "store":
                    type = Function.TYPE_DATA_STORE;
                    break;
                default:
                    type = Function.TYPE_PROCESS;
            }
            Function f = plugin.createFunction(base, type);
            f.setName(node.name);
            f.setBounds(new FRectangle(node.cx - node.w / 2, node.cy - node.h / 2, node.w, node.h));
            functionsById.put(node.id, f);
        }

        MovingArea area = new MovingArea(plugin, base);
        area.setActiveFunction(base);
        SectorRefactor refactor = area.getRefactor();

        Map<String, Stream> streamsByText = new HashMap<>();
        for (DfdDsl.Flow flow : model.flows) {
            Function from = functionsById.get(flow.fromId);
            Function to = functionsById.get(flow.toId);
            DfdDsl.Node fromNode = model.nodes.get(flow.fromId);
            DfdDsl.Node toNode = model.nodes.get(flow.toId);

            Sector sector = plugin.createSector();
            sector.setFunction(base);
            PaintSector ps = new PaintSector();
            ps.setSector(sector);
            ps.setMovingArea(area);
            refactor.addSector(ps);

            List<double[]> pts = DfdLayout.routeFlow(fromNode, toNode);

            Point[] points = new Point[pts.size()];
            Ordinate x = new Ordinate(Ordinate.TYPE_X);
            x.setPosition(pts.get(0)[0]);
            Ordinate y = new Ordinate(Ordinate.TYPE_Y);
            y.setPosition(pts.get(0)[1]);
            points[0] = new Point(x, y);
            for (int i = 1; i < pts.size(); i++) {
                double[] prev = pts.get(i - 1);
                double[] cur = pts.get(i);
                boolean horizontal = Math.abs(cur[0] - prev[0]) >= Math.abs(cur[1] - prev[1]);
                if (horizontal) {
                    x = new Ordinate(Ordinate.TYPE_X);
                    x.setPosition(cur[0]);
                } else {
                    y = new Ordinate(Ordinate.TYPE_Y);
                    y.setPosition(cur[1]);
                }
                points[i] = new Point(x, y);
            }
            ps.setPoints(points);

            NSectorBorder startBorder = sector.getStart();
            startBorder.setCrosspointA(plugin.createCrosspoint());
            startBorder.setFunctionTypeA(MovingFunction.RIGHT);
            startBorder.setFunctionA(from);
            startBorder.commit();

            NSectorBorder endBorder = sector.getEnd();
            endBorder.setCrosspointA(plugin.createCrosspoint());
            endBorder.setFunctionTypeA(MovingFunction.LEFT);
            endBorder.setFunctionA(to);
            endBorder.commit();

            Stream stream = streamsByText.computeIfAbsent(flow.text, text -> {
                Stream s = (Stream) plugin.createRow(plugin.getBaseStream(), true);
                s.setName(text);
                return s;
            });
            sector.setStream(stream, ReplaceStreamType.CHILDREN);
        }
    }

    static Attribute findOrCreateNameAttribute(Engine engine) {
        for (Attribute a : engine.getAttributes()) {
            if ("Core.Text".equals(a.getAttributeType().toString()) && "name".equalsIgnoreCase(a.getName())) {
                return a;
            }
        }
        Attribute attribute = engine.createAttribute(new AttributeType("Core", "Text", true));
        attribute.setName("name");
        engine.updateAttribute(attribute);
        return attribute;
    }
}
