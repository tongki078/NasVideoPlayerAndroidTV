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

# --- [특수 관리 설정] ---
# 폴더 구조가 복잡하여 '상위 폴더명' 기준으로 엄격하게 분리하고 싶은 대작들 리스트
SPECIAL_GRANULAR_GROUPS = ["원피스", "명탐정 코난", "나루토", "블리치"]

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

    # [추가] 터미널에도 즉시 출력하여 실시간성 강화
    # 현재 상태 변수들과 합쳐서 더 명확하게 출력합니다.
    with UPDATE_LOCK:
        status = f"[{UPDATE_STATE['current']}/{UPDATE_STATE['total']}]" if UPDATE_STATE['total'] > 0 else ""
        print(f"[{timestamp}] {status} {msg}", flush=True)

# -----------------------------------------------------------

os.makedirs(DATA_DIR, exist_ok=True)
os.makedirs(TMDB_CACHE_DIR, exist_ok=True)
os.makedirs(SUBTITLE_DIR, exist_ok=True)  # 자막 폴더 생성
if os.path.exists(HLS_ROOT): shutil.rmtree(HLS_ROOT, ignore_errors=True)
os.makedirs(HLS_ROOT, exist_ok=True)
# [추가] 수동 업로드 포스터 저장 경로
CUSTOM_POSTER_DIR = os.path.join(DATA_DIR, "custom_posters")
os.makedirs(CUSTOM_POSTER_DIR, exist_ok=True)

PARENT_VIDEO_DIR = "/volume2/video/GDS3/GDRIVE/VIDEO"
PATH_MAP = {
    "외국TV": (os.path.join(PARENT_VIDEO_DIR, "외국TV"), "ftv"),
    "국내TV": (os.path.join(PARENT_VIDEO_DIR, "국내TV"), "ktv"),
    "영화": (os.path.join(PARENT_VIDEO_DIR, "영화"), "movies"), # 'movie' -> 'movies'로 수정
    "애니메이션": (os.path.join(PARENT_VIDEO_DIR, "일본 애니메이션"), "anim_all"),
    "방송중": (os.path.join(PARENT_VIDEO_DIR, "방송중"), "air")
}

EXCLUDE_FOLDERS = ["성인", "19금", "Adult", "@eaDir", "#recycle"]
VIDEO_EXTS = ('.mp4', '.mkv', '.avi', '.wmv', '.flv', '.ts', '.tp', '.m4v', '.m2ts', '.mov')
# [추가] 검색 결과에서 제외할 DB 경로(시작 부분) 목록
SEARCH_EXCLUDE_PATHS = [
    "koreantv/애니메이션/",
    "air/애니메이션/",
]

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

