import re
import unicodedata

# Hebrew niqqud (combining points) + cantillation marks (U+0591–U+05C7).
_NIQQUD = re.compile(r"[֑-ׇֽֿׁׂׅׄ]")
_GERESH = "׳"      # ׳ -> '
_GERSHAYIM = "״"   # ״ -> "
# keep: ascii letters/digits, Hebrew letters (incl. final forms), apostrophe, quote, space
_KEEP = re.compile(r"[^a-z0-9א-ת'\"\s]")


def normalize_he(text: str) -> str:
    text = unicodedata.normalize("NFKD", text)
    text = _NIQQUD.sub("", text)
    text = text.replace(_GERESH, "'").replace(_GERSHAYIM, '"')
    text = text.lower()
    text = _KEEP.sub(" ", text)
    text = re.sub(r"\s+", " ", text).strip()
    return text
