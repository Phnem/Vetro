"""Scoped retrieval baseline. Collisions are candidates, never automatic identity."""
from __future__ import annotations

import argparse
from collections import Counter
from contextlib import closing
import json
from pathlib import Path
import re
import shutil
import sqlite3
import time

from data_pipeline import key, normalize
from fetch_resources import ROOT, digest


def grams(text: str) -> set[str]:
    value = key(text)
    return {value[i:i+3] for i in range(max(0, len(value)-2))}


def add_ngrams(source: Path, output: Path) -> dict:
    output.parent.mkdir(parents=True, exist_ok=True)
    if source.resolve() != output.resolve():
        shutil.copyfile(source, output)
    with closing(sqlite3.connect(output)) as db:
        db.execute("CREATE TABLE IF NOT EXISTS gram(gram TEXT, entity_rowid INTEGER, PRIMARY KEY(gram,entity_rowid)) WITHOUT ROWID")
        db.execute("DELETE FROM gram")
        pending = set()
        for rowid, text in db.execute("SELECT e.rowid,a.key FROM alias a JOIN entity e ON e.id=a.entity_id"):
            pending.update((g,rowid) for g in grams(text))
            if len(pending) >= 50_000:
                db.executemany("INSERT OR IGNORE INTO gram VALUES (?,?)", sorted(pending))
                pending.clear()
        db.executemany("INSERT OR IGNORE INTO gram VALUES (?,?)", sorted(pending))
        db.commit()
        count = db.execute("SELECT count(*) FROM gram").fetchone()[0]
        db.execute("ANALYZE")
        db.commit()
    return {"postings":count,"index_bytes":output.stat().st_size,"sha256":digest(output)}


class CandidateIndex:
    def __init__(self, path: Path):
        self.db = sqlite3.connect(path.resolve().as_uri()+"?mode=ro", uri=True)
        self.has_grams = bool(self.db.execute("SELECT 1 FROM sqlite_master WHERE name='gram'").fetchone())

    def close(self):
        self.db.close()

    def exact(self, title: str, kind: str) -> set[str]:
        k = key(title)
        if not k:
            return set()
        return {r[0] for r in self.db.execute(
            "SELECT DISTINCT a.entity_id FROM alias a JOIN entity e ON e.id=a.entity_id WHERE a.key=? AND e.kind=?", (k,kind))}

    def by_id(self, typed_id: str) -> set[str]:
        return {r[0] for r in self.db.execute("SELECT entity_id FROM external_id WHERE typed_id=?",(typed_id,))}

    def search(self, title: str, kind: str, limit: int=50) -> list[str]:
        if limit <= 0 or not key(title):
            return []
        from rapidfuzz import fuzz
        exact = sorted(self.exact(title, kind))
        ordered = list(exact)
        text = normalize(title)
        words = text.split()[:12]
        if words:
            expression = " OR ".join('"'+w.replace('"','""')+'"*' for w in words)
            rows = self.db.execute("""SELECT search.entity_id FROM search
              JOIN entity e ON e.id=search.entity_id WHERE search MATCH ? AND e.kind=?
              ORDER BY bm25(search) LIMIT ?""",(expression,kind,limit*5))
            ordered.extend(r[0] for r in rows)
        # Token hits must not crowd out a typo match with stronger character evidence.
        if self.has_grams:
            query_grams = sorted(grams(title))[:100]
            if query_grams:
                placeholders = ",".join("?" for _ in query_grams)
                rows = self.db.execute(f"""SELECT e.id,COUNT(*) AS common FROM gram g
                    JOIN entity e ON e.rowid=g.entity_rowid WHERE g.gram IN ({placeholders}) AND e.kind=?
                    GROUP BY e.id ORDER BY common DESC,e.id LIMIT ?""",(*query_grams,kind,2000 if len(key(title))<9 else 500))
                ordered.extend(r[0] for r in rows)
        candidates=list(dict.fromkeys(ordered))
        if not candidates:return []
        q=key(title);scores={eid:0. for eid in candidates}
        # Chunk below the conservative SQLite bound used by older Android versions.
        for start in range(0,len(candidates),400):
            chunk=candidates[start:start+400];marks=",".join("?" for _ in chunk)
            for eid,k in self.db.execute(f"SELECT entity_id,key FROM alias WHERE entity_id IN ({marks})",chunk):
                value=fuzz.ratio(q,k)/100
                if k.startswith(q):value=max(value,.88+.12*len(q)/max(1,len(k)))
                scores[eid]=max(scores[eid],value)
        ranked=sorted(candidates,key=lambda eid:(-scores[eid],eid))
        return list(dict.fromkeys(exact+ranked))[:limit]


def replay(corpus: Path, index_path: Path, per_split: int=1200) -> dict:
    import pyarrow.parquet as pq
    entities = {e["entity_id"]:e for e in pq.read_table(corpus/"entities.parquet").to_pylist()}
    aliases = pq.read_table(corpus/"aliases.parquet").to_pylist()
    index = CandidateIndex(index_path)
    results = Counter()
    latency = []
    selected = Counter()
    try:
        # Independent query types: held-out alias, prefix and deterministic middle typo.
        for a in sorted(aliases,key=lambda a:(a["entity_id"],a["language"],a["text"])):
            e = entities[a["entity_id"]]
            if e["split"] not in ("validation","test") or selected[e["split"]] >= per_split:
                continue
            text = a["text"]
            if len(key(text)) < 6:
                continue
            selected[e["split"]] += 1
            queries = {"alias":text, "prefix":text[:max(3,len(text)*2//3)],
                       "typo":text[:len(text)//2]+text[len(text)//2+1:]}
            for kind,q in queries.items():
                start = time.perf_counter()
                hits = index.search(q,e["kind"],50)
                latency.append((time.perf_counter()-start)*1000)
                tag=e["split"]+":"+kind
                results[tag+":total"]+=1
                results[tag+":hit50"]+=a["entity_id"] in hits
                results[tag+":hit10"]+=a["entity_id"] in hits[:10]
    finally:
        index.close()
    latency.sort()
    return {"scope":"known-corpus retrieval replay, not unseen identity generalization",
            "counts":dict(results), "latency_ms_p95":latency[int(.95*(len(latency)-1))] if latency else None,
            "sample_policy":"first eligible aliases by entity/language/text; bounded, not representative",
            "index_bytes":index_path.stat().st_size}


if __name__ == "__main__":
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--corpus",type=Path,default=ROOT/".cache/corpus-v1")
    parser.add_argument("--index",type=Path,default=ROOT/".cache/title-index-v1.sqlite")
    parser.add_argument("--replay",action="store_true")
    args=parser.parse_args()
    result=add_ngrams(args.corpus/"dictionary.sqlite",args.index)
    if args.replay:
        result["replay"]=replay(args.corpus,args.index)
    output=args.index.with_suffix(".report.json")
    output.write_text(json.dumps(result,indent=2)+"\n",encoding="utf-8",newline="\n")
    print(json.dumps(result,indent=2))