# 에피소드/시즌 마커 (1~4자리 숫자 대응)
REGEX_EP_MARKER_STRICT = re.compile(
    r'(?i)(?:(?<=[\uac00-\ud7af\u3040-\u30ff\u4e00-\u9fff])|[.\s_-]|^)(?:'
    r'第?\s*S(\d+)[.\s_-]*E(\d+)(?:[-~]E?\d+)?(?:[화회기부話장쿨편])?|'
    r'第?\s*S(\d+)|'
    r'第?\s*E(\d+)(?:[-~]\d+)?(?:[화회기부話장쿨편])?|'
    r'(?<!\d)(\d+)\s*(?:화|회|기|부|話|장|쿨|편)|'
    r'(?:Season|Episode|Part|시즌|파트)[.\s_-]*(\d+)|'
    r'(?<=[.\s_-])(\d{1,4})(?=[.\s_-]|$)'  # 1~4자리 숫자 인식
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

    # [로그] 시작 지점
    is_bleach = "블리치" in t_orig
    if is_bleach: print(f"\n[DEBUG] === 블리치 분석 시작: {t_orig} ===")

    year_match = REGEX_YEAR.search(t_orig)
    extracted_year = year_match.group().strip('()') if year_match else None

    t_low = t_orig.lower().replace(" ", "")
    is_movie = any(x in t_low for x in ['극장판', 'themovie', '劇場版', 'thelast'])

    series_name = ""
    if is_movie and year_match:
        potential = t_orig[:year_match.start()].replace('.', ' ').strip()
        if len(potential) >= 2:
            series_name = potential
            if is_bleach: print(f"[DEBUG] 1단계(영화+연도): {series_name}")

    if full_path:
        parts = [p.strip() for p in full_path.replace('\\', '/').split('/') if p.strip()]
        SKIP_DIRS = {'애니메이션', '일본애니메이션', '라프텔', '시리즈', '기타', 'video', 'volume1', 'volume2', 'movies', 'animations_all',
                     'koreantv', 'foreigntv', 'air', 'gdrive', 'nas', 'share', '더빙', '자막', 'gds3', 'video', 'GDS3',
                     'GDRIVE', 'VIDEO', 'specials', 'season', '시즌', '가', '나', '다', '라', '마', '바', '사', '아', '자', '차',
                     '카', '타', '파', '하', '극장판', '극장', 'movie', 'themovie'}

        if not series_name:
            for p in reversed(parts[:-1]):
                p_clean = nfc(p.lower().replace(" ", ""))
                if p_clean in SKIP_DIRS or len(p_clean) <= 1: continue
                if re.search(r'\d+~\d+|\d+-\d+', p_clean): continue
                if any(p_clean.endswith(ext) for ext in VIDEO_EXTS): continue
                if re.search(r'(?i)season\s*\d+|시즌\s*\d+|part\s*\d+|s\d+', p_clean): continue
                series_name = p
                if is_bleach: print(f"[DEBUG] 2단계(폴더추출): {series_name}")
                break

    if not series_name:
        series_name = os.path.splitext(t_orig)[0]
        if is_bleach: print(f"[DEBUG] 3단계(파일명기반): {series_name}")

    series_name = REGEX_BRACKETS.sub(' ', series_name)
    if is_bleach: print(f"[DEBUG] 4단계(괄호제거): {series_name}")

    marker_match = REGEX_EP_MARKER_STRICT.search(series_name)
    if marker_match:
        potential = series_name[:marker_match.start()].strip()
        if len(potential) >= 2:
            series_name = potential
            if is_bleach: print(f"[DEBUG] 5단계(마커절단): {series_name}")

    # 범인으로 의심되는 기수/회차 제거 구간
    # [수정] 영화(극장판)일 때는 '1기', '2기'를 지우지 않고 보존해야 함!
    if not is_movie:
        series_name = re.sub(r'(?i)\b\d{1,4}\s*(?:기|화|회|부|장|쿨|편|시즌|Season|Part|파트)\b', ' ', series_name)
    if is_bleach: print(f"[DEBUG] 6단계(기수제거): {series_name}")

    # 1. [핵심] '1~4기' 같은 묶음 범위를 실제 파일 회차(E01, E02...)에 맞춰서 개별화
    # 반드시 아래 정제 로직(2, 3번)보다 먼저 실행되어야 합니다.
    range_match = re.search(r'(\d+)[~-](\d+)\s*(?:기|화|회|부|장|쿨|편|시즌|Season|Part|파트)?', series_name)
    if range_match:
        actual_ep = re.search(r'(?i)(?:[.\s_-]E|EP)\s*(\d+)|(\d+)\s*(?:화|회)', t_orig)
        if actual_ep:
            ep_num = int(actual_ep.group(1) or actual_ep.group(2))
            series_name = series_name.replace(range_match.group(0), f"{ep_num}기")

    # 2. 일반적인 기수/회차 제거 (영화가 아닐 때만 수행하여 '극장판 블리치 1기' 같은 이름을 보호)
    if not is_movie:
        series_name = re.sub(r'(?i)\b\d{1,4}\s*(?:기|화|회|부|장|쿨|편|시즌|Season|Part|파트)\b', ' ', series_name)
        series_name = re.sub(r'(?i)[.\s_-](?:E|EP|S)\d+\b', ' ', series_name)

    # 3. 공통 정제 (기술 태그, 특수문자, 공백 제거)
    series_name = REGEX_TECHNICAL_TAGS.sub('', series_name)
    series_name = REGEX_SPECIAL_CHARS.sub(' ', series_name)
    series_name = REGEX_SPACES.sub(' ', series_name).strip()

    if is_movie and "극장판" not in series_name:
        series_name = f"극장판 {series_name}"

    if is_bleach: print(f"[DEBUG] === 최종 결과: {series_name} ===\n")
    return series_name.strip(), extracted_year

@app.route('/api/repair/naruto_movie_liberation')
def naruto_movie_liberation():
    try:
        conn = get_db()
        cursor = conn.cursor()
        # 1. Specials 폴더 안에 있는 나루토 에피소드들을 모두 찾습니다.
        query = "SELECT id, title, series_path FROM episodes WHERE series_path LIKE '%나루토%질풍전%Specials%'"
        eps = conn.execute(query).fetchall()

        count = 0
        for ep in eps:
            ep_id = ep['id']
            # 파일명을 기반으로 새로운 독립적인 시리즈 경로를 생성합니다.
            # 예: animations_all/나루토_극장판_파일명.mkv
            new_series_path = f"animations_all/극장판_{ep['title']}"

            # 2. series 테이블에 새로운 독립 레코드를 생성합니다.
            # 제목은 파일명에서 일본어/영문을 최대한 활용합니다.
            cursor.execute("""
                INSERT OR REPLACE INTO series (path, category, name, cleanedName)
                VALUES (?, 'animations_all', ?, ?)
            """, (new_series_path, ep['title'], ep['title']))

            # 3. 에피소드 테이블의 series_path를 새로운 독립 경로로 업데이트합니다.
            # 이제 본편 '나루토 질풍전' 그룹과의 연결고리가 완전히 끊어집니다.
            cursor.execute("UPDATE episodes SET series_path = ? WHERE id = ?", (new_series_path, ep_id))
            count += 1

        conn.commit()
        conn.close()

        # 메모리 캐시 강제 갱신 (앱에 즉시 반영)
        build_all_caches()

        return f"성공! {count}개의 나루토 극장판을 본편 그룹에서 분리하여 독립시켰습니다. 이제 검색 화면에 각각 나타날 것입니다."
    except Exception as e:
        return f"에러 발생: {str(e)}"

@app.route('/api/repair/naruto_metadata_recovery')
def naruto_metadata_recovery():
    try:
        conn = get_db()
        cursor = conn.cursor()
        # 1. 에피소드가 연결되지 않은(고립된) 나루토 시리즈 중 매칭 정보가 있는 것을 찾습니다.
        # (이것이 사용자님이 이전에 수동 매칭했던 그 데이터입니다)
        query = """
            SELECT tmdbId, tmdbTitle, posterPath, year, overview, director, actors, genreNames, name
            FROM series
            WHERE (name LIKE '%나루토%' OR tmdbTitle LIKE '%나루토%')
              AND tmdbId IS NOT NULL
              AND path NOT IN (SELECT DISTINCT series_path FROM episodes)
        """
        orphans = conn.execute(query).fetchall()

        recovered_count = 0
        for old in orphans:
            # 2. 현재 에피소드가 연결되어 있는 '독립된' 나루토 극장판 시리즈를 찾아서
            #    기존 정보를 덮어씌웁니다. (이름이 유사한 것을 매칭)
            cursor.execute("""
                UPDATE series
                SET tmdbId=?, tmdbTitle=?, posterPath=?, year=?, overview=?, director=?, actors=?, genreNames=?, failed=0
                WHERE (name LIKE ? OR cleanedName LIKE ?)
                  AND tmdbId IS NULL
            """, (old['tmdbId'], old['tmdbTitle'], old['posterPath'], old['year'],
                  old['overview'], old['director'], old['actors'], old['genreNames'],
                  f"%{old['tmdbTitle']}%", f"%{old['tmdbTitle']}%"))

            recovered_count += cursor.rowcount

        conn.commit()
        conn.close()
        build_all_caches()

        return f"복구 성공! 고립되었던 {recovered_count}건의 나루토 메타데이터를 새로운 독립 경로로 이전 완료했습니다."
    except Exception as e:
        return f"복구 중 에러: {str(e)}"

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
def get_tmdb_info_server(title, category=None, ignore_cache=False, path=None):  # category 매개변수 추가
    if not title: return {"failed": True}
    hint_id = extract_tmdb_id(title)
    if not hint_id and path: # 제목에 ID가 없으면 경로(폴더명)에서 추출 시도
        hint_id = extract_tmdb_id(path)
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
    "movies": ["최신","UHD","제목"],
    "koreantv": ["드라마","시트콤","예능","교양","다큐멘터리"],
    "foreigntv": ["미국 드라마","일본 드라마","중국 드라마","기타국가 드라마","다큐"],
    "animations_all": ["라프텔","시리즈"],
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
            # [여기에 넣어주세요!]
            print(f"DEBUG: 현재 탐색 중인 폴더 -> {curr}", flush=True)
            emit_ui_log(f"탐색 중: {os.path.basename(curr)}", "info")
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
            sn, en = extract_episode_numbers(fp)  # 번호 추출 로직 호출
            cursor.execute(
                'INSERT OR REPLACE INTO episodes (id, series_path, title, videoUrl, thumbnailUrl, season_number, episode_number) VALUES (?, ?, ?, ?, ?, ?, ?)',
                (mid, spath, os.path.basename(fp), f"/video_serve?type={prefix}&path={urllib.parse.quote(rel)}",
                 f"/thumb_serve?type={prefix}&id={mid}&path={urllib.parse.quote(rel)}", sn, en))
            emit_ui_log(f"신규 추가: '{name}' (S{sn}E{en})", 'success')
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

    # [수정] 아래 문구 추가!
    log("SCAN", f"✅ 모든 파일 탐색 완료. 메타데이터 매칭 스레드를 가동합니다...")
    emit_ui_log(f"✅ 파일 탐색 완료. 지금부터 메타데이터 매칭을 시작합니다.", "success")

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


def fetch_metadata_async(force_all=False, target_name=None, target_category=None):
    global IS_METADATA_RUNNING
    if IS_METADATA_RUNNING:
        log("METADATA", "이미 프로세스가 실행 중입니다. 중단합니다.")
        return
    IS_METADATA_RUNNING = True

    task_display_name = f"메타데이터 매칭 (대상: {target_name})" if target_name else "메타데이터 매칭"
    log("METADATA", f"⚙️ 병렬 매칭 프로세스 시작 (force_all={force_all}, target='{target_name}')")

    try:
        conn = get_db()
        cursor = conn.cursor()

        # [개선] 대상 수 파악 - 타겟팅 작업 시 진행률을 정확히 표시하기 위함
        count_query = "SELECT COUNT(*) FROM series WHERE failed = 0"
        count_params = []
        if target_name:
            count_query += " AND (name LIKE ? OR cleanedName LIKE ?)"
            count_params.extend([f'%{target_name}%', f'%{target_name}%'])
        elif target_category:  # 카테고리 필터 추가
            count_query += " AND category = ?"
            count_params.append(target_category)

        t_wait = cursor.execute(count_query, count_params).fetchone()[0]

        # [개선] 전체 라이브러리 개수가 아닌, '실제 작업할 대상(t_wait)'을 기준으로 UI 상태 초기화
        set_update_state(is_running=True, task_name=task_display_name, total=t_wait,
                         current=0, success=0, fail=0, clear_logs=True)
        emit_ui_log(f"'{task_display_name}' 작업을 시작합니다. (대상: {t_wait}개 그룹)", "info")

        if force_all and not target_name:
            conn.execute('UPDATE series SET failed=0 WHERE tmdbId IS NULL')
            conn.commit()

        # 2. 미정제 이름들 정리 로직 (기존 유지)
        uncleaned_query = 'SELECT name, path FROM series WHERE cleanedName IS NULL AND tmdbId IS NULL AND failed = 0'
        uncleaned_params = []
        if target_name:
            uncleaned_query += ' AND (name LIKE ? OR cleanedName LIKE ?)'
            uncleaned_params.extend([f'%{target_name}%', f'%{target_name}%'])
        uncleaned_query += ' GROUP BY name'

        uncleaned_rows = conn.execute(uncleaned_query, uncleaned_params).fetchall()
        if uncleaned_rows:
            for idx, r in enumerate(uncleaned_rows):
                ct, yr = clean_title_complex(r['name'], full_path=r['path'])
                cursor.execute('UPDATE series SET cleanedName=?, yearVal=? WHERE name=? AND cleanedName IS NULL',
                               (ct, yr, r['name']))
                if (idx + 1) % 1000 == 0: conn.commit()
            conn.commit()

        # 3. 매칭할 그룹 쿼리 (기존 유지)
        group_query = '''
            SELECT cleanedName, yearVal, category, MIN(name) as sample_name, MIN(path) as sample_path, GROUP_CONCAT(name, '|') as orig_names
            FROM series
            WHERE failed = 0 AND cleanedName IS NOT NULL
        '''
        group_params = []
        if target_name:
            group_query += ' AND (name LIKE ? OR cleanedName LIKE ?)'
            group_params.extend([f'%{target_name}%', f'%{target_name}%'])
        elif target_category:
            group_query += ' AND category = ?'
            group_params.append(target_category)
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
                'sample_path': gr['sample_path'],  # 🔴 이 줄을 추가!
                'orig_names': gr['orig_names'].split('|')
            })

        total = len(tasks)
        set_update_state(total=total)  # [추가] 실제 작업할 그룹 수로 다시 정교하게 세팅

        def process_one(task):
            # path=task['sample_path'] 추가 전달
            info = get_tmdb_info_server(task['sample_name'], category=task['category'],
                                        ignore_cache=(target_name is not None), path=task['sample_path'])
            return (task, info)

        batch_size = 20  # [개선] 병렬 처리 안정성을 위해 배치 사이즈 조정
        total_processed = 0
        total_success = 0
        total_fail = 0

        for i in range(0, total, batch_size):
            batch = tasks[i:i + batch_size]
            results = []
            with ThreadPoolExecutor(max_workers=8) as executor:
                future_to_task = {executor.submit(process_one, t): t for t in batch}
                for future in as_completed(future_to_task):
                    results.append(future.result())

            conn = get_db()
            cursor = conn.cursor()

            for task, info in results:
                total_processed += 1
                orig_names = task['orig_names']
                clean_name = task['clean_title']

                if info.get('failed'):
                    total_fail += 1
                    cursor.executemany('UPDATE series SET failed=1 WHERE name=?', [(n,) for n in orig_names])
                    # [추가] 매칭 실패 로그 출력
                    emit_ui_log(f"❌ 매칭 실패: '{clean_name}' (TMDB에 정보 없음)", "warning")
                else:
                    total_success += 1
                    # --- [기존 복구: Series 정보 업데이트] ---
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

                    # --- [기존 복구: Episodes 상세 정보 및 스틸컷 업데이트] ---
                    if 'seasons_data' in info:
                        # eps_to_update = []
                        # for name in orig_names:
                        #     cursor.execute(
                        #         'SELECT id, title, series_path FROM episodes WHERE series_path IN (SELECT path FROM series WHERE name = ?)',
                        #         (name,))
                        #     eps_to_update.extend(cursor.fetchall())
                        # [수정] 이름(name)이 아니라 정확한 경로(path)를 가진 시리즈의 에피소드만 가져옵니다.
                        eps_to_update = []
                        # 현재 그룹에 속한 모든 시리즈(폴더)들의 실제 경로를 가져옵니다.
                        cursor.execute(
                            'SELECT path FROM series WHERE cleanedName = ? AND category = ?',
                            (task['clean_title'], task['category'])
                        )
                        paths = [r['path'] for r in cursor.fetchall()]

                        for p in paths:
                            cursor.execute('SELECT id, title, series_path FROM episodes WHERE series_path = ?', (p,))
                            eps_to_update.extend(cursor.fetchall())

                        ep_batch = []
                        for ep_row in eps_to_update:
                            sn, EN = extract_episode_numbers(f"{ep_row['series_path']}/{ep_row['title']}")
                            if EN:
                                ei = info['seasons_data'].get(f"{sn}_{EN}")
                                if ei:
                                    still_url = f"https://image.tmdb.org/t/p/w500{ei.get('still_path')}" if ei.get(
                                        'still_path') else None
                                    ep_batch.append(
                                        (ei.get('overview'), ei.get('air_date'), sn, EN, still_url, ep_row['id']))
                        if ep_batch:
                            # COALESCE를 사용하여, 새로운 이미지(still_url)가 NULL이면 기존의 추출 주소를 유지하도록 합니다.
                            cursor.executemany(
                                'UPDATE episodes SET overview=?, air_date=?, season_number=?, episode_number=?, thumbnailUrl=COALESCE(?, thumbnailUrl) WHERE id=?',
                                ep_batch)

                    # [추가] 매칭 성공 로그 출력 (상세 정보 포함)
                    emit_ui_log(f"✅ 매칭 성공: '{clean_name}' -> '{info.get('title')}'", "success")

            conn.commit()
            conn.close()

            # [개선] UI 진행률 상태 실시간 업데이트
            with UPDATE_LOCK:
                UPDATE_STATE["current"] = total_processed
                UPDATE_STATE["success"] = total_success
                UPDATE_STATE["fail"] = total_fail
                UPDATE_STATE["current_item"] = f"진행 중... ({total_processed}/{total})"

        build_all_caches()
        set_update_state(is_running=False, current_item=f"🏁 매칭 완료 (+{total_success}건 성공)")
        emit_ui_log(f"🏁 모든 매칭 작업이 완료되었습니다. (성공: {total_success}건)", "success")

    except Exception as e:
        log("METADATA", f"⚠️ 에러 발생: {traceback.format_exc()}")
        emit_ui_log(f"❌ 치명적 에러 발생: {str(e)}", "error")
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
        path = nfc(urllib.parse.unquote_plus(path_raw))
        if not path: return gzip_response({})

        conn = get_db()
        target_row = conn.execute('SELECT * FROM series WHERE path = ?', (path,)).fetchone()
        if not target_row:
            target_row = conn.execute('SELECT * FROM series WHERE path = ?', (nfd(path),)).fetchone()

        if not target_row:
            conn.close()
            return gzip_response({})

        series_data = dict(target_row)
        cat = series_data.get('category')
        c_name = nfc(series_data.get('cleanedName'))
        t_id = series_data.get('tmdbId')
        db_path = series_data.get('path')

        # 자막/더빙 판별
        is_dub = "더빙" in nfc(db_path + series_data.get('name', '')).lower()
        is_sub = "자막" in nfc(db_path + series_data.get('name', '')).lower()

        # 극장판(단일 영화) 여부
        is_single_movie = (cat == 'movies') or ("극장판" in nfc(series_data.get('name', '')))

        all_refined_eps = []
        if is_single_movie:
            query = "SELECT e.*, p.position, p.duration FROM episodes e LEFT JOIN playback_progress p ON e.id = p.episode_id WHERE e.series_path = ? OR e.series_path = ?"
            rows = conn.execute(query, (db_path, nfd(db_path))).fetchall()
        else:
            if t_id:
                query = "SELECT e.*, p.position, p.duration FROM episodes e LEFT JOIN playback_progress p ON e.id = p.episode_id WHERE e.series_path IN (SELECT path FROM series WHERE tmdbId = ? AND cleanedName = ?)"
                q_params = [t_id, c_name]
            else:
                query = "SELECT e.*, p.position, p.duration FROM episodes e LEFT JOIN playback_progress p ON e.id = p.episode_id WHERE e.series_path IN (SELECT path FROM series WHERE cleanedName = ?)"
                q_params = [c_name]

            # 필터링: 더빙엔 자막 제외, 자막엔 더빙 제외
            if is_dub:
                query += " AND e.series_path NOT LIKE '%자막%' AND e.title NOT LIKE '%자막%'"
            elif is_sub:
                query += " AND e.series_path NOT LIKE '%더빙%' AND e.title NOT LIKE '%더빙%'"

            is_special = any(k in (c_name or "") for k in SPECIAL_GRANULAR_GROUPS)
            if is_special:
                path_parts = db_path.split('/')
                if len(path_parts) >= 2:
                    # 'animations_all/라프텔/' 처럼 카테고리와 서브폴더까지만 경로 추출
                    folder_prefix = f"{path_parts[0]}/{path_parts[1]}/"
                    query += " AND e.series_path LIKE ?"
                    q_params.append(f"{folder_prefix}%")

            rows = conn.execute(query, q_params).fetchall()

        for r in rows:
            d = dict(r)
            ep_path = nfc(d['series_path'])
            ep_title = nfc(d['title'])

            if is_single_movie:
                s_disp, s_weight, en = "영화", 1, 1
            else:
                s_disp = "1시즌"
                s_weight = 1
                en = 1

                path_parts = ep_path.split('/')
                parent_folder = path_parts[-2] if len(path_parts) >= 2 else ""

                full_name_upper = f"{ep_path}/{ep_title}".upper()
                combined_name_nfc = nfc(parent_folder + " " + ep_title)

                # --- [엄격한 특별 분기: 애니메이션 카테고리 + 코난 미공개X파일 한정] ---
                if cat == 'animations_all' and "코난" in combined_name_nfc and "미공개X파일" in combined_name_nfc:
                    # 파일명 또는 폴더명에서 '미공개X파일 1', '미공개X파일3' 등의 숫자 추출
                    xfile_match = re.search(r'(?i)미공개\s*X\s*파일\s*(\d+)', combined_name_nfc)
                    if xfile_match:
                        season_num = int(xfile_match.group(1))
                        s_disp = f"미공개X파일 {season_num}"
                        s_weight = 800 + season_num  # 일반 시즌(1~100)과 스페셜(999) 사이에 배치
                    else:
                        s_disp = "미공개X파일"
                        s_weight = 800
                # ------------------------------------------------------------------------
                elif any(kw in full_name_upper for kw in ["극장판", "MOVIE"]):
                    s_disp = "극장판"
                    s_weight = 1000
                elif any(kw in full_name_upper for kw in ["스페셜", "OVA", "OAD", "대괴수", "수학여행", "실종사건", "에피소드 원"]):
                    s_disp = "스페셜"
                    s_weight = 999
                else:
                    clean_folder = re.sub(r'\[.*?\]|\(.*?\)|\{.*?\}', '', parent_folder).strip()
                    season_match = re.search(r'(?i)(?:시즌|Season|S)\s*(\d+)|(\d+)\s*(?:기|시즌)', clean_folder)

                    if season_match:
                        season_num = int(season_match.group(1) or season_match.group(2))
                        s_disp = f"{season_num}시즌"
                        s_weight = season_num
                    # 2. 폴더명이 그냥 숫자인 경우 (예: "01", "04")
                    elif re.search(r'^\d{1,2}$', clean_folder):
                        season_num = int(clean_folder)
                        s_disp = f"{season_num}시즌"
                        s_weight = season_num
                    # 3. 그 다음 특수 키워드를 체크합니다 (범위를 파일명+폴더명으로 축소)
                    elif any(kw in (parent_folder + ep_title).upper() for kw in ["극장판", "MOVIE"]):
                        s_disp = "극장판"
                        s_weight = 1000
                    elif any(kw in (parent_folder + ep_title).upper() for kw in ["스페셜", "OVA", "OAD", "특전"]):
                        s_disp = "스페셜"
                        s_weight = 999
                    else:
                        # 기존의 부제 처리 로직 유지
                        base_c_name = re.sub(r'\[.*?\]|\(.*?\)|\{.*?\}', '', c_name).strip()
                        short_name = clean_folder.replace(base_c_name, '').strip()
                        if short_name:
                            s_disp = short_name
                            num_match = re.search(r'\d+', short_name)
                            s_weight = int(num_match.group(0)) if num_match else 900
                        else:
                            s_disp = "1시즌"
                            s_weight = 1

                if any(k in ep_path.upper() for k in ["4K", "UHD", "고화질", "BD", "BLURAY"]):
                    s_disp = f"고화질 ({s_disp})"
                    s_weight += 900

                ep_match = re.search(r'(?i)(?:[.\s_-]E|EP)\s*(\d+)|(\d+)\s*(?:화|회)', ep_title)
                if ep_match:
                    en = int(ep_match.group(1) or ep_match.group(2))
                else:
                    nums = re.findall(r'\d+', ep_title)
                    if nums: en = int(nums[-1])

            all_refined_eps.append({
                "id": str(d.get('id')), "title": ep_title, "videoUrl": d.get('videoUrl'),
                "thumbnailUrl": d.get('thumbnailUrl'), "overview": d.get('overview'),
                "air_date": d.get('air_date'), "season_number": s_weight, "episode_number": en,
                "display_season": s_disp, "sort_weight": s_weight,
                "position": d.get('position') or 0, "duration": d.get('duration') or 0, "runtime": d.get('runtime')
            })

        sorted_eps = sorted(all_refined_eps, key=lambda x: (x['sort_weight'], x['episode_number'] or 0))

        # 중복 에피소드 방어 로직 (id 기준 고유값만 남김)
        unique_eps_dict = {}
        for ep in sorted_eps:
            unique_eps_dict[ep['id']] = ep
        final_sorted_eps = list(unique_eps_dict.values())

        seasons_map = {}
        for ep in final_sorted_eps:
            seasons_map.setdefault(ep['display_season'], []).append(ep)

        final_season_count = len(seasons_map)

        # --- [수정 후: 제목 일관성 유지 로직] ---
        # 1. 원본 파일명/폴더명에서 태그를 먼저 추출하여 보존합니다.
        orig_raw_name = nfc(series_data.get('name', ''))
        tag = ""
        if "더빙" in nfc(db_path + orig_raw_name).lower():
            tag = "더빙"
        elif "자막" in nfc(db_path + orig_raw_name).lower():
            tag = "자막"

        # 2. 기본 제목을 결정합니다.
        base_title = c_name if c_name else nfc(series_data.get('tmdbTitle') or series_data.get('name'))

        # 3. 특수 대작(나루토 등)의 경우 폴더명 정보를 포함합니다. (리스트와 동일한 로직)
        is_special = any(k in base_title for k in SPECIAL_GRANULAR_GROUPS)
        sub_folder = ""
        if is_special:
            parts = db_path.split('/')
            sub_folder = parts[1] if len(parts) > 2 else ""
            if sub_folder and sub_folder not in base_title:
                base_title = f"{sub_folder} > {base_title}"

        # 4. 최종 이름을 조립합니다. (이미 태그가 있다면 중복 방지)
        if tag and f"[{tag}]" not in base_title:
            final_display_name = f"{base_title} [{tag}]"
        else:
            final_display_name = base_title

        response_data = {
            **series_data,
            "name": final_display_name.strip(), # 이 값이 앱의 제목이 됩니다.
            "seasonCount": final_season_count,
            "episodes": final_sorted_eps, "movies": final_sorted_eps, "seasons": seasons_map,
            "genreIds": json.loads(series_data.get('genreIds', '[]')) if series_data.get('genreIds') else [],
            "genreNames": json.loads(series_data.get('genreNames', '[]')) if series_data.get('genreNames') else [],
            "actors": json.loads(series_data.get('actors', '[]')) if series_data.get('actors') else []
        }
        conn.close()
        return gzip_response(response_data)
    except Exception as e:
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
        conn = get_db()
        cat_map = {"영화": "movies", "외국TV": "foreigntv", "국내TV": "koreantv", "애니메이션": "animations_all", "방송중": "air"}
        target_cat = cat_map.get(cat_filter)

        # 1. 기본 쿼리
        query = """
            SELECT * FROM series
            WHERE (name LIKE ? OR name LIKE ? OR cleanedName LIKE ? OR cleanedName LIKE ? OR tmdbTitle LIKE ? OR tmdbTitle LIKE ?)
            AND posterPath IS NOT NULL
            AND EXISTS (SELECT 1 FROM episodes WHERE series_path = series.path)
        """
        params = [f"%{q_nfc}%", f"%{nfd(q)}%", f"%{q_nfc}%", f"%{nfd(q)}%", f"%{q_nfc}%", f"%{nfd(q)}%"]

        # 2. 카테고리 필터 동적 추가
        if target_cat:
            query += " AND category = ?"
            params.append(target_cat)

        # 3. [개선] 제외 폴더 동적 추가
        for exclude_path in SEARCH_EXCLUDE_PATHS:
            query += " AND path NOT LIKE ?"
            params.append(f"{exclude_path}%")

        # 4. 그룹화 및 정렬 (특수 대상만 폴더별로 묶음)
        # query += """
        #     GROUP BY category, tmdbId, cleanedName,
        #         (CASE WHEN (cleanedName LIKE '%원피스%' OR cleanedName LIKE '%명탐정 코난%' OR cleanedName LIKE '%나루토%')
        #               THEN (CASE WHEN path LIKE '%/%/%' THEN SUBSTR(path, INSTR(path, '/') + 1, INSTR(SUBSTR(path, INSTR(path, '/') + 1), '/') - 1) ELSE '' END)
        #               ELSE '' END),
        #         CASE WHEN (path LIKE '%더빙%' OR name LIKE '%더빙%') THEN '더빙' WHEN (path LIKE '%자막%' OR name LIKE '%자막%') THEN '자막' ELSE '' END
        #     ORDER BY
        #         CASE WHEN tmdbTitle = ? OR cleanedName = ? THEN 1 WHEN tmdbTitle LIKE ? THEN 2 WHEN cleanedName LIKE ? THEN 3 ELSE 4 END ASC,
        #         seasonCount DESC, name ASC
        # """

        # [개선] 하드코딩 대신 상단의 SPECIAL_GRANULAR_GROUPS 리스트를 동적으로 반영
        special_names_cond = " OR ".join([f"cleanedName LIKE '%{g}%'" for g in SPECIAL_GRANULAR_GROUPS])

        query += f"""
            GROUP BY category, tmdbId, cleanedName,
                (CASE WHEN ({special_names_cond})
                      THEN (CASE WHEN path LIKE '%/%/%' THEN SUBSTR(path, INSTR(path, '/') + 1, INSTR(SUBSTR(path, INSTR(path, '/') + 1), '/') - 1) ELSE '' END)
                      ELSE '' END),
                CASE WHEN (path LIKE '%더빙%' OR name LIKE '%더빙%') THEN '더빙' WHEN (path LIKE '%자막%' OR name LIKE '%자막%') THEN '자막' ELSE '' END
            ORDER BY
                CASE WHEN tmdbTitle = ? OR cleanedName = ? THEN 1 WHEN tmdbTitle LIKE ? THEN 2 WHEN cleanedName LIKE ? THEN 3 ELSE 4 END ASC,
                seasonCount DESC, name ASC
        """

        # 정렬을 위한 파라미터 4개 추가
        params.extend([q_nfc, q_nfc, f'{q_nfc}%', f'{q_nfc}%'])

        cursor = conn.execute(query, params)
        rows = []
        for row in cursor.fetchall():
            item = dict(row)

            # --- [폴더 경로 추출 로직] ---
            spath = nfc(item.get('path', ''))
            parts = spath.split('/')
            sub_folder = parts[1] if len(parts) > 2 else ""

            # 특수 관리 대상인지 확인
            is_special = any(k in nfc(item.get('cleanedName', '')) for k in SPECIAL_GRANULAR_GROUPS)

            # 무조건 정제된 이름(cleanedName)이나 공식 이름(tmdbTitle)을 최우선으로 사용합니다.
            base_name = nfc(item.get('cleanedName') or item.get('tmdbTitle'))

            if not base_name:
                raw_name = nfc(item.get('name', ''))
                base_name, _ = clean_title_complex(raw_name)
                if not base_name: base_name = raw_name

            # 특수 대상은 이름 앞에 폴더명을 붙여줍니다.
            if is_special and sub_folder:
                base_name = f"{sub_folder} > {base_name}"

            full_check = (nfc(item.get('path', '')) + " " + nfc(item.get('name', ''))).lower()
            tags = []
            if "더빙" in full_check: tags.append("더빙")
            if "자막" in full_check: tags.append("자막")
            if "극장판" in full_check or item.get('category') == 'movies': tags.append("극장판")
            tag_str = "".join([f" [{t}]" for t in tags])

            processed = {
                "name": f"{base_name}{tag_str}".strip(),
                "path": item['path'], "category": item['category'],
                "posterPath": item['posterPath'] or "", "year": item['year'] or "",
                "overview": (item['overview'] or "")[:200],
                "genreIds": [], "genreNames": [], "director": item.get('director') or "",
                "rating": item.get('rating') or "",
                "tmdbTitle": item.get('tmdbTitle') or "", "tmdbId": item.get('tmdbId') or "",
                "actors": [], "movies": [], "seasons": {}
            }
            for col in ['genreIds', 'genreNames', 'actors']:
                try:
                    processed[col] = json.loads(item[col]) if item.get(col) else []
                except:
                    processed[col] = []
            rows.append(processed)
        conn.close()
        return gzip_response(rows)
    except Exception as e:
        log("SEARCH_ERROR", f"검색 중 오류: {str(e)}")
        return jsonify([])


