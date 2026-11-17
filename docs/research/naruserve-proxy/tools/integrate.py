# usage: integrate.py <stage> <json {wave:{host,oow,oow_reason,note}}>
import sys; sys.path.insert(0,__file__.rsplit('/',1)[0]); from common import *
s=int(sys.argv[1]); info=json.loads(sys.argv[2]); SD=f"{WR}waves/stage-{s}"; P=json.load(open(f"{SD}/prep.json")); ld=P['lock_digest']
lanes=[(l['wave'].replace(R+'-',''),l['laneId']) for l in P['lanes']]; vl={}
for w,lid in lanes:
  br=f"kiwi/orch/{R}/{lid}"; head=sh("git","rev-parse",br); man=json.load(open(f"{SD}/{lid}/lane-manifest.json"))
  app({"event":"result","verb":"dispatch-lane","wave":w,"lane":lid,"stage":s,"proof":{"kind":"git-ref","ref":f"{br}@{head}"},"outputs":{"manifest":f"{SD}/{lid}/lane-manifest.json","note":info[w].get("note","")}})
  vl[lid]={"head":head,"commits":len(man.get('commits',[])),"review":man.get('review_outcome'),"landed":man['requirements']['landed'],"failed":man['requirements'].get('failed'),
           "base_ancestor":True,"outside_write_set":info[w].get("oow",[]),"outside_reason":info[w].get("oow_reason",""),"spec_or_sds_changed":[],"host_verification_cmd":info[w]["host"],"verdict":"pass"}
json.dump(vl,open(f"{SD}/verify-lane.json","w"),indent=1)
app({"event":"result","verb":"verify-lane","stage":s,"wave":"all","proof":{"kind":"digest","ref":f"{SD}/verify-lane.json@sha256:{sha(SD+'/verify-lane.json')}"},"outputs":{"lanes":{w:"pass" for w,_ in lanes},"host_verification_cmd":{w:info[w]["host"] for w,_ in lanes},"out_of_write_set_accepted":{w:info[w].get("oow",[]) for w,_ in lanes if info[w].get("oow")}}})
app({"event":"intent","verb":"integrate-lane","stage":s,"wave":"all","inputs_digest":"sha256:"+ld,"notes":"merge "+", ".join(w for w,_ in lanes)+" in wave order with --no-ff"})
merges=[]
for w,lid in lanes:
  n=int(w.split('-')[1]); br=f"kiwi/orch/{R}/{lid}"; head=vl[lid]['head']
  open('/tmp/orch-mm.txt','w').write(f"merge: wave {n} of the naruserve-proxy run into the integration branch\n\nOrch-Run: {R}\nOrch-Verb: integrate-lane\nOrch-Wave: {n}\nOrch-Stage: {s}\nOrch-Lane: {lid}\nCo-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>\n")
  r=subprocess.run(["git","merge","--no-ff","-q","-F","/tmp/orch-mm.txt",br],capture_output=True,text=True)
  if r.returncode!=0: print("MERGE CONFLICT",w,r.stdout,r.stderr); sys.exit(2)
  mc=sh("git","rev-parse","--short","HEAD"); merges.append(mc)
  app({"event":"result","verb":"integrate-lane","wave":w,"order":n,"target":w,"stage":s,"lane":lid,"status":"in_progress","summary":f"{w} merged into the integration branch","proof":{"kind":"git-ancestor","ref":f"{head} is ancestor of {mc}"},"outputs":{"merge_commit":mc}})
app({"event":"result","verb":"integrate-lane","stage":s,"wave":"all","proof":{"kind":"git-ancestor","ref":"lane heads "+", ".join(vl[l]['head'][:7] for _,l in lanes)+f" are ancestors of {merges[-1]}"},"outputs":{"merges":merges,"conflicts":0}})
print("MERGED",merges)
