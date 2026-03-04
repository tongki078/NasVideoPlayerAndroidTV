import os, subprocess, hashlib, urllib.parse, unicodedata, threading, time, json, re, sys, traceback, shutil, requests, \
    random, mimetypes, sqlite3, gzip
from flask import Flask, jsonify, send_from_directory, request, Response, redirect, send_file, make_response
from flask_cors import CORS
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime
from collections import deque
from io import BytesIO

app = Flask(__name__)
CORS(app)

# MIME 타입 추가 등록 (강제 적용)
mimetypes.add_type('video/x-matroska', '.mkv')
mimetypes.add_type('video/mp2t', '.ts')
mimetypes.add_type('video/mp2t', '.tp')


# --- [최적화: Gzip 압축 함수 추가] ---
def gzip_response(data):
    content = gzip.compress(json.dumps(data, ensure_ascii=False).encode('utf-8'))
    response = make_response(content)
    response.headers['Content-Encoding'] = 'gzip'
    response.headers['Content-Type'] = 'application/json'
    response.headers['Content-Length'] = len(content)
    return response


# --- [1. 설정 및 경로] ---
MY_IP = "ggommi.duckdns.org"
# MY_IP = "192.168.0.2"
DATA_DIR = "/volume2/video/thumbnails"
DB_FILE = "/volume2/video/video_metadata.db"
TMDB_CACHE_DIR = "/volume2/video/tmdb_cache"
HLS_ROOT = "/dev/shm/videoplayer_hls"
SUBTITLE_DIR = "/volume2/video/subtitles"  # 자막 저장 경로
CACHE_VERSION = "138.23"  # 제목 탭 초고속 데이터 다이어트 버전

# [수정] 절대 경로를 사용하여 파일 생성 보장
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
FAILURE_LOG_PATH = os.path.join(SCRIPT_DIR, "metadata_failures.txt")
SUCCESS_LOG_PATH = os.path.join(SCRIPT_DIR, "metadata_success.txt")

TMDB_MEMORY_CACHE = {}
TMDB_API_KEY = "eyJhbGciOiJIUzI1NiJ9.eyJhdWQiOiI3OGNiYWQ0ZjQ3NzcwYjYyYmZkMTcwNTA2NDIwZDQyYyIsIm5iZiI6MTY1MzY3NTU4MC45MTUsInN1YiI6IjYyOTExNjNjMTI0MjVjMDA1MjI0ZGQzNCIsInNjb3BlcyI6WyJhcGlfcmVhZCJdLCJ2ZXJzaW9uIjoxfQ.3YU0WuIx_WDo6nTRKehRtn4N5I4uCgjI1tlpkqfsUhk".strip()
TMDB_BASE_URL = "https://api.themoviedb.org/3"

# [추가] 매칭 진단용 전역 변수
MATCH_DIAGNOSTICS = {}

# --- [추가] 실시간 UI 모니터링을 위한 전역 상태 관리 ---
UPDATE_STATE = {
    "is_running": False,
    "task_name": "대기 중",
    "total": 0,
    "current": 0,
    "success": 0,
    "fail": 0,
    "current_item": "-",
    "logs": deque(maxlen=300)  # 최근 300개의 로그만 유지하여 메모리 최적화
}
UPDATE_LOCK = threading.Lock()


def set_update_state(is_running=None, task_name=None, total=None, current=None, success=None, fail=None,
                     current_item=None, clear_logs=False):
    with UPDATE_LOCK:
        if is_running is not None: UPDATE_STATE["is_running"] = is_running
        if task_name is not None: UPDATE_STATE["task_name"] = task_name
        if total is not None: UPDATE_STATE["total"] = total
        if current is not None: UPDATE_STATE["current"] = current
        if success is not None: UPDATE_STATE["success"] = success
        if fail is not None: UPDATE_STATE["fail"] = fail
        if current_item is not None: UPDATE_STATE["current_item"] = current_item
        if clear_logs: UPDATE_STATE["logs"].clear()


def emit_ui_log(msg, log_type='info'):
    timestamp = datetime.now().strftime("%H:%M:%S")
    with UPDATE_LOCK:
        UPDATE_STATE["logs"].append({"time": timestamp, "msg": msg, "type": log_type})


# -----------------------------------------------------------

os.makedirs(DATA_DIR, exist_ok=True)
os.makedirs(TMDB_CACHE_DIR, exist_ok=True)
os.makedirs(SUBTITLE_DIR, exist_ok=True)  # 자막 폴더 생성
if os.path.exists(HLS_ROOT): shutil.rmtree(HLS_ROOT, ignore_errors=True)
os.makedirs(HLS_ROOT, exist_ok=True)

PARENT_VIDEO_DIR = "/volume2/video/GDS3/GDRIVE/VIDEO"
PATH_MAP = {
    "외국TV": (os.path.join(PARENT_VIDEO_DIR, "외국TV"), "ftv"),
    "국내TV": (os.path.join(PARENT_VIDEO_DIR, "국내TV"), "ktv"),
    "영화": (os.path.join(PARENT_VIDEO_DIR, "영화"), "movie"),
    "애니메이션": (os.path.join(PARENT_VIDEO_DIR, "일본 애니메이션"), "anim_all"),
    "방송중": (os.path.join(PARENT_VIDEO_DIR, "방송중"), "air")
}

EXCLUDE_FOLDERS = ["성인", "19금", "Adult", "@eaDir", "#recycle"]
VIDEO_EXTS = ('.mp4', '.mkv', '.avi', '.wmv', '.flv', '.ts', '.tp', '.m4v', '.m2ts', '.mov')

# [개선] 더 많은 FFmpeg 경로 탐색 (시놀로지 환경 고려)
FFMPEG_PATH = "ffmpeg"
for p in ["/usr/local/bin/ffmpeg", "/var/packages/ffmpeg/target/bin/ffmpeg", "/usr/bin/ffmpeg",
          "/var/packages/VideoStation/target/bin/ffmpeg", "/var/packages/CodecPack/target/bin/ffmpeg"]:
    if os.path.exists(p):
        FFMPEG_PATH = p
        break

# [추가] FFprobe 경로 설정 (스토리보드 생성용)
FFPROBE_PATH = "ffprobe"
for p in ["/usr/local/bin/ffprobe", "/var/packages/ffmpeg/target/bin/ffprobe", "/usr/bin/ffprobe",
          "/var/packages/VideoStation/target/bin/ffprobe"]:
    if os.path.exists(p):
        FFPROBE_PATH = p
        break

HOME_RECOMMEND = []
IS_METADATA_RUNNING = False
_FAST_CATEGORY_CACHE = {}
_SECTION_CACHE = {}  # 카테고리 섹션 결과 캐시 추가
_GZIP_SECTION_CACHE = {} # Gzipped 섹션 결과 캐시
_DETAIL_CACHE = deque(maxlen=200)

THUMB_SEMAPHORE = threading.Semaphore(4)
STORYBOARD_SEMAPHORE = threading.Semaphore(2)  # [추가] 스토리보드 생성용 세마포어
THUMB_EXECUTOR = ThreadPoolExecutor(max_workers=8)
SUBTITLE_EXECUTOR = ThreadPoolExecutor(max_workers=2)  # 자막 추출 전용 대기열 (최대 2개 동시 처리)

ACTIVE_EXTRACTIONS = set()
EXTRACTION_LOCK = threading.Lock()

def log(tag, msg):
    timestamp = datetime.now().strftime("%H:%M:%S")
    print(f"[{timestamp}] [{tag}] {msg}", flush=True)


def log_matching_failure(orig, cleaned, reason):
    timestamp = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    try:
        with open(FAILURE_LOG_PATH, "a", encoding="utf-8") as f:
            f.write(f"[{timestamp}] [FAIL] ORIG: {orig} | CLEANED: {cleaned} | REASON: {reason}\n")
    except Exception as e:
        log("LOG_ERROR", f"실패 로그 파일 쓰기 중 에러: {str(e)}")


def log_matching_success(orig, cleaned, matched, tmdb_id):
    timestamp = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    try:
        with open(SUCCESS_LOG_PATH, "a", encoding="utf-8") as f:
            f.write(f"[{timestamp}] [OK] ORIG: {orig} | CLEANED: {cleaned} | MATCHED: {matched} | ID: {tmdb_id}\n")
    except:
        pass


def nfc(text):
    return unicodedata.normalize('NFC', text) if text else ""


def nfd(text):
    return unicodedata.normalize('NFD', text) if text else ""


# --- [DB 관리] ---
def get_db():
    # 연결 대기 시간을 60초로 설정 (기본값보다 길게)
    conn = sqlite3.connect(DB_FILE, timeout=60)
    conn.row_factory = sqlite3.Row

    # 동시성 향상을 위해 WAL 모드 활성화 시도
    try:
        # WAL 모드 설정 시 락이 걸려도 전체 프로세스가 중단되지 않도록 함
        conn.execute('PRAGMA journal_mode=WAL')
        # conn.execute('PRAGMA journal_mode=TRUNCATE')
        # conn.execute('PRAGMA journal_mode=DELETE')
        # busy_timeout을 한번 더 명시적으로 설정 (밀리초 단위, 30000ms = 30초)
        conn.execute('PRAGMA busy_timeout = 30000')
        # [추가] temp_store를 메모리로 변경하여 디스크 I/O 최적화 및 디스크 풀림 현상 완화
        conn.execute('PRAGMA temp_store = MEMORY')
    except sqlite3.OperationalError as e:
        log("DB_ERROR", f"WAL 모드 설정 실패 (무시하고 계속): {e}")
    except Exception as e:
        log("DB_ERROR", f"기타 DB 설정 오류: {e}")

    return conn


def init_db():
    try:
        conn = get_db()
        cursor = conn.cursor()
        cursor.execute(
            'CREATE TABLE IF NOT EXISTS series (path TEXT PRIMARY KEY, category TEXT, name TEXT, posterPath TEXT, year TEXT, overview TEXT, rating TEXT, seasonCount INTEGER, genreIds TEXT, genreNames TEXT, director TEXT, actors TEXT, failed INTEGER DEFAULT 0, tmdbId TEXT)')
        cursor.execute(
            'CREATE TABLE IF NOT EXISTS episodes (id TEXT PRIMARY KEY, series_path TEXT, title TEXT, videoUrl TEXT, thumbnailUrl TEXT, overview TEXT, air_date TEXT, season_number INTEGER, episode_number INTEGER, FOREIGN KEY (series_path) REFERENCES series (path) ON DELETE CASCADE)')
        cursor.execute('CREATE TABLE IF NOT EXISTS tmdb_cache (h TEXT PRIMARY KEY, data TEXT)')
        cursor.execute('CREATE TABLE IF NOT EXISTS server_config (key TEXT PRIMARY KEY, value TEXT)')

        # 인덱스 생성 (조회 속도 최적화)
        cursor.execute('CREATE INDEX IF NOT EXISTS idx_series_category ON series(category)')
        cursor.execute('CREATE INDEX IF NOT EXISTS idx_series_name ON series(name)')
        cursor.execute('CREATE INDEX IF NOT EXISTS idx_series_tmdbId ON series(tmdbId)')
        cursor.execute('CREATE INDEX IF NOT EXISTS idx_episodes_series ON episodes(series_path)')
        cursor.execute('CREATE INDEX IF NOT EXISTS idx_series_cleanedName ON series(cleanedName)')

        # 🔴 [추가] 조회 및 정렬 속도를 극대화하는 복합 인덱스
        cursor.execute('CREATE INDEX IF NOT EXISTS idx_series_cat_year ON series(category, yearVal DESC)')
        cursor.execute('CREATE INDEX IF NOT EXISTS idx_episodes_series_path ON episodes(series_path)')

        # 🔴 [추가] 사용자님이 요청하신 인덱스 명시적 생성
        cursor.execute('CREATE INDEX IF NOT EXISTS idx_episodes_series_path ON episodes(series_path)')
        # 🔴 [추가] 시청한 기록 진행률표시
        cursor.execute('''
            CREATE TABLE IF NOT EXISTS playback_progress (
                episode_id TEXT PRIMARY KEY,
                position REAL DEFAULT 0,
                duration REAL DEFAULT 0,
                last_watched TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                FOREIGN KEY (episode_id) REFERENCES episodes (id) ON DELETE CASCADE
            )
        ''')

        def add_col_if_missing(table, col, type):
            cursor.execute(f"PRAGMA table_info({table})")
            cols = [c[1] for c in cursor.fetchall()]
            if col not in cols:
                log("DB", f"컬럼 추가: {table}.{col}")
                cursor.execute(f"ALTER TABLE {table} ADD COLUMN {col} {type}")

        add_col_if_missing('series', 'tmdbId', 'TEXT')
        add_col_if_missing('series', 'genreNames', 'TEXT')
        add_col_if_missing('series', 'director', 'TEXT')
        add_col_if_missing('series', 'actors', 'TEXT')
        add_col_if_missing('series', 'cleanedName', 'TEXT')
        add_col_if_missing('series', 'yearVal', 'TEXT')

        add_col_if_missing('episodes', 'overview', 'TEXT')
        add_col_if_missing('episodes', 'air_date', 'TEXT')
        add_col_if_missing('episodes', 'season_number', 'INTEGER')
        add_col_if_missing('episodes', 'episode_number', 'INTEGER')
        add_col_if_missing('series', 'tmdbTitle', 'TEXT')
        add_col_if_missing('series', 'runtime', 'INTEGER')
        add_col_if_missing('episodes', 'runtime', 'INTEGER')

        cursor.execute('CREATE INDEX IF NOT EXISTS idx_series_cleanedName ON series(cleanedName)')

        conn.commit()
        conn.close()
        log("DB", "시스템 초기화 및 최적화 완료")
    except sqlite3.OperationalError as e:
        log("DB", f"초기화 중 락 발생: {e}. 이미 실행 중인 프로세스가 있는지 확인하세요.")


# --- [유틸리티] ---
def get_real_path(path):
    if not path or os.path.exists(path): return path
    if os.path.exists(nfc(path)): return nfc(path)
    if os.path.exists(nfd(path)): return nfd(path)
    return path


def migrate_json_to_db():
    if not os.path.exists(TMDB_CACHE_DIR): return
    conn = get_db()
    row = conn.execute("SELECT value FROM server_config WHERE key = 'json_migration_done'").fetchone()
    if row and row['value'] == 'true':
        conn.close()
        return
    files = [f for f in os.listdir(TMDB_CACHE_DIR) if f.endswith(".json")]
    if not files:
        conn.close()
        return
    log("MIGRATE", f"JSON 캐시 {len(files)}개 DB 이관 중...")
    for idx, f in enumerate(files):
        h = f.replace(".json", "")
        try:
            with open(os.path.join(TMDB_CACHE_DIR, f), 'r', encoding='utf-8') as file:
                data = json.load(file)
                conn.execute('INSERT OR REPLACE INTO tmdb_cache (h, data) VALUES (?, ?)', (h, json.dumps(data)))
        except:
            pass
        if (idx + 1) % 2000 == 0:
            conn.commit()
            log("MIGRATE", f"진행 중... ({idx + 1}/{len(files)})")
    conn.execute("INSERT OR REPLACE INTO server_config (key, value) VALUES ('json_migration_done', 'true')")
    conn.commit()
    conn.close()
    log("MIGRATE", "이관 완료")


# --- [정규식 및 클리닝 대폭 강화] ---
REGEX_EXT = re.compile(r'\.[a-zA-Z0-9]{2,4}$')
REGEX_YEAR = re.compile(r'\((19|20)\d{2}\)|(?<!\d)(19|20)\d{2}(?!\d)')
REGEX_CH_PREFIX = re.compile(
    r'^\[(?:KBS|SBS|MBC|tvN|JTBC|OCN|Mnet|TV조선|채널A|MBN|ENA|KBS2|KBS1|CH\d+|TV|Netflix|Disney\+|AppleTV|NET|Wavve|Tving|Coupang)\]\s*')
# [수정] 기술적 태그: 한글 단어 일부를 태그로 오해하지 않도록 경계 조건 강화
REGEX_TECHNICAL_TAGS = re.compile(
    r'(?i)[.\s_-](?!(?:\d+\b))(\d{3,4}p|2160p|FHD|QHD|UHD|4K|Bluray|Blu-ray|WEB-DL|WEBRip|HDRip|BDRip|DVDRip|H\.?26[45]|x26[45]|HEVC|AVC|AAC\d?|DTS-?H?D?|AC3|DDP\d?|DD\+\d?|Dual|Atmos|REPACK|10bit|REMUX|FLAC|xvid|DivX|MKV|MP4|AVI|HDR(?:10)?(?:\+)?|Vision|Dolby|NF|AMZN|HMAX|DSNP|AppleTV?|Disney|PCOK|playWEB|ATVP|HULU|HDTV|HD|KBS|SBS|MBC|TVN|JTBC|NEXT|ST|SW|KL|YT|MVC|KN|FLUX|hallowed|PiRaTeS|Jadewind|Movie|pt\s*\d+|KOREAN|KOR|ITALIAN|JAPANESE|JPN|CHINESE|CHN|ENGLISH|ENG|USA|HK|TW|FRENCH|GERMAN|SPANISH|THAI|VIETNAMESE|WEB|DL|TVRip|HDR10Plus|IMAX|Unrated|REMASTERED|Criterion|NonDRM|BRRip|1080i|720i|국어|Mandarin|Cantonese|FanSub|VFQ|VF|2CH|5\.1CH|8m|2398|PROPER|PROMO|LIMITED|RM4K|DC|THEATRICAL|EXTENDED|FINAL|DUB|KORDUB|JAPDUB|ENGDUB|ARROW|EDITION|SPECIAL|COLLECTION|RETAIL|TVING|WAVVE|Coupang|CP|B-Global|TrueHD|E-AC3|EAC3|DV|Dual-Audio|Multi-Audio|Multi-Sub)(?:\b|[.\s_-]|$)')


# [수정] 날짜 형식 (6자리 또는 8자리 숫자)
REGEX_DATE = re.compile(r'(?<!\d)\d{6}(?!\d)|(?<!\d)\d{8}(?!\d)')

# 에피소드/시즌 마커 (단순 숫자 패턴 추가로 정제 능력 강화)
REGEX_EP_MARKER_STRICT = re.compile(
    r'(?i)(?:(?<=[\uac00-\ud7af\u3040-\u30ff\u4e00-\u9fff])|[.\s_-]|^)(?:'
    r'第?\s*S(\d+)[.\s_-]*E(\d+)(?:[-~]E?\d+)?(?:[화회기부話장쿨편])?|'
    r'第?\s*S(\d+)|'
    r'第?\s*E(\d+)(?:[-~]\d+)?(?:[화회기부話장쿨편])?|'
    r'(?<!\d)(\d+)\s*(?:화|회|기|부|話|장|쿨|편)|'
    r'(?:Season|Episode|Part|시즌|파트)[.\s_-]*(\d+)|'
    r'(?<=[.\s_-])(\d{1,3})(?=[.\s_-]|$)'  # [추가] 구분자 사이의 1~3자리 숫자 인식
    r')(?:[.\s_-]*완)?(?:\b|[.\s_-]|$)'
)
# 제목 중간의 불필요한 수식어 제거
REGEX_TITLE_NOISE = re.compile(r'(?i)\b(?:part|파트|시즌|season|episode|ep|vol|volume|disk|disc)\s*\d+\b')

# [추가] 릴리즈 그룹 및 노이즈 제거
REGEX_RELEASE_GROUP = re.compile(r'-[A-Za-z0-9]+(?:\s|$|(?=\.))')

REGEX_DATE_YYMMDD = re.compile(r'(?<!\d)\d{6}(?!\d)')
REGEX_FORBIDDEN_CONTENT = re.compile(
    r'(?i)(Storyboard|Behind the Scenes|Making of|Deleted Scenes|Alternate Scenes|Gag Reel|Gag Menu|Digital Hits|Trailer|Bonus|Extras|Gallery|Production|Visual Effects|VFX|등급고지|예고편|개봉버전|인터뷰|삭제장면|(?<!\S)[상하](?!\S))')
# [수정] '라프텔', '시리즈'는 키워드로 사용되므로 금지어에서 제외하거나 조심스럽게 처리
REGEX_FORBIDDEN_TITLE = re.compile(
    r'(?i)^\s*(Season\s*\d+|Part\s*\d+|EP\s*\d+|\d+화|\d+회|\d+기|시즌\s*\d+|S\d+|E\d+|Disk\s*\d+|Disc\s*\d+|CD\s*\d+|Specials?|Extras?|Bonus|미분류|기타|새\s*폴더|VIDEO|GDS3|GDRIVE|NAS|share|영화|UHD|최신|최신작|최신영화|4K|1080P|720P)\s*$',
    re.I)

REGEX_BRACKETS = re.compile(
    r'\[.*?(?:\]|$)|\(.*?(?:\)|$)|\{.*?(?:\)|$)|\{.*?(?:\}|$)|\【.*?(?:\】|$)|\『.*?(?:\』|$)|\「.*?(?:\」|$)|\（.*?(?:\）|$)')
REGEX_TMDB_HINT = re.compile(r'\{tmdb[\s-]*(\d+)\}')
# [수정] 중요 구분 키워드는 지우지 않도록 보완 (극장판, 스페셜, OVA 등 보존)
REGEX_JUNK_KEYWORDS = re.compile(
    r'(?i)\s*(?:더빙|자막|한국어|BD|TV|Web|OAD|ONA|Full|무삭제|감독판|확장판|익스텐디드|등급고지|예고편|(?<!\S)[상하](?!\S)|\d+부작|큐레이션|단편|드라마)\s*')
# [수정] 특수문자 제거 시 하이픈(-)과 콜론(:)은 제외하여 부제 분리에 사용 (별표 추가)
REGEX_SPECIAL_CHARS = re.compile(r'[\[\]()_\.!#@*※×,~;【】『』「」"\'（）☆★]')
REGEX_LEADING_INDEX = re.compile(r'^\s*(\d{1,5}(?:\s+|[.\s_-]+|(?=[가-힣a-zA-Z])))|^\s*(\d{1,5}\. )')
REGEX_SPACES = re.compile(r'\s+')

_NATURAL_SORT_RE = re.compile(r'(\d+)')
def natural_sort_key(s):
    if not s: return []
    # 미리 정규화된 문자열을 사용하여 CPU 부하 감소
    return [int(text) if text.isdigit() else text.lower() for text in _NATURAL_SORT_RE.split(nfc(str(s)))]

