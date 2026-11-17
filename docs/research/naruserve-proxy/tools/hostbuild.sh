#!/bin/sh
# usage: hostbuild.sh <dir> — full Maven-mode build, prints exit and JUnit totals
cd "$1" || exit 2
./gradlew -PtinyHttpdFromMaven=true build -q > /tmp/orch-build-$(basename "$1").log 2>&1; ec=$?
python3 - <<'PY'
import glob,xml.etree.ElementTree as E
t=f=e=s=0
for p in glob.glob('**/build/test-results/**/*.xml',recursive=True):
  r=E.parse(p).getroot();t+=int(r.get('tests',0));f+=int(r.get('failures',0));e+=int(r.get('errors',0));s+=int(r.get('skipped',0))
print(f"tests={t} failures={f} errors={e} skipped={s}")
PY
echo "$1 exit=$ec"
