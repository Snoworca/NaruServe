const r='/tmp/naruserve-parity-research.KkcjUP'; const p3=require(r+'/e5/node_modules/path-to-regexp'); const p4=require(r+'/p8latest/node_modules/path-to-regexp');
for(const [label,p]of[['8.3.0',p3],['8.4.2',p4]]){
 for(const pattern of ['/x'+Array(9).fill('{/a}').join(''),'/x\\'])try{console.log(JSON.stringify({label,pattern,len:p.pathToRegexp(pattern).regexp.source.length}))}catch(e){console.log(JSON.stringify({label,pattern,error:e.message}))}
}
(async()=>{for(const [label,p]of[['express4',r+'/e4/node_modules/express'],['express5',r+'/e5/node_modules/express']]){const a=require(p)(); a.post('/a',(q,s)=>s.end());a.get('/a',(q,s)=>s.end());a.delete('/a',(q,s)=>s.end()); const srv=await new Promise(res=>{const s=a.listen(0,()=>res(s))});const response=await fetch('http://localhost:'+srv.address().port+'/a',{method:'OPTIONS'});console.log(JSON.stringify({label,allow:response.headers.get('allow'),body:await response.text()}));srv.close()}})();
