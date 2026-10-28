const express=require('/tmp/naruserve-parity-research.KkcjUP/e5fixed/node_modules/express');
async function probe(name,setup,path='/a',method='GET'){
 const a=express(),trace=[];setup(a,trace);const s=await new Promise(r=>{const s=a.listen(0,()=>r(s))});
 try{const res=await fetch('http://localhost:'+s.address().port+path,{method,signal:AbortSignal.timeout(300)});console.log(JSON.stringify({name,status:res.status,trace,body:await res.text()}))}catch(e){console.log(JSON.stringify({name,trace,error:e.name}))}finally{s.closeAllConnections();s.close()}
}
(async()=>{
 await probe('array-alternative',(a,t)=>a.get(['/a/:x','/b/:y'],(q,s)=>s.json(q.params)),'/b/42');
 await probe('regexp-substring',(a,t)=>a.get(/foo/,(q,s)=>s.json({path:q.path,params:q.params})),'/prefixfooend');
 await probe('regexp-numeric-capture',(a,t)=>a.get(/\/x\/(\w+)/,(q,s)=>s.json(q.params)),'/x/42');
 await probe('regexp-named-capture',(a,t)=>a.get(/\/x\/(?<id>\w+)/,(q,s)=>s.json(q.params)),'/x/42');
 await probe('regexp-case-not-inherited',(a,t)=>a.get(/foo/,(q,s)=>s.end('yes')),'/FOO');
 await probe('delayed-next',(a,t)=>{a.get('/a',(q,s,n)=>setTimeout(()=>{t.push('late');n()},10),(q,s)=>s.json(t))});
 await probe('fulfilled-promise-no-next',(a,t)=>a.get('/a',async(q,s)=>{t.push('fulfilled')}));
 await probe('rejected-promise-error',(a,t)=>{a.get('/a',async(q,s)=>{throw new Error('bad')});a.use((e,q,s,n)=>{t.push(e.message);s.status(500).json(t)})});
 for(const merge of [false,true])await probe('nested-merge-'+merge,(a,t)=>{const r=express.Router({mergeParams:merge});r.get('/users/:id',(q,s)=>s.json(q.params));a.use('/api/:version',r)},'/api/v1/users/42');
 await probe('next-router',(a,t)=>{const r=express.Router();r.get('/a',(q,s,n)=>{t.push('router1');n('router')});r.get('/a',(q,s)=>{t.push('router2');s.end()});a.use(r);a.get('/a',(q,s)=>{t.push('parent');s.json(t)})});
 await probe('param-cached',(a,t)=>{a.param('id',(q,s,n,v)=>{t.push('param:'+v);n()});a.get('/:id',(q,s,n)=>{t.push('one');n()});a.get('/:id',(q,s)=>{t.push('two');s.json(t)})},'/42');
})();
