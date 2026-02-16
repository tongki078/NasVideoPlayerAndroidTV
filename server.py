import os, subprocess, hashlib, urllib.parse, unicodedata, threading, time, json, re, sys, traceback, shutil, requests, random, mimetypes, sqlite3, gzip
from flask import Flask, jsonify, send_from_directory, request, Response, redirect, send_file, make_response
from flask_cors import CORS
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime
from collections import deque
from io import BytesIO

app = Flask(__name__)
CORS(app)

# MIME 타입 추가 등록
if not mimetypes.types_map.get('.mkv'): mimetypes.add_type('video/x-matroska', '.mkv')
if not mimetypes.types_map.get('.ts'): mimetypes.add_type('video/mp2t', '.ts')
if not mimetypes.types_map.get('.tp'): mimetypes.add_type('video/mp2t', '.tp')

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
CACHE_VERSION = "137.12" # 버전 업그레이드

# [수정] 절대 경로를 사용하여 파일 생성 보장
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
FAILURE_LOG_PATH = os.path.join(SCRIPT_DIR, "metadata_failures.txt")
SUCCESS_LOG_PATH = os.path.join(SCRIPT_DIR, "metadata_success.txt")

TMDB_MEMORY_CACHE = {}
TMDB_API_KEY = "eyJhbGciOiJIUzI1NiJ9.eyJhdWQiOiI3OGNiYWQ0ZjQ3NzcwYjYyYmZkMTcwNTA2NDIwZDQyYyIsIm5iZiI6MTY1MzY3NTU4MC45MTUsInN1YiI6IjYyOTExNjNjMTI0MjVjMDA1MjI0ZGQzNCIsInNjb3BlcyI6WyJhcGlfcmVhZCJdLCJ2ZXJzaW9uIjoxfQ.3YU0WuIx_WDo6nTRKehRtn4N5I4uCgjI1tlpkqfsUhk".strip()
TMDB_BASE_URL = "https://api.themoviedb.org/3"

os.makedirs(DATA_DIR, exist_ok=True)
os.makedirs(TMDB_CACHE_DIR, exist_ok=True)
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
FFMPEG_PATH = "ffmpeg"
for p in ["/usr/local/bin/ffmpeg", "/var/packages/ffmpeg/target/bin/ffmpeg", "/usr/bin/ffmpeg"]:
    if os.path.exists(p):
        FFMPEG_PATH = p
        break

HOME_RECOMMEND = []
IS_METADATA_RUNNING = False
_FAST_CATEGORY_CACHE = {}
_SECTION_CACHE = {} # 카테고리 섹션 결과 캐시 추가
_DETAIL_CACHE = deque(maxlen=200)

THUMB_SEMAPHORE = threading.Semaphore(4)

def log(tag, msg):
    timestamp = datetime.now().strftime("%H:%M:%S")
    print(f"[{timestamp}] [{tag}] {msg}", flush=True)

def log_matching_failure(orig, cleaned, reason):
    """[추가] 매칭 실패 내용을 파일에 기록하여 AI 분석 지원"""
    timestamp = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    try:
        # 파일이 생성되었는지 로그로 확인
        with open(FAILURE_LOG_PATH, "a", encoding="utf-8") as f:
            f.write(f"[{timestamp}] [FAIL] ORIG: {orig} | CLEANED: {cleaned} | REASON: {reason}\n")
    except Exception as e:
        log("LOG_ERROR", f"실패 로그 파일 쓰기 중 에러: {str(e)}")

def log_matching_success(orig, cleaned, matched, tmdb_id):
    """[추가] 매칭 성공 내용을 파일에 기록하여 학습 데이터 확보"""
    timestamp = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    try:
        with open(SUCCESS_LOG_PATH, "a", encoding="utf-8") as f:
            f.write(f"[{timestamp}] [OK] ORIG: {orig} | CLEANED: {cleaned} | MATCHED: {matched} | ID: {tmdb_id}\n")
    except: pass

def nfc(text):
    return unicodedata.normalize('NFC', text) if text else ""

def nfd(text):
    return unicodedata.normalize('NFD', text) if text else ""

# --- [DB 관리] ---
def get_db():
    conn = sqlite3.connect(DB_FILE, timeout=60)
    conn.row_factory = sqlite3.Row
    return conn

