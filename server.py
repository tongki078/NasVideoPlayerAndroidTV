import os, subprocess, hashlib, urllib.parse, unicodedata, threading, time, json, re, sys, traceback, shutil, requests, random, mimetypes, sqlite3
from flask import Flask, jsonify, send_from_directory, request, Response, redirect, send_file
from flask_cors import CORS
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime
from collections import deque

app = Flask(__name__)
CORS(app)

# MIME 타입 추가 등록
if not mimetypes.types_map.get('.mkv'): mimetypes.add_type('video/x-matroska', '.mkv')
if not mimetypes.types_map.get('.ts'): mimetypes.add_type('video/mp2t', '.ts')
if not mimetypes.types_map.get('.tp'): mimetypes.add_type('video/mp2t', '.tp')

# --- [1. 설정 및 경로] ---
MY_IP = "192.168.0.2"
DATA_DIR = "/volume2/video/thumbnails"
DB_FILE = "/volume2/video/video_metadata.db"
TMDB_CACHE_DIR = "/volume2/video/tmdb_cache"
HLS_ROOT = "/dev/shm/videoplayer_hls"
CACHE_VERSION = "137.3"

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
_DETAIL_CACHE = deque(maxlen=200)

THUMB_SEMAPHORE = threading.Semaphore(4)

def log(tag, msg):
    timestamp = datetime.now().strftime("%H:%M:%S")
    print(f"[{timestamp}] [{tag}] {msg}", flush=True)

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
    cursor.execute('CREATE TABLE IF NOT EXISTS series (path TEXT PRIMARY KEY, category TEXT, name TEXT, posterPath TEXT, year TEXT, overview TEXT, rating TEXT, seasonCount INTEGER, genreIds TEXT, genreNames TEXT, director TEXT, actors TEXT, failed INTEGER DEFAULT 0, tmdbId TEXT)')
    cursor.execute('CREATE TABLE IF NOT EXISTS episodes (id TEXT PRIMARY KEY, series_path TEXT, title TEXT, videoUrl TEXT, thumbnailUrl TEXT, overview TEXT, air_date TEXT, season_number INTEGER, episode_number INTEGER, FOREIGN KEY (series_path) REFERENCES series (path) ON DELETE CASCADE)')

    for col in ['genreNames', 'director', 'actors']:
        try: cursor.execute(f'ALTER TABLE series ADD COLUMN {col} TEXT')
        except: pass
    for col in ['overview', 'air_date']:
        try: cursor.execute(f'ALTER TABLE episodes ADD COLUMN {col} TEXT')
        except: pass
    for col in ['season_number', 'episode_number']:
        try: cursor.execute(f'ALTER TABLE episodes ADD COLUMN {col} INTEGER')
        except: pass

    cursor.execute('CREATE TABLE IF NOT EXISTS tmdb_cache (h TEXT PRIMARY KEY, data TEXT)')
    cursor.execute('CREATE TABLE IF NOT EXISTS server_config (key TEXT PRIMARY KEY, value TEXT)')
    cursor.execute('CREATE INDEX IF NOT EXISTS idx_series_category ON series(category)')
    cursor.execute('CREATE INDEX IF NOT EXISTS idx_series_name ON series(name)')
    cursor.execute('CREATE INDEX IF NOT EXISTS idx_series_tmdbId ON series(tmdbId)')
    cursor.execute('CREATE INDEX IF NOT EXISTS idx_episodes_series ON episodes(series_path)')
    conn.commit()
    conn.close()
    log("DB", "시스템 초기화 및 인덱스 확장 완료")

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

