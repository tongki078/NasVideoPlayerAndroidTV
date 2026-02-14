import os, subprocess, hashlib, urllib.parse, unicodedata, threading, time, json, re, sys, traceback, shutil, requests, random, mimetypes, sqlite3
from flask import Flask, jsonify, send_from_directory, request, Response, redirect, send_file
from flask_cors import CORS
from concurrent.futures import ThreadPoolExecutor
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
CACHE_FILE = "/volume2/video/video_cache.json"
DB_FILE = "/volume2/video/video_metadata.db"
TMDB_CACHE_DIR = "/volume2/video/tmdb_cache"
HLS_ROOT = "/dev/shm/videoplayer_hls"
CACHE_VERSION = "10.5" # 영화 폴더 구조 개선 버전

# TMDB 관련 전역 메모리 캐시
TMDB_MEMORY_CACHE = {}

# TMDB API KEY
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

# 메모리 상의 추천 리스트 (DB 조회 후 갱신)
HOME_RECOMMEND = []

# 매칭 중복 실행 방지 플래그
IS_METADATA_RUNNING = False

def log(msg):
    timestamp = datetime.now().strftime("%H:%M:%S")
    print(f"[{timestamp}] {msg}", flush=True)

def nfc(text): return unicodedata.normalize('NFC', text) if text else ""
def nfd(text): return unicodedata.normalize('NFD', text) if text else ""

# --- [DB 관리] ---
def get_db():
    conn = sqlite3.connect(DB_FILE)
    conn.row_factory = sqlite3.Row
    return conn

def init_db():
    conn = get_db()
    cursor = conn.cursor()
    # Series 테이블
    cursor.execute('''
        CREATE TABLE IF NOT EXISTS series (
            path TEXT PRIMARY KEY,
            category TEXT,
            name TEXT,
            posterPath TEXT,
            year TEXT,
            overview TEXT,
            rating TEXT,
            seasonCount INTEGER,
            genreIds TEXT,
            failed INTEGER DEFAULT 0
        )
    ''')
    # Episodes 테이블
    cursor.execute('''
        CREATE TABLE IF NOT EXISTS episodes (
            id TEXT PRIMARY KEY,
            series_path TEXT,
            title TEXT,
            videoUrl TEXT,
            thumbnailUrl TEXT,
            FOREIGN KEY (series_path) REFERENCES series (path) ON DELETE CASCADE
        )
    ''')
    # 인덱스 추가
    cursor.execute('CREATE INDEX IF NOT EXISTS idx_series_category ON series(category)')
    cursor.execute('CREATE INDEX IF NOT EXISTS idx_episodes_series ON episodes(series_path)')
    conn.commit()
    conn.close()
    log("🗄️ [DB] 데이터베이스 초기화 완료")

def migrate_json_to_sqlite():
    if not os.path.exists(CACHE_FILE): return
    log("🚚 [MIGRATE] JSON 데이터를 SQLite로 이관을 시도합니다...")
    try:
        with open(CACHE_FILE, 'r', encoding='utf-8') as f:
            data = json.load(f)

        conn = get_db()
        cursor = conn.cursor()

        series_count = 0
        episode_count = 0
        for key in ["air", "movies", "foreigntv", "koreantv", "animations_all"]:
            category_items = data.get(key, [])
            log(f"  📂 [MIGRATE] '{key}' 카테고리 이관 중 ({len(category_items)}개 시리즈)")
            for cat in category_items:
                cursor.execute('''
                    INSERT OR REPLACE INTO series (path, category, name, posterPath, year, overview, rating, seasonCount, genreIds, failed)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ''', (
                    cat.get('path'), key, cat.get('name'), cat.get('posterPath'),
                    cat.get('year'), cat.get('overview'), cat.get('rating'),
                    cat.get('seasonCount'), json.dumps(cat.get('genreIds', [])),
                    1 if cat.get('failed') else 0
                ))
                series_count += 1

                for m in cat.get('movies', []):
                    cursor.execute('''
                        INSERT OR REPLACE INTO episodes (id, series_path, title, videoUrl, thumbnailUrl)
                        VALUES (?, ?, ?, ?, ?)
                    ''', (m.get('id'), cat.get('path'), m.get('title'), m.get('videoUrl'), m.get('thumbnailUrl')))
                    episode_count += 1

        conn.commit()
        conn.close()
        log(f"✅ [MIGRATE] 이관 완료: 시리즈 {series_count}개, 에피소드 {episode_count}개")
        os.rename(CACHE_FILE, CACHE_FILE + ".bak")
        log(f"📦 [MIGRATE] 기존 JSON 파일을 '{CACHE_FILE}.bak'으로 백업했습니다.")
    except Exception as e:
        log(f"❌ [MIGRATE] 이관 실패: {str(e)}")
        traceback.print_exc()

