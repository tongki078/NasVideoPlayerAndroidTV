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
CACHE_VERSION = "135.0"

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
    if os.path.exists(p): FFMPEG_PATH = p; break

HOME_RECOMMEND = []
IS_METADATA_RUNNING = False
_FAST_CATEGORY_CACHE = {} # 고속 응답용 메모리 캐시

def log(msg):
    timestamp = datetime.now().strftime("%H:%M:%S")
    print(f"[{timestamp}] {msg}", flush=True)

def nfc(text): return unicodedata.normalize('NFC', text) if text else ""
def nfd(text): return unicodedata.normalize('NFD', text) if text else ""

# --- [DB 관리] ---
def get_db():
    conn = sqlite3.connect(DB_FILE, timeout=60)
    conn.row_factory = sqlite3.Row
    return conn

def init_db():
    conn = get_db(); cursor = conn.cursor()
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
    conn.commit(); conn.close()
    log("🗄️ [DB] 시스템 초기화 및 인덱스 확장 완료")

# --- [유틸리티] ---
def get_real_path(path):
    if not path or os.path.exists(path): return path
    if os.path.exists(nfc(path)): return nfc(path)
    if os.path.exists(nfd(path)): return nfd(path)
    return path

def migrate_json_to_db():
    if not os.path.exists(TMDB_CACHE_DIR): return

    # 이미 이관 완료되었는지 체크
    conn = get_db()
    row = conn.execute("SELECT value FROM server_config WHERE key = 'json_migration_done'").fetchone()
    if row and row['value'] == 'true':
        conn.close()
        return

    files = [f for f in os.listdir(TMDB_CACHE_DIR) if f.endswith(".json")]
    if not files:
        conn.close()
        return

    log(f"🚚 [MIGRATE] JSON 캐시 {len(files)}개 DB 이관 중...")
    for idx, f in enumerate(files):
        h = f.replace(".json", "")
        try:
            with open(os.path.join(TMDB_CACHE_DIR, f), 'r', encoding='utf-8') as file:
                data = json.load(file); conn.execute('INSERT OR REPLACE INTO tmdb_cache (h, data) VALUES (?, ?)', (h, json.dumps(data)))
        except: pass

        # 2000개마다 커밋하여 속도와 안정성 확보
        if (idx + 1) % 2000 == 0:
            conn.commit()
            log(f"  🚚 이관 진행 중... ({idx+1}/{len(files)})")

    conn.execute("INSERT OR REPLACE INTO server_config (key, value) VALUES ('json_migration_done', 'true')")
    conn.commit(); conn.close()
    log("✅ [MIGRATE] JSON 캐시 이관 완료")