# --- [정규식 및 클리닝] ---
REGEX_EXT = re.compile(r'\.[a-zA-Z0-9]{2,4}$')
REGEX_YEAR = re.compile(r'\((19|20)\d{2}\)|(?<!\d)(19|20)\d{2}(?!\d)')
REGEX_CH_PREFIX = re.compile(r'^\[(?:KBS|SBS|MBC|tvN|JTBC|OCN|Mnet|TV조선|채널A|MBN|ENA|KBS2|KBS1|CH\d+|TV)\]\s*')
REGEX_TECHNICAL_TAGS = re.compile(r'(?i)[.\s_-](?!(?:\d+\b))(\d{3,4}p|UHD|Bluray|Blu-ray|WEB-DL|WEBRip|HDRip|BDRip|DVDRip|H\.?26[45]|x26[45]|HEVC|AVC|AAC\d?|DTS-?H?D?|AC3|DDP\d?|DD\+\d?|Dual|Atmos|REPACK|10bit|REMUX|FLAC|xvid|DivX|MKV|MP4|AVI|HDR(?:10)?(?:\+)?|Vision|Dolby|NF|AMZN|HMAX|DSNP|AppleTV?|Disney|PCOK|playWEB|ATVP|HULU|HDTV|HD|KBS|SBS|MBC|TVN|JTBC|NEXT|ST|SW|KL|YT|MVC|KN|FLUX|hallowed|PiRaTeS|Jadewind|Movie|pt\s*\d+|KOREAN|ITALIAN|JAPANESE|CHINESE|ENGLISH|FRENCH|GERMAN|SPANISH|THAI|VIETNAMESE)(\b|$|[.\s_-])')
REGEX_EP_MARKER_STRICT = re.compile(r'(?i)(?:[.\s_-]|(?<=[가-힣]))(?:S(\d+)E(\d+)|S(\d+)|E(\d+)|\d+\s*(?:화|회|기|부)|Season\s*\d+|Part\s*\d+|pt\s*\d+|Episode\s*\d+|Disk\s*\d+|Disc\s*\d+|CD\s*\d+|시즌\s*\d+|[상하]부|최종화|\d{6}|\d{8})')
REGEX_DATE_YYMMDD = re.compile(r'(?<!\d)\d{6}(?!\d)')
REGEX_FORBIDDEN_TITLE = re.compile(r'(?i)^\s*(Season\s*\d+|Part\s*\d+|EP\s*\d+|\d+화|\d+회|\d+기|시즌\s*\d+|S\d+|E\d+|Disk\s*\d+|Disc\s*\d+|CD\s*\d+|Specials?|Extras?|Bonus|미분류|기타|새\s*폴더|VIDEO|GDS3|GDRIVE|NAS|share|영화|외국TV|국내TV|애니메이션|방송중|제목|UHD|최신|최신작|최신영화|4K|1080P|720P)\s*$', re.I)
REGEX_BRACKETS = re.compile(r'\[.*?(?:\]|$)|\(.*?(?:\)|$)|\{.*?(?:\}|$)|\【.*?(?:\】|$)|\『.*?(?:\』|$)|\「.*?(?:\」|$)')
REGEX_TMDB_HINT = re.compile(r'\{tmdb[\s-]*(\d+)\}')
REGEX_JUNK_KEYWORDS = re.compile(r'(?i)\s*(?:더빙|자막|극장판|BD|TV|Web|OAD|OVA|ONA|Full|무삭제|감독판|확장판|(?<!\S)[상하](?!\S))\s*')
REGEX_SPECIAL_CHARS = re.compile(r'[\[\]()_\-!?【】『』「」"\'#@*※×,~:;]')
REGEX_LEADING_INDEX = re.compile(r'^(\d+\s+|(?:\d+\.(?!\d)\s*))')
REGEX_SPACES = re.compile(r'\s+')

def clean_title_complex(title):
    if not title: return "", None
    orig_title = nfc(title)
    cleaned = REGEX_EXT.sub('', orig_title)
    cleaned = REGEX_CH_PREFIX.sub('', cleaned)
    cleaned = REGEX_TMDB_HINT.sub('', cleaned)
    if cleaned.count('.') >= 2:
        cleaned = cleaned.replace('.', ' ')
    ep_match = REGEX_EP_MARKER_STRICT.search(cleaned)
    if ep_match:
        cleaned = cleaned[:ep_match.start()].strip()
    tech_match = REGEX_TECHNICAL_TAGS.search(cleaned)
    if tech_match:
        cleaned = cleaned[:tech_match.start()].strip()
    cleaned = REGEX_DATE_YYMMDD.sub(' ', cleaned)
    year_match = REGEX_YEAR.search(cleaned)
    year = year_match.group().replace('(', '').replace(')', '') if year_match else None
    cleaned = REGEX_YEAR.sub(' ', cleaned)
    cleaned = REGEX_BRACKETS.sub(' ', cleaned)
    cleaned = cleaned.replace("(자막)", "").replace("(더빙)", "").replace("[자막]", "").replace("[더빙]", "")
    cleaned = REGEX_JUNK_KEYWORDS.sub(' ', cleaned)
    cleaned = REGEX_SPECIAL_CHARS.sub(' ', cleaned)
    cleaned = REGEX_LEADING_INDEX.sub('', cleaned)
    cleaned = re.sub(r'([가-힣a-zA-Z])(\d+)$', r'\1 \2', cleaned)
    cleaned = REGEX_SPACES.sub(' ', cleaned).strip()
    if len(cleaned) < 2:
        backup = REGEX_TMDB_HINT.sub('', orig_title)
        backup = REGEX_EXT.sub('', backup).strip()
        if len(backup) >= 2: return backup, year
        return orig_title, year
    return nfc(cleaned), year