def init_db():
    conn = get_db()
    cursor = conn.cursor()
    # 테이블 생성
    cursor.execute('CREATE TABLE IF NOT EXISTS series (path TEXT PRIMARY KEY, category TEXT, name TEXT, posterPath TEXT, year TEXT, overview TEXT, rating TEXT, seasonCount INTEGER, genreIds TEXT, genreNames TEXT, director TEXT, actors TEXT, failed INTEGER DEFAULT 0, tmdbId TEXT)')
    cursor.execute('CREATE TABLE IF NOT EXISTS episodes (id TEXT PRIMARY KEY, series_path TEXT, title TEXT, videoUrl TEXT, thumbnailUrl TEXT, overview TEXT, air_date TEXT, season_number INTEGER, episode_number INTEGER, FOREIGN KEY (series_path) REFERENCES series (path) ON DELETE CASCADE)')
    cursor.execute('CREATE TABLE IF NOT EXISTS tmdb_cache (h TEXT PRIMARY KEY, data TEXT)')
    cursor.execute('CREATE TABLE IF NOT EXISTS server_config (key TEXT PRIMARY KEY, value TEXT)')

    # 인덱스 생성
    cursor.execute('CREATE INDEX IF NOT EXISTS idx_series_category ON series(category)')
    cursor.execute('CREATE INDEX IF NOT EXISTS idx_series_name ON series(name)')
    cursor.execute('CREATE INDEX IF NOT EXISTS idx_series_tmdbId ON series(tmdbId)')
    cursor.execute('CREATE INDEX IF NOT EXISTS idx_episodes_series ON episodes(series_path)')

    # 누락된 컬럼 동적 추가 (PRAGMA 사용으로 최적화)
    def add_col_if_missing(table, col, type):
        cursor.execute(f"PRAGMA table_info({table})")
        cols = [c[1] for c in cursor.fetchall()]
        if col not in cols:
            log("DB", f"컬럼 추가: {table}.{col}")
            cursor.execute(f"ALTER TABLE {table} ADD COLUMN {col} {type}")

    add_col_if_missing('series', 'tmdbId', 'TEXT') # [중요] 누락 시 매칭 안됨
    add_col_if_missing('series', 'genreNames', 'TEXT')
    add_col_if_missing('series', 'director', 'TEXT')
    add_col_if_missing('series', 'actors', 'TEXT')
    # [추가] 정제 제목 캐싱 컬럼
    add_col_if_missing('series', 'cleanedName', 'TEXT')
    add_col_if_missing('series', 'yearVal', 'TEXT')

    add_col_if_missing('episodes', 'overview', 'TEXT')
    add_col_if_missing('episodes', 'air_date', 'TEXT')
    add_col_if_missing('episodes', 'season_number', 'INTEGER')
    add_col_if_missing('episodes', 'episode_number', 'INTEGER')

    # 인덱스 보강
    cursor.execute('CREATE INDEX IF NOT EXISTS idx_series_cleanedName ON series(cleanedName)')

    conn.commit()
    conn.close()
    log("DB", "시스템 초기화 및 최적화 완료")

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
        except: pass
        if (idx + 1) % 2000 == 0:
            conn.commit()
            log("MIGRATE", f"진행 중... ({idx+1}/{len(files)})")
    conn.execute("INSERT OR REPLACE INTO server_config (key, value) VALUES ('json_migration_done', 'true')")
    conn.commit()
    conn.close()
    log("MIGRATE", "이관 완료")

# --- [정규식 및 클리닝 대폭 강화] ---
REGEX_EXT = re.compile(r'\.[a-zA-Z0-9]{2,4}$')
REGEX_YEAR = re.compile(r'\((19|20)\d{2}\)|(?<!\d)(19|20)\d{2}(?!\d)')
REGEX_CH_PREFIX = re.compile(r'^\[(?:KBS|SBS|MBC|tvN|JTBC|OCN|Mnet|TV조선|채널A|MBN|ENA|KBS2|KBS1|CH\d+|TV)\]\s*')
# 기술 태그 및 국가 약어 보강 (LIMITED, RM4K, DC, THEATRICAL 등 로그 기반 보강)
REGEX_TECHNICAL_TAGS = re.compile(r'(?i)[.\s_-](?!(?:\d+\b))(\d{3,4}p|FHD|QHD|UHD|4K|Bluray|Blu-ray|WEB-DL|WEBRip|HDRip|BDRip|DVDRip|H\.?26[45]|x26[45]|HEVC|AVC|AAC\d?|DTS-?H?D?|AC3|DDP\d?|DD\+\d?|Dual|Atmos|REPACK|10bit|REMUX|FLAC|xvid|DivX|MKV|MP4|AVI|HDR(?:10)?(?:\+)?|Vision|Dolby|NF|AMZN|HMAX|DSNP|AppleTV?|Disney|PCOK|playWEB|ATVP|HULU|HDTV|HD|KBS|SBS|MBC|TVN|JTBC|NEXT|ST|SW|KL|YT|MVC|KN|FLUX|hallowed|PiRaTeS|Jadewind|Movie|pt\s*\d+|KOREAN|KOR|ITALIAN|JAPANESE|JPN|CHINESE|CHN|ENGLISH|ENG|USA|HK|TW|FRENCH|GERMAN|SPANISH|THAI|VIETNAMESE|WEB|DL|TVRip|HDR10Plus|IMAX|Unrated|REMASTERED|Criterion|NonDRM|BRRip|1080i|720i|국어|Mandarin|Cantonese|FanSub|VFQ|VF|2CH|5\.1CH|8m|2398|PROPER|PROMO|LIMITED|RM4K|DC|THEATRICAL|EXTENDED|FINAL|DUB|KORDUB|JAPDUB|ENGDUB|ARROW|EDITION|SPECIAL|COLLECTION|RETAIL)(\b|$|[.\s_-])')