@app.route('/rescan_broken')
def rescan_broken():
    threading.Thread(target=perform_full_scan, daemon=True).start()
    return jsonify({"status": "success"})

@app.route('/db_cleanup')
def db_cleanup():
    """파일 스캔 없이 DB에서 화이트리스트 외의 데이터를 즉시 삭제합니다."""

    # 1. 충돌 방지: 다른 백그라운드 작업이 실행 중인지 확인
    if IS_METADATA_RUNNING or UPDATE_STATE.get("is_running"):
        return jsonify({
            "status": "error",
            "message": "현재 다른 작업(스캔 또는 매칭)이 진행 중입니다. 해당 작업이 끝난 후 실행해 주세요."
        }), 409

    conn = None
    try:
        conn = get_db()
        # 2. 트랜잭션 시작 (with문을 사용하면 에러 발생 시 자동 롤백, 성공 시 자동 커밋됩니다)
        with conn:
            cursor = conn.cursor()

            # 카테고리별 허용된 폴더가 아니면 DB에서 삭제
            for cat, folders in WHITELISTS.items():
                if not folders: continue

                # SQL 조건 생성
                conditions = [f"path NOT LIKE '{cat}/{f}/%'" for f in folders]
                where_clause = " AND ".join(conditions)

                # 최적화: 삭제 대상이 있는지 먼저 확인하고 삭제하면 락 시간을 줄일 수 있습니다.
                query = f"DELETE FROM series WHERE category = ? AND ({where_clause})"
                cursor.execute(query, (cat,))
                log("CLEANUP", f"'{cat}' 카테고리 정리 완료")

            # 3. 고아 데이터 정리 (에피소드 및 빈 시리즈)
            log("CLEANUP", "고아 에피소드 및 빈 시리즈 정리 중...")
            cursor.execute("DELETE FROM episodes WHERE series_path NOT IN (SELECT path FROM series)")
            cursor.execute("DELETE FROM series WHERE path NOT IN (SELECT DISTINCT series_path FROM episodes)")

        log("CLEANUP", "모든 정리 작업 완료")

        # 메모리 캐시 즉시 갱신
        build_all_caches()
        return jsonify({"status": "success", "message": "DB cleanup completed successfully."})

    except sqlite3.OperationalError as e:
        if "locked" in str(e):
            log("CLEANUP_ERROR", "DB가 잠겨 있어 작업을 완료하지 못했습니다.")
            return jsonify({"status": "error", "message": "데이터베이스가 사용 중(Locked)입니다. 잠시 후 다시 시도해 주세요."}), 503
        raise e
    except Exception as e:
        log("CLEANUP_ERROR", f"예외 발생: {str(e)}")
        return jsonify({"status": "error", "message": str(e)}), 500
    finally:
        if conn:
            conn.close()

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
    cat = request.args.get('category') # 카테고리 파라미터 받기
    threading.Thread(target=run_apply_thumbnails, kwargs={'target_category': cat}, daemon=True).start()
    msg = f"{cat or '전체'} 카테고리 스틸컷 적용 시작"
    return jsonify({"status": "success", "message": msg})

def run_apply_thumbnails(target_category=None):
    task_name = f"TMDB 썸네일 교체 ({target_category or '전체'})"
    log("THUMB_SYNC", f"🔄 {task_name} 시작")
    set_update_state(is_running=True, task_name=task_name, clear_logs=True)

    conn = get_db()
    query = """
        SELECT DISTINCT s.path, s.name, s.category, s.tmdbId
        FROM series s
        JOIN episodes e ON s.path = e.series_path
        WHERE s.tmdbId IS NOT NULL
    """
    params = []
    if target_category:
        query += " AND s.category = ?"
        params.append(target_category)

    series_rows = [dict(r) for r in conn.execute(query, params).fetchall()]
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
                            sn, en = extract_episode_numbers(f"{path}/{ep['title']}")
                            if en:
                                key = f"{sn}_{en}"
                                if key in info['seasons_data']:
                                    still = info['seasons_data'][key].get('still_path')
                                    if still:
                                        # 최상의 화질을 위해 original 사용
                                        new_url = f"https://image.tmdb.org/t/p/w500{still}"
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

@app.route('/api/refresh_one_piece_names')
def refresh_one_piece_names():
    """'원피스' 관련 데이터의 이름을 재정제하고, 멈춰있는 매칭을 다시 시작합니다."""
    target_keyword = "원피스"

    def run_refresh():
        set_update_state(is_running=True, task_name=f"[{target_keyword}] 정밀 재정제 및 매칭 재가동",
                         total=0, current=0, success=0, fail=0, clear_logs=True)
        emit_ui_log(f"'{target_keyword}' 데이터 분석 및 매칭 상태 점검 시작...", "info")

        try:
            conn = get_db()
            query = "SELECT path, name, cleanedName, failed, tmdbId FROM series WHERE name LIKE ? OR path LIKE ?"
            rows = conn.execute(query, (f'%{target_keyword}%', f'%{target_keyword}%')).fetchall()

            total = len(rows)
            set_update_state(total=total)

            name_updates = []
            reset_count = 0

            for idx, row in enumerate(rows):
                if (idx + 1) % 100 == 0:
                    set_update_state(current=idx + 1, current_item=f"분석 중: {row['name'][:20]}...")

                new_clean, _ = clean_title_complex(row['name'], full_path=row['path'])

                if new_clean != row['cleanedName']:
                    name_updates.append((new_clean, row['path']))
                    emit_ui_log(f"변경 감지: '{row['cleanedName']}' -> '{new_clean}'", "info")

                # 매칭이 안 되어 있거나 실패한 상태면 다시 시도하도록 리셋
                if row['tmdbId'] is None:
                    reset_count += 1

            cursor = conn.cursor()
            if name_updates:
                cursor.executemany("UPDATE series SET cleanedName = ?, failed = 0 WHERE path = ?", name_updates)

            # 원피스 관련 모든 항목의 실패 플래그를 초기화하여 재매칭 유도
            cursor.execute("UPDATE series SET failed = 0 WHERE name LIKE ? OR path LIKE ?",
                           (f'%{target_keyword}%', f'%{target_keyword}%'))

            conn.commit()
            conn.close()

            emit_ui_log(f"분석 완료: {len(name_updates)}건 이름 수정, {reset_count}건 매칭 재가동 예약", "success")

            # 캐시 갱신 및 자동 매칭 스레드 실행
            build_all_caches()
            emit_ui_log("메타데이터 매칭 엔진(fetch_metadata_async)을 호출합니다...", "info")
            threading.Thread(target=fetch_metadata_async, kwargs={'target_name': target_keyword}, daemon=True).start()

            set_update_state(is_running=False, current_item=f"완료 (수정:{len(name_updates)}, 대상:{reset_count})")
        except Exception as e:
            emit_ui_log(f"오류 발생: {str(e)}", "error")
            set_update_state(is_running=False, current_item="오류 발생")

    import threading
    threading.Thread(target=run_refresh, daemon=True).start()
    return f"'{target_keyword}' 정제 및 매칭 재가동 작업이 시작되었습니다."

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


            /* --- [추가] Navigation Tabs --- */
            .nav-tabs {
                display: flex;
                gap: 10px;
                margin-bottom: 30px;
                border-bottom: 1px solid #334155;
                padding-bottom: 15px;
            }
            .nav-tab {
                padding: 10px 20px;
                background: var(--card-bg);
                color: var(--text-dim);
                text-decoration: none;
                border-radius: 8px;
                font-size: 14px;
                font-weight: 600;
                transition: all 0.2s;
                border: 1px solid #334155;
                display: flex;
                align-items: center;
                gap: 8px;
            }
            .nav-tab.active {
                background: var(--primary);
                color: white;
                border-color: var(--primary);
            }
            .nav-tab i { font-size: 16px; }
            .nav-tab:hover:not(.active) {
                background: #334155;
                color: var(--text-main);
            }

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

            <!-- [추가] 상단 탭 메뉴 -->
            <div class="nav-tabs">
                <a href="/updater" class="nav-tab active"><i class="fas fa-sync-alt"></i> 대시보드</a>
                <a href="/admin/ghost" class="nav-tab"><i class="fas fa-ghost"></i> 유령 데이터 관리</a>
                <a href="/admin" class="nav-tab"><i class="fas fa-search"></i> 매칭 진단</a>
                <a href="/admin_stills" class="nav-tab"><i class="fas fa-image"></i> 스틸컷 현황</a>
                <a href="/admin/db_pro" class="nav-tab active" style="background: var(--accent); color: white;"><i class="fas fa-database"></i> DB Pro (데이터 관리)</a>
            </div>

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
                <!-- 특정 작품 그룹화 재정렬 -->
            <div class="action-card">
                <div class="card-title"><i class="fas fa-layer-group"></i> 특정 작품 그룹화 재정렬</div>
                <div class="input-group">
                    <select id="regroupCategory" onchange="fetchPathHints()" style="width: 100%; background: #0f172a; border: 1px solid #334155; padding: 10px; border-radius: 8px; color: white; font-size: 13px; margin-bottom: 5px;">
                        <option value="전체">전체 카테고리</option>
                        <option value="movies">영화</option>
                        <option value="koreantv">국내TV</option>
                        <option value="foreigntv">외국TV</option>
                        <option value="animations_all">애니메이션</option>
                        <option value="air">방송중</option>
                    </select>
                    <input type="text" id="regroupKeyword" placeholder="검색 키워드 (예: 블리치)">
                    <input type="text" id="regroupPath" list="pathHints" placeholder="포함할 경로 (선택 또는 직접 입력)">
                    <datalist id="pathHints"></datalist>
                    <button class="btn-meta" onclick="regroupByKeyword()" style="justify-content: center; background: var(--accent); margin-top: 5px;">
                        <i class="fas fa-sync-alt"></i> 지정 범위 재정렬 실행
                    </button>
                </div>
            </div>
                <!-- 수동 메타데이터 정보 수정 -->
                <div class="action-card">
                    <div class="card-title"><i class="fas fa-edit"></i> 수동 텍스트 정보 수정</div>
                    <div class="input-group">
                        <select id="editCategorySelect" style="width: 100%; background: #0f172a; border: 1px solid #334155; padding: 10px; border-radius: 8px; color: white; font-size: 13px; margin-bottom: 5px;">
                            <option value="전체">전체 카테고리</option>
                            <option value="영화">영화</option>
                            <option value="국내TV">국내TV</option>
                            <option value="외국TV">외국TV</option>
                            <option value="애니메이션">애니메이션</option>
                            <option value="방송중">방송중</option>
                        </select>
                        <div style="display: flex; gap: 5px;">
                            <input type="text" id="editSearchName" placeholder="작품 제목 (예: 미공개X파일)" style="flex: 1;">
                            <button class="btn-scan" onclick="loadMetadataForEdit(this)" style="width: auto; padding: 10px; justify-content: center;"><i class="fas fa-search"></i> 불러오기</button>
                        </div>

                        <!-- 불러오기를 누르면 나타나는 실제 수정 폼 영역 -->
                        <div id="editFormArea" style="display: none; margin-top: 15px; flex-direction: column; gap: 10px; border-top: 1px solid #334155; padding-top: 15px;">
                            <div>
                                <label style="font-size: 11px; color: var(--text-dim); display: block; margin-bottom: 3px;">표시 제목 (tmdbTitle - 앱에서 보이는 이름)</label>
                                <input type="text" id="editTitle" style="width: 100%; box-sizing: border-box;">
                            </div>
                            <div>
                                <label style="font-size: 11px; color: var(--text-dim); display: block; margin-bottom: 3px;">그룹화 이름 (cleanedName - 같은 작품끼리 묶는 기준)</label>
                                <input type="text" id="editCleanedName" style="width: 100%; box-sizing: border-box; border-color: var(--primary);">
                            </div>
                            <!-- 나머지 year, overview 등 기존 필드 유지 -->
                            <div>
                                <label style="font-size: 11px; color: var(--text-dim); display: block; margin-bottom: 3px;">방영/개봉 연도</label>
                                <input type="text" id="editYear" style="width: 100%; box-sizing: border-box;">
                            </div>
                            <div>
                                <label style="font-size: 11px; color: var(--text-dim); display: block; margin-bottom: 3px;">표시 제목 (tmdbTitle)</label>
                                <input type="text" id="editTitle" style="width: 100%; box-sizing: border-box;">
                            </div>
                            <div>
                                <label style="font-size: 11px; color: var(--text-dim); display: block; margin-bottom: 3px;">방영/개봉 연도</label>
                                <input type="text" id="editYear" style="width: 100%; box-sizing: border-box;">
                            </div>
                            <div>
                                <label style="font-size: 11px; color: var(--text-dim); display: block; margin-bottom: 3px;">줄거리 요약</label>
                                <textarea id="editOverview" rows="4" style="width: 100%; box-sizing: border-box; background: #0f172a; border: 1px solid #334155; border-radius: 8px; color: white; padding: 10px; font-family: inherit; font-size: 13px; resize: vertical;"></textarea>
                            </div>
                            <div>
                                <label style="font-size: 11px; color: var(--text-dim); display: block; margin-bottom: 3px;">감독</label>
                                <input type="text" id="editDirector" style="width: 100%; box-sizing: border-box;">
                            </div>
                            <div>
                                <label style="font-size: 11px; color: var(--text-dim); display: block; margin-bottom: 3px;">출연진 (쉼표로 구분하여 입력)</label>
                                <input type="text" id="editActors" style="width: 100%; box-sizing: border-box;">
                            </div>
                            <div>
                                <label style="font-size: 11px; color: var(--text-dim); display: block; margin-bottom: 3px;">장르 (쉼표로 구분하여 입력)</label>
                                <input type="text" id="editGenres" style="width: 100%; box-sizing: border-box;">
                            </div>

                            <button class="btn-meta" onclick="saveManualMetadata(this)" style="justify-content: center; margin-top: 5px; background: var(--accent);">
                                <i class="fas fa-save"></i> 정보 수정 저장
                            </button>
                        </div>
                    </div>
                </div>
                <div class="action-card">
                <div class="sidebar">
                <!-- 특정 폴더 정밀 스캔 및 갱신 -->
<div class="action-card">
    <div class="card-title"><i class="fas fa-folder-search"></i> 특정 폴더 정밀 스캔</div>
    <div class="input-group">
        <select id="scanTargetCategory" style="width: 100%; background: #0f172a; border: 1px solid #334155; padding: 10px; border-radius: 8px; color: white; font-size: 13px; margin-bottom: 5px;">
            <option value="movies">영화 (movies)</option>
            <option value="koreantv">국내TV (koreantv)</option>
            <option value="foreigntv">외국TV (foreigntv)</option>
            <option value="animations_all" selected>애니메이션 (animations_all)</option>
            <option value="air">방송중 (air)</option>
        </select>
        <input type="text" id="scanTargetFolder" placeholder="스캔할 폴더명 (예: 원피스)">
        <button class="btn-meta" onclick="scanTargetedFolder()" style="justify-content: center; background: var(--primary); margin-top: 5px;">
            <i class="fas fa-search-plus"></i> 폴더 스캔 & 메타데이터 갱신
        </button>
        <p style="font-size: 11px; color: var(--text-dim); margin-top: 5px;">* 서버의 실제 폴더명을 입력하세요. 해당 폴더만 다시 읽고 정보를 가져옵니다.</p>
    </div>
