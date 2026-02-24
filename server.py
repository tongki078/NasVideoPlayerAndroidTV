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
MY_IP = "192.168.0.2"
DATA_DIR = "/volume2/video/thumbnails"
DB_FILE = "/volume2/video/video_metadata.db"
TMDB_CACHE_DIR = "/volume2/video/tmdb_cache"
HLS_ROOT = "/dev/shm/videoplayer_hls"
SUBTITLE_DIR = "/volume2/video/subtitles"  # 자막 저장 경로
CACHE_VERSION = "137.33"  # 제목 정제 강화 버전

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

        cursor.execute('CREATE INDEX IF NOT EXISTS idx_series_category ON series(category)')
        cursor.execute('CREATE INDEX IF NOT EXISTS idx_series_name ON series(name)')
        cursor.execute('CREATE INDEX IF NOT EXISTS idx_series_tmdbId ON series(tmdbId)')
        cursor.execute('CREATE INDEX IF NOT EXISTS idx_episodes_series ON episodes(series_path)')

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
# [개선] 기술적 태그: 한글 단어 일부를 태그로 오해하지 않도록 경계 조건 강화
REGEX_TECHNICAL_TAGS = re.compile(
    r'(?i)[.\s_-](?!(?:\d+\b))(\d{3,4}p|2160p|FHD|QHD|UHD|4K|Bluray|Blu-ray|WEB-DL|WEBRip|HDRip|BDRip|DVDRip|H\.?26[45]|x26[45]|HEVC|AVC|AAC\d?|DTS-?H?D?|AC3|DDP\d?|DD\+\d?|Dual|Atmos|REPACK|10bit|REMUX|FLAC|xvid|DivX|MKV|MP4|AVI|HDR(?:10)?(?:\+)?|Vision|Dolby|NF|AMZN|HMAX|DSNP|AppleTV?|Disney|PCOK|playWEB|ATVP|HULU|HDTV|HD|KBS|SBS|MBC|TVN|JTBC|NEXT|ST|SW|KL|YT|MVC|KN|FLUX|hallowed|PiRaTeS|Jadewind|Movie|pt\s*\d+|KOREAN|KOR|ITALIAN|JAPANESE|JPN|CHINESE|CHN|ENGLISH|ENG|USA|HK|TW|FRENCH|GERMAN|SPANISH|THAI|VIETNAMESE|WEB|DL|TVRip|HDR10Plus|IMAX|Unrated|REMASTERED|Criterion|NonDRM|BRRip|1080i|720i|국어|Mandarin|Cantonese|FanSub|VFQ|VF|2CH|5\.1CH|8m|2398|PROPER|PROMO|LIMITED|RM4K|DC|THEATRICAL|EXTENDED|FINAL|DUB|KORDUB|JAPDUB|ENGDUB|ARROW|EDITION|SPECIAL|COLLECTION|RETAIL|TVING|WAVVE|Coupang|CP|B-Global|TrueHD|E-AC3|EAC3|DV|Dual-Audio|Multi-Audio|Multi-Sub)(?:\b|[.\s_-]|$)')

# [수정] 에피소드 마커 정규식 (마지막 괄호 확인 필수!)
REGEX_EP_MARKER_STRICT = re.compile(
    r'(?i)(?:(?<=[\uac00-\ud7af\u3040-\u30ff\u4e00-\u9fff])|[.\s_-]|^)(?:第?\s*S(\d+)E(\d+)(?:[-~]E?\d+)?(?:[화회기부話장쿨편])?|第?\s*S(\d+)|第?\s*E(\d+)(?:[-~]\d+)?(?:[화회기부話장쿨편])?|(\d+)\s*(?:화|회|기|부|話|장|쿨|편)|Season\s*(\d+)|Episode\s*(\d+)|시즌\s*(\d+)|Part\s*(\d+))(?:\b|[.\s_-]|$)')

REGEX_DATE_YYMMDD = re.compile(r'(?<!\d)\d{6}(?!\d)')
REGEX_FORBIDDEN_CONTENT = re.compile(
    r'(?i)(Storyboard|Behind the Scenes|Making of|Deleted Scenes|Alternate Scenes|Gag Reel|Gag Menu|Digital Hits|Trailer|Bonus|Extras|Gallery|Production|Visual Effects|VFX|등급고지|예고편|개봉버전|인터뷰|삭제장면|(?<!\S)[상하](?!\S))')
REGEX_FORBIDDEN_TITLE = re.compile(
    r'(?i)^\s*(Season\s*\d+|Part\s*\d+|EP\s*\d+|\d+화|\d+회|\d+기|시즌\s*\d+|S\d+|E\d+|Disk\s*\d+|Disc\s*\d+|CD\s*\d+|Specials?|Extras?|Bonus|미분류|기타|새\s*폴더|VIDEO|GDS3|GDRIVE|NAS|share|영화|외국TV|국내TV|애니메이션|방송중|제목|UHD|최신|최신작|최신영화|4K|1080P|720P)\s*$',
    re.I)

REGEX_BRACKETS = re.compile(
    r'\[.*?(?:\]|$)|\(.*?(?:\)|$)|\{.*?(?:\)|$)|\【.*?(?:\】|$)|\『.*?(?:\』|$)|\「.*?(?:\」|$)|\（.*?(?:\）|$)')
REGEX_TMDB_HINT = re.compile(r'\{tmdb[\s-]*(\d+)\}')
# [수정] 불필요한 키워드 제거 목록 보강
REGEX_JUNK_KEYWORDS = re.compile(
    r'(?i)\s*(?:더빙|자막|한국어|극장판|BD|TV|Web|OAD|OVA|ONA|Full|무삭제|감독판|확장판|익스텐디드|등급고지|예고편|(?<!\S)[상하](?!\S)|극장판\s*\d+[기장쿨]|특집\s*다큐|\d+부작|큐레이션|단편|드라마)\s*')
