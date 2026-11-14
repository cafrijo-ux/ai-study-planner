const http=require('node:http');
const fs=require('node:fs');
const path=require('node:path');
const project=path.resolve(__dirname,'..'),logDir=path.join(project,'verification/stage3b');
fs.mkdirSync(logDir,{recursive:true});
const roots={v1:path.join(project,'build/web'),v2:path.join(project,'build/update-v2')};
let active='v1'; // Latest settings fix; v1 remains available for rollback checks.
const port=process.argv[2]===undefined?59248:Number(process.argv[2]);
const maxMinutes=Number(process.env.PWA_PREVIEW_MINUTES||45);
if(!Number.isInteger(maxMinutes)||maxMinutes<1||maxMinutes>120)throw Error('Preview lifetime must be 1-120 minutes');
const startedAt=new Date().toISOString(),autoStopAt=new Date(Date.now()+maxMinutes*60000).toISOString();
if(!Number.isInteger(port)||port<49152||port>65535) throw Error('Use an ephemeral port 49152-65535');
if(!fs.existsSync(path.join(roots.v1,'pwa-sw.js'))) throw Error('Run prepare-pwa.cjs first');
const mime={'.html':'text/html; charset=utf-8','.js':'text/javascript','.json':'application/json','.wasm':'application/wasm','.png':'image/png','.ttf':'font/ttf','.otf':'font/otf','.txt':'text/plain'};
function writeState(running){fs.writeFileSync(path.join(logDir,'preview-state.json'),JSON.stringify({running,version:active,pid:process.pid,url:'http://127.0.0.1:'+port+'/ipad/',time:new Date().toISOString(),startedAt,autoStopAt,maxMinutes},null,2));}
const server=http.createServer((req,res)=>{
 let status=200,file,pathname;
 try{
   pathname=decodeURIComponent(new URL(req.url,'http://localhost').pathname);
   if(!['GET','HEAD'].includes(req.method))status=405;
   else if(!pathname.startsWith('/ipad/'))status=404;
   else {
     const root=roots[active],relative=pathname.slice('/ipad/'.length);
     file=path.resolve(root,relative||'index.html');
     if(!file.startsWith(root+path.sep))status=403;
     else if(!fs.existsSync(file)||!fs.statSync(file).isFile())status=404;
   }
   fs.appendFileSync(path.join(logDir,'requests.jsonl'),JSON.stringify({time:new Date().toISOString(),method:req.method,path:pathname,status,version:active})+'\n');
   res.writeHead(status,{'Content-Type':status===200?(mime[path.extname(file)]||'application/octet-stream'):'text/plain','Cache-Control':'no-store','X-Content-Type-Options':'nosniff'});
   if(status!==200||req.method==='HEAD')return res.end();
   fs.createReadStream(file).pipe(res);
 }catch{res.writeHead(400);res.end();}
});
function stop(){
 server.close(()=>{writeState(false);process.exit(0);});
 server.closeAllConnections();
}
server.listen(port,'127.0.0.1',()=>{
 writeState(true);
 console.log('Mock PWA: http://127.0.0.1:'+port+'/ipad/');
 console.log('Browser checks: http://127.0.0.1:'+port+'/ipad/pwa-check.html');
 console.log('stdin: v1 / v2 / stop. Auto-stop '+maxMinutes+' minutes. Static files only.');
});
process.stdin.setEncoding('utf8');
process.stdin.on('data',data=>{
 for(const line of data.trim().split(/\r?\n/)) {
   if(line==='stop')stop();
   else if(line in roots && fs.existsSync(path.join(roots[line],'pwa-sw.js'))) {
     active=line;writeState(true);console.log('Serving '+active+' at the SAME origin; app SW update requires old clients to close.');
   }
 }
});
process.on('SIGINT',stop);process.on('SIGTERM',stop);
setTimeout(stop,maxMinutes*60*1000).unref();

