# usage: snapshot.py <stage>
import sys,json; sys.path.insert(0,__file__.rsplit('/',1)[0]); from common import *
s=int(sys.argv[1]); SD=f"{WR}waves/stage-{s}"; os.makedirs(SD,exist_ok=True)
l=json.loads(sh("speckiwi","list","--format","full","--json")); su=json.loads(sh("speckiwi","summary","--target","0.12.0","--json"))
json.dump({"transport":"speckiwi-list-json","target":"0.12.0","list":l,"summary":su},open(f"{SD}/readiness-snapshot.json","w"))
# dependency report
import glob,re
txt="".join(open(f).read() for f in glob.glob('docs/spec/*.srs.md'))
own={}
for w,n in nodes().items():
  for a in n['acs']: own[a.split(' ')[0]]=w
st={r['id']:r.get('status') for r in l.get('records',[])}
for w in sys.argv[2].split(','):
  for q in reqs_of(nodes()[w]):
    m=re.search(r"^#+ "+re.escape(q)+r"\b",txt,re.M); i=m.start(); j=txt.find("\n### ",i+5); blk=txt[i:j if j>0 else None]
    deps=re.findall(r"\| Requirement \| ([A-Z]+-[A-Z0-9]+-\d+) \| depends_on",blk)
    print(w,q,[(d,own.get(d,'-'),st.get(d)) for d in deps])