# [수정] 에피소드 마커 정규식 강화 (제/화/話/S/E 및 범위 표시 지원, CJK 지원 확장)
REGEX_EP_MARKER_STRICT = re.compile(r'(?i)(?:^|[.\s_-]|[가-힣\u3040-\u30ff\u4e00-\u9fff])(?:第?\s*S(\d+)E(\d+)(?:[-~]E?\d+)?|第?\s*S(\d+)|第?\s*E(\d+)(?:[-~]\d+)?|\d+\s*(?:화|회|기|부|話)|Season\s*\d+|Part\s*\d+|pt\s*\d+|Episode\s*\d+|Disk\s*\d+|Disc\s*\d+|CD\s*\d+|시즌\s*\d+|[상하]부|최종화|\d{6}|\d{8})')

REGEX_DATE_YYMMDD = re.compile(r'(?<!\d)\d{6}(?!\d)')
# 금지 단어: 검색 실패의 90%인 부가 영상을 패턴 검색으로 완벽 차단
REGEX_FORBIDDEN_CONTENT = re.compile(r'(?i)(Storyboard|Behind the Scenes|Making of|Deleted Scenes|Alternate Scenes|Gag Reel|Gag Menu|Digital Hits|Trailer|Bonus|Extras|Gallery|Production|Visual Effects|VFX|등급고지|예고편|개봉버전|인터뷰|삭제장면|(?<!\S)[상하](?!\S))')
REGEX_FORBIDDEN_TITLE = re.compile(r'(?i)^\s*(Season\s*\d+|Part\s*\d+|EP\s*\d+|\d+화|\d+회|\d+기|시즌\s*\d+|S\d+|E\d+|Disk\s*\d+|Disc\s*\d+|CD\s*\d+|Specials?|Extras?|Bonus|미분류|기타|새\s*폴더|VIDEO|GDS3|GDRIVE|NAS|share|영화|외국TV|국내TV|애니메이션|방송중|제목|UHD|최신|최신작|최신영화|4K|1080P|720P)\s*$', re.I)

# [수정] 일본어 전각 괄호(（ ）) 포함
REGEX_BRACKETS = re.compile(r'\[.*?(?:\]|$)|\(.*?(?:\)|$)|\{.*?(?:\)|$)|\【.*?(?:\】|$)|\『.*?(?:\』|$)|\「.*?(?:\」|$)|\（.*?(?:\）|$)')
REGEX_TMDB_HINT = re.compile(r'\{tmdb[\s-]*(\d+)\}')
REGEX_JUNK_KEYWORDS = re.compile(r'(?i)\s*(?:더빙|자막|극장판|BD|TV|Web|OAD|OVA|ONA|Full|무삭제|감독판|확장판|익스텐디드|등급고지|예고편|(?<!\S)[상하](?!\S))\s*')

# [수정] 특수문자 제거 대상에 전각 괄호 추가
REGEX_SPECIAL_CHARS = re.compile(r'[\[\]()_\-!?【】『』「」"\'#@*※×,~:;（）]')
# [학습] 앞부분 숫자/인덱스 제거 정규식 강화 (08038 포켓몬스터 -> 포켓몬스터)
REGEX_LEADING_INDEX = re.compile(r'^\s*(\d{1,5}(?:\s+|[.\s_-]+|(?=[가-힣a-zA-Z])))|^\s*(\d{1,5}\. )')
REGEX_SPACES = re.compile(r'\s+')