# --- [정규식 및 클리닝] ---
REGEX_EXT = re.compile(r'\.[a-zA-Z0-9]{2,4}$')
REGEX_YEAR = re.compile(r'\((19|20)\d{2}\)|(?<!\d)(19|20)\d{2}(?!\d)')
REGEX_EP_MARKER = re.compile(r'(?i)(?:^|[.\s_]|(?<=[가-힣]))(?:S\d+E\d+|S\d+|E\d+|\d+\s*(?:화|회|기)|Season\s*\d+|Part\s*\d+|Disk\s*\d+|Disc\s*\d+|CD\s*\d+).*')
# [수정] 금지된 제목 폴더에 '제목', 'UHD', '최신' 등 추가하여 해당 폴더가 하나의 시리즈로 묶이지 않게 함
REGEX_FORBIDDEN_TITLE = re.compile(r'(?i)^\s*(Season\s*\d+|Part\s*\d+|EP\s*\d+|\d+화|\d+회|\d+기|시즌\s*\d+|S\d+|E\d+|Disk\s*\d+|Disc\s*\d+|CD\s*\d+|Specials?|Extras?|Bonus|미분류|기타|새\s*폴더|VIDEO|GDS3|GDRIVE|NAS|share|영화|외국TV|국내TV|애니메이션|방송중|제목|UHD|최신|최신작|최신영화|4K|1080P|720P)\s*$', re.I)

def natural_sort_key(s):
    if s is None: return []
    return [int(text) if text.isdigit() else text.lower() for text in re.split(r'(\d+)', nfc(str(s)))]

def clean_title_complex(title):
    if not title: return "", None
    title = nfc(title)
    title = re.sub(r'^\d+[\s.]+(?=.+)', '', title).strip()
    cleaned = REGEX_EXT.sub('', title)
    year_match = REGEX_YEAR.search(cleaned)
    year = year_match.group().replace('(', '').replace(')', '') if year_match else None
    cleaned = REGEX_YEAR.sub(' ', cleaned)
    cleaned = REGEX_EP_MARKER.sub(' ', cleaned)
    cleaned = re.sub(r'\[.*?\]|\(.*?\)', ' ', cleaned)
    cleaned = re.sub(r'(?<!\d)\.|\.(?!\d)', ' ', cleaned)
    cleaned = re.sub(r'[\_\-!?【】『』「」"\'#@*※:]', ' ', cleaned)
    cleaned = re.sub(r'\s+', ' ', cleaned).strip()
    return cleaned, year

# --- [유틸리티] ---
def load_tmdb_memory_cache():
    if not os.path.exists(TMDB_CACHE_DIR): return
    for f in os.listdir(TMDB_CACHE_DIR):
        if f.endswith(".json"):
            try:
                with open(os.path.join(TMDB_CACHE_DIR, f), 'r', encoding='utf-8') as file:
                    data = json.load(file)
                    if not data.get('failed'): TMDB_MEMORY_CACHE[f.replace(".json", "")] = data
            except: pass

def get_real_path(path):
    if not path or os.path.exists(path): return path
    if os.path.exists(nfc(path)): return nfc(path)
    if os.path.exists(nfd(path)): return nfd(path)
    return path

def resolve_nas_path(app_path):
    app_path = nfc(urllib.parse.unquote(app_path or ""))
    parts = app_path.split('/')
    if parts and parts[0] in PATH_MAP:
        base_dir, type_code = PATH_MAP[parts[0]]
        return get_real_path(os.path.join(base_dir, "/".join(parts[1:]))), type_code
    return None, None

def get_meaningful_name(path):
    curr = nfc(path)
    while True:
        name = os.path.basename(curr)
        if not name: break
        if not REGEX_FORBIDDEN_TITLE.match(name) and name.lower() not in ["video", "share"]: return name
        parent = os.path.dirname(curr)
        if parent == curr: break
        curr = parent
    return os.path.basename(path)