def extract_episode_numbers(filename):
    match = REGEX_EP_MARKER_STRICT.search(filename)
    if match:
        s, e = match.group(1), match.group(2)
        if s and e: return int(s), int(e)
        e_only = match.group(4) or match.group(2)
        if e_only: return 1, int(e_only)
    return 1, None

def natural_sort_key(s):
    if s is None: return []
    return [int(text) if text.isdigit() else text.lower() for text in re.split(r'(\d+)', nfc(str(s)))]

def extract_tmdb_id(title):
    match = REGEX_TMDB_HINT.search(nfc(title))
    return int(match.group(1)) if match else None

# --- [TMDB API] ---
def get_tmdb_info_server(title, ignore_cache=False):
    if not title: return {"failed": True}
    hint_id = extract_tmdb_id(title)
    ct, year = clean_title_complex(title)
    ct = nfc(ct)
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
                if not data.get('failed'):
                    TMDB_MEMORY_CACHE[h] = data
                    return data
        except: pass

    headers = {"Authorization": f"Bearer {TMDB_API_KEY}"}
    base_params = {"include_adult": "true", "region": "KR"}

    def perform_search(query, lang=None, m_type='multi'):
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
            for mt in ['movie', 'tv']:
                resp = requests.get(f"{TMDB_BASE_URL}/{mt}/{hint_id}", params={"language": "ko-KR", **base_params}, headers=headers, timeout=10)
                if resp.status_code == 200:
                    best = resp.json()
                    best['media_type'] = mt
                    results = [best]
                    break

        if not results:
            results = perform_search(ct, "ko-KR", "multi")
            if not results: results = perform_search(ct, "ko-KR", "tv")
            if not results: results = perform_search(ct, None, "multi")

        if results:
            best = results[0]
            m_type, t_id = best.get('media_type'), best.get('id')
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
                for s_num in range(1, (d_resp.get('number_of_seasons') or 1) + 1):
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
    except: pass
    return {"failed": True}

# --- [스캔 및 탐색] ---
def scan_recursive_to_db(bp, prefix, category):
    log("SCAN", f"'{category}' 탐색 중: {bp}")
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

        cursor.execute('INSERT OR IGNORE INTO series (path, category, name) VALUES (?, ?, ?)', (spath, category, name))
        if mid not in db_data:
            cursor.execute('INSERT OR REPLACE INTO episodes (id, series_path, title, videoUrl, thumbnailUrl) VALUES (?, ?, ?, ?, ?)', (mid, spath, os.path.basename(fp), f"/video_serve?type={prefix}&path={urllib.parse.quote(rel)}", f"/thumb_serve?type={prefix}&id={mid}&path={urllib.parse.quote(rel)}"))
        elif db_data[mid] != spath:
            cursor.execute('UPDATE episodes SET series_path = ? WHERE id = ?', (spath, mid))

        if (idx + 1) % 2000 == 0:
            conn.commit()

    for rid in (set(db_data.keys()) - current_ids):
        cursor.execute('DELETE FROM episodes WHERE id = ?', (rid,))
    cursor.execute('DELETE FROM series WHERE path NOT IN (SELECT DISTINCT series_path FROM episodes) AND category = ?', (category,))
    conn.commit()
    conn.close()
    log("SCAN", f"'{category}' 스캔 완료 ({total}개)")

