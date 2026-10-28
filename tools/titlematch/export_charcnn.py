"""Verified weight mirror: PyTorch -> TensorFlow ops -> CPU TFLite full INT8.

This avoids relying on a Linux-only conversion wheel on native Windows. Every
export compares PyTorch, float TFLite and quantized TFLite on the same vectors.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import time

import numpy as np
import torch

from charcnn import CharCNN
from features import MAX_BYTES,NAMES
from fetch_resources import ROOT,digest


def export(model: CharCNN, calibration: list[tuple], output: Path, verification: list[tuple]|None=None) -> dict:
    import tensorflow as tf
    tf.config.threading.set_inter_op_parallelism_threads(2)
    tf.config.threading.set_intra_op_parallelism_threads(4)
    model=model.cpu().eval()
    state={k:v.detach().cpu().numpy() for k,v in model.state_dict().items()}

    class Mirror(tf.Module):
        def __init__(self):
            super().__init__()
            for name,value in state.items():
                setattr(self,name.replace(".","_"),tf.Variable(value,trainable=False,name=name.replace(".","_")))

        def encode(self,tokens):
            x=tf.gather(self.embedding_weight,tokens)
            c3=tf.nn.relu(tf.nn.conv1d(x,tf.transpose(self.conv3_weight,[2,1,0]),stride=1,padding="SAME")+self.conv3_bias)
            c5=tf.nn.relu(tf.nn.conv1d(x,tf.transpose(self.conv5_weight,[2,1,0]),stride=1,padding="SAME")+self.conv5_bias)
            pooled=tf.concat([tf.reduce_max(c3,axis=1),tf.reduce_max(c5,axis=1)],axis=1)
            return tf.nn.relu(tf.matmul(pooled,tf.transpose(self.projection_weight))+self.projection_bias)

        @tf.function(input_signature=[tf.TensorSpec([1,MAX_BYTES],tf.int32,name="title_a"),
            tf.TensorSpec([1,MAX_BYTES],tf.int32,name="title_b"),tf.TensorSpec([1,len(NAMES)],tf.float32,name="features")])
        def serve(self,a,b,features):
            x,y=self.encode(a),self.encode(b)
            comparison=tf.concat([tf.abs(x-y),x*y,features],axis=1)
            h=tf.nn.relu(tf.matmul(comparison,tf.transpose(self.hidden_weight))+self.hidden_bias)
            return {"logit":tf.matmul(h,tf.transpose(self.output_weight))+self.output_bias}

    mirror=Mirror()
    concrete=mirror.serve.get_concrete_function()
    vectors=verification or calibration[:128]
    def reference(vector):
        a,b,f=vector
        with torch.no_grad():
            return float(torch.sigmoid(model(torch.from_numpy(a).long(),torch.from_numpy(b).long(),torch.from_numpy(f))).item())
    mirror_error=max(abs(reference(v)-float(tf.sigmoid(mirror.serve(*v)["logit"]).numpy().item())) for v in vectors)
    if mirror_error>1e-5:raise ValueError(f"PyTorch/TF mirror mismatch:{mirror_error}")
    output.mkdir(parents=True,exist_ok=True)
    converter=tf.lite.TFLiteConverter.from_concrete_functions([concrete],mirror)
    float_model=converter.convert()
    (output/"charcnn.float32.tflite").write_bytes(float_model)
    input_details=tf.lite.Interpreter(model_content=float_model).get_input_details()
    order=[]
    for item in input_details:
        position=next((index for name,index in {"title_a":0,"title_b":1,"features":2}.items() if name in item["name"]),None)
        if position is None:raise ValueError("Unexpected converter input:"+item["name"])
        order.append(position)
    converter=tf.lite.TFLiteConverter.from_concrete_functions([concrete],mirror)
    converter.optimizations=[tf.lite.Optimize.DEFAULT]
    converter.representative_dataset=lambda:([vector[i] for i in order] for vector in calibration)
    converter.target_spec.supported_ops=[tf.lite.OpsSet.TFLITE_BUILTINS_INT8]
    converter.inference_input_type=tf.int8
    converter.inference_output_type=tf.int8
    integer_model=converter.convert()
    path=output/"charcnn.int8.tflite"
    path.write_bytes(integer_model)

    def evaluate(content):
        interpreter=tf.lite.Interpreter(model_content=content,num_threads=2)
        interpreter.allocate_tensors()
        inputs=interpreter.get_input_details()
        outputs=interpreter.get_output_details()
        by_name={"title_a":0,"title_b":1,"features":2}
        errors=[];times=[];predictions=[]
        for vector in vectors:
            start=time.perf_counter()
            for item in inputs:
                name=next((n for n in by_name if n in item["name"]),None)
                if name is None:raise ValueError("Unrecognized TFLite input:"+item["name"])
                value=vector[by_name[name]]
                if item["dtype"]==np.int8:
                    scale,zero=item["quantization"]
                    value=np.clip(np.rint(value/scale)+zero,-128,127).astype(np.int8)
                interpreter.set_tensor(item["index"],value.astype(item["dtype"]))
            interpreter.invoke()
            item=outputs[0]
            value=float(interpreter.get_tensor(item["index"]).item())
            if item["dtype"]==np.int8:
                scale,zero=item["quantization"];value=(value-zero)*scale
            value=float(1/(1+np.exp(-np.clip(value,-80,80))))
            times.append((time.perf_counter()-start)*1000)
            errors.append(abs(value-reference(vector)));predictions.append(value)
        float_tensors=[i["name"] for i in interpreter.get_tensor_details() if i["dtype"]==np.float32]
        return {"max_probability_error":max(errors),"mean_probability_error":float(np.mean(errors)),
                "p95_ms":float(np.percentile(times[1:],95)),"float32_tensors":float_tensors,
                "inputs":[{"name":i["name"],"dtype":str(i["dtype"]),"shape":i["shape"].tolist(),"quantization":list(i["quantization"])} for i in inputs],
                "output_quantization":list(outputs[0]["quantization"])}

    fp=evaluate(float_model);int8=evaluate(integer_model)
    if fp["max_probability_error"]>1e-5:raise ValueError("Float TFLite parity failed")
    if int8["float32_tensors"]:raise ValueError("Unexpected float fallback in INT8 graph")
    report={"method":"Explicit shared PyTorch weight mirror -> TF ops -> TFLite",
        "parameters":sum(p.numel() for p in model.parameters()),"mirror_max_error":mirror_error,
        "float32":fp,"int8":int8,"int8_bytes":len(integer_model),"sha256":digest(path),
        "calibration_vectors":len(calibration),"verification_vectors":len(vectors),
        "integer_input_contract":"Two int32 byte-token index arrays, int8 features; int8 logit output (dequantize then sigmoid/calibration)",
        "device_benchmark":False}
    (output/"export-report.json").write_text(json.dumps(report,indent=2)+"\n",encoding="utf-8",newline="\n")
    return report


if __name__=="__main__":
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output",type=Path,default=ROOT/".cache/export-spike")
    args=parser.parse_args()
    torch.manual_seed(7)
    generator=np.random.default_rng(7)
    vectors=[(generator.integers(0,257,size=(1,MAX_BYTES),dtype=np.int32),
              generator.integers(0,257,size=(1,MAX_BYTES),dtype=np.int32),
              generator.random((1,len(NAMES)),dtype=np.float32)) for _ in range(96)]
    print(json.dumps(export(CharCNN(),vectors,args.output),indent=2))