def get_series_root_path(path, rel_base):
    curr = nfc(path); rel_base = nfc(rel_base)
    while True:
        name = os.path.basename(curr)
        if not name or curr == rel_base: break
        if not REGEX_FORBIDDEN_TITLE.match(name) and name.lower() not in ["video", "share"]: return nfc(os.path.relpath(curr, rel_base))
        parent = os.path.dirname(curr)
        if parent == curr: break
        curr = parent
    return nfc(os.path.relpath(path, rel_base))

# --- [TMDB 및 메타데이터] ---
def get_tmdb_info_server(title, ignore_cache=False):
    if not title: return {"failed": True}
    h = hashlib.md5(nfc(title).encode()).hexdigest(); cp = os.path.join(TMDB_CACHE_DIR, f"{h}.json")
    if not ignore_cache and h in TMDB_MEMORY_CACHE: return TMDB_MEMORY_CACHE[h]
    if not ignore_cache and os.path.exists(cp):
        try:
            with open(cp, 'r', encoding='utf-8') as f:
                data = json.load(f); TMDB_MEMORY_CACHE[h] = data; return data
        except: pass
    ct, year = clean_title_complex(title)
    if not ct or REGEX_FORBIDDEN_TITLE.match(ct): return {"failed": True, "forbidden": True}
    params = {"query": ct, "language": "ko-KR", "include_adult": "true", "region": "KR"}
    if year: params["year"] = year
    headers = {"Authorization": f"Bearer {TMDB_API_KEY}"}
    try:
        resp = requests.get(f"{TMDB_BASE_URL}/search/multi", params=params, headers=headers, timeout=5).json()
        results = [r for r in resp.get('results', []) if r.get('media_type') in ['movie', 'tv']]
        if results:
            best = results[0]; m_type, t_id = best.get('media_type'), best.get('id')
            d_resp = requests.get(f"{TMDB_BASE_URL}/{m_type}/{t_id}?language=ko-KR&append_to_response=content_ratings", params=params, headers=headers, timeout=5).json()
            year_val = (d_resp.get('release_date') or d_resp.get('first_air_date') or "").split('-')[0]
            rating = None
            if 'content_ratings' in d_resp:
                kr = next((r['rating'] for r in d_resp['content_ratings'].get('results', []) if r.get('iso_3166_1') == 'KR'), None)
                if kr: rating = f"{kr}+" if kr.isdigit() else kr
            info = {"genreIds": [g['id'] for g in d_resp.get('genres', [])], "posterPath": d_resp.get('poster_path'), "year": year_val, "overview": d_resp.get('overview'), "rating": rating, "seasonCount": d_resp.get('number_of_seasons'), "failed": False}
            TMDB_MEMORY_CACHE[h] = info
            with open(cp, 'w', encoding='utf-8') as f: json.dump(info, f, ensure_ascii=False)
            return info
    except: pass
    return {"failed": True}

# --- [스캔 및 탐색 로직 (SQLite)] ---
def scan_recursive_to_db(bp, prefix, category):
    log(f"  📂 '{category}' 카테고리 스캔 시작: {bp}")
    base = nfc(get_real_path(bp)); exts = VIDEO_EXTS; all_files = []
    stack = [base]
    visited_dirs = set()

    # 1단계: 파일 시스템 뒤지기 (심볼릭 링크에 의한 중복 탐색 방지)
    find_count = 0
    while stack:
        curr = stack.pop()
        real_curr = os.path.realpath(curr)
        if real_curr in visited_dirs: continue
        visited_dirs.add(real_curr)

        try:
            with os.scandir(curr) as it:
                for entry in sorted(list(it), key=lambda e: natural_sort_key(e.name)):
                    if entry.is_dir():
                        if not any(ex in entry.name for ex in EXCLUDE_FOLDERS) and not entry.name.startswith('.'):
                            stack.append(entry.path)
                    elif entry.is_file() and entry.name.lower().endswith(exts):
                        all_files.append(nfc(entry.path))
                        find_count += 1
                        if find_count % 1000 == 0:
                            log(f"    🔎 파일 탐색 중... 현재 {find_count}개 발견")
        except: pass

    log(f"  🔍 '{category}' 탐색 완료 (총 {len(all_files)}개). 이제 DB 정보를 갱신합니다.")
    conn = get_db()
    cursor = conn.cursor()

    series_map = {}
    db_update_count = 0
    for fp in all_files:
        dp = nfc(os.path.dirname(fp)); rel_path = get_series_root_path(dp, base)

        # [수정] 영화 카테고리 로직 강화: 각 파일을 개별 시리즈(영화)로 인식하도록 처리
        if category == 'movies':
            clean_name, _ = clean_title_complex(os.path.basename(fp))
            # 폴더가 '제목', '최신' 등 generic한 경우 파일명을 기준으로 경로 생성
            parent_folder = os.path.basename(dp)
            if REGEX_FORBIDDEN_TITLE.match(parent_folder):
                full_series_path = f"{category}/{rel_path}/{clean_name}".replace("//", "/")
                name = clean_name
            else:
                full_series_path = f"{category}/{rel_path}"
                name = get_meaningful_name(dp)
        else:
            full_series_path = f"{category}/{rel_path}"
            name = get_meaningful_name(dp)

        if full_series_path not in series_map:
            cursor.execute('INSERT OR IGNORE INTO series (path, category, name) VALUES (?, ?, ?)', (full_series_path, category, name))
            series_map[full_series_path] = True

        movie_id = hashlib.md5(fp.encode()).hexdigest()
        cursor.execute('''
            INSERT OR REPLACE INTO episodes (id, series_path, title, videoUrl, thumbnailUrl)
            VALUES (?, ?, ?, ?, ?)
        ''', (
            movie_id, full_series_path, os.path.basename(fp),
            f"/video_serve?type={prefix}&path={urllib.parse.quote(os.path.relpath(fp, base))}",
            f"/thumb_serve?type={prefix}&id={movie_id}&path={urllib.parse.quote(os.path.relpath(fp, base))}"
        ))
        db_update_count += 1
        if db_update_count % 1000 == 0:
            log(f"    ⏳ DB 업데이트 진행 중... ({db_update_count}/{len(all_files)})")
            conn.commit()

    conn.commit()
    conn.close()
    log(f"  ✅ '{category}' 모든 DB 갱신 완료.")

