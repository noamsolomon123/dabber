import jiwer

from benchmark.normalize_he import normalize_he


def score(refs, hyps):
    refs_n = [normalize_he(r) for r in refs]
    hyps_n = [normalize_he(h) for h in hyps]
    wer = jiwer.wer(refs_n, hyps_n)
    cer = jiwer.cer(refs_n, hyps_n)
    return {"wer": float(wer), "cer": float(cer)}
