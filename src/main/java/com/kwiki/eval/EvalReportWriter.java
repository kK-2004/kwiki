package com.kwiki.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 每个 run 写一份本地 JSON 报告：配置快照、汇总与逐题明细。 */
@Component
public class EvalReportWriter {

    private final ObjectMapper mapper;
    private final Path directory;

    public EvalReportWriter(ObjectMapper mapper,
                            @Value("${kwiki.eval.report-dir:./eval-reports}") String directory) {
        this.mapper = mapper;
        this.directory = Path.of(directory);
    }

    public Path write(String runName, Map<String, Object> config, EvalSummary summary, List<CaseResult> results) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("runName", runName);
        report.put("config", config);
        report.put("summary", summary);
        report.put("cases", results);
        try {
            Files.createDirectories(directory);
            Path file = directory.resolve(runName.replaceAll("[^A-Za-z0-9._-]", "_") + ".json");
            mapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), report);
            return file.toAbsolutePath();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write eval report", e);
        }
    }
}