def clean_title_complex(title):
    if not title: return "", None
    orig_title = nfc(title)

    # [차단] 부가 영상 키워드 감지 시 즉시 제외하여 성공률 보존 및 리소스 절약
    if REGEX_FORBIDDEN_CONTENT.search(orig_title):
        return "", None

    cleaned = REGEX_EXT.sub('', orig_title)
    cleaned = REGEX_CH_PREFIX.sub('', cleaned)
    cleaned = REGEX_TMDB_HINT.sub('', cleaned)
    if '.' in cleaned:
        cleaned = cleaned.replace('.', ' ')

    ep_match = REGEX_EP_MARKER_STRICT.search(cleaned)
    if ep_match:
        # [학습] 앞에 붙은 에피소드 마커 처리 (S08E027 포켓몬스터 -> 포켓몬스터)
        if ep_match.start() < 5:
            cleaned = cleaned[ep_match.end():].strip()
        else:
            cleaned = cleaned[:ep_match.start()].strip()

    tech_match = REGEX_TECHNICAL_TAGS.search(cleaned)
    if tech_match:
        cleaned = cleaned[:tech_match.start()].strip()

    # [학습] 한글/일어/영어/숫자 경계에 공백 삽입하여 TMDB 검색률 극대화
    # 한글/일어 - 영어
    cleaned = re.sub(r'([가-힣\u3040-\u30ff\u4e00-\u9fff])([a-zA-Z])', r'\1 \2', cleaned)
    cleaned = re.sub(r'([a-zA-Z])([가-힣\u3040-\u30ff\u4e00-\u9fff])', r'\1 \2', cleaned)
    # 한글/일어/영어 - 숫자
    cleaned = re.sub(r'([가-힣\u3040-\u30ff\u4e00-\u9fff\w])(\d+)', r'\1 \2', cleaned)
    cleaned = re.sub(r'(\d+)([가-힣\u3040-\u30ff\u4e00-\u9fff\w])', r'\1 \2', cleaned)

    cleaned = REGEX_DATE_YYMMDD.sub(' ', cleaned)
    year_match = REGEX_YEAR.search(cleaned)
    year = year_match.group().replace('(', '').replace(')', '') if year_match else None
    cleaned = REGEX_YEAR.sub(' ', cleaned)

    # [학습] 만약 모든 정제 후 남은 제목이 너무 짧으면, 삭제했던 괄호 안의 내용을 복구 시도 (Recovery Logic)
    if len(cleaned.strip()) < 2:
        brackets = re.findall(r'\[(.*?)\]|\((.*?)\)|（(.*?)）', orig_title)
        for b in brackets:
            inner = (b[0] or b[1] or b[2]).strip()
            if len(inner) >= 2 and not REGEX_TECHNICAL_TAGS.search(inner) and not REGEX_FORBIDDEN_TITLE.match(inner):
                cleaned = inner
                break

    cleaned = REGEX_BRACKETS.sub(' ', cleaned)
    cleaned = cleaned.replace("(자막)", "").replace("(더빙)", "").replace("[자막]", "").replace("[더빙]", "").replace("（자막）", "").replace("（더빙）", "")
    cleaned = REGEX_JUNK_KEYWORDS.sub(' ', cleaned)
    cleaned = REGEX_SPECIAL_CHARS.sub(' ', cleaned)
    cleaned = REGEX_LEADING_INDEX.sub('', cleaned)
    cleaned = re.sub(r'([가-힣a-zA-Z\u3040-\u30ff\u4e00-\u9fff])(\d+)$', r'\1 \2', cleaned)
    cleaned = REGEX_SPACES.sub(' ', cleaned).strip()

    if len(cleaned) < 2:
        return "", None
    return nfc(cleaned), year

def extract_episode_numbers(filename):
    match = REGEX_EP_MARKER_STRICT.search(filename)
    if match:
        s, e = match.group(1), match.group(2)
        if s and e: return int(s), int(e)
        s_only = match.group(3)
        if s_only: return int(s_only), 1
        e_only = match.group(4)
        if e_only: return 1, int(e_only)
    return 1, None

def natural_sort_key(s):
    if s is None: return []
    return [int(text) if text.isdigit() else text.lower() for text in re.split(r'(\d+)', nfc(str(s)))]

def extract_tmdb_id(title):
    match = REGEX_TMDB_HINT.search(nfc(title))
    return int(match.group(1)) if match else None

