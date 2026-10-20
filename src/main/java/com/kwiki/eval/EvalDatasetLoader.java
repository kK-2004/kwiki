package com.kwiki.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 读取仓库内唯一事实来源的 jsonl 测试集；id 必须唯一。 */
@Component
public class EvalDatasetLoader {

    public static final String DEFAULT_RESOURCE = "eval/datasets/kwiki-rag.jsonl";

    private final ObjectMapper mapper;

    public EvalDatasetLoader(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public List<EvalCase> load() {
        return load(new ClassPathResource(DEFAULT_RESOURCE));
    }

    public List<EvalCase> load(Resource resource) {
        List<EvalCase> cases = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        try (var reader = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            int lineNo = 0;
            while ((line = reader.readLine()) != null) {
                lineNo++;
                if (line.isBlank()) continue;
                EvalCase evalCase;
                try {
                    evalCase = mapper.readValue(line, EvalCase.class);
                } catch (IOException | IllegalArgumentException e) {
                    throw new IllegalArgumentException(
                            "invalid eval case at line " + lineNo + ": " + e.getMessage(), e);
                }
                if (!ids.add(evalCase.id())) {
                    throw new IllegalArgumentException("duplicate eval case id: " + evalCase.id());
                }
                cases.add(evalCase);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read eval dataset " + resource, e);
        }
        return List.copyOf(cases);
    }

    public static List<EvalCase> select(List<EvalCase> all, EvalDatasetSize size) {
        return all.stream().filter(evalCase -> evalCase.inDataset(size)).toList();
    }
}
