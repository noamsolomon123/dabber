from benchmark.normalize_he import normalize_he


def test_strips_niqqud():
    assert normalize_he("שָׁלוֹם") == "שלום"


def test_strips_punctuation_and_collapses_space():
    assert normalize_he("שלום,  עולם!") == "שלום עולם"


def test_keeps_final_letters():
    # final mem must stay final mem, not fold to regular mem
    assert normalize_he("עולם") == "עולם"


def test_normalizes_geresh_gershayim_to_ascii():
    assert normalize_he("צה״ל ד׳") == "צה\"ל ד'"


def test_lowercases_latin_for_mixed():
    assert normalize_he("Hello שלום") == "hello שלום"


def test_strips_leading_trailing_space():
    assert normalize_he("  היי  ") == "היי"
