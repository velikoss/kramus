package com.ramussoft.idef0.textimport;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.Collection;

import com.ramussoft.common.AccessRules;
import com.ramussoft.common.Attribute;
import com.ramussoft.common.Engine;
import com.ramussoft.common.PluginProvider;
import com.ramussoft.common.Qualifier;
import com.ramussoft.common.journal.Journaled;
import com.ramussoft.core.impl.FileIEngineImpl;
import com.ramussoft.database.MemoryDatabase;
import com.ramussoft.idef0.IDEF0Plugin;
import com.ramussoft.idef0.IDEF0PluginProvider;
import com.ramussoft.idef0.NDataPluginFactory;
import com.ramussoft.pb.DataPlugin;

/**
 * Headless equivalent of Ramus's own "IDEF0 -&gt; Импортировать из IDL" menu
 * action: imports an .idl file into a model and writes it out as .rms, so the
 * result can just be opened instead of imported by hand — and so a malformed
 * .idl fails here, loudly, instead of silently in the GUI.
 *
 * Usage: IdlImportMain &lt;out.rms&gt; &lt;in.idl&gt; [encoding, default cp1251]
 */
public class IdlImportMain {

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: IdlImportMain <out.rms> <in.idl> [encoding]");
            System.exit(1);
        }
        File modelFile = new File(args[0]);
        File idlFile = new File(args[1]);
        String encoding = args.length >= 3 ? args[2] : "cp1251";
        if (!idlFile.exists()) {
            System.err.println("error: no such .idl file: " + idlFile);
            System.exit(1);
        }

        final File file = modelFile.exists() ? modelFile : null;
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
            Qualifier qualifier = engine.createQualifier();
            Attribute nameAttr = DfdTextImportMain.findOrCreateNameAttribute(engine);
            qualifier.getAttributes().add(nameAttr);
            qualifier.setAttributeForName(nameAttr.getId());
            engine.updateQualifier(qualifier);

            IDEF0Plugin.installFunctionAttributes(qualifier, engine);
            DataPlugin plugin = NDataPluginFactory.getDataPlugin(qualifier, engine, accessRules);

            try (FileInputStream in = new FileInputStream(idlFile)) {
                plugin.importFromIDL(plugin, encoding, in);
            }
            journaled.commitUserTransaction();
        } catch (Exception e) {
            journaled.rollbackUserTransaction();
            throw e;
        }

        FileIEngineImpl impl = (FileIEngineImpl) engine.getDeligate();
        impl.saveToFile(modelFile);
        impl.close();

        System.out.println("imported " + idlFile.getName() + " -> " + modelFile);
    }
}