def clean_title_complex(title, full_path=None, base_path=None):
    if not title: return "", None
    t_orig = nfc(title)
    t_low = t_orig.lower().replace(" ", "")

    # 극장판 여부 등 특징만 추출 (중복 제거용 키 생성을 위해)
    is_movie = any(x in t_low for x in ['극장판', 'movie', 'themovie'])

    series_name = ""
    if full_path:
        parts = [p.strip() for p in full_path.replace('\\', '/').split('/') if p.strip()]
        SKIP_DIRS = {
            '애니메이션', '일본애니메이션', '라프텔', '시리즈', '기타', 'video', 'volume1', 'volume2',
            'movies', 'animations_all', 'koreantv', 'foreigntv', 'air', 'gdrive', 'nas', 'share',
            '더빙', '자막', 'gds3', 'video', 'GDS3', 'GDRIVE', 'VIDEO'
        }

        for p in reversed(parts[:-1]):
            p_clean = p.lower().replace(" ", "")
            if p_clean in SKIP_DIRS: continue
            if any(p_clean.endswith(ext) for ext in VIDEO_EXTS): continue

            is_season_folder = any(x in p_clean for x in ['기', '시즌', 'season', 'part', '파트', 'ep', 'vol']) or \
                               re.search(r'(?i)[.\s_-](s\d+|e\d+)\b', p_clean) or \
                               re.match(r'^(?i)s\d+|e\d+$', p_clean)

            if is_season_folder:
                temp_name = REGEX_BRACKETS.sub(' ', p)
                m = REGEX_EP_MARKER_STRICT.search(temp_name)
                if m and m.start() > 1:
                    series_name = temp_name[:m.start()].strip()
                    if series_name and not REGEX_FORBIDDEN_TITLE.match(series_name):
                        break
                continue
            series_name = p
            break

    if not series_name:
        series_name = os.path.splitext(t_orig)[0]

    series_name = REGEX_BRACKETS.sub(' ', series_name)
    marker_match = REGEX_EP_MARKER_STRICT.search(series_name)
    if marker_match:
        series_name = series_name[:marker_match.start()]

    # [보완] 제목 끝에 남은 대시(-)나 불필요한 공백 제거
    series_name = re.sub(r'\s*-\s*$', '', series_name)
    series_name = REGEX_TECHNICAL_TAGS.sub('', series_name)
    series_name = REGEX_SPECIAL_CHARS.sub(' ', series_name)
    series_name = REGEX_SPACES.sub(' ', series_name).strip()

    if is_movie and "극장판" not in series_name:
        series_name = f"극장판 {series_name}"

    # [수정] 태그(더빙/자막)를 붙이지 않고 순수 제목만 반환
    return series_name.strip(), None


def extract_episode_numbers(full_path):
    n = nfc(full_path)

    # [새로운 추가 로직] 폴더 구조 기반 시즌 판별 (스마트 시즌 감지)
    # 예: "자막 명탐정 코난 미공개X파일 1 (1996)/01화.mp4" -> 시즌 1 추출
    season = None
    parts = n.split('/')
    if len(parts) >= 2:
        parent_folder = parts[-2]

        # 1. 괄호를 먼저 제거하여 순수 텍스트만 분석 (연도 등에 속지 않기 위해)
        clean_parent = REGEX_BRACKETS.sub(' ', parent_folder)

        # 2. 명확한 숫자 패턴 (예: X파일 1, 1기, 시즌1) 추출 시도
        ms = re.search(r'(?i)(?:시즌|Season|S)\s*(\d+)|(\d+)\s*기|(?<=\s)(\d+)(?=\s*$)', clean_parent)
        if ms:
            season = int(ms.group(1) or ms.group(2) or ms.group(3))

    # [기존 로직] 파일명 기반 마커 추출
    # 1. 표준 패턴 우선 확인 (S01E01, 1기 1화 등)
    m = re.search(r'(?i)S(\d+)\s*E(\d+)|(\d+)\s*기\s*(\d+)\s*(?:화|회)', n)
    if m:
        if m.group(1): return int(m.group(1)), int(m.group(2))
        if not season: season = int(m.group(3))
        return season, int(m.group(4))

    # 2. 파일명 내의 시즌 정보 (위에서 폴더 시즌을 찾지 못했을 때만)
    if not season:
        season = 1
        ms = re.search(r'(?i)(?:시즌|Season|S)\s*(\d+)|(\d+)\s*기', n)
        if ms: season = int(ms.group(1) or ms.group(2))

    # 3. 회차 정보 (파일명의 가장 마지막 숫자 뭉치)
    filename = os.path.basename(n)
    me = re.search(r'(?i)(?:[.\s_-]E|EP)\s*(\d+)|(\d+)\s*(?:화|회)', filename)
    if me:
        episode = int(me.group(1) or me.group(2))
    else:
        # 마커가 없으면 파일명 끝에서부터 숫자 추출
        nums = re.findall(r'\d+', filename)
        episode = int(nums[-1]) if nums else 1

    return season, episode


def extract_tmdb_id(title):
    match = REGEX_TMDB_HINT.search(nfc(title))
    return int(match.group(1)) if match else None


def simple_similarity(s1, s2):
    s1, s2 = s1.lower().replace(" ", ""), s2.lower().replace(" ", "")
    if s1 == s2: return 1.0
    if s1 in s2 or s2 in s1: return 0.8
    return 0.0


# --- [TMDB API 보완: 지능형 재검색 및 랭킹 시스템] ---
def get_tmdb_info_server(title, category=None, ignore_cache=False):  # category 매개변수 추가
    if not title: return {"failed": True}
    hint_id = extract_tmdb_id(title)
    ct, year = clean_title_complex(title)
    if not ct or REGEX_FORBIDDEN_TITLE.match(ct):
        return {"failed": True, "forbidden": True}

    # 카테고리에 따른 선호 타입 결정 (Taxi Driver 등 동명 타이틀 오매칭 방지)
    pref_mtype = 'movie' if (category == 'movies' or '극장판' in title) else 'tv' if category in ['koreantv', 'foreigntv',
                                                                                               'air',
                                                                                               'animations_all'] else None

    cache_key = f"{ct}_{year}_{category}" if year else f"{ct}_{category}"
    h = hashlib.md5(nfc(cache_key).encode()).hexdigest()

    if not ignore_cache and h in TMDB_MEMORY_CACHE:
        return TMDB_MEMORY_CACHE[h]

    if not ignore_cache:
        try:
            conn = get_db()
            row = conn.execute('SELECT data FROM tmdb_cache WHERE h = ?', (h,)).fetchone()
            conn.close()
            if row:
                data = json.loads(row['data'])
                TMDB_MEMORY_CACHE[h] = data
                return data
        except:
            pass

    log("TMDB", f"🔍 지능형 검색 시작: '{ct}'" + (f" ({year})" if year else "") + (f" [Cat: {category}]" if category else ""))
    headers = {"Authorization": f"Bearer {TMDB_API_KEY}"}
    # [수정 후: 성인물 검색 완전 차단]
    base_params = {"include_adult": "false", "region": "KR"}

    def perform_search(query, lang=None, m_type='multi', search_year=None):
        if not query or len(query) < 1: return []
        params = {**base_params, "query": query}
        if lang: params["language"] = lang
        if search_year:
            params['year' if m_type == 'movie' else 'first_air_date_year' if m_type == 'tv' else 'year'] = search_year
            if m_type == 'multi': params['year'] = search_year
        try:
            r = requests.get(f"{TMDB_BASE_URL}/search/{m_type}", params=params, headers=headers, timeout=10)
            return r.json().get('results', []) if r.status_code == 200 else []
        except:
            return []

    def rank_results(results, target_title, target_year, pref_type=None):
        if not results: return None, []
        scored = []
        for res in results:
            if res.get('media_type') == 'person': continue
            m_type = res.get('media_type') or ('movie' if res.get('title') else 'tv')
            score = 0
            res_title = res.get('title') or res.get('name') or ""
            res_year = (res.get('release_date') or res.get('first_air_date') or "").split('-')[0]

            sim = simple_similarity(target_title, res_title)
            score += sim * 60

            if target_year and res_year:
                if target_year == res_year:
                    score += 30
                elif abs(int(target_year) - int(res_year)) <= 1:
                    score += 15
            elif not target_year:
                score += 10

            score += min(res.get('popularity', 0) / 10, 10)
            if res.get('poster_path'): score += 5

            # [추가] 선호하는 타입(영화/TV)과 일치할 경우 큰 가중치 부여
            if pref_type and m_type == pref_type:
                score += 40

            scored.append((score, res))

        scored.sort(key=lambda x: x[0], reverse=True)

        # [추가] 진단 데이터 수집
        candidates = []
        for s, r in scored[:3]:
            candidates.append({
                "title": r.get('title') or r.get('name'),
                "year": (r.get('release_date') or r.get('first_air_date') or "").split('-')[0],
                "score": round(s, 1),
                "type": r.get('media_type') or ('movie' if r.get('title') else 'tv')
            })

        # [수정] 반환 시 항상 두 개의 값을 반환하도록 보장
        best = scored[0][1] if scored and scored[0][0] > 35 else None
        return best, candidates

    try:
        best_match = None
        all_candidates = []

        if hint_id:
            log("TMDB", f"💡 힌트 ID 사용: {hint_id}")
            for mt in ['movie', 'tv']:
                resp = requests.get(f"{TMDB_BASE_URL}/{mt}/{hint_id}", params={"language": "ko-KR", **base_params},
                                    headers=headers, timeout=10)
                if resp.status_code == 200:
                    best_match = resp.json()
                    best_match['media_type'] = mt
                    break

        if not best_match:
            results = perform_search(ct, "ko-KR", "multi", year)
            best_match, all_candidates = rank_results(results, ct, year, pref_mtype)

            if not best_match and year:
                log("TMDB", f"🔄 연도 제외 재검색: '{ct}'")
                results = perform_search(ct, "ko-KR", "multi", None)
                best_match, all_candidates = rank_results(results, ct, year, pref_mtype)

            if not best_match:
                # [수정] 괄호 안의 원어/영어 제목 추출하여 검색 시도
                alt_titles = re.findall(r'[\(\[\{【『「（](.*?)[\)\]\}】』」）]', title)
                for alt in alt_titles:
                    alt = alt.strip()
                    if len(alt) >= 2 and not REGEX_TECHNICAL_TAGS.search(alt):
                        log("TMDB", f"🔄 대체 제목 검색: '{alt}'")
                        results = perform_search(alt, None, "multi", year)
                        best_match, all_candidates = rank_results(results, alt, year, pref_mtype)
                        if best_match: break

            if not best_match:
                # [개선] 한글 제목만 추출하여 검색 (특수문자/영어 제외)
                ko_only = "".join(re.findall(r'[가-힣\s]+', ct)).strip()
                if ko_only and ko_only != ct and len(ko_only) >= 2:
                    log("TMDB", f"🔄 한글 부분 재검색: '{ko_only}'")
                    results = perform_search(ko_only, "ko-KR", "multi", year)
                    best_match, all_candidates = rank_results(results, ko_only, year, pref_mtype)

            if not best_match:
                # [수정] 원어(일어/한자) 부분 추출 검색 추가
                cjk_parts = re.findall(r'[\u3040-\u30ff\u4e00-\u9fff]+', title)
                for part in cjk_parts:
                    if len(part) >= 2:
                        log("TMDB", f"🔄 원어 부분 검색: '{part}'")
                        results = perform_search(part, None, "multi", year)
                        best_match, all_candidates = rank_results(results, part, year, pref_mtype)
                        if best_match: break

            if not best_match:
                # [수정] 하이픈(-)이나 콜론(:)으로 구분된 부분 검색 시도
                parts = re.split(r'[-:～]', ct)
                if len(parts) > 1:
                    for p in parts:
                        sub_title = p.strip()
                        if len(sub_title) >= 2 and not REGEX_FORBIDDEN_TITLE.match(sub_title):
                            log("TMDB", f"🔄 부분 제목 검색: '{sub_title}'")
                            results = perform_search(sub_title, "ko-KR", "multi", year)
                            best_match, all_candidates = rank_results(results, sub_title, year, pref_mtype)
                            if best_match: break

        if best_match:
            m_type, t_id = best_match.get('media_type') or (
                'movie' if best_match.get('title') else 'tv'), best_match.get('id')
            log("TMDB", f"✅ 매칭 성공: '{ct}' -> {m_type}:{t_id}")
            log_matching_success(title, ct, best_match.get('title') or best_match.get('name'), f"{m_type}:{t_id}")
            d_resp = requests.get(
                f"{TMDB_BASE_URL}/{m_type}/{t_id}?language=ko-KR&append_to_response=content_ratings,credits",
                headers=headers, timeout=10).json()

            yv = (d_resp.get('release_date') or d_resp.get('first_air_date') or "").split('-')[0]
            rating = None
            if 'content_ratings' in d_resp:
                res_r = d_resp['content_ratings'].get('results', [])
                kr = next((r['rating'] for r in res_r if r.get('iso_3166_1') == 'KR'), None)
                if kr: rating = f"{kr}+" if kr.isdigit() else kr

            genre_names = [g['name'] for g in d_resp.get('genres', [])] if d_resp.get('genres') else []
            cast_data = d_resp.get('credits', {}).get('cast', [])
            actors = [{"name": c['name'], "profile": c['profile_path'], "role": c['character']} for c in cast_data[:10]]
            crew_data = d_resp.get('credits', {}).get('crew', [])
            director = next((c['name'] for c in crew_data if c.get('job') == 'Director'), "")

            info = {
                "tmdbId": f"{m_type}:{t_id}",
                "title": d_resp.get('title') or d_resp.get('name'),
                "genreIds": [g['id'] for g in d_resp.get('genres', [])],
                "genreNames": [g['name'] for g in d_resp.get('genres', [])],
                "director": director,
                "actors": actors,
                "posterPath": d_resp.get('poster_path'),
                "year": yv,
                "overview": d_resp.get('overview'),
                "rating": rating,
                "seasonCount": d_resp.get('number_of_seasons'),
                "runtime": d_resp.get('runtime'),  # <-- 영화 전체 런타임 추가 (분 단위)
                "failed": False
            }

            if m_type == 'tv':
                info['seasons_data'] = {}
                s_count = d_resp.get('number_of_seasons') or 1
                for s_num in range(1, s_count + 1):
                    s_resp = requests.get(f"{TMDB_BASE_URL}/tv/{t_id}/season/{s_num}?language=ko-KR", headers=headers,
                                          timeout=10).json()
                    if 'episodes' in s_resp:
                        for ep in s_resp['episodes']:
                            info['seasons_data'][f"{s_num}_{ep['episode_number']}"] = {
                                "overview": ep.get('overview'),
                                "air_date": ep.get('air_date'),
                                "still_path": ep.get('still_path'),
                                "runtime": ep.get('runtime')  # <-- 에피소드별 런타임 추가 (분 단위)
                            }

            TMDB_MEMORY_CACHE[h] = info
            try:
                conn = get_db()
                conn.execute('INSERT OR REPLACE INTO tmdb_cache (h, data) VALUES (?, ?)', (h, json.dumps(info)))
                conn.commit()
                conn.close()
            except:
                pass
            return info
        else:
            log("TMDB", f"❌ 검색 결과 없음: '{ct}'")
            # [추가] 진단 정보 저장
            MATCH_DIAGNOSTICS[title] = {
                "cleaned": ct,
                "year": year,
                "category": category,
                "candidates": all_candidates,
                "timestamp": datetime.now().strftime("%Y-%m-%d %H:%M:%S")
            }
            log_matching_failure(title, ct, "NOT_FOUND_IN_TMDB")
            try:
                conn = get_db()
                conn.execute('INSERT OR REPLACE INTO tmdb_cache (h, data) VALUES (?, ?)',
                             (h, json.dumps({"failed": True})))
                conn.commit()
                conn.close()
            except:
                pass
    except:
        log("TMDB", f"⚠️ 에러 발생: {traceback.format_exc()}")
        log_matching_failure(title, ct, f"API_ERROR: {str(sys.exc_info()[1])}")
    return {"failed": True}


WHITELISTS = {
    "movies": [],
    "koreantv": [],
    "foreigntv": [],
    "animations_all": [],
    "air": ["드라마", "라프텔 애니메이션", "외국"]  # "외국" 폴더를 추가했습니다.
}


def scan_recursive_to_db(bp, prefix, category, include_only=None):
    """
    bp: 기준 경로 (예: /volume2/video/GDS3/GDRIVE/VIDEO/방송중)
    prefix: URL 접두사 (예: air)
    category: DB 카테고리 식별자 (예: air)
    include_only: 스캔할 특정 하위 폴더 리스트 (예: ["외국"])
    """
    emit_ui_log(f"'{category}' 카테고리 스캔을 시작합니다...", "info")
    log("SCAN", f"📂 '{category}' 탐색 시작 (대상: {include_only if include_only else '전체'})")

    base = nfc(get_real_path(bp))
    all_files = []

    # 1. 스캔 대상 경로 결정 (특정 폴더만 혹은 전체)
    targets = []
    if include_only:
        for folder in include_only:
            target = os.path.join(base, folder)
            if os.path.exists(target):
                targets.append(target)
            elif os.path.exists(nfc(target)):
                targets.append(nfc(target))
    else:
        targets = [base]

    # 2. 파일 시스템 탐색 (스택 방식 트리 순회)
    for start_point in targets:
        stack = [start_point]
        visited = set()
        while stack:
            curr = stack.pop()
            real_curr = os.path.realpath(curr)
            if real_curr in visited: continue
            visited.add(real_curr)
            try:
                with os.scandir(curr) as it:
                    for entry in it:
                        if entry.is_dir():
                            # 제외 폴더 및 숨김 폴더 필터링
                            if not any(ex in entry.name for ex in EXCLUDE_FOLDERS) and not entry.name.startswith('.'):
                                stack.append(entry.path)
                        elif entry.is_file() and entry.name.lower().endswith(VIDEO_EXTS):
                            all_files.append(nfc(entry.path))

                        if len(all_files) % 1000 == 0:
                            log("SCAN", f"⏳ 파일 수집 중... 현재 {len(all_files)}개 발견")
            except Exception as e:
                log("SCAN_ERR", f"접근 오류 ({curr}): {e}")

    # 3. 데이터베이스 동기화 시작
    conn = get_db()
    cursor = conn.cursor()

    # 해당 카테고리의 기존 데이터 맵핑 (삭제 판단용)
    cursor.execute('SELECT id, series_path FROM episodes WHERE series_path LIKE ?', (f"{category}/%",))
    db_data = {row['id']: row['series_path'] for row in cursor.fetchall()}
    current_ids = set()
    total = len(all_files)

    set_update_state(is_running=True, task_name=f"스캔 ({category})", total=total, current=0, success=0, fail=0)

    # 4. 신규 추가 및 경로 변경 반영
    for idx, fp in enumerate(all_files):
        mid = hashlib.md5(fp.encode()).hexdigest()
        current_ids.add(mid)
        rel = nfc(os.path.relpath(fp, base))
        name = os.path.splitext(os.path.basename(fp))[0]
        spath = f"{category}/{rel}"

        # 제목 정제 및 메타데이터 기본형 생성
        ct, yr = clean_title_complex(name, full_path=fp, base_path=base)

        # 시리즈 테이블 등록 (이미 있으면 무시)
        cursor.execute(
            'INSERT OR IGNORE INTO series (path, category, name, cleanedName, yearVal) VALUES (?, ?, ?, ?, ?)',
            (spath, category, name, ct, yr))

        # 에피소드 테이블 등록/갱신
        if mid not in db_data:
            cursor.execute(
                'INSERT OR REPLACE INTO episodes (id, series_path, title, videoUrl, thumbnailUrl) VALUES (?, ?, ?, ?, ?)',
                (mid, spath, os.path.basename(fp), f"/video_serve?type={prefix}&path={urllib.parse.quote(rel)}",
                 f"/thumb_serve?type={prefix}&id={mid}&path={urllib.parse.quote(rel)}"))
            emit_ui_log(f"신규 추가: '{name}'", 'success')
            with UPDATE_LOCK:
                UPDATE_STATE["success"] += 1
        elif db_data[mid] != spath:
            # 파일 위치가 바뀐 경우 경로 업데이트
            cursor.execute('UPDATE episodes SET series_path = ? WHERE id = ?', (spath, mid))
            with UPDATE_LOCK:
                UPDATE_STATE["success"] += 1
        else:
            with UPDATE_LOCK:
                UPDATE_STATE["success"] += 1

        # 1000개 단위로 커밋하여 안정성 확보
        if (idx + 1) % 1000 == 0:
            conn.commit()
            with UPDATE_LOCK: UPDATE_STATE["current"] = idx + 1

    # 5. [중요] 스마트 클린업 로직 (부분 스캔 시 기존 데이터 보호)
    delete_candidates = set(db_data.keys()) - current_ids
    deleted_count = 0

    for rid in delete_candidates:
        old_spath = db_data[rid]  # 예: "air/드라마/제목.mp4"

        should_delete = False
        if not include_only:
            # 전체 스캔 모드일 때는 이번에 발견 안 된 모든 항목 삭제
            should_delete = True
        else:
            # 부분 스캔 모드일 때는 이번에 조사한 폴더 안에 있던 데이터만 삭제
            for folder in include_only:
                # 예: "air/외국/..." 경로로 시작하는 데이터만 지움
                if old_spath.startswith(f"{category}/{folder}/"):
                    should_delete = True
                    break

        if should_delete:
            cursor.execute('DELETE FROM episodes WHERE id = ?', (rid,))
            deleted_count += 1

    # 에피소드가 하나도 남지 않은 시리즈(폴더) 정보 정리
    cursor.execute('DELETE FROM series WHERE path NOT IN (SELECT DISTINCT series_path FROM episodes) AND category = ?',
                   (category,))

    conn.commit()
    conn.close()

    set_update_state(is_running=False, current_item=f"스캔 완료 (신규/갱신: {UPDATE_STATE['success']}, 삭제: {deleted_count})")
    log("SCAN", f"✅ '{category}' 스캔 및 DB 동기화 완료")