def perform_full_scan(cache_keys=None):
    keys = cache_keys if cache_keys else [("애니메이션", "animations_all"), ("외국TV", "foreigntv"), ("국내TV", "koreantv"), ("영화", "movies"), ("방송중", "air")]
    log(f"🔄 [SCAN] NAS 전체 재스캔 시작: {keys}")

    # 구형 카테고리 명칭(예: movie)으로 인한 중복 제거를 위해 현재 활성 카테고리 외에는 정리
    active_cats = [k[1] for k in keys]
    conn = get_db()
    conn.execute(f"DELETE FROM series WHERE category NOT IN ({','.join(['?']*len(active_cats))})", active_cats)
    conn.commit()
    conn.close()

    for label, cache_key in keys:
        path, prefix = PATH_MAP[label]
        scan_recursive_to_db(path, prefix, cache_key)

    log("🧠 [SCAN] 추천 리스트 갱신 중...")
    build_home_recommend()
    log("🏁 [SCAN] 전체 스캔 작업 완료")
    threading.Thread(target=fetch_metadata_async, daemon=True).start()

def fetch_metadata_async(force_all=False):
    global IS_METADATA_RUNNING
    if IS_METADATA_RUNNING:
        log("⚠️ [METADATA] 이미 매칭 작업이 진행 중입니다. 중복 실행을 방지합니다.")
        return

    IS_METADATA_RUNNING = True
    log("🚀 [METADATA] 백그라운드 TMDB 매칭 시작")
    try:
        conn = get_db()
        cursor = conn.cursor()

        cursor.execute('SELECT COUNT(*) as cnt FROM series')
        total_in_db = cursor.fetchone()['cnt']
        cursor.execute('SELECT COUNT(*) as cnt FROM series WHERE posterPath IS NOT NULL OR failed = 1')
        already_completed = cursor.fetchone()['cnt']

        if force_all:
            cursor.execute('SELECT path, name FROM series')
        else:
            cursor.execute('SELECT path, name FROM series WHERE posterPath IS NULL AND failed = 0')

        tasks = cursor.fetchall()
        conn.close()

        total_tasks = len(tasks)
        if total_tasks == 0:
            log("🏁 [METADATA] 매칭할 대상이 없습니다.")
            IS_METADATA_RUNNING = False
            return

        log(f"📊 [METADATA] 총 {total_tasks}개의 신규 항목을 TMDB와 매칭합니다. (현재 전체 완료: {already_completed}/{total_in_db})")

        count = 0
        success_count = 0
        fail_count = 0
        start_time = time.time()

        for row in tasks:
            path, name = row['path'], row['name']
            info = get_tmdb_info_server(name, ignore_cache=force_all)

            conn = get_db()
            cursor = conn.cursor()
            cursor.execute('''
                UPDATE series SET
                    posterPath = ?, year = ?, overview = ?, rating = ?,
                    seasonCount = ?, genreIds = ?, failed = ?
                WHERE path = ?
            ''', (
                info.get('posterPath'), info.get('year'), info.get('overview'),
                info.get('rating'), info.get('seasonCount'),
                json.dumps(info.get('genreIds', [])),
                1 if info.get('failed') else 0,
                path
            ))
            conn.commit()
            conn.close()

            count += 1
            if not info.get('failed'): success_count += 1
            else: fail_count += 1

            if count % 10 == 0 or count == total_tasks:
                elapsed = time.time() - start_time
                speed = count / elapsed if elapsed > 0 else 0
                remaining = (total_tasks - count) / speed if speed > 0 else 0
                current_total_progress = already_completed + count
                percent = (current_total_progress / total_in_db * 100) if total_in_db > 0 else 0
                log(f"  ⏳ 진행중: {current_total_progress}/{total_in_db} ({percent:.1f}%) - [성공: {success_count}, 실패: {fail_count}]")

            time.sleep(0.05)
        log(f"🏁 [METADATA] 모든 작업 완료")
        build_home_recommend()
    finally:
        IS_METADATA_RUNNING = False