def perform_full_scan():
    log("SYSTEM", f"전체 스캔 시작 (v{CACHE_VERSION})")
    pk = [("영화", "movies"), ("외국TV", "foreigntv"), ("국내TV", "koreantv"), ("애니메이션", "animations_all"), ("방송중", "air")]
    conn = get_db()
    rows = conn.execute("SELECT key FROM server_config WHERE key LIKE 'scan_done_%' AND value = 'true'").fetchone()
    done = [r['key'].replace('scan_done_', '') for r in rows] if rows else []
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
    if IS_METADATA_RUNNING: return
    IS_METADATA_RUNNING = True
    log("METADATA", "병렬 매칭 프로세스 시작")
    try:
        conn = get_db()
        if force_all:
            conn.execute('UPDATE series SET posterPath=NULL, tmdbId=NULL, failed=0')
            conn.commit()

        all_names_rows = conn.execute('SELECT name FROM series WHERE tmdbId IS NULL AND failed = 0').fetchall()
        conn.close()

        if not all_names_rows:
            log("METADATA", "매칭 대상 없음 (완료)")
            IS_METADATA_RUNNING = False
            return

        name_groups = {}
        for row in all_names_rows:
            orig_name = row['name']
            ct, year = clean_title_complex(orig_name)
            if not ct: continue
            key = (ct, year)
            name_groups.setdefault(key, set()).add(orig_name)

        tasks = [{'clean_title': ct, 'year': year, 'sample_name': list(names)[0], 'orig_names': list(names)} for (ct, year), names in name_groups.items()]
        total = len(tasks)
        log("METADATA", f"대상 작품: {total}개 (총 {len(all_names_rows)}개 파일)")

        def process_one(task):
            info = get_tmdb_info_server(task['sample_name'], ignore_cache=force_all)
            return (task, info)

        batch_size = 50
        for i in range(0, total, batch_size):
            batch = tasks[i:i+batch_size]
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
                    log("MATCH", f"성공: {task['clean_title']} -> {info.get('tmdbId')}")
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
                        def chunked_ep_fetch(names):
                            all_eps = []
                            for j in range(0, len(names), 900):
                                chunk = names[j:j+900]
                                ps = ','.join(['?'] * len(chunk))
                                cursor.execute(f'SELECT id, title FROM episodes WHERE series_path IN (SELECT path FROM series WHERE name IN ({ps}))', chunk)
                                all_eps.extend(cursor.fetchall())
                            return all_eps

                        eps_to_update = chunked_ep_fetch(orig_names)
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
            log("METADATA", f"배치 완료: 성공 {batch_success} / 실패 {batch_fail} (진행: {round((i+len(batch))/total*100, 1)}%)")
            if (i // batch_size) % 2 == 0:
                build_all_caches()

        log("METADATA", "일괄 업데이트 완료")
    except:
        traceback.print_exc()
    finally:
        IS_METADATA_RUNNING = False
        log("METADATA", "프로세스 종료")

# --- [풍성한 섹션화 API] ---
def get_sections_for_category(cat, kw=None):
    # 1. 원본 데이터 가져오기
    base_list = _FAST_CATEGORY_CACHE.get(cat, [])
    if not base_list: return []

    # 2. kw(폴더명/키워드) 필터링 (UHD 폴더 등 하위 경로 검색 강화)
    if kw and kw not in ["전체", "All", "제목"]:
        target_list = []
        for i in base_list:
            # 기본 path 검사
            if f"/{kw}/" in i['path'] or i['path'].startswith(f"{cat}/{kw}/"):
                target_list.append(i)
                continue
            # 그룹화된 개별 영화/에피소드 경로 검사 (UHD 폴더 등 대응)
            match = False
            for m in i.get('movies', []):
                # videoUrl 예시: /video_serve?type=movies&path=UHD%2FMovie.mp4
                v_url = urllib.parse.unquote(m.get('videoUrl', ''))
                if f"path={kw}/" in v_url or f"/{kw}/" in v_url:
                    match = True
                    break
            if match:
                target_list.append(i)
    else:
        target_list = base_list

    if not target_list:
        log("DEBUG", f"'{cat}' 카테고리 '{kw}' 폴더에 영상이 없습니다.")
        return []

    sections = []
    seen_ids = set()

    # 3. 방금 올라온 최신 콘텐츠 (상단 고정 30개)
    latest_items = target_list[:30]
    if latest_items:
        sections.append({"title": "방금 올라온 최신 콘텐츠", "items": latest_items})
        for item in latest_items:
            uid = item.get('tmdbId') or item.get('path')
            if uid: seen_ids.add(uid)

    # 4. 장르별 섹션 구성 (임계값 10 -> 5로 하향 조정하여 노출 확대)
    threshold = 5 if kw and kw not in ["전체", "All", "제목"] else 10
    genres = {}
    for item in target_list:
        uid = item.get('tmdbId') or item.get('path')
        if uid in seen_ids: continue

        g_names = item.get('genreNames') or []
        if not g_names: g_names = ["기타"]
        for g in g_names:
            genres.setdefault(g, []).append(item)

    sorted_genres = sorted(genres.items(), key=lambda x: len(x[1]), reverse=True)

    for g_name, g_items in sorted_genres:
        if len(g_items) >= threshold:
            sections.append({"title": f"인기 {g_name} 추천", "items": g_items[:60]})
            for item in g_items[:60]:
                uid = item.get('tmdbId') or item.get('path')
                if uid: seen_ids.add(uid)
        if len(sections) >= 5: break

    # 5. 나머지 랜덤 추천 (최소 10개 제한 제거하여 누락 방지)
    remaining = [i for i in target_list if (i.get('tmdbId') or i.get('path')) not in seen_ids]
    if remaining:
        sections.append({"title": "이런 콘텐츠는 어떠세요?", "items": random.sample(remaining, min(len(remaining), 60))})
    elif not sections:
        sections.append({"title": "전체 작품 둘러보기", "items": target_list[:100]})

    return sections

@app.route('/category_sections')
def get_category_sections():
    cat = request.args.get('cat', 'movies')
    kw = request.args.get('kw') # 탭(폴더) 키워드 파라미터 추가
    return jsonify(get_sections_for_category(cat, kw))

@app.route('/home')
def get_home():
    return jsonify(HOME_RECOMMEND)

@app.route('/list')
def get_list():
    p = request.args.get('path', '')
    m = {"영화": "movies", "외국TV": "foreigntv", "국내TV": "koreantv", "애니메이션": "animations_all", "방송중": "air", "movies": "movies", "foreigntv": "foreigntv", "koreantv": "koreantv", "animations_all": "animations_all", "air": "air"}
    cat = m.get(p) or "movies"

    bl = _FAST_CATEGORY_CACHE.get(cat, [])
    kw = request.args.get('keyword')
    lim = int(request.args.get('limit', 1000))
    off = int(request.args.get('offset', 0))

    res = [item for item in bl if not kw or nfc(kw) in item['path'] or nfc(kw).lower() in item['name'].lower()] if kw and kw not in ["전체", "All"] else bl
    return jsonify(res[off:off+lim])

@app.route('/api/series_detail')
def get_series_detail_api():
    path = request.args.get('path')
    if not path: return jsonify([])
    for c_path, data in _DETAIL_CACHE:
        if c_path == path: return jsonify(data)

    conn = get_db()
    row = conn.execute('SELECT * FROM series WHERE path = ?', (path,)).fetchone()
    if not row:
        conn.close()
        return jsonify([])

    series = dict(row)
    for col in ['genreIds', 'genreNames', 'actors']:
        if series.get(col):
            series[col] = json.loads(series[col])

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
    return jsonify(series)

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
                item[col] = json.loads(item[col])
        rows.append(item)
    conn.close()
    return jsonify(rows)

@app.route('/rescan_broken')
def rescan_broken():
    threading.Thread(target=perform_full_scan, daemon=True).start()
    return jsonify({"status": "success"})

@app.route('/rematch_metadata')
def rescan_metadata():
    threading.Thread(target=fetch_metadata_async, args=(True,), daemon=True).start()
    return jsonify({"status": "success"})

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
                subprocess.run([FFMPEG_PATH, "-y", "-skip_frame", "nokey", "-ss", t, "-i", vp, "-frames:v", "1", "-an", "-sn", "-map", "0:v:0", "-q:v", "6", "-vf", "scale=320:-1:flags=fast_bilinear", "-threads", "1", tp], timeout=8, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        return send_file(tp, mimetype='image/jpeg') if os.path.exists(tp) else ("Not Found", 404)
    except:
        return "Not Found", 404

def build_all_caches():
    build_home_recommend()
    _rebuild_fast_memory_cache()

def _rebuild_fast_memory_cache():
    global _FAST_CATEGORY_CACHE
    temp = {}
    conn = get_db()
    for cat in ["movies", "foreigntv", "koreantv", "animations_all", "air"]:
        rows_dict = {}
        group_ep_urls = {}
        all_rows = conn.execute('SELECT s.*, e.id as ep_id, e.videoUrl, e.thumbnailUrl, e.title FROM series s LEFT JOIN episodes e ON s.path = e.series_path WHERE s.category = ? ORDER BY s.name ASC', (cat,)).fetchall()
        for row in all_rows:
            item = dict(row)
            ct, year = clean_title_complex(item['name'])
            # 그룹 키 생성: TMDB ID가 있으면 무조건 ID로, 없으면 정제된 제목과 연도로 확실하게 묶음
            group_key = f"tmdb:{item['tmdbId']}" if item['tmdbId'] else f"name:{ct}_{year}"
            v_url = item.get('videoUrl')

            if group_key not in rows_dict:
                item['movies'] = []
                group_ep_urls[group_key] = set()
                if item.get('ep_id') and v_url:
                    item['movies'].append({"id": item['ep_id'], "videoUrl": v_url, "thumbnailUrl": item['thumbnailUrl'], "title": item['title']})
                    group_ep_urls[group_key].add(v_url)

                for col in ['genreIds', 'genreNames', 'actors']:
                    item[col] = json.loads(item[col]) if item.get(col) else []
                for k in ['ep_id', 'videoUrl', 'thumbnailUrl', 'title']:
                    item.pop(k, None)
                rows_dict[group_key] = item
            else:
                # 같은 그룹(작품)이면 에피소드만 추가 (URL 중복은 칼같이 체크)
                if item.get('ep_id') and v_url and v_url not in group_ep_urls[group_key]:
                    rows_dict[group_key]['movies'].append({"id": item['ep_id'], "videoUrl": v_url, "thumbnailUrl": item['thumbnailUrl'], "title": item['title']})
                    group_ep_urls[group_key].add(v_url)
        temp[cat] = list(rows_dict.values())
    conn.close()
    _FAST_CATEGORY_CACHE = temp

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
        hot_picks = random.sample(unique_hot_list, min(60, len(unique_hot_list))) if unique_hot_list else []
        seen_ids = { (p.get('tmdbId') or p.get('path')) for p in hot_picks }

        airing_picks = []
        for item in a:
            uid = item.get('tmdbId') or item.get('path')
            if uid not in seen_ids:
                airing_picks.append(item)
                if len(airing_picks) >= 60: break

        HOME_RECOMMEND = [
            {"title": "지금 가장 핫한 인기작", "items": hot_picks},
            {"title": "실시간 방영 중", "items": airing_picks}
        ]
        log("CACHE", f"홈 추천 빌드 완료 ({len(hot_picks)} / {len(airing_picks)})")
    except:
        traceback.print_exc()

def report_db_status():
    try:
        conn = get_db()
        eps = conn.execute("SELECT COUNT(*) FROM episodes").fetchone()[0]
        ser = conn.execute("SELECT COUNT(*) FROM series").fetchone()[0]
        mtch = conn.execute("SELECT COUNT(*) FROM series WHERE tmdbId IS NOT NULL").fetchone()[0]
        cch = conn.execute("SELECT COUNT(*) FROM tmdb_cache").fetchone()[0]
        log("DB", f"STATUS: 에피소드 {eps} / 시리즈 {ser} / 매칭 {mtch} / 캐시 {cch}")
        conn.close()
    except: pass

if __name__ == '__main__':
    init_db()
    migrate_json_to_db()
    report_db_status()
    threading.Thread(target=build_all_caches, daemon=True).start()
    app.run(host='0.0.0.0', port=5000, threaded=True)