def perform_full_scan():
    log("SYSTEM", f"🚀 전체 스캔 시작 (v{CACHE_VERSION})")
    pk = [("영화", "movies"), ("외국TV", "foreigntv"), ("국내TV", "koreantv"), ("애니메이션", "animations_all"), ("방송중", "air")]
    conn = get_db()
    rows = conn.execute("SELECT key FROM server_config WHERE key LIKE 'scan_done_%' AND value = 'true'").fetchall()
    done = [r['key'].replace('scan_done_', '') for r in rows]
    conn.close()

    for label, ck in pk:
        if ck in done: continue
        path, prefix = PATH_MAP[label]
        if os.path.exists(path):
            # [수정] 해당 카테고리의 화이트리스트 폴더만 스캔하도록 인자 추가
            scan_recursive_to_db(path, prefix, ck, include_only=WHITELISTS.get(ck))
            conn = get_db()
            conn.execute("INSERT OR REPLACE INTO server_config (key, value) VALUES (?, 'true')", (f'scan_done_{ck}',))
            conn.commit()
            conn.close()
            build_all_caches()

    conn = get_db()
    conn.execute('INSERT OR REPLACE INTO server_config (key, value) VALUES (?, ?)',
                 ('last_scan_version', CACHE_VERSION))
    conn.execute("DELETE FROM server_config WHERE key LIKE 'scan_done_%'")
    conn.commit()
    conn.close()
    threading.Thread(target=fetch_metadata_async, daemon=True).start()


def fetch_metadata_async(force_all=False, target_name=None):
    global IS_METADATA_RUNNING
    if IS_METADATA_RUNNING:
        log("METADATA", "이미 프로세스가 실행 중입니다. 중단합니다.")
        return
    IS_METADATA_RUNNING = True

    # 작업명 설정 (타겟이 있으면 표시)
    task_display_name = f"메타데이터 매칭 (대상: {target_name})" if target_name else "메타데이터 매칭"
    log("METADATA", f"⚙️ 병렬 매칭 프로세스 시작 (force_all={force_all}, target='{target_name}')")

    try:
        conn = get_db()
        cursor = conn.cursor()

        # 1. 대상 카운팅 쿼리 수정 (진행률 표시용)
        count_query = "SELECT COUNT(*) FROM series WHERE failed = 0"
        count_params = []
        if target_name:
            count_query += " AND (name LIKE ? OR cleanedName LIKE ?)"
            count_params.extend([f'%{target_name}%', f'%{target_name}%'])
        else:
            count_query += " AND (tmdbId IS NULL OR tmdbTitle IS NULL)"

        t_wait = cursor.execute(count_query, count_params).fetchone()[0]
        t_all = cursor.execute("SELECT COUNT(*) FROM series").fetchone()[0]
        t_ok = cursor.execute("SELECT COUNT(*) FROM series WHERE tmdbId IS NOT NULL").fetchone()[0]
        t_fail = cursor.execute("SELECT COUNT(*) FROM series WHERE failed = 1").fetchone()[0]

        set_update_state(is_running=True, task_name=task_display_name, total=t_all,
                         current=t_ok + t_fail, success=t_ok, fail=t_fail, clear_logs=True)
        emit_ui_log(f"{task_display_name} 작업을 시작합니다. (대상: {t_wait}개)", "info")

        if force_all and not target_name:
            conn.execute('UPDATE series SET failed=0 WHERE tmdbId IS NULL')
            conn.commit()

        # 2. 제목 정제 안 된 항목들 처리 (필터 적용)
        uncleaned_query = 'SELECT name, path FROM series WHERE cleanedName IS NULL AND tmdbId IS NULL AND failed = 0'
        uncleaned_params = []
        if target_name:
            uncleaned_query += ' AND (name LIKE ? OR cleanedName LIKE ?)'
            uncleaned_params.extend([f'%{target_name}%', f'%{target_name}%'])
        uncleaned_query += ' GROUP BY name'

        uncleaned_names_rows = conn.execute(uncleaned_query, uncleaned_params).fetchall()
        if uncleaned_names_rows:
            for idx, r in enumerate(uncleaned_names_rows):
                name = r['name']
                ct, yr = clean_title_complex(name, full_path=r['path'])
                cursor.execute('UPDATE series SET cleanedName=?, yearVal=? WHERE name=? AND cleanedName IS NULL',
                               (ct, yr, name))
                if (idx + 1) % 2000 == 0: conn.commit()
            conn.commit()

        # 3. 매칭 대상 그룹화 쿼리 수정 (핵심!)
        group_query = '''
            SELECT cleanedName, yearVal, category, MIN(name) as sample_name, GROUP_CONCAT(name, '|') as orig_names
            FROM series
            WHERE failed = 0
            AND cleanedName IS NOT NULL
        '''
        group_params = []
        if target_name:
            group_query += ' AND (name LIKE ? OR cleanedName LIKE ?)'
            group_params.extend([f'%{target_name}%', f'%{target_name}%'])
        else:
            group_query += ' AND (tmdbId IS NULL OR tmdbTitle IS NULL OR path IN (SELECT series_path FROM episodes WHERE season_number IS NULL))'

        group_query += ' GROUP BY cleanedName, yearVal, category'
        group_rows = conn.execute(group_query, group_params).fetchall()
        conn.close()

        tasks = []
        for gr in group_rows:
            tasks.append({
                'clean_title': gr['cleanedName'],
                'year': gr['yearVal'],
                'category': gr['category'],
                'sample_name': gr['sample_name'],
                'orig_names': gr['orig_names'].split('|')
            })

        total = len(tasks)

        def process_one(task):
            # 타겟팅 작업일 때는 캐시를 무시하고 새로 가져오도록 함 (강제 갱신 효과)
            info = get_tmdb_info_server(task['sample_name'], category=task['category'],
                                        ignore_cache=(target_name is not None))
            return (task, info)

        batch_size = 50
        total_success = 0
        total_fail = 0
        for i in range(0, total, batch_size):
            batch = tasks[i:i + batch_size]
            results = []
            with ThreadPoolExecutor(max_workers=10) as executor:
                future_to_task = {executor.submit(process_one, t): t for t in batch}
                for future in as_completed(future_to_task):
                    results.append(future.result())

            conn = get_db()
            cursor = conn.cursor()
            batch_success = 0
            batch_fail = 0
            for task, info in results:
                orig_names = task['orig_names']
                if info.get('failed'):
                    batch_fail += 1
                    cursor.executemany('UPDATE series SET failed=1 WHERE name=?', [(n,) for n in orig_names])
                else:
                    batch_success += 1
                    up = (
                        info.get('posterPath'), info.get('year'), info.get('overview'),
                        info.get('rating'), info.get('seasonCount'),
                        json.dumps(info.get('genreIds', [])),
                        json.dumps(info.get('genreNames', []), ensure_ascii=False),
                        info.get('director'),
                        json.dumps(info.get('actors', []), ensure_ascii=False),
                        info.get('tmdbId'),
                        info.get('title') or info.get('name')
                    )

                    cursor.executemany(
                        'UPDATE series SET posterPath=?, year=?, overview=?, rating=?, seasonCount=?, genreIds=?, genreNames=?, director=?, actors=?, tmdbId=?, tmdbTitle=?, failed=0 WHERE name=?',
                        [(*up, name) for name in orig_names])

                    if 'seasons_data' in info:
                        eps_to_update = []
                        for name in orig_names:
                            cursor.execute(
                                'SELECT id, title FROM episodes WHERE series_path IN (SELECT path FROM series WHERE name = ?)',
                                (name,))
                            eps_to_update.extend(cursor.fetchall())

                        ep_batch = []
                        for ep_row in eps_to_update:
                            sn, EN = extract_episode_numbers(ep_row['title'])
                            if EN:
                                ei = info['seasons_data'].get(f"{sn}_{EN}")
                                if ei:
                                    still_url = f"https://image.tmdb.org/t/p/original{ei.get('still_path')}" if ei.get(
                                        'still_path') else None
                                    ep_batch.append(
                                        (ei.get('overview'), ei.get('air_date'), sn, EN, still_url, ep_row['id']))
                        if ep_batch:
                            cursor.executemany(
                                'UPDATE episodes SET overview=?, air_date=?, season_number=?, episode_number=?, thumbnailUrl=COALESCE(?, thumbnailUrl) WHERE id=?',
                                ep_batch)

            conn.commit()
            conn.close()
            total_success += batch_success
            total_fail += batch_fail

            with UPDATE_LOCK:
                # 진행률 갱신
                UPDATE_STATE["current"] = (t_ok + total_success + total_fail)
                UPDATE_STATE["success"] = (t_ok + total_success)
                UPDATE_STATE["fail"] = (t_fail + total_fail)

        build_all_caches()
        set_update_state(is_running=False, current_item=f"매칭 완료 (+{total_success}건)")
    except:
        log("METADATA", f"⚠️ 에러 발생: {traceback.format_exc()}")
    finally:
        IS_METADATA_RUNNING = False