</div>
                    <div class="action-card">
                        <div class="card-title"><i class="fas fa-search"></i> 스캔 및 매칭 (전체)</div>
                        <div class="btn-list">
                            <button class="btn-meta" onclick="triggerTask('/api/match_air_foreign')"><i class="fas fa-bolt"></i> 외국 폴더 핀셋 매칭 (추천)</button>
                            <button class="btn-scan" onclick="triggerTask('/api/scan_air_foreign')"><i class="fas fa-folder-open"></i> 외국 폴더 로컬 스캔</button>
                            <button class="btn-scan" onclick="triggerTask('/rescan_broken')"><i class="fas fa-sync"></i> 전체 로컬 폴더 스캔</button>

                        </div>
                    </div>
                    <!--1. 개별 작품 수정 (통합 버전) -->
                    <div class="action-card">
                        <div class="card-title"><i class="fas fa-magic"></i> 매칭 지정 수정</div>
                        <div class="input-group">
                            <select id="fixCategorySelect" style="width: 100%; background: #0f172a; border: 1px solid #334155; padding: 10px; border-radius: 8px; color: white; font-size: 13px; margin-bottom: 5px;">
                                <option value="전체">전체 카테고리</option>
                                <option value="영화">영화</option>
                                <option value="국내TV">국내TV</option>
                                <option value="외국TV">외국TV</option>
                                <option value="애니메이션">애니메이션</option>
                                <option value="방송중">방송중</option>
                            </select>
                            <input type="text" id="fixNameInput" placeholder="작품 제목 (예: 미공개X파일)">
                            <input type="text" id="tmdbIdInput" placeholder="TMDB ID (예: tv:32863)">
                            <div style="display: grid; grid-template-columns: 1fr 1fr; gap: 8px; margin-top: 5px;">
                                <button class="btn-meta" onclick="manualMatchSimple()" style="justify-content: center;"><i class="fas fa-link"></i> 수동 연결\n(로컬제목으로고정)</button>
                                <button class="btn-meta" onclick="manualMatchV2()" style="justify-content: center; background: var(--accent);"><i class="fas fa-sync-alt"></i> 수동 연결 V2\n(tmdb제목으로 변경됨)</button>
                                <button class="btn-maintenance" onclick="fixMetadata()" style="justify-content: center;"><i class="fas fa-wand-magic-sparkles"></i> 자동 수정</button>
                                <button class="btn-maintenance" onclick="resetAndRefresh()" style="justify-content: center; background: var(--danger);"><i class="fas fa-trash-alt"></i> 캐시삭제 & 재매칭</button>
                                <button class="btn-meta" onclick="triggerTask('/retry_failed_metadata')" style="justify-content: center; background: #10b981; grid-column: span 2; margin-top: 5px;"><i class="fas fa-redo"></i> 실패 메타데이터 재시도</button>
                                <button class="btn-meta" onclick="manualMatchTargeted()" style="justify-content: center; background: #2563eb; grid-column: span 2; margin-top: 5px;">
                                    <i class="fas fa-bullseye"></i> 카테고리 지정 메타데이터 정밀 연결
                                </button>
                                <button class="btn-danger-alt" onclick="triggerTask('/api/admin/force_clear_one_piece_stills')" style="justify-content: center; background: #991b1b; grid-column: span 2; margin-top: 5px;">
    <i class="fas fa-eraser"></i> 원피스 실사판 썸네일 강제 제거
