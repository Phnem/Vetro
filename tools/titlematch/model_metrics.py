"""Calibration and policy selection use validation only. No test access here."""
from __future__ import annotations

import numpy as np
from scipy.optimize import minimize
from scipy.special import expit,logit
from scipy.stats import beta
from sklearn.metrics import average_precision_score,log_loss,roc_auc_score


def calibrate(scores,labels):
    raw=logit(np.clip(scores,1e-7,1-1e-7))
    def objective(params):
        z=params[0]*raw+params[1]
        return float(np.mean(np.logaddexp(0,z)-labels*z)+1e-5*(params[0]-1)**2)
    result=minimize(objective,[1.,0.],method="L-BFGS-B",bounds=[(.05,10),(-20,20)])
    if not result.success:raise ValueError("Probability calibration did not converge")
    return {"method":"platt","slope":float(result.x[0]),"intercept":float(result.x[1]),
            "population":"generated validation candidate pairs, not deployment prevalence"}


def apply_calibration(scores,calibration):
    return expit(calibration["slope"]*logit(np.clip(scores,1e-7,1-1e-7))+calibration["intercept"])


def precision_lower(tp,fp):
    return float(beta.ppf(.05,tp,fp+1)) if tp else 0.


def select_threshold(scores,labels):
    order=np.argsort(-scores,kind="stable")
    y=np.asarray(labels)[order]
    p=np.asarray(scores)[order]
    tp=np.cumsum(y==1);fp=np.cumsum(y==0)
    ends=np.r_[np.flatnonzero(p[:-1]!=p[1:]),len(p)-1]
    # Pair-level interval is descriptive; group and real-candidate checks remain separate gates.
    lower=beta.ppf(.05,np.maximum(tp[ends],1),fp[ends]+1)
    # Keep a validation safety margin above the final 99.9% test target.
    eligible=ends[(tp[ends]>=2995)&(lower>=.9995)]
    if not len(eligible):
        return {"threshold":1.000001,"validated_auto_threshold":False,
                "reason":"No validation threshold meets 99.95% lower precision bound with >=2995 TPs"}
    i=int(eligible[-1])
    return {"threshold":float(p[i]),"validated_auto_threshold":True,"validation_tp":int(tp[i]),
            "validation_fp":int(fp[i]),"validation_precision_lower95":precision_lower(int(tp[i]),int(fp[i])),
            "selection_lower_bound_target":.9995,"final_lower_bound_target":.999,
            "interval_scope":"pair-level, generated candidate distribution"}


def metrics(scores,labels,threshold):
    scores=np.asarray(scores);labels=np.asarray(labels)
    accepted=scores>=threshold
    tp=int(np.sum(accepted&(labels==1)));fp=int(np.sum(accepted&(labels==0)))
    fn=int(np.sum(~accepted&(labels==1)));tn=int(np.sum(~accepted&(labels==0)))
    ece=0.
    for lo in np.linspace(0,.9,10):
        mask=(scores>=lo)&(scores<lo+.1+1e-10)
        if mask.any():ece+=float(mask.mean()*abs(scores[mask].mean()-labels[mask].mean()))
    return {"n":len(labels),"tp":tp,"fp":fp,"fn":fn,"tn":tn,
            "precision":tp/(tp+fp) if tp+fp else None,"precision_lower95":precision_lower(tp,fp),
            "recall":tp/(tp+fn) if tp+fn else None,"accepted_fraction":float(accepted.mean()),
            "auc":float(roc_auc_score(labels,scores)) if len(set(labels))==2 else None,
            "average_precision":float(average_precision_score(labels,scores)) if np.any(labels) else None,
            "log_loss":float(log_loss(labels,np.clip(scores,1e-7,1-1e-7),labels=[0,1])),
            "brier":float(np.mean((scores-labels)**2)),"ece10":ece}
