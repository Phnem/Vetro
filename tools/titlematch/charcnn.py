"""Small shared byte-CNN, trained in PyTorch with an explicit TensorFlow export mirror."""
from __future__ import annotations

import torch
from torch import nn

from features import NAMES


class CharCNN(nn.Module):
    def __init__(self):
        super().__init__()
        self.embedding=nn.Embedding(257,16,padding_idx=0)
        self.conv3=nn.Conv1d(16,32,3,padding=1)
        self.conv5=nn.Conv1d(16,32,5,padding=2)
        self.projection=nn.Linear(64,32)
        self.hidden=nn.Linear(64+len(NAMES),32)
        self.output=nn.Linear(32,1)

    def encode(self,tokens):
        x=self.embedding(tokens).transpose(1,2)
        a=torch.relu(self.conv3(x)).amax(dim=2)
        b=torch.relu(self.conv5(x)).amax(dim=2)
        return torch.relu(self.projection(torch.cat([a,b],dim=1)))

    def forward(self,a,b,features):
        x,y=self.encode(a),self.encode(b)
        comparison=torch.cat([torch.abs(x-y),x*y,features],dim=1)
        return self.output(torch.relu(self.hidden(comparison))).squeeze(1)
