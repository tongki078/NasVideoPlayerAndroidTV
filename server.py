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
CACHE_VERSION = "10.0" # SQLite 도입으로 버전 업

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
REGEX_FORBIDDEN_TITLE = re.compile(r'(?i)^\s*(Season\s*\d+|Part\s*\d+|EP\s*\d+|\d+화|\d+회|\d+기|시즌\s*\d+|S\d+|E\d+|Disk\s*\d+|Disc\s*\d+|CD\s*\d+|Specials?|Extras?|Bonus|미분류|기타|새\s*폴더|VIDEO|GDS3|GDRIVE|NAS|share)\s*$', re.I)

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

    # 1단계: 파일 시스템 뒤지기 (로그 보강)
    find_count = 0
    while stack:
        curr = stack.pop()
        try:
            with os.scandir(curr) as it:
                for entry in sorted(list(it), key=lambda e: natural_sort_key(e.name)):
                    if entry.is_dir():
                        if not any(ex in entry.name for ex in EXCLUDE_FOLDERS) and not entry.name.startswith('.'): stack.append(entry.path)
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
        full_series_path = f"{category}/{rel_path}"

        if full_series_path not in series_map:
            name = get_meaningful_name(dp)
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
            conn.commit() # 중간 커밋: 앱에서 즉시 볼 수 있게 함

    conn.commit()
    conn.close()
    log(f"  ✅ '{category}' 모든 DB 갱신 완료.")

def perform_full_scan(cache_keys=None):
    keys = cache_keys if cache_keys else [("애니메이션", "animations_all"), ("외국TV", "foreigntv"), ("국내TV", "koreantv"), ("영화", "movies"), ("방송중", "air")]
    log(f"🔄 [SCAN] NAS 전체 재스캔 시작: {keys}")
    for label, cache_key in keys:
        path, prefix = PATH_MAP[label]
        scan_recursive_to_db(path, prefix, cache_key)

    log("🧠 [SCAN] 추천 리스트 갱신 중...")
    build_home_recommend()
    log("🏁 [SCAN] 전체 스캔 작업 완료")
    # 스캔 후에도 혹시 누락된 매칭이 있다면 실행
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
        if force_all:
            cursor.execute('SELECT path, name FROM series')
        else:
            cursor.execute('SELECT path, name FROM series WHERE posterPath IS NULL AND failed = 0')

        tasks = cursor.fetchall()
        conn.close()

        total = len(tasks)
        if total == 0:
            log("🏁 [METADATA] 매칭할 대상이 없습니다.")
            IS_METADATA_RUNNING = False
            return

        log(f"📊 [METADATA] 총 {total}개의 항목을 TMDB와 매칭할 예정입니다.")

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

            if count % 10 == 0 or count == total:
                elapsed = time.time() - start_time
                speed = count / elapsed if elapsed > 0 else 0
                remaining = (total - count) / speed if speed > 0 else 0
                log(f"  ⏳ 진행중: {count}/{total} ({(count/total*100):.1f}%) - [성공: {success_count}, 실패: {fail_count}] [남은시간: {int(remaining//60)}분 {int(remaining%60)}초]")
                if not info.get('failed'): log(f"    ✅ 매칭성공: {name}")

            time.sleep(0.05)
        log(f"🏁 [METADATA] 모든 작업 완료 (최종 성공: {success_count}, 실패: {fail_count})")
        build_home_recommend()
    finally:
        IS_METADATA_RUNNING = False

def build_home_recommend():
    global HOME_RECOMMEND
    log("🏠 [HOME] 추천 리스트 구축 중...")
    try:
        conn = get_db()
        cursor = conn.cursor()

        # 포스터 유무와 상관없이 모든 데이터가 노출되도록 조건 완화
        # 1. 인기작 (랜덤)
        cursor.execute('SELECT * FROM series ORDER BY RANDOM() LIMIT 20')
        all_p = [dict(row) for row in cursor.fetchall()]
        # 2. 영화
        cursor.execute('SELECT * FROM series WHERE category = "movies" LIMIT 20')
        m = [dict(row) for row in cursor.fetchall()]
        # 3. 시리즈
        cursor.execute('SELECT * FROM series WHERE category IN ("koreantv", "foreigntv") LIMIT 20')
        kf = [dict(row) for row in cursor.fetchall()]

        # 각 시리즈에 첫 번째 에피소드(movies) 정보 추가 (앱 호환성)
        for section_items in [all_p, m, kf]:
            for series in section_items:
                if series.get('genreIds'): series['genreIds'] = json.loads(series['genreIds'])
                cursor.execute('SELECT * FROM episodes WHERE series_path = ? LIMIT 1', (series['path'],))
                ep = cursor.fetchone()
                series['movies'] = [dict(ep)] if ep else []

        conn.close()
        HOME_RECOMMEND = [
            {"title": "지금 가장 핫한 인기작", "items": all_p},
            {"title": "방금 올라온 최신 영화", "items": m},
            {"title": "지금 인기 있는 시리즈", "items": kf}
        ]
        log(f"🏠 [HOME] 추천 리스트 갱신 완료 (항목수: {len(all_p) + len(m) + len(kf)})")
    except Exception as e:
        log(f"❌ [HOME] 추천 리스트 구축 실패: {str(e)}")