def build_home_recommend():
    global HOME_RECOMMEND
    log("🏠 [HOME] 추천 리스트 구축 중...")
    try:
        conn = get_db()
        cursor = conn.cursor()

        def get_series_with_first_movie(sql_filter):
            # [수정] 영화 중복 제거 기준 강화: posterPath가 같거나 name이 같으면 하나로 그룹화
            group_by_clause = "GROUP BY COALESCE(s.posterPath, s.name)" if "movies" in sql_filter or "1=1" in sql_filter else "GROUP BY s.path"
            sql = f'''
                SELECT s.*, e.id as movie_id, e.title as movie_title, e.videoUrl, e.thumbnailUrl
                FROM series s
                LEFT JOIN (
                    SELECT * FROM episodes GROUP BY series_path
                ) e ON s.path = e.series_path
                WHERE {sql_filter}
                {group_by_clause}
                ORDER BY RANDOM() LIMIT 20
            '''
            cursor.execute(sql)
            results = []
            for row in cursor.fetchall():
                item = dict(row)
                if item.get('genreIds'): item['genreIds'] = json.loads(item['genreIds'])
                cursor.execute('SELECT * FROM episodes WHERE series_path = ?', (item['path'],))
                item['movies'] = [dict(r) for r in cursor.fetchall()]
                results.append(item)
            return results

        all_p = get_series_with_first_movie("1=1")
        m = get_series_with_first_movie("category = 'movies'")
        kf = get_series_with_first_movie("category IN ('koreantv', 'foreigntv')")

        conn.close()
        HOME_RECOMMEND = [
            {"title": "지금 가장 핫한 인기작", "items": all_p},
            {"title": "방금 올라온 최신 영화", "items": m},
            {"title": "지금 인기 있는 시리즈", "items": kf}
        ]
        log(f"🏠 [HOME] 추천 리스트 갱신 완료")
    except Exception as e:
        log(f"❌ [HOME] 추천 리스트 구축 실패: {str(e)}")

# --- [API 엔드포인트] ---
@app.route('/home')
def get_home(): return jsonify(HOME_RECOMMEND)

def get_series_list_api(category, filter_keyword=None):
    conn = get_db()
    cursor = conn.cursor()
    # [수정] 영화 카테고리는 포스터 경로가 같거나 이름이 같으면 하나로 묶어 중복 노출 방지
    group_by = "GROUP BY COALESCE(s.posterPath, s.name)" if category == "movies" else "GROUP BY s.path"

    query = f'''
        SELECT s.* FROM series s WHERE s.category = ?
    '''
    params = [category]
    if filter_keyword:
        query += ' AND (s.path LIKE ? OR s.name LIKE ?)'
        params.extend([f'%{filter_keyword}%', f'%{filter_keyword}%'])

    query += f' {group_by}'

    cursor.execute(query, params)
    rows = []
    for row in cursor.fetchall():
        item = dict(row)
        if item.get('genreIds'): item['genreIds'] = json.loads(item['genreIds'])
        cursor.execute("SELECT * FROM episodes WHERE series_path = ?", (item['path'],))
        item['movies'] = [dict(r) for r in cursor.fetchall()]
        rows.append(item)

    conn.close()
    return sorted(rows, key=lambda x: natural_sort_key(x['name']))

