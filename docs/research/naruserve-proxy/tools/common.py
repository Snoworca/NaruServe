import json,subprocess,hashlib,sys,os
R="2026-11-14.NaruServe.naruserve-proxy"; ROOT="/Users/beom/work/git/NaruServe"; WR="docs/research/naruserve-proxy/"
G=f"{WR}waves/waves.json"
os.chdir(ROOT)
def sh(*a,check=True):
  r=subprocess.run(a,capture_output=True,text=True)
  if check and r.returncode!=0: print(r.stdout,r.stderr); sys.exit(1)
  return r.stdout.strip()
def sha(p): return hashlib.sha256(open(p,'rb').read()).hexdigest()
def app(p):
  p={"schema_version":"2.0.0","run_id":R,"engine":"kiwi-orchestrator",**p}
  r=subprocess.run(["speckiwi","orchestrate","journal","append","--run-id",R,"--payload",json.dumps(p),"--json"],capture_output=True,text=True)
  o=json.loads(r.stdout or '{}'); print("journal",p.get("event"),p.get("verb") or p.get("status"),p.get("wave",""),o.get("ok"))
  if not o.get("ok"): print(r.stdout); sys.exit(1)
def commit(msg,trail,paths,allow_empty=False):
  m=msg+"\n\n"+"\n".join(f"{k}: {v}" for k,v in trail)+"\nCo-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>\n"
  open('/tmp/orch-msg.txt','w').write(m)
  if paths: sh("git","add","--",*paths)
  sh("git","commit","-q","-F","/tmp/orch-msg.txt",*(["--allow-empty"] if allow_empty else []))
  return sh("git","rev-parse","HEAD")
def nodes(): return {n['wave']:n for n in json.load(open(G))['nodes']}
def reqs_of(node): return node.get('requirements') or list(dict.fromkeys(a.split(' ')[0] for a in node['acs']))
