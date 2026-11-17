# Host helper scripts — run 2026-11-14.NaruServe.naruserve-proxy

Mechanical journal/commit helpers the host uses per stage (they only call `speckiwi orchestrate …` and git with explicit pathspecs). Order per stage:
`snapshot.py S waves` → `stageprep.py S waves "<reason>"` → `dispatch.py S "<verdict>"` → workers → `harvest.py S` → host build in each worktree → `integrate.py S '<json>'` → `replay_intent.py S "<notes>"` + MCP `orchestrate_replay_apply` per lane → `release.py S '<counts>'` → validate + `sync_index` + duplication audit + commit → loop P (`round.py`) → issues + `orchestrate wave close` → promotion (MCP) → `closewave.py` → `card.py`.
