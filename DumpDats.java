import com.threerings.export.BinaryImporter;
import com.threerings.export.XMLExporter;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

/**
 * Dumps Spiral Knights config .dat files to XML using the game's own
 * BinaryImporter + XMLExporter. Each .dat round-trips to a sibling .xml.
 *
 * Usage: java DumpDats <path-to-rsrc-dir> [out-dir]
 *
 * Files dumped (stat-relevant):
 *   config/item.dat            -> item catalog + per-item LevelTableConfig refs
 *   config/level_table.dat     -> per-level stat deltas (LevelConfig entries)
 *   config/forge_property.dat  -> forge upgrades
 *   config/item_property.dat   -> misc item properties
 *   config/accessory.dat       -> accessory configs
 *   config/attack.dat          -> raw attack/damage data
 */
public final class DumpDats {
    private static final String[] FILES = {
        "config/item.dat",
        "config/level_table.dat",
        "config/forge_property.dat",
        "config/item_property.dat",
        "config/accessory.dat",
        "config/attack.dat",
    };

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: java DumpDats <rsrc-dir> [out-dir]");
            System.exit(2);
        }
        File rsrc = new File(args[0]);
        File outDir = new File(args.length >= 2 ? args[1] : "out");
        if (!outDir.isDirectory() && !outDir.mkdirs()) {
            throw new RuntimeException("cannot create " + outDir);
        }

        for (String rel : FILES) {
            File in = new File(rsrc, rel);
            if (!in.isFile()) {
                System.err.println("SKIP missing: " + in);
                continue;
            }
            String baseName = in.getName().replaceFirst("\\.dat$", ".xml");
            File out = new File(outDir, baseName);
            long t0 = System.currentTimeMillis();
            System.out.print("DUMP " + rel + " -> " + out.getName() + " ... ");
            try {
                dump(in, out);
                long ms = System.currentTimeMillis() - t0;
                System.out.println("ok (" + out.length() + " B, " + ms + " ms)");
            } catch (Throwable t) {
                System.out.println("FAILED: " + t);
                t.printStackTrace(System.err);
            }
        }
    }

    private static void dump(File in, File out) throws Exception {
        Object root;
        try (BinaryImporter bi = new BinaryImporter(
                new BufferedInputStream(new FileInputStream(in)))) {
            root = bi.pL();
        }
        try (XMLExporter xe = new XMLExporter(
                new BufferedOutputStream(new FileOutputStream(out)))) {
            xe.bf(root);
        }
    }
}
