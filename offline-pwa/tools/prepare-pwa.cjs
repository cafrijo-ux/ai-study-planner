const fs=require('node:fs');
const path=require('node:path');
const crypto=require('node:crypto');
const project=path.resolve(__dirname,'..');
const root=path.resolve(project,process.argv[2]||'build/web');
if(!root.startsWith(project+path.sep)||!root.includes(path.sep+'build'+path.sep)) throw Error('Only generated build output inside project is permitted');
const hash=b=>crypto.createHash('sha256').update(b).digest('hex');
// Web-only default-family coverage: explicit AppBar/Button styles can fall
// back to Roboto despite the page text theme. Reuse the bundled Korean face
// for that alias so CanvasKit does not request absent remote CJK shards.
const fontPath=path.join(root,'assets/FontManifest.json');
const fontManifest=JSON.parse(fs.readFileSync(fontPath,'utf8'));
const korean=fontManifest.find(f=>f.family==='PlannerKorean')?.fonts?.[0];
if(!korean)throw Error('Bundled Korean font is required');
let defaults=fontManifest.find(f=>f.family==='Roboto');
if(!defaults){defaults={family:'Roboto',fonts:[]};fontManifest.push(defaults);}
defaults.fonts=[{...korean}];
fs.writeFileSync(fontPath,JSON.stringify(fontManifest)+'\n');
const template=fs.readFileSync(path.join(__dirname,'pwa-sw.template.js'),'utf8');
const bootstrap=fs.readFileSync(path.join(root,'flutter_bootstrap.js'),'utf8');
const loaderCall=bootstrap.lastIndexOf('_flutter.loader.load(');
if(loaderCall<0 || /serviceWorkerSettings\s*:/.test(bootstrap.slice(loaderCall))) throw Error('Default Flutter SW registration must be disabled');
const originalIndex=fs.readFileSync(path.join(root,'index.html'),'utf8');
const index=originalIndex.includes('name="planner-build"')
 ? originalIndex.replace(/<meta name="planner-build" content="[^"]*">/,'<meta name="planner-build" content="__PWA_BUILD_ID__">')
 : originalIndex.replace('</head>','<meta name="planner-build" content="__PWA_BUILD_ID__">\n</head>');
if(!index.includes('<base href="/ipad/">')) throw Error('Build with --base-href=/ipad/');
const walk=dir=>fs.readdirSync(dir,{withFileTypes:true}).flatMap(e=>e.isDirectory()?walk(path.join(dir,e.name)):[path.join(dir,e.name)]);
let files=walk(root).filter(f=>!['pwa-sw.js','precache-manifest.json','flutter_service_worker.js','.last_build_id'].includes(path.basename(f)))
 .map(f=>({path:path.relative(root,f).replaceAll('\\','/'),sha256:hash(f===path.join(root,'index.html')?Buffer.from(index):fs.readFileSync(f)),bytes:f===path.join(root,'index.html')?Buffer.byteLength(index):fs.statSync(f).size})).sort((a,b)=>a.path.localeCompare(b.path));
for(const f of files) if(/^(auth|api)(\/|$)/.test(f.path)) throw Error('API path in static manifest');
for(const required of ['index.html','main.dart.js','flutter_bootstrap.js','study-cache.js','pwa-runtime.js','manifest.json','canvaskit/canvaskit.wasm','assets/FontManifest.json','assets/assets/fonts/NotoSansKR.ttf']) {
 if(!files.some(f=>f.path===required)) throw Error('Missing offline dependency: '+required);
}
const version=hash(JSON.stringify(files)+template+(process.env.PWA_BUILD_TAG||'')).slice(0,24);
fs.writeFileSync(path.join(root,'index.html'),index.replace('__PWA_BUILD_ID__',version));
files=files.map(f=>f.path==='index.html'?{...f,sha256:hash(fs.readFileSync(path.join(root,f.path))),bytes:fs.statSync(path.join(root,f.path)).size}:f);
fs.writeFileSync(path.join(root,'pwa-sw.js'),template.replace('__VERSION__',JSON.stringify(version)).replace('__FILES__',JSON.stringify(files)));
fs.writeFileSync(path.join(root,'precache-manifest.json'),JSON.stringify({version,scope:'/ipad/',files},null,2)+'\n');
console.log(JSON.stringify({version,files:files.length,bytes:files.reduce((n,f)=>n+f.bytes,0),scope:'/ipad/'}));

