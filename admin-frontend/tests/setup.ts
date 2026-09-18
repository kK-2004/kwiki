import{afterEach,vi}from"vitest";import{cleanup}from"@testing-library/vue";
class ResizeObserver{observe(){}unobserve(){}disconnect(){}}
const values=new Map<string,string>();const storage={getItem:(key:string)=>values.get(key)??null,setItem:(key:string,value:string)=>values.set(key,String(value)),removeItem:(key:string)=>values.delete(key),clear:()=>values.clear(),key:(index:number)=>[...values.keys()][index]??null,get length(){return values.size}};
vi.stubGlobal("localStorage",storage);vi.stubGlobal("ResizeObserver",ResizeObserver);vi.stubGlobal("crypto",{randomUUID:()=>"test-idempotency-key"});afterEach(()=>{cleanup();storage.clear();vi.clearAllMocks()});