# --- [정규식 및 클리닝] ---
REGEX_EXT = re.compile(r'\.[a-zA-Z0-9]{2,4}$')
REGEX_YEAR = re.compile(r'\((19|20)\d{2}\)|(?<!\d)(19|20)\d{2}(?!\d)')
REGEX_CH_PREFIX = re.compile(r'^\[(?:KBS|SBS|MBC|tvN|JTBC|OCN|Mnet|TV조선|채널A|MBN|ENA|KBS2|KBS1|CH\d+|TV)\]\s*')
REGEX_TECHNICAL_TAGS = re.compile(r'(?i)[.\s_-](?!(?:\d+\b))(\d{3,4}p|UHD|Bluray|Blu-ray|WEB-DL|WEBRip|HDRip|BDRip|DVDRip|H\.?26[45]|x26[45]|HEVC|AVC|AAC\d?|DTS-?H?D?|AC3|DDP\d?|DD\+\d?|Dual|Atmos|REPACK|10bit|REMUX|FLAC|xvid|DivX|MKV|MP4|AVI|HDR(?:10)?(?:\+)?|DV|Vision|Dolby|NF|AMZN|HMAX|DSNP|AppleTV?|Disney|PCOK|playWEB|ATVP|HULU|HDTV|HD|KBS|SBS|MBC|TVN|JTBC|NEXT|ST|SW|KL|YT|MVC|KN|FLUX|hallowed|PiRaTeS|Jadewind)(\b|$|[.\s_-])')
REGEX_EP_MARKER_STRICT = re.compile(r'(?i)(?:[.\s_-]|(?<=[가-힣]))(?:S(\d+)E(\d+)|S(\d+)|E(\d+)|\d+\s*(?:화|회|기|부)|Season\s*\d+|Part\s*\d+|Episode\s*\d+|Disk\s*\d+|Disc\s*\d+|CD\s*\d+|시즌\s*\d+|[상하]부|최종화|\d{6}|\d{8})')
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
    if cleaned.count('.') >= 2: cleaned = cleaned.replace('.', ' ')
    ep_match = REGEX_EP_MARKER_STRICT.search(cleaned)
    if ep_match: cleaned = cleaned[:ep_match.start()].strip()
    tech_match = REGEX_TECHNICAL_TAGS.search(cleaned)
    if tech_match: cleaned = cleaned[:tech_match.start()].strip()
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
        backup = REGEX_TMDB_HINT.sub('', orig_title); backup = REGEX_EXT.sub('', backup).strip()
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
    hint_id = extract_tmdb_id(title); ct, year = clean_title_complex(title); ct = nfc(ct)
    if not ct or REGEX_FORBIDDEN_TITLE.match(ct): return {"failed": True, "forbidden": True}
    cache_key = f"{ct}_{year}" if year else ct; h = hashlib.md5(nfc(cache_key).encode()).hexdigest()

    if not ignore_cache and h in TMDB_MEMORY_CACHE: return TMDB_MEMORY_CACHE[h]
    if not ignore_cache:
        try:
            conn = get_db(); row = conn.execute('SELECT data FROM tmdb_cache WHERE h = ?', (h,)).fetchone(); conn.close()
            if row:
                data = json.loads(row['data'])
                if not data.get('failed'): TMDB_MEMORY_CACHE[h] = data; return data
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
                if resp.status_code == 200: best = resp.json(); best['media_type'] = mt; results = [best]; break
        if not results:
            results = perform_search(ct, "ko-KR", "multi")
            if not results: results = perform_search(ct, "ko-KR", "tv")
            if not results: results = perform_search(ct, None, "multi")

        if results:
            best = results[0]; m_type, t_id = best.get('media_type'), best.get('id')
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
                            key = f"{s_num}_{ep['episode_number']}"
                            info['seasons_data'][key] = {"overview": ep.get('overview'), "air_date": ep.get('air_date')}

            TMDB_MEMORY_CACHE[h] = info
            try:
                conn = get_db(); conn.execute('INSERT OR REPLACE INTO tmdb_cache (h, data) VALUES (?, ?)', (h, json.dumps(info))); conn.commit(); conn.close()
            except: pass
            return info
    except: pass
    return {"failed": True}

# --- [스캔 및 탐색] ---
def scan_recursive_to_db(bp, prefix, category):
    log(f"📂 '{category}' 스캔 시작: {bp}"); base = nfc(get_real_path(bp)); all_files = []; stack = [base]; visited = set()
    while stack:
        curr = stack.pop(); real_curr = os.path.realpath(curr)
        if real_curr in visited: continue
        visited.add(real_curr)
        try:
            with os.scandir(curr) as it:
                for entry in it:
                    if entry.is_dir():
                        if not any(ex in entry.name for ex in EXCLUDE_FOLDERS) and not entry.name.startswith('.'): stack.append(entry.path)
                    elif entry.is_file() and entry.name.lower().endswith(VIDEO_EXTS): all_files.append(nfc(entry.path))
        except: pass
    conn = get_db(); cursor = conn.cursor(); cursor.execute('SELECT id, series_path FROM episodes WHERE series_path LIKE ?', (f"{category}/%",))
    db_data = {row['id']: row['series_path'] for row in cursor.fetchall()}; current_ids = set(); total = len(all_files)
    for idx, fp in enumerate(all_files):
        mid = hashlib.md5(fp.encode()).hexdigest(); current_ids.add(mid); rel = nfc(os.path.relpath(fp, base)); name = os.path.splitext(os.path.basename(fp))[0]; spath = f"{category}/{rel}"
        cursor.execute('INSERT OR IGNORE INTO series (path, category, name) VALUES (?, ?, ?)', (spath, category, name))
        if mid not in db_data:
            cursor.execute('INSERT OR REPLACE INTO episodes (id, series_path, title, videoUrl, thumbnailUrl) VALUES (?, ?, ?, ?, ?)', (mid, spath, os.path.basename(fp), f"/video_serve?type={prefix}&path={urllib.parse.quote(rel)}", f"/thumb_serve?type={prefix}&id={mid}&path={urllib.parse.quote(rel)}"))
        elif db_data[mid] != spath: cursor.execute('UPDATE episodes SET series_path = ? WHERE id = ?', (spath, mid))
        if (idx + 1) % 2000 == 0: conn.commit()
    for rid in (set(db_data.keys()) - current_ids): cursor.execute('DELETE FROM episodes WHERE id = ?', (rid,))
    cursor.execute('DELETE FROM series WHERE path NOT IN (SELECT DISTINCT series_path FROM episodes) AND category = ?', (category,))
    conn.commit(); conn.close(); log(f"✅ '{category}' 스캔 완료 ({total}개)")