def get_chosung(text):
    CHOSUNG_LIST = ['ㄱ', 'ㄲ', 'ㄴ', 'ㄷ', 'ㄸ', 'ㄹ', 'ㅁ', 'ㅂ', 'ㅃ', 'ㅅ', 'ㅆ', 'ㅇ', 'ㅈ', 'ㅉ', 'ㅊ', 'ㅋ', 'ㅌ', 'ㅍ', 'ㅎ']
    if not text: return ""
    char_code = ord(text[0])
    if 0xAC00 <= char_code <= 0xD7A3:  # 한글인 경우
        return CHOSUNG_LIST[(char_code - 0xAC00) // 588]
    return text[0].upper()  # 영문/숫자는 대문자 첫글자

def get_sections_for_category(cat, kw=None):
    GENRE_MAP = {
        "Sci-Fi & Fantasy": "SF & 판타지",
        "Action & Adventure": "액션 & 어드벤처",
        "Science Fiction": "SF",
        "Animation": "애니메이션"
    }

    start_time = time.perf_counter()
    cache_key = f"sections_{cat}_{kw}"

    if cache_key in _SECTION_CACHE:
        return _SECTION_CACHE[cache_key]

    cat_data = _FAST_CATEGORY_CACHE.get(cat, {})
    if not cat_data: return []

    all_list = cat_data.get("all", [])
    folders = cat_data.get("folders", {})

    # 1. 필터링 로직
    if kw and kw in folders:
        target_list = folders[kw]
    elif kw and kw not in ["전체", "All"]:
        sk = nfc(kw).lower().strip()
        target_list = [i for i in all_list if sk in i.get('_search_name', '')]
    else:
        target_list = all_list

    if not target_list: return []

    sections = []

    # --- [핵심 추가: 방송중 > 외국 탭 전용 폴더별 그룹화 로직] ---
    if cat == 'air' and kw == '외국':
        sub_group_map = {}
        # 폴더명 매핑 테이블 (필요시 더 추가하세요)
        SUB_NAME_MAP = {
            "미드": "미국 드라마",
            "일드": "일본 드라마",
            "중드": "중국 드라마",
            "다큐": "다큐멘터리 영상",
            "기타": "기타 외국 영상",
            "영드": "영국 드라마"
        }

        for item in target_list:
            path_parts = item['path'].split('/')
            # path 구조: air/외국/미드/작품명/... -> 3번째 파트가 그룹명
            if len(path_parts) > 2:
                folder_key = path_parts[2]
                display_title = SUB_NAME_MAP.get(folder_key, f"{folder_key} 영상")
                sub_group_map.setdefault(display_title, []).append(item)

        # 그룹화된 데이터를 섹션으로 추가
        # 중요도 순서대로 정렬 (미드, 일드, 중드 순)
        sorted_keys = sorted(sub_group_map.keys(), key=lambda x: ("미국" in x, "일본" in x, "중국" in x), reverse=True)

        for title in sub_group_map.keys():
            sections.append({
                "title": title,
                "items": sub_group_map[title]
            })

        log("PERF", f"✅ {cat}>{kw} 폴더 기반 그룹화 섹션 구성 완료")
        _SECTION_CACHE[cache_key] = sections
        return sections

    # --- [외국 탭 전용 로직 끝] ---

    # 2. 카테고리/탭별 감성 테마명 설정 (기존 로직 유지)
    def get_attractive_title(category, keyword, section_type):
        titles = {
            "recommend": {
                "movies": ["지금 봐야 할 인생 영화", "놓치면 아까운 명작 컬렉션", "별점이 증명하는 추천 영화"],
                "animations_all": ["화제의 애니메이션", "정주행을 부르는 대작 애니", "덕심 자극! 인기 애니"],
                "koreantv": ["한국인이 사랑한 대작 드라마", "한 번 시작하면 멈출 수 없는 드라마"],
                "foreigntv": ["전 세계가 열광한 시리즈", "최고의 몰입감! 해외 드라마"],
                "air": ["현재 가장 뜨거운 실시간 방영작"],
                "default": [f"엄선된 추천작"]
            },
            "genre": [f"인기 {{}} 장르", f"세대를 아우르는 {{}} 명작"]
        }

        if section_type == "recommend":
            pick_list = titles["recommend"].get(category, titles["recommend"]["default"])
            return random.choice(pick_list)
        return random.choice(titles["genre"])

    # [테마 1] 감성적인 추천 섹션
    if len(target_list) > 20:
        sections.append({
            "title": get_attractive_title(cat, kw, "recommend"),
            "items": random.sample(target_list, min(40, len(target_list)))
        })

    # [테마 2] 장르별 베스트
    current_genre_map = {}
    for item in target_list:
        for g_raw in item.get('genreNames', []):
            g_name = GENRE_MAP.get(g_raw, g_raw)
            if g_name not in ["애니메이션", "TV 영화"]:
                current_genre_map.setdefault(g_name, []).append(item)

    sorted_genres = sorted(current_genre_map.keys(), key=lambda x: len(current_genre_map[x]), reverse=True)
    for g in sorted_genres[:3]:
        if len(current_genre_map[g]) >= 5:
            base_genre_title = get_attractive_title(cat, kw, "genre")
            sections.append({
                "title": base_genre_title.format(g),
                "items": random.sample(current_genre_map[g], min(50, len(current_genre_map[g])))
            })

    # [테마 3] 전체 목록 복원 + 초성 인덱싱
    display_limit = 3000
    full_list = target_list[:display_limit]
    for item in full_list:
        item['chosung'] = get_chosung(item.get('name', ''))

    sections.append({
        "title": "전체목록",
        "items": full_list,
        "is_full_list": True
    })

    _SECTION_CACHE[cache_key] = sections
    return sections


@app.route('/category_sections')
def get_category_sections():
    req_start = time.perf_counter()
    cat = request.args.get('cat', 'movies')
    kw = request.args.get('kw', '')

    log("API", f"🚀 요청 수신: {cat} > {kw}")

    # 1. 로직 처리 시간
    sections = get_sections_for_category(cat, kw)
    logic_time = time.perf_counter() - req_start

    # 2. JSON 변환 및 압축 시간 측정
    gzip_start = time.perf_counter()
    resp = gzip_response(sections)
    gzip_time = time.perf_counter() - gzip_start

    # 3. 데이터 크기 측정
    raw_size = len(json.dumps(sections))
    gz_size = len(resp.data)

    log("PERF", f"📊 [데이터 통계] 원본: {raw_size / 1024 / 1024:.2f}MB -> 압축: {gz_size / 1024 / 1024:.2f}MB")
    log("PERF", f"⌛ [최종 요약] 로직: {logic_time:.3f}초 | 압축: {gzip_time:.3f}초 | 합계: {time.perf_counter() - req_start:.3f}초")

    return resp


@app.route('/home')
def get_home():
    return gzip_response(HOME_RECOMMEND)

@app.route('/list')
def get_list():
    p = request.args.get('path', '')
    m = {"영화": "movies", "외국TV": "foreigntv", "국내TV": "koreantv", "애니메이션": "animations_all", "방송중": "air"}
    cat = "movies"
    for label, code in m.items():
        if label in p: cat = code; break

    cat_data = _FAST_CATEGORY_CACHE.get(cat, {})
    folders = cat_data.get("folders", {})
    bl = cat_data.get("all", [])

    kw = request.args.get('keyword')

    # 🔴 [최적화 4] 폴더명 직접 매칭
    if kw and kw in folders:
        res = folders[kw]
    elif kw and kw not in ["전체", "All"]:
        sk = nfc(kw).lower()
        res = [i for i in bl if sk in i.get('_search_name', i['name'].lower())]
    else:
        res = bl

    lim = int(request.args.get('limit', 1000))
    off = int(request.args.get('offset', 0))

    return gzip_response(res[off:off + lim])



# 상세페이지 결과 캐시를 위한 전역 변수 (함수 밖에 위치)
_DETAIL_MEM_CACHE = {}


@app.route('/api/series_detail')
def get_series_detail_api():
    try:
        path_raw = request.args.get('path', '')
        # 1. URL 디코딩 및 정규화
        path = nfc(urllib.parse.unquote_plus(path_raw))
        if not path: return gzip_response({})

        print(f"\n[DETAIL_DIAG] ====================================", flush=True)
        print(f"[DETAIL_DIAG] 1. 요청 경로: {path}", flush=True)

        if path in _DETAIL_MEM_CACHE:
            return gzip_response(_DETAIL_MEM_CACHE[path])

        conn = get_db()
        row = conn.execute('SELECT * FROM series WHERE path = ?', (path,)).fetchone()
        if not row:
            row = conn.execute('SELECT * FROM series WHERE path = ?', (nfd(path),)).fetchone()

        if not row:
            conn.close()
            return gzip_response({})

        series_data = dict(row)
        t_id = series_data.get('tmdbId')
        cat = series_data.get('category')
        c_name = nfc(series_data.get('cleanedName'))
        db_path = series_data.get('path')  # DB에 저장된 실제 경로

        # 🟢 [근본 해결 1] 영화와 시리즈 로직 분리
        all_refined_eps = []

        if cat == 'movies':
            # 🎥 영화 모드: 복잡한 폴더 탐색 없이 이 파일(db_path)에 매핑된 에피소드만 1:1로 가져옴
            print(f"[DETAIL_DIAG] 2. 영화 모드: 단일 파일 매칭 시도", flush=True)
            # query = "SELECT * FROM episodes WHERE series_path = ? OR series_path = ?"
            query = """
                SELECT e.*, p.position, p.duration
                FROM episodes e
                LEFT JOIN playback_progress p ON e.id = p.episode_id
                WHERE e.series_path = ? OR e.series_path = ?
            """
            rows = conn.execute(query, (db_path, nfd(db_path))).fetchall()

            # 🚨 [긴급 패치] 만약 episodes 테이블에서 데이터가 유실되었다면, 즉석에서 가짜 에피소드를 생성해준다.
            if not rows:
                print(f"[DETAIL_DIAG] 2-1. 에피소드 테이블에서 찾지 못함! 가짜 에피소드 생성", flush=True)
                fake_id = hashlib.md5(db_path.encode()).hexdigest()
                # 'movies/' prefix 제거 (실제 파일 경로)
                rel = db_path.replace("movies/", "", 1) if db_path.startswith("movies/") else db_path
                fake_row = {
                    "id": fake_id,
                    "series_path": db_path,
                    "title": series_data.get('name') or c_name,
                    "videoUrl": f"/video_serve?type=movie&path={urllib.parse.quote(rel)}",
                    "thumbnailUrl": f"/thumb_serve?type=movie&id={fake_id}&path={urllib.parse.quote(rel)}",
                    "overview": series_data.get('overview'),
                    "air_date": None,
                    "season_number": 1,
                    "episode_number": 1
                }
                rows = [fake_row]
        else:
            # 📺 TV/애니메이션 모드: 기존의 부모 폴더 기반 그룹화 수행
            path_parts = db_path.split('/')
            if len(path_parts) > 1:
                parent_dir = "/".join(path_parts[:-1])
                folder_name = path_parts[-2]
                is_season_pattern = re.search(r'(?i)(?:시즌|Season|S)\s*\d+|(\d+)\s*기|X파일\s*\d+|파트\s*\d+|Part\s*\d+',
                                              folder_name)
                if is_season_pattern and len(path_parts) > 2:
                    parent_dir = "/".join(path_parts[:-2])
            else:
                parent_dir = ""

            print(f"[DETAIL_DIAG] 2. 시리즈 모드 루트: {parent_dir}", flush=True)
            # query = "SELECT * FROM episodes WHERE series_path LIKE ? AND series_path IN (SELECT path FROM series WHERE cleanedName = ?)"
            query = """
                SELECT e.*, p.position, p.duration
                FROM episodes e
                LEFT JOIN playback_progress p ON e.id = p.episode_id
                WHERE e.series_path LIKE ? AND e.series_path IN (SELECT path FROM series WHERE cleanedName = ?)
            """
            rows = conn.execute(query, (f"{parent_dir}%", c_name)).fetchall()

        print(f"[DETAIL_DIAG] 3. 에피소드 발견: {len(rows)}개", flush=True)

        for r in rows:
            d = dict(r)
            ep_path = nfc(d['series_path'])

            # 고화질 버전 분리 로직 (시리즈일 때만 작동)
            season_display_name = "1시즌"
            sort_weight = 1

            if cat != 'movies':
                # 시리즈의 경우 폴더 구조 분석하여 탭 분리
                rel_sub_path = ep_path.replace(nfc(parent_dir), "").strip("/")
                sub_parts = rel_sub_path.split("/")
                sn, en = d.get('season_number'), d.get('episode_number')
                if sn is None: sn, en = extract_episode_numbers(d.get('title', ''))

                season_display_name = f"{sn or 1}시즌"
                sort_weight = sn or 1

                if len(sub_parts) > 1:
                    sub_folder_name = sub_parts[-1] if "." not in sub_parts[-1] else sub_parts[-2]
                    if any(k in sub_folder_name.upper() for k in ["4K", "UHD", "고화질", "BD", "BLURAY"]):
                        season_display_name = f"고화질 버전 ({sub_folder_name})"
                        sort_weight = 900 + (sn or 0)
            else:
                # 영화는 무조건 1시즌/1화로 고정 (탭 이름을 '영화'로)
                season_display_name = "영화"
                sn, en = 1, 1

            # rows를 순회하며 데이터를 담는 부분에 추가
            all_refined_eps.append({
                "id": str(d.get('id')),
                "title": d.get('title'),
                "videoUrl": d.get('videoUrl'),
                "thumbnailUrl": d.get('thumbnailUrl'),
                "overview": d.get('overview'),
                "air_date": d.get('air_date'),
                "season_number": sn,
                "episode_number": en,
                "display_season": season_display_name,
                "sort_weight": sort_weight,
                # --- 아래 두 필드 추가 ---
                # 🔴 None(NULL)일 경우 확실하게 0을 반환하도록 수정
                "position": d['position'] if d['position'] is not None else 0,
                "duration": d['duration'] if d['duration'] is not None else 0,
                "runtime": d.get('runtime')  # <--- 이 줄을 추가 (DB나 TMDB 데이터에서 가져온 값)
            })

        # 3. 정렬 및 시즌 맵 생성
        sorted_eps = sorted(all_refined_eps, key=lambda x: (x['sort_weight'], x['episode_number'] or 0))
        seasons_map = {}
        for ep in sorted_eps:
            sk = ep['display_season']
            if sk not in seasons_map: seasons_map[sk] = []
            seasons_map[sk].append(ep)

        # 🟢 [근본 해결 2] 앱 모델 필드명(episodes) 일치화
        clean_main_name = nfc(series_data.get('tmdbTitle') or series_data.get('cleanedName') or series_data.get('name'))
        is_dub = "더빙" in nfc(db_path + series_data['name']).lower()
        is_sub = "자막" in nfc(db_path + series_data['name']).lower()

        response_data = {
            **series_data,
            "name": f"{clean_main_name} {'[더빙]' if is_dub else '[자막]' if is_sub else ''}".strip(),
            "episodes": sorted_eps,  # 상세페이지 버튼용
            "movies": sorted_eps,  # 홈 미리보기/하위호환용
            "seasons": seasons_map,
            "genreIds": json.loads(series_data.get('genreIds', '[]')) if series_data.get('genreIds') else [],
            "genreNames": json.loads(series_data.get('genreNames', '[]')) if series_data.get('genreNames') else [],
            "actors": json.loads(series_data.get('actors', '[]')) if series_data.get('actors') else []
        }

        conn.close()
        print(f"[DETAIL_DIAG] 4. 최종 결과: 에피소드 {len(sorted_eps)}개, 시즌 {len(seasons_map)}개", flush=True)
        print(f"[DETAIL_DIAG] ====================================\n", flush=True)

        _DETAIL_MEM_CACHE[path] = response_data
        return gzip_response(response_data)

    except Exception as e:
        print(f"[DETAIL_DIAG ERROR] {traceback.format_exc()}", flush=True)
        return gzip_response({"error": str(e)})

def pre_generate_individual_task(ep_thumb_url):
    try:
        u = urllib.parse.urlparse(ep_thumb_url)
        q = urllib.parse.parse_qs(u.query)
        if 'path' in q and 'type' in q and 'id' in q:
            _generate_thumb_file(q['path'][0], q['type'][0], q['id'][0], q.get('t', ['300'])[0], q.get('w', ['320'])[0])
    except:
        pass


@app.route('/search')
def search_videos():
    try:
        q = request.args.get('q', '').strip()
        cat_filter = request.args.get('cat', '전체')
        if not q: return jsonify([])

        q_nfc = nfc(q)
        log("SEARCH", f"🔍 검색어: '{q_nfc}' (필터: {cat_filter})")

        conn = get_db()
        cat_map = {"영화": "movies", "외국TV": "foreigntv", "국내TV": "koreantv", "애니메이션": "animations_all", "방송중": "air"}
        target_cat = cat_map.get(cat_filter)

        # 1. WHERE 조건
        query = """
            SELECT * FROM series
            WHERE (name LIKE ? OR name LIKE ? OR cleanedName LIKE ? OR cleanedName LIKE ? OR tmdbTitle LIKE ? OR tmdbTitle LIKE ?)
            AND posterPath IS NOT NULL
            AND EXISTS (SELECT 1 FROM episodes WHERE series_path = series.path)
            -- [추가] 국내TV 내부의 애니메이션 폴더 결과 제외
            AND path NOT LIKE 'koreantv/애니메이션/%'
        """
        params = [f"%{q_nfc}%", f"%{nfd(q)}%", f"%{q_nfc}%", f"%{nfd(q)}%", f"%{q_nfc}%", f"%{nfd(q)}%"]

        if target_cat:
            query += " AND category = ?"
            params.append(target_cat)

        # 2. GROUP BY: 태그를 기준으로 그룹을 나누어 데이터 누락 방지
        query += """
            GROUP BY
                category,
                tmdbId,
                cleanedName,
                CASE
                    WHEN (path LIKE '%더빙%' OR name LIKE '%더빙%') THEN '더빙'
                    WHEN (path LIKE '%자막%' OR name LIKE '%자막%') THEN '자막'
                    ELSE ''
                END
            ORDER BY
                CASE
                    WHEN name LIKE ? THEN 1
                    WHEN tmdbTitle LIKE ? THEN 2
                    ELSE 3
                END, name ASC
        """
        params.extend([f'{q_nfc}%', f'{q_nfc}%'])

        cursor = conn.execute(query, params)
        rows = []
        for row in cursor.fetchall():
            item = dict(row)

            # 앱 표시용 기본 이름 (TMDB 제목 우선)
            base_name = nfc(item.get('tmdbTitle') or item.get('cleanedName') or item.get('name'))

            # --- [태그 복원 로직 개선 및 로그 추가] ---
            # 검사할 텍스트를 path와 name 모두 합쳐서 생성
            orig_path = nfc(item.get('path', ''))
            orig_name = nfc(item.get('name', ''))
            full_check_text = (orig_path + " " + orig_name).lower()

            detected_tags = []
            if "더빙" in full_check_text: detected_tags.append("더빙")
            if "자막" in full_check_text: detected_tags.append("자막")
            if "극장판" in full_check_text or item.get('category') == 'movies':
                if "극장판" not in detected_tags: detected_tags.append("극장판")
            if "ova" in full_check_text: detected_tags.append("OVA")

            # 상세 로그 (태그가 안 나올 때 원인 파악용)
            if "코난" in base_name:
                log("TAG_DEBUG",
                    f"작품: {base_name} | 검출태그: {detected_tags} | 원본Name: {orig_name[:30]}... | Path포함여부(자막): {'자막' in full_check_text}")

            # [태그] 문자열 생성
            tag_str = "".join([f" [{t}]" for t in detected_tags])

            processed = {
                "name": f"{base_name}{tag_str}".strip(),
                "path": item['path'],
                "category": item['category'],
                "posterPath": item['posterPath'] or "",
                "year": item['year'] or "",
                "overview": (item['overview'] or "")[:200],
                "genreIds": [], "genreNames": [], "director": item.get('director') or "",
                "rating": item.get('rating') or "",
                "tmdbTitle": item.get('tmdbTitle') or "",
                "tmdbId": item.get('tmdbId') or "",
                "actors": [], "movies": [], "seasons": {}
            }

            # JSON 필드 복구
            for col in ['genreIds', 'genreNames', 'actors']:
                try:
                    if item.get(col): processed[col] = json.loads(item[col])
                except:
                    processed[col] = []

            rows.append(processed)

        conn.close()
        return gzip_response(rows)
    except Exception as e:
        log("SEARCH_ERROR", f"에러: {str(e)}")
        return jsonify([])


@app.route('/rescan_broken')
def rescan_broken():
    threading.Thread(target=perform_full_scan, daemon=True).start()
    return jsonify({"status": "success"})


@app.route('/db_cleanup')
def db_cleanup():
    """파일 스캔 없이 DB에서 화이트리스트 외의 데이터를 즉시 삭제합니다."""
    conn = get_db()
    cursor = conn.cursor()

    # 카테고리별 허용된 폴더가 아니면 DB에서 삭제
    for cat, folders in WHITELISTS.items():
        if not folders: continue

        # SQL 조건 생성: path가 'koreantv/드라마/%' 형식이 아닌 것들을 찾음
        # Path 예시: "koreantv/애니메이션/프리렌.mp4" -> 삭제 대상
        conditions = [f"path NOT LIKE '{cat}/{f}/%'" for f in folders]
        where_clause = " AND ".join(conditions)

        query = f"DELETE FROM series WHERE category = ? AND ({where_clause})"
        cursor.execute(query, (cat,))
        log("CLEANUP", f"'{cat}' 카테고리 정리 완료")

    # 연결된 에피소드(회차) 정보도 함께 정리
    cursor.execute("DELETE FROM episodes WHERE series_path NOT IN (SELECT path FROM series)")

    conn.commit()
    conn.close()

    # 메모리 캐시 즉시 갱신
    build_all_caches()
    return jsonify({"status": "success", "message": "DB cleanup completed instantly without scanning files."})


@app.route('/rematch_metadata')
def rescan_metadata():
    if IS_METADATA_RUNNING:
        return jsonify({"status": "error", "message": "Metadata process is already running."})
    threading.Thread(target=fetch_metadata_async, args=(True,), daemon=True).start()
    return jsonify({"status": "success", "message": "Scanning for new or failed metadata in background."})


@app.route('/retry_failed_metadata')
def retry_failed_metadata():
    if IS_METADATA_RUNNING:
        return jsonify({"status": "error", "message": "Metadata process is already running."})

    # [추가] 재시도 시 이전 진단 로그 초기화 (Admin 페이지 로딩 문제 해결)
    MATCH_DIAGNOSTICS.clear()

    conn = get_db()
    conn.execute('UPDATE series SET failed = 0 WHERE failed = 1')
    conn.commit()
    conn.close()
    # [수정] 강제 업데이트를 위해 daemon=False로 실행하여 확실히 완료되도록 함
    threading.Thread(target=fetch_metadata_async, args=(False,), daemon=False).start()
    return jsonify(
        {"status": "success", "message": "Retrying failed metadata and updating matched series with stills."})


@app.route('/backup_metadata')
def backup_metadata():
    try:
        log("BACKUP", "메타데이터 백업 시작...")
        conn = get_db()
        # tmdbId가 있는(성공한) 항목만 조회
        cursor = conn.execute('SELECT * FROM series WHERE tmdbId IS NOT NULL')
        rows = cursor.fetchall()
        conn.close()

        backup_data = []
        for row in rows:
            item = dict(row)
            # JSON 문자열로 저장된 필드들을 실제 객체로 변환하여 저장 (가독성/재사용성 위해)
            for key in ['genreIds', 'genreNames', 'actors']:
                if item.get(key):
                    try:
                        item[key] = json.loads(item[key])
                    except:
                        pass
            backup_data.append(item)

        timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
        filename = f"metadata_backup_{timestamp}.json"
        save_path = os.path.join(SCRIPT_DIR, filename)

        with open(save_path, 'w', encoding='utf-8') as f:
            json.dump(backup_data, f, ensure_ascii=False, indent=2)

        log("BACKUP", f"백업 완료: {save_path} ({len(backup_data)}건)")
        return jsonify({"status": "success", "file": save_path, "count": len(backup_data)})
    except Exception as e:
        log("BACKUP_ERROR", str(e))
        return jsonify({"status": "error", "message": str(e)})


@app.route('/apply_tmdb_thumbnails')
def apply_tmdb_thumbnails():
    threading.Thread(target=run_apply_thumbnails, daemon=True).start()
    return jsonify({"status": "success", "message": "Background task started: Applying TMDB thumbnails to episodes."})


def run_apply_thumbnails():
    log("THUMB_SYNC", "🔄 TMDB 고화질 스틸컷/배경(original) 일괄 적용 시작")

    conn = get_db()
    # 전체 매칭된 작품 목록 가져오기
    query = """
        SELECT DISTINCT s.path, s.name, s.category, s.tmdbId
        FROM series s
        JOIN episodes e ON s.path = e.series_path
        WHERE s.tmdbId IS NOT NULL
    """
    series_rows = [dict(r) for r in conn.execute(query).fetchall()]
    conn.close()

    total = len(series_rows)
    set_update_state(is_running=True, task_name="TMDB 썸네일 고화질 교체", total=total, current=0, success=0, fail=0, clear_logs=True)

    updated_count = 0
    for idx, s_row in enumerate(series_rows):
        path, name = s_row['path'], s_row['name']
        with UPDATE_LOCK:
            UPDATE_STATE["current"] += 1
            UPDATE_STATE["current_item"] = name

        try:
            tmdb_id_full = s_row['tmdbId']
            if tmdb_id_full and ':' in tmdb_id_full:
                m_type, t_id = tmdb_id_full.split(':')
                hint_name = f"{{tmdb-{t_id}}} {name}"
                # TMDB 상세 정보 가져오기
                info = get_tmdb_info_server(hint_name, category=s_row['category'], ignore_cache=False)

                if info and not info.get('failed'):
                    u_conn = get_db()
                    eps = u_conn.execute("SELECT id, title FROM episodes WHERE series_path = ?", (path,)).fetchall()
                    ep_batch = []

                    # --- [CASE 1: TV 시리즈] 시즌/에피소드별 스틸컷 적용 ---
                    if m_type == 'tv' and 'seasons_data' in info:
                        for ep in eps:
                            sn, en = extract_episode_numbers(ep['title'])
                            if en:
                                key = f"{sn}_{en}"
                                if key in info['seasons_data']:
                                    still = info['seasons_data'][key].get('still_path')
                                    if still:
                                        # 최상의 화질을 위해 original 사용
                                        new_url = f"https://image.tmdb.org/t/p/original{still}"
                                        ep_batch.append((new_url, ep['id']))

                    # --- [CASE 2: 영화] 영화 배경(Backdrop) 이미지 적용 ---
                    elif m_type == 'movie':
                        # 영화는 에피소드(파일)가 하나이거나 폴더 내 여러개일 수 있음
                        # 영화의 대표 배경 이미지를 가져옴
                        backdrop = info.get('posterPath') # 혹은 info 내 backdrop_path
                        if backdrop:
                            # 최상의 화질을 위해 original 사용
                            new_url = f"https://image.tmdb.org/t/p/original{backdrop}"
                            for ep in eps:
                                ep_batch.append((new_url, ep['id']))

                    if ep_batch:
                        u_conn.executemany("UPDATE episodes SET thumbnailUrl = ? WHERE id = ?", ep_batch)
                        u_conn.commit()
                        updated_count += len(ep_batch)
                        with UPDATE_LOCK: UPDATE_STATE["success"] += len(ep_batch)
                        emit_ui_log(f"'{name}' 고화질 교체 완료 ({len(ep_batch)}개)", "success")
                    else:
                        emit_ui_log(f"'{name}' 건너뜀 (이미지 정보 없음)", "info")
                    u_conn.close()
                else:
                    emit_ui_log(f"'{name}' TMDB 정보 조회 실패", "warning")
        except Exception as e:
            log("THUMB_SYNC_ERROR", f"Error: {e}")

    set_update_state(is_running=False, current_item=f"고화질 교체 완료 (총 {updated_count}개)")
    build_all_caches()
    emit_ui_log(f"모든 작업이 완료되었습니다. 총 {updated_count}개의 썸네일이 고화질로 교체되었습니다.", "success")


FFMPEG_PROCS = {}


def kill_old_processes(sid):
    if sid in FFMPEG_PROCS:
        try:
            FFMPEG_PROCS[sid].terminate()
            FFMPEG_PROCS[sid].wait()
        except:
            pass
        del FFMPEG_PROCS[sid]
    sdir = os.path.join(HLS_ROOT, sid)
    if os.path.exists(sdir):
        try:
            shutil.rmtree(sdir)
        except:
            pass

@app.route('/video_serve')
def video_serve():
    path_raw, prefix = request.args.get('path'), request.args.get('type')
    try:
        # PATH_MAP에서 기준 경로 찾기
        base = next(v[0] for k, v in PATH_MAP.items() if v[1] == prefix)

        # 🟢 [개선] unquote_plus를 사용하여 + 기호와 %20 모두 공백으로 정확히 변환
        decoded_path = urllib.parse.unquote_plus(path_raw)
        full_path = get_real_path(os.path.join(base, nfc(decoded_path)))

        if not os.path.exists(full_path):
            log("VIDEO", f"❌ 파일을 찾을 수 없음: {full_path}")
            return "Not Found", 404

        ua = request.headers.get('User-Agent', '').lower()
        is_ios = any(x in ua for x in ['iphone', 'ipad', 'apple', 'avfoundation'])

        # 안드로이드/ExoPlayer는 iOS 로직에서 제외
        if 'android' in ua or 'exoplayer' in ua:
            is_ios = False

        # --- [iOS용 HLS 스트리밍] ---
        file_ext = full_path.lower()
        if is_ios and not file_ext.endswith(('.mp4', '.m4v', '.mov')):
            sid = hashlib.md5(full_path.encode()).hexdigest()
            kill_old_processes(sid)
            sdir = os.path.join(HLS_ROOT, sid)
            os.makedirs(sdir, exist_ok=True)
            video_m3u8 = os.path.join(sdir, "video.m3u8")

            if not os.path.exists(video_m3u8):
                cmd = [FFMPEG_PATH, '-y', '-i', full_path,
                       '-c:v', 'copy', '-c:a', 'aac', '-b:a', '192k',
                       '-f', 'hls', '-hls_time', '6', '-hls_list_size', '0', video_m3u8]
                FFMPEG_PROCS[sid] = subprocess.Popen(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                for _ in range(40):
                    if os.path.exists(video_m3u8): break
                    time.sleep(0.5)
            # return redirect(f"http://{MY_IP}:5000/hls/{sid}/video.m3u8")
            return redirect(f"https://{MY_IP}:9811/hls/{sid}/video.m3u8")

        # --- [안드로이드 TV/PC용 소리 및 코덱 해결 로직] ---
        # 🟢 FLAC 오디오나 고스펙 영상으로 인해 재생이 안 될 경우
        # 주소창에 &transcode=true를 붙여 호출하면 오디오를 AAC로 실시간 변환하여 전송합니다.
        force_transcode = request.args.get('transcode') == 'true'

        if force_transcode:
            log("VIDEO", f"🔊 오디오 실시간 AAC 변환 시작: {os.path.basename(full_path)}")

            def generate_transcoded():
                # -c:v copy (영상 원본 유지), -c:a aac (소리만 변환)
                # 만약 영상 자체가 안 나오는 거라면 -c:v libx264 로 바꿔야 하지만 부하가 큽니다.
                cmd = [
                    FFMPEG_PATH, "-i", full_path,
                    "-c:v", "copy", "-c:a", "aac", "-b:a", "256k",
                    "-f", "matroska", "pipe:1"
                ]
                proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
                try:
                    while True:
                        chunk = proc.stdout.read(1024 * 64)  # 64KB씩 전송
                        if not chunk: break
                        yield chunk
                finally:
                    proc.kill()

            return Response(generate_transcoded(), mimetype='video/x-matroska')

        # 기본값: 원본 파일 전송 (가장 빠름)
        return send_file(full_path, conditional=True)

    except Exception as e:
        log("VIDEO_ERROR", f"재생 중 에러 발생: {str(e)}")
        return "Internal Server Error", 500


@app.route('/hls/<sid>/<filename>')
def serve_hls(sid, filename):
    return send_from_directory(os.path.join(HLS_ROOT, sid), filename)


# --- [새로운 고속 미리보기 엔드포인트] ---
@app.route('/preview_serve')
def preview_serve():
    """
    영상 미리보기용 고속 스트리밍:
    원본 영상의 특정 지점부터 저해상도(480p)로 빠르게 인코딩하여 스트리밍합니다.
    """
    path_raw, prefix = request.args.get('path'), request.args.get('type')
    try:
        base = next(v[0] for k, v in PATH_MAP.items() if v[1] == prefix)
        vp = get_real_path(os.path.join(base, nfc(urllib.parse.unquote(path_raw))))
        if not os.path.exists(vp): return "Not Found", 404

        # 미리보기 시작 지점 설정 (파일 크기나 길이에 따라 조절 가능, 여기선 1분 지점 선호)
        start_time = "60"

        # FFmpeg을 사용하여 실시간 다운스케일링 스트리밍
        # -ss를 앞에 두어 고속 탐색, -t 30으로 30초만 추출
        cmd = [
            FFMPEG_PATH, "-ss", start_time, "-i", vp,
            "-t", "30", "-vf", "scale=640:-2",
            "-vcodec", "libx264", "-preset", "ultrafast", "-tune", "zerolatency",
            "-crf", "28", "-c:a", "aac", "-b:a", "128k", "-f", "matroska", "pipe:1"  # 🔴 -an(음소거) 제거 후 AAC 오디오 추가
        ]

        def generate():
            proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
            try:
                while True:
                    chunk = proc.stdout.read(65536)
                    if not chunk: break
                    yield chunk
            finally:
                proc.kill()

        return Response(generate(), mimetype='video/x-matroska')
    except:
        return "Error", 500


@app.route('/api/fast_korean_titles')
def fast_korean_titles():
    """TMDB ID를 사용하여 중복 없이 광속으로 한글 제목을 가져옵니다."""

    def run_sync():
        set_update_state(is_running=True, task_name="ID 기반 고속 한글화", total=0, current=0, success=0, fail=0,
                         clear_logs=True)
        emit_ui_log("DB에 저장된 ID를 기반으로 한글화를 시작합니다...", "info")

        conn = get_db()
        # 1. 한글 제목이 없는 고유한 TMDB ID 목록을 가져옵니다.
        id_rows = conn.execute(
            "SELECT tmdbId, category FROM series WHERE tmdbId IS NOT NULL AND tmdbTitle IS NULL GROUP BY tmdbId").fetchall()
        total_ids = len(id_rows)
        set_update_state(total=total_ids)

        emit_ui_log(f"분석 완료: 총 {total_ids}개의 작품을 업데이트해야 합니다.", "info")

        headers = {"Authorization": f"Bearer {TMDB_API_KEY}"}
        success_count = 0

        for idx, row in enumerate(id_rows):
            try:
                full_id = row['tmdbId']  # 예: "movie:123"
                if ':' not in full_id: continue
                m_type, t_id = full_id.split(':')

                # 2. TMDB에 ID로 직접 물어봅니다 (검색보다 100배 정확하고 빠름)
                r = requests.get(f"{TMDB_BASE_URL}/{m_type}/{t_id}?language=ko-KR", headers=headers, timeout=10)
                if r.status_code == 200:
                    data = r.json()
                    ko_title = data.get('title') or data.get('name')

                    if ko_title:
                        # 3. 해당 ID를 가진 모든 에피소드를 한꺼번에 업데이트!
                        conn.execute("UPDATE series SET tmdbTitle = ? WHERE tmdbId = ?", (ko_title, full_id))
                        success_count += 1

                if (idx + 1) % 10 == 0:  # 10개마다 저장
                    conn.commit()
                    set_update_state(current=idx + 1, success=success_count)

                time.sleep(0.1)  # TMDB 차단 방지용 미세 지연
            except Exception as e:
                log("SYNC_ERROR", f"ID {full_id} 처리 중 에러: {e}")
                continue

        conn.commit()
        conn.close()
        build_all_caches()
        set_update_state(is_running=False, current_item="모든 작업 완료!")
        emit_ui_log(f"축하합니다! 총 {success_count}개 작품(수십만 에피소드)의 한글화가 완료되었습니다.", "success")

    threading.Thread(target=run_sync, daemon=True).start()
    return jsonify({"status": "success", "message": "High-speed ID sync started."})

# --- [관리자 및 진단 로직 추가] ---
@app.route('/admin')
def admin_page():
    return """
    <html>
    <head>
        <title>NAS Player Admin - Metadata Failures</title>
        <style>
            body { font-family: sans-serif; background: #141414; color: white; padding: 20px; }
            table { width: 100%; border-collapse: collapse; margin-top: 20px; }
            th, td { padding: 12px; text-align: left; border-bottom: 1px solid #333; }
            th { background: #222; }
            .candidate { font-size: 0.85em; color: #aaa; margin-bottom: 5px; }
            .score { color: #46D369; font-weight: bold; }
            input { padding: 5px; border-radius: 4px; border: 1px solid #444; background: #222; color: white; }
            button { padding: 5px 10px; background: #E50914; color: white; border: none; border-radius: 4px; cursor: pointer; }
            button:hover { background: #b20710; }
            .pagination { margin-top: 20px; text-align: center; }
            .pagination button { margin: 0 5px; }
        </style>
    </head>
    <body>
        <h1>메타데이터 매칭 실패 진단 및 수동 수정</h1>
        <div id="content">로딩 중...</div>
        <div class="pagination">
            <button onclick="prevPage()">이전</button>
            <span id="pageInfo" style="margin: 0 10px;"></span>
            <button onclick="nextPage()">다음</button>
        </div>
        <script>
            let currentOffset = 0;
            const LIMIT = 50;
            let totalCount = 0;

            async function loadFailures() {
                const resp = await fetch(`/api/admin/diagnostics?offset=${currentOffset}&limit=${LIMIT}`);
                const data = await resp.json();
                totalCount = data.total;

                let html = '<table><tr><th>원본 파일명</th><th>정제된 제목</th><th>TMDB 후보군 (점수)</th><th>수동 매칭 (Type:ID)</th></tr>';
                for (const [orig, info] of Object.entries(data.items)) {
                    let candHtml = info.candidates.map(c =>
                        `<div class="candidate">${c.title} (${c.year}) - <span class="score">${c.score}점</span> [${c.type}]</div>`
                    ).join('') || '후보 없음';

                    html += `<tr>
                        <td>${orig}</td>
                        <td>${info.cleaned} (${info.year || ''})</td>
                        <td>${candHtml}</td>
                        <td>
                            <input type="text" id="id_${btoa(orig)}" placeholder="movie:123 or tv:456">
                            <button onclick="manualMatch('${orig}')">적용</button>
                        </td>
                    </tr>`;
                }
                html += '</table>';
                document.getElementById('content').innerHTML = html;
                document.getElementById('pageInfo').innerText = `${currentOffset + 1} ~ ${Math.min(currentOffset + LIMIT, totalCount)} / 총 ${totalCount}건`;
            }

            function prevPage() {
                if (currentOffset - LIMIT >= 0) {
                    currentOffset -= LIMIT;
                    loadFailures();
                }
            }

            function nextPage() {
                if (currentOffset + LIMIT < totalCount) {
                    currentOffset += LIMIT;
                    loadFailures();
                }
            }

            async function manualMatch(orig) {
                const val = document.getElementById('id_' + btoa(orig)).value;
                if (!val.includes(':')) { alert('형식 오류! movie:ID 또는 tv:ID 로 입력하세요.'); return; }
                const [type, id] = val.split(':');
                const resp = await fetch('/api/admin/manual_match', {
                    method: 'POST',
                    headers: {'Content-Type': 'application/json'},
                    body: JSON.stringify({orig_name: orig, type: type, tmdb_id: id})
                });
                const res = await resp.json();
                if (res.status === 'success') { alert('수정 완료!'); loadFailures(); }
                else { alert('에러: ' + res.message); }
            }
            loadFailures();
        </script>
    </body>
    </html>
    """


@app.route('/api/admin/diagnostics')
def get_diagnostics():
    offset = int(request.args.get('offset', 0))
    limit = int(request.args.get('limit', 50))

    all_items = list(MATCH_DIAGNOSTICS.items())
    total_count = len(all_items)
    paged_items = all_items[offset: offset + limit]

    return jsonify({
        "total": total_count,
        "items": dict(paged_items),
        "offset": offset,
        "limit": limit
    })


@app.route('/api/admin/manual_match', methods=['POST'])
def manual_match():
    data = request.json
    orig_name = data.get('orig_name')
    m_type = data.get('type')
    t_id = data.get('tmdb_id')

    if not all([orig_name, m_type, t_id]):
        return jsonify({"status": "error", "message": "Missing data"})

    try:
        headers = {"Authorization": f"Bearer {TMDB_API_KEY}"}
        d_resp = requests.get(
            f"{TMDB_BASE_URL}/{m_type}/{t_id}?language=ko-KR&append_to_response=content_ratings,credits",
            headers=headers, timeout=10).json()

        if 'id' not in d_resp:
            return jsonify({"status": "error", "message": f"TMDB ID {t_id} not found"})

        yv = (d_resp.get('release_date') or d_resp.get('first_air_date') or "").split('-')[0]
        rating = None
        if 'content_ratings' in d_resp:
            res_r = d_resp['content_ratings'].get('results', [])
            kr = next((r['rating'] for r in res_r if r.get('iso_3166_1') == 'KR'), None)
            if kr: rating = f"{kr}+" if kr.isdigit() else kr

        genre_names = [g['name'] for g in d_resp.get('genres', [])]
        cast_data = d_resp.get('credits', {}).get('cast', [])
        actors = [{"name": c['name'], "profile": c['profile_path'], "role": c['character']} for c in cast_data[:10]]
        crew_data = d_resp.get('credits', {}).get('crew', [])
        director = next((c['name'] for c in crew_data if c.get('job') == 'Director'), "")

        info = {
            "tmdbId": f"{m_type}:{t_id}",
            "genreIds": [g['id'] for g in d_resp.get('genres', [])],
            "genreNames": genre_names,
            "director": director,
            "actors": actors,
            "posterPath": d_resp.get('poster_path'),
            "year": yv,
            "overview": d_resp.get('overview'),
            "rating": rating,
            "seasonCount": d_resp.get('number_of_seasons'),
            "failed": False
        }

        conn = get_db()
        cursor = conn.cursor()
        up = (
            info['posterPath'], info['year'], info['overview'],
            info['rating'], info['seasonCount'],
            json.dumps(info['genreIds']),
            json.dumps(info['genreNames'], ensure_ascii=False),
            info['director'],
            json.dumps(info['actors'], ensure_ascii=False),
            info['tmdbId'],
            d_resp.get('title') or d_resp.get('name')
        )
        cursor.execute(
            'UPDATE series SET posterPath=?, year=?, overview=?, rating=?, seasonCount=?, genreIds=?, genreNames=?, director=?, actors=?, tmdbId=?, tmdbTitle=?, failed=0 WHERE name=?',
            (*up, orig_name))
        conn.commit()
        conn.close()

        if orig_name in MATCH_DIAGNOSTICS:
            del MATCH_DIAGNOSTICS[orig_name]

        build_all_caches()
        return jsonify({"status": "success"})
    except Exception as e:
        log("MANUAL_MATCH_ERROR", str(e))
        return jsonify({"status": "error", "message": str(e)})


def _generate_thumb_file(path_raw, prefix, tid, t, w):
    # 기본 너비를 1280 정도로 조정 (너무 크면 NAS가 힘들어하고, 작으면 화질이 깨짐)
    target_w = "1280"
    tp = os.path.join(DATA_DIR, f"seek_{tid}_{t}_{target_w}.jpg")
    if os.path.exists(tp) and os.path.getsize(tp) > 0: return tp

    try:
        base = next(v[0] for k, v in PATH_MAP.items() if v[1] == prefix)
        # 🔴 경로 디코딩 로그 추가
        decoded_path = urllib.parse.unquote_plus(path_raw)
        vp = get_real_path(os.path.join(base, nfc(decoded_path)))

        if not os.path.exists(vp):
            log("THUMB_DIAG", f"❌ 파일을 찾을 수 없음: {vp}")
            return None

        with THUMB_SEMAPHORE:
            if os.path.exists(tp): return tp
            log("THUMB_DIAG", f"📸 썸네일 생성 중: {os.path.basename(vp)} ({t}초 지점)")

            # 생성 속도를 위해 고속 추출 옵션(-ss를 -i 앞에) 사용
            subprocess.run([
                FFMPEG_PATH, "-y",
                "-ss", str(t),
                "-i", vp,
                "-frames:v", "1",
                "-q:v", "5",
                "-vf", f"scale={target_w}:-1",
                tp
            ], timeout=20, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

        return tp if os.path.exists(tp) else None
    except Exception as e:
        log("THUMB_DIAG", f"⚠️ 에러 발생: {str(e)}")
        return None


@app.route('/thumb_serve')
def thumb_serve():
    path = request.args.get('path')
    prefix = request.args.get('type')
    tid = request.args.get('id')
    t = request.args.get('t', default="300")
    w = request.args.get('w', default="1280")

    # 🔴 요청이 들어오는지 확인하는 로그
    log("THUMB_DIAG", f"🔗 썸네일 요청 수신: {path[:30]}... (type={prefix}, t={t})")

    tp = _generate_thumb_file(path, prefix, tid, t, w)
    if tp and os.path.exists(tp):
        resp = make_response(send_file(tp, mimetype='image/jpeg'))
        resp.headers['Cache-Control'] = 'public, max-age=31536000, immutable'
        return resp

    log("THUMB_DIAG", "❌ 썸네일 반환 실패 (파일 없음)")
    return "Not Found", 404


# --- [복원된 기능: 스킵 네비게이션용 스토리보드 생성] ---
def get_video_duration(path):
    try:
        result = subprocess.run([FFPROBE_PATH, "-v", "error", "-show_entries", "format=duration", "-of",
                                 "default=noprint_wrappers=1:nokey=1", path], stdout=subprocess.PIPE,
                                stderr=subprocess.PIPE)
        return float(result.stdout)
    except:
        return 0


@app.route('/storyboard')
def gen_seek_thumbnails():
    path_raw = request.args.get('path')
    prefix = request.args.get('type')
    log("DIAG", "=== 스토리보드 생성 진단 시작 ===")
    log("DIAG", f"1. 앱에서 전달된 경로: {path_raw}")

    try:
        # 1. 경로 복원 및 검증
        base = next(v[0] for k, v in PATH_MAP.items() if v[1] == prefix)
        decoded_path = urllib.parse.unquote_plus(path_raw)
        vp = get_real_path(os.path.join(base, nfc(decoded_path)))
        log("DIAG", f"2. 서버에서 찾은 실제 경로: {vp}")

        if not os.path.exists(vp):
            log("DIAG", "❌ 에러: 파일을 찾을 수 없습니다. 경로를 다시 확인하세요.")
            return "Not Found", 404

        # 2. 영상 길이 확인
        duration = get_video_duration(vp)
        log("DIAG", f"3. 영상 재생 시간: {duration}초")

        if duration == 0:
            log("DIAG", "❌ 에러: ffprobe가 영상 길이를 읽지 못했습니다.")
            return "Duration Error", 500

        # 3. 캐시 확인
        file_hash = hashlib.md5(vp.encode()).hexdigest()
        sb_path = os.path.join(DATA_DIR, f"sb_{file_hash}.jpg")

        if os.path.exists(sb_path) and os.path.getsize(sb_path) > 0:
            log("DIAG", "4. 기존 캐시 파일 발견. 즉시 반환합니다.")
            return send_file(sb_path, mimetype='image/jpeg')

        # 4. 스토리보드 생성 시도
        with STORYBOARD_SEMAPHORE:
            log("DIAG", "5. FFmpeg 생성 명령을 실행합니다...")
            cmd = [
                FFMPEG_PATH, "-y",
                "-i", vp,
                "-vf", f"fps=101/{duration},scale=160:90,tile=10x10",
                "-frames:v", "1", "-an", "-sn", "-dn", "-q:v", "5",
                sb_path
            ]
            log("DIAG", f"6. 실행 명령어: {' '.join(cmd)}")

            proc = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, timeout=120)

            if os.path.exists(sb_path):
                log("DIAG", f"✅ 생성 성공: {sb_path}")
                resp = make_response(send_file(sb_path, mimetype='image/jpeg'))
                resp.headers['Cache-Control'] = 'public, max-age=31536000, immutable'
                return resp
            else:
                log("DIAG", f"❌ 생성 실패. FFmpeg 메시지: {proc.stderr}")
                return "Generation Failed", 500

    except Exception as e:
        log("DIAG", f"❌ 서버 내부 예외 발생: {str(e)}")
        return "Internal Server Error", 500


@app.route('/api/status')
def get_server_status():
    try:
        conn = get_db()
        eps = conn.execute("SELECT COUNT(*) FROM episodes").fetchone()[0]
        ser = conn.execute("SELECT COUNT(*) FROM series").fetchone()[0]
        mtch = conn.execute("SELECT COUNT(*) FROM series WHERE tmdbId IS NOT NULL").fetchone()[0]
        fail = conn.execute("SELECT COUNT(*) FROM series WHERE failed = 1").fetchone()[0]

        # --- [추가/수정된 부분: 서브카테고리 포함 스틸컷 현황] ---
        # 1. 썸네일이 변경된(http) 에피소드만 우선 조회
        applied_eps = conn.execute("""
            SELECT series_path, COUNT(id) as tmdb_eps
            FROM episodes
            WHERE thumbnailUrl LIKE 'http%'
            GROUP BY series_path
        """).fetchall()

        applied_map = {row['series_path']: row['tmdb_eps'] for row in applied_eps}

        # 2. TMDB 매칭된 작품의 기본 정보(전체 회차수, 이름, 카테고리, 상세경로) 조회
        series_info = conn.execute("""
            SELECT s.path, s.name, s.category, COUNT(e.id) as total_eps
            FROM series s
            JOIN episodes e ON s.path = e.series_path
            WHERE s.tmdbId IS NOT NULL
            GROUP BY s.path
        """).fetchall()

        stills_applied = []
        for row in series_info:
            path = row['path']
            tmdb_eps = applied_map.get(path, 0)
            if tmdb_eps > 0:
                # 서브 카테고리 추출 로직 (예: air/라프텔 애니메이션/나혼렙 -> 방송중 > 라프텔 애니메이션)
                # category_name 매핑
                cat_map = {"movies": "영화", "foreigntv": "외국TV", "koreantv": "국내TV", "animations_all": "애니메이션",
                           "air": "방송중"}
                main_cat_ko = cat_map.get(row['category'], row['category'])

                parts = path.split('/')
                sub_cat = parts[1] if len(parts) > 2 else "일반"
                display_cat = f"{main_cat_ko} > {sub_cat}"

                stills_applied.append({
                    "name": row['name'],
                    "category": display_cat,
                    "applied": f"{tmdb_eps}/{row['total_eps']}"
                })

        # 보기 좋게 카테고리, 이름순으로 정렬
        stills_applied.sort(key=lambda x: (x['category'], x['name']))
        # --- [추가된 부분 끝] ---

        conn.close()
        return jsonify({
            "total_episodes": eps,
            "total_series": ser,
            "matched_series": mtch,
            "failed_series": fail,
            "success_rate": f"{round(mtch / ser * 100, 1)}%" if ser > 0 else "0%",
            "stills_applied_count": len(stills_applied),
            "stills_applied_series": stills_applied
        })
    except:
        return jsonify({"error": traceback.format_exc()})

# --- [자막 기능: 비동기 추출, 검색 로직 개선, 타임아웃 해결 버전] ---
def run_subtitle_extraction(vp, rel_path, sub_to_extract):
    """백그라운드에서 자막을 추출하고, 완료 후 작업 목록에서 제거합니다."""
    with EXTRACTION_LOCK:
        ACTIVE_EXTRACTIONS.add(rel_path)
    try:
        stream_index = sub_to_extract['index']
        # tags가 None일 가능성 완벽 차단
        tags = sub_to_extract.get('tags') if sub_to_extract.get('tags') else {}
        lang = str(tags.get('language') if tags.get('language') else 'und').lower()
        lang_suffix = 'ko' if lang in ['ko', 'kor'] else 'en' if lang in ['en', 'eng'] else 'und'

        video_rel_hash = hashlib.md5(rel_path.encode()).hexdigest()
        subtitle_filename = f"{video_rel_hash}.{lang_suffix}.srt"
        subtitle_full_path = os.path.join(SUBTITLE_DIR, subtitle_filename)

        if not os.path.exists(subtitle_full_path):
            log("SUBTITLE", f"백그라운드 추출 시작: 스트림 #{stream_index} ({lang}) -> {subtitle_filename}")
            extract_cmd = [
                FFMPEG_PATH, "-y", "-nostdin",
                "-analyzeduration", "1000000",  # 분석 시간 단축 (1초)
                "-probesize", "1000000",  # 분석 용량 단축 (1MB)
                "-i", vp,
                "-vn", "-an",
                "-map", f"0:{stream_index}",
                "-c:s", "srt",
                "-map_metadata", "-1",  # 메타데이터 무시
                subtitle_full_path
            ]
            try:
                subprocess.run(extract_cmd, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                               timeout=300)
                log("SUBTITLE", f"백그라운드 추출 성공: {subtitle_filename}")
            except subprocess.CalledProcessError as e:
                log("SUBTITLE_FAIL", f"추출 실패. FFmpeg Code: {e.returncode}")
            except subprocess.TimeoutExpired:
                log("SUBTITLE_FAIL", f"추출 시간 초과 (300초): {subtitle_filename}")
    except Exception as e:
        log("SUBTITLE_ERROR", f"백그라운드 자막 추출 중 예외 발생: {traceback.format_exc()}")
    finally:
        # 작업이 성공하든 실패하든, 목록에서 제거
        with EXTRACTION_LOCK:
            ACTIVE_EXTRACTIONS.discard(rel_path)
            log("SUBTITLE", f"추출 작업 완료 및 정리: {os.path.basename(rel_path)}")


@app.route('/api/subtitle_info')
def get_subtitle_info_api():
    path_raw, prefix = request.args.get('path'), request.args.get('type')
    try:
        base = next(v[0] for k, v in PATH_MAP.items() if v[1] == prefix)
        # 1. 경로 디코딩 (공백 + 처리 포함)
        rel_path = nfc(urllib.parse.unquote_plus(path_raw))
        vp = get_real_path(os.path.join(base, rel_path))
        video_filename = os.path.basename(vp)
        video_name_no_ext = os.path.splitext(video_filename)[0]

        log("SUBTITLE", f"자막 조회 시작: {video_filename}")
        external, embedded = [], []

        # 2. 외부 자막 탐색 (원본 폴더)
        parent_dir = os.path.dirname(vp)
        if os.path.exists(parent_dir):
            for f in os.listdir(parent_dir):
                f_nfc = nfc(f)
                if f_nfc.lower().endswith(('.srt', '.smi', '.ass', '.vtt')):
                    sub_name_no_ext = os.path.splitext(f_nfc)[0]
                    if video_name_no_ext.startswith(sub_name_no_ext) or sub_name_no_ext.startswith(video_name_no_ext):
                        if f_nfc != video_filename:
                            external.append({"name": f_nfc, "path": nfc(os.path.join(os.path.dirname(rel_path), f_nfc))})

        # 3. 외부 자막 탐색 (서버 캐시 폴더)
        video_rel_hash = hashlib.md5(rel_path.encode()).hexdigest()
        if os.path.exists(SUBTITLE_DIR):
            for f in os.listdir(SUBTITLE_DIR):
                if f.startswith(video_rel_hash):
                    full_p = os.path.join(SUBTITLE_DIR, f)
                    # [중요] 추출 중이 아니고, 파일 크기가 0보다 클 때만 목록에 추가
                    with EXTRACTION_LOCK:
                        is_extracting = rel_path in ACTIVE_EXTRACTIONS

                    if not is_extracting and os.path.exists(full_p) and os.path.getsize(full_p) > 0:
                        display_name = f.replace(f"{video_rel_hash}.", f"{video_name_no_ext}.")
                        external.append({"name": display_name, "path": f"__SUBTITLE_DIR__/{f}"})
        # 4. 내장 자막 정보 수집 (외부 자막이 없을 때 대비)
        try:
            cmd = [FFPROBE_PATH, "-v", "error", "-show_streams", "-of", "json", vp]
            result = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, check=True, timeout=60)
            all_streams = json.loads(result.stdout).get('streams', [])
            embedded = [s for s in all_streams if s.get('codec_type') == 'subtitle' or 'sub' in s.get('codec_name', '').lower()]
        except Exception as e:
            log("SUBTITLE_ERROR", f"내장 자막 분석 실패: {e}")

        # 5. 자막 목록 확정 및 정렬
        final_subs = sorted(list({sub['path']: sub for sub in external}.values()), key=lambda x: x['name'])
        log("SUBTITLE", f"탐색 완료 - 최종 외부 자막: {len(final_subs)}개, 내장 자막: {len(embedded)}개")

        # 6. [중요] 추출 진행 여부 확인 및 대기 신호 전송
        # 파일이 아직 없는데 현재 추출 중이라면 앱에 대기 신호를 보냅니다.
        if not final_subs:
            with EXTRACTION_LOCK:
                if rel_path in ACTIVE_EXTRACTIONS:
                    log("SUBTITLE", f"추출 진행 중... 클라이언트에 대기 신호 전송: {video_filename}")
                    return jsonify({"external": [], "embedded": [], "extraction_triggered": True})

        # 7. 자동 추출 트리거
        extraction_started = False
        if not final_subs and embedded:
            def get_lang_score(s):
                t = s.get('tags') if s.get('tags') else {}
                l = str(t.get('language') if t.get('language') else 'und').lower()
                return {'ko': 1, 'kor': 1, 'en': 2, 'eng': 2}.get(l, 99)

            try:
                sub_to_extract = min(embedded, key=get_lang_score)
                if sub_to_extract:
                    with EXTRACTION_LOCK:
                        if rel_path not in ACTIVE_EXTRACTIONS:
                            log("SUBTITLE", f"자동 추출 시작: {video_filename} (Stream #{sub_to_extract['index']})")
                            SUBTITLE_EXECUTOR.submit(run_subtitle_extraction, vp, rel_path, sub_to_extract)
                            # [수정] 자동-다음화 버그 방지를 위해 extraction_triggered는 False로 반환
                            extraction_started = False
            except Exception as e:
                log("SUBTITLE_ERROR", f"추출 트리거 중 에러: {e}")

        return jsonify({"external": final_subs, "embedded": embedded, "extraction_triggered": extraction_started})

    except Exception as e:
        log("SUBTITLE_ERROR", f"자막 정보 조회 중 에러: {traceback.format_exc()}")
        return jsonify({"error": str(e), "external": [], "embedded": [], "extraction_triggered": False})

@app.route('/api/subtitle_extract')
def subtitle_extract():
    path_raw = request.args.get('path')
    prefix = request.args.get('type')
    index = request.args.get('index')
    sub_path = request.args.get('sub_path')

    try:
        if sub_path:
            full_sub_path = None
            if sub_path.startswith("__SUBTITLE_DIR__/"):
                filename = sub_path.replace("__SUBTITLE_DIR__/", "")
                full_sub_path = get_real_path(os.path.join(SUBTITLE_DIR, filename))
            else:
                base = next(v[0] for k, v in PATH_MAP.items() if v[1] == prefix)
                full_sub_path = get_real_path(os.path.join(base, nfc(urllib.parse.unquote(sub_path))))

            if full_sub_path and os.path.exists(full_sub_path):
                return send_file(full_sub_path)
            else:
                log("SUBTITLE_ERROR", f"외부 자막 파일을 찾을 수 없음: {full_sub_path}")
                return "Not Found", 404

        if index is not None:
            base = next(v[0] for k, v in PATH_MAP.items() if v[1] == prefix)
            vp = get_real_path(os.path.join(base, nfc(urllib.parse.unquote(path_raw))))

            cmd = [FFMPEG_PATH, "-y", "-nostdin", "-i", vp, "-map", f"0:{index}", "-f", "srt", "pipe:1"]

            def generate():
                proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
                try:
                    while True:
                        chunk = proc.stdout.read(4096)
                        if not chunk: break
                        yield chunk
                finally:
                    proc.kill()

            return Response(generate(), mimetype='text/plain; charset=utf-8')

        return "Bad Request", 400
    except Exception as e:
        log("SUBTITLE_ERROR", f"자막 전송 중 에러 발생: {str(e)}\n{traceback.format_exc()}")
        return "Internal Server Error", 500

def pre_extract_movie_subtitles():
    """영화 카테고리에서 자막이 없는 영상만 대상으로 자막 사전 추출을 실행합니다."""
    set_update_state(is_running=True, task_name="영화 자막 사전 추출", total=0, current=0, success=0, fail=0, clear_logs=True)
    log("SUBTITLE_PRE", "영화 카테고리 자막 사전 추출 시작...")
    emit_ui_log("영화 카테고리에서 자막이 없는 항목을 찾아 추출을 시작합니다.", "info")

    try:
        conn = get_db()
        # [수정] 'episodes' 테이블에서 'path'가 아닌 'videoUrl' 컬럼을 조회합니다.
        video_rows = conn.execute("SELECT videoUrl FROM episodes WHERE series_path LIKE 'movies/%'").fetchall()
        conn.close()

        total_videos = len(video_rows)
        set_update_state(total=total_videos)
        log("SUBTITLE_PRE", f"사전 추출 대상 영화 수: {total_videos}개")
        emit_ui_log(f"전체 영화 {total_videos}개를 대상으로 검사를 시작합니다.", "info")

        base_movie_path = PATH_MAP.get("영화", (None, None))[0]
        if disabled_movie_path := not base_movie_path:
            log("SUBTITLE_PRE_ERROR", "영화 경로를 찾을 수 없습니다.")
            emit_ui_log("설정에서 영화 카테고리 경로를 찾을 수 없습니다.", "error")
            return

        extraction_queued_count = 0

        for idx, row in enumerate(video_rows):
            with UPDATE_LOCK:
                UPDATE_STATE["current"] = idx + 1

            # [수정] row['path'] -> row['videoUrl']
            video_url = row['videoUrl']
            rel_path = video_url.replace('/video_serve?type=movie&path=', '')
            rel_path = nfc(urllib.parse.unquote(rel_path))

            vp = get_real_path(os.path.join(base_movie_path, rel_path))
            video_filename = os.path.basename(rel_path)

            with UPDATE_LOCK:
                UPDATE_STATE["current_item"] = video_filename

            if not os.path.exists(vp):
                emit_ui_log(f"파일 없음, 건너뜀: {video_filename}", "warning")
                with UPDATE_LOCK: UPDATE_STATE["fail"] += 1
                continue

            # --- [핵심 로직] 이미 자막 파일이 있는지 확인 ---
            parent_dir = os.path.dirname(vp)
            video_name_no_ext = os.path.splitext(video_filename)[0]

            has_external = False

            # 1. 원본 폴더 확인
            if os.path.exists(parent_dir):
                for f in os.listdir(parent_dir):
                    if f.lower().endswith(('.srt', '.smi', '.ass', '.vtt')):
                        sub_name_no_ext = os.path.splitext(f)[0]
                        if video_name_no_ext.startswith(sub_name_no_ext) or sub_name_no_ext.startswith(
                                video_name_no_ext):
                            has_external = True;
                            break

            if has_external:
                with UPDATE_LOCK: UPDATE_STATE["success"] += 1
                continue

            # 2. 서버 캐시 폴더 확인
            video_rel_hash = hashlib.md5(rel_path.encode()).hexdigest()
            if os.path.exists(SUBTITLE_DIR):
                for f in os.listdir(SUBTITLE_DIR):
                    if f.startswith(video_rel_hash):
                        has_external = True;
                        break

            if has_external:
                with UPDATE_LOCK: UPDATE_STATE["success"] += 1
                continue

            # --- 자막이 없는 경우에만 아래 로직 실행 ---
            try:
                cmd = [FFPROBE_PATH, "-v", "error", "-show_streams", "-of", "json", vp]
                result = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, check=True,
                                        timeout=60)
                all_streams = json.loads(result.stdout).get('streams', [])
                embedded = [s for s in all_streams if
                            s.get('codec_type') == 'subtitle' or 'sub' in s.get('codec_name', '').lower()]
            except Exception:
                embedded = []

            if embedded:
                def get_lang_score(s):
                    tags = s.get('tags') or {}
                    lang = str(tags.get('language') or 'und').lower()
                    return {'ko': 1, 'kor': 1, 'en': 2, 'eng': 2}.get(lang, 99)

                sub_to_extract = min(embedded, key=get_lang_score)

                with EXTRACTION_LOCK:
                    if rel_path not in ACTIVE_EXTRACTIONS:
                        SUBTITLE_EXECUTOR.submit(run_subtitle_extraction, vp, rel_path, sub_to_extract)
                        extraction_queued_count += 1
                        emit_ui_log(f"내장 자막 발견, 추출 예약: {video_filename}", "success")

            if (idx + 1) % 100 == 0:
                log("SUBTITLE_PRE", f"진행 상황: {idx + 1}/{total_videos} 검사 완료, {extraction_queued_count}개 추출 예약됨.")

    except Exception as e:
        log("SUBTITLE_PRE_ERROR", f"사전 추출 중 오류 발생: {traceback.format_exc()}")
        emit_ui_log(f"치명적 오류 발생: {e}", "error")
    finally:
        set_update_state(is_running=False, current_item="영화 자막 사전 추출 완료")
        log("SUBTITLE_PRE", f"영화 카테고리 자막 사전 추출 완료. 총 {extraction_queued_count}개의 자막 추출 작업을 예약했습니다.")
        emit_ui_log(f"모든 작업이 완료되었습니다. (신규 추출 {extraction_queued_count}개)", "success")