# --- [TMDB API 보완: 지능형 재검색] ---
def get_tmdb_info_server(title, ignore_cache=False):
    if not title: return {"failed": True}
    hint_id = extract_tmdb_id(title)
    ct, year = clean_title_complex(title)
    if not ct or REGEX_FORBIDDEN_TITLE.match(ct):
        return {"failed": True, "forbidden": True}

    cache_key = f"{ct}_{year}" if year else ct
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
                # [최적화] 실패 결과도 캐시에서 즉시 반환하여 반복 작업 방지
                TMDB_MEMORY_CACHE[h] = data
                return data
        except: pass

    log("TMDB", f"🔍 검색 시작: '{ct}'" + (f" ({year})" if year else ""))
    headers = {"Authorization": f"Bearer {TMDB_API_KEY}"}
    base_params = {"include_adult": "true", "region": "KR"}

    def perform_search(query, lang=None, m_type='multi'):
        if not query or len(query) < 2: return []
        params = {**base_params, "query": query}
        if lang: params["language"] = lang
        if year: params["year"] = year
        try:
            r = requests.get(f"{TMDB_BASE_URL}/search/{m_type}", params=params, headers=headers, timeout=10)
            if r.status_code != 200: return []
            items = r.json().get('results', [])
            if m_type == 'multi': return [i for i in items if i.get('media_type') in ['movie', 'tv']]
            for i in items: i['media_type'] = m_type
            return items
        except: return []

    try:
        results = []
        if hint_id:
            log("TMDB", f"💡 힌트 ID 사용: {hint_id}")
            for mt in ['movie', 'tv']:
                resp = requests.get(f"{TMDB_BASE_URL}/{mt}/{hint_id}", params={"language": "ko-KR", **base_params}, headers=headers, timeout=10)
                if resp.status_code == 200:
                    best = resp.json()
                    best['media_type'] = mt
                    results = [best]
                    break

        if not results:
            # 1단계: 정제된 제목으로 검색
            results = perform_search(ct, "ko-KR", "multi")

            # 2단계: 실패 시 제목 분할 검색 (한글/영어/일어 혼용 대응)
            if not results:
                # [학습] 한글/일어만 추출 (숫자 완벽 배제하여 '08038 포켓몬스터' -> '포켓몬스터' 유도)
                cjk_only = "".join(re.findall(r'[가-힣\u3040-\u30ff\u4e00-\u9fff\s]+', ct)).strip()
                if cjk_only and len(cjk_only) >= 2 and cjk_only != ct:
                    log("TMDB", f"🔄 재검색 (CJK만): '{cjk_only}'")
                    results = perform_search(cjk_only, "ko-KR", "multi")

                # 실패 시 영어만 추출
                if not results:
                    en_only = "".join(re.findall(r'[a-zA-Z\s]+', ct)).strip()
                    if en_only and len(en_only) >= 3:
                        log("TMDB", f"🔄 재검색 (영어만): '{en_only}'")
                        results = perform_search(en_only, "ko-KR", "multi")

            # 3단계: 시즌제 대응 (숫자 제거)
            if not results:
                stripped_ct = re.sub(r'\s+\d+$', '', ct).strip()
                if stripped_ct != ct and len(stripped_ct) >= 2:
                    log("TMDB", f"🔄 재검색 (숫자 제거): '{stripped_ct}'")
                    results = perform_search(stripped_ct, "ko-KR", "multi")

            if not results: results = perform_search(ct, "ko-KR", "tv")
            if not results: results = perform_search(ct, None, "multi")

        if results:
            best = results[0]
            m_type, t_id = best.get('media_type'), best.get('id')
            log("TMDB", f"✅ 매칭 성공: '{ct}' -> {m_type}:{t_id}")
            log_matching_success(title, ct, best.get('title') or best.get('name'), f"{m_type}:{t_id}")
            d_resp = requests.get(f"{TMDB_BASE_URL}/{m_type}/{t_id}?language=ko-KR&append_to_response=content_ratings,credits", headers=headers, timeout=10).json()

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
                    s_resp = requests.get(f"{TMDB_BASE_URL}/tv/{t_id}/season/{s_num}?language=ko-KR", headers=headers, timeout=10).json()
                    if 'episodes' in s_resp:
                        for ep in s_resp['episodes']:
                            info['seasons_data'][f"{s_num}_{ep['episode_number']}"] = {"overview": ep.get('overview'), "air_date": ep.get('air_date')}

            TMDB_MEMORY_CACHE[h] = info
            try:
                conn = get_db()
                conn.execute('INSERT OR REPLACE INTO tmdb_cache (h, data) VALUES (?, ?)', (h, json.dumps(info)))
                conn.commit()
                conn.close()
            except: pass
            return info
        else:
            log("TMDB", f"❌ 검색 결과 없음: '{ct}'")
            log_matching_failure(title, ct, "NOT_FOUND_IN_TMDB")
            # [추가] 실패 결과도 캐싱하여 반복적인 낭비 방지
            try:
                conn = get_db()
                conn.execute('INSERT OR REPLACE INTO tmdb_cache (h, data) VALUES (?, ?)', (h, json.dumps({"failed": True})))
                conn.commit()
                conn.close()
            except: pass
    except:
        log("TMDB", f"⚠️ 에러 발생: {traceback.format_exc()}")
        log_matching_failure(title, ct, f"API_ERROR: {str(sys.exc_info()[1])}")
    return {"failed": True}

