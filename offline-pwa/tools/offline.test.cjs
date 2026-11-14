const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const vm=require('node:vm');
const {webcrypto,createHash}=require('node:crypto');
const path=require('node:path');
const cp=require('node:child_process');
const {validateRecord,storageKey,errorCode,CacheError}=require('../web/study-cache.js');
const sha=b=>createHash('sha256').update(b).digest('hex');
const template=fs.readFileSync(path.join(__dirname,'pwa-sw.template.js'),'utf8');
class Cache {
  constructor(){this.rows=new Map();}
  async put(key,response){this.rows.set(String(key.url||key),response.clone());}
  async match(key){return this.rows.get(String(key.url||key))?.clone();}
}
class Storage {
  constructor(){this.rows=new Map();}
  async open(key){if(!this.rows.has(key))this.rows.set(key,new Cache());return this.rows.get(key);}
  async keys(){return [...this.rows.keys()];}
  async delete(key){return this.rows.delete(key);}
}
function worker(version,storage=new Storage(),bad=false) {
  const contents={'index.html':'<h1>'+version+'</h1>','main.dart.js':'/* '+version+' */','fonts/korean.ttf':'font-data'};
  const files=Object.entries(contents).map(([p,b])=>({path:p,sha256:sha(b),bytes:b.length}));
  const handlers={},stats={network:0,claim:0,skip:0};
  const context={
    self:{location:{href:'https://planner.example/ipad/pwa-sw.js'},
      clients:{claim:async()=>{stats.claim++;}},skipWaiting:()=>{stats.skip++;},
      addEventListener:(name,fn)=>{handlers[name]=fn;}},
    caches:storage,crypto:webcrypto,URL,Request,Response,console,
    fetch:async request=>{
      stats.network++;
      const key=new URL(request.url).pathname.slice('/ipad/'.length);
      if(!(key in contents))return new Response('missing',{status:404});
      return new Response(bad?'tampered':contents[key]);
    },
  };
  vm.runInNewContext(template.replace('__VERSION__',JSON.stringify(version)).replace('__FILES__',JSON.stringify(files)),context);
  async function event(name,extra={}) {
    const pending=[];let response;
    handlers[name]({waitUntil:p=>pending.push(p),respondWith:p=>{response=p;},...extra});
    await Promise.all(pending);
    return response?await response:null;
  }
  return {context,stats,event,storage};
}
test('install verifies every hash before readiness; never skipWaiting',async()=>{
  const w=worker('one');
  await w.event('install');
  assert.equal(w.stats.network,3);assert.equal(w.stats.skip,0);
  await w.event('activate');assert.equal(w.stats.claim,1);
  let status;
  await w.event('message',{data:{type:'STATUS'},ports:[{postMessage:s=>{status=s;}}]});
  assert.equal(status.ready,true);assert.equal(status.version,'one');
});
test('cached app restarts and reloads with fetch unavailable',async()=>{
  const w=worker('offline');await w.event('install');await w.event('activate');
  w.context.fetch=async()=>{throw Error('network offline');};
  const r=await w.event('fetch',{request:{url:'https://planner.example/ipad/',method:'GET',mode:'navigate'}});
  assert.equal(await r.text(),'<h1>offline</h1>');
  const js=await w.event('fetch',{request:{url:'https://planner.example/ipad/main.dart.js',method:'GET',mode:'cors'}});
  assert.equal(await js.text(),'/* offline */');
});
test('API paths, POST, query and outside scope never receive app HTML',async()=>{
  const w=worker('boundaries');await w.event('install');
  for(const [url,method] of [
    ['https://planner.example/auth/csrf','GET'],
    ['https://planner.example/api/goals','GET'],
    ['https://planner.example/study-logs','GET'],
    ['https://planner.example/llm-coaching','GET'],
    ['https://planner.example/ipad/auth/csrf','GET'],
    ['https://planner.example/ipad/api/goals','GET'],
    ['https://planner.example/ipad/study-logs','GET'],
    ['https://planner.example/ipad/main.dart.js?q=x','GET'],
    ['https://other.example/ipad/main.dart.js','GET'],
    ['https://planner.example/ipad/index.html','POST'],
    ['https://planner.example/dashboard.html','GET'],
  ]) assert.equal(await w.event('fetch',{request:{url,method,mode:'navigate'}}),null,url);
});
test('failed version install removes only incomplete own cache',async()=>{
  const storage=new Storage(),old=worker('old',storage);await old.event('install');
  const failed=worker('bad',storage,true);
  await assert.rejects(failed.event('install'),/PRECACHE_HASH/);
  assert.deepEqual(await storage.keys(),['planner-ipad-shell:/ipad/:old']);
});
test('update keeps old bundle while waiting; activation cleans own old cache only',async()=>{
  const storage=new Storage();
  await storage.open('pc-web-cache');
  const old=worker('v1',storage);await old.event('install');await old.event('activate');
  const next=worker('v2',storage);await next.event('install');
  assert.equal(next.stats.skip,0);
  const r=await old.event('fetch',{request:{url:'https://planner.example/ipad/main.dart.js',method:'GET'}});
  assert.equal(await r.text(),'/* v1 */');
  assert.equal((await storage.keys()).length,3);
  // Browser lifecycle activation after all old clients close is simulated here.
  await next.event('activate');
  assert.deepEqual((await storage.keys()).sort(),['pc-web-cache','planner-ipad-shell:/ipad/:v2']);
});
test('missing cache resource makes readiness false and returns 503, never new network files',async()=>{
  const w=worker('evicted');await w.event('install');
  const cache=await w.storage.open('planner-ipad-shell:/ipad/:evicted');
  cache.rows.delete('https://planner.example/ipad/main.dart.js');
  let status;
  await w.event('message',{data:{type:'STATUS'},ports:[{postMessage:s=>{status=s;}}]});
  assert.equal(status.ready,false);
  const r=await w.event('fetch',{request:{url:'https://planner.example/ipad/main.dart.js',method:'GET'}});
  assert.equal(r.status,503);assert.equal(w.stats.network,3);
});
const identity={server:'https://mock.invalid',userId:1};
const key={...identity,goalId:1,kind:'day',period:'2026-11-06'};
const record=()=>({schema:1,key,source:'MOCK_FIXTURE',syncedAt:'2026-11-06T00:00:00Z',payload:{saved:true,goalId:1,planDate:'2026-11-06',planStatus:'FINAL'}});
test('snapshot keys partition users, goals and dates',()=>{
  const first=storageKey(key);
  assert.notEqual(first,storageKey({...key,userId:2}));
  assert.notEqual(first,storageKey({...key,goalId:2}));
  assert.notEqual(first,storageKey({...key,period:'2026-11-07'}));
});
test('real-data storage and unsupported source are rejected',()=>{
  assert.throws(()=>storageKey({...key,server:'https://real.example'}),/REAL_DATA_DISABLED/);
  assert.throws(()=>validateRecord({...record(),source:'SERVER'}),/CORRUPT/);
});
test('FINAL/DRAFT and known absent plan remain valid distinct records',()=>{
  validateRecord(record());
  validateRecord({...record(),payload:{...record().payload,planStatus:'DRAFT'}});
  validateRecord({...record(),payload:{saved:false,goalId:1,planDate:'2026-11-06'}});
  assert.throws(()=>validateRecord({...record(),payload:{...record().payload,planStatus:'UNKNOWN'}}),/CORRUPT/);
  assert.throws(()=>validateRecord({...record(),payload:{saved:false,goalId:99,planDate:'2026-11-06'}}),/CORRUPT/);
});
test('credentials are blocked recursively and corrupt dates rejected',()=>{
  for(const field of ['password','JSESSIONID','csrf_token','apiKey','Authorization']) {
    assert.throws(()=>validateRecord({...record(),payload:{...record().payload,nested:{[field]:'x'}}}),/SENSITIVE_FIELD/);
  }
  assert.throws(()=>storageKey({...key,period:'2026-02-31'}),/INVALID_PERIOD/);
});
test('quota and storage errors retain actionable categories',()=>{
  assert.equal(errorCode({name:'QuotaExceededError'}),'QUOTA');
  assert.equal(errorCode({name:'SecurityError'}),'UNAVAILABLE');
  assert.equal(errorCode(new CacheError('CORRUPT')),'CORRUPT');
});
test('offline cold start keeps existing cached controller ready when registration update fails',async()=>{
  const channels=[];
  function Channel(){
    const first={close(){},onmessage:null};
    this.port1=first;this.port2={postMessage:data=>first.onmessage({data})};
    channels.push(this);
  }
  const context={URL,MessageChannel:Channel,setTimeout,clearTimeout,
    document:{baseURI:'https://planner.example/ipad/'},
    PlannerSnapshot:{MockSnapshotStore:class {}},
    navigator:{onLine:false,serviceWorker:{
      register:async()=>{throw Error('network offline');},
      controller:{postMessage:(_,ports)=>ports[0].postMessage({ready:true,version:'cached-v1'})},
    }},
    window:{isSecureContext:true,addEventListener(){}},
  };
  vm.runInNewContext(fs.readFileSync(path.join(__dirname,'../web/pwa-runtime.js'),'utf8'),context);
  await context.window.PlannerPwaStart();
  const status=JSON.parse(context.window.PlannerOfflineBridge.status());
  assert.equal(status.shellReady,true);assert.equal(status.online,false);
  assert.equal(status.error,null);assert.equal(status.version,'cached-v1');
});
test('packer produces stable version, stamped HTML and exact hashes including nested index',()=>{
  const project=path.resolve(__dirname,'..'),dir=path.join(project,'build','packer-test-'+Date.now());
  const contents={
    'index.html':'<html><head><base href="/ipad/"></head></html>',
    'main.dart.js':'app','flutter_bootstrap.js':'_flutter.loader.load({config:{}});',
    'study-cache.js':'store','pwa-runtime.js':'runtime','manifest.json':'{}',
    'canvaskit/canvaskit.wasm':'wasm','assets/assets/fonts/NotoSansKR.ttf':'font',
    'assets/FontManifest.json':JSON.stringify([{family:'PlannerKorean',fonts:[{asset:'assets/fonts/NotoSansKR.ttf'}]},{family:'Roboto',fonts:[{asset:'fonts/fallback/Roboto-Regular.ttf'}]}]),
    'nested/index.html':'nested HTML must retain its own digest',
  };
  for(const [file,data] of Object.entries(contents)){
    const target=path.join(dir,file);fs.mkdirSync(path.dirname(target),{recursive:true});fs.writeFileSync(target,data);
  }
  function pack(){
    const r=cp.spawnSync(process.execPath,[path.join(__dirname,'prepare-pwa.cjs'),path.relative(project,dir)],{encoding:'utf8'});
    assert.equal(r.status,0,r.stderr);
    return JSON.parse(fs.readFileSync(path.join(dir,'precache-manifest.json'),'utf8'));
  }
  const first=pack(),second=pack();assert.equal(first.version,second.version);
  const fonts=JSON.parse(fs.readFileSync(path.join(dir,'assets/FontManifest.json'),'utf8'));
  assert.equal(fonts.find(f=>f.family==='Roboto').fonts[0].asset,'assets/fonts/NotoSansKR.ttf');
  assert(fs.readFileSync(path.join(dir,'index.html'),'utf8').includes('content="'+first.version+'"'));
  for(const file of first.files)assert.equal(sha(fs.readFileSync(path.join(dir,file.path))),file.sha256,file.path);
});

