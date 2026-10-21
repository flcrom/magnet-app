#!/usr/bin/env python3
"""MLP challenger eval (did NOT beat the tracker clearly; not deployed Oct 21).
MLP 128x128 on [unit-direction(3), log-magnitude/5(1)], 6x strength augmentation
k~U(0.06,1.0) + 0.5uT noise for hover invariance. sklearn MLPRegressor, adam,
alpha 1e-3, lr 2e-3, early stopping. Reference numbers: stroke1 14.6/37.9,
stroke6 12.6/22.7 med/p90 (no temporal filter). Revisit at 15-20k samples.
Full source: agent transcript chat-01M52QBGV58SY4E4ESW1YQFZZR ~17:43."""