@app.route('/list')
def get_list_api():
    path = request.args.get('path', '')
    # 접두사 제거 (영화/movies/Path -> movies/Path)
    for prefix in ["영화/", "외국TV/", "국내TV/", "애니메이션/", "방송중/"]:
        if path.startswith(prefix):
            path = path[len(prefix):]
            break

    if not path or path in ["movies", "foreigntv", "koreantv", "animations_all", "air"]:
        return jsonify(get_series_list_api(path or "movies"))

    conn = get_db(); cursor = conn.cursor()
    cursor.execute('SELECT * FROM series WHERE path = ?', (path,))
    row = cursor.fetchone()
    if not row:
        conn.close(); return jsonify([])

    series = dict(row)
    if series.get('genreIds'): series['genreIds'] = json.loads(series['genreIds'])
    cursor.execute('SELECT * FROM episodes WHERE series_path = ?', (path,))
    series['movies'] = [dict(r) for r in cursor.fetchall()]
    conn.close()
    return jsonify([series])

def get_series_list_filtered(category, filter_keyword=None):
    synonyms = {
        "미국": ["미국", "미드", "us"], "중국": ["중국", "중드", "cn"], "일본": ["일본", "일드", "jp"],
        "기타": ["기타", "etc"], "다큐": ["다큐", "docu"], "드라마": ["드라마"], "시트콤": ["시트콤"],
        "예능": ["예능"], "교양": ["교양"], "uhd": ["uhd", "4k"],
        "latest": ["latest", "최신", "최신작", "최신영화", "최근"], "title": ["title", "제목"]
    }

    conn = get_db()
    cursor = conn.cursor()
    # [수정] 중복 제거 기준 강화
    group_by = "GROUP BY COALESCE(s.posterPath, s.name)" if category == "movies" else "GROUP BY s.path"

    filter_clause = ""
    params = [category]
    if filter_keyword:
        targets = synonyms.get(filter_keyword, [filter_keyword])
        filter_parts = []
        for t in targets:
            filter_parts.append("(s.path LIKE ? OR s.name LIKE ? OR s.path LIKE ? OR s.path LIKE ?)")
            params.extend([f'%/{t}/%', f'%{t}%', f'%/{t}', f'{t}/%'])
        filter_clause = " AND (" + " OR ".join(filter_parts) + ")"

    query = f'''
        SELECT s.* FROM series s WHERE s.category = ? {filter_clause} {group_by}
    '''

    cursor.execute(query, params)
    rows = []
    for row in cursor.fetchall():
        item = dict(row)
        if item.get('genreIds'): item['genreIds'] = json.loads(item['genreIds'])
        cursor.execute("SELECT * FROM episodes WHERE series_path = ?", (item['path'],))
        item['movies'] = [dict(r) for r in cursor.fetchall()]
        rows.append(item)

    conn.close()
    return sorted(rows, key=lambda x: natural_sort_key(x['name']))

@app.route('/air')
def get_air_all(): return jsonify(get_series_list_api("air"))
@app.route('/air_animations')
def get_air_animations(): return jsonify(get_series_list_api("air", "애니메이션"))
@app.route('/air_dramas')
def get_air_dramas(): return jsonify(get_series_list_api("air", "드라마"))

@app.route('/foreigntv')
def get_ftv(): return jsonify(get_series_list_api("foreigntv"))
@app.route('/ftv_us')
def get_ftv_us(): return jsonify(get_series_list_filtered("foreigntv", "미국"))
@app.route('/ftv_cn')
def get_ftv_cn(): return jsonify(get_series_list_filtered("foreigntv", "중국"))
@app.route('/ftv_jp')
def get_ftv_jp(): return jsonify(get_series_list_filtered("foreigntv", "일본"))
@app.route('/ftv_docu')
def get_ftv_docu(): return jsonify(get_series_list_filtered("foreigntv", "다큐"))
@app.route('/ftv_etc')
def get_ftv_etc(): return jsonify(get_series_list_filtered("foreigntv", "기타"))

