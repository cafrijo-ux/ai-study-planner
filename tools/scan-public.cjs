'use strict';
// Local, value-redacting check. Use Gitleaks as an additional independent scanner.
const fs=require('node:fs'),path=require('node:path');
const root=path.resolve(__dirname,'..');let count=0;const findings=[];
const rules=[
 ['gemini-key',/AIza[0-9A-Za-z_-]{30,}/g],
 ['openai-key',/\bsk-(?:proj-|svcacct-)?[A-Za-z0-9_-]{20,}/g],
 ['github-token',/\b(?:gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{30,})/g],
 ['private-key',/-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----/g],
 ['jwt',/\beyJ[A-Za-z0-9_-]{15,}\.[A-Za-z0-9_-]{15,}\.[A-Za-z0-9_-]{15,}/g],
 ['session-cookie',/(?:JSESSIONID|sessionid)\s*[=:]\s*[A-Za-z0-9_-]{24,}/gi],
];
function walk(dir){for(const item of fs.readdirSync(dir,{withFileTypes:true})){
 const file=path.join(dir,item.name);
 if(item.isSymbolicLink())throw Error('Review symlink manually: '+path.relative(root,file));
 if(item.isDirectory()){if(!/^(?:\.git|\.gradle.*|\.dart_tool|\.local|build|node_modules|verification)$/.test(item.name))walk(file);continue;}
 if(!/\.(?:java|dart|js|cjs|mjs|html|css|json|ya?ml|xml|properties|ps1|sh|bat|sql|gradle|kts|md|txt|example)$|^\.env/.test(item.name))continue;
 const text=fs.readFileSync(file,'utf8');count++;
 for(const [rule,pattern]of rules){pattern.lastIndex=0;let match;while((match=pattern.exec(text)))findings.push({file:path.relative(root,file).replaceAll('\\','/'),rule,line:text.slice(0,match.index).split('\n').length});}
 for(const [i,line]of text.split(/\r?\n/).entries()){
  const match=line.match(/^\s*(?:spring\.datasource\.password|(?:gemini|llm)\.api\.key|gpt\.weekly\.api-key)\s*=\s*(.*)$/);
  if(match&&match[1]&&!match[1].startsWith('${')&&!/^none\s*$/.test(match[1]))findings.push({file:path.relative(root,file).replaceAll('\\','/'),rule:'literal-config-credential',line:i+1});
 }
}}
walk(root);console.log(JSON.stringify({files:count,findings},null,2));if(findings.length)process.exitCode=1;