# --- [API 엔드포인트] ---
@app.route('/home')
def get_home(): return jsonify(HOME_RECOMMEND)

def get_series_list_api(category, filter_keyword=None):
    conn = get_db()
    cursor = conn.cursor()
    query = 'SELECT * FROM series WHERE category = ?'
    params = [category]
    if filter_keyword:
        query += ' AND (path LIKE ? OR name LIKE ?)'
        params.extend([f'%{filter_keyword}%', f'%{filter_keyword}%'])
    cursor.execute(query, params)
    rows = [dict(r) for r in cursor.fetchall()]

    # 앱 호환성을 위해 각 항목에 에피소드 리스트(movies) 추가
    for item in rows:
        if item.get('genreIds'): item['genreIds'] = json.loads(item['genreIds'])
        cursor.execute('SELECT * FROM episodes WHERE series_path = ? LIMIT 1', (item['path'],))
        ep = cursor.fetchone()
        item['movies'] = [dict(ep)] if ep else []

    conn.close()
    return sorted(rows, key=lambda x: natural_sort_key(x['name']))

def get_series_list_filtered(category, filter_keyword=None):
    # 기존 process_api에서 사용하던 동의어 로직을 SQLite 쿼리로 재현
    synonyms = {
        "미국": ["미국", "미드", "us"],
        "중국": ["중국", "중드", "cn"],
        "일본": ["일본", "일드", "jp"],
        "기타": ["기타", "etc"],
        "다큐": ["다큐", "docu"],
        "드라마": ["드라마"],
        "시트콤": ["시트콤"],
        "예능": ["예능"],
        "교양": ["교양"],
        "uhd": ["uhd", "4k"],
        "latest": ["latest", "최신"],
        "title": ["title", "제목"]
    }

    conn = get_db()
    cursor = conn.cursor()
    query = 'SELECT * FROM series WHERE category = ?'
    params = [category]

    if filter_keyword:
        targets = synonyms.get(filter_keyword, [filter_keyword])
        filter_parts = []
        for t in targets:
            filter_parts.append("(path LIKE ? OR name LIKE ?)")
            params.extend([f'%{t}%', f'%{t}%'])
        query += " AND (" + " OR ".join(filter_parts) + ")"

    cursor.execute(query, params)
    rows = [dict(r) for r in cursor.fetchall()]

    for item in rows:
        if item.get('genreIds'): item['genreIds'] = json.loads(item['genreIds'])
        cursor.execute('SELECT * FROM episodes WHERE series_path = ? LIMIT 1', (item['path'],))
        ep = cursor.fetchone()
        item['movies'] = [dict(ep)] if ep else []

    conn.close()
    return sorted(rows, key=lambda x: natural_sort_key(x['name']))

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

@app.route('/api/series_detail')
def get_series_detail_api():
    path = request.args.get('path')
    if not path: return jsonify(None)
    conn = get_db()
    cursor = conn.cursor()
    cursor.execute('SELECT * FROM series WHERE path = ?', (path,))
    row = cursor.fetchone()
    if not row:
        conn.close()
        return jsonify(None)
    series = dict(row)
    if series.get('genreIds'): series['genreIds'] = json.loads(series['genreIds'])
    cursor.execute('SELECT * FROM episodes WHERE series_path = ?', (path,))
    series['movies'] = sorted([dict(r) for r in cursor.fetchall()], key=lambda x: natural_sort_key(x['title']))
    conn.close()
    return jsonify(series)

@app.route('/search')
def search_videos():
    q = request.args.get('q', '').lower()
    if not q: return jsonify([])
    conn = get_db()
    cursor = conn.cursor()
    cursor.execute('SELECT * FROM series WHERE name LIKE ? OR path LIKE ?', (f'%{q}%', f'%{q}%'))
    rows = [dict(r) for r in cursor.fetchall()]
    for item in rows:
        if item.get('genreIds'): item['genreIds'] = json.loads(item['genreIds'])
        cursor.execute('SELECT * FROM episodes WHERE series_path = ? LIMIT 1', (item['path'],))
        ep = cursor.fetchone()
        item['movies'] = [dict(ep)] if ep else []
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

if __name__ == '__main__':
    log(f"📺 NAS Server 시작 (SQLite 기반 API 최적화)")
    init_db()
    migrate_json_to_sqlite()
    load_tmdb_memory_cache()
    build_home_recommend()
    # 병렬 실행: 스캔과 매칭을 동시에 시작 (규칙 준수 및 로그 보강)
    threading.Thread(target=perform_full_scan, daemon=True).start()
    threading.Thread(target=fetch_metadata_async, daemon=True).start()
    app.run(host='0.0.0.0', port=5000, threaded=True)