# [수정] 특수문자 제거 시 하이픈(-)과 콜론(:)은 제외하여 부제 분리에 사용 (별표 추가)
REGEX_SPECIAL_CHARS = re.compile(r'[\[\]()_\.!#@*※×,~;【】『』「」"\'（）☆★]')
REGEX_LEADING_INDEX = re.compile(r'^\s*(\d{1,5}(?:\s+|[.\s_-]+|(?=[가-힣a-zA-Z])))|^\s*(\d{1,5}\. )')
REGEX_SPACES = re.compile(r'\s+')


def clean_title_complex(title):
    if not title: return "", None
    orig_title = nfc(title)

    if REGEX_FORBIDDEN_CONTENT.search(orig_title):
        return "", None

    cleaned = REGEX_EXT.sub('', orig_title)
    cleaned = REGEX_CH_PREFIX.sub('', cleaned)
    cleaned = REGEX_TMDB_HINT.sub('', cleaned)

    # [수정] 연도 정보 미리 추출 (브래킷 제거 전)
    year_match = REGEX_YEAR.search(cleaned)
    year = year_match.group().replace('(', '').replace(')', '') if year_match else None

    # [수정] 마커 확인 시 제목이 너무 많이 잘려나가는 것을 방지
    ep_match = REGEX_EP_MARKER_STRICT.search(cleaned)
    if ep_match:
        # [개선] EP 마커 앞부분이 2자 미만이거나 한글이 포함되지 않은 경우만 뒷부분을 취함
        pre = cleaned[:ep_match.start()].strip()
        if len(pre) >= 2 and not REGEX_FORBIDDEN_TITLE.match(pre):
            cleaned = pre
        elif len(pre) >= 1 and any('\uac00' <= c <= '\ud7af' for c in pre):
            cleaned = pre
        else:
            # 앞부분이 의미 없으면 마커 이후를 보되, 이후도 너무 짧으면 원본 제목 활용 고려
            post = cleaned[ep_match.end():].strip()
            if len(post) >= 2:
                cleaned = post
            else:
                cleaned = pre if pre else post

    tech_match = REGEX_TECHNICAL_TAGS.search(cleaned)
    if tech_match:
        # [개선] 기술 태그에 의해 제목이 너무 짧아지면(1자 이하) 자르지 않음
        pre_tech = cleaned[:tech_match.start()].strip()
        if len(pre_tech) >= 2:
            cleaned = pre_tech

    # [개선] 숫자 분리 로직: 날짜나 연도가 깨지지 않도록 한글/영어 경계만 처리
    cleaned = re.sub(r'([가-힣\u3040-\u30ff\u4e00-\u9fff])([a-zA-Z])', r'\1 \2', cleaned)
    cleaned = re.sub(r'([a-zA-Z])([가-힣\u3040-\u30ff\u4e00-\u9fff])', r'\1 \2', cleaned)

    cleaned = REGEX_DATE_YYMMDD.sub(' ', cleaned)
    cleaned = REGEX_YEAR.sub(' ', cleaned)

    # 정제 후 너무 짧아진 경우 브래킷 내부에서 대체 제목 찾기
    if len(cleaned.strip()) < 2:
        brackets = re.findall(r'\[(.*?)\]|\((.*?)\)|（(.*?)）', orig_title)
        for b in brackets:
            inner = (b[0] or b[1] or b[2] or "").strip()
            if len(inner) >= 2 and not REGEX_TECHNICAL_TAGS.search(inner) and not REGEX_FORBIDDEN_TITLE.match(inner):
                cleaned = inner
                break

    cleaned = REGEX_BRACKETS.sub(' ', cleaned)
    cleaned = cleaned.replace("(자막)", "").replace("(더빙)", "").replace("[자막]", "").replace("[더빙]", "").replace("（자막）",
                                                                                                              "").replace(
        "（더빙）", "")
    cleaned = REGEX_JUNK_KEYWORDS.sub(' ', cleaned)

    # [수정] 점(.)을 무조건 제거하기 전에 공백으로 변환 (숫자 보호 위해 특수문자 처리에서 다룸)
    cleaned = REGEX_SPECIAL_CHARS.sub(' ', cleaned)
    cleaned = REGEX_LEADING_INDEX.sub('', cleaned)
    cleaned = REGEX_SPACES.sub(' ', cleaned).strip()

    if len(cleaned) < 1:
        # 최종 정제 실패 시 원본 제목에서 확장자만 떼고 반환 (최후의 수단)
        return nfc(os.path.splitext(orig_title)[0]), year
    return nfc(cleaned), year


def extract_episode_numbers(filename):
    match = REGEX_EP_MARKER_STRICT.search(filename)
    if match:
        if match.group(1) and match.group(2): return int(match.group(1)), int(match.group(2))
        if match.group(3): return int(match.group(3)), 1
        if match.group(4): return 1, int(match.group(4))
        if match.group(5): return 1, int(match.group(5))
        if match.group(6): return int(match.group(6)), 1
        if match.group(7): return 1, int(match.group(7))
        if match.group(8): return int(match.group(8)), 1
        if match.group(9): return int(match.group(9)), 1 # Part 번호를 시즌으로 간주
    return 1, None


def natural_sort_key(s):
    if s is None: return []
    return [int(text) if text.isdigit() else text.lower() for text in re.split(r'(\d+)', nfc(str(s)))]


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
    base_params = {"include_adult": "true", "region": "KR"}

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
                "genreIds": [g['id'] for g in d_resp.get('genres', [])] if d_resp.get('genres') else [],
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
                                "still_path": ep.get('still_path')
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