def perform_full_scan():
    log(f"🚀 [FULL SCAN v{CACHE_VERSION}] 시작"); pk = [("영화", "movies"), ("외국TV", "foreigntv"), ("국내TV", "koreantv"), ("애니메이션", "animations_all"), ("방송중", "air")]
    conn = get_db(); rows = conn.execute("SELECT key FROM server_config WHERE key LIKE 'scan_done_%' AND value = 'true'").fetchall(); done = [r['key'].replace('scan_done_', '') for r in rows]; conn.close()
    for label, ck in pk:
        if ck in done: continue
        path, prefix = PATH_MAP[label]
        if os.path.exists(path):
            scan_recursive_to_db(path, prefix, ck)
            conn = get_db(); conn.execute("INSERT OR REPLACE INTO server_config (key, value) VALUES (?, 'true')", (f'scan_done_{ck}',)); conn.commit(); conn.close(); build_home_recommend(); _rebuild_fast_memory_cache()
    conn = get_db(); conn.execute('INSERT OR REPLACE INTO server_config (key, value) VALUES (?, ?)', ('last_scan_version', CACHE_VERSION)); conn.execute("DELETE FROM server_config WHERE key LIKE 'scan_done_%'"); conn.commit(); conn.close()
    threading.Thread(target=fetch_metadata_async, daemon=True).start()