</button>
                            </div>
                        </div>
                    </div>

                    <!-- 2. 수동 포스터 변경 -->
                    <div class="action-card">
                        <div class="card-title"><i class="fas fa-image"></i> 수동 포스터 변경</div>
                        <div class="input-group">
                            <select id="posterCategorySelect" style="width: 100%; background: #0f172a; border: 1px solid #334155; padding: 10px; border-radius: 8px; color: white; font-size: 13px; margin-bottom: 5px;">
                                <option value="전체">전체 카테고리</option>
                                <option value="영화">영화</option>
                                <option value="국내TV">국내TV</option>
                                <option value="외국TV">외국TV</option>
                                <option value="애니메이션">애니메이션</option>
                                <option value="방송중">방송중</option>
                            </select>
                            <input type="text" id="posterNameInput" placeholder="작품 제목 (예: 미공개X파일)">
                            <input type="file" id="posterFileInput" accept="image/*" style="background: #1e293b; padding: 8px;">
                            <button class="btn-meta" onclick="uploadCustomPoster(this)" style="justify-content: center; margin-top: 5px; background: #10b981;">
                                <i class="fas fa-upload"></i> 포스터 이미지 변경
                            </button>
                        </div>
                    </div>

                    <!-- 3. 고급 도구 -->
                    <div class="action-card">
    <div class="card-title"><i class="fas fa-tools"></i> 고급 도구</div>
    <div class="input-group" style="margin-top: 0;">
        <select id="stillsCategory" style="width: 100%; background: #0f172a; border: 1px solid #334155; padding: 10px; border-radius: 8px; color: white; font-size: 13px; margin-bottom: 5px;">
            <option value="">전체 카테고리</option>
            <option value="movies">영화</option>
            <option value="koreantv">국내TV</option>
            <option value="foreigntv">외국TV</option>
            <option value="animations_all">애니메이션</option>
            <option value="air">방송중</option>
        </select>
        <button class="btn-maintenance" onclick="applyStillsByCategory()"><i class="fas fa-image"></i> 선택 카테고리 스틸컷 적용</button>
    </div>
    <div class="btn-list" style="margin-top:15px; border-top: 1px solid #334155; padding-top: 15px;">
        <button class="btn-maintenance" onclick="triggerTask('/pre_extract_subtitles')"><i class="fas fa-closed-captioning"></i> 영화 자막 일괄 추출</button>
        <button class="btn-maintenance" onclick="triggerTask('/refresh_cleaned_names')"><i class="fas fa-broom"></i> 제목 정제 및 그룹화 재정렬</button>
        <button class="btn-danger-alt" onclick="triggerTask('/reset_episodes_metadata')"><i class="fas fa-undo"></i> 에피소드 회차 정보 초기화</button>
        <button class="btn-danger-alt" onclick="triggerTask('/reset_all_tmdb_data')"><i class="fas fa-trash-alt"></i> 전체 TMDB 데이터 초기화</button>
        <button class="btn-meta" onclick="triggerTask('/api/admin/match_movies_all')" style="background: #E50914;">
    <i class="fas fa-film"></i> 영화 카테고리 포스터 집중 매칭
    </button>
    </div>
                    </div>
                </div>
            </div>
        </div>

    <script>
    function applyStillsByCategory() {
        const category = document.getElementById('stillsCategory').value;
        const catName = category || '전체';
        if (confirm(`'${catName}' 카테고리의 스틸컷을 적용하시겠습니까?`)) {
            triggerTask(`/apply_tmdb_thumbnails?category=${category}`);
        }
    }
    // 카테고리별 하위 폴더 힌트 가져오기
    // 힌트 가져오기
    async function fetchPathHints() {
        const cat = document.getElementById('regroupCategory').value;
        const datalist = document.getElementById('pathHints');
        const pathInput = document.getElementById('regroupPath');

        datalist.innerHTML = '';
        if (cat === '전체') return;

        try {
            const resp = await fetch(`/api/admin/get_path_hints?category=${cat}`);
            const hints = await resp.json();
            hints.forEach(h => {
                const opt = document.createElement('option');
                opt.value = h;
                datalist.appendChild(opt);
            });
            console.log(`[HINTS] ${hints.length}개 로드 완료`);
        } catch (e) { console.error("힌트 로딩 실패:", e); }
    }

    // 그룹화 재정렬
    async function regroupByKeyword() {
        const keyword = document.getElementById('regroupKeyword').value.trim();
        const category = document.getElementById('regroupCategory').value;
        const path = document.getElementById('regroupPath').value.trim();

        if (!keyword) { alert('키워드를 입력하세요.'); return; }

        if (confirm(`'${keyword}' 관련 데이터를 재분석하시겠습니까?\n범위: ${category}${path ? ' > ' + path : ''}`)) {
            let url = `/api/refresh_by_keyword?name=${encodeURIComponent(keyword)}`;
            if (category !== '전체') url += `&category=${encodeURIComponent(category)}`;
            if (path) url += `&path=${encodeURIComponent(path)}`;

            try {
                const resp = await fetch(url);
                const data = await resp.json();
                if (data.status === 'success') {
                    alert('재정렬 시작! 로그 창을 확인하세요.');
                    document.getElementById('terminalBox').scrollIntoView({ behavior: 'smooth' });
                } else {
                    alert('에러: ' + data.message);
                }
            } catch (e) { alert('요청 오류: ' + e); }
        }
    }


            async function scanTargetedFolder() {
                const category = document.getElementById('scanTargetCategory').value;
                const folder = document.getElementById('scanTargetFolder').value.trim();

                if (!folder) {
                    alert('스캔할 폴더명을 입력하세요.');
                    return;
                }

                if (confirm(`'${category}' 카테고리의 '${folder}' 폴더를 정밀 스캔하고 메타데이터를 갱신하시겠습니까?`)) {
                    try {
                        const resp = await fetch(`/api/admin/scan_targeted?category=${category}&folder=${encodeURIComponent(folder)}`);
                        const data = await resp.json();
                        alert(data.message);
                        // 상단 로그 창으로 이동
                        document.getElementById('terminalBox').scrollIntoView({ behavior: 'smooth' });
                    } catch (e) {
                        alert('요청 중 오류 발생: ' + e);
                    }
                }
            }
            async function manualMatchTargeted() {
                const name = document.getElementById('fixNameInput').value.trim();
                const id = document.getElementById('tmdbIdInput').value.trim();
                const category = document.getElementById('fixCategorySelect').value;

                if (!name || !id || category === '전체') {
                    alert('제목, ID, 그리고 범위를 좁힐 구체적인 [카테고리]를 선택하세요.');
                    return;
                }

                if (confirm(`'${category}' 카테고리에 있는 '${name}' 관련 항목만 매칭하시겠습니까?\n(다른 카테고리의 원피스는 수정되지 않습니다.)`)) {
                    const resp = await fetch(`/api/manual_match_targeted?name=${encodeURIComponent(name)}&id=${encodeURIComponent(id)}&category=${encodeURIComponent(category)}`);
                    alert(await resp.text());
                }
            }
            async function regroupByKeyword() {
                const keyword = document.getElementById('regroupKeyword').value.trim();
                if (!keyword) { alert('키워드를 입력하세요.'); return; }

                if (confirm(`'${keyword}' 관련 데이터를 원본 기준으로 재분석하고 그룹화를 다시 수행하시겠습니까?`)) {
                    try {
                        const resp = await fetch(`/api/refresh_by_keyword?name=${encodeURIComponent(keyword)}`);
                        const data = await resp.json();
                        if (data.status === 'success') {
                            // 상단 로그 창으로 시선 이동
                            document.getElementById('terminalBox').scrollIntoView({ behavior: 'smooth' });
                        } else {
                            alert('에러: ' + data.message);
                        }
                    } catch (e) {
                        alert('요청 중 오류 발생: ' + e);
                    }
                }
            }
            async function resetAndRefresh() {
                const name = document.getElementById('fixNameInput').value.trim();
                const category = document.getElementById('fixCategorySelect').value;

                if (!name) { alert('초기화할 작품 제목을 입력하세요.'); return; }

                if (confirm(`'${category}' 카테고리의 '${name}' 관련 모든 메타데이터와 로컬 캐시를 삭제하고 새로 가져오시겠습니까?`)) {
                    const resp = await fetch(`/api/reset_and_refresh_metadata?name=${encodeURIComponent(name)}&category=${encodeURIComponent(category)}`);
                    const data = await resp.json();
                    alert(data.message);
                }
            }
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

            // DB에서 기존 정보를 불러와 폼에 채우는 함수
            async function loadMetadataForEdit(btn) {
                const category = document.getElementById('editCategorySelect').value;
                const name = document.getElementById('editSearchName').value.trim();

                if (!name) { alert('작품 제목을 입력하세요.'); return; }

                const originalText = btn.innerHTML;
                btn.innerHTML = '<i class="fas fa-spinner fa-spin"></i>';

                try {
                    const resp = await fetch(`/api/get_metadata_for_edit?category=${encodeURIComponent(category)}&name=${encodeURIComponent(name)}`);
                    if (!resp.ok) {
                        const err = await resp.json();
                        alert('불러오기 실패: ' + err.error);
                        return;
                    }
                    const data = await resp.json();

                    document.getElementById('editTitle').value = data.tmdbTitle || '';
                    document.getElementById('editCleanedName').value = data.cleanedName || ''; // [추가]
                    document.getElementById('editYear').value = data.year || '';
                    document.getElementById('editOverview').value = data.overview || '';
                    document.getElementById('editDirector').value = data.director || '';

                    // JSON 배열 형태의 배우/장르를 쉼표 문자열로 예쁘게 변환해서 보여줌
                    let actorsStr = '';
                    try {
                        const actorsArr = JSON.parse(data.actors || '[]');
                        actorsStr = actorsArr.map(a => a.name).join(', ');
                    } catch(e) { actorsStr = data.actors; }
                    document.getElementById('editActors').value = actorsStr;

                    let genresStr = '';
                    try {
                        const genresArr = JSON.parse(data.genreNames || '[]');
                        genresStr = genresArr.join(', ');
                    } catch(e) { genresStr = data.genreNames; }
                    document.getElementById('editGenres').value = genresStr;

                    // 폼 영역을 보여줌
                    document.getElementById('editFormArea').style.display = 'flex';

                } catch(e) {
                    alert('에러 발생: ' + e);
                } finally {
                    btn.innerHTML = originalText;
                }
            }

            // 수정한 정보를 다시 서버로 보내 DB를 덮어쓰는 함수
            async function saveManualMetadata(btn) {
                const category = document.getElementById('editCategorySelect').value;
                const name = document.getElementById('editSearchName').value.trim();

                if (!confirm(`'${name}'의 정보를 정말 이 내용으로 덮어쓰시겠습니까?`)) return;

                const payload = {
                    category: category,
                    name: name,
                    tmdbTitle: document.getElementById('editTitle').value.trim(),
                    cleanedName: document.getElementById('editCleanedName').value.trim(), // [추가]
                    year: document.getElementById('editYear').value.trim(),
                    overview: document.getElementById('editOverview').value.trim(),
                    director: document.getElementById('editDirector').value.trim(),
                    actors: document.getElementById('editActors').value.trim(),
                    genres: document.getElementById('editGenres').value.trim()
                };

                const originalText = btn.innerHTML;
                btn.disabled = true;
                btn.innerHTML = '<i class="fas fa-spinner fa-spin"></i> 저장 중...';

                try {
                    const resp = await fetch('/api/save_manual_metadata', {
                        method: 'POST',
                        headers: {'Content-Type': 'application/json'},
                        body: JSON.stringify(payload)
                    });
                    const data = await resp.json();

                    if (data.status === 'success') {
                        alert(data.message);
                    } else {
                        alert('실패: ' + data.message);
                    }
                } catch(e) {
                    alert('저장 중 에러 발생: ' + e);
                } finally {
                    btn.disabled = false;
                    btn.innerHTML = originalText;
                }
            }


            async function uploadCustomPoster(btn) {
                try {
                    const category = document.getElementById('posterCategorySelect').value;
                    const name = document.getElementById('posterNameInput').value.trim();
                    const fileInput = document.getElementById('posterFileInput');

                    if (!name) { alert('작품 제목을 입력하세요.'); return; }
                    if (!fileInput.files || fileInput.files.length === 0) { alert('변경할 이미지를 선택하세요.'); return; }

                    if (!confirm(`'${name}'의 포스터를 외부 서버에 업로드하여 변경하시겠습니까?`)) return;

                    const formData = new FormData();
                    formData.append('category', category);
                    formData.append('name', name);
                    formData.append('file', fileInput.files[0]);

                    // 버튼 상태 표시
                    const originalText = btn.innerHTML;
                    btn.disabled = true;
                    btn.style.opacity = '0.5';
                    btn.innerHTML = '<i class="fas fa-spinner fa-spin"></i> 업로드 중...';

                    const resp = await fetch('/api/upload_custom_poster', {
                        method: 'POST',
                        body: formData
                    });

                    const data = await resp.json();
                    if (data.status === 'success') {
                        alert('성공적으로 변경되었습니다!');
                        fileInput.value = '';
                    } else {
                        alert('실패: ' + data.message);
                    }

                    // 버튼 복구
                    btn.disabled = false;
                    btn.style.opacity = '1';
                    btn.innerHTML = originalText;

                } catch (e) {
                    alert('에러 발생: ' + e);
                    btn.disabled = false;
                    btn.style.opacity = '1';
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
    log("SYSTEM", "⚡ 메모리 캐시 최적화 빌드 시작 (영화 데이터 강제 복구 모드)...")
    _SECTION_CACHE = {}
    _DETAIL_MEM_CACHE = {}
    temp_cache = {}
    conn = get_db()

    # 스캐너에서 사용하는 정확한 카테고리 목록
    CATS = ["movies", "foreigntv", "koreantv", "animations_all", "air"]

    for cat in CATS:
        if cat == 'movies':
            # DB에 movies 또는 movie로 저장된 모든 데이터를 긁어옴
            query = "SELECT s.*, (SELECT COUNT(DISTINCT season_number) FROM episodes WHERE series_path = s.path) as actual_seasons FROM series s WHERE (s.category = 'movies' OR s.category = 'movie') ORDER BY s.yearVal DESC"
            rows = conn.execute(query).fetchall()
        else:
            query = "SELECT s.*, (SELECT COUNT(DISTINCT season_number) FROM episodes WHERE series_path = s.path) as actual_seasons FROM series s WHERE s.category = ? ORDER BY s.yearVal DESC"
            rows = conn.execute(query, (cat,)).fetchall()
        items = []
        seen_keys = set()
        folder_precalc = {}

        for r in rows:
            path = nfc(r['path'])
            name = nfc(r['name'])

            # [수정] 영화 카테고리는 중복 제거를 하지 않음 (모든 영화 노출 보장)
            if cat == 'movies':
                group_key = path
            else:
                path_lower, name_lower = path.lower(), name.lower()
                tag = "더빙" if "더빙" in path_lower or "더빙" in name_lower else "자막" if "자막" in path_lower or "자막" in name_lower else ""
                tmdb_id = r['tmdbId']
                c_name = nfc(r['cleanedName'] or r['name'])
                is_special = any(k in c_name for k in SPECIAL_GRANULAR_GROUPS)
                sub_folder = ""
                if is_special:
                    parts = path.split('/')
                    sub_folder = parts[1] if len(parts) > 2 else ""
                group_key = f"{tmdb_id}_{c_name}_{tag}_{sub_folder}" if tmdb_id else f"{c_name}_{tag}_{sub_folder}"

            if not group_key or group_key in seen_keys: continue
            if not r['posterPath']: continue
            seen_keys.add(group_key)

            # 영화는 TMDB 제목이나 파일명을 우선 노출
            display_name = nfc(r['tmdbTitle'] or r['name']) if cat == 'movies' else (
                c_name if c_name else nfc(r['tmdbTitle'] or r['name']))

            if cat != 'movies' and 'is_special' in locals():
                if is_special and sub_folder: display_name = f"{sub_folder} > {display_name}"
                if tag and f"[{tag}]" not in display_name: display_name = f"{display_name} [{tag}]"

            item = {
                "path": path, "name": display_name.strip(), "posterPath": r['posterPath'],
                "year": r['year'], "genreNames": [], "tmdbId": r['tmdbId'], "rating": r['rating'],
                "seasonCount": r['actual_seasons'] if r['actual_seasons'] and r['actual_seasons'] > 0 else (
                            r['seasonCount'] or 1),
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
        log("SYSTEM", f"✅ {cat} 캐시 빌드 완료 (아이템 수: {len(items)})")

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

    # 1. 띄어쓰기를 무시하고 검색할 수 있도록 검색어 다듬기
    safe_target = target_name.strip()
    search_pattern = f"%{safe_target}%"
    search_pattern_no_space = f"%{safe_target.replace(' ', '')}%"

    emit_ui_log(f"수동 ID 매칭 시작: '{safe_target}' -> {full_id}", "info")
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
        updated_count = 0
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

                # [수정포인트] 검색 조건을 더 강력하게 (띄어쓰기 무시 조건 추가)
                cursor.execute("""
                    UPDATE series
                    SET posterPath=?, year=?, overview=?, seasonCount=?,
                        genreIds=?, genreNames=?, director=?, actors=?, tmdbId=?, failed=0,
                        runtime=?
                    WHERE name LIKE ? OR cleanedName LIKE ?
                       OR REPLACE(name, ' ', '') LIKE ? OR REPLACE(cleanedName, ' ', '') LIKE ?
                """, (*up, search_pattern, search_pattern, search_pattern_no_space, search_pattern_no_space))

                updated_count = cursor.rowcount
                conn.commit()
                break
            except sqlite3.OperationalError:
                time.sleep(2)
            finally:
                if conn: conn.close()

        if updated_count > 0:
            build_all_caches()
            emit_ui_log(f"성공! '{safe_target}' 관련 {updated_count}건에 깨끗한 포스터를 연결했습니다. (제목은 유지됨)", "success")
            return f"성공! {updated_count}개 항목의 포스터를 교체했습니다."

        # [수정포인트] 대상이 없을 경우, DB에 어떤 비슷한 이름들이 있는지 힌트를 주도록 에러 메시지 보강
        emit_ui_log(f"실패: DB에 '{safe_target}' 이름이 들어간 작품을 찾을 수 없습니다.", "warning")
        return f"대상 없음: DB에서 '{safe_target}'을(를) 찾을 수 없습니다. 철자와 띄어쓰기를 확인하세요.", 404

    except Exception as e:
        emit_ui_log(f"에러: {str(e)}", "error")
        return f"에러 발생: {str(e)}"

@app.route('/api/manual_match_v2')
def manual_match_v2():
    """TMDB 정보를 연결하고, 제목(tmdbTitle)과 그룹명(cleanedName)까지 TMDB 공식 명칭으로 강제 업데이트하는 기능"""
    target_name = request.args.get('name')
    full_id = request.args.get('id')

    if not target_name or not full_id or ':' not in full_id:
        return "오류: 작품 키워드와 TMDB ID가 필요합니다.", 400

    emit_ui_log(f"수동 연결 v2 시작: '{target_name}' -> {full_id} (제목 및 그룹명 갱신 포함)", "info")
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

        # 공식 제목(TMDB)
        tmdb_title = d_resp.get('title') or d_resp.get('name')

        conn = get_db()
        cursor = conn.cursor()

        # 업데이트 데이터 (tmdbTitle과 cleanedName 동시 갱신)
        up = (
            d_resp.get('poster_path'), yv, d_resp.get('overview'),
            d_resp.get('number_of_seasons'),
            json.dumps([g['id'] for g in d_resp.get('genres', [])]),
            json.dumps(genre_names, ensure_ascii=False),
            director,
            json.dumps(actors, ensure_ascii=False),
            full_id,
            tmdb_title,  # <-- 1. 공식 제목 (tmdbTitle)
            runtime,  # <-- 2. 런타임
            tmdb_title,  # <-- 3. [추가] cleanedName도 TMDB 이름으로 덮어씀
            f'%{target_name}%', f'%{target_name}%'
        )

        cursor.execute("""
            UPDATE series
            SET posterPath=?, year=?, overview=?, seasonCount=?,
                genreIds=?, genreNames=?, director=?, actors=?, tmdbId=?, failed=0,
                tmdbTitle=?, runtime=?, cleanedName=?  -- <-- cleanedName 추가됨
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

@app.route('/api/manual_match_targeted')
def manual_match_targeted():
    """특정 카테고리를 선택하여 해당 카테고리의 작품만 매칭 정보를 갱신합니다."""
    target_name = request.args.get('name')
    full_id = request.args.get('id')
    category_filter = request.args.get('category')

    if not target_name or not full_id or not category_filter or category_filter == '전체' or ':' not in full_id:
        return "오류: 작품 이름, ID, 그리고 대상을 지정할 '구체적인 카테고리' 선택이 필요합니다.", 400

    emit_ui_log(f"정밀 매칭 시작: [{category_filter}] {target_name} -> {full_id}", "info")
    m_type, t_id = full_id.split(':')

    try:
        headers = {"Authorization": f"Bearer {TMDB_API_KEY}"}
        d_resp = requests.get(
            f"{TMDB_BASE_URL}/{m_type}/{t_id}?language=ko-KR&append_to_response=credits",
            headers=headers, timeout=10).json()

        if 'id' not in d_resp: return "TMDB ID를 찾을 수 없습니다.", 404

        tmdb_title = d_resp.get('title') or d_resp.get('name')
        yv = (d_resp.get('release_date') or d_resp.get('first_air_date') or "").split('-')[0]
        genre_names = [g['name'] for g in d_resp.get('genres', [])]
        cast_data = d_resp.get('credits', {}).get('cast', [])
        actors = [{"name": c['name'], "profile": c['profile_path'], "role": c['character']} for c in cast_data[:10]]
        crew_data = d_resp.get('credits', {}).get('crew', [])
        director = next((c['name'] for c in crew_data if c.get('job') == 'Director'), "")

        conn = get_db()
        cursor = conn.cursor()

        # 카테고리 이름 -> DB 내부 키 매핑
        cat_map = {"영화": "movies", "외국TV": "foreigntv", "국내TV": "koreantv", "애니메이션": "animations_all", "방송중": "air"}
        internal_cat = cat_map.get(category_filter, category_filter)

        up = (
            d_resp.get('poster_path'), yv, d_resp.get('overview'),
            d_resp.get('number_of_seasons'),
            json.dumps([g['id'] for g in d_resp.get('genres', [])]),
            json.dumps(genre_names, ensure_ascii=False),
            director,
            json.dumps(actors, ensure_ascii=False),
            full_id, tmdb_title, d_resp.get('runtime'), tmdb_title,
            internal_cat, f'%{target_name}%', f'%{target_name}%'
        )

        # WHERE 절에 category = ? 를 추가하여 범위를 한정함
        cursor.execute("""
            UPDATE series
            SET posterPath=?, year=?, overview=?, seasonCount=?,
                genreIds=?, genreNames=?, director=?, actors=?, tmdbId=?, failed=0,
                tmdbTitle=?, runtime=?, cleanedName=?
            WHERE category=? AND (name LIKE ? OR cleanedName LIKE ?)
        """, up)

        updated_count = cursor.rowcount
        conn.commit()
        conn.close()

        if updated_count > 0:
            build_all_caches()
            emit_ui_log(f"성공! [{category_filter}] 내 {updated_count}건 수정 완료.", "success")
            return f"성공! {category_filter} 카테고리의 {updated_count}개 항목 정보를 교체했습니다."
        return "수정할 대상을 찾지 못했습니다. 카테고리와 이름을 다시 확인하세요.", 404
    except Exception as e:
        return f"에러: {str(e)}"

@app.route('/api/view_conan_data')
def view_conan_data():
    """데이터가 많아도 락(Lock) 없이 빠르게 확인할 수 있는 그룹 요약 및 페이징 API"""
    search_q = request.args.get('q', '원피스')
    limit = int(request.args.get('limit', 50))  # 한 페이지에 보여줄 개수
    offset = int(request.args.get('offset', 0))  # 시작 위치

    conn = None
    try:
        conn = get_db()
        search_pattern = f'%{search_q}%'

        # 1. [요약 통계] 어떤 이름(cleanedName)으로 몇 개씩 묶여있는지 요약 (가장 중요)
        # 이 통계를 보면 '원피스'로 잘 묶였는지, '아'로 묶인 게 남아있는지 한눈에 보입니다.
        summary_query = """
            SELECT cleanedName, COUNT(*) as cnt,
                   COUNT(tmdbId) as matched_cnt,
                   MAX(tmdbTitle) as sample_tmdb_title
            FROM series
            WHERE (name LIKE ? OR path LIKE ?)
            GROUP BY cleanedName
            ORDER BY cnt DESC
        """
        summary_rows = conn.execute(summary_query, (search_pattern, search_pattern)).fetchall()

        summary = []
        for r in summary_rows:
            summary.append({
                "그룹명(cleanedName)": r['cleanedName'],
                "항목수": r['cnt'],
                "매칭완료": r['matched_cnt'],
                "TMDB표시이름": r['sample_tmdb_title']
            })

        # 2. [상세 목록] 페이징을 적용하여 상단 일부 데이터만 반환
        list_query = """
            SELECT category, name, cleanedName, posterPath, tmdbId, path, tmdbTitle
            FROM series
            WHERE (name LIKE ? OR path LIKE ?)
            ORDER BY path ASC
            LIMIT ? OFFSET ?
        """
        rows = conn.execute(list_query, (search_pattern, search_pattern, limit, offset)).fetchall()

        items = []
        for r in rows:
            items.append({
                "카테고리": r['category'],
                "파일명": r['name'],
                "그룹명": r['cleanedName'],
                "매칭상태": "OK" if r['tmdbId'] else "FAIL",
                "포스터": "있음" if r['posterPath'] else "없음",
                "tmdbTitle": r['tmdbTitle'],
                "경로": r['path']
            })

        total_count = sum(s['항목수'] for s in summary)

        return gzip_response({
            "검색어": search_q,
            "전체항목수": total_count,
            "그룹별_요약(현재상태)": summary,
            "상세목록_페이지": items,
            "페이지정보": {
                "limit": limit,
                "offset": offset,
                "다음페이지": f"/api/view_conan_data?q={search_q}&offset={offset + limit}" if offset + limit < total_count else None
            }
        })
    except Exception as e:
        return jsonify({"status": "error", "message": str(e)})
    finally:
        if conn: conn.close()

@app.route('/api/view_null_conan_data')
def view_null_conan_data():
    try:
        conn = get_db()
        # 1. 이름이나 경로에 '명탐정 코난'이 포함된 것 중
        # 2. tmdbId 나 posterPath가 NULL인 항목들을 찾습니다.
        query = """
            SELECT path, name, cleanedName, category, posterPath, tmdbId,tmdbTitle
            FROM series
            WHERE (name LIKE '%명탐정 코난%' OR path LIKE '%명탐정 코난%')
            AND (tmdbId IS NULL OR posterPath IS NULL)
            ORDER BY path ASC
        """
        rows = conn.execute(query).fetchall()
        conn.close()

        # 3. 브라우저에서 보기 좋게 JSON 리스트로 변환
        null_data_list = []
        for row in rows:
            null_data_list.append({
                "1_카테고리": row['category'],
                "2_파일명(name)": row['name'],
                "3_현재그룹명(cleanedName)": row['cleanedName'],
                "4_포스터상태(posterPath)": row['posterPath'] if row['posterPath'] else "[날아감 - NULL]",
                "5_ID상태(tmdbId)": row['tmdbId'] if row['tmdbId'] else "[날아감 - NULL]",
                "6_파일경로(path)": row['path'],
                "7_tmdbTitle": row['tmdbTitle']
            })

        # 총 몇 개가 날아갔는지 요약 정보 포함
        result = {
            "총_피해_파일_수": len(null_data_list),
            "날아간_데이터_목록": null_data_list
        }

        # 보기 편하도록 JSON 응답 (한글 깨짐 방지)
        return Response(json.dumps(result, ensure_ascii=False, indent=4), mimetype='application/json')

    except Exception as e:
        return jsonify({"error": f"조회 중 에러 발생: {str(e)}"})


@app.route('/api/reset_and_refresh_metadata')
def api_reset_and_refresh():
    """특정 제목과 카테고리의 작품 및 에피소드 메타데이터를 완전히 초기화하고 재매칭합니다."""
    target_name = request.args.get('name')
    target_cat = request.args.get('category', '전체')

    if not target_name:
        return jsonify({"status": "error", "message": "작품 이름을 입력해주세요."}), 400

    def run_reset_task():
        # 1. 작업 시작 (UI 초기화)
        set_update_state(is_running=True, task_name=f"[{target_name}] 초기화 및 재매칭", total=100, current=0, success=0,
                         fail=0, clear_logs=True)
        emit_ui_log(f"🚀 [초기화 시작] 대상: '{target_name}' | 카테고리: {target_cat}", "info")
        time.sleep(1)  # UI 갱신 대기

        try:
            conn = get_db()
            cursor = conn.cursor()

            cat_map = {"영화": "movies", "외국TV": "foreigntv", "국내TV": "koreantv", "애니메이션": "animations_all", "방송중": "air"}
            internal_cat = cat_map.get(target_cat)

            # 2. 대상 작품 검색 및 로그
            set_update_state(current=10, current_item="DB 검색 중...")
            find_query = "SELECT cleanedName, yearVal, category, path FROM series WHERE (name LIKE ? OR cleanedName LIKE ?)"
            find_params = [f'%{target_name}%', f'%{target_name}%']
            if internal_cat:
                find_query += " AND category = ?"
                find_params.append(internal_cat)

            rows = conn.execute(find_query, find_params).fetchall()

            if not rows:
                emit_ui_log(f"⚠️ '{target_name}'에 해당하는 작품을 찾을 수 없습니다.", "warning")
                set_update_state(is_running=False, current_item="대상 없음")
                conn.close()
                return

            emit_ui_log(f"📦 총 {len(rows)}개의 시리즈/그룹을 발견했습니다. 캐시 파괴 중...", "info")
            time.sleep(1)

            # 3. TMDB 캐시 삭제 로그
            set_update_state(current=30, current_item="TMDB 로컬 캐시 삭제 중...")
            cache_count = 0
            for r in rows:
                cache_key = f"{r['cleanedName']}_{r['yearVal']}_{r['category']}" if r[
                    'yearVal'] else f"{r['cleanedName']}_{r['category']}"
                h = hashlib.md5(nfc(cache_key).encode()).hexdigest()
                cursor.execute("DELETE FROM tmdb_cache WHERE h = ?", (h,))
                if h in TMDB_MEMORY_CACHE:
                    del TMDB_MEMORY_CACHE[h]
                cache_count += 1
            emit_ui_log(f"🧹 TMDB 로컬 검색 캐시 {cache_count}건 삭제 완료.", "success")
            time.sleep(1)

            # 4. 에피소드 초기화 로그
            set_update_state(current=50, current_item="에피소드 정보 초기화 중...")
            emit_ui_log(f"🧼 에피소드 테이블 청소 중... (줄거리, 시즌/회차 번호, TMDB 썸네일)", "info")
            ep_update_query = """
                UPDATE episodes
                SET overview = NULL, air_date = NULL, season_number = NULL, episode_number = NULL,
                    thumbnailUrl = CASE WHEN thumbnailUrl LIKE 'http%' THEN NULL ELSE thumbnailUrl END
                WHERE series_path IN (
                    SELECT path FROM series WHERE (name LIKE ? OR cleanedName LIKE ?)
            """
            ep_params = [f'%{target_name}%', f'%{target_name}%']
            if internal_cat:
                ep_update_query += " AND category = ?"
                ep_params.append(internal_cat)
            ep_update_query += ")"

            cursor.execute(ep_update_query, ep_params)
            emit_ui_log(f"✅ 에피소드 레코드 {cursor.rowcount}건 초기화 성공.", "success")
            time.sleep(1)

            # 5. 시리즈 초기화 로그
            set_update_state(current=70, current_item="시리즈 메타데이터 삭제 중...")
            emit_ui_log(f"📝 시리즈 메타데이터(포스터, 줄거리, 출연진 등)를 비웁니다.", "info")
            ser_update_query = """
                UPDATE series
                SET tmdbId = NULL, posterPath = NULL, overview = NULL,
                    tmdbTitle = NULL, rating = NULL, genreNames = NULL,
                    actors = NULL, director = NULL, failed = 0, runtime = NULL
                WHERE (name LIKE ? OR cleanedName LIKE ?)
            """
            ser_params = [f'%{target_name}%', f'%{target_name}%']
            if internal_cat:
                ser_update_query += " AND category = ?"
                ser_params.append(internal_cat)

            cursor.execute(ser_update_query, ser_params)

            conn.commit()
            conn.close()

            set_update_state(current=90, current_item="메모리 캐시 갱신 중...")
            emit_ui_log(f"✨ 모든 데이터가 신선하게 비워졌습니다! 이제 재매칭을 가동합니다.", "success")
            time.sleep(1)

            # 캐시 빌드 및 재매칭 스레드 실행
            build_all_caches()
            set_update_state(current=100, current_item="초기화 완료, 매칭 스레드 시작")
            time.sleep(0.5)

            # 주의: 여기서는 상태 바를 끄지 않고 fetch_metadata_async에게 바톤을 넘깁니다.
            fetch_metadata_async(target_name=target_name)

        except Exception as e:
            emit_ui_log(f"❌ 작업 중 치명적 오류: {str(e)}", "error")
            set_update_state(is_running=False, current_item="오류 발생")

    # API 요청에는 즉시 응답하고, 무거운 작업은 백그라운드로 넘깁니다.
    threading.Thread(target=run_reset_task, daemon=True).start()
    return jsonify({"status": "success", "message": f"'{target_name}' 데이터 초기화 작업이 시작되었습니다. 로그 창을 확인하세요."})

@app.route('/api/upload_custom_poster', methods=['POST'])
def upload_custom_poster():
    """ImgBB를 사용하여 이미지를 외부 서버에 업로드하고 DB를 업데이트합니다."""
    try:
        # 무료 API 키 (직접 발급받아 교체하는 것을 권장합니다: https://api.imgbb.com/)
        IMGBB_API_KEY = "785b021132b54c5f3191d4f48ee3093d"

        if 'file' not in request.files:
            return jsonify({"status": "error", "message": "파일이 없습니다."}), 400

        file = request.files['file']
        category = request.form.get('category', '전체')
        name = request.form.get('name', '').strip()

        if not name:
            return jsonify({"status": "error", "message": "작품 제목을 입력하세요."}), 400

        # 1. ImgBB API로 이미지 전송
        img_data = file.read()
        files = {'image': img_data}
        params = {'key': IMGBB_API_KEY}

        log("UPLOAD", f"외부 서버(ImgBB) 업로드 시작: {name}")
        response = requests.post("https://api.imgbb.com/1/upload", params=params, files=files, timeout=30)
        res_json = response.json()

        if response.status_code != 200 or not res_json.get('success'):
            error_msg = res_json.get('error', {}).get('message', '알 수 없는 오류')
            return jsonify({"status": "error", "message": f"외부 서버 업로드 실패: {error_msg}"}), 500

        # 2. 업로드된 이미지의 URL 추출
        poster_url = res_json['data']['url']
        log("UPLOAD", f"업로드 완료 URL: {poster_url}")

        # 3. DB 업데이트
        conn = get_db()
        cursor = conn.cursor()
        search_pattern = f"%{name}%"

        cat_map = {"영화": "movies", "외국TV": "foreigntv", "국내TV": "koreantv", "애니메이션": "animations_all", "방송중": "air"}
        internal_cat = cat_map.get(category)

        if internal_cat:
            cursor.execute("""
                UPDATE series
                SET posterPath = ?
                WHERE category = ? AND (name LIKE ? OR cleanedName LIKE ?)
            """, (poster_url, internal_cat, search_pattern, search_pattern))
        else:
            cursor.execute("""
                UPDATE series
                SET posterPath = ?
                WHERE name LIKE ? OR cleanedName LIKE ?
            """, (poster_url, search_pattern, search_pattern))

        updated_count = cursor.rowcount
        conn.commit()
        conn.close()

        if updated_count > 0:
            build_all_caches()
            emit_ui_log(f"수동 포스터(외부) 적용 완료: '{name}'", "success")
            return jsonify({"status": "success", "message": "포스터가 성공적으로 변경되었습니다.", "url": poster_url})
        else:
            return jsonify({"status": "error", "message": "DB에서 대상을 찾을 수 없습니다."}), 404

    except Exception as e:
        log("UPLOAD_ERROR", str(e))
        return jsonify({"status": "error", "message": str(e)}), 500

# --- [추가] 수동 메타데이터 정보 수정 라우트 ---
@app.route('/api/get_metadata_for_edit')
def get_metadata_for_edit():
    cat = request.args.get('category', '전체')
    name = request.args.get('name', '').strip()
    if not name: return jsonify({"error": "이름을 입력하세요"}), 400

    conn = get_db()
    search_pattern = f"%{name}%"
    cat_map = {"영화": "movies", "외국TV": "foreigntv", "국내TV": "koreantv", "애니메이션": "animations_all", "방송중": "air"}
    internal_cat = cat_map.get(cat)

    if internal_cat:
        row = conn.execute("SELECT * FROM series WHERE category = ? AND (name LIKE ? OR cleanedName LIKE ?) LIMIT 1",
                           (internal_cat, search_pattern, search_pattern)).fetchone()
    else:
        row = conn.execute("SELECT * FROM series WHERE name LIKE ? OR cleanedName LIKE ? LIMIT 1",
                           (search_pattern, search_pattern)).fetchone()
    conn.close()

    if row:
        return jsonify({
            "tmdbTitle": row['tmdbTitle'] or "",
            "cleanedName": row['cleanedName'] or "",  # [추가]
            "year": row['year'] or "",
            "overview": row['overview'] or "",
            "director": row['director'] or "",
            "actors": row['actors'] or "[]",
            "genreNames": row['genreNames'] or "[]"
        })
    return jsonify({"error": "대상을 찾을 수 없습니다."}), 404


@app.route('/api/save_manual_metadata', methods=['POST'])
def save_manual_metadata():
    data = request.json
    cat = data.get('category', '전체')
    name = data.get('name', '').strip()

    if not name: return jsonify({"status": "error", "message": "대상이 지정되지 않았습니다."}), 400

    conn = get_db()
    cursor = conn.cursor()

    search_pattern = f"%{name}%"
    cat_map = {"영화": "movies", "외국TV": "foreigntv", "국내TV": "koreantv", "애니메이션": "animations_all", "방송중": "air"}
    internal_cat = cat_map.get(cat)

    # 출연진/장르 JSON 변환 로직 (기존과 동일)
    actors_input = data.get('actors', '').strip()
    actors_json = actors_input if actors_input.startswith('[') else json.dumps(
        [{"name": a.strip(), "profile": None, "role": ""} for a in actors_input.split(',') if a.strip()],
        ensure_ascii=False)

    genres_input = data.get('genres', '').strip()
    genres_json = genres_input if genres_input.startswith('[') else json.dumps(
        [g.strip() for g in genres_input.split(',') if g.strip()], ensure_ascii=False)

    # [수정] tmdbTitle, cleanedName 포함하여 업데이트
    up = (
        data.get('tmdbTitle'),
        data.get('cleanedName'),
        data.get('year'),
        data.get('overview'),
        data.get('director'),
        actors_json,
        genres_json
    )

    sql = '''UPDATE series SET tmdbTitle=?, cleanedName=?, year=?, overview=?, director=?, actors=?, genreNames=?, failed=0 '''

    if internal_cat:
        cursor.execute(sql + "WHERE category=? AND (name LIKE ? OR cleanedName LIKE ?)",
                       (*up, internal_cat, search_pattern, search_pattern))
    else:
        cursor.execute(sql + "WHERE name LIKE ? OR cleanedName LIKE ?", (*up, search_pattern, search_pattern))

    updated = cursor.rowcount
    conn.commit()
    conn.close()

    if updated > 0:
        build_all_caches()
        emit_ui_log(f"수동 메타데이터 수정 완료: '{name}' ({updated}건)", "success")
        return jsonify({"status": "success", "message": f"성공적으로 {updated}개 항목이 수정되었습니다."})
    return jsonify({"status": "error", "message": "수정할 대상을 찾을 수 없습니다."}), 404


@app.route('/api/admin/get_path_hints')
def get_path_hints():
    """카테고리별 하위 폴더 힌트 API"""
    cat = request.args.get('category')
    if not cat or cat == '전체' or cat not in _FAST_CATEGORY_CACHE:
        return jsonify([])
    # 폴더 구조에서 힌트 가져오기
    hints = sorted(list(_FAST_CATEGORY_CACHE[cat].get('folders', {}).keys()))
    return jsonify(hints)

@app.route('/api/refresh_by_keyword')
def refresh_by_keyword():
    """입력받은 키워드, 카테고리, 경로를 기준으로 대상을 한정하여 재정제합니다."""
    target_keyword = request.args.get('name')
    target_cat = request.args.get('category')
    target_path = request.args.get('path')

    if not target_keyword:
        return jsonify({"status": "error", "message": "키워드가 없습니다."}), 400

    def run_refresh():
        set_update_state(is_running=True, task_name=f"[{target_keyword}] 정밀 재정렬", total=0, current=0, success=0, fail=0,
                         clear_logs=True)
        emit_ui_log(f"분석 시작: 키워드='{target_keyword}', 카테고리='{target_cat}', 경로='{target_path}'", "info")
        try:
            conn = get_db()
            query = "SELECT path, name, cleanedName FROM series WHERE (name LIKE ? OR path LIKE ?)"
            params = [f'%{target_keyword}%', f'%{target_keyword}%']

            if target_cat and target_cat != '전체':
                query += " AND category = ?"
                params.append(target_cat)
            if target_path:
                query += " AND path LIKE ?"
                params.append(f'%{target_path}%')

            rows = conn.execute(query, params).fetchall()
            total = len(rows)
            set_update_state(total=total)
            emit_ui_log(f"총 {total}개의 관련 데이터를 분석합니다.", "info")

            updates = []
            for idx, row in enumerate(rows):
                if (idx + 1) % 100 == 0:
                    set_update_state(current=idx + 1, current_item=f"분석 중: {row['name'][:20]}...")

                # 최신 정제 로직 적용
                new_clean, _ = clean_title_complex(row['name'], full_path=row['path'])

                if new_clean != row['cleanedName']:
                    updates.append((new_clean, row['path']))
                    emit_ui_log(f"교정: '{row['cleanedName']}' -> '{new_clean}'", "info")

            update_count = len(updates)
            if update_count > 0:
                cursor = conn.cursor()
                for i in range(0, update_count, 1000):
                    batch = updates[i:i + 1000]
                    cursor.executemany("UPDATE series SET cleanedName = ? WHERE path = ?", batch)
                    conn.commit()
                    set_update_state(success=i + len(batch))
                emit_ui_log(f"업데이트 완료: {update_count}개 항목의 그룹명이 수정되었습니다.", "success")
            else:
                emit_ui_log("수정할 항목이 없습니다. 이미 최신 로직으로 정제되어 있습니다.", "info")

            conn.close()
            build_all_caches()
            set_update_state(is_running=False, current_item=f"완료! ({update_count}건 수정됨)")

        except Exception as e:
            emit_ui_log(f"오류 발생: {str(e)}", "error")
            set_update_state(is_running=False, current_item="오류 발생")

    threading.Thread(target=run_refresh, daemon=True).start()
    return jsonify({"status": "success", "message": f"'{target_keyword}' 재정렬 작업이 시작되었습니다."})

# --- [관리자: 유령 데이터 관리 기능 추가] ---

@app.route('/admin/ghost')
def admin_ghost_page():
    return """
    <html>
    <head>
        <title>NAS Player - Ghost Data Manager</title>
        <link href="https://cdnjs.cloudflare.com/ajax/libs/font-awesome/6.0.0/css/all.min.css" rel="stylesheet">
        <style>
            body { font-family: 'Pretendard', sans-serif; background: #0f172a; color: white; padding: 30px; }
            .container { max-width: 1200px; margin: 0 auto; }

            /* Navigation Tabs (공통 스타일) */
            .nav-tabs {
                display: flex;
                gap: 10px;
                margin-bottom: 30px;
                border-bottom: 1px solid #334155;
                padding-bottom: 15px;
            }
            .nav-tab {
                padding: 10px 20px;
                background: #1e293b;
                color: #94a3b8;
                text-decoration: none;
                border-radius: 8px;
                font-size: 14px;
                font-weight: 600;
                transition: all 0.2s;
                border: 1px solid #334155;
                display: flex;
                align-items: center;
                gap: 8px;
            }
            .nav-tab.active {
                background: #3b82f6;
                color: white;
                border-color: #3b82f6;
            }
            .nav-tab:hover:not(.active) { background: #334155; }

            .search-box { background: #1e293b; padding: 20px; border-radius: 12px; margin-bottom: 20px; display: flex; gap: 10px; align-items: center; }
            select, input { padding: 10px; border-radius: 6px; border: 1px solid #334155; background: #0f172a; color: white; }
            button { padding: 10px 20px; border-radius: 6px; border: none; background: #3b82f6; color: white; cursor: pointer; font-weight: bold; }
            button.delete-btn { background: #ef4444; padding: 5px 10px; font-size: 12px; }
            button.delete-all-btn { background: #dc2626; margin-left: auto; }
            table { width: 100%; border-collapse: collapse; background: #1e293b; border-radius: 12px; overflow: hidden; }
            th, td { padding: 15px; text-align: left; border-bottom: 1px solid #334155; }
            th { background: #334155; color: #94a3b8; }
            .status-ok { color: #10b981; font-weight: bold; }
            .status-ghost { color: #f87171; font-weight: bold; }
            .path-text { font-size: 11px; color: #64748b; word-break: break-all; }
        </style>
    </head>
    <body>
        <div class="container">
            <h1>👻 유령 데이터 관리자</h1>

            <!-- 상단 탭 메뉴 -->
            <div class="nav-tabs">
                <a href="/updater" class="nav-tab"><i class="fas fa-sync-alt"></i> 대시보드</a>
                <a href="/admin/ghost" class="nav-tab active"><i class="fas fa-ghost"></i> 유령 데이터 관리</a>
                <a href="/admin" class="nav-tab"><i class="fas fa-search"></i> 매칭 진단</a>
                <a href="/admin_stills" class="nav-tab"><i class="fas fa-image"></i> 스틸컷 현황</a>
            </div>
            <div class="search-box">
                <select id="category">
                    <option value="all">전체 카테고리</option>
                    <option value="movies">영화</option>
                    <option value="koreantv">국내TV</option>
                    <option value="foreigntv">외국TV</option>
                    <option value="animations_all">애니메이션</option>
                    <option value="air">방송중</option>
                </select>
                <input type="text" id="query" placeholder="제목 검색 (공백 시 전체)" style="flex: 1;">
                <button onclick="searchGhost()">검색 및 파일 확인</button>
                <button class="delete-all-btn" onclick="deleteGhostAll()">유령 데이터 일괄 삭제</button>
            </div>

            <table id="resultTable">
                <thead>
                    <tr>
                        <th>카테고리</th>
                        <th>제목</th>
                        <th>상태</th>
                        <th>관리</th>
                    </tr>
                </thead>
                <tbody id="resultBody">
                    <tr><td colspan="4" style="text-align:center;">검색 버튼을 눌러주세요.</td></tr>
                </tbody>
            </table>
        </div>

        <script>
            async function searchGhost() {
                const cat = document.getElementById('category').value;
                const q = document.getElementById('query').value;
                const tbody = document.getElementById('resultBody');
                tbody.innerHTML = '<tr><td colspan="4" style="text-align:center;">서버 파일 체크 중... 잠시만 기다려주세요.</td></tr>';

                const resp = await fetch(`/api/admin/ghost_list?cat=${cat}&q=${encodeURIComponent(q)}`);
                const data = await resp.json();

                tbody.innerHTML = '';
                if (data.length === 0) {
                    tbody.innerHTML = '<tr><td colspan="4" style="text-align:center;">검색 결과가 없습니다.</td></tr>';
                    return;
                }

                data.forEach(item => {
                    const row = document.createElement('tr');
                    row.innerHTML = `
                        <td>${item.category}</td>
                        <td>
                            <div>${item.name}</div>
                            <div class="path-text">${item.path}</div>
                        </td>
                        <td class="${item.exists ? 'status-ok' : 'status-ghost'}">
                            ${item.exists ? '✅ 파일 있음' : '⚠️ 파일 없음 (유령)'}
                        </td>
                        <td>
                            <button class="delete-btn" onclick="deleteSeries('${item.path}')">DB 삭제</button>
                        </td>
                    `;
                    tbody.appendChild(row);
                });
            }

            async function deleteSeries(path) {
                if(!confirm('이 항목을 DB에서 삭제하시겠습니까? (실제 파일은 건드리지 않습니다)')) return;
                const resp = await fetch('/api/admin/delete_series', {
                    method: 'POST',
                    headers: {'Content-Type': 'application/json'},
                    body: JSON.stringify({ path: path })
                });
                const res = await resp.json();
                if(res.status === 'success') searchGhost();
                else alert('에러: ' + res.message);
            }

            async function deleteGhostAll() {
                if(!confirm('실제 파일이 없는 모든 "유령 데이터"를 DB에서 일괄 삭제하시겠습니까?')) return;
                const resp = await fetch('/api/admin/delete_ghost_all', { method: 'POST' });
                const res = await resp.json();
                alert(res.message);
                searchGhost();
            }
        </script>
    </body>
    </html>
    """


# --- [통합 관리자용: 유령 데이터 처리 API] ---

@app.route('/api/admin/ghost_list')
def api_ghost_list():
    cat_filter = request.args.get('cat', 'all')
    query_text = request.args.get('q', '').strip()

    conn = get_db()
    sql = "SELECT path, category, name FROM series WHERE 1=1"
    params = []

    if cat_filter != 'all':
        sql += " AND category = ?"
        params.append(cat_filter)
    if query_text:
        sql += " AND (name LIKE ? OR cleanedName LIKE ?)"
        params.extend([f'%{query_text}%', f'%{query_text}%'])

    rows = conn.execute(sql, params).fetchall()
    conn.close()

    results = []
    # 카테고리별 실제 베이스 경로 매핑
    CAT_BASE_MAP = {
        "movies": PATH_MAP["영화"][0],
        "foreigntv": PATH_MAP["외국TV"][0],
        "koreantv": PATH_MAP["국내TV"][0],
        "animations_all": PATH_MAP["애니메이션"][0],
        "air": PATH_MAP["방송중"][0]
    }

    for row in rows:
        spath = row['path']
        cat = row['category']
        base_path = CAT_BASE_MAP.get(cat)

        exists = False
        if base_path:
            # DB의 path는 'category/relative/path' 형식이므로 실제 상대 경로만 추출
            rel_path = spath.replace(f"{cat}/", "", 1)
            full_path = get_real_path(os.path.join(base_path, rel_path))
            exists = os.path.exists(full_path)

        results.append({
            "path": spath,
            "category": cat,
            "name": row['name'],
            "exists": exists
        })
    return jsonify(results)


@app.route('/api/admin/delete_series', methods=['POST'])
def api_delete_series():
    data = request.json
    path = data.get('path')
    if not path: return jsonify({"status": "error", "message": "Path is required"})
    try:
        conn = get_db()
        conn.execute("DELETE FROM series WHERE path = ?", (path,))
        conn.commit()
        conn.close()
        build_all_caches()
        return jsonify({"status": "success"})
    except Exception as e:
        return jsonify({"status": "error", "message": str(e)})


@app.route('/api/admin/delete_ghost_all', methods=['POST'])
def api_delete_ghost_all():
    conn = get_db()
    rows = conn.execute("SELECT path, category FROM series").fetchall()
    CAT_BASE_MAP = {
        "movies": PATH_MAP["영화"][0],
        "foreigntv": PATH_MAP["외국TV"][0],
        "koreantv": PATH_MAP["국내TV"][0],
        "animations_all": PATH_MAP["애니메이션"][0],
        "air": PATH_MAP["방송중"][0]
    }
    ghost_paths = []
    for row in rows:
        spath, cat = row['path'], row['category']
        base_path = CAT_BASE_MAP.get(cat)
        if base_path:
            rel_path = spath.replace(f"{cat}/", "", 1)
            full_path = get_real_path(os.path.join(base_path, rel_path))
            if not os.path.exists(full_path):
                ghost_paths.append(spath)
    if ghost_paths:
        cursor = conn.cursor()
        cursor.executemany("DELETE FROM series WHERE path = ?", [(p,) for p in ghost_paths])
        conn.commit()
    conn.close()
    build_all_caches()
    retur


@app.route('/api/admin/force_clear_one_piece_stills')
def force_clear_one_piece_stills():
    def run_task():
        # 상태 표시 업데이트
        set_update_state(is_running=True, task_name="원피스 썸네일 강제 제거", current_item="DB 작업 중...")
        emit_ui_log("원피스 실사판 썸네일 강제 제거 작업을 시작합니다...", "info")

        try:
            conn = get_db()
            # 1. 애니메이션 카테고리 내 원피스 관련 에피소드 중 외부 링크(http)만 초기화
            cursor = conn.execute("""
                UPDATE episodes
                SET thumbnailUrl = NULL
                WHERE series_path LIKE 'animations_all/%'
                  AND (series_path LIKE '%원피스%' OR title LIKE '%원피스%')
                  AND (thumbnailUrl LIKE 'http%' OR thumbnailUrl IS NULL)
            """)
            cleared_count = cursor.rowcount
            conn.commit()
            conn.close()

            emit_ui_log(f"DB 수정 완료 ({cleared_count}건). 캐시를 갱신합니다. 잠시만 기다려주세요...", "info")

            # 2. 캐시 갱신 (여기서 시간이 좀 걸립니다)
            build_all_caches()

            emit_ui_log(f"✅ 성공! 총 {cleared_count}개의 잘못된 썸네일을 삭제하고 캐시 갱신을 완료했습니다.", "success")
        except Exception as e:
            emit_ui_log(f"❌ 실패: {str(e)}", "error")
        finally:
            set_update_state(is_running=False, current_item="작업 완료")

    # 별도 스레드에서 실행하여 Flask 타임아웃 방지
    threading.Thread(target=run_task, daemon=True).start()
    return jsonify({"status": "success", "message": "원피스 썸네일 제거 작업이 백그라운드에서 시작되었습니다."})


@app.route('/api/repair/naruto_fix')
def repair_naruto_fix():
    # 나루토 질풍전의 실제 절대 경로 (쿼리 결과의 경로를 기반으로 설정)
    base_video_path = "/volume2/video/GDS3/GDRIVE/VIDEO/일본 애니메이션"
    target_dir = os.path.join(base_video_path, "시리즈/나/나루토 질풍전 (2007)")

    if not os.path.exists(nfc(target_dir)):
        return f"경로를 찾을 수 없습니다: {target_dir}"

    conn = get_db()
    cursor = conn.cursor()
    added_count = 0

    # os.walk로 나루토 폴더만 정밀 탐색
    for root, dirs, files in os.walk(nfc(target_dir)):
        for file in files:
            if file.lower().endswith(VIDEO_EXTS):
                full_path = nfc(os.path.join(root, file))
                mid = hashlib.md5(full_path.encode()).hexdigest()

                # DB에 있는지 확인
                exists = conn.execute("SELECT 1 FROM episodes WHERE id = ?", (mid,)).fetchone()
                if not exists:
                    rel = nfc(os.path.relpath(full_path, base_video_path))
                    spath = f"animations_all/{rel}"
                    name = os.path.splitext(file)[0]

                    # 제목 정제 및 시리즈/에피소드 추가
                    ct, yr = clean_title_complex(name, full_path=full_path, base_path=base_video_path)
                    cursor.execute(
                        'INSERT OR IGNORE INTO series (path, category, name, cleanedName, yearVal) VALUES (?, ?, ?, ?, ?)',
                        (spath, "animations_all", name, ct, yr))

                    sn, en = extract_episode_numbers(full_path)
                    cursor.execute(
                        'INSERT OR REPLACE INTO episodes (id, series_path, title, videoUrl, thumbnailUrl, season_number, episode_number) VALUES (?, ?, ?, ?, ?, ?, ?)',
                        (mid, spath, file, f"/video_serve?type=anim_all&path={urllib.parse.quote(rel)}",
                         f"/thumb_serve?type=anim_all&id={mid}&path={urllib.parse.quote(rel)}", sn, en))
                    added_count += 1

    conn.commit()
    conn.close()
    build_all_caches()  # 메모리 캐시 갱신
    return f"나루토 복구 완료: {added_count}개의 에피소드가 새로 추가되었습니다."


@app.route('/api/debug/naruto_check')
def debug_naruto_check():
    conn = get_db()
    # 1. '나루토' 키워드가 들어간 모든 시리즈와 해당 에피소드 개수 파악
    rows = conn.execute("""
        SELECT s.path, s.name, s.cleanedName, s.category,
               (SELECT COUNT(*) FROM episodes WHERE series_path = s.path) as ep_count
        FROM series s
        WHERE s.name LIKE '%나루토%' OR s.cleanedName LIKE '%나루토%'
    """).fetchall()

    # 2. 에피소드 테이블에서 '나루토' 파일들이 어떤 경로로 잡혀있는지 샘플링
    eps = conn.execute("""
        SELECT series_path, title, season_number, episode_number
        FROM episodes
        WHERE title LIKE '%나루토%' OR series_path LIKE '%나루토%'
        LIMIT 100
    """).fetchall()

    conn.close()
    return jsonify({
        "series_list": [dict(r) for r in rows],
        "episode_samples": [dict(e) for e in eps]
    })

@app.route('/api/debug/naruto_full_scan')
def debug_naruto_full_scan():
    try:
        conn = get_db()
        # 1. episodes 테이블에서 '나루토' 관련 모든 파일 찾기
        query = """
            SELECT
                e.title as file_name,
                e.series_path as db_series_path,
                e.season_number,
                e.episode_number,
                s.cleanedName,
                s.tmdbId,
                s.category
            FROM episodes e
            LEFT JOIN series s ON e.series_path = s.path
            WHERE e.title LIKE '%나루토%'
               OR e.series_path LIKE '%나루토%'
            ORDER BY e.series_path ASC
        """
        rows = conn.execute(query).fetchall()
        conn.close()

        result = [dict(row) for row in rows]

        # 통계 요약
        summary = {}
        for r in result:
            path = r['db_series_path']
            summary[path] = summary.get(path, 0) + 1

        return jsonify({
            "total_count": len(result),
            "paths_summary": summary,  # 어떤 경로에 몇 개씩 묶여 있는지 요약
            "data": result
        })
    except Exception as e:
        return jsonify({"error": str(e)})


@app.route('/api/debug/trace_naruto_path')
def debug_trace_naruto_path():
    try:
        conn = get_db()
        # 1, 2, 4 시즌 경로 패턴이 있는 에피소드를 찾고, 현재 어떤 그룹(series)에 속해있는지 확인
        query = """
            SELECT e.series_path, s.cleanedName, s.category, COUNT(*) as file_count,
                   MIN(e.title) as sample_file, MIN(e.videoUrl) as sample_url
            FROM episodes e
            LEFT JOIN series s ON e.series_path = s.path
            WHERE e.videoUrl LIKE '%Season%01%'
               OR e.videoUrl LIKE '%Season%02%'
               OR e.videoUrl LIKE '%Season%04%'
               OR e.title LIKE '%Naruto%'
            GROUP BY e.series_path, s.cleanedName, s.category
        """
        rows = conn.execute(query).fetchall()
        conn.close()

        analysis = []
        for r in rows:
            # 나루토와 관련된 파일인 경우만 필터링 (결과가 너무 많을까봐)
            if 'Naruto' in (r['sample_url'] or '') or '나루토' in (r['sample_url'] or ''):
                analysis.append({
                    "현재_DB_그룹경로": r['series_path'],
                    "매칭된_그룹명(cleanedName)": r['cleanedName'] or "[미지정]",
                    "카테고리": r['category'] or "[미지정]",
                    "파일_개수": r['file_count'],
                    "샘플_파일명": r['sample_file']
                })

        return jsonify({
            "description": "DB에 이미 존재하는 나루토 파일들의 그룹화 상태입니다.",
            "summary": analysis
        })
    except Exception as e:
        return jsonify({"error": str(e)})


@app.route('/api/repair/naruto_root_merge')
def repair_naruto_root_merge():
    try:
        conn = get_db()
        cursor = conn.cursor()

        # 1. '나루토 질풍전'의 기준이 될 깨끗한 대표 경로를 직접 생성 (파일 경로가 아닌 폴더 형식으로)
        # 쿼리에서 확인하신 'Season 03'의 부모 경로를 기준으로 잡습니다.
        target_path = "animations_all/시리즈/나/나루토 질풍전 (2007)"

        # 만약 series 테이블에 이 경로가 없다면 하나 만들어줍니다.
        cursor.execute("INSERT OR IGNORE INTO series (path, category, name, cleanedName) VALUES (?, ?, ?, ?)",
                       (target_path, "animations_all", "나루토 질풍전", "나루토 질풍전"))

        # 2. [핵심] episodes 테이블에서 '나루토'와 '질풍전'이 경로에 포함된 모든 데이터를 수거합니다.
        # 기존에 엉뚱한 series_path를 가지고 있던 1, 2, 4시즌 데이터들이 여기서 다 잡힙니다.
        cursor.execute("""
            UPDATE episodes
            SET series_path = ?
            WHERE videoUrl LIKE '%나루토%질풍전%'
               OR series_path LIKE '%나루토%질풍전%'
        """, (target_path,))

        merged_count = cursor.rowcount

        # 3. 통합된 에피소드들의 시즌/회차 번호 정밀 재설정 (S01E01 등 분석)
        cursor.execute("SELECT id, title FROM episodes WHERE series_path = ?", (target_path,))
        eps = cursor.fetchall()

        update_batch = []
        for ep in eps:
            # 파일명(NARUTO...S01E01...)에서 번호를 파싱합니다.
            sn, en = extract_episode_numbers(ep['title'])
            if sn is not None:
                update_batch.append((sn, en, ep['id']))

        if update_batch:
            cursor.executemany("UPDATE episodes SET season_number = ?, episode_number = ? WHERE id = ?", update_batch)

        conn.commit()
        conn.close()
        build_all_caches()

        return f"성공: {merged_count}개의 파일을 '{target_path}' 그룹으로 통합하고 시즌 정보를 교정했습니다."
    except Exception as e:
        return f"에러 발생: {str(e)}"


@app.route('/api/repair/naruto_super_injection')
def naruto_super_injection():
    import os, hashlib, urllib.parse, unicodedata

    def nfc(text):
        return unicodedata.normalize('NFC', text) if text else ""

    # 1. 스샷을 바탕으로 한 실제 절대 경로 (가장 중요한 부분입니다)
    # NAS 환경이므로 NFC 정규화를 강제로 적용하여 경로 인식을 보장합니다.
    base_dir = nfc("/volume2/video/GDS3/GDRIVE/VIDEO/일본 애니메이션/시리즈/나/나루토 질풍전 (2007)")

    # 2. 기준이 될 애니메이션 카테고리 루트 경로 (상대 경로 계산용)
    anim_root = nfc("/volume2/video/GDS3/GDRIVE/VIDEO/일본 애니메이션")

    target_seasons = ["Season 01", "Season 02", "Season 04"]

    conn = get_db()
    cursor = conn.cursor()
    added_count = 0
    skipped_count = 0
    logs = []

    if not os.path.exists(base_dir):
        return jsonify({"error": f"나루토 메인 폴더를 찾을 수 없습니다: {base_dir}"})

    for sn_folder in target_seasons:
        full_path = os.path.join(base_dir, sn_folder)

        if not os.path.exists(full_path):
            logs.append(f"폴더 없음: {sn_folder}")
            continue

        for file in os.listdir(full_path):
            if file.lower().endswith(VIDEO_EXTS):
                file_full_path = nfc(os.path.join(full_path, file))
                mid = hashlib.md5(file_full_path.encode()).hexdigest()

                # DB 등록을 위한 경로 계산
                rel_path = nfc(os.path.relpath(file_full_path, anim_root))
                # 앱에서 사용할 series_path 형식: animations_all/상대경로
                spath = f"animations_all/{rel_path}"

                # 3. 시리즈 테이블 등록 (무조건 '나루토 질풍전'으로 그룹화)
                cursor.execute("""
                    INSERT OR IGNORE INTO series (path, category, name, cleanedName)
                    VALUES (?, 'animations_all', '나루토 질풍전', '나루토 질풍전')
                """, (spath,))

                # 4. 에피소드 테이블 강제 주입
                # 파일명에서 시즌/회차 번호 추출 (NARUTO...S01E05...)
                sn, en = extract_episode_numbers(file)

                cursor.execute("""
                    INSERT OR REPLACE INTO episodes (id, series_path, title, videoUrl, thumbnailUrl, season_number, episode_number)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                """, (mid, spath, file,
                      f"/video_serve?type=anim_all&path={urllib.parse.quote(rel_path)}",
                      f"/thumb_serve?type=anim_all&id={mid}&path={urllib.parse.quote(rel_path)}",
                      sn, en))
                added_count += 1
            else:
                skipped_count += 1

    conn.commit()
    conn.close()
    build_all_caches()  # 캐시 즉시 갱신

    return jsonify({
        "status": "success",
        "added": added_count,
        "skipped": skipped_count,
        "not_found_folders": logs,
        "message": "나루토 1, 2, 4시즌 데이터가 DB에 강제 주입되었습니다. 앱에서 확인하세요."
    })

@app.route('/api/debug/bleach_files_raw')
def debug_bleach_files_raw():
    import os, unicodedata
    def nfc(text):
        return unicodedata.normalize('NFC', text) if text else ""

    # 스샷에서 확인된 경로를 기준으로 설정 (경로를 정확히 맞추는 것이 중요합니다)
    # NAS 환경이므로 실제 경로를 확인하여 수정해 주세요.
    base_dir = nfc("/volume2/video/GDS3/GDRIVE/VIDEO/일본 애니메이션/라프텔/가/극장판 블리치 1~4기 (2006)")
    # 혹시 다른 경로라면 아래처럼 스샷의 경로에 맞춰 수정하세요.
    # base_dir = nfc("/volume2/video/GDS3/GDRIVE/VIDEO/일본 애니메이션/라프텔/극장판 블리치 1~4기")

    report = {}

    # 해당 디렉토리가 존재하는지 확인 후 리스트업
    if os.path.exists(base_dir):
        report["target_dir"] = base_dir
        report["files_found"] = os.listdir(base_dir)
    else:
        report["error"] = f"폴더를 찾을 수 없습니다: {base_dir}"

    return jsonify({
        "video_exts_configured": VIDEO_EXTS,
        "debug_report": report
    })

@app.route('/api/admin/scan_targeted')
def scan_targeted():
    cat = request.args.get('category')
    folder = request.args.get('folder', '').strip()

    path, prefix = PATH_MAP.get(next((k for k, v in PATH_MAP.items() if v[1] == cat), ""), (None, None))
    if not path:
        return jsonify({"status": "error", "message": "카테고리 경로 없음"})

    def run_scan_and_match():
        # 1. 스캔 실행
        scan_recursive_to_db(path, prefix, cat, include_only=[folder] if folder else None)

        # 2. 매칭 실행 (핵심: category를 명시적으로 전달)
        # 기존 fetch_metadata_async 함수에 category 필터링 로직이 없다면
        # 아래처럼 직접 쿼리를 호출하는 게 훨씬 확실합니다.
        log("METADATA", f"🎬 [{cat}/{folder}] 폴더 타겟 매칭 시작")

        # 카테고리 필터링이 적용된 매칭 로직을 바로 실행
        # 기존 fetch_metadata_async를 수정하기 어렵다면 아래 로직을 바로 수행합니다.
        conn = get_db()
        # 해당 카테고리 & 폴더 경로에 포함된 놈들만 콕 집어서 매칭
        targets = conn.execute("""
            SELECT cleanedName, yearVal FROM series
            WHERE category = ? AND path LIKE ?
            AND (tmdbId IS NULL OR tmdbTitle IS NULL)
            GROUP BY cleanedName, yearVal
        """, (cat, f'%{folder}%')).fetchall()
        conn.close()

        for row in targets:
            info = get_tmdb_info_server(row['cleanedName'], category=cat, ignore_cache=True)
            if not info.get('failed'):
                # (이후 DB 업데이트 로직은 기존 fetch_metadata_async와 동일하게 작성)
                pass

        build_all_caches()
        emit_ui_log(f"🏁 [{cat}/{folder}] 전체 작업 완료.", "success")

    threading.Thread(target=run_scan_and_match, daemon=True).start()
    return jsonify({"status": "success", "message": f"[{cat}/{folder}] 스캔 및 전용 매칭 시작"})

@app.route('/api/admin/fix_bleach_episodes')
def fix_bleach_episodes():
    """파일명의 E01~E04를 읽어 에피소드 번호를 강제 고정합니다."""
    conn = get_db()
    cursor = conn.cursor()

    # 1. '극장판 블리치 1~4기' 폴더의 모든 에피소드 조회
    eps = conn.execute("SELECT id, title FROM episodes WHERE series_path LIKE '%극장판 블리치 1~4기%'").fetchall()

    updates = []
    for ep in eps:
        # 파일명에서 E0x 숫자를 추출 (E01 -> 1, E02 -> 2...)
        match = re.search(r'\.E(\d+)\.', ep['title'], re.I)
        if match:
            ep_num = int(match.group(1))
            updates.append((1, ep_num, ep['id']))  # 시즌1, 에피소드 번호, ID

    if updates:
        cursor.executemany("UPDATE episodes SET season_number=?, episode_number=? WHERE id=?", updates)
        conn.commit()

    conn.close()
    build_all_caches()
    return f"성공! {len(updates)}개의 에피소드 번호를 파일명에서 추출하여 고정했습니다."

@app.route('/admin/db_pro')
def admin_db_pro():
    return """
    <!DOCTYPE html>
    <html lang="ko">
    <head>
        <meta charset="UTF-8">
        <title>NAS PRO ADMIN - DB Management</title>
        <link href="https://cdnjs.cloudflare.com/ajax/libs/font-awesome/6.0.0/css/all.min.css" rel="stylesheet">
        <style>
            :root { --bg-dark: #0f172a; --bg-card: #1e293b; --accent: #3b82f6; --accent-hover: #2563eb; --text-main: #f8fafc; --text-dim: #94a3b8; --border: #334155; }
            body { font-family: 'Inter', sans-serif; background: var(--bg-dark); color: var(--text-main); margin: 0; display: flex; height: 100vh; }
            .sidebar { width: 240px; background: var(--bg-card); border-right: 1px solid var(--border); display: flex; flex-direction: column; }
            .sidebar-header { padding: 25px 20px; font-size: 18px; font-weight: 800; color: var(--accent); border-bottom: 1px solid var(--border); }
            .nav-menu { padding: 15px; flex: 1; }
            .nav-item { padding: 12px 15px; border-radius: 8px; cursor: pointer; color: var(--text-dim); display: flex; align-items: center; gap: 12px; margin-bottom: 5px; transition: 0.2s; }
            .nav-item:hover { background: #334155; color: white; }
            .nav-item.active { background: var(--accent); color: white; }
            .main-content { flex: 1; display: flex; flex-direction: column; overflow: hidden; }
            .top-bar { padding: 15px 30px; background: var(--bg-card); border-bottom: 1px solid var(--border); display: flex; align-items: center; gap: 15px; }
            select, input { background: var(--bg-dark); color: white; border: 1px solid var(--border); padding: 10px; border-radius: 8px; outline: none; font-size: 14px; }
            .btn { padding: 10px 20px; border-radius: 8px; border: none; cursor: pointer; font-weight: 600; display: flex; align-items: center; gap: 8px; transition: 0.2s; }
            .btn-primary { background: var(--accent); color: white; }
            .btn-ghost { background: transparent; border: 1px solid var(--border); color: var(--text-dim); }
            .grid-container { flex: 1; overflow: auto; padding: 20px 30px; }
            table { width: 100%; border-collapse: separate; border-spacing: 0; background: var(--bg-card); border-radius: 12px; border: 1px solid var(--border); }
            th { position: sticky; top: 0; z-index: 10; background: #334155; color: var(--text-dim); padding: 12px 15px; text-align: left; font-size: 12px; border-bottom: 1px solid var(--border); cursor: pointer; white-space: nowrap; }
            td { padding: 12px 15px; border-bottom: 1px solid var(--border); font-size: 13px; max-width: 300px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
            tr:hover { background: #2d3748; }
            .pagination { padding: 15px 30px; background: var(--bg-card); border-top: 1px solid var(--border); display: flex; justify-content: center; align-items: center; gap: 15px; }
            .poster-thumb { width: 35px; height: 50px; border-radius: 4px; object-fit: cover; }
        </style>
    </head>
    <body>
        <div class="sidebar">
            <div class="sidebar-header"><i class="fas fa-terminal"></i> DB PRO ADMIN</div>
            <div class="nav-menu">
                <div class="nav-item active" onclick="switchTable('series', this)"><i class="fas fa-layer-group"></i> 작품 (Series)</div>
                <div class="nav-item" onclick="switchTable('episodes', this)"><i class="fas fa-film"></i> 에피소드 (Episodes)</div>
                <div class="nav-item" onclick="switchTable('playback_progress', this)"><i class="fas fa-history"></i> 시청 기록</div>
                <div style="margin-top: auto; padding-top: 20px; border-top: 1px solid var(--border);">
                    <a href="/updater" style="text-decoration:none;"><div class="nav-item"><i class="fas fa-arrow-left"></i> 대시보드 복귀</div></a>
                </div>
            </div>
        </div>
        <div class="main-content">
            <div class="top-bar">
                <select id="categorySelect" onchange="triggerSearch()">
                    <option value="전체">전체 카테고리</option>
                    <option value="movies">영화</option>
                    <option value="koreantv">국내TV</option>
                    <option value="foreigntv">외국TV</option>
                    <option value="animations_all">애니메이션</option>
                    <option value="air">방송중</option>
                </select>
                <input type="text" id="searchInput" style="flex:1;" placeholder="키워드 검색..." onkeyup="if(event.key==='Enter') triggerSearch()">
                <button class="btn btn-primary" onclick="triggerSearch()"><i class="fas fa-search"></i> 검색</button>
                <span id="rowCount" style="color: var(--text-dim); font-size: 13px; white-space:nowrap;">0 items</span>
            </div>
            <div class="grid-container" id="gridArea"></div>
            <div class="pagination">
                <button class="btn btn-ghost" id="prevBtn" onclick="changePage(-1)"><i class="fas fa-chevron-left"></i></button>
                <span id="pageDisplay" style="font-weight:bold; color:var(--accent);">Page 1 / 1</span>
                <button class="btn btn-ghost" id="nextBtn" onclick="changePage(1)"><i class="fas fa-chevron-right"></i></button>
                <select id="pageSize" onchange="changeSize(this.value)">
                    <option value="50">50개씩</option><option value="100">100개씩</option><option value="200">200개씩</option>
                </select>
            </div>
        </div>
        <script>
            let currentTable = 'series', currentPage = 0, pageSize = 50, sortCol = '', sortDir = 'ASC';
            function switchTable(table, el) {
                document.querySelectorAll('.nav-item').forEach(i => i.classList.remove('active'));
                el.classList.add('active');
                currentTable = table; triggerSearch();
            }
            function triggerSearch() { currentPage = 0; loadData(); }
            async function loadData() {
                const q = document.getElementById('searchInput').value;
                const cat = document.getElementById('categorySelect').value;
                const grid = document.getElementById('gridArea');
                grid.innerHTML = '<div style="text-align:center; padding:100px;"><i class="fas fa-spinner fa-spin fa-3x"></i></div>';
                try {
                    const res = await (await fetch(`/api/admin/db_pro_data?table=${currentTable}&q=${encodeURIComponent(q)}&cat=${cat}&limit=${pageSize}&offset=${currentPage*pageSize}&sort=${sortCol}&dir=${sortDir}`)).json();
                    document.getElementById('rowCount').innerText = `${res.total.toLocaleString()} items found`;
                    document.getElementById('pageDisplay').innerText = `Page ${currentPage + 1} / ${Math.ceil(res.total / pageSize) || 1}`;
                    document.getElementById('prevBtn').disabled = currentPage === 0;
                    document.getElementById('nextBtn').disabled = (currentPage + 1) * pageSize >= res.total;
                    let html = '<table><thead><tr>' + res.columns.map(c => `<th onclick="handleSort('${c}')">${c} ${sortCol===c?(sortDir==='ASC'?'▲':'▼'):''}</th>`).join('') + '</tr></thead><tbody>';
                    res.data.forEach(row => {
                        html += '<tr>' + res.columns.map(c => {
                            let val = row[c], display = val === null ? '<span style="color:#475569">null</span>' : val;
                            if (c === 'posterPath' && val) display = `<img src="${val.startsWith('http') ? val : 'https://image.tmdb.org/t/p/w200' + val}" class="poster-thumb">`;
                            return `<td title="${String(val).replace(/"/g, '&quot;')}">${display}</td>`;
                        }).join('') + '</tr>';
                    });
                    grid.innerHTML = html + '</tbody></table>';
                } catch (e) { grid.innerHTML = `<div style="color:#ef4444; padding:50px;">Error: ${e}</div>`; }
            }
            function changePage(delta) { currentPage += delta; loadData(); document.getElementById('gridArea').scrollTop = 0; }
            function changeSize(size) { pageSize = parseInt(size); triggerSearch(); }
            function handleSort(col) { if (sortCol === col) sortDir = sortDir === 'ASC' ? 'DESC' : 'ASC'; else { sortCol = col; sortDir = 'ASC'; } loadData(); }
            window.onload = loadData;
        </script>
    </body>
    </html>
    """
@app.route('/api/admin/db_pro_data')
def api_db_pro_data():
    table = request.args.get('table', 'series')
    kw = request.args.get('q', '').strip()
    cat_filter = request.args.get('cat', '전체')
    limit = int(request.args.get('limit', 50))
    offset = int(request.args.get('offset', 0))
    sort = request.args.get('sort', '')
    direction = request.args.get('dir', 'ASC')

    if table not in ['series', 'episodes', 'playback_progress', 'tmdb_cache']:
        return jsonify({"error": "Invalid table"}), 400

    conn = get_db()
    where_clauses = []
    params = []

    # 1. 키워드 검색 (테이블별 컬럼 대응)
    if kw:
        if table == 'series':
            where_clauses.append("(name LIKE ? OR cleanedName LIKE ? OR tmdbTitle LIKE ? OR path LIKE ?)")
            params.extend([f'%{kw}%', f'%{kw}%', f'%{kw}%', f'%{kw}%'])
        elif table == 'episodes':
            where_clauses.append("(title LIKE ? OR series_path LIKE ?)")
            params.extend([f'%{kw}%', f'%{kw}%'])
        elif table == 'playback_progress':
            where_clauses.append("(episode_id LIKE ?)")
            params.append(f'%{kw}%')

    # 2. 카테고리 필터
    if cat_filter and cat_filter != '전체':
        if table == 'series':
            where_clauses.append("category = ?")
            params.append(cat_filter)
        elif table == 'episodes':
            where_clauses.append("series_path LIKE ?")
            params.append(f"{cat_filter}/%")

    where_stmt = " WHERE " + " AND ".join(where_clauses) if where_clauses else ""

    try:
        # 전체 개수 확인
        total_count = conn.execute(f"SELECT COUNT(*) FROM {table} {where_stmt}", params).fetchone()[0]

        # 정렬 및 쿼리 실행
        order_by = ""
        if sort:
            order_by = f" ORDER BY {sort} {direction}"
        elif table == 'series':
            order_by = " ORDER BY path ASC"
        elif table == 'episodes':
            order_by = " ORDER BY series_path ASC, episode_number ASC"

        query = f"SELECT * FROM {table} {where_stmt} {order_by} LIMIT ? OFFSET ?"
        cursor = conn.execute(query, params + [limit, offset])

        # 컬럼 추출 및 정렬 (중복 방지 및 우선순위 정렬)
        columns = [d[0] for d in cursor.description]
        prio = ['posterPath', 'path', 'series_path', 'name', 'title', 'cleanedName', 'season_number', 'episode_number']
        columns = sorted(list(dict.fromkeys(columns)), key=lambda x: prio.index(x) if x in prio else 999)

        data = [dict(zip(columns, row)) for row in cursor.fetchall()]
        conn.close()

        return jsonify({"columns": columns, "data": data, "total": total_count, "offset": offset, "limit": limit})
    except Exception as e:
        if conn: conn.close()
        return jsonify({"error": str(e)}), 500

@app.route('/api/debug/test_movies')
def test_movies():
    """DB에 저장된 영화 데이터의 원본 상태를 조회합니다."""
    conn = get_db()
    rows = conn.execute("SELECT path, name, cleanedName, category, posterPath FROM series WHERE category = 'movies' LIMIT 50").fetchall()
    total = conn.execute("SELECT COUNT(*) FROM series WHERE category = 'movies'").fetchone()[0]
    conn.close()
    return jsonify({
        "total_in_db": total,
        "sample_rows": [dict(r) for r in rows]
    })


@app.route('/api/debug/movie_status')
def debug_movie_status():
    """영화 데이터의 카테고리별 분류 및 포스터 누락 상태를 정밀 진단합니다."""
    conn = get_db()
    try:
        # 1. 전체 카테고리 분포 확인
        cat_dist = conn.execute("SELECT category, COUNT(*) as cnt FROM series GROUP BY category").fetchall()

        # 2. 'movies' 카테고리로 분류된 것 중 실제 데이터 상황
        movie_data = conn.execute("""
            SELECT
                COUNT(*) as total,
                SUM(CASE WHEN posterPath IS NULL THEN 1 ELSE 0 END) as no_poster,
                SUM(CASE WHEN tmdbId IS NULL THEN 1 ELSE 0 END) as no_tmdbid
            FROM series
            WHERE category IN ('movies', 'movie')
        """).fetchone()

        # 3. 샘플 데이터 (movies 카테고리 중 5개)
        samples = conn.execute(
            "SELECT name, path, category, posterPath FROM series WHERE category IN ('movies', 'movie') LIMIT 5").fetchall()

        return jsonify({
            "category_distribution": [dict(r) for r in cat_dist],
            "movie_stats": dict(movie_data),
            "samples": [dict(r) for r in samples]
        })
    except Exception as e:
        return jsonify({"error": str(e)})
    finally:
        conn.close()

# 영화 파일명이나 경로를 키워드로 검색해 보세요
@app.route('/api/debug/search_movies_anywhere')
def search_movies_anywhere():
    conn = get_db()
    # 경로에 'movies' 혹은 '영화'가 들어간 파일들을 찾아 어떤 category로 등록되어 있는지 확인
    rows = conn.execute("""
        SELECT category, path, name
        FROM series
        WHERE path LIKE '%영화%' OR path LIKE '%movies%'
        LIMIT 50
    """).fetchall()
    conn.close()
    return jsonify([dict(r) for r in rows])

@app.route('/api/debug/find_movies_wrong_cat')
def find_movies_wrong_cat():
    # 경로에 '영화' 혹은 'movies'가 포함되어 있는데 category가 'movies'가 아닌 것들 조회
    conn = get_db()
    rows = conn.execute("""
        SELECT path, category, name
        FROM series
        WHERE (path LIKE '%영화%' OR path LIKE '%movies%')
        AND category != 'movies'
        LIMIT 50
    """).fetchall()
    conn.close()
    return jsonify([dict(r) for r in rows])


@app.route('/api/debug/check_exclusion_rules')
def check_exclusion_rules():
    # 영화 폴더 경로 정의 (PATH_MAP 확인)
    base_path, _ = PATH_MAP["영화"]

    # 1. 영화 폴더 내부에 무엇이 있는지 확인
    try:
        items = os.listdir(base_path)
    except Exception as e:
        return jsonify({"error": f"폴더 접근 불가: {str(e)}"})

    # 2. 제외 규칙(EXCLUDE_FOLDERS)에 걸리는 항목이 있는지 확인
    excluded = [item for item in items if any(ex in item for ex in EXCLUDE_FOLDERS)]

    # 3. 화이트리스트 확인
    whitelist = WHITELISTS.get("movies", [])

    return jsonify({
        "base_path": base_path,
        "total_items_in_folder": len(items),
        "items_matching_exclude_list": excluded,
        "current_whitelist": whitelist
    })

@app.route('/api/admin/match_movies_all')
def match_movies_all():
    if IS_METADATA_RUNNING: return jsonify({"status": "error", "message": "이미 작업 중입니다."})
    # 영화 카테고리 실패 기록 초기화
    conn = get_db(); conn.execute("UPDATE series SET failed = 0 WHERE category = 'movies'"); conn.commit(); conn.close()
    threading.Thread(target=fetch_metadata_async, kwargs={'target_category': 'movies', 'force_all': True}, daemon=True).start()
    return jsonify({"status": "success", "message": "영화 전용 포스터 매칭을 시작합니다."})

# --- [추가] 수동 포스터 변경 라우트 ---
@app.route('/custom_poster/<filename>')
def serve_custom_poster(filename):
    """업로드된 수동 포스터 이미지를 서빙합니다."""
    return send_from_directory(CUSTOM_POSTER_DIR, filename)


def background_init_tasks():
    build_all_caches()


# 수정 후 (port를 9821로 변경)
if __name__ == '__main__':
    init_db()
    threading.Thread(target=background_init_tasks, daemon=True).start()
    app.run(host='0.0.0.0', port=9821, threaded=True)
