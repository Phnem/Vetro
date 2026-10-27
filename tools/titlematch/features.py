"""Versioned symmetric title features; IDs and entity membership are never inputs."""
from __future__ import annotations

import re
import unicodedata

import numpy as np
from rapidfuzz import fuzz
from rapidfuzz.distance import JaroWinkler, Levenshtein

from data_pipeline import key, normalize

VERSION = "pair-features-v1"
MAX_BYTES = 192
NAMES = ["levenshtein", "indel", "jaro_winkler", "token_jaccard", "token_subset",
         "substring_ratio", "length_ratio", "prefix_ratio", "suffix_ratio",
         "bigram_jaccard", "trigram_jaccard", "numeric_agreement", "numeric_conflict",
         "same_script", "normalized_equal", "token_count_ratio", "same_kind",
         "both_years", "year_agreement", "year_distance", "short_title", "truncated"]


def _jaccard(a, b):
    return len(a & b)/len(a | b) if a or b else 0.0


def script(text):
    counts = {"LATIN":0,"CYRILLIC":0,"CJK":0,"OTHER":0}
    for c in text:
        if not c.isalpha():
            continue
        name=unicodedata.name(c,"")
        s="LATIN" if "LATIN" in name else "CYRILLIC" if "CYRILLIC" in name else "CJK" if any(t in name for t in ("CJK","HIRAGANA","KATAKANA")) else "OTHER"
        counts[s]+=1
    return max(counts,key=counts.get) if any(counts.values()) else "NONE"


def encode(text: str) -> np.ndarray:
    raw = normalize(text).encode("utf-8")
    if len(raw) > MAX_BYTES:
        raw = raw[:MAX_BYTES//2]+raw[-MAX_BYTES//2:]
    result=np.zeros(MAX_BYTES,dtype=np.int32)
    result[:len(raw)]=np.frombuffer(raw,dtype=np.uint8).astype(np.int32)+1
    return result


def pair_features(a: str,b: str,kind_a: str="ANIME",kind_b: str|None=None,year_a=None,year_b=None) -> np.ndarray:
    x,y=normalize(a),normalize(b)
    kx,ky=key(a),key(b)
    ta,tb=set(x.split()),set(y.split())
    long=max(len(kx),len(ky),1)
    prefix=next((i for i,(ca,cb) in enumerate(zip(kx,ky)) if ca!=cb),min(len(kx),len(ky)))
    suffix=next((i for i,(ca,cb) in enumerate(zip(kx[::-1],ky[::-1])) if ca!=cb),min(len(kx),len(ky)))
    na,nb=set(re.findall(r"\d+",x)),set(re.findall(r"\d+",y))
    both=year_a is not None and year_b is not None and year_a>0 and year_b>0
    ng=lambda s,n:{s[i:i+n] for i in range(max(0,len(s)-n+1))}
    return np.asarray([
        Levenshtein.normalized_similarity(x,y) if x and y else 0,
        fuzz.ratio(x,y)/100 if x and y else 0,
        JaroWinkler.normalized_similarity(x,y) if x and y else 0,
        _jaccard(ta,tb),float(bool(ta and tb) and (ta<=tb or tb<=ta)),
        min(len(kx),len(ky))/long if kx and ky and (kx in ky or ky in kx) else 0,
        min(len(kx),len(ky))/long,prefix/long,suffix/long,
        _jaccard(ng(kx,2),ng(ky,2)),_jaccard(ng(kx,3),ng(ky,3)),
        float(na==nb),float(bool(na and nb) and na!=nb),float(script(x)==script(y)),
        float(bool(kx) and kx==ky),min(len(ta),len(tb))/max(len(ta),len(tb),1),
        float(kind_a==(kind_b or kind_a)),float(both),float(both and year_a==year_b),
        min(abs(year_a-year_b)/10,1) if both else 0,float(min(len(kx),len(ky))<4),
        float(max(len(x.encode()),len(y.encode()))>MAX_BYTES)],dtype=np.float32)


def legacy_score(a: str,b: str) -> float:
    """TitleMatcher's current subset/substring/Jaccard/Levenshtein decision structure."""
    x,y=normalize(a),normalize(b)
    if not x or not y:return 0
    if x==y:return 1
    ta,tb=set(x.split()),set(y.split())
    short,long=(ta,tb) if len(ta)<=len(tb) else (tb,ta)
    if short and (len(short)>=2 or sum(map(len,short))>=5) and short<=long:return .92
    s,l=(x,y) if len(x)<=len(y) else (y,x)
    if len(s)>=4 and s in l:return .87
    return max(_jaccard(ta,tb),Levenshtein.normalized_similarity(x,y))