# [추가] 카테고리별로 스캔할 허용 폴더 목록 정의
WHITELISTS = {
    "movies": ["제목", "UHD", "최신"],
    "koreantv": ["드라마", "시트콤", "교양", "예능", "다큐멘터리"],
    "foreigntv": ["미국 드라마", "일본 드라마", "중국 드라마", "기타국가 드라마", "다큐"],
    "animations_all": ["라프텔", "시리즈"],
    "air": ["라프텔 애니메이션", "드라마"]
}


def scan_recursive_to_db(bp, prefix, category, include_only=None):
    emit_ui_log("파일 목록을 불러오는 중입니다...", "info")
    log("SCAN", f"📂 '{category}' 탐색 시작 (허용 폴더만: {include_only if include_only else '전체'})")
    base = nfc(get_real_path(bp))
    all_files = []

    # 허용 목록이 있으면 해당 폴더들만 시작 지점으로 설정
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
                            if not any(ex in entry.name for ex in EXCLUDE_FOLDERS) and not entry.name.startswith('.'):
                                stack.append(entry.path)
                        elif entry.is_file() and entry.name.lower().endswith(VIDEO_EXTS):
                            all_files.append(nfc(entry.path))
                        # --- [여기에 아래 코드 추가] ---
                        if len(all_files) % 1000 == 0:
                            log("SCAN", f"⏳ 파일 찾는 중... 현재 {len(all_files)}개 발견")
                        # ------------------------------
            except:
                pass

    conn = get_db()
    cursor = conn.cursor()
    # 해당 카테고리의 기존 데이터 가져오기
    cursor.execute('SELECT id, series_path FROM episodes WHERE series_path LIKE ?', (f"{category}/%",))
    db_data = {row['id']: row['series_path'] for row in cursor.fetchall()}
    current_ids = set()
    total = len(all_files)

    set_update_state(is_running=True, task_name=f"스캔 ({category})", total=total, current=0, success=0, fail=0,
                     clear_logs=True)

    for idx, fp in enumerate(all_files):
        mid = hashlib.md5(fp.encode()).hexdigest()
        current_ids.add(mid)
        rel = nfc(os.path.relpath(fp, base))
        name = os.path.splitext(os.path.basename(fp))[0]
        spath = f"{category}/{rel}"

        with UPDATE_LOCK:
            UPDATE_STATE["current"] += 1
            UPDATE_STATE["current_item"] = name

        ct, yr = clean_title_complex(name)
        cursor.execute(
            'INSERT OR IGNORE INTO series (path, category, name, cleanedName, yearVal) VALUES (?, ?, ?, ?, ?)',
            (spath, category, name, ct, yr))

        if mid not in db_data:
            cursor.execute(
                'INSERT OR REPLACE INTO episodes (id, series_path, title, videoUrl, thumbnailUrl) VALUES (?, ?, ?, ?, ?)',
                (mid, spath, os.path.basename(fp), f"/video_serve?type={prefix}&path={urllib.parse.quote(rel)}",
                 f"/thumb_serve?type={prefix}&id={mid}&path={urllib.parse.quote(rel)}"))
            emit_ui_log(f"신규 추가: '{name}'", 'success')
            with UPDATE_LOCK:
                UPDATE_STATE["success"] += 1
        elif db_data[mid] != spath:
            cursor.execute('UPDATE episodes SET series_path = ? WHERE id = ?', (spath, mid))
            emit_ui_log(f"경로 갱신: '{name}'", 'info')
            with UPDATE_LOCK:
                UPDATE_STATE["success"] += 1
        else:
            with UPDATE_LOCK:
                UPDATE_STATE["success"] += 1

        if (idx + 1) % 2000 == 0:
            conn.commit()

    # 허용되지 않은 폴더에서 스캔되지 않은 이전 데이터 삭제
    for rid in (set(db_data.keys()) - current_ids):
        cursor.execute('DELETE FROM episodes WHERE id = ?', (rid,))
    cursor.execute('DELETE FROM series WHERE path NOT IN (SELECT DISTINCT series_path FROM episodes) AND category = ?',
                   (category,))
    conn.commit()
    conn.close()

    set_update_state(is_running=False, current_item="작업 완료")
    log("SCAN", f"✅ '{category}' 스캔 완료 ({total}개)")


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