@app.route('/pre_extract_subtitles')
def pre_extract_subtitles_route():
    # 다른 작업이 실행 중일 때는 새 작업을 시작하지 않음
    if UPDATE_STATE.get("is_running", False):
        return jsonify({"status": "error", "message": "다른 작업이 이미 실행 중입니다."}), 409

    threading.Thread(target=pre_extract_movie_subtitles, daemon=True).start()
    return jsonify({"status": "success", "message": "영화 자막 사전 추출 작업을 시작합니다."})

@app.route('/refresh_cleaned_names')
def refresh_cleaned_names():
    def run_refresh():
        set_update_state(is_running=True, task_name="[제목 정제 및 그룹화 재정렬]",
                         total=0, current=0, success=0, fail=0, clear_logs=True)
        emit_ui_log("DB 데이터를 불러오는 중입니다...", "info")

        try:
            conn = get_db()
            rows = conn.execute("SELECT path, name, cleanedName FROM series").fetchall()
            total = len(rows)
            set_update_state(total=total)
            emit_ui_log(f"총 {total}개의 데이터를 분석합니다.", "info")

            updates = []
            for idx, row in enumerate(rows):
                if (idx + 1) % 1000 == 0:
                    set_update_state(current=idx + 1, current_item=f"분석 중: {row['name']}")

                new_clean, _ = clean_title_complex(row['name'], full_path=row['path'])
                if new_clean != row['cleanedName']:
                    updates.append((new_clean, row['path']))

            update_count = len(updates)
            emit_ui_log(f"분석 완료: {update_count}개의 항목에 정제가 필요합니다.", "info")

            if update_count > 0:
                cursor = conn.cursor()
                for i in range(0, update_count, 2000):
                    batch = updates[i:i + 2000]
                    cursor.executemany("UPDATE series SET cleanedName = ? WHERE path = ?", batch)
                    conn.commit()
                    set_update_state(success=i + len(batch))

            conn.close()
            emit_ui_log("캐시 갱신 중...", "info")
            build_all_caches()
            set_update_state(is_running=False, current_item=f"완료! ({update_count}건 수정됨)")
            emit_ui_log("모든 작업이 성공적으로 완료되었습니다.", "success")

        except Exception as e:
            emit_ui_log(f"오류 발생: {str(e)}", "error")
            set_update_state(is_running=False, current_item="오류 발생")

    import threading
    threading.Thread(target=run_refresh, daemon=True).start()
    return "복구 시작"


