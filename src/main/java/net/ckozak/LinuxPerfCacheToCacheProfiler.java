package net.ckozak;

import org.openjdk.jmh.infra.BenchmarkParams;
import org.openjdk.jmh.profile.ExternalProfiler;
import org.openjdk.jmh.profile.ProfilerException;
import org.openjdk.jmh.results.AggregationPolicy;
import org.openjdk.jmh.results.Aggregator;
import org.openjdk.jmh.results.BenchmarkResult;
import org.openjdk.jmh.results.BenchmarkResultMetaData;
import org.openjdk.jmh.results.Defaults;
import org.openjdk.jmh.results.Result;
import org.openjdk.jmh.results.ResultRole;
import org.openjdk.jmh.util.FileUtils;
import org.openjdk.jmh.util.ListStatistics;
import org.openjdk.jmh.util.Statistics;
import org.openjdk.jmh.util.TempFile;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public final class LinuxPerfCacheToCacheProfiler implements ExternalProfiler {

    protected final TempFile perfBinData;

    public LinuxPerfCacheToCacheProfiler() throws ProfilerException {
        try {
            perfBinData = FileUtils.weakTempFile("perf-c2c-bin");
        } catch (IOException e) {
            throw new ProfilerException(e);
        }
    }

    @Override
    public Collection<String> addJVMInvokeOptions(BenchmarkParams params) {
        long delay = TimeUnit.NANOSECONDS.toMillis(params.getWarmup().getCount() *
                    params.getWarmup().getTime().convertTo(TimeUnit.NANOSECONDS))
                    + TimeUnit.SECONDS.toMillis(1); // loosely account for the JVM lag
        return new ArrayList<>(Arrays.asList(
                "perf", "c2c", "record", "-o", perfBinData.getAbsolutePath(), "--", "--delay", String.valueOf(delay)));
    }

    @Override
    public Collection<String> addJVMOptions(BenchmarkParams params) {
        return Collections.emptyList();
    }

    @Override
    public void beforeTrial(BenchmarkParams _params) {}

    @Override
    public Collection<? extends Result> afterTrial(BenchmarkResult br, long pid, File stdOut, File stdErr) {
        try {
            Process process = new ProcessBuilder("perf", "c2c", "report", "--stats", "-i", perfBinData.getAbsolutePath())
                    .redirectErrorStream(true)
                    .start();
            try (InputStream stream = process.getInputStream();
                 Reader isReader = new InputStreamReader(stream, StandardCharsets.UTF_8);
                 BufferedReader reader = new BufferedReader(isReader)) {
                StringBuilder output = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append('\n');
                }
                return Collections.singleton(new PerfCacheToCaceResult(output.toString()));
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public boolean allowPrintOut() {
        return false;
    }

    @Override
    public boolean allowPrintErr() {
        return false;
    }

    @Override
    public String getDescription() {
        return "Linux perf cache to cache (c2c) statistics";
    }

    static class PerfCacheToCaceResult extends Result<PerfCacheToCaceResult> {
        private static final long serialVersionUID = 1L;

        private final String output;

        public PerfCacheToCaceResult(String output) {
            super(ResultRole.SECONDARY, Defaults.PREFIX + "perf-c2c", of(Double.NaN), "---", AggregationPolicy.AVG);
            this.output = output;
        }

        private PerfCacheToCaceResult(String output, Statistics stat) {
            super(ResultRole.SECONDARY, Defaults.PREFIX + "perf-c2c", stat, "#/op", AggregationPolicy.AVG);
            this.output = output;
        }

        @Override
        protected Aggregator<PerfCacheToCaceResult> getThreadAggregator() {
            return new PerfResultAggregator();
        }

        @Override
        protected Aggregator<PerfCacheToCaceResult> getIterationAggregator() {
            return new PerfResultAggregator();
        }

        @Override
        protected Collection<? extends Result> getDerivativeResults() {
            return Collections.emptyList();
        }

        @Override
        public String toString() {
            return "perf c2c";
        }

        @Override
        public String extendedInfo() {
            return "Perf C2C report (stats):\n--------------------------------------------------\n" + output;
        }
    }

    static class PerfResultAggregator implements Aggregator<PerfCacheToCaceResult> {

        @Override
        public PerfCacheToCaceResult aggregate(Collection<PerfCacheToCaceResult> results) {
            String joined = results.stream()
                    .map(result -> result.output)
                    .collect(Collectors.joining("\n========================================\n"));
            ListStatistics stat = new ListStatistics();
            for (PerfCacheToCaceResult result : results) {
                stat.addValue(result.getScore());
            }
            return new PerfCacheToCaceResult(joined, stat);
        }
    }

}
