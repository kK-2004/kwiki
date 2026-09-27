import{afterEach,beforeEach,vi}from"vitest";import{cleanup}from"@testing-library/vue";
class ResizeObserver{observe(){}unobserve(){}disconnect(){}}
const values=new Map<string,string>();const storage={getItem:(key:string)=>values.get(key)??null,setItem:(key:string,value:string)=>values.set(key,String(value)),removeItem:(key:string)=>values.delete(key),clear:()=>values.clear(),key:(index:number)=>[...values.keys()][index]??null,get length(){return values.size}};
// 用例中的 vi.unstubAllGlobals() 会连同这些全局桩一起撤销，因此模块加载时安装一次、每个用例开始前再重新安装
const stubGlobals=()=>{vi.stubGlobal("localStorage",storage);vi.stubGlobal("ResizeObserver",ResizeObserver);vi.stubGlobal("crypto",{randomUUID:()=>"test-idempotency-key"})};stubGlobals();beforeEach(stubGlobals);afterEach(()=>{cleanup();storage.clear();vi.clearAllMocks()});
