"""Generate split-contained natural/synthetic pairs; preserve label provenance."""
from __future__ import annotations

import argparse
from collections import Counter,defaultdict
import itertools
import json
from pathlib import Path
import random

import numpy as np
import pyarrow as pa
import pyarrow.parquet as pq

from data_pipeline import key
from features import MAX_BYTES,NAMES,VERSION,encode,pair_features
from fetch_resources import ROOT,digest


def typo(text: str,rng: random.Random) -> str|None:
    choices=[i for i,c in enumerate(text) if c.isalpha()]
    if len(choices)<4:return None
    position=rng.choice(choices)
    if position+1<len(text) and text[position+1].isalpha() and rng.random()<.5:
        result=text[:position]+text[position+1]+text[position]+text[position+2:]
    else:
        result=text[:position]+text[position+1:]
    return result if result!=text else None


def prepare(corpus: Path,output: Path,seed=20261027) -> dict:
    rng=random.Random(seed)
    entities=pq.read_table(corpus/"entities.parquet").to_pylist()
    alias_rows=pq.read_table(corpus/"aliases.parquet").to_pylist()
    ids=pq.read_table(corpus/"external_ids.parquet").to_pylist()
    by_id={e["entity_id"]:e for e in entities}
    aliases=defaultdict(list);keys=defaultdict(set);external=defaultdict(set);key_groups=defaultdict(set)
    for a in alias_rows:
        aliases[a["entity_id"]].append(a)
        keys[a["entity_id"]].add(a["search_key"])
        key_groups[a["search_key"]].add(by_id[a["entity_id"]]["group_id"])
    for row in ids:external[row["entity_id"]].add(row["typed_id"])
    active=[e for e in entities if e["split"] in ("train","validation","test") and aliases[e["entity_id"]]]
    groups=defaultdict(list);scope=defaultdict(list)
    for e in active:
        groups[(e["split"],e["group_id"],e["kind"])].append(e["entity_id"])
        scope[(e["split"],e["kind"])].append(e["entity_id"])
    preferred={}
    for e in active:
        values=sorted(aliases[e["entity_id"]],key=lambda a:(a["language"]!="en",len(a["text"]),a["text"]))
        preferred[e["entity_id"]]=next((a["text"] for a in values if len(a["search_key"])>=4),values[0]["text"])
    neighbours={}
    for members in scope.values():
        ordered=sorted(members,key=lambda eid:(key(preferred[eid]),eid))
        for i,eid in enumerate(ordered):neighbours[eid]=ordered[max(0,i-3):i]+ordered[i+1:i+4]
    rows_by_split=defaultdict(list);dedupe=set();skipped=Counter()

    def add(a_id,b_id,a,b,label,stratum):
        ea,eb=by_id[a_id],by_id[b_id]
        if ea["split"]!=eb["split"]:raise AssertionError("Cross-split pair")
        identity=(ea["split"],*sorted((a,b)),label,ea["kind"],eb["kind"])
        if identity in dedupe:return
        if label==0:
            if ea["qid"]==eb["qid"] and ea["kind"]==eb["kind"]:raise AssertionError("Contradictory negative")
            if ea["kind"]==eb["kind"] and (external[a_id]&external[b_id] or keys[a_id]&keys[b_id]):
                skipped["ambiguous_negative"]+=1;return
        dedupe.add(identity)
        ya,yb=ea["year"],eb["year"]
        # Positive release-date differences and missing metadata are realistic, not contradictions.
        if label and ya and rng.random()<.2:yb=ya+rng.choice((-1,1))
        if rng.random()<.35:ya=None
        if rng.random()<.35:yb=None
        rows_by_split[ea["split"]].append({"a":a,"b":b,"label":label,"stratum":stratum,
            "kind_a":ea["kind"],"kind_b":eb["kind"],"year_a":ya,"year_b":yb,
            "entity_a":a_id,"entity_b":b_id,"group_a":ea["group_id"],"group_b":eb["group_id"]})

    for e in sorted(active,key=lambda e:e["entity_id"]):
        eid=e["entity_id"]
        values=sorted({a["text"] for a in aliases[eid]})
        combinations=list(itertools.combinations(values[:24],2))
        rng.shuffle(combinations)
        for a,b in combinations[:8]:add(eid,eid,a,b,1,"natural_alias")
        bases=rng.sample(values,min(3,len(values)))
        for a in bases:
            for _ in range(2):
                b=typo(a,rng)
                if b and not(key_groups.get(key(b),set())-{e["group_id"]}):
                    add(eid,eid,a,b,1,"synthetic_typo")
                elif b:skipped["augmentation_alias_collision"]+=1
        for other in neighbours[eid]:
            add(eid,other,preferred[eid],preferred[other],0,"lexical_negative")
        peers=groups[(e["split"],e["group_id"],e["kind"])]
        for other in rng.sample(peers,min(4,len(peers))):
            if other!=eid:add(eid,other,preferred[eid],preferred[other],0,"franchise_negative")
        members=scope[(e["split"],e["kind"])]
        for _ in range(3):
            other=rng.choice(members)
            if other!=eid:add(eid,other,preferred[eid],preferred[other],0,"random_negative")
    output.mkdir(parents=True,exist_ok=True)
    texts=sorted({r[k] for rows in rows_by_split.values() for r in rows for k in ("a","b")})
    text_to_index={s:i for i,s in enumerate(texts)}
    tokens=np.stack([encode(t) for t in texts]).astype(np.uint16)
    np.save(output/"tokens.npy",tokens,allow_pickle=False)
    (output/"texts.json").write_text(json.dumps(texts,ensure_ascii=False),encoding="utf-8",newline="\n")
    report={"seed":seed,"source":"Wikidata CC0 only","corpus_sha256":digest(corpus/"corpus.json"),
            "feature_version":VERSION,"feature_names":NAMES,"max_utf8_bytes":MAX_BYTES,
            "unique_texts":len(texts),"skipped":dict(skipped),"splits":{}}
    split_groups={}
    for split,rows in sorted(rows_by_split.items()):
        rng.shuffle(rows)
        pq.write_table(pa.Table.from_pylist(rows),output/f"{split}.parquet",compression="zstd")
        features=np.empty((len(rows),len(NAMES)),dtype=np.float32)
        for i,r in enumerate(rows):
            features[i]=pair_features(r["a"],r["b"],r["kind_a"],r["kind_b"],r["year_a"],r["year_b"])
        indices=np.asarray([(text_to_index[r["a"]],text_to_index[r["b"]]) for r in rows],dtype=np.int32)
        np.savez_compressed(output/f"{split}.npz",features=features,indices=indices,
                            labels=np.asarray([r["label"] for r in rows],dtype=np.int8))
        split_groups[split]={r[k] for r in rows for k in ("group_a","group_b")}
        report["splits"][split]={"pairs":len(rows),"strata":dict(Counter(r["stratum"] for r in rows)),
                                 "groups":len(split_groups[split]),"sha256":digest(output/f"{split}.npz")}
        print(json.dumps({"split":split,**report["splits"][split]}),flush=True)
    for a,b in itertools.combinations(split_groups,2):
        if split_groups[a]&split_groups[b]:raise AssertionError("Group leakage")
    report["group_overlap"]=0
    (output/"pairs.json").write_text(json.dumps(report,indent=2)+"\n",encoding="utf-8",newline="\n")
    return report


if __name__=="__main__":
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--corpus",type=Path,default=ROOT/".cache/corpus-v1")
    parser.add_argument("--output",type=Path,default=ROOT/".cache/pairs-v1")
    args=parser.parse_args()
    print(json.dumps(prepare(args.corpus,args.output),indent=2))
