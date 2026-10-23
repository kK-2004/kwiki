package com.kwiki.eval;

import com.kk2004.common.response.TransDTO;
import com.kwiki.security.CurrentUser;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** RAG 召回评测管理接口；类级角色校验是全部命令的服务端边界。 */
@RestController
@RequestMapping("/api/v1/admin/eval")
@PreAuthorize("hasRole('ADMIN')")
public class EvalAdminController {

    private final EvalCorpusService corpus;
    private final EvalDatasetSyncService datasets;
    private final EvalRunService runs;

    public EvalAdminController(EvalCorpusService corpus, EvalDatasetSyncService datasets, EvalRunService runs) {
        this.corpus = corpus;
        this.datasets = datasets;
        this.runs = runs;
    }

    @PostMapping("/corpus/import")
    TransDTO<EvalCorpusService.ImportResult> importCorpus(@AuthenticationPrincipal CurrentUser user,
                                                          @RequestParam long kbId,
                                                          @RequestParam(defaultValue = "dragonball-zh") String corpus) {
        return TransDTO.success(this.corpus.importCorpus(user, kbId, corpus));
    }

    @PostMapping("/datasets/sync")
    TransDTO<Map<String, Integer>> syncDatasets() {
        EvalDatasetSyncService.SyncResult result = datasets.sync();
        return TransDTO.success(Map.of("items", result.items(), "written", result.written(),
                "skipped", result.skipped()));
    }

    @PostMapping("/runs")
    TransDTO<EvalRunService.RunStatus> start(@AuthenticationPrincipal CurrentUser user,
                                             @RequestBody EvalRunService.StartRequest request) {
        return TransDTO.success(runs.start(user, request));
    }

    @GetMapping("/runs")
    TransDTO<List<EvalRunService.RunStatus>> list() {
        return TransDTO.success(runs.list());
    }

    @GetMapping("/runs/{runId}")
    TransDTO<EvalRunService.RunStatus> status(@PathVariable String runId) {
        return TransDTO.success(runs.status(runId));
    }

    /** 完整报告（runName、config、summary、逐题结果），管理端据此下载 JSON。 */
    @GetMapping("/runs/{runId}/report")
    TransDTO<Map<String, Object>> report(@PathVariable String runId) {
        return TransDTO.success(runs.report(runId));
    }

    @PostMapping("/runs/{runId}/cancel")
    TransDTO<EvalRunService.RunStatus> cancel(@PathVariable String runId) {
        return TransDTO.success(runs.cancel(runId));
    }

    @PostMapping("/runs/{runId}/resume")
    TransDTO<EvalRunService.RunStatus> resume(@AuthenticationPrincipal CurrentUser user,
                                              @PathVariable String runId) {
        return TransDTO.success(runs.resume(user, runId));
    }

    /** 重试出错题：删除出错题的结果后重新排队，只重新评测这些题。 */
    @PostMapping("/runs/{runId}/retry-failed")
    TransDTO<EvalRunService.RunStatus> retryFailed(@AuthenticationPrincipal CurrentUser user,
                                                   @PathVariable String runId) {
        return TransDTO.success(runs.retryFailed(user, runId));
    }

    @DeleteMapping("/runs/{runId}")
    TransDTO<EvalRunService.DeleteResult> delete(@PathVariable String runId,
                                                 @RequestParam(defaultValue = "false") boolean langfuse) {
        return TransDTO.success(runs.delete(runId, langfuse));
    }
}