def fetch_metadata_async(force_all=False):
    global IS_METADATA_RUNNING
    if IS_METADATA_RUNNING: return
    IS_METADATA_RUNNING = True; log(f"🚀 [METADATA] 병렬 재매칭 시작")
    try:
        conn = get_db()
        if force_all: conn.execute('UPDATE series SET posterPath=NULL, tmdbId=NULL, failed=0'); conn.commit()
        tasks = conn.execute('SELECT path, name FROM series WHERE tmdbId IS NULL AND failed = 0').fetchall(); total = len(tasks); conn.close()
        log(f"📊 대상 작품: {total}개 (병렬 처리)")

        def process_one(row):
            info = get_tmdb_info_server(row['name'], ignore_cache=force_all)
            return (row['path'], row['name'], info)

        batch_size = 50
        for i in range(0, total, batch_size):
            batch = tasks[i:i+batch_size]
            results = []
            with ThreadPoolExecutor(max_workers=10) as executor:
                future_to_path = {executor.submit(process_one, row): row for row in batch}
                for future in as_completed(future_to_path):
                    results.append(future.result())

            conn = get_db(); cursor = conn.cursor()
            for path, name, info in results:
                if info.get('failed'):
                    ct, _ = clean_title_complex(name)
                    cursor.execute('UPDATE series SET failed=1 WHERE path=?', (path,))
                    log(f"🔍 매칭 실패: {name} (정제: {ct})")
                else:
                    cursor.execute('UPDATE series SET posterPath=?, year=?, overview=?, rating=?, seasonCount=?, genreIds=?, genreNames=?, director=?, actors=?, tmdbId=?, failed=0 WHERE path=?', (info.get('posterPath'), info.get('year'), info.get('overview'), info.get('rating'), info.get('seasonCount'), json.dumps(info.get('genreIds', [])), json.dumps(info.get('genreNames', []), ensure_ascii=False), info.get('director'), json.dumps(info.get('actors', []), ensure_ascii=False), info.get('tmdbId'), path))
                    if 'seasons_data' in info:
                        cursor.execute('SELECT id, title FROM episodes WHERE series_path = ?', (path,))
                        for ep_row in cursor.fetchall():
                            s_num, e_num = extract_episode_numbers(ep_row['title'])
                            if e_num:
                                key = f"{s_num}_{e_num}"
                                ep_data = info['seasons_data'].get(key)
                                if ep_data:
                                    cursor.execute('UPDATE episodes SET overview=?, air_date=?, season_number=?, episode_number=? WHERE id=?', (ep_data['overview'], ep_data['air_date'], s_num, e_num, ep_row['id']))

                    log(f"✅ 매칭 성공: {name} -> {info.get('tmdbId')} (상세 정보 구축 완료)")
            conn.commit(); conn.close()
            log(f"🔍 [{round((i+len(batch))/total*100, 1)}%] 매칭 진행 중 ({i+len(batch)}/{total})")
            if (i // batch_size) % 5 == 0: build_home_recommend(); _rebuild_fast_memory_cache()

        log("✅ 모든 메타데이터 고도화 완료")
    except: traceback.print_exc()
    finally: IS_METADATA_RUNNING = False; log("🏁 매칭 종료")

# --- [초고속 메모리 필터링 API] ---
def build_home_recommend():
    try:
        conn = get_db(); cursor = conn.cursor()
        def get_items(sql):
            cursor.execute(f'SELECT s.*, e.id as ep_id, e.title as ep_title, e.videoUrl, e.thumbnailUrl FROM series s LEFT JOIN episodes e ON s.path = e.series_path WHERE {sql} GROUP BY s.path ORDER BY RANDOM() LIMIT 20')
            res = []
            for row in cursor.fetchall():
                item = dict(row);
                item['movies'] = [{"id": item.pop('ep_id'), "title": item.pop('ep_title'), "videoUrl": item.pop('videoUrl'), "thumbnailUrl": item.pop('thumbnailUrl')}] if item.get('ep_id') else [];
                for col in ['genreIds', 'genreNames', 'actors']:
                    if item.get(col): item[col] = json.loads(item[col])
                res.append(item)
            return res
        global HOME_RECOMMEND
        HOME_RECOMMEND = [{"title": "지금 가장 핫한 인기작", "items": get_items("1=1")}, {"title": "방금 올라온 최신 영화", "items": get_items("category = 'movies'")}, {"title": "지금 인기 있는 시리즈", "items": get_items("category IN ('koreantv', 'foreigntv')")}]
        conn.close()
    except: pass

def get_series_list_from_db(cat):
    conn = get_db(); cursor = conn.cursor()
    cursor.execute(f'SELECT s.*, e.id as ep_id, e.videoUrl, e.thumbnailUrl, e.title FROM series s LEFT JOIN episodes e ON s.path = e.series_path WHERE s.category = ? GROUP BY s.path ORDER BY s.name ASC', (cat,))
    rows = []
    for row in cursor.fetchall():
        item = dict(row); item['movies'] = [{"id": item.pop('ep_id'), "videoUrl": item.pop('videoUrl'), "thumbnailUrl": item.pop('thumbnailUrl'), "title": item.pop('title')}] if item.get('ep_id') else []
        for col in ['genreIds', 'genreNames', 'actors']:
            if item.get(col): item[col] = json.loads(item[col])
        rows.append(item)
    conn.close(); return rows

def _rebuild_fast_memory_cache():
    global _FAST_CATEGORY_CACHE; temp = {}
    for cat in ["movies", "foreigntv", "koreantv", "animations_all", "air"]:
        try: temp[cat] = get_series_list_from_db(cat)
        except: pass
    _FAST_CATEGORY_CACHE = temp; log(f"🚀 [CACHE] 모든 카테고리 메모리 로드 완료")

def get_fast_filtered_list(cat, keyword=None):
    base_list = _FAST_CATEGORY_CACHE.get(cat, [])
    # 앱에서 요청하는 limit/offset 처리
    limit = int(request.args.get('limit', 500))
    offset = int(request.args.get('offset', 0))

    if keyword and keyword not in ["전체", "All"]:
        kw = nfc(keyword)
        filtered = [item for item in base_list if kw in item['path']]
    else:
        filtered = base_list

    return filtered[offset : offset + limit]

@app.route('/home')
def get_home(): return jsonify(HOME_RECOMMEND)

@app.route('/list')
def get_list():
    path = request.args.get('path', ''); mapping = {"영화": "movies", "외국TV": "foreigntv", "국내TV": "koreantv", "애니메이션": "animations_all", "애니": "animations_all", "방송중": "air", "movies": "movies", "foreigntv": "foreigntv", "koreantv": "koreantv", "animations_all": "animations_all", "air": "air"}
    keyword = request.args.get('keyword')
    if path in mapping: return jsonify(get_fast_filtered_list(mapping[path], keyword))
    return get_series_detail_api()

# 앱 전용 엔드포인트들도 모두 고속 메모리 캐시 사용 및 페이지네이션 지원
@app.route('/air')
def get_air_all(): return jsonify(get_fast_filtered_list("air"))
@app.route('/air_animations')
def get_air_anim(): return jsonify(get_fast_filtered_list("air", "라프텔 애니메이션"))
@app.route('/air_dramas')
def get_air_dra(): return jsonify(get_fast_filtered_list("air", "드라마"))
@app.route('/foreigntv')
def get_ftv_all(): return jsonify(get_fast_filtered_list("foreigntv"))
@app.route('/ftv_us')
def get_ftv_us(): return jsonify(get_fast_filtered_list("foreigntv", "미국 드라마"))
@app.route('/ftv_cn')
def get_ftv_cn(): return jsonify(get_fast_filtered_list("foreigntv", "중국 드라마"))
@app.route('/ftv_jp')
def get_ftv_jp(): return jsonify(get_fast_filtered_list("foreigntv", "일본 드라마"))
@app.route('/ftv_docu')
def get_ftv_docu(): return jsonify(get_fast_filtered_list("foreigntv", "다큐"))
@app.route('/ftv_etc')
def get_ftv_etc(): return jsonify(get_fast_filtered_list("foreigntv", "기타국가 드라마"))
@app.route('/koreantv')
def get_ktv_all(): return jsonify(get_fast_filtered_list("koreantv"))
@app.route('/koreantv_drama')
def get_ktv_dra(): return jsonify(get_fast_filtered_list("koreantv", "드라마"))
@app.route('/koreantv_sitcom')
def get_ktv_sit(): return jsonify(get_fast_filtered_list("koreantv", "시트콤"))
@app.route('/koreantv_variety')
def get_ktv_var(): return jsonify(get_fast_filtered_list("koreantv", "예능"))
@app.route('/koreantv_edu')
def get_ktv_edu(): return jsonify(get_fast_filtered_list("koreantv", "교양"))
@app.route('/koreantv_docu')
def get_ktv_docu(): return jsonify(get_fast_filtered_list("koreantv", "다큐멘터리"))
@app.route('/animations_all')
def get_anim_all(): return jsonify(get_fast_filtered_list("animations_all"))
@app.route('/anim_raftel')
def get_anim_r(): return jsonify(get_fast_filtered_list("animations_all", "라프텔"))
@app.route('/anim_series')
def get_anim_s(): return jsonify(get_fast_filtered_list("animations_all", "시리즈"))
@app.route('/movies')
def get_mov_all(): return jsonify(get_fast_filtered_list("movies"))
@app.route('/movies_uhd')
def get_mov_uhd(): return jsonify(get_fast_filtered_list("movies", "UHD"))
@app.route('/movies_latest')
def get_mov_lat(): return jsonify(get_fast_filtered_list("movies", "최신"))
@app.route('/movies_title')
def get_mov_tit(): return jsonify(get_fast_filtered_list("movies", "제목"))

@app.route('/api/series_detail')
def get_series_detail_api():
    path = request.args.get('path')
    if not path: return jsonify([])
    conn = get_db(); cursor = conn.cursor(); cursor.execute('SELECT * FROM series WHERE path = ?', (path,)); row = cursor.fetchone()
    if not row: conn.close(); return jsonify([])
    series = dict(row)
    for col in ['genreIds', 'genreNames', 'actors']:
        if series.get(col): series[col] = json.loads(series[col])
    if series.get('tmdbId'): cursor.execute("SELECT e.* FROM episodes e JOIN series s ON e.series_path = s.path WHERE s.tmdbId = ?", (series['tmdbId'],))
    else: cursor.execute("SELECT * FROM episodes WHERE series_path = ?", (path,))
    eps = []; seen = set()
    for r in cursor.fetchall():
        if r['videoUrl'] not in seen: eps.append(dict(r)); seen.add(r['videoUrl'])
    series['movies'] = sorted(eps, key=lambda x: natural_sort_key(x['title'])); conn.close(); return jsonify(series)

@app.route('/search')
def search_videos():
    q = request.args.get('q', '').lower()
    if not q: return jsonify([])
    # 검색은 어쩔수 없이 DB를 찌르지만 최적화됨
    conn = get_db(); cursor = conn.cursor(); cursor.execute(f'SELECT s.*, e.id as ep_id, e.videoUrl, e.thumbnailUrl, e.title FROM series s LEFT JOIN episodes e ON s.path = e.series_path WHERE (s.path LIKE ? OR s.name LIKE ?) GROUP BY s.path ORDER BY s.name ASC', (f'%{q}%', f'%{q}%'))
    rows = []
    for row in cursor.fetchall():
        item = dict(row); item['movies'] = [{"id": item.pop('ep_id'), "videoUrl": item.pop('videoUrl'), "thumbnailUrl": item.pop('thumbnailUrl'), "title": item.pop('title')}] if item.get('ep_id') else []
        for col in ['genreIds', 'genreNames', 'actors']:
            if item.get(col): item[col] = json.loads(item[col])
        rows.append(item)
    conn.close(); return jsonify(rows)

@app.route('/rescan_broken')
def rescan_broken(): threading.Thread(target=perform_full_scan, daemon=True).start(); return jsonify({"status": "success"})
@app.route('/rematch_metadata')
def rescan_metadata(): threading.Thread(target=fetch_metadata_async, args=(True,), daemon=True).start(); return jsonify({"status": "success"})

@app.route('/video_serve')
def video_serve():
    path, prefix = request.args.get('path'), request.args.get('type')
    try:
        base = next(v[0] for k, v in PATH_MAP.items() if v[1] == prefix)
        return send_file(get_real_path(os.path.join(base, nfc(urllib.parse.unquote(path)))), conditional=True)
    except: return "Not Found", 404

@app.route('/thumb_serve')
def thumb_serve():
    path, prefix, tid, t = request.args.get('path'), request.args.get('type'), request.args.get('id'), request.args.get('t', default="300")
    try:
        base = next(v[0] for k, v in PATH_MAP.items() if v[1] == prefix); vp = get_real_path(os.path.join(base, nfc(urllib.parse.unquote(path)))); tp = os.path.join(DATA_DIR, f"seek_{tid}_{t}.jpg")
        if not os.path.exists(tp): subprocess.run([FFMPEG_PATH, "-y", "-ss", t, "-i", vp, "-vframes", "1", "-q:v", "5", "-vf", "scale=320:-1", tp], timeout=15)
        return send_file(tp, mimetype='image/jpeg') if os.path.exists(tp) else ("Not Found", 404)
    except: return "Not Found", 404

def report_db_status():
    try:
        conn = get_db(); cursor = conn.cursor(); eps = cursor.execute("SELECT COUNT(*) FROM episodes").fetchone()[0]; ser = cursor.execute("SELECT COUNT(*) FROM series").fetchone()[0]; mtch = cursor.execute("SELECT COUNT(*) FROM series WHERE tmdbId IS NOT NULL").fetchone()[0]; cch = cursor.execute("SELECT COUNT(*) FROM tmdb_cache").fetchone()[0]; log("="*50); log(f"📊 [DB STATUS] 파일: {eps} / 시리즈: {ser} / 매칭: {mtch} / 캐시: {cch}"); log("="*50); conn.close()
    except: pass

if __name__ == '__main__':
    log(f"📺 NAS Server v{CACHE_VERSION} 시작 (초고속 메모리 캐시 모드)"); init_db(); migrate_json_to_db(); report_db_status();
    # 캐시 생성 및 추천 로직 백그라운드 실행 (서버는 즉시 시작)
    threading.Thread(target=lambda: (build_home_recommend(), _rebuild_fast_memory_cache()), daemon=True).start()
    conn = get_db(); last = ""
    try:
        row = conn.execute('SELECT value FROM server_config WHERE key = ?', ('last_scan_version',)).fetchone()
        if row: last = row['value']
    except: pass
    conn.close()
    if last != CACHE_VERSION: log(f"🆙 버전 업데이트({last}->{CACHE_VERSION})"); threading.Thread(target=perform_full_scan, daemon=True).start()
    app.run(host='0.0.0.0', port=5000, threaded=True)