@app.route('/koreantv')
def get_ktv(): return jsonify(get_series_list_api("koreantv"))
@app.route('/ktv_drama')
def get_ktv_drama(): return jsonify(get_series_list_filtered("koreantv", "드라마"))
@app.route('/ktv_sitcom')
def get_ktv_sitcom(): return jsonify(get_series_list_filtered("koreantv", "시트콤"))
@app.route('/ktv_variety')
def get_ktv_variety(): return jsonify(get_series_list_filtered("koreantv", "예능"))
@app.route('/ktv_edu')
def get_ktv_edu(): return jsonify(get_series_list_filtered("koreantv", "교양"))
@app.route('/ktv_docu')
def get_ktv_docu(): return jsonify(get_series_list_filtered("koreantv", "다큐멘터리"))

@app.route('/animations_all')
def get_anim(): return jsonify(get_series_list_api("animations_all"))
@app.route('/anim_raftel')
def get_anim_r(): return jsonify(get_series_list_filtered("animations_all", "라프텔"))
@app.route('/anim_series')
def get_anim_s(): return jsonify(get_series_list_filtered("animations_all", "시리즈"))

@app.route('/movies')
def get_movies(): return jsonify(get_series_list_api("movies"))
@app.route('/movies_uhd')
def get_movies_uhd(): return jsonify(get_series_list_filtered("movies", "uhd"))
@app.route('/movies_latest')
def get_movies_latest(): return jsonify(get_series_list_filtered("movies", "latest"))
@app.route('/movies_title')
def get_movies_title(): return jsonify(get_series_list_filtered("movies", "title"))

@app.route('/rescan_broken')
def rescan_broken():
    log("⚠️ 영화/방송중 카테고리 즉시 재탐색 요청 수신")
    threading.Thread(target=perform_full_scan, args=([("영화", "movies"), ("방송중", "air")],), daemon=True).start()
    return jsonify({"status": "success", "message": "영화/방송중 카테고리 재탐색 시작"})

@app.route('/rematch_metadata')
def rescan_metadata():
    log("⚠️ TMDB 메타데이터 전체 재매칭 요청 수신")
    threading.Thread(target=fetch_metadata_async, args=(True,), daemon=True).start()
    return jsonify({"status": "success", "message": "TMDB 메타데이터 전체 재매칭 시작 (백그라운드)"})

@app.route('/api/series_detail')
def get_series_detail_api():
    path = request.args.get('path')
    if not path: return jsonify(None)

    clean_path = path
    for prefix in ["영화/", "외국TV/", "국내TV/", "애니메이션/", "방송중/"]:
        if clean_path.startswith(prefix):
            clean_path = clean_path[len(prefix):]
            break

    conn = get_db(); cursor = conn.cursor()
    cursor.execute('SELECT * FROM series WHERE path = ?', (clean_path,))
    row = cursor.fetchone()
    if not row:
        conn.close(); return jsonify(None)
    series = dict(row)
    if series.get('genreIds'): series['genreIds'] = json.loads(series['genreIds'])

    if series.get('posterPath'):
        cursor.execute("SELECT e.* FROM episodes e JOIN series s ON e.series_path = s.path WHERE s.posterPath = ?", (series['posterPath'],))
    else:
        cursor.execute("SELECT e.* FROM episodes e JOIN series s ON e.series_path = s.path WHERE s.name = ?", (series['name'],))

    eps = []
    seen = set()
    for r in cursor.fetchall():
        if r['videoUrl'] not in seen:
            eps.append(dict(r))
            seen.add(r['videoUrl'])
    series['movies'] = sorted(eps, key=lambda x: natural_sort_key(x['title']))
    conn.close()
    return jsonify(series)

@app.route('/search')
def search_videos():
    q = request.args.get('q', '').lower()
    if not q: return jsonify([])
    conn = get_db(); cursor = conn.cursor()
    query = f'''
        SELECT s.* FROM series s
        WHERE s.name LIKE ? OR s.path LIKE ?
        GROUP BY COALESCE(s.posterPath, s.name)
    '''
    cursor.execute(query, (f'%{q}%', f'%{q}%'))
    rows = []
    for row in cursor.fetchall():
        item = dict(row)
        if item.get('genreIds'): item['genreIds'] = json.loads(item['genreIds'])
        cursor.execute("SELECT * FROM episodes WHERE series_path = ?", (item['path'],))
        item['movies'] = [dict(r) for r in cursor.fetchall()]
        rows.append(item)
    conn.close()
    return jsonify(rows)

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
        base = next(v[0] for k, v in PATH_MAP.items() if v[1] == prefix)
        vp = get_real_path(os.path.join(base, nfc(urllib.parse.unquote(path))))
        if os.path.isdir(vp):
            fs = sorted([f for f in os.listdir(vp) if f.lower().endswith(VIDEO_EXTS)])
            vp = os.path.join(vp, fs[0]) if fs else vp
        tp = os.path.join(DATA_DIR, f"seek_{tid}_{t}.jpg")
        if not os.path.exists(tp):
            subprocess.run([FFMPEG_PATH, "-y", "-ss", t, "-i", vp, "-vframes", "1", "-q:v", "5", "-vf", "scale=320:-1", tp], timeout=15)
        return send_file(tp, mimetype='image/jpeg') if os.path.exists(tp) else ("Not Found", 404)
    except: return "Not Found", 404

