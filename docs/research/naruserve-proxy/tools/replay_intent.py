import sys; sys.path.insert(0,__file__.rsplit('/',1)[0]); from common import *
s=int(sys.argv[1]); SD=f"{WR}waves/stage-{s}"; P=json.load(open(f"{SD}/prep.json"))
app({"event":"intent","verb":"replay-deferred-mutations","stage":s,"wave":"all","inputs_digest":"sha256:"+P['lock_digest'],"notes":sys.argv[2]})
