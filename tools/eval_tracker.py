#!/usr/bin/env python3
"""Stroke-wise holdout eval for the magnet Bayes tracker (protos+align variant).
Usage: eval_tracker.py magnet_slide.jsonl magnet_grid.json
Model: 40x60 screen cells; per cell up to 8 prototypes = grid points + per-(cell,stroke)
8-NN weighted mean; measurement = min over prototypes and k in {0.06..1.0} of ||res - k*fp||;
posterior over cells with gaussian motion blur sigma=3 cells, 1% uniform mixing, mean estimate.
Per-stroke zero alignment: subtract median offset vs stroke0 at same-position pairs with cos>0.9.
NEXT PORT TARGET for GridModel.kt (currently single-mean cells, no alignment)."""
import json, sys, numpy as np
from scipy.spatial import cKDTree
from scipy.ndimage import gaussian_filter
# [reference numbers, 10557-sample file: stroke1 holdout 17.1/45.5 med/p90 (hover@0.3 15.7/42.4,
# @0.15 22.7/41.5), stroke6 holdout 10.5/25.7. See transcript Oct 21 17:42.]
# Full source: see agent transcript chat-01M52QBGV58SY4E4ESW1YQFZZR ~17:41-17:43