def fetch_metadata_async(force_all=False):
    global IS_METADATA_RUNNING
    if IS_METADATA_RUNNING:
        log("METADATA", "이미 프로세스가 실행 중입니다. 중단합니다.")
        return
    IS_METADATA_RUNNING = True
    log("METADATA", f"⚙️ 병렬 매칭 프로세스 시작 (force_all={force_all})")

    try:
        conn = get_db()
        cursor = conn.cursor()
        t_all = cursor.execute("SELECT COUNT(*) FROM series").fetchone()[0]
        t_ok = cursor.execute("SELECT COUNT(*) FROM series WHERE tmdbId IS NOT NULL").fetchone()[0]
        t_fail = cursor.execute("SELECT COUNT(*) FROM series WHERE failed = 1").fetchone()[0]
        t_wait = cursor.execute("""
            SELECT COUNT(*) FROM series
            WHERE failed = 0
            AND (tmdbId IS NULL OR tmdbTitle IS NULL)
        """).fetchone()[0]

        set_update_state(is_running=True, task_name="메타데이터 매칭", total=t_all,
                         current=t_ok + t_fail, success=t_ok, fail=t_fail, clear_logs=True)
        emit_ui_log(f"메타데이터 매칭 작업을 시작합니다. (대상: {t_wait}개)", "info")

        if force_all:
            conn.execute('UPDATE series SET failed=0 WHERE tmdbId IS NULL')
            conn.commit()

        uncleaned_names_rows = conn.execute(
            'SELECT name FROM series WHERE cleanedName IS NULL AND tmdbId IS NULL AND failed = 0 GROUP BY name').fetchall()
        if uncleaned_names_rows:
            for idx, r in enumerate(uncleaned_names_rows):
                name = r['name']
                ct, yr = clean_title_complex(name)
                cursor.execute('UPDATE series SET cleanedName=?, yearVal=? WHERE name=? AND cleanedName IS NULL',
                               (ct, yr, name))
                if (idx + 1) % 2000 == 0: conn.commit()
            conn.commit()

        group_rows = conn.execute('''
            SELECT cleanedName, yearVal, category, MIN(name) as sample_name, GROUP_CONCAT(name, '|') as orig_names
            FROM series
            WHERE failed = 0
            AND (tmdbId IS NULL OR tmdbTitle IS NULL OR path IN (SELECT series_path FROM episodes WHERE season_number IS NULL))
            AND cleanedName IS NOT NULL
            GROUP BY cleanedName, yearVal, category
        ''').fetchall()
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
            info = get_tmdb_info_server(task['sample_name'], category=task['category'], ignore_cache=force_all)
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
                                    # 고화질 스틸컷 주소 (original) 적용
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
                UPDATE_STATE["current"] = (t_ok + t_fail + total_success + total_fail)
                UPDATE_STATE["success"] = (t_ok + total_success)
                UPDATE_STATE["fail"] = (t_fail + total_fail)

            if (i // batch_size) % 10 == 0: build_all_caches()

        build_all_caches()
        set_update_state(is_running=False, current_item=f"매칭 완료 (+{total_success}건)")
    except:
        log("METADATA", f"⚠️ 에러 발생: {traceback.format_exc()}")
    finally:
        IS_METADATA_RUNNING = False


def get_sections_for_category(cat, kw=None):
    cache_key = f"sections_{cat}_{kw}"
    if cache_key in _SECTION_CACHE:
        return _SECTION_CACHE[cache_key]

    base_list = _FAST_CATEGORY_CACHE.get(cat, [])
    if not base_list: return []

    # Filter by keyword if provided (e.g., "라프텔", "제목", "드라마")
    target_list = base_list
    is_search = False
    if kw and kw not in ["전체", "All"]:
        search_kw = kw.strip().lower()
        # [수정] "방송중" 키워드 검색 시 "방송중(기타)"가 섞이지 않도록 폴더 단위 정확히 매칭 검사
        target_list = [i for i in base_list if
                       f"/{search_kw}/" in f"/{i['path'].lower()}/" or search_kw in i['name'].lower()]
        is_search = True

    if not target_list: return []

    # 방송중(air) 카테고리는 기존처럼 전체 목록 유지
    if cat == 'air':
        sections = [{"title": "실시간 방영 중", "items": target_list}]
    else:
        sections = []

        # 1. 오늘의 추천 (랜덤 40개)
        if len(target_list) > 20:
            random_picks = random.sample(target_list, min(40, len(target_list)))
            sections.append({"title": f"{kw if is_search else ''} 오늘의 추천".strip(), "items": random_picks})

        # 2. 최신 공개작 (2024년 이후)
        recent_items = [i for i in target_list if i.get('year') and i['year'] >= '2024']
        if len(recent_items) >= 5:
            sections.append({"title": f"{kw if is_search else ''} 최신 공개작".strip(), "items": recent_items[:100]})

        # 3. 장르별 섹션 (데이터 기반 자동 큐레이션)
        genre_map = {}
        for item in target_list:
            for g in item.get('genreNames', []):
                if g not in genre_map: genre_map[g] = []
                genre_map[g].append(item)

        # 아이템이 많은 순서대로 장르 정렬
        sorted_genres = sorted(genre_map.keys(), key=lambda x: len(genre_map[x]), reverse=True)
        # 너무 포괄적인 장르는 제외하고 상위 3개 선택
        display_genres = [g for g in sorted_genres if g not in ["TV 영화", "애니메이션"] or cat != 'animations_all'][:3]

        for g in display_genres:
            g_items = genre_map[g]
            if len(g_items) >= 5:
                title = f"{kw if is_search else ''} 인기 {g}".strip()
                # 해당 장르 내에서도 랜덤하게 노출
                sections.append({"title": title, "items": random.sample(g_items, min(60, len(g_items)))})

        # 4. 마지막에 전체 목록 추가
        sections.append({"title": "전체 목록", "items": target_list[:800]})

    _SECTION_CACHE[cache_key] = sections
    return sections


@app.route('/category_sections')
def get_category_sections():
    cat = request.args.get('cat', 'movies')
    kw = request.args.get('kw')
    return gzip_response(get_sections_for_category(cat, kw))


@app.route('/home')
def get_home():
    return gzip_response(HOME_RECOMMEND)


@app.route('/list')
def get_list():
    p = request.args.get('path', '')
    m = {"영화": "movies", "외국TV": "foreigntv", "국내TV": "koreantv", "애니메이션": "animations_all", "방송중": "air",
         "movies": "movies", "foreigntv": "foreigntv", "koreantv": "koreantv", "animations_all": "animations_all",
         "air": "air"}
    cat = "movies"
    for label, code in m.items():
        if label in p:
            cat = code
            break
    bl = _FAST_CATEGORY_CACHE.get(cat, [])
    kw = request.args.get('keyword')
    lim = int(request.args.get('limit', 1000))
    off = int(request.args.get('offset', 0))

    # [수정] "제목" 예외 제거 및 폴더 단위 필터링 강화
    if kw and kw not in ["전체", "All"]:
        search_kw = nfc(kw).lower()
        res = [item for item in bl if
               f"/{search_kw}/" in f"/{item['path'].lower()}/" or search_kw in item['name'].lower()]
    else:
        res = bl

    return gzip_response(res[off:off + lim])


@app.route('/api/series_detail')
def get_series_detail_api():
    path = request.args.get('path')
    if not path: return gzip_response([])
    for c_path, data in _DETAIL_CACHE:
        if c_path == path: return gzip_response(data)

    conn = get_db()
    row = conn.execute('SELECT * FROM series WHERE path = ?', (path,)).fetchone()
    if not row:
        conn.close()
        return gzip_response([])
    series = dict(row)

    # [중요] 상세 페이지에서도 최적의 제목(TMDB > 정제된 제목 > 원본)을 name 필드에 넣어서 반환
    series['name'] = series.get('tmdbTitle') or series.get('cleanedName') or series.get('name')

    for col in ['genreIds', 'genreNames', 'actors']:
        if series.get(col):
            try:
                series[col] = json.loads(series[col])
            except:
                series[col] = []

    # [핵심 수정] 폴더 경로가 아니라 '정제된 제목(cleanedName)'과 '카테고리'를 기준으로 모든 회차를 찾습니다.
    cursor = conn.execute("""
        SELECT e.* FROM episodes e
        JOIN series s ON e.series_path = s.path
        WHERE s.cleanedName = ? AND s.category = ?
    """, (series['cleanedName'], series['category']))

    eps = []
    seen = set()
    for r in cursor.fetchall():
        if r['videoUrl'] not in seen:
            eps.append(dict(r))
            seen.add(r['videoUrl'])

    series['movies'] = sorted(eps, key=lambda x: natural_sort_key(x['title']))
    conn.close()

    _DETAIL_CACHE.append((path, series))
    return gzip_response(series)



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
    # 1. 검색어 정규화 (한글 자모 분리 방지)
    q = nfc(request.args.get('q', '')).lower().strip()
    if not q: return jsonify([])

    conn = get_db()

    # 2. 검색 및 그룹화 쿼리 (카테고리별 제목 단위로 묶음)
    query = """
        SELECT s.*
        FROM series s
        WHERE (
            s.path LIKE ?
            OR s.name LIKE ?
            OR s.cleanedName LIKE ?
            OR s.tmdbId LIKE ?
            OR s.overview LIKE ?
        )
        AND EXISTS (SELECT 1 FROM episodes WHERE series_path = s.path)
        GROUP BY s.category, TRIM(s.cleanedName)
        ORDER BY
            CASE
                WHEN s.name LIKE ? THEN 1
                WHEN s.cleanedName LIKE ? THEN 2
                ELSE 3
            END, s.name ASC
    """

    search_param = f'%{q}%'

    # 총 7개의 인자를 전달합니다.
    cursor = conn.execute(query, (
        search_param, search_param, search_param, search_param, search_param,
        f'{q}%', f'{q}%'
    ))

    rows = []
    for row in cursor.fetchall():
        item = dict(row)

        # [중요] 검색 결과에서도 최적의 제목을 name 필드에 반영
        item['name'] = item.get('tmdbTitle') or item.get('cleanedName') or item.get('name')

        # [핵심] 에피소드 목록을 비워서 보내야 앱에서 상세 회차 정보를 서버에 다시 요청합니다.
        item['movies'] = []

        for col in ['genreIds', 'genreNames', 'actors']:
            if item.get(col):
                try:
                    item[col] = json.loads(item[col])
                except:
                    item[col] = []
        rows.append(item)

    conn.close()
    return gzip_response(rows)

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
    path, prefix = request.args.get('path'), request.args.get('type')
    try:
        base = next(v[0] for k, v in PATH_MAP.items() if v[1] == prefix)
        full_path = get_real_path(os.path.join(base, nfc(urllib.parse.unquote(path))))
        if not os.path.exists(full_path):
            log("VIDEO", f"File not found: {full_path}")
            return "Not Found", 404

        # [iOS HLS Logic]
        ua = request.headers.get('User-Agent', '').lower()
        is_ios = any(x in ua for x in ['iphone', 'ipad', 'apple', 'avfoundation'])

        # [수정] 안드로이드나 에뮬레이터(ExoPlayer)에서 'apple' 키워드로 인해 iOS로 오판되는 현상 방지
        if 'android' in ua or 'exoplayer' in ua:
            is_ios = False

        if is_ios and not full_path.lower().endswith(('.mp4', '.m4v', '.mov')):
            sid = hashlib.md5(full_path.encode()).hexdigest()
            kill_old_processes(sid)

            sdir = os.path.join(HLS_ROOT, sid)
            os.makedirs(sdir, exist_ok=True)
            video_m3u8 = os.path.join(sdir, "video.m3u8")

            if not os.path.exists(video_m3u8):
                cmd = [FFMPEG_PATH, '-y', '-i', full_path, '-c:v', 'libx264', '-preset', 'ultrafast', '-crf', '28',
                       '-sn', '-c:a', 'aac', '-f', 'hls', '-hls_time', '6', '-hls_list_size', '0', video_m3u8]
                FFMPEG_PROCS[sid] = subprocess.Popen(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                for _ in range(40):
                    if os.path.exists(video_m3u8): break
                    time.sleep(0.5)

            return redirect(f"http://{MY_IP}:5000/hls/{sid}/video.m3u8")

        return send_file(full_path, conditional=True)
    except:
        log("VIDEO", f"Error serving video: {traceback.format_exc()}")
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
            "-crf", "28", "-an", "-f", "matroska", "pipe:1"
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
            return jsonify({"status": "error", "message": "TMDB ID not found"})

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
            info['tmdbId']
        )
        cursor.execute(
            'UPDATE series SET posterPath=?, year=?, overview=?, rating=?, seasonCount=?, genreIds=?, genreNames=?, director=?, actors=?, tmdbId=?, failed=0 WHERE name=?',
            (*up, orig_name))
        conn.commit()
        conn.close()

        if orig_name in MATCH_DIAGNOSTICS:
            del MATCH_DIAGNOSTICS[orig_name]

        build_all_caches()
        return jsonify({"status": "success"})
    except Exception as e:
        return jsonify({"status": "error", "message": "Missing data"})


def _generate_thumb_file(path_raw, prefix, tid, t, w):
    # 대형 TV를 위해 기본 생성 해상도를 1920px(Full HD급)로 고정
    target_w = "1920"
    tp = os.path.join(DATA_DIR, f"seek_{tid}_{t}_{target_w}.jpg")
    if os.path.exists(tp): return tp

    try:
        base = next(v[0] for k, v in PATH_MAP.items() if v[1] == prefix)
        vp = get_real_path(os.path.join(base, nfc(urllib.parse.unquote(path_raw))))
        if not os.path.exists(vp):
            log("THUMB", f"Video not found for thumb: {vp}")
            return None

        with THUMB_SEMAPHORE:
            if os.path.exists(tp): return tp
            log("THUMB", f"고화질 썸네일 생성 중: {os.path.basename(vp)} (1920px)")

            try:
                # 최상의 화질을 위해 lanczos 필터와 고품질(q:v 2) 설정 적용
                result = subprocess.run([
                    FFMPEG_PATH, "-y",
                    "-ss", str(t),
                    "-i", vp,
                    "-frames:v", "1",
                    "-map", "0:v:0",
                    "-an", "-sn",
                    "-q:v", "2",
                    "-vf", f"scale={target_w}:-1:flags=lanczos",
                    tp
                ], timeout=30, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)

                if result.returncode != 0:
                    log("THUMB_ERROR", f"FFmpeg failed: {result.stderr.decode()}")
            except subprocess.TimeoutExpired:
                log("THUMB_TIMEOUT", f"FFmpeg timed out for: {os.path.basename(vp)}")
            except Exception as e:
                log("THUMB_ERROR", f"Unexpected error: {str(e)}")

        return tp if os.path.exists(tp) else None
    except:
        log("THUMB_ERROR", f"Exception: {traceback.format_exc()}")
        return None

@app.route('/thumb_serve')
def thumb_serve():
    path, prefix, tid = request.args.get('path'), request.args.get('type'), request.args.get('id')
    t = request.args.get('t', default="300")
    w = request.args.get('w', default="1920") # 기본 요청 해상도를 1920으로 상향

    tp = _generate_thumb_file(path, prefix, tid, t, w)
    if tp and os.path.exists(tp):
        resp = make_response(send_file(tp, mimetype='image/jpeg'))
        resp.headers['Cache-Control'] = 'public, max-age=31536000, immutable'
        return resp
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
    """
    비디오의 전체 구간을 미리볼 수 있는 스프라이트 시트(스토리보드)를 생성하여 반환합니다.
    ExoPlayer 등 클라이언트에서 탐색 바(seek bar) 이동 시 썸네일을 표시하는 데 사용됩니다.
    """
    path_raw = request.args.get('path')
    prefix = request.args.get('type')
    if not path_raw or not prefix: return "Bad Request", 400

    try:
        base = next(v[0] for k, v in PATH_MAP.items() if v[1] == prefix)
        vp = get_real_path(os.path.join(base, nfc(urllib.parse.unquote(path_raw))))
        if not os.path.exists(vp): return "Not Found", 404

        # 파일명 기반 해시 생성 (캐시용)
        file_hash = hashlib.md5(vp.encode()).hexdigest()
        sb_path = os.path.join(DATA_DIR, f"sb_{file_hash}.jpg")

        # 이미 생성된 스토리보드가 있으면 반환
        if os.path.exists(sb_path):
            resp = make_response(send_file(sb_path, mimetype='image/jpeg'))
            resp.headers['Cache-Control'] = 'public, max-age=31536000, immutable'
            return resp

        # 생성 중 충돌 방지를 위한 락(Lock)
        with STORYBOARD_SEMAPHORE:
            if os.path.exists(sb_path):  # 락 획득 후 다시 확인
                return send_file(sb_path, mimetype='image/jpeg')

            duration = get_video_duration(vp)
            if duration == 0: return "Duration Error", 500

            # 10x10 그리드, 100개의 썸네일 생성
            interval = duration / 100

            # FFmpeg 명령어로 타일(Sprite Sheet) 생성
            # fps=1/interval: interval 초마다 1프레임 추출
            # scale=160:-1: 너비 160px로 리사이징 (높이 비율 유지)
            # tile=10x10: 10행 10열로 합치기
            cmd = [
                FFMPEG_PATH, "-y",
                "-i", vp,
                "-vf", f"fps=1/{interval},scale=160:-1,tile=10x10",
                "-frames:v", "1",
                "-q:v", "5",  # JPEG 품질
                sb_path
            ]

            log("STORYBOARD", f"썸네일 생성 시작: {os.path.basename(vp)} (Duration: {duration}s)")
            subprocess.run(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=60)

            if os.path.exists(sb_path):
                log("STORYBOARD", f"생성 완료: {os.path.basename(sb_path)}")
                return send_file(sb_path, mimetype='image/jpeg')
            else:
                log("STORYBOARD", "생성 실패")
                return "Generation Failed", 500
    except Exception as e:
        log("STORYBOARD", f"에러 발생: {str(e)}")
        return "Internal Server Error", 501


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
    """DB의 모든 제목을 재정제하고 공백을 제거하여 강제로 하나로 합칩니다."""
    try:
        conn = sqlite3.connect(DB_FILE, timeout=60)
        conn.row_factory = sqlite3.Row
        cursor = conn.cursor()
        rows = cursor.execute("SELECT path, name FROM series").fetchall()

        update_data = []
        for row in rows:
            path, name = row['path'], row['name']
            # 1. '장', '기', 'Part', '쿨' 등을 포함한 강력한 정제 수행
            ct, yr = clean_title_complex(name)
            # 2. 공백을 완전히 제거하여 '불꽃 소방대'와 '불꽃소방대'를 동일하게 만듦
            ct = ct.replace(" ", "")
            update_data.append((ct, yr, path))

        # 3. 제목과 연도를 업데이트하고, 꼬여있을 수 있는 TMDB ID를 초기화하여 재매칭을 준비합니다.
        cursor.executemany("UPDATE series SET cleanedName = ?, yearVal = ?, tmdbId = NULL, failed = 0 WHERE path = ?", update_data)

        conn.commit()
        conn.close()
        build_all_caches()
        return jsonify({"status": "success", "updated": len(update_data)})
    except Exception as e:
        return jsonify({"status": "error", "message": str(e)}), 500

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
            .tag { display: inline-block; padding: 3px 8px; border-radius: 4px; font-size: 0.85em; font-weight: bold; background: #333; color: #ccc; border: 1px solid #555;}
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

# --- [UI/캐시 로직 보존] ---
@app.route('/updater')
def updater_ui():
    return """
    <!DOCTYPE html>
    <html lang="ko">
    <head>
        <meta charset="UTF-8">
        <meta name="viewport" content="width=device-width, initial-scale=1.0">
        <title>NAS Player - 메타데이터 모니터링</title>
        <style>
            body { font-family: 'Segoe UI', Tahoma, Geneva, Verdana, sans-serif; background: #f4f6f9; color: #333; margin: 0; padding: 20px; }
            .container { max-width: 900px; margin: 0 auto; background: white; border-radius: 12px; box-shadow: 0 4px 12px rgba(0,0,0,0.1); padding: 30px; }
            h1 { font-size: 24px; color: #2c3e50; border-bottom: 2px solid #ecf0f1; padding-bottom: 15px; margin-top: 0; display: flex; align-items: center; gap: 10px; }
            .btn-group { margin-bottom: 25px; display: flex; gap: 10px; flex-wrap: wrap; }
            button { background: #6c757d; color: white; border: none; padding: 12px 20px; border-radius: 6px; cursor: pointer; font-size: 14px; font-weight: bold; transition: opacity 0.2s; box-shadow: 0 2px 4px rgba(0,0,0,0.1); }
            button:hover { opacity: 0.8; }
            button.btn-primary { background: #007bff; }
            button.btn-success { background: #28a745; }
            button.btn-warning { background: #ffc107; color: #212529; }
            .status-box { background: #f8f9fa; border: 1px solid #dee2e6; border-radius: 8px; padding: 20px; margin-bottom: 20px; }
            .status-header { font-size: 18px; font-weight: bold; margin-bottom: 15px; color: #34495e; display: flex; justify-content: space-between;}
            .task-badge { background: #e9ecef; color: #495057; padding: 3px 10px; border-radius: 15px; font-size: 13px; font-weight: bold;}
            .progress-container { background: #e9ecef; border-radius: 8px; height: 20px; width: 100%; overflow: hidden; margin-bottom: 15px; }
            .progress-bar { background: #28a745; height: 100%; width: 0%; transition: width 0.3s; }
            .stats { display: flex; justify-content: space-between; font-size: 14px; font-weight: bold; color: #495057; }
            .stats span.success { color: #28a745; }
            .stats span.fail { color: #dc3545; }
            .terminal { background: #1e1e1e; border-radius: 8px; padding: 15px; height: 500px; overflow-y: auto; font-family: 'Consolas', 'Courier New', monospace; font-size: 13px; line-height: 1.6; color: #d4d4d4; box-shadow: inset 0 2px 5px rgba(0,0,0,0.5); }
            .log-success { color: #4CAF50; }
            .log-error { color: #F44336; }
            .log-info { color: #9E9E9E; }
        </style>
    </head>
    <body>
        <div class="container">
            <h1>⚙️ 메타데이터 실시간 업데이트 모니터</h1>

            <div class="btn-group">
                <button class="btn-primary" onclick="triggerTask('/retry_failed_metadata')">↻ 실패 메타데이터 재매칭</button>
                <button class="btn-success" onclick="triggerTask('/apply_tmdb_thumbnails')">🖼️ TMDB 썸네일 일괄 교체</button>
                <button class ="btn-primary" style="background-color: #343a40;" onclick="triggerTask('/pre_extract_subtitles')"> 🎬 영화 자막 일괄 추출 </button>
                <button class="btn-warning" onclick="triggerTask('/rematch_metadata')">⚠️ 전체 강제 재스캔</button>
                <button class="btn-info" onclick="window.open('/admin_stills', '_blank')" style="background-color: #17a2b8;">📊 스틸컷 적용 확인</button>
                <button class ="btn-secondary" onclick="triggerTask('/rescan_broken')" style="background-color: #6c757d;"> 🔍 로컬 폴더 스캔 </button>
            </div>

            <div class="status-box">
                <div class="status-header">
                    <span id="statusText">처리 중: 대기 중</span>
                    <span class="task-badge" id="taskName">대기 중</span>
                </div>

                <div class="progress-container">
                    <div class="progress-bar" id="progressBar"></div>
                </div>

                <div class="stats">
                    <div>
                        <span id="progressCount">0 / 0</span>
                        <span id="progressPercent" style="margin-left: 10px; color: #007bff;">0%</span>
                    </div>
                    <div>
                        <span class="success" id="successCount">성공: 0</span> &nbsp;|&nbsp;
                        <span class="fail" id="failCount">실패: 0</span>
                    </div>
                </div>
            </div>

            <div class="terminal" id="terminalBox"></div>
        </div>

        <script>
            async function triggerTask(url) {
                if (confirm('작업을 시작하시겠습니까? (백그라운드에서 실행되며 모니터링 창에 반영됩니다)')) {
                    await fetch(url);
                }
            }

            async function updateStatus() {
                try {
                    const res = await fetch('/api/updater/status');
                    const data = await res.json();

                    document.getElementById('statusText').innerText = data.is_running ? `처리 중: ${data.current_item}` : `완료됨: ${data.current_item}`;
                    document.getElementById('taskName').innerText = data.task_name;

                    const percent = data.total > 0 ? Math.round((data.current / data.total) * 100) : 0;
                    document.getElementById('progressBar').style.width = percent + '%';

                    if(!data.is_running && data.total > 0) {
                        document.getElementById('progressBar').style.width = '100%';
                    }

                    document.getElementById('progressCount').innerText = `${data.current.toLocaleString()} / ${data.total.toLocaleString()}`;
                    document.getElementById('progressPercent').innerText = `${percent}%`;
                    document.getElementById('successCount').innerText = `성공: ${data.success.toLocaleString()}`;
                    document.getElementById('failCount').innerText = `실패: ${data.fail.toLocaleString()}`;

                    const term = document.getElementById('terminalBox');

                    if (data.logs.length > 0) {
                        let html = '';
                        data.logs.forEach(log => {
                            let cssClass = 'log-info';
                            let icon = 'ℹ️';

                            if (log.type === 'success') { cssClass = 'log-success'; icon = '✅'; }
                            else if (log.type === 'error') { cssClass = 'log-error'; icon = '❌'; }
                            else if (log.type === 'warning') { cssClass = 'log-error'; icon = '⚠️'; }

                            html += `<div class="${cssClass}">${log.time} ${icon} [UPDATE] ${log.msg}</div>`;
                        });

                        const isScrolledToBottom = term.scrollHeight - term.clientHeight <= term.scrollTop + 50;
                        term.innerHTML = html;

                        if (isScrolledToBottom) {
                            term.scrollTop = term.scrollHeight;
                        }
                    }
                } catch (e) {
                    console.error('Failed to fetch status:', e);
                }
            }

            setInterval(updateStatus, 500);
            updateStatus();
        </script>
    </body>
    </html>
    """


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


def build_all_caches():
    global _SECTION_CACHE
    _SECTION_CACHE = {}
    _rebuild_fast_memory_cache()
    build_home_recommend()


def _rebuild_fast_memory_cache():
    global _FAST_CATEGORY_CACHE
    temp = {}
    conn = get_db()
    log("CACHE", "⚙️ 경량 메모리 캐시 빌드 시작")
    for cat in ["movies", "foreigntv", "koreantv", "animations_all", "air"]:
        rows_dict = {}
        all_rows = conn.execute(
            'SELECT path, name, posterPath, year, rating, genreIds, genreNames, director, actors, tmdbId, cleanedName, yearVal, overview, tmdbTitle FROM series WHERE category = ? ORDER BY yearVal DESC, name ASC',
            (cat,)).fetchall()
        for row in all_rows:
            path, name, poster, year, rating, g_ids, g_names, director, actors, t_id, c_name, y_val, overview, tmdb_title = row
            if not poster and cat != 'air': continue
            if c_name is not None:
                ct, yr = c_name, y_val
            else:
                ct, yr = clean_title_complex(name)
            group_key = f"tmdb:{t_id}" if t_id else f"name:{ct}_{yr}"
            if group_key not in rows_dict:
                try:
                    genre_list = json.loads(g_names) if g_names else []
                except:
                    genre_list = []
                try:
                    genre_ids = json.loads(g_ids) if g_ids else []
                except:
                    genre_ids = []
                try:
                    actors_list = json.loads(actors) if actors else []
                except:
                    actors_list = []

                display_name = tmdb_title if tmdb_title else (c_name if c_name else name)

                rows_dict[group_key] = {
                    "path": path, "name": display_name, "posterPath": poster,
                    "year": year, "rating": rating, "genreIds": genre_ids, "genreNames": genre_list,
                    "director": director, "actors": actors_list, "tmdbId": t_id, "overview": overview, "movies": []
                }
        temp[cat] = list(rows_dict.values())
    conn.close()
    _FAST_CATEGORY_CACHE = temp
    log("CACHE", "✅ 경량 메모리 캐시 빌드 완료")


def build_home_recommend():
    global HOME_RECOMMEND
    try:
        m = _FAST_CATEGORY_CACHE.get('movies', [])
        k = _FAST_CATEGORY_CACHE.get('koreantv', [])
        a = _FAST_CATEGORY_CACHE.get('air', [])
        combined = m + k
        unique_map = {}
        for item in combined:
            uid = item.get('tmdbId') or item.get('path')
            if uid not in unique_map: unique_map[uid] = item
        unique_hot_list = list(unique_map.values())
        hot_picks = random.sample(unique_hot_list, min(100, len(unique_hot_list))) if unique_hot_list else []
        seen_ids = {(p.get('tmdbId') or p.get('path')) for p in hot_picks}
        airing_picks = []
        for item in a:
            uid = item.get('tmdbId') or item.get('path')
            if uid not in seen_ids:
                airing_picks.append(item)
                if len(airing_picks) >= 100: break
        HOME_RECOMMEND = [
            {"title": "지금 가장 핫한 인기작", "items": hot_picks},
            {"title": "실시간 방영 중", "items": airing_picks}
        ]
        log("CACHE", f"🏠 홈 추천 빌드 완료 ({len(hot_picks)} / {len(airing_picks)})")
    except:
        traceback.print_exc()


def background_init_tasks():
    build_all_caches()


if __name__ == '__main__':
    init_db()
    threading.Thread(target=background_init_tasks, daemon=True).start()
    app.run(host='0.0.0.0', port=5000, threaded=True)
