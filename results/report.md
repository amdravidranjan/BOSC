# BOSC detector — results

Model hash `373d97e902713b8f` · window=4 caller utterances · C=0.5 · tactic weight=0.3
Thresholds (picked on dev so genuine-call false alarms ≤2% PAUSE / ≤5% WARN): WARN=0.341, PAUSE=0.380

> **Read this first.** Every set below is synthetic (template-generated or LLM-generated). Do not headline these numbers. The headline must come from the human-written lockbox and real recordings.

## Main results (95% bootstrap CIs)

| Test set | Scam calls | Genuine | Recall @PAUSE | False alarm @PAUSE | Recall @WARN | False alarm @WARN | Median earliness (turns) | PAUSE before first OTP/pay/app ask | Window ROC-AUC |
|---|---|---|---|---|---|---|---|---|---|
| test_gen_heldout_phrasing | 300 | 300 | 98.0% [96.3%–99.3%] | 9.0% [5.7%–12.3%] | 98.0% [96.3%–99.3%] | 13.0% [9.3%–17.0%] | 1.0 | 89.6% | 0.958 |
| test_hf | 160 | 160 | 100.0% [100.0%–100.0%] | 1.2% [0.0%–3.1%] | 100.0% [100.0%–100.0%] | 1.9% [0.0%–4.4%] | 0.0 | n/a | 0.999 |

## Ablation (same threshold policy)

| System | Gen test recall @PAUSE | Gen test FA @PAUSE | HF test recall @PAUSE | HF test FA @PAUSE |
|---|---|---|---|---|
| A_rules_only | 45.7% | 0.0% | 0.0% | 0.0% |
| B_tfidf_lr_only | 97.3% | 9.3% | 100.0% | 1.2% |
| C_tfidf_lr_plus_tactics | 98.0% | 9.0% | 100.0% | 1.2% |

## Cross-source generalisation

- Trained on our Indian-context templates only → tested on HF (US-style) calls: recall @PAUSE 90.0%, false alarm 26.2%
- Trained on HF only → tested on our templates: recall @PAUSE 100.0%, false alarm 53.3%

## Leave-one-scam-type-out (recall on the unseen type @PAUSE)

| Held-out type | Recall @PAUSE | Recall @WARN |
|---|---|---|
| S10_family_emergency | 96.7% | 96.7% |
| S1_bank_kyc | 86.7% | 86.7% |
| S2_digital_arrest | 100.0% | 100.0% |
| S3_remote_refund | 100.0% | 100.0% |
| S4_utility | 100.0% | 100.0% |
| S5_sim_aadhaar | 96.7% | 96.7% |
| S6_card_loan | 100.0% | 100.0% |
| S7_task_investment | 100.0% | 100.0% |
| S8_prize_lottery | 100.0% | 100.0% |
| S9_tech_support | 100.0% | 100.0% |

## Per-type flag rate on gen test (scam types should be high, genuine low)

| Type | n | Flagged @PAUSE |
|---|---|---|
| G1_delivery_otp | 34 | 0.0% |
| G2_bank_emi_reminder | 34 | 0.0% |
| G3_bank_fraud_check | 34 | 0.0% |
| G4_police_verification | 33 | 0.0% |
| G5_family_money | 33 | 9.1% |
| G6_telemarketing | 33 | 0.0% |
| G7_everyday | 33 | 0.0% |
| G8_scam_aware | 33 | 0.0% |
| G9_support_genuine | 33 | 72.7% |
| S10_family_emergency | 30 | 96.7% |
| S1_bank_kyc | 30 | 86.7% |
| S2_digital_arrest | 30 | 100.0% |
| S3_remote_refund | 30 | 100.0% |
| S4_utility | 30 | 100.0% |
| S5_sim_aadhaar | 30 | 96.7% |
| S6_card_loan | 30 | 100.0% |
| S7_task_investment | 30 | 100.0% |
| S8_prize_lottery | 30 | 100.0% |
| S9_tech_support | 30 | 100.0% |
