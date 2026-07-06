package bench;

import com.powsybl.cgmes.conversion.Conversion;
import com.powsybl.cgmes.model.CgmesModel;
import com.powsybl.cgmes.model.CgmesModelFactory;
import com.powsybl.commons.datasource.ReadOnlyDataSource;
import com.powsybl.commons.datasource.ZipArchiveDataSource;
import com.powsybl.iidm.network.Network;
import com.powsybl.triplestore.api.TripleStoreFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Memory scoping harness for CGMES import.
 *
 * Phases:
 *   0. baseline (JVM warm, nothing loaded)
 *   1. CgmesModel loaded (rdf4j MemoryStore populated, no conversion yet)
 *   2. Network converted, CGMES model still open (peak-like state)
 *   3. CGMES model closed, Network alive (what a default import retains)
 *   4. Network dropped (proves nothing else pins memory)
 *
 * At each phase: force GC, report used heap, and dump a jcmd class histogram
 * to a file so we can attribute bytes to classes.
 */
public final class CgmesMemBench {

    private CgmesMemBench() {
    }

    static long usedHeapAfterGc() {
        for (int i = 0; i < 5; i++) {
            System.gc();
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        var mu = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        return mu.getUsed();
    }

    static void resetPeaks() {
        ManagementFactory.getMemoryPoolMXBeans().forEach(java.lang.management.MemoryPoolMXBean::resetPeakUsage);
    }

    static long heapPeak() {
        return ManagementFactory.getMemoryPoolMXBeans().stream()
                .filter(p -> p.getType() == java.lang.management.MemoryType.HEAP)
                .mapToLong(p -> p.getPeakUsage().getUsed())
                .sum();
    }

    static long allocatedBytes() {
        var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long sum = 0;
        for (long id : bean.getAllThreadIds()) {
            long a = bean.getThreadAllocatedBytes(id);
            if (a > 0) {
                sum += a;
            }
        }
        return sum;
    }

    static void histogram(String tag, Path outDir) throws Exception {
        long pid = ProcessHandle.current().pid();
        Process p = new ProcessBuilder("jcmd", Long.toString(pid), "GC.class_histogram")
                .redirectErrorStream(true)
                .start();
        List<String> lines = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = r.readLine()) != null) {
                lines.add(line);
            }
        }
        p.waitFor();
        Files.write(outDir.resolve("histo-" + tag + ".txt"), lines);
    }

    static void report(String phase, long baseline, Path outDir) throws Exception {
        long used = usedHeapAfterGc();
        System.out.printf("PHASE %-28s usedHeap=%8.1f MB  delta=%8.1f MB%n",
                phase, used / 1048576.0, (used - baseline) / 1048576.0);
        histogram(phase.replaceAll("[^a-zA-Z0-9-]", "_"), outDir);
    }

    public static void main(String[] args) throws Exception {
        Path zip = Paths.get(args[0]);
        Path outDir = Paths.get(args[1]);
        boolean retainExtensions = args.length > 2 && Boolean.parseBoolean(args[2]);
        Files.createDirectories(outDir);

        long baseline = usedHeapAfterGc();
        System.out.printf("PHASE %-28s usedHeap=%8.1f MB%n", "0-baseline", baseline / 1048576.0);

        ReadOnlyDataSource ds = new ZipArchiveDataSource(zip);

        // Phase 1: triplestore + CgmesModel only
        resetPeaks();
        long alloc0 = allocatedBytes();
        long t0 = System.nanoTime();
        CgmesModel cgmes = CgmesModelFactory.create(ds, TripleStoreFactory.defaultImplementation());
        long t1 = System.nanoTime();
        long alloc1 = allocatedBytes();
        System.out.printf("TIME  load-triplestore          %8.2f s   peakHeap=%8.1f MB   allocated=%8.1f MB%n",
                (t1 - t0) / 1e9, heapPeak() / 1048576.0, (alloc1 - alloc0) / 1048576.0);
        report("1-cgmesmodel-loaded", baseline, outDir);

        // Phase 2: conversion, keeping the model open
        Conversion.Config config = new Conversion.Config();
        config.setStoreCgmesModelAsNetworkExtension(retainExtensions);
        config.setStoreCgmesConversionContextAsNetworkExtension(retainExtensions);
        resetPeaks();
        long alloc2 = allocatedBytes();
        long t2 = System.nanoTime();
        Conversion conversion = new Conversion(cgmes, config);
        Network network = conversion.convert();
        long t3 = System.nanoTime();
        long alloc3 = allocatedBytes();
        System.out.printf("TIME  conversion                %8.2f s   peakHeap=%8.1f MB   allocated=%8.1f MB%n",
                (t3 - t2) / 1e9, heapPeak() / 1048576.0, (alloc3 - alloc2) / 1048576.0);
        System.out.println("INFO  network: " + network.getSubstationCount() + " substations, "
                + network.getVoltageLevelCount() + " voltage levels, "
                + network.getLineCount() + " lines, "
                + network.getBusBreakerView().getBusCount() + " buses");
        report("2-converted-model-open", baseline, outDir);

        // Phase 3: close the CGMES model (what the default import path does)
        if (!retainExtensions) {
            cgmes.close();
        }
        cgmes = null;
        conversion = null;
        report("3-model-closed-network-alive", baseline, outDir);

        // Phase 4: drop the network too
        network = null;
        report("4-all-dropped", baseline, outDir);
    }
}