@app.route('/admin_stills')
def admin_stills_page():
    return """
    <html>
    <head>
        <title>NAS Player - TMDB 스틸컷 적용 현황</title>
        <style>
            body { font-family: sans-serif; background: #141414; color: white; padding: 20px; }
            h1 { margin-bottom: 5px; }
            .summary { font-size: 1.1em; color: #46D369; margin-bottom: 20px; font-weight: bold; }
            table { width: 100%; border-collapse: collapse; margin-top: 20px; font-size: 0.9em; }
            th, td { padding: 10px; text-align: left; border-bottom: 1px solid #333; }
            th { background: #222; position: sticky; top: 0; }
            .tag { display: inline-block; padding: 3px 8px; border-radius: 4px; font-size: 0.85em; fontWeight: bold; background: #333; color: #ccc; border: 1px solid #555;}
        </style>
    </head>
    <body>
        <h1>TMDB 스틸컷 적용 작품 목록</h1>
        <div class="summary" id="summary">데이터 불러오는 중...</div>
        <div id="content"></div>
        <script>
            async function loadData() {
                try {
                    const resp = await fetch('/api/status');
                    const data = await resp.json();

                    document.getElementById('summary').innerText = `총 ${data.stills_applied_count}개의 작품에 스틸컷이 반영되었습니다. (매칭된 총 작품 수: ${data.matched_series}개)`;

                    let html = '<table><tr><th>카테고리</th><th>작품명</th><th>스틸컷 반영 에피소드 비율</th></tr>';
                    data.stills_applied_series.forEach(item => {
                        html += `<tr>
                            <td><span class="tag">${item.category}</span></td>
                            <td>${item.name}</td>
                            <td>${item.applied}</td>
                        </tr>`;
                    });
                    html += '</table>';
                    document.getElementById('content').innerHTML = html;
                } catch (e) {
                    document.getElementById('summary').innerText = "데이터를 불러오는 중 오류가 발생했습니다.";
                }
            }
            loadData();
        </script>
    </body>
    </html>
    """


@app.route('/api/restore_names')
def restore_names():
    target = "미공개X파일"
    try:
        conn = get_db()
        # 오직 '미공개X파일'이 포함된 데이터 중,
        # 수동 매칭으로 인해 이름이 본편(명탐정 코난)으로 바뀐 것들만 원래대로 되돌립니다.
        cursor = conn.execute("""
            UPDATE series
            SET tmdbTitle = cleanedName
            WHERE (name LIKE ? OR cleanedName LIKE ?)
              AND tmdbTitle != cleanedName
        """, (f'%{target}%', f'%{target}%'))

        updated_count = cursor.rowcount
        conn.commit()
        conn.close()

        # 메모리 캐시를 즉시 갱신하여 앱에 바로 반영되게 합니다.
        build_all_caches()

        log("RESTORE", f"✅ '{target}' 관련 {updated_count}개 항목 복구 완료")
        return f"성공! '{target}' 관련 {updated_count}개 항목의 이름을 원래대로('{target}') 복구했습니다."
    except Exception as e:
        return f"복구 중 에러 발생: {str(e)}"

# --- [UI/캐시 로직 보존] ---
@app.route('/updater')
def updater_ui():
    return """
    <!DOCTYPE html>
    <html lang="ko">
    <head>
        <meta charset="UTF-8">
        <meta name="viewport" content="width=device-width, initial-scale=1.0">
        <title>NAS Player Pro - Admin Dashboard</title>
        <link href="https://cdnjs.cloudflare.com/ajax/libs/font-awesome/6.0.0/css/all.min.css" rel="stylesheet">
        <style>
            :root {
                --bg-color: #0f172a;
                --card-bg: #1e293b;
                --text-main: #f8fafc;
                --text-dim: #94a3b8;
                --primary: #3b82f6;
                --success: #10b981;
                --warning: #f59e0b;
                --danger: #ef4444;
                --accent: #8b5cf6;
            }

            body {
                font-family: 'Inter', -apple-system, sans-serif;
                background: var(--bg-color);
                color: var(--text-main);
                margin: 0; padding: 0;
                line-height: 1.5;
            }

            .dashboard {
                max-width: 1200px;
                margin: 0 auto;
                padding: 40px 20px;
            }

            /* Header Section */
            header {
                display: flex;
                justify-content: space-between;
                align-items: center;
                margin-bottom: 40px;
                border-bottom: 1px solid #334155;
                padding-bottom: 20px;
            }

            h1 { font-size: 28px; margin: 0; display: flex; align-items: center; gap: 12px; }
            h1 i { color: var(--primary); }

            /* Status Overview Cards */
            .status-grid {
                display: grid;
                grid-template-columns: repeat(auto-fit, minmax(240px, 1fr));
                gap: 20px;
                margin-bottom: 30px;
            }

            .stat-card {
                background: var(--card-bg);
                padding: 24px;
                border-radius: 16px;
                box-shadow: 0 4px 6px -1px rgba(0, 0, 0, 0.1);
                border: 1px solid #334155;
                transition: transform 0.2s;
            }

            .stat-card:hover { transform: translateY(-5px); }
            .stat-label { color: var(--text-dim); font-size: 14px; margin-bottom: 8px; font-weight: 500; }
            .stat-value { font-size: 24px; font-weight: 700; display: flex; align-items: baseline; gap: 8px; }
            .stat-unit { font-size: 14px; color: var(--text-dim); }

            /* Main Layout: Controls & Terminal */
            .main-grid {
                display: grid;
                grid-template-columns: 1fr 380px;
                gap: 30px;
            }

            @media (max-width: 1024px) {
                .main-grid { grid-template-columns: 1fr; }
            }

            /* Terminal Area */
            .terminal-container {
                background: #000;
                border-radius: 16px;
                padding: 20px;
                border: 1px solid #334155;
                display: flex;
                flex-direction: column;
                height: 600px;
            }

            .terminal-header {
                display: flex;
                justify-content: space-between;
                color: var(--text-dim);
                font-size: 12px;
                margin-bottom: 15px;
                text-transform: uppercase;
                letter-spacing: 1px;
            }

            .terminal {
                flex-grow: 1;
                overflow-y: auto;
                font-family: 'Fira Code', 'Consolas', monospace;
                font-size: 13px;
                color: #e2e8f0;
                line-height: 1.6;
            }

            /* Action Sidebar Cards */
            .sidebar { display: flex; flex-direction: column; gap: 20px; }
            .action-card {
                background: var(--card-bg);
                padding: 20px;
                border-radius: 16px;
                border: 1px solid #334155;
            }

            .card-title { font-size: 16px; font-weight: 600; margin-bottom: 15px; display: flex; align-items: center; gap: 8px; }
            .card-title i { color: var(--primary); }

            .btn-list { display: flex; flex-direction: column; gap: 10px; }

            button {
                width: 100%;
                padding: 12px;
                border-radius: 8px;
                border: none;
                font-size: 14px;
                font-weight: 600;
                cursor: pointer;
                transition: all 0.2s;
                text-align: left;
                display: flex;
                align-items: center;
                gap: 10px;
                color: white;
            }

            .btn-scan { background: #334155; }
            .btn-scan:hover { background: #475569; }
            .btn-meta { background: var(--primary); }
            .btn-meta:hover { background: #2563eb; }
            .btn-maintenance { background: #475569; }
            .btn-maintenance:hover { background: #64748b; }
            .btn-danger-alt { background: #7f1d1d; }
            .btn-danger-alt:hover { background: #991b1b; }

            /* Progress Bar Section */
            .progress-section {
                background: var(--card-bg);
                padding: 24px;
                border-radius: 16px;
                margin-bottom: 30px;
                border-left: 4px solid var(--primary);
            }

            .progress-info { display: flex; justify-content: space-between; margin-bottom: 12px; }
            .current-task { font-weight: 600; font-size: 16px; }
            .progress-track { background: #0f172a; height: 12px; border-radius: 6px; overflow: hidden; margin-bottom: 8px; }
            .progress-fill { background: linear-gradient(90deg, var(--primary), var(--accent)); width: 0%; height: 100%; transition: width 0.5s cubic-bezier(0.4, 0, 0.2, 1); }

            /* Inputs Area */
            .input-group { margin-top: 15px; display: flex; flex-direction: column; gap: 8px; }
            input {
                background: #0f172a;
                border: 1px solid #334155;
                padding: 10px;
                border-radius: 8px;
                color: white;
                font-size: 13px;
            }

            .log-success { color: #4ade80; }
            .log-error { color: #f87171; }
            .log-warning { color: #fbbf24; }
            .log-info { color: #94a3b8; }

        </style>
    </head>
    <body>
        <div class="dashboard">
            <header>
                <h1><i class="fas fa-server"></i> NAS Player Pro Admin</h1>
                <div style="font-size: 14px; color: var(--text-dim)">
                    <span id="serverTime"></span>
                </div>
            </header>

            <!-- Dashboard Stats -->
            <div class="status-grid">
                <div class="stat-card">
                    <div class="stat-label">진행 상황</div>
                    <div class="stat-value" id="progressPercent">0<span class="stat-unit">%</span></div>
                </div>
                <div class="stat-card">
                    <div class="stat-label">성공 건수</div>
                    <div class="stat-value" id="successCount" style="color: var(--success)">0</div>
                </div>
                <div class="stat-card">
                    <div class="stat-label">실패 건수</div>
                    <div class="stat-value" id="failCount" style="color: var(--danger)">0</div>
                </div>
                <div class="stat-card">
                    <div class="stat-label">처리 항목</div>
                    <div class="stat-value" id="progressCount" style="font-size: 18px;">0 / 0</div>
                </div>
            </div>

            <!-- Active Progress Bar -->
            <div class="progress-section">
                <div class="progress-info">
                    <span class="current-task" id="taskName">대기 중...</span>
                    <span id="statusText" style="color: var(--text-dim); font-size: 14px;">준비 완료</span>
                </div>
                <div class="progress-track">
                    <div class="progress-fill" id="progressBar"></div>
                </div>
                <div style="font-size: 13px; color: var(--text-dim);" id="currentItem">현재 항목: -</div>
            </div>

            <div class="main-grid">
                <!-- Left: Terminal -->
                <div class="terminal-container">
                    <div class="terminal-header">
                        <span><i class="fas fa-terminal"></i> System Activity Logs</span>
                        <span id="logCount">0 Logs</span>
                    </div>
                    <div class="terminal" id="terminalBox"></div>
                </div>

                <!-- Right: Actions Sidebar -->
                <div class="sidebar">
                    <div class="action-card">
                        <div class="card-title"><i class="fas fa-search"></i> 스캔 및 매칭</div>
                        <div class="btn-list">
                            <button class="btn-meta" onclick="triggerTask('/api/match_air_foreign')"><i class="fas fa-bolt"></i> 외국 폴더 핀셋 매칭 (추천)</button>
                            <button class="btn-scan" onclick="triggerTask('/api/scan_air_foreign')"><i class="fas fa-folder-open"></i> 외국 폴더 로컬 스캔</button>
                            <button class="btn-scan" onclick="triggerTask('/rescan_broken')"><i class="fas fa-sync"></i> 전체 로컬 폴더 스캔</button>
                            <button class="btn-meta" onclick="triggerTask('/retry_failed_metadata')"><i class="fas fa-redo"></i> 실패 메타데이터 재시도</button>
                        </div>
                    </div>

                    <div class="action-card">
                        <div class="card-title"><i class="fas fa-magic"></i> 개별 작품 수정</div>
                        <div class="input-group">
                            <input type="text" id="fixNameInput" placeholder="작품 제목 (예: 미공개X파일)">
                            <input type="text" id="tmdbIdInput" placeholder="TMDB ID (예: tv:32863)">
                            <div style="display: grid; grid-template-columns: 1fr 1fr; gap: 8px; margin-top: 5px;">
                                <button class="btn-meta" onclick="manualMatchSimple()" style="justify-content: center;"><i class="fas fa-link"></i> 수동 연결</button>
                                    <button class="btn-meta" onclick="manualMatchV2()" style="justify-content: center; background: var(--accent);"><i class="fas fa-sync-alt"></i> 수동 연결 V2</button>
                                <button class="btn-maintenance" onclick="fixMetadata()" style="justify-content: center;"><i class="fas fa-wand-magic-sparkles"></i> 자동 수정</button>
                            </div>
                        </div>
                    </div>

                    <div class="action-card">
                        <div class="card-title"><i class="fas fa-tools"></i> 고급 도구</div>
                        <div class="btn-list">
                            <button class="btn-maintenance" onclick="triggerTask('/apply_tmdb_thumbnails')"><i class="fas fa-image"></i> TMDB 스틸컷 일괄 교체</button>
                            <button class="btn-maintenance" onclick="triggerTask('/pre_extract_subtitles')"><i class="fas fa-closed-captioning"></i> 영화 자막 일괄 추출</button>
                            <button class="btn-maintenance" onclick="triggerTask('/refresh_cleaned_names')"><i class="fas fa-broom"></i> 제목 정제 및 그룹화 재정렬</button>
                            <button class="btn-danger-alt" onclick="triggerTask('/reset_episodes_metadata')"><i class="fas fa-undo"></i> 에피소드 회차 정보 초기화</button>
                            <button class="btn-danger-alt" onclick="triggerTask('/reset_all_tmdb_data')"><i class="fas fa-trash-alt"></i> 전체 TMDB 데이터 초기화</button>
                        </div>
                    </div>
                </div>
            </div>
        </div>

        <script>
            async function triggerTask(url) {
                if (confirm('이 작업을 실행하시겠습니까?')) {
                    const resp = await fetch(url);
                    const data = await resp.json();
                    alert(data.message || '작업이 시작되었습니다.');
                }
            }

            async function manualMatchSimple() {
                const name = document.getElementById('fixNameInput').value.trim();
                const id = document.getElementById('tmdbIdInput').value.trim();
                if (!name || !id) { alert('제목과 ID를 모두 입력하세요.'); return; }
                if (confirm('수동 매칭을 실행할까요?')) {
                    const resp = await fetch(`/api/manual_match_simple?name=${encodeURIComponent(name)}&id=${encodeURIComponent(id)}`);
                    alert(await resp.text());
                }
            }

            async function manualMatchV2() {
                const name = document.getElementById('fixNameInput').value.trim();
                const id = document.getElementById('tmdbIdInput').value.trim();
                if (!name || !id) { alert('제목과 ID를 모두 입력하세요.'); return; }
                if (confirm('제목까지 TMDB 공식 명칭으로 갱신할까요?')) {
                    const resp = await fetch(`/api/manual_match_v2?name=${encodeURIComponent(name)}&id=${encodeURIComponent(id)}`);
                    alert(await resp.text());
                }
            }

            async function fixMetadata() {
                const name = document.getElementById('fixNameInput').value.trim();
                if (!name) { alert('제목을 입력하세요.'); return; }
                if (confirm('자동 수정을 시도할까요?')) {
                    const resp = await fetch(`/fix_wrong_match?name=${encodeURIComponent(name)}`);
                    alert(await resp.text());
                }
            }

            async function updateStatus() {
                try {
                    const res = await fetch('/api/updater/status');
                    const data = await res.json();

                    document.getElementById('serverTime').innerText = new Date().toLocaleTimeString();
                    document.getElementById('taskName').innerText = data.task_name;
                    document.getElementById('statusText').innerText = data.is_running ? '실행 중' : '대기 중';
                    document.getElementById('currentItem').innerText = '현재 항목: ' + data.current_item;

                    const percent = data.total > 0 ? Math.round((data.current / data.total) * 100) : 0;
                    document.getElementById('progressBar').style.width = (data.is_running ? percent : (data.total > 0 ? 100 : 0)) + '%';
                    document.getElementById('progressPercent').innerHTML = `${percent}<span class="stat-unit">%</span>`;
                    document.getElementById('progressCount').innerText = `${data.current.toLocaleString()} / ${data.total.toLocaleString()}`;
                    document.getElementById('successCount').innerText = data.success.toLocaleString();
                    document.getElementById('failCount').innerText = data.fail.toLocaleString();

                    const term = document.getElementById('terminalBox');
                    document.getElementById('logCount').innerText = `${data.logs.length} Logs`;

                    if (data.logs.length > 0) {
                        let html = '';
                        data.logs.forEach(log => {
                            let cssClass = 'log-info';
                            if (log.type === 'success') cssClass = 'log-success';
                            else if (log.type === 'error' || log.type === 'warning') cssClass = 'log-error';
                            html += `<div class="${cssClass}">[${log.time}] ${log.msg}</div>`;
                        });
                        const isAtBottom = term.scrollHeight - term.clientHeight <= term.scrollTop + 50;
                        term.innerHTML = html;
                        if (isAtBottom) term.scrollTop = term.scrollHeight;
                    }
                } catch (e) { console.error(e); }
            }

            setInterval(updateStatus, 1000);
            updateStatus();
        </script>
    </body>
    </html>
    """