# --- [최적화 전용 고속 API 추가 (규칙 준수)] ---
# 기존 로직을 보존하며, 대용량 카테고리 로딩 성능 향상을 위해 에피소드 최소화 및 페이징 기능을 추가합니다.
CAT_MAP_V2 = { "영화": "movies", "외국TV": "foreigntv", "국내TV": "koreantv", "애니메이션": "animations_all", "방송중": "air" }

@app.route('/api/fast_list')
def get_fast_list():
    path_arg = request.args.get('path', 'movies')
    limit = int(request.args.get('limit', 100))
    offset = int(request.args.get('offset', 0))

    # 카테고리와 필터 키워드 분리
    target_cat = "movies"
    filter_q = ""
    for kor, eng in CAT_MAP_V2.items():
        if path_arg.startswith(kor):
            target_cat = eng
            filter_q = path_arg[len(kor):].strip("/")
            break
        elif path_arg.startswith(eng):
            target_cat = eng
            filter_q = path_arg[len(eng):].strip("/")
            break

    conn = get_db(); cursor = conn.cursor()
    params = [target_cat]

    # [수정] 필터링을 '포함' 검색(%keyword%)으로 변경하여 누락 방지
    filter_clause = ""
    if filter_q:
        filter_clause = " AND (s.path LIKE ? OR s.name LIKE ?)"
        params.extend([f'%{filter_q}%', f'%{filter_q}%'])

    # [수정] 영화는 중복 제거(포스터 기준), TV/애니 등은 폴더별 노출(기존 규칙 준수)
    group_by = "GROUP BY COALESCE(s.posterPath, s.name)" if target_cat == "movies" else "GROUP BY s.path"

    query = f"SELECT * FROM series s WHERE category = ? {filter_clause} {group_by} ORDER BY name ASC LIMIT ? OFFSET ?"
    params.extend([limit, offset])

    cursor.execute(query, params); rows = []
    for row in cursor.fetchall():
        item = dict(row)
        if item.get('genreIds'): item['genreIds'] = json.loads(item['genreIds'])
        cursor.execute("SELECT * FROM episodes WHERE series_path = ? LIMIT 1", (item['path'],))
        ep = cursor.fetchone()
        item['movies'] = [dict(ep)] if ep else []
        rows.append(item)
    conn.close()
    return jsonify(rows)

@app.route('/api/fast_detail')
def get_fast_detail():
    path = request.args.get('path', '')
    for kor, eng in CAT_MAP_V2.items():
        if path.startswith(kor):
            path = path.replace(kor, eng, 1).strip("/")
            break
    conn = get_db(); cursor = conn.cursor()
    cursor.execute("SELECT * FROM series WHERE path = ?", (path,))
    row = cursor.fetchone()
    if not row: conn.close(); return jsonify(None)
    series = dict(row); items = []
    if series.get('genreIds'): series['genreIds'] = json.loads(series['genreIds'])
    # 상세 화면용 에피소드 합산 로직
    sql = "SELECT e.* FROM episodes e JOIN series s ON e.series_path = s.path WHERE "
    sql += "s.posterPath = ?" if series.get('posterPath') else "s.name = ?"
    cursor.execute(sql, (series.get('posterPath') or series['name'],))
    seen = set()
    for r in cursor.fetchall():
        if r['videoUrl'] not in seen: items.append(dict(r)); seen.add(r['videoUrl'])
    series['movies'] = sorted(items, key=lambda x: natural_sort_key(x['title']))
    conn.close()
    return jsonify(series)

if __name__ == '__main__':
    log(f"📺 NAS Server 시작 (SQLite 기반 중복 및 구조 최적화 버전)")
    init_db()
    migrate_json_to_sqlite()
    load_tmdb_memory_cache()
    build_home_recommend()
    threading.Thread(target=perform_full_scan, daemon=True).start()
    app.run(host='0.0.0.0', port=5000, threaded=True)
