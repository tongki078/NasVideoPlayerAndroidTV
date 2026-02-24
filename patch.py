import re

with open('/Users/gommi/Downloads/NasVideoPlayerAndroidTV/server.py', 'r', encoding='utf-8') as f:
    content = f.read()

# Make REGEX_EP_MARKER_STRICT more permissive at the end
old_regex = r"REGEX_EP_MARKER_STRICT = re.compile(\n    r'(?i)(?:(?<=[\uac00-\ud7af\u3040-\u30ff\u4e00-\u9fff])|[.\s_-]|^)(?:第?\s*S(\d+)E(\d+)(?:[-~]E?\d+)?(?:[화회기부話장쿨편])?|第?\s*S(\d+)|第?\s*E(\d+)(?:[-~]\d+)?(?:[화회기부話장쿨편])?|(\d+)\s*(?:화|회|기|부|話|장|쿨|편)|Season\s*(\d+)|Episode\s*(\d+)|시즌\s*(\d+)|Part\s*(\d+))(?:\b|[.\s_-]|$)'\)"
new_regex = r"REGEX_EP_MARKER_STRICT = re.compile(\n    r'(?i)(?:(?<=[\uac00-\ud7af\u3040-\u30ff\u4e00-\u9fff])|[.\s_-]|^)(?:第?\s*S(\d+)E(\d+)(?:[-~]E?\d+)?(?:[화회기부話장쿨편])?|第?\s*S(\d+)|第?\s*E(\d+)(?:[-~]\d+)?(?:[화회기부話장쿨편])?|(\d+)\s*(?:화|회|기|부|話|장|쿨|편)|Season\s*(\d+)|Episode\s*(\d+)|시즌\s*(\d+)|Part\s*(\d+))(?:[.\s_-]*완)?(?:\b|[.\s_-]|$)'\)"
content = content.replace(old_regex, new_regex)

# Add an extra strip to be totally sure
old_clean = r"    cleaned = REGEX_SPACES.sub(' ', cleaned).strip()"
new_clean = r"    cleaned = REGEX_SPACES.sub(' ', cleaned).strip()" + "\n    cleaned = re.sub(r'(?i)\\s*(?:S\\d+E\\d+|S\\d+|Season\\s*\\d+|시즌\\s*\\d+|\\d+\\s*[기화회부장쿨편]|Part\\s*\\d+)(?:\\s*완)?\\s*$', '', cleaned).strip()"
content = content.replace(old_clean, new_clean)

# Also fix the GROUP BY logic if needed?
# Actually, the user says "이게 그룹되어 검색이 안되고 있어."
# I will make sure the UI has a button to refresh cleaned names.
ui_old = r"<button class=\"btn-danger\" style=\"background-color: #dc3545;\" onclick=\"triggerTask('/reset_all_tmdb_data')\">🚨 전체 TMDB 메타데이터 초기화 (19금 오류 해결)</button>"
ui_new = ui_old + "\n<button class=\"btn-info\" style=\"background-color: #17a2b8;\" onclick=\"triggerTask('/refresh_cleaned_names')\">♻️ 그룹화 오류 수정 (이름 재정제)</button>"
content = content.replace(ui_old, ui_new)

with open('/Users/gommi/Downloads/NasVideoPlayerAndroidTV/server.py', 'w', encoding='utf-8') as f:
    f.write(content)

print("Patched successfully")