# --- [스캔 및 탐색] ---
def scan_recursive_to_db(bp, prefix, category):
    log("SCAN", f"📂 '{category}' 탐색 중: {bp}")
    base = nfc(get_real_path(bp))
    all_files = []
    stack = [base]
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
        except: pass

    conn = get_db()
    cursor = conn.cursor()
    cursor.execute('SELECT id, series_path FROM episodes WHERE series_path LIKE ?', (f"{category}/%",))
    db_data = {row['id']: row['series_path'] for row in cursor.fetchall()}
    current_ids = set()
    total = len(all_files)

    for idx, fp in enumerate(all_files):
        mid = hashlib.md5(fp.encode()).hexdigest()
        current_ids.add(mid)
        rel = nfc(os.path.relpath(fp, base))
        name = os.path.splitext(os.path.basename(fp))[0]
        spath = f"{category}/{rel}"

        # [최적화] 삽입 시점에 제목 정제 미리 수행 (나중에 다시 안해도 됨)
        ct, yr = clean_title_complex(name)
        cursor.execute('INSERT OR IGNORE INTO series (path, category, name, cleanedName, yearVal) VALUES (?, ?, ?, ?, ?)', (spath, category, name, ct, yr))

        if mid not in db_data:
            cursor.execute('INSERT OR REPLACE INTO episodes (id, series_path, title, videoUrl, thumbnailUrl) VALUES (?, ?, ?, ?, ?)', (mid, spath, os.path.basename(fp), f"/video_serve?type={prefix}&path={urllib.parse.quote(rel)}", f"/thumb_serve?type={prefix}&id={mid}&path={urllib.parse.quote(rel)}"))
        elif db_data[mid] != spath:
            cursor.execute('UPDATE episodes SET series_path = ? WHERE id = ?', (spath, mid))

        if (idx + 1) % 2000 == 0:
            conn.commit()
            log("SCAN", f"⏳ 진행 중... ({idx+1}/{total})")

    for rid in (set(db_data.keys()) - current_ids):
        cursor.execute('DELETE FROM episodes WHERE id = ?', (rid,))
    cursor.execute('DELETE FROM series WHERE path NOT IN (SELECT DISTINCT series_path FROM episodes) AND category = ?', (category,))
    conn.commit()
    conn.close()
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
            scan_recursive_to_db(path, prefix, ck)
            conn = get_db()
            conn.execute("INSERT OR REPLACE INTO server_config (key, value) VALUES (?, 'true')", (f'scan_done_{ck}',))
            conn.commit()
            conn.close()
            build_all_caches()

    conn = get_db()
    conn.execute('INSERT OR REPLACE INTO server_config (key, value) VALUES (?, ?)', ('last_scan_version', CACHE_VERSION))
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
        # [핵심 수정] force_all이더라도 이미 성공한 tmdbId는 유지하고 실패 항목만 초기화
        if force_all:
            log("METADATA", "🧹 실패한 항목 초기화 및 재시도 준비 (성공 항목 보존)...")
            conn.execute('UPDATE series SET failed=0 WHERE tmdbId IS NULL')
            conn.commit()

        # [최적화] 누락된 정제 제목 필드 채우기 (매칭되지 않은 항목만 대상으로)
        uncleaned_names_rows = conn.execute('SELECT name FROM series WHERE cleanedName IS NULL AND tmdbId IS NULL AND failed = 0 GROUP BY name').fetchall()
        if uncleaned_names_rows:
            log("METADATA", f"🧪 누락된 항목 제목 정제 중 ({len(uncleaned_names_rows)}개 고유 제목)...")
            cursor = conn.cursor()
            for idx, r in enumerate(uncleaned_names_rows):
                name = r['name']
                ct, yr = clean_title_complex(name)
                cursor.execute('UPDATE series SET cleanedName=?, yearVal=? WHERE name=? AND cleanedName IS NULL', (ct, yr, name))
                if (idx + 1) % 2000 == 0: conn.commit()
            conn.commit()

        log("METADATA", "📂 DB에서 매칭되지 않은 작품 목록 불러오는 중...")
        # [중요] tmdbId가 이미 있는 항목은 절대 다시 건드리지 않음 (비효율 제거)
        all_names_rows = conn.execute('SELECT name FROM series WHERE tmdbId IS NULL AND failed = 0').fetchall()

        if not all_names_rows:
            conn.close()
            log("METADATA", "✅ 매칭 대상 없음 (모든 작품이 이미 매칭되었거나 실패 처리됨)")
            IS_METADATA_RUNNING = False
            build_all_caches()
            return

        log("METADATA", f"🧩 총 {len(all_names_rows)}개 파일에 대해 그룹화 시작 (이미 성공한 매칭 제외)")

        # [최적화] SQL 그룹화를 통해 메모리 사용량 및 그룹화 속도 100배 개선
        group_rows = conn.execute('''
            SELECT cleanedName, yearVal, MIN(name) as sample_name, GROUP_CONCAT(name, '|') as orig_names
            FROM series
            WHERE tmdbId IS NULL AND failed = 0 AND cleanedName IS NOT NULL
            GROUP BY cleanedName, yearVal
        ''').fetchall()
        conn.close()

        tasks = []
        for gr in group_rows:
            tasks.append({
                'clean_title': gr['cleanedName'],
                'year': gr['yearVal'],
                'sample_name': gr['sample_name'],
                'orig_names': gr['orig_names'].split('|')
            })

        total = len(tasks)
        log("METADATA", f"📊 그룹화 완료: {total}개의 고유 작품 식별됨")

        def process_one(task):
            info = get_tmdb_info_server(task['sample_name'], ignore_cache=force_all)
            return (task, info)

        batch_size = 50
        total_success = 0
        total_fail = 0
        for i in range(0, total, batch_size):
            batch = tasks[i:i+batch_size]
            log("METADATA", f"📦 배치 처리 중 ({i+1}~{min(i+batch_size, total)} / {total})")
            results = []
            with ThreadPoolExecutor(max_workers=10) as executor:
                future_to_task = {executor.submit(process_one, t): t for t in batch}
                for future in as_completed(future_to_task):
                    results.append(future.result())

            log("METADATA", f"💾 배치 {i//batch_size + 1} 결과 DB 반영 중...")
            conn = get_db()
            cursor = conn.cursor()
            batch_success = 0
            batch_fail = 0
            for task, info in results:
                orig_names = task['orig_names']
                if info.get('failed'):
                    batch_fail += 1
                    # [최적화] 매칭 안된 것은 failed=1
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
                        info.get('tmdbId')
                    )
                    cursor.executemany('UPDATE series SET posterPath=?, year=?, overview=?, rating=?, seasonCount=?, genreIds=?, genreNames=?, director=?, actors=?, tmdbId=?, failed=0 WHERE name=?', [(*up, name) for name in orig_names])

                    if 'seasons_data' in info:
                        eps_to_update = []
                        for name in orig_names:
                            cursor.execute('SELECT id, title FROM episodes WHERE series_path IN (SELECT path FROM series WHERE name = ?)', (name,))
                            eps_to_update.extend(cursor.fetchall())

                        ep_batch = []
                        for ep_row in eps_to_update:
                            sn, EN = extract_episode_numbers(ep_row['title'])
                            if EN:
                                ei = info['seasons_data'].get(f"{sn}_{EN}")
                                if ei:
                                    ep_batch.append((ei.get('overview'), ei.get('air_date'), sn, EN, ep_row['id']))
                        if ep_batch:
                            cursor.executemany('UPDATE episodes SET overview=?, air_date=?, season_number=?, episode_number=? WHERE id=?', ep_batch)

            conn.commit()
            conn.close()
            total_success += batch_success
            total_fail += batch_fail

            log("METADATA", f"📈 진행 상황: ✅ 성공 {total_success} / ❌ 실패 {total_fail} (진행률: {round((min(i+batch_size, total))/total*100, 1)}%)")

            if (i // batch_size) % 10 == 0:
                log("METADATA", "♻️ 중간 캐시 갱신 중...")
                build_all_caches()

        build_all_caches()
        log("METADATA", f"🎊 최종 완료: 총 ✅ {total_success}개 성공, ❌ {total_fail}개 실패")
    except:
        log("METADATA", f"⚠️ 치명적 에러 발생: {traceback.format_exc()}")
    finally:
        IS_METADATA_RUNNING = False
        log("METADATA", "🏁 병렬 매칭 프로세스 종료")

# --- [풍성한 섹션화 API] ---
def get_sections_for_category(cat, kw=None):
    cache_key = f"sections_{cat}_{kw}"
    if cache_key in _SECTION_CACHE:
        return _SECTION_CACHE[cache_key]
    base_list = _FAST_CATEGORY_CACHE.get(cat, [])
    if not base_list: return []
    if kw and kw not in ["전체", "All"]:
        search_kw = kw.strip().lower()
        target_list = [i for i in base_list if search_kw in i['path'].lower() or search_kw in i['name'].lower()]
    else:
        target_list = base_list
    if not target_list: return []
    sections = [{"title": "전체 목록", "items": target_list[:400]}]
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
    m = {"영화": "movies", "외국TV": "foreigntv", "국내TV": "koreantv", "애니메이션": "animations_all", "방송중": "air", "movies": "movies", "foreigntv": "foreigntv", "koreantv": "koreantv", "animations_all": "animations_all", "air": "air"}
    cat = "movies"
    for label, code in m.items():
        if label in p:
            cat = code
            break
    bl = _FAST_CATEGORY_CACHE.get(cat, [])
    kw = request.args.get('keyword')
    lim = int(request.args.get('limit', 1000))
    off = int(request.args.get('offset', 0))
    res = [item for item in bl if not kw or nfc(kw).lower() in item['path'].lower() or nfc(kw).lower() in item['name'].lower()] if kw and kw not in ["전체", "All"] else bl
    return gzip_response(res[off:off+lim])

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
    for col in ['genreIds', 'genreNames', 'actors']:
        if series.get(col):
            try: series[col] = json.loads(series[col])
            except: series[col] = []
    if series.get('tmdbId'):
        cursor = conn.execute("SELECT e.* FROM episodes e JOIN series s ON e.series_path = s.path WHERE s.tmdbId = ?", (series['tmdbId'],))
    else:
        cursor = conn.execute("SELECT * FROM episodes WHERE series_path = ?", (path,))
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

@app.route('/search')
def search_videos():
    q = request.args.get('q', '').lower()
    if not q: return jsonify([])
    conn = get_db()
    cursor = conn.execute('SELECT s.*, e.id as ep_id, e.videoUrl, e.thumbnailUrl, e.title FROM series s LEFT JOIN episodes e ON s.path = e.series_path WHERE (s.path LIKE ? OR s.name LIKE ?) GROUP BY s.path ORDER BY s.name ASC', (f'%{q}%', f'%{q}%'))
    rows = []
    for row in cursor.fetchall():
        item = dict(row)
        item['movies'] = [{"id": item.pop('ep_id'), "videoUrl": item.pop('videoUrl'), "thumbnailUrl": item.pop('thumbnailUrl'), "title": item.pop('title')}] if item.get('ep_id') else []
        for col in ['genreIds', 'genreNames', 'actors']:
            if item.get(col):
                try: item[col] = json.loads(item[col])
                except: item[col] = []
        rows.append(item)
    conn.close()
    return gzip_response(rows)

@app.route('/rescan_broken')
def rescan_broken():
    threading.Thread(target=perform_full_scan, daemon=True).start()
    return jsonify({"status": "success"})

@app.route('/rematch_metadata')
def rescan_metadata():
    if IS_METADATA_RUNNING:
        return jsonify({"status": "error", "message": "Metadata process is already running."})
    # [수정] Full Rematch 시에도 이미 성공한 매칭은 건드리지 않도록 fetch_metadata_async 내부 로직 변경됨
    threading.Thread(target=fetch_metadata_async, args=(True,), daemon=True).start()
    return jsonify({"status": "success", "message": "Scanning for new or failed metadata in background."})

@app.route('/retry_failed_metadata')
def retry_failed_metadata():
    if IS_METADATA_RUNNING:
        return jsonify({"status": "error", "message": "Metadata process is already running."})
    conn = get_db()
    conn.execute('UPDATE series SET failed = 0 WHERE failed = 1')
    conn.commit()
    conn.close()
    threading.Thread(target=fetch_metadata_async, daemon=False).start()
    return jsonify({"status": "success", "message": "Retrying failed metadata with improved regex."})

@app.route('/video_serve')
def video_serve():
    path, prefix = request.args.get('path'), request.args.get('type')
    try:
        base = next(v[0] for k, v in PATH_MAP.items() if v[1] == prefix)
        return send_file(get_real_path(os.path.join(base, nfc(urllib.parse.unquote(path)))), conditional=True)
    except:
        return "Not Found", 404

@app.route('/thumb_serve')
def thumb_serve():
    path, prefix, tid, t = request.args.get('path'), request.args.get('type'), request.args.get('id'), request.args.get('t', default="300")
    try:
        base = next(v[0] for k, v in PATH_MAP.items() if v[1] == prefix)
        vp = get_real_path(os.path.join(base, nfc(urllib.parse.unquote(path))))
        tp = os.path.join(DATA_DIR, f"seek_{tid}_{t}.jpg")
        if not os.path.exists(tp):
            with THUMB_SEMAPHORE:
                # [수정] MKV 탐색 최적화 및 정확한 프레임 추출을 위해 -map 0:v:0 복구
                # -ss를 -i 앞에 두어 속도 개선, -frames:v 1로 단일 프레임 추출
                subprocess.run([FFMPEG_PATH, "-y", "-ss", t, "-i", vp, "-frames:v", "1", "-map", "0:v:0", "-an", "-sn", "-q:v", "5", "-vf", "scale=480:-1:flags=fast_bilinear", "-threads", "1", tp], timeout=15, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        return send_file(tp, mimetype='image/jpeg') if os.path.exists(tp) else ("Not Found", 404)
    except:
        return "Not Found", 404

@app.route('/api/status')
def get_server_status():
    try:
        conn = get_db()
        eps = conn.execute("SELECT COUNT(*) FROM episodes").fetchone()[0]
        ser = conn.execute("SELECT COUNT(*) FROM series").fetchone()[0]
        mtch = conn.execute("SELECT COUNT(*) FROM series WHERE tmdbId IS NOT NULL").fetchone()[0]
        fail = conn.execute("SELECT COUNT(*) FROM series WHERE failed = 1").fetchone()[0]
        conn.close()
        return jsonify({
            "total_episodes": eps,
            "total_series": ser,
            "matched_series": mtch,
            "failed_series": fail,
            "success_rate": f"{round(mtch/ser*100, 1)}%" if ser > 0 else "0%"
        })
    except:
        return jsonify({"error": traceback.format_exc()})

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
        # [최적화] overview, genreIds, actors 추가 로드
        all_rows = conn.execute('SELECT path, name, posterPath, year, rating, genreIds, genreNames, director, actors, tmdbId, cleanedName, yearVal, overview FROM series WHERE category = ? ORDER BY name ASC', (cat,)).fetchall()
        for row in all_rows:
            path, name, poster, year, rating, g_ids, g_names, director, actors, t_id, c_name, y_val, overview = row
            # [최적화] DB 캐시 우선 사용
            if c_name is not None:
                ct, yr = c_name, y_val
            else:
                ct, yr = clean_title_complex(name)

            group_key = f"tmdb:{t_id}" if t_id else f"name:{ct}_{yr}"
            if group_key not in rows_dict:
                try: genre_list = json.loads(g_names) if g_names else []
                except: genre_list = []
                try: genre_ids = json.loads(g_ids) if g_ids else []
                except: genre_ids = []
                try: actors_list = json.loads(actors) if actors else []
                except: actors_list = []
                rows_dict[group_key] = {
                    "path": path, "name": name, "posterPath": poster,
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
            if uid not in unique_map:
                unique_map[uid] = item
        unique_hot_list = list(unique_map.values())
        hot_picks = random.sample(unique_hot_list, min(100, len(unique_hot_list))) if unique_hot_list else []
        seen_ids = { (p.get('tmdbId') or p.get('path')) for p in hot_picks }
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
