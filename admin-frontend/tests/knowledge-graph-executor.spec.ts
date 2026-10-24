import {describe,it,expect,vi} from "vitest";
import {render,screen,fireEvent} from "@testing-library/vue";
import {createPinia} from "pinia";
import ElementPlus from "element-plus";
const mocks=vi.hoisted(()=>({graphServiceStatus:vi.fn(),graphSubmit:vi.fn(),versions:vi.fn(),graphBatches:vi.fn(),graphRuns:vi.fn(),graphSchedule:vi.fn(),graphPublications:vi.fn(),graphSnapshots:vi.fn(),graphAudits:vi.fn(),adminKnowledgeBases:vi.fn(),graphPlugins:vi.fn(),graphFreshness:vi.fn()}));
vi.mock("../src/api",async original=>{const actual=await original<typeof import("../src/api")>();return {...actual,api:{...actual.api,...mocks}}});
import View from "../src/views/KnowledgeGraphView.vue";

const pluginsFixture={extractors:[{id:"kwiki-extractor-1",label:"大模型抽取 v1",available:true,unavailableReason:null}],communityDetectors:[{id:"arcadedb-leiden-1",label:"ArcadeDB Leiden v1",available:true,unavailableReason:null}],summarizers:[{id:"kwiki-summarizer-1",label:"大模型摘要 v1",available:true,unavailableReason:null},{id:"other-summarizer",label:"不可用摘要器",available:false,unavailableReason:"缺少配置：kwiki.graph.models.summary.api-key"}],defaults:{extractor:"kwiki-extractor-1",communityDetector:"arcadedb-leiden-1",summarizer:"kwiki-summarizer-1"}};

async function setup() {
  mocks.graphServiceStatus.mockResolvedValue({enabled:true,algorithmMode:"ARCADEDB_NATIVE_UNWEIGHTED",scheduleCron:"0 0 2 * * *",scheduleZone:"Asia/Shanghai",autoPublish:false,capacity:{maxEntities:50000,warnEntities:20000,maxRelations:250000,warnRelations:100000,maxCommunities:5000},activeBatchId:9});
  mocks.versions.mockResolvedValue([{versionNumber:5,selected:true,pipelineSupported:true,physicalName:"kwiki-chunks-v5",indexSchemaLabel:"图文 + 实体关联 v1",schemaCapabilities:["ENTITY_LINKING"],configuration:{indexSchema:"kwiki-schema-1"}}]);
  mocks.graphBatches.mockResolvedValue([{id:9,scopeKind:"KNOWLEDGE_BASE",chunkIndexVersion:5,communityIndexVersion:6,state:"EXTRACTING",autoPublish:false,requestedBy:"admin",scheduleDate:null,failureSummary:null,createdAt:"2026-10-03T02:00:00Z",extractor:"kwiki-extractor-1",communityDetector:"arcadedb-leiden-1",summarizer:"kwiki-summarizer-1"}]);
  mocks.graphRuns.mockResolvedValue([{id:12,kbId:7,chunkIndexVersion:5,communityIndexVersion:6,communityPhysicalIndex:"kwiki-communities-v6-kb7",graphVersion:42,state:"EXTRACTING",stage:"EXTRACTING",entityCount:0,relationCount:0,sourceCount:0,communityCount:0,extractor:"kwiki-extractor-1",communityDetector:"arcadedb-leiden-1",summarizer:"kwiki-summarizer-1",sourcesTotal:100,sourcesExtracted:40,extractionCacheHits:15,communitiesTotal:0,communitiesSummarized:0}]);
  mocks.graphSchedule.mockResolvedValue([]);
  mocks.graphPublications.mockResolvedValue([]);
  mocks.graphSnapshots.mockResolvedValue([{id:91,kbId:7,graphVersion:40,chunkIndexVersion:5,communityIndexVersion:6,communityPhysicalIndex:"kwiki-communities-v6-kb7",state:"READY",entityCount:10,relationCount:20,communityCount:2,sealedAt:"2026-10-02T03:00:00Z",retiredAt:null,createdAt:"2026-10-02T02:00:00Z",extractor:"kwiki-extractor-1",communityDetector:"arcadedb-leiden-1",summarizer:"kwiki-summarizer-1"}]);
  mocks.graphAudits.mockResolvedValue([]);
  mocks.adminKnowledgeBases.mockResolvedValue([{id:7,name:"知识库七"}]);
  mocks.graphPlugins.mockResolvedValue(pluginsFixture);
  mocks.graphFreshness.mockResolvedValue([]);
  mocks.graphSubmit.mockResolvedValue({batchId:13,chunkIndexSchema:"kwiki-schema-1",communityIndexVersion:6,runIds:[21],replayed:false});
  render(View,{global:{plugins:[createPinia(),ElementPlus]}});
}

describe("图构建执行器前端",()=>{
  it("子任务显示中文状态、阶段与抽取进度文本",async ()=>{
    await setup();
    expect(await screen.findByText("抽取中")).toBeTruthy();
    // 状态与阶段相同时只显示一次：不会出现重复的「抽取中 抽取中」
    expect(await screen.findByText("已抽取 40 / 100（缓存命中 15）")).toBeTruthy();
    expect(screen.getByText("就绪")).toBeTruthy();
  });

  it("提交表单三项插件下拉默认选中默认插件",async ()=>{
    await setup();
    // 切到「全部知识库」以跳过单库选择
    await fireEvent.click(await screen.findByText("全部知识库"));
    const submit=await screen.findByRole("button",{name:"提交构建批次"});
    await fireEvent.click(submit);
    await vi.waitFor(()=>{
      const payload=mocks.graphSubmit.mock.calls.at(-1)?.[0] as Record<string,string>;
      expect(payload.extractor).toBe("kwiki-extractor-1");
      expect(payload.communityDetector).toBe("arcadedb-leiden-1");
      expect(payload.summarizer).toBe("kwiki-summarizer-1");
    });
  });

  it("不可用的插件在下拉中置灰并显示原因",async ()=>{
    await setup();
    // 打开摘要插件下拉（占位文案定位触发器）
    const trigger=await screen.findByText("选择社区摘要插件");
    await fireEvent.click(trigger.closest(".el-select") ?? trigger);
    const optionLabel=await screen.findByText(/不可用摘要器（缺少配置/);
    const option=optionLabel.closest("li");
    expect(option?.className).toContain("is-disabled");
  });

  it("批次与快照显示插件组合",async ()=>{
    await setup();
    expect((await screen.findAllByText("kwiki-extractor-1 / arcadedb-leiden-1 / kwiki-summarizer-1")).length).toBeGreaterThanOrEqual(2);
  });
});
