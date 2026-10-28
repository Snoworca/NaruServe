#!/usr/bin/env node
'use strict';
// OPS-ROUTE-004 AC-1 AC-2 AC-5: external reference tool; never installed or invoked by Gradle.
const fs=require('node:fs'), path=require('node:path'), crypto=require('node:crypto');
const http=require('node:http'), {createRequire}=require('node:module');
const argv=process.argv.slice(2);function option(name,fallback){const i=argv.indexOf(name);if(i<0)return fallback;if(!argv[i+1]||argv[i+1].startsWith('--'))throw Error('Missing '+name);return argv[i+1];}
const referenceRoot=option('--reference-root',process.env.EXPRESS_REFERENCE_ROOT);
if(!referenceRoot)throw Error('Supply --reference-root pointing to the existing reference installation (no install is performed).');
if(process.versions.node!=='22.22.0')throw Error('Pinned oracle requires Node 22.22.0; observed '+process.versions.node);
const root=path.resolve(__dirname,'../..');
const output=path.resolve(option('--out',path.join(root,'src/test/resources/parity/express5.json')));
const lockFile=path.resolve(option('--lock',path.join(root,'src/test/resources/parity/reference-lock.json')));
const requireReference=createRequire(path.join(path.resolve(referenceRoot),'package.json'));
function packageRoot(entry,name){let d=path.dirname(entry);for(;;){const f=path.join(d,'package.json');if(fs.existsSync(f)&&JSON.parse(fs.readFileSync(f)).name===name)return d;const parent=path.dirname(d);if(parent===d)throw Error('Cannot find '+name);d=parent;}}
function hash(file){return crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex');}
const expressEntry=requireReference.resolve('express'),expressRoot=packageRoot(expressEntry,'express');
const expressRequire=createRequire(path.join(expressRoot,'package.json'));
const routerEntry=expressRequire.resolve('router'),routerRoot=packageRoot(routerEntry,'router');
const routerRequire=createRequire(path.join(routerRoot,'package.json'));
const regexpEntry=routerRequire.resolve('path-to-regexp'),regexpRoot=packageRoot(regexpEntry,'path-to-regexp');
const versions={express:'5.1.0',router:'2.2.0','path-to-regexp':'8.4.2'};
const packages={};
for(const [name,directory,entry] of [['express',expressRoot,expressEntry],['router',routerRoot,routerEntry],['path-to-regexp',regexpRoot,regexpEntry]]){
 const file=path.join(directory,'package.json'),version=JSON.parse(fs.readFileSync(file)).version;
 if(version!==versions[name])throw Error(name+' must be '+versions[name]+'; observed '+version);
 packages[name]={version,packageSha256:hash(file),entrySha256:hash(entry)};
}
const sources={};for(const [name,directory,files] of [['express',expressRoot,['lib/application.js']],['router',routerRoot,['index.js','lib/layer.js','lib/route.js']],['path-to-regexp',regexpRoot,['dist/index.js']]])for(const file of files)sources[name+'/'+file]=hash(path.join(directory,file));
const reference={node:process.versions.node,express:'5.1.0',router:'2.2.0',pathToRegexp:'8.4.2',packages,sources};
if(argv.includes('--write-lock')){fs.mkdirSync(path.dirname(lockFile),{recursive:true});fs.writeFileSync(lockFile,JSON.stringify(reference,null,2)+'\n');}
else if(JSON.stringify(JSON.parse(fs.readFileSync(lockFile)))!==JSON.stringify(reference))throw Error('Reference source/package hashes differ from committed lock; do not silently regenerate expectations.');
const express=requireReference('express');
const clone=value=>JSON.parse(JSON.stringify(value));
function view(req,handler){return {handler,params:clone(req.params),path:req.path,url:req.url,baseUrl:req.baseUrl,originalUrl:req.originalUrl};}
function record(req,trace,handler){trace.push(view(req,handler));}
function text(req,res,trace,handler,body,status=200){record(req,trace,handler);res.status(status).type('text/plain').send(body);}
const essentialHeaders=['allow','content-type','content-length','location','x-handler'];
function canonicalHeader(name,value){return name==='content-type'&&typeof value==='string'?value.replace(/(;[ \t]*)charset=utf-8(?=;|$)/gi,'$1charset=utf-8'):value;}
async function runCase(id,feature,setup,rawPath='/a',method='GET'){
 const app=express();app.disable('x-powered-by');app.set('env','production');
 const trace=[],errors=[];let completion='pending';
 app.use((req,res,next)=>{res.once('finish',()=>{completion='finished';});res.once('close',()=>{if(completion!=='finished')completion='aborted';});next();});
 try{setup(app,trace,errors);}catch(error){return {id,feature,registration:{success:false,error:{name:error.name,message:error.message}},request:null,observation:null};}
 const server=await new Promise((resolve,reject)=>{const s=app.listen(0,'127.0.0.1',()=>resolve(s));s.once('error',reject);});
 try{
  const response=await new Promise((resolve,reject)=>{const req=http.request({host:'127.0.0.1',port:server.address().port,method,path:rawPath},res=>{const chunks=[];res.on('data',data=>chunks.push(data));res.on('error',reject);res.on('end',()=>resolve({status:res.statusCode,headers:res.headers,body:Buffer.concat(chunks).toString('utf8')}));});req.setTimeout(2000,()=>req.destroy(Error('Reference request timed out: '+id)));req.once('error',reject);req.end();});
  await new Promise(resolve=>setImmediate(resolve));
  const headers={};for(const name of essentialHeaders)headers[name]=response.headers[name]===undefined?null:canonicalHeader(name,response.headers[name]);
  return {id,feature,registration:{success:true,error:null},request:{method,rawPath},observation:{status:response.status,headers,body:response.body,trace:clone(trace),completion:{state:completion,errors:clone(errors)}}};
 }finally{await new Promise(resolve=>server.close(resolve));}
}
(async()=>{
 const cases=[];const add=async(...args)=>cases.push(await runCase(...args));
 await add('literal.get','baseline',(a,t)=>a.get('/a',(q,s)=>text(q,s,t,'literal','literal')));
 await add('literal.post','baseline',(a,t)=>a.post('/a',(q,s)=>text(q,s,t,'posted','posted',201)),'/a','POST');
 await add('param.basic','baseline',(a,t)=>a.get('/users/:id',(q,s)=>text(q,s,t,'user','id='+q.params.id)),'/users/42');
 for(const [id,p] of [['optional.absent','/users'],['optional.present','/users/42']])await add(id,'grammar',(a,t)=>a.get('/users{/:id}',(q,s)=>text(q,s,t,'optional','ok')),p);
 await add('wildcard.list','grammar',(a,t)=>a.get('/files/*parts',(q,s)=>text(q,s,t,'wildcard','ok')),'/files/a/b');
 await add('param.duplicate','grammar',(a,t)=>a.get('/:id/:id',(q,s)=>text(q,s,t,'duplicate','ok')),'/a/b');
 await add('param.quoted','grammar',(a,t)=>a.get('/:"user-id"',(q,s)=>text(q,s,t,'quoted','ok')),'/42');
 await add('registration.unnamed-wildcard','registration-error',a=>a.get('/files/*',()=>{}));
 await add('registration.inline-regex','registration-error',a=>a.get('/x/:id(\\d+)',()=>{}));
 await add('array.alternative','path-alternatives',(a,t)=>a.get(['/a/:a','/b/:b'],(q,s)=>text(q,s,t,'alternative','ok')),'/b/42');
 await add('order.parameter-before-literal','ordered-stack',(a,t)=>{a.get('/:id',(q,s)=>text(q,s,t,'parameter','parameter'));a.get('/fixed',(q,s)=>text(q,s,t,'fixed','fixed'));},'/fixed');
 await add('route.next','route-boundary',(a,t)=>{a.route('/a').get((q,s,n)=>{record(q,t,'one');n();}).get((q,s)=>text(q,s,t,'two','two'));a.get('/a',(q,s)=>text(q,s,t,'three','three'));});
 await add('route.skip','route-boundary',(a,t)=>{a.route('/a').get((q,s,n)=>{record(q,t,'one');n('route');}).get((q,s)=>text(q,s,t,'two','two'));a.get('/a',(q,s)=>text(q,s,t,'three','three'));});
 await add('use.route-skip-continues','continuation',(a,t)=>{a.use('/a',(q,s,n)=>{record(q,t,'use-one');n('route');},(q,s,n)=>{record(q,t,'use-two');n();});a.get('/a',(q,s)=>text(q,s,t,'route','ok'));});
 await add('mount.layer-views','mount',(a,t)=>{a.use('/api/:version',(q,s,n)=>{record(q,t,'before');n();record(q,t,'after');});a.get('/api/:other/users',(q,s)=>text(q,s,t,'route','ok'));},'/api/v1/users?q=1');
 for(const merge of [false,true])await add('mount.merge-'+merge,'mount',(a,t)=>{const r=express.Router({mergeParams:merge});r.get('/users/:id',(q,s)=>text(q,s,t,'child','ok'));a.use('/api/:version',r);},'/api/v1/users/42');
 await add('router.exit','router-skip',(a,t)=>{const r=express.Router();r.get('/a',(q,s,n)=>{record(q,t,'child');n('router');});r.get('/a',(q,s)=>text(q,s,t,'skipped','bad'));a.use(r);a.get('/a',(q,s)=>text(q,s,t,'parent','ok'));});
 await add('param.callback-cache','param-hook',(a,t)=>{a.param('id',(q,s,n,v)=>{record(q,t,'param:'+v);n();});a.get('/:id',(q,s,n)=>{record(q,t,'one');n();});a.get('/:id',(q,s)=>text(q,s,t,'two','ok'));},'/42');
 for(const headFirst of [false,true])await add('head.order-'+headFirst,'http-defaults',(a,t)=>{const head=(q,s)=>{s.set('X-Handler','head');text(q,s,t,'head','head');};if(headFirst)a.head('/a',head);a.get('/a',(q,s)=>{s.set('X-Handler','get');text(q,s,t,'get','GET');});if(!headFirst)a.head('/a',head);},'/a','HEAD');
 await add('options.automatic','http-defaults',a=>{a.post('/a',(q,s)=>s.end());a.get('/a',(q,s)=>s.end());a.delete('/a',(q,s)=>s.end());},'/a','OPTIONS');
 await add('options.middleware-fallthrough','http-defaults',(a,t)=>{a.get('/a',(q,s)=>s.end());a.use((q,s)=>text(q,s,t,'fallback','custom',202));},'/a','OPTIONS');
 await add('error.thrown','error-flow',(a,t,e)=>{a.get('/a',(q,s)=>{record(q,t,'throw');throw Error('boom');});a.use((err,q,s,n)=>{e.push({name:err.name,message:err.message});text(q,s,t,'error',err.message,500);});});
 await add('async.delayed-next','async',(a,t)=>a.get('/a',(q,s,n)=>{record(q,t,'scheduled');setTimeout(()=>{record(q,t,'continued');n();},5);},(q,s)=>text(q,s,t,'final','ok')));
 await add('async.rejected','async',(a,t,e)=>{a.get('/a',async(q,s)=>{record(q,t,'async');throw Error('rejected');});a.use((err,q,s,n)=>{e.push({name:err.name,message:err.message});text(q,s,t,'error',err.message,500);});});
 await add('continuation.after-response','continuation',(a,t)=>{a.get('/a',(q,s,n)=>{text(q,s,t,'respond','ok');n();});a.use((q,s,n)=>{record(q,t,'after-response');n();});});
 await add('raw.encoded-slash','decoding',(a,t)=>a.get('/x/:id',(q,s)=>text(q,s,t,'decoded','ok')),'/x/a%2Fb');
 await add('raw.malformed-escape','decoding',(a,t,e)=>{a.get('/x/:id',(q,s)=>text(q,s,t,'unexpected','bad'));a.use((err,q,s,n)=>{e.push({name:err.name,message:err.message});text(q,s,t,'decode-error','bad path',err.status||500);});},'/x/%ZZ');
 const corpus={schemaVersion:1,reference,generatorSha256:hash(__filename),boundary:{regexEngine:'java.util.regex.Pattern',ecmascriptRegExpEquivalent:false,jni:false},normalization:{essentialHeaders,excludedHeaders:['date','connection','keep-alive','etag','x-powered-by'],notes:['Unquoted UTF-8 charset parameter tokens are lowercased on both sides (RFC 9110 sections 8.3.1/8.3.2); other header values remain exact.','Ephemeral TCP ports and elapsed times are not observations.','Stacks and filesystem paths are excluded; observed error names/messages are retained.','Response bytes, selected essential headers, trace order and absent/null/list parameter shape remain exact.','Java-specific Pattern cases belong to Java contract tests, not ECMAScript regex comparison.']},cases};
 fs.mkdirSync(path.dirname(output),{recursive:true});fs.writeFileSync(output,JSON.stringify(corpus,null,2)+'\n');
 console.log('Generated '+cases.length+' actual Express cases; no NaruServe runtime claim.');
})().catch(error=>{console.error(error.message);process.exitCode=1;});
