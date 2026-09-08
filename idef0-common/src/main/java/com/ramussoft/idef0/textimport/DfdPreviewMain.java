package com.ramussoft.idef0.textimport;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Pure preview: DSL text -&gt; PNG, no .rms/engine involved at all. Usage:
 * DfdPreviewMain &lt;diagram.txt&gt; &lt;out.png&gt; [scale]
 */
public class DfdPreviewMain {

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: DfdPreviewMain <diagram.txt> <out.png> [scale]");
            System.exit(1);
        }
        String dslText = new String(Files.readAllBytes(new File(args[0]).toPath()), StandardCharsets.UTF_8);
        DfdDsl.Model model = DfdDsl.parse(dslText);
        DfdLayout.layout(model);
        double scale = args.length >= 3 ? Double.parseDouble(args[2]) : 0.35;
        File outFile = new File(args[1]);
        DfdPngRenderer.render(model, outFile, scale);
        System.out.println("wrote " + outFile);
    }
}
