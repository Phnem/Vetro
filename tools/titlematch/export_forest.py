"""Portable numeric LightGBM forest, no native ML dependency required on Android."""
from __future__ import annotations

import math
from pathlib import Path
import struct

import numpy as np

MAGIC=b"VTMF0001"
NODE=struct.Struct("<hhdhhd")  # feature, reserved, threshold, left, right, leaf


def export_forest(booster,path: Path):
    description=booster.dump_model()
    if description["objective"]!="binary sigmoid:1":raise ValueError("Unsupported LightGBM objective")
    trees=[]
    for tree in description["tree_info"]:
        nodes=[]
        def flatten(node):
            i=len(nodes);nodes.append(None)
            if "leaf_value" in node:
                nodes[i]=(-1,0,0.,0,0,float(node["leaf_value"]))
            else:
                if node["decision_type"]!="<=":raise ValueError("Categorical/unsupported split")
                left=flatten(node["left_child"]);right=flatten(node["right_child"])
                nodes[i]=(node["split_feature"],0,float(node["threshold"]),left,right,0.)
            return i
        flatten(tree["tree_structure"])
        if len(nodes)>32767:raise ValueError("Tree exceeds portable node bound")
        trees.append(nodes)
    with path.open("wb") as stream:
        stream.write(MAGIC);stream.write(struct.pack("<ii",booster.num_feature(),len(trees)))
        for nodes in trees:
            stream.write(struct.pack("<i",len(nodes)))
            for node in nodes:stream.write(NODE.pack(*node))
    return {"trees":len(trees),"nodes":sum(map(len,trees)),"bytes":path.stat().st_size,
            "format":"VTMF0001, little endian, finite numeric <= splits, sigmoid:1"}


def load_forest(path: Path):
    with path.open("rb") as stream:
        if stream.read(8)!=MAGIC:raise ValueError("Invalid forest magic")
        features,n=struct.unpack("<ii",stream.read(8));trees=[]
        if not 1<=features<=128 or not 1<=n<=5000:raise ValueError("Invalid forest dimensions")
        for _ in range(n):
            size=struct.unpack("<i",stream.read(4))[0]
            if not 1<=size<=32767:raise ValueError("Invalid tree size")
            nodes=[NODE.unpack(stream.read(NODE.size)) for _ in range(size)]
            for f,_,threshold,left,right,leaf in nodes:
                if not math.isfinite(threshold) or not math.isfinite(leaf):raise ValueError("Non-finite tree")
                if f!=-1 and not(0<=f<features and 0<=left<size and 0<=right<size):raise ValueError("Invalid tree edge")
            trees.append(nodes)
        if stream.read(1):raise ValueError("Trailing forest bytes")
    return features,trees


def portable_predict(model,values):
    features,trees=model
    values=np.asarray(values)
    if values.ndim!=2 or values.shape[1]!=features or not np.isfinite(values).all():raise ValueError("Invalid model features")
    result=[]
    for row in values:
        total=0.
        for nodes in trees:
            index=0
            for _ in range(len(nodes)):
                feature,_,threshold,left,right,leaf=nodes[index]
                if feature==-1:
                    total+=leaf;break
                index=left if float(row[feature])<=threshold else right
            else:raise ValueError("Cyclic tree")
        result.append(1/(1+math.exp(-max(-700,min(700,total)))))
    return np.asarray(result)
