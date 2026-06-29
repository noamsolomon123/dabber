from benchmark.metrics import score


def test_perfect_match_is_zero():
    r = score(["שלום עולם"], ["שָׁלוֹם, עולם!"])
    assert r["wer"] == 0.0
    assert r["cer"] == 0.0


def test_one_word_substitution():
    # 2 ref words, 1 wrong -> WER 0.5
    r = score(["שלום עולם"], ["שלום חבר"])
    assert abs(r["wer"] - 0.5) < 1e-9


def test_aggregates_over_corpus():
    r = score(["א ב", "ג ד"], ["א ב", "ג ה"])  # 4 words, 1 error
    assert abs(r["wer"] - 0.25) < 1e-9
