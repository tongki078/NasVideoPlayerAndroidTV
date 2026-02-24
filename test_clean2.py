import re
REGEX_EP_MARKER_STRICT = re.compile(
    r'(?i)(?:(?<=[\uac00-\ud7af\u3040-\u30ff\u4e00-\u9fff])|[.\s_-]|^)(?:第?\s*S(\d+)E(\d+)(?:[-~]E?\d+)?(?:[화회기부話장쿨편])?|第?\s*S(\d+)|第?\s*E(\d+)(?:[-~]\d+)?(?:[화회기부話장쿨편])?|(\d+)\s*(?:화|회|기|부|話|장|쿨|편)|Season\s*(\d+)|Episode\s*(\d+)|시즌\s*(\d+)|Part\s*(\d+))(?:\b|[.\s_-]|$)')

print(REGEX_EP_MARKER_STRICT.search("원피스 9기"))
print(REGEX_EP_MARKER_STRICT.search("원피스 9기 01화"))
print(REGEX_EP_MARKER_STRICT.search("더빙 원피스 9기 01화"))
print(REGEX_EP_MARKER_STRICT.search("(더빙) 원피스 9기"))
