import re
import server

def new_clean(title):
    cleaned, yr = server.clean_title_complex(title)
    cleaned = re.sub(r'(?i)\s*(?:S\d+E\d+|S\d+|Season\s*\d+|시즌\s*\d+|\d+\s*[기화회부장쿨편]|Part\s*\d+)(?:\s*완)?\s*$', '', cleaned)
    return cleaned, yr

for n in ["(더빙) 원피스 9기", "원피스 9기완", "더빙 원피스 1기", "자막 원피스 32기", "원피스 시즌 1"]:
    print(f"'{n}' -> {new_clean(n)}")