@app.route('/api/scan_air_foreign')
def scan_air_foreign_only():
    if "방송중" not in PATH_MAP:
        return "오류: PATH_MAP 설정 확인 필요", 404

    path, prefix = PATH_MAP["방송중"]

    # 1. 스캔 실행
    scan_recursive_to_db(path, prefix, "air", include_only=["외국"])
    build_all_caches()

    # 2. [추가] 매칭 작업 자동 트리거 (이 부분이 추가되어야 자동으로 넘어갑니다)
    threading.Thread(target=fetch_metadata_async, daemon=True).start()

    return "성공: '방송중 > 외국' 폴더 스캔 완료 및 메타데이터 매칭 시작"


@app.route('/api/match_air_foreign')
def match_air_foreign_only():
    """'방송중 > 외국' 폴더 내의 신규 항목만 골라서 상세 로그와 함께 TMDB 매칭을 진행합니다."""
    global IS_METADATA_RUNNING
    if IS_METADATA_RUNNING:
        return jsonify({"status": "error", "message": "이미 다른 매칭 작업이 실행 중입니다."}), 409

    def run_targeted_match():
        global IS_METADATA_RUNNING
        IS_METADATA_RUNNING = True
        emit_ui_log("🚀 [핀셋 매칭] '방송중 > 외국' 데이터 분석 및 매칭을 시작합니다.", "info")

        try:
            conn = get_db()
            # 1. 대상 선정: 경로(path) 정보와 샘플 이름을 함께 가져옵니다.
            query = """
                SELECT
                    cleanedName, yearVal, category,
                    MIN(name) as sample_name,
                    MIN(path) as sample_path,
                    GROUP_CONCAT(name, '|') as orig_names
                FROM series
                WHERE category = 'air' AND path LIKE 'air/외국/%'
                AND (tmdbId IS NULL OR tmdbTitle IS NULL)
                AND failed = 0
                GROUP BY cleanedName, yearVal
            """
            targets = conn.execute(query).fetchall()
            conn.close()

            total = len(targets)
            set_update_state(is_running=True, task_name="외국 폴더 정밀 매칭", total=total, current=0, success=0, fail=0,
                             clear_logs=True)

            if total == 0:
                emit_ui_log("✨ 모든 항목이 이미 매칭되어 있거나 대상이 없습니다.", "success")
                IS_METADATA_RUNNING = False
                set_update_state(is_running=False, current_item="작업 완료")
                return

            emit_ui_log(f"📊 총 {total}개의 작품 묶음을 발견했습니다. 매칭을 시작합니다.", "info")

            # 2. 매칭 루프 시작
            total_success = 0
            for idx, row in enumerate(targets):
                c_name = row['cleanedName']
                s_path = row['sample_path']
                orig_names = row['orig_names'].split('|')

                with UPDATE_LOCK:
                    UPDATE_STATE["current"] = idx + 1
                    UPDATE_STATE["current_item"] = c_name

                # 상세 로그 출력 (경로 포함)
                folder_only = "/".join(s_path.split('/')[:-1])  # 파일명 제외한 폴더 경로만 추출
                emit_ui_log(f"🔍 매칭 시도: {c_name} (위치: {folder_only})", "info")

                # TMDB API 호출
                info = get_tmdb_info_server(row['sample_name'], category='air')

                u_conn = get_db()
                cursor = u_conn.cursor()

                if info.get('failed'):
                    emit_ui_log(f"❌ 매칭 실패: '{c_name}' (TMDB에서 정보를 찾을 수 없음)", "warning")
                    cursor.executemany('UPDATE series SET failed=1 WHERE name=?', [(n,) for n in orig_names])
                else:
                    up = (
                        info.get('posterPath'), info.get('year'), info.get('overview'),
                        info.get('rating'), info.get('seasonCount'),
                        json.dumps(info.get('genreIds', [])),
                        json.dumps(info.get('genreNames', []), ensure_ascii=False),
                        info.get('director'),
                        json.dumps(info.get('actors', []), ensure_ascii=False),
                        info.get('tmdbId'),
                        info.get('title') or info.get('name')
                    )
                    cursor.executemany(
                        'UPDATE series SET posterPath=?, year=?, overview=?, rating=?, seasonCount=?, genreIds=?, genreNames=?, director=?, actors=?, tmdbId=?, tmdbTitle=?, failed=0 WHERE name=?',
                        [(*up, name) for name in orig_names])

                    total_success += 1
                    with UPDATE_LOCK:
                        UPDATE_STATE["success"] += 1

                    # 성공 로그를 더 풍성하게
                    match_res = f"✅ 매칭 성공: '{c_name}' -> '{info.get('title')}' ({info.get('year')})"
                    emit_ui_log(match_res, "success")

                u_conn.commit()
                u_conn.close()

            # 3. 마무리
            build_all_caches()
            emit_ui_log(f"🏁 핀셋 매칭 완료! 총 {total_success}/{total}개 작품 정보가 업데이트되었습니다.", "success")

        except Exception as e:
            log("TARGETED_MATCH_ERROR", f"Error: {e}")
            emit_ui_log(f"❗ 치명적 에러 발생: {str(e)}", "error")
        finally:
            IS_METADATA_RUNNING = False
            set_update_state(is_running=False, current_item="핀셋 매칭 종료")

    threading.Thread(target=run_targeted_match, daemon=True).start()
    return jsonify({"status": "success", "message": "상세 로그 모드로 매칭을 시작합니다."})

@app.route('/api/updater/status')
def get_updater_status():
    with UPDATE_LOCK:
        logs = list(UPDATE_STATE['logs'])
        return jsonify({
            "is_running": UPDATE_STATE['is_running'],
            "task_name": UPDATE_STATE['task_name'],
            "total": UPDATE_STATE['total'],
            "current": UPDATE_STATE['current'],
            "success": UPDATE_STATE['success'],
            "fail": UPDATE_STATE['fail'],
            "current_item": UPDATE_STATE['current_item'],
            "logs": logs
        })


@app.route('/api/admin/fast_db_repair')
def fast_db_repair():
    def run_repair():
        set_update_state(is_running=True, task_name="카테고리 핀셋 교정", clear_logs=True)
        emit_ui_log("🔍 1단계: 유령 데이터 삭제 및 카테고리 재분류 시작...")

        conn = get_db()
        cursor = conn.cursor()

        # 1. 휴지통 등 쓸데없는 데이터 먼저 제거
        cursor.execute("DELETE FROM episodes WHERE videoUrl LIKE '%@eaDir%' OR videoUrl LIKE '%#recycle%'")

        # 2. 🔴 [핵심 수술] 잘못된 카테고리에 있는 작품들을 원래 카테고리로 돌려보냅니다.
        # 기존 메타데이터(포스터, 이름 등)는 100% 보존됩니다.
        updates = [
            ("movies", "movies/%"),
            ("koreantv", "koreantv/%"),
            ("foreigntv", "foreigntv/%"),
            ("animations_all", "animations_all/%"),
            ("air", "air/%")
        ]

        for correct_cat, path_pattern in updates:
            # 예: 카테고리는 animations_all인데 경로는 koreantv/... 인 애들을 찾아서 수정
            cursor.execute(
                "UPDATE series SET category = ? WHERE path LIKE ? AND category != ?",
                (correct_cat, path_pattern, correct_cat)
            )
            updated_count = cursor.rowcount
            if updated_count > 0:
                log("REPAIR", f"✅ {correct_cat} 카테고리로 {updated_count}개 작품 원대복귀 완료.")
                emit_ui_log(f"{correct_cat} 카테고리로 {updated_count}개 작품 원대복귀 완료.", "success")

        conn.commit()
        conn.close()

        emit_ui_log("♻️ 2단계: 메모리 캐시 갱신 중...", "info")
        build_all_caches()

        emit_ui_log("✨ 수술 완료! 이제 앱을 껐다 켜보세요.", "success")
        set_update_state(is_running=False, current_item="핀셋 교정 완료")

    threading.Thread(target=run_repair, daemon=True).start()
    return "카테고리 핀셋 교정이 백그라운드에서 시작되었습니다. /updater 창을 확인하세요."


@app.route('/reset_episodes_metadata')
def reset_episodes_metadata():
    """DB에 저장된 에피소드들의 시즌/회차 정보를 초기화하여 재스캔 시 새 로직이 적용되게 함"""
    if UPDATE_STATE.get("is_running", False):
        return jsonify({"status": "error", "message": "다른 작업이 이미 실행 중입니다."}), 409

    def run_reset():
        set_update_state(is_running=True, task_name="에피소드 정보 초기화", total=0, current=0, success=0, fail=0,
                         clear_logs=True)
        emit_ui_log("DB의 기존 에피소드 시즌/회차 정보를 초기화합니다...", "info")

        try:
            conn = get_db()
            # season_number와 episode_number를 싹 비움
            conn.execute("UPDATE episodes SET season_number = NULL, episode_number = NULL")
            conn.commit()
            conn.close()

            emit_ui_log("초기화 완료. 메타데이터 재매칭을 시작하면 새로운 로직으로 파싱됩니다.", "success")
            # 강제로 메타데이터 재매칭 실행 (백그라운드)
            fetch_metadata_async(force_all=True)
        except Exception as e:
            emit_ui_log(f"초기화 중 오류 발생: {str(e)}", "error")
        finally:
            set_update_state(is_running=False, current_item="에피소드 초기화 완료")

    threading.Thread(target=run_reset, daemon=True).start()
    return jsonify({"status": "success", "message": "에피소드 정보 초기화 및 재매칭 작업을 시작합니다."})


@app.route('/reset_all_tmdb_data')
def reset_all_tmdb_data():
    """DB에 저장된 모든 TMDB 메타데이터(19금 포스터 등)와 캐시를 싹 지웁니다."""
    try:
        conn = get_db()
        # 1. 시리즈 테이블의 TMDB 관련 정보 모두 초기화
        conn.execute("""
            UPDATE series
            SET tmdbId = NULL, posterPath = NULL, overview = NULL,
                tmdbTitle = NULL, rating = NULL, genreNames = NULL,
                actors = NULL, director = NULL, failed = 0
        """)
        # 2. TMDB API 캐시 삭제 (잘못된 19금 데이터 캐시 제거)
        conn.execute("DELETE FROM tmdb_cache")
        conn.commit()
        conn.close()

        # 메모리 캐시 갱신
        build_all_caches()

        return jsonify({"status": "success", "message": "모든 TMDB 매칭 데이터가 초기화되었습니다. 전체 재스캔을 실행해주세요."})
    except Exception as e:
        return jsonify({"status": "error", "message": str(e)})

@app.route('/fix_wrong_match')
def fix_wrong_match():
    """잘못 매칭된 특정 작품(19금 등)의 이름의 일부를 입력받아 그것만 초기화하고 재매칭합니다."""
    target_name = request.args.get('name')
    if not target_name:
        return "오류: 주소창 끝에 '?name=작품이름' 을 붙여주세요. (예: /fix_wrong_match?name=나루토)", 400

    try:
        conn = get_db()
        # 1. 해당 이름이 포함된 작품의 TMDB 정보만 초기화
        cursor = conn.execute("""
            UPDATE series
            SET tmdbId = NULL, posterPath = NULL, overview = NULL,
                tmdbTitle = NULL, rating = NULL, genreNames = NULL,
                actors = NULL, director = NULL, failed = 0
            WHERE name LIKE ? OR cleanedName LIKE ?
        """, (f'%{target_name}%', f'%{target_name}%'))

        updated_count = cursor.rowcount
        conn.commit()
        conn.close()

        if updated_count > 0:
            # 메모리 캐시 새로고침
            build_all_caches()
            # 2. 🔴 수정: fetch_metadata_async에 target_name을 전달하여 '해당 작품만' 매칭하도록 함
            threading.Thread(target=fetch_metadata_async, kwargs={'target_name': target_name}, daemon=True).start()
            return f"성공! '{target_name}' 관련 {updated_count}개 항목을 초기화하고 전용 재매칭을 시작했습니다."
        else:
            return f"'{target_name}'을(를) DB에서 찾을 수 없습니다. 이름을 다시 확인해주세요."

    except Exception as e:
        return f"에러 발생: {str(e)}"

def build_all_caches():
    # 이 함수는 외부(업데이터 등)에서 캐시 갱신을 요청할 때만 사용합니다.
    global _GZIP_SECTION_CACHE
    _GZIP_SECTION_CACHE = {} # 캐시 빌드 시 Gzip 캐시도 초기화
    _rebuild_fast_memory_cache()

def _rebuild_fast_memory_cache():
    global _FAST_CATEGORY_CACHE, _SECTION_CACHE, _DETAIL_MEM_CACHE
    log("SYSTEM", "⚡ 메모리 캐시 최적화 빌드 시작 (자막/더빙 엄격 분리)...")
    _SECTION_CACHE = {}
    _DETAIL_MEM_CACHE = {}
    temp_cache = {}
    conn = get_db()

    CATS = ["movies", "foreigntv", "koreantv", "animations_all", "air"]

    for cat in CATS:
        query = "SELECT path, name, cleanedName, tmdbTitle, tmdbId, posterPath, year, genreNames, rating, seasonCount FROM series WHERE category = ? ORDER BY yearVal DESC"
        rows = conn.execute(query, (cat,)).fetchall()

        items = []
        seen_keys = set()
        folder_precalc = {}

        for r in rows:
            # 🟢 [개선] 경로와 이름을 모두 NFC로 정규화하여 검사
            path = nfc(r['path'])
            name = nfc(r['name'])
            path_lower = path.lower()
            name_lower = name.lower()

            tag = ""
            if "더빙" in path_lower or "더빙" in name_lower:
                tag = "더빙"
            elif "자막" in path_lower or "자막" in name_lower:
                tag = "자막"

            tmdb_id = r['tmdbId']
            c_name = nfc(r['cleanedName'] or r['name'])
            # 그룹키에 태그를 포함하여 카드 분리
            group_key = f"{tmdb_id}_{tag}" if tmdb_id else f"{c_name}_{tag}"

            if not group_key or group_key in seen_keys: continue
            if not r['posterPath']: continue
            seen_keys.add(group_key)

            display_name = nfc(r['tmdbTitle'] or c_name or name)
            if tag and f"[{tag}]" not in display_name:
                display_name = f"{display_name} [{tag}]"

            item = {
                "path": path,
                "name": display_name.strip(),
                "posterPath": r['posterPath'],
                "year": r['year'],
                "genreNames": [],
                "tmdbId": tmdb_id,
                "rating": r['rating'],  # 🔴 이 줄 추가
                "seasonCount": r['seasonCount'],  # 🔴 이 줄 추가
                "_search_name": display_name.lower()
            }

            try:
                if r['genreNames']: item["genreNames"] = json.loads(r['genreNames'])
            except:
                pass

            items.append(item)

            parts = path.split('/')
            if len(parts) > 2:
                folder_name = parts[1]
                folder_precalc.setdefault(folder_name, []).append(item)

        temp_cache[cat] = {"all": items, "folders": folder_precalc}
        log("SYSTEM", f"✅ {cat} 캐시 빌드 완료")

    conn.close()
    _FAST_CATEGORY_CACHE = temp_cache
    build_home_recommend()


def clean_title_for_retry(title):
    if not title: return ""
    # 1. 모든 괄호와 그 안의 내용 제거
    t = re.sub(r'\[.*?\]|\(.*?\)|\{.*?\}|\【.*?\】|\「.*?\」', ' ', title)
    # 2. 한글, 영문, 숫자만 남기고 나머지 특수문자/기호 제거
    t = "".join(re.findall(r'[가-힣a-zA-Z0-9\s]+', t))
    # 3. 연속된 공백 정리
    t = re.sub(r'\s+', ' ', t).strip()
    return t

def get_pure_ko_title(title):
    # 아예 한글 단어만 추출 (최후의 수단)
    ko_only = "".join(re.findall(r'[가-힣]+', title))
    return ko_only.strip()

def get_tmdb_info_with_fallback(orig_name, full_path=None, category=None):
    # [1단계] 기본 검색 (기존 로직)
    info = get_tmdb_info_server(orig_name, category=category, ignore_cache=True)
    if not info.get('failed'): return info

    # [2단계] 더 강력한 정제 후 재검색
    clean_retry = clean_title_for_retry(orig_name)
    if clean_retry and clean_retry != orig_name:
        log("RETRY", f"🔄 2단계 시도 (정제어): {clean_retry}")
        info = get_tmdb_info_server(clean_retry, category=category, ignore_cache=True)
        if not info.get('failed'): return info

    # [3단계] 부모 폴더명으로 시도 (애니메이션/시리즈물 특화)
    if full_path:
        parent_folder = os.path.basename(os.path.dirname(full_path))
        # 폴더명이 카테고리명과 같거나 너무 짧으면 무시
        if parent_folder and len(parent_folder) > 1 and parent_folder not in ["movies", "animations_all", "air"]:
            clean_parent = clean_title_for_retry(parent_folder)
            log("RETRY", f"🔄 3단계 시도 (폴더명): {clean_parent}")
            info = get_tmdb_info_server(clean_parent, category=category, ignore_cache=True)
            if not info.get('failed'): return info

    # [4단계] 순수 한글 키워드만 추출하여 시도 (최후의 수단)
    pure_ko = get_pure_ko_title(orig_name)
    if pure_ko and len(pure_ko) >= 2:
        log("RETRY", f"🔄 4단계 시도 (순수 한글): {pure_ko}")
        info = get_tmdb_info_server(pure_ko, category=category, ignore_cache=True)
        return info

    return {"failed": True}

