import os, re, unicodedata

# --- [설정 및 경로] ---
MY_IP = "192.168.0.2"
DATA_DIR = "/volume2/video/thumbnails"
CACHE_FILE = "/volume2/video/video_cache.json"
HLS_ROOT = "/dev/shm/videoplayer_hls"
CACHE_VERSION = "8.2" # 리팩토링 및 로직 보강으로 버전 상향

# 실제 NAS 경로 설정
PARENT_VIDEO_DIR = "/volume2/video/GDS3/GDRIVE/VIDEO"
FOREIGN_TV_DIR = os.path.join(PARENT_VIDEO_DIR, "외국TV")
KOREAN_TV_DIR = os.path.join(PARENT_VIDEO_DIR, "국내TV")
MOVIES_ROOT_DIR = os.path.join(PARENT_VIDEO_DIR, "영화")
ANI_DIR = os.path.join(PARENT_VIDEO_DIR, "일본 애니메이션")
AIR_DIR = os.path.join(PARENT_VIDEO_DIR, "방송중")

EXCLUDE_FOLDERS = ["성인", "19금", "Adult", "@eaDir", "#recycle"]

# --- [정규식 상수] ---
REGEX_EXT = re.compile(r'\.[a-zA-Z0-9]{2,4}$')
REGEX_HANGUL_ALPHA = re.compile(r'([가-힣])([a-zA-Z0-9])')
REGEX_ALPHA_HANGUL = re.compile(r'([a-zA-Z0-9])([가-힣])')
REGEX_START_NUM = re.compile(r'^\d+[.\s_-]+')
REGEX_START_PREFIX = re.compile(r'^[a-zA-Z]\d+[.\s_-]+')
REGEX_BRACKET_NUM = re.compile(r'^\[\d+\]\s*')
REGEX_YEAR = re.compile(r'\((19|20)\d{2}\)|(?<!\d)(19|20)\d{2}(?!\d)')
REGEX_BRACKETS = re.compile(r'\[.*?\]|\(.*?\)')
REGEX_TAGS = re.compile(r'(?i)[.\s_](?:더빙|자막|무삭제|\d{3,4}p|WEB-DL|WEBRip|Bluray|HDRip|BDRip|DVDRip|H\.?26[45]|x26[45]|HEVC|AAC|DTS|AC3|DDP|Dual|Atmos|REPACK|10bit|REMUX|OVA|OAD|ONA|TV판|극장판|FLAC|xvid|DivX|MKV|MP4|AVI|속편|1부|2부|파트|완결|상|하).*')
REGEX_SPECIAL_CHARS = re.compile(r'[._\-::!?【】『』「」"\'#@*※]')
REGEX_SPACES = re.compile(r'\s+')
REGEX_EP_SUFFIX = re.compile(r'(?i)[.\s_](?:S\d+E\d+|S\d+|E\d+|\d+\s*(?:화|회|기)|Season\s*\d+|Part\s*\d+).*')

# --- [공통 유틸리티] ---
def nfc(text): return unicodedata.normalize('NFC', text) if text else ""
def nfd(text): return unicodedata.normalize('NFD', text) if text else ""

def get_real_path(path):
    if not path: return path
    if os.path.exists(path): return path
    p_nfc, p_nfd = nfc(path), nfd(path)
    if os.path.exists(p_nfc): return p_nfc
    if os.path.exists(p_nfd): return p_nfd
    parent = os.path.dirname(path)
    if os.path.exists(parent):
        try:
            tn = nfc(os.path.basename(path))
            for e in os.listdir(parent):
                if nfc(e) == tn: return os.path.join(parent, e)
        except: pass
    return path

def is_excluded(path):
    n = os.path.basename(path)
    return any(ex in n for ex in EXCLUDE_FOLDERS) or n.startswith('.')