def run_retry_all_no_poster():
    set_update_state(is_running=True, task_name="포스터 누락 정밀 재매칭", clear_logs=True)
    emit_ui_log("포스터가 없는 항목들에 대해 4단계 정밀 매칭을 시작합니다.", "info")

    conn = get_db()
    # 포스터가 없는 항목들 추출 (failed 여부 상관없이 전체 시도)
    targets = conn.execute("SELECT name, path, category FROM series WHERE posterPath IS NULL").fetchall()
    conn.close()

    total = len(targets)
    set_update_state(total=total, current=0, success=0, fail=0)

    success_count = 0
    for idx, row in enumerate(targets):
        name, path, cat = row['name'], row['path'], row['category']
        with UPDATE_LOCK:
            UPDATE_STATE["current"] = idx + 1
            UPDATE_STATE["current_item"] = name

        # 위에서 만든 fallback 엔진 호출
        info = get_tmdb_info_with_fallback(name, full_path=path, category=cat)

        if not info.get('failed'):
            # 성공 시 DB 업데이트
            u_conn = get_db()
            up = (
                info.get('posterPath'), info.get('year'), info.get('overview'),
                info.get('rating'), info.get('tmdbId'), info.get('title') or info.get('name'),
                name
            )
            u_conn.execute('''
                UPDATE series SET
                posterPath=?, year=?, overview=?, rating=?, tmdbId=?, tmdbTitle=?, failed=0
                WHERE name=?
            ''', up)
            u_conn.commit()
            u_conn.close()

            success_count += 1
            with UPDATE_LOCK:
                UPDATE_STATE["success"] += 1
            emit_ui_log(f"매칭 성공: {name} -> {info.get('title')}", "success")
        else:
            with UPDATE_LOCK:
                UPDATE_STATE["fail"] += 1

    build_all_caches()
    set_update_state(is_running=False, current_item=f"작업 완료 (신규 매칭: {success_count}건)")
    emit_ui_log(f"정밀 재매칭 완료. 총 {success_count}개의 포스터를 새로 찾았습니다.", "success")

def clean_title_generic(full_path, category_base_path):
    # 1. 카테고리 루트로부터의 상대 경로 추출 (예: '애니메이션/명탐정 코난/1기/파일.mp4')
    rel_path = nfc(os.path.relpath(full_path, category_base_path))
    parts = rel_path.split(os.sep)

    # 2. 시리즈 이름 결정 알고리즘
    if len(parts) >= 2:
        # 파일이 폴더 안에 있다면, 그 폴더명이 시리즈 이름일 확률이 가장 높음
        # 예: '명탐정 코난/1기/파일.mp4' -> '명탐정 코난'
        # 단, '1기', '시즌1' 같은 하위 폴더명은 건너뛰고 그 상위 폴더를 취함
        series_name = parts[0]
        if any(x in series_name for x in ['기', '시즌', 'Season', 'Part', '파트']) and len(parts) > 2:
            series_name = parts[0]  # 상황에 따라 parts[0] 또는 parts[1] 선택
    else:
        # 루트에 파일이 그냥 있다면 파일명에서 정제
        series_name = os.path.splitext(parts[-1])[0]

    # 3. 범용 정제 로직 (회차 정보 및 기술 태그 제거)
    # 괄호 안 내용 삭제
    series_name = re.sub(r'\[.*?\]|\(.*?\)|\{.*?\}', '', series_name)

    # 시즌/에피소드 마커(S01, E01, 1화 등)가 나오면 그 앞부분까지만 제목으로 인정
    # 이 정규식이 50만 개 영상의 제목을 하드코딩 없이 추출하는 핵심입니다.
    marker_match = REGEX_EP_MARKER_STRICT.search(series_name)
    if marker_match:
        series_name = series_name[:marker_match.start()]

    # 불필요한 기술 태그 및 특수문자 제거
    series_name = REGEX_TECHNICAL_TAGS.sub('', series_name)
    series_name = REGEX_SPECIAL_CHARS.sub(' ', series_name)

    # 4. 자막/더빙 속성 보존 (파일명 전체에서 검색)
    tag = ""
    if '더빙' in rel_path:
        tag = "[더빙]"
    elif '자막' in rel_path:
        tag = "[자막]"

    return f"{series_name.strip()} {tag}".strip()

def build_home_recommend():
    global HOME_RECOMMEND
    log("HOME", "🏠 홈 추천 데이터 빌드 시작...")
    try:
        new_sections = []

        def get_unique_picks(cat_name, limit=15):
            cat_data = _FAST_CATEGORY_CACHE.get(cat_name, {})
            items = list(cat_data.get("all", []))  # 바뀐 구조 대응
            if not items: return []
            random.shuffle(items)
            return items[:limit]

        for cat_name, title in [("foreigntv", "인기 외국 TV 시리즈"), ("koreantv", "화제의 국내 드라마")]:
            picks = get_unique_picks(cat_name)
            if picks: new_sections.append({"title": title, "items": picks})

        air_data = _FAST_CATEGORY_CACHE.get('air', {})
        air = list(air_data.get("all", []))  # 바뀐 구조 대응
        if air:
            random.shuffle(air)
            new_sections.append({"title": "실시간 방영 중", "items": air[:15]})

        HOME_RECOMMEND = new_sections
        log("HOME", "✨ 홈 추천 빌드 최종 완료")
    except Exception as e:
        log("HOME_ERROR", f"Error: {e}")

@app.route('/api/debug_grouping')
def debug_grouping():
    keyword = request.args.get('q', '')
    conn = get_db()
    query = """
        SELECT
            CASE WHEN s.path LIKE '%라프텔%' THEN '라프텔' WHEN s.path LIKE '%시리즈%' THEN '시리즈' ELSE '기타' END as folder,
            s.cleanedName, e.title
        FROM episodes e JOIN series s ON e.series_path = s.path
        WHERE s.name LIKE ? OR s.cleanedName LIKE ? OR e.title LIKE ?
    """
    rows = conn.execute(query, (f'%{keyword}%', f'%{keyword}%', f'%{keyword}%')).fetchall()
    conn.close()
    grouping_test = {}
    for r in rows:
        key = f"[{r['folder']}] {r['cleanedName']}" # 폴더명과 제목을 합쳐서 보여줌
        grouping_test.setdefault(key, []).append(r['title'])
    return jsonify({"그룹화_결과": grouping_test})

@app.route('/api/test_grouping_logic')
def test_grouping_logic():
    import sqlite3
    test_conn = sqlite3.connect(":memory:")
    test_conn.row_factory = sqlite3.Row
    cursor = test_conn.cursor()
    cursor.execute('CREATE TABLE series (path TEXT, category TEXT, name TEXT, cleanedName TEXT)')
    cursor.execute('CREATE TABLE episodes (title TEXT, series_path TEXT, videoUrl TEXT)')

    # [테스트 케이스] 이름과 폴더는 다르지만 결국 '명탐정 코난'으로 묶여야 하는 데이터들
    sample_data = [
        ("animations_all/코난 더빙/1기", "명탐정 코난 1기 [더빙]", "코난 더빙 1기 01화.mp4"),
        ("animations_all/코난 더빙/5기", "명탐정 코난 5기 [더빙]", "코난 더빙 5기 01화.mp4"),
        ("animations_all/코난 자막/1기", "명탐정 코난 1기 (자막)", "코난 자막 1기 01화.mp4"),
        ("animations_all/코난 자막/15기", "명탐정 코난 15기 (자막)", "코난 자막 15기 01화.mp4"),
    ]

    for spath, sname, etitle in sample_data:
        # 우리가 수정한 함수 호출 (fp와 base를 넘김)
        cname, _ = clean_title_complex(sname, full_path=spath, base_path="animations_all")
        cursor.execute("INSERT INTO series VALUES (?, 'animations_all', ?, ?)", (spath, sname, cname))
        cursor.execute("INSERT INTO episodes VALUES (?, ?, ?)", (etitle, spath, "/video/"+etitle))

    # [검증 1] 정제된 이름으로 그룹핑 확인
    cursor.execute("SELECT cleanedName, COUNT(*) as count FROM series GROUP BY cleanedName")
    groups = {row['cleanedName']: row['count'] for row in cursor.fetchall()}

    # [검증 2] 첫 번째 그룹(더빙)의 에피소드가 2개 다 나오는지 확인
    test_target = list(groups.keys())[0] if groups else ""
    cursor.execute("""
        SELECT e.title FROM episodes e
        WHERE e.series_path IN (SELECT path FROM series WHERE cleanedName = ?)
    """, (test_target,))
    found_files = [r['title'] for r in cursor.fetchall()]

    report = {
        "1_정제된_그룹_목록": groups,
        "2_테스트대상_그룹": test_target,
        "3_통합결과_파일수": len(found_files),
        "4_파일샘플": found_files,
        "결론": "성공" if len(groups) == 2 and len(found_files) == 2 else "실패"
    }
    test_conn.close()
    return jsonify(report)

@app.route('/api/retry_all_no_poster')
def retry_all_no_poster():
    """포스터가 없는 모든 항목에 대해 4단계 정밀 재매칭을 백그라운드에서 시작합니다."""
    # 이미 다른 작업(스캔, 매칭 등)이 실행 중인지 확인
    if UPDATE_STATE.get("is_running", False):
        return jsonify({
            "status": "error",
            "message": f"현재 '{UPDATE_STATE.get('task_name')}' 작업이 진행 중입니다. 잠시 후 다시 시도해주세요."
        }), 409

    # 백그라운드 스레드에서 정밀 재매칭 루프 실행
    import threading
    threading.Thread(target=run_retry_all_no_poster, daemon=True).start()

    return jsonify({
        "status": "success",
        "message": "모든 카테고리의 포스터 누락 항목에 대해 4단계 정밀 재매칭 작업을 시작했습니다. /updater 페이지에서 진행 상황을 확인하세요."
    })


@app.route('/api/debug_search_conan')
def debug_search_conan():
    conn = get_db()
    # 검색 쿼리에서 필터를 하나씩 제거하며 어디서 걸러지는지 확인합니다.

    # 1. 단순히 이름만 맞는게 몇개인지?
    total = conn.execute("SELECT COUNT(*) FROM series WHERE name LIKE '%명탐정 코난%'").fetchone()[0]

    # 2. 포스터가 있는게 몇개인지?
    has_poster = \
    conn.execute("SELECT COUNT(*) FROM series WHERE name LIKE '%명탐정 코난%' AND posterPath IS NOT NULL").fetchone()[0]

    # 3. 에피소드가 연결된게 몇개인지?
    has_episodes = conn.execute("""
        SELECT COUNT(*) FROM series s
        WHERE s.name LIKE '%명탐정 코난%'
        AND EXISTS (SELECT 1 FROM episodes e WHERE e.series_path = s.path)
    """).fetchone()[0]

    # 4. 실제 검색 쿼리에서 최종적으로 남는 데이터 샘플
    final_sample = conn.execute(
        "SELECT name, path, posterPath, tmdbId FROM series WHERE name LIKE '%명탐정 코난%' LIMIT 5").fetchall()

    conn.close()
    return jsonify({
        "1_이름매칭_총수": total,
        "2_포스터보유_수": has_poster,
        "3_에피소드연결_수": has_episodes,
        "4_데이터샘플": [dict(r) for r in final_sample]
    })

@app.route('/api/debug_xfile_seasons')
def debug_xfile_seasons():
    conn = get_db()
    # 미공개X파일 에피소드들이 DB에 어떻게 저장되어 있는지 10개만 샘플링
    rows = conn.execute("""
        SELECT title, season_number, episode_number
        FROM episodes
        WHERE title LIKE '%미공개X파일%'
        LIMIT 10
    """).fetchall()

    # 묶음 단위로 몇 개씩 있는지 확인
    group_check = conn.execute("""
        SELECT season_number, COUNT(*) as cnt
        FROM episodes
        WHERE title LIKE '%미공개X파일%'
        GROUP BY season_number
    """).fetchall()
    conn.close()

    return jsonify({
        "1_샘플데이터": [dict(r) for r in rows],
        "2_시즌별_개수": [dict(r) for r in group_check]
    })

@app.route('/api/fix_xfile_seasons')
def fix_xfile_seasons():
    """
    파일명(title)에 적힌 '미공개X파일1', '미공개X파일3' 등의 텍스트를 직접 읽어
    시즌 번호로 강제 배정하는 가장 확실한 로직입니다.
    """
    import re

    def parse_season_from_filename(filename):
        season = 1  # 기본값

        # 1. 파일명에서 '미공개X파일 3' 또는 '미공개X파일3' 패턴을 찾아 그 숫자를 시즌으로!
        # 예: "명탐정코난 미공개X파일3.E01" -> 3 추출
        # 예: "명탐정 코난 미공개X파일 2" -> 2 추출
        season_match = re.search(r'(?i)미공개\s*X\s*파일\s*(\d+)', nfc(filename))
        if season_match:
            season = int(season_match.group(1))

        # 2. 에피소드 번호 추출 (기존과 동일)
        episode = 1
        ep_match = re.search(r'(?i)(?:[.\s_-]E|EP)\s*(\d+)|(\d+)\s*(?:화|회)', nfc(filename))
        if ep_match:
            episode = int(ep_match.group(1) or ep_match.group(2))
        else:
            nums = re.findall(r'\d+', nfc(filename))
            if nums:
                # 미공개X파일3 처럼 시즌 숫자가 마지막일 수 있으므로 가장 마지막 숫자를 에피소드로 봄
                episode = int(nums[-1])

        return season, episode

    try:
        conn = get_db()
        # 미공개X파일이 들어간 모든 에피소드 가져오기
        episodes = conn.execute("SELECT id, title FROM episodes WHERE title LIKE '%미공개X파일%'").fetchall()

        if not episodes:
            conn.close()
            return "대상 에피소드를 찾을 수 없습니다."

        update_batch = []
        for ep in episodes:
            ep_id = ep['id']
            filename = ep['title']

            # 여기서 똑똑하게 뽑아냅니다.
            season_num, episode_num = parse_season_from_filename(filename)
            update_batch.append((season_num, episode_num, ep_id))

        cursor = conn.cursor()
        cursor.executemany(
            "UPDATE episodes SET season_number = ?, episode_number = ? WHERE id = ?",
            update_batch
        )
        updated_count = cursor.rowcount
        conn.commit()
        conn.close()

        # 메모리 캐시 강제 갱신
        build_all_caches()

        return f"성공! 파일명을 분석하여 총 {len(update_batch)}개의 '미공개X파일' 시즌/회차 정보를 완벽하게 나누었습니다."

    except Exception as e:
        import traceback
        return f"에러 발생: {str(e)}<br><pre>{traceback.format_exc()}</pre>"


@app.route('/api/update_progress', methods=['POST'])
def update_progress():
    data = request.json
    episode_id = data.get('episode_id')
    position = data.get('position')  # 현재 재생 위치(초)
    duration = data.get('duration')  # 영상 전체 길이(초)

    if not episode_id:
        return jsonify({"status": "error", "message": "Missing episode_id"}), 400

    try:
        conn = get_db()
        conn.execute('''
            INSERT OR REPLACE INTO playback_progress (episode_id, position, duration, last_watched)
            VALUES (?, ?, ?, CURRENT_TIMESTAMP)
        ''', (episode_id, position, duration))
        conn.commit()
        conn.close()

        # 🔴 [추가] 시청 기록이 업데이트되면 상세 페이지 캐시를 비워야 앱에 즉시 반영됩니다.
        global _DETAIL_MEM_CACHE
        _DETAIL_MEM_CACHE = {}

        return jsonify({"status": "success"})
    except Exception as e:
        return jsonify({"status": "error", "message": str(e)}), 500

@app.route('/api/manual_match_simple')
def manual_match_simple():
    """TMDB 정보를 연결하되, 원래 제목(미공개X파일 등)은 유지하는 최종 로직"""
    target_name = request.args.get('name')
    full_id = request.args.get('id')

    if not target_name or not full_id or ':' not in full_id:
        return "오류: 작품 키워드와 TMDB ID가 필요합니다.", 400

    emit_ui_log(f"수동 ID 매칭 시작: '{target_name}' -> {full_id}", "info")
    m_type, t_id = full_id.split(':')

    try:
        # 1. TMDB 데이터 조회
        headers = {"Authorization": f"Bearer {TMDB_API_KEY}"}
        d_resp = requests.get(
            f"{TMDB_BASE_URL}/{m_type}/{t_id}?language=ko-KR&append_to_response=content_ratings,credits",
            headers=headers, timeout=10).json()

        if 'id' not in d_resp:
            emit_ui_log(f"오류: TMDB ID {t_id}를 찾을 수 없습니다.", "error")
            return f"오류: TMDB ID {t_id}를 찾을 수 없습니다.", 404

        # 2. 데이터 가공 (제목 제외)
        yv = (d_resp.get('release_date') or d_resp.get('first_air_date') or "").split('-')[0]
        genre_names = [g['name'] for g in d_resp.get('genres', [])]
        cast_data = d_resp.get('credits', {}).get('cast', [])
        actors = [{"name": c['name'], "profile": c['profile_path'], "role": c['character']} for c in cast_data[:10]]
        crew_data = d_resp.get('credits', {}).get('crew', [])
        director = next((c['name'] for c in crew_data if c.get('job') == 'Director'), "")
        runtime = d_resp.get('runtime')  # 영화의 경우

        # 3. DB 업데이트 (tmdbTitle은 업데이트 목록에서 제외!!)
        conn = None
        for attempt in range(5):
            try:
                conn = get_db()
                cursor = conn.cursor()
                # 제목(tmdbTitle)을 뺀 나머지 정보만 업데이트
                up = (
                    d_resp.get('poster_path'), yv, d_resp.get('overview'),
                    d_resp.get('number_of_seasons'),
                    json.dumps([g['id'] for g in d_resp.get('genres', [])]),
                    json.dumps(genre_names, ensure_ascii=False),
                    director,
                    json.dumps(actors, ensure_ascii=False),
                    full_id,
                    runtime
                )

                cursor.execute("""
                    UPDATE series
                    SET posterPath=?, year=?, overview=?, seasonCount=?,
                        genreIds=?, genreNames=?, director=?, actors=?, tmdbId=?, failed=0,
                        runtime=?
                    WHERE name LIKE ? OR cleanedName LIKE ?
                """, (*up, f'%{target_name}%', f'%{target_name}%'))

                updated_count = cursor.rowcount
                conn.commit()
                break
            except sqlite3.OperationalError:
                time.sleep(2)
            finally:
                if conn: conn.close()

        if updated_count > 0:
            build_all_caches()
            emit_ui_log(f"성공! '{target_name}' 관련 {updated_count}건에 깨끗한 포스터를 연결했습니다. (제목은 유지됨)", "success")
            return f"성공! {updated_count}개 항목의 포스터를 교체했습니다."
        return "대상 없음", 404

    except Exception as e:
        emit_ui_log(f"에러: {str(e)}", "error")
        return f"에러 발생: {str(e)}"

@app.route('/api/manual_match_v2')
def manual_match_v2():
    """TMDB 정보를 연결하고, 제목(tmdbTitle)까지 TMDB 공식 명칭으로 강제 업데이트하는 기능"""
    target_name = request.args.get('name')
    full_id = request.args.get('id')

    if not target_name or not full_id or ':' not in full_id:
        return "오류: 작품 키워드와 TMDB ID가 필요합니다.", 400

    emit_ui_log(f"수동 연결 v2 시작: '{target_name}' -> {full_id} (제목 갱신 포함)", "info")
    m_type, t_id = full_id.split(':')

    try:
        headers = {"Authorization": f"Bearer {TMDB_API_KEY}"}
        d_resp = requests.get(
            f"{TMDB_BASE_URL}/{m_type}/{t_id}?language=ko-KR&append_to_response=content_ratings,credits",
            headers=headers, timeout=10).json()

        if 'id' not in d_resp:
            return f"오류: TMDB ID {t_id}를 찾을 수 없습니다.", 404

        yv = (d_resp.get('release_date') or d_resp.get('first_air_date') or "").split('-')[0]
        genre_names = [g['name'] for g in d_resp.get('genres', [])]
        cast_data = d_resp.get('credits', {}).get('cast', [])
        actors = [{"name": c['name'], "profile": c['profile_path'], "role": c['character']} for c in cast_data[:10]]
        crew_data = d_resp.get('credits', {}).get('crew', [])
        director = next((c['name'] for c in crew_data if c.get('job') == 'Director'), "")

        # 런타임 정보 수집 (아까 만든 로직 적용)
        runtime = d_resp.get('runtime')

        conn = get_db()
        cursor = conn.cursor()

        # 업데이트 데이터 (tmdbTitle 추가됨!)
        up = (
            d_resp.get('poster_path'), yv, d_resp.get('overview'),
            d_resp.get('number_of_seasons'),
            json.dumps([g['id'] for g in d_resp.get('genres', [])]),
            json.dumps(genre_names, ensure_ascii=False),
            director,
            json.dumps(actors, ensure_ascii=False),
            full_id,
            d_resp.get('title') or d_resp.get('name'),  # <-- 1. 공식 제목 추가
            runtime,  # <-- 2. 런타임 추가
            f'%{target_name}%', f'%{target_name}%'
        )

        cursor.execute("""
            UPDATE series
            SET posterPath=?, year=?, overview=?, seasonCount=?,
                genreIds=?, genreNames=?, director=?, actors=?, tmdbId=?, failed=0,
                tmdbTitle=?, runtime=?  -- <-- 3. 제목과 런타임 컬럼 갱신
            WHERE name LIKE ? OR cleanedName LIKE ?
        """, up)

        updated_count = cursor.rowcount
        conn.commit()
        conn.close()

        if updated_count > 0:
            build_all_caches()
            emit_ui_log(f"성공! '{target_name}' 관련 {updated_count}건의 제목과 메타데이터를 모두 갱신했습니다.", "success")
            return f"성공! {updated_count}개 항목의 제목과 정보를 교체했습니다."
        return "대상 없음", 404

    except Exception as e:
        return f"에러 발생: {str(e)}"

def background_init_tasks():
    build_all_caches()


# 수정 후 (port를 9821로 변경)
if __name__ == '__main__':
    init_db()
    threading.Thread(target=background_init_tasks, daemon=True).start()
    app.run(host='0.0.0.0', port=9821, threaded=True)
