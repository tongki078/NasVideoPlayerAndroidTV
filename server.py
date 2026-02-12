import os, subprocess, hashlib, urllib.parse, unicodedata, threading, time, json, re, sys, traceback, shutil, requests, random, mimetypes
from flask import Flask, jsonify, send_from_directory, request, Response, redirect, send_file
from flask_cors import CORS
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime

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
TMDB_CACHE_DIR = "/volume2/video/tmdb_cache"
HLS_ROOT = "/dev/shm/videoplayer_hls"
CACHE_VERSION = "9.7" # 규칙: 버전 유지로 기존 데이터 보존

# TMDB API KEY
TMDB_API_KEY = "eyJhbGciOiJIUzI1NiJ9.eyJhdWQiOiI3OGNiYWQ0ZjQ3NzcwYjYyYmZkMTcwNTA2NDIwZDQyYyIsIm5iZiI6MTY1MzY3NTU4MC45MTUsInN1YiI6IjYyOTExNjNjMTI0MjVjMDA1MjI0ZGQzNCIsInNjb3BlcyI6WyJhcGlfcmVhZCJdLCJ2ZXJzaW9uIjoxfQ.3YU0WuIx_WDo6nTRKehRtn4N5I4uCgjI1tlpkqfsUhk".strip()
TMDB_BASE_URL = "https://api.themoviedb.org/3"

os.makedirs(DATA_DIR, exist_ok=True)
os.makedirs(TMDB_CACHE_DIR, exist_ok=True)
if os.path.exists(HLS_ROOT): shutil.rmtree(HLS_ROOT, ignore_errors=True)
os.makedirs(HLS_ROOT, exist_ok=True)

PARENT_VIDEO_DIR = "/volume2/video/GDS3/GDRIVE/VIDEO"
FOREIGN_TV_DIR = os.path.join(PARENT_VIDEO_DIR, "외국TV")
KOREAN_TV_DIR = os.path.join(PARENT_VIDEO_DIR, "국내TV")
MOVIES_ROOT_DIR = os.path.join(PARENT_VIDEO_DIR, "영화")
ANI_DIR = os.path.join(PARENT_VIDEO_DIR, "일본 애니메이션")
AIR_DIR = os.path.join(PARENT_VIDEO_DIR, "방송중")

PATH_MAP = {
    "외국TV": (FOREIGN_TV_DIR, "ftv"),
    "국내TV": (KOREAN_TV_DIR, "ktv"),
    "영화": (MOVIES_ROOT_DIR, "movie"),
    "애니메이션": (ANI_DIR, "anim_all"),
    "일본 애니메이션": (ANI_DIR, "anim_all"),
    "방송중": (AIR_DIR, "air")
}

EXCLUDE_FOLDERS = ["성인", "19금", "Adult", "@eaDir", "#recycle"]
VIDEO_EXTS = ('.mp4', '.mkv', '.avi', '.wmv', '.flv', '.ts', '.tp', '.m4v', '.m2ts', '.mov')
FFMPEG_PATH = "ffmpeg"
for p in ["/usr/local/bin/ffmpeg", "/var/packages/ffmpeg/target/bin/ffmpeg", "/usr/bin/ffmpeg"]:
    if os.path.exists(p): FFMPEG_PATH = p; break

GLOBAL_CACHE = {
    "air": [], "movies": [], "foreigntv": [], "koreantv": [],
    "animations_all": [], "search_index": [], "home_recommend": [], "version": CACHE_VERSION
}

def log(msg):
    timestamp = datetime.now().strftime("%H:%M:%S")
    print(f"[{timestamp}] {msg}", flush=True)

def nfc(text): return unicodedata.normalize('NFC', text) if text else ""
def nfd(text): return unicodedata.normalize('NFD', text) if text else ""

# --- [정규식 및 유틸리티] ---
REGEX_EXT = re.compile(r'\.[a-zA-Z0-9]{2,4}$')
REGEX_YEAR = re.compile(r'\((19|20)\d{2}\)|(?<!\d)(19|20)\d{2}(?!\d)')
REGEX_EP_MARKER = re.compile(r'(?i)(?:^|[.\s_]|(?<=[가-힣]))(?:S\d+E\d+|S\d+|E\d+|\d+\s*(?:화|회|기)|Season\s*\d+|Part\s*\d+).*')
REGEX_FORBIDDEN_TITLE = re.compile(r'(?i)^\s*(Season\s*\d+|Part\s*\d+|EP\s*\d+|\d+화|\d+회|\d+기|시즌\s*\d+|S\d+|E\d+)\s*$', re.I)

def clean_title_complex(title):
    if not title: return "", None
    title = nfc(title)
    cleaned = REGEX_EXT.sub('', title)
    year_match = REGEX_YEAR.search(cleaned)
    year = year_match.group().replace('(', '').replace(')', '') if year_match else None
    cleaned = REGEX_YEAR.sub(' ', cleaned)
    cleaned = REGEX_EP_MARKER.sub(' ', cleaned)
    cleaned = re.sub(r'\[.*?\]|\(.*?\)', ' ', cleaned)
    cleaned = re.sub(r'[._\-!?【】『』「」"\'#@*※:]', ' ', cleaned)
    cleaned = re.sub(r'\s+', ' ', cleaned).strip()
    return cleaned, year

def get_real_path(path):
    if not path: return path
    if os.path.exists(path): return path
    p_nfc, p_nfd = nfc(path), nfd(path)
    if os.path.exists(p_nfc): return p_nfc
    if os.path.exists(p_nfd): return p_nfd
    return path

def resolve_nas_path(app_path):
    if not app_path: return None, None
    app_path = nfc(urllib.parse.unquote(app_path))
    parts = app_path.split('/')
    prefix = parts[0]
    if prefix in PATH_MAP:
        base_dir, type_code = PATH_MAP[prefix]
        rel_path = "/".join(parts[1:])
        resolved = get_real_path(os.path.join(base_dir, rel_path))
        return resolved, type_code
    return None, None

def get_tmdb_info_server(title, ignore_cache=False, log_path=None, search_override=None):
    if not title: return {"failed": True}
    title_pure = nfc(title).split('/')[-1]
    cp = os.path.join(TMDB_CACHE_DIR, f"{hashlib.md5(title_pure.encode()).hexdigest()}.json")
    if not ignore_cache and os.path.exists(cp):
        try:
            with open(cp, 'r', encoding='utf-8') as f: return json.load(f)
        except: pass

    # 검색 텍스트 결정: override 우선
    query_text = search_override if search_override else title_pure
    ct, year = clean_title_complex(query_text)

    if not search_override and (REGEX_FORBIDDEN_TITLE.match(ct) or ct.lower() in ["season", "series", "video", "episode"]):
        info = {"failed": True, "forbidden": True}
        with open(cp, 'w', encoding='utf-8') as f: json.dump(info, f, ensure_ascii=False)
        return info

    path_info = f" (경로: {log_path})" if log_path else ""
    log(f"  [TMDB-SEARCH] '{query_text}' -> '{ct}' ({year}){path_info}")

    params = {"query": ct, "language": "ko-KR", "include_adult": "false", "region": "KR"}
    if year: params["year"] = year
    headers = {"Authorization": f"Bearer {TMDB_API_KEY}"} if TMDB_API_KEY.startswith("eyJ") else {}
    if not headers: params["api_key"] = TMDB_API_KEY

    try:
        resp = requests.get(f"{TMDB_BASE_URL}/search/multi", params=params, headers=headers, timeout=5).json()
        results = [r for r in resp.get('results', []) if r.get('media_type') in ['movie', 'tv']]
        if results:
            best = results[0]
            m_type, t_id = best.get('media_type'), best.get('id')
            d_resp = requests.get(f"{TMDB_BASE_URL}/{m_type}/{t_id}?language=ko-KR&append_to_response=content_ratings", params=params, headers=headers, timeout=5).json()
            year_val = (d_resp.get('release_date') or d_resp.get('first_air_date') or "").split('-')[0]
            rating = None
            if 'content_ratings' in d_resp:
                results_list = d_resp['content_ratings'].get('results', [])
                kr = next((r['rating'] for r in results_list if r.get('iso_3166_1') == 'KR'), None)
                if kr: rating = f"{kr}+" if kr.isdigit() else kr
            info = {"genreIds": [g['id'] for g in d_resp.get('genres', [])], "posterPath": d_resp.get('poster_path'), "year": year_val, "overview": d_resp.get('overview'), "rating": rating, "seasonCount": d_resp.get('number_of_seasons'), "failed": False}
            with open(cp, 'w', encoding='utf-8') as f: json.dump(info, f, ensure_ascii=False)
            return info
    except: pass
    with open(cp, 'w', encoding='utf-8') as f: json.dump({"failed": True}, f, ensure_ascii=False)
    return {"failed": True}

def attach_tmdb_info(cat):
    name = cat.get('name')
    if name:
        info = get_tmdb_info_server(name, log_path=cat.get('path'))
        cat.update(info)
    return cat

def fetch_metadata_async(force_all=False):
    log("🚀 [METADATA] 백그라운드 매칭 시작")
    tasks = []
    # 데이터 수집 (어떤 카테고리의 어떤 경로인지 정보를 유지)
    for k in ["animations_all", "foreigntv", "koreantv", "movies", "air"]:
        for cat in GLOBAL_CACHE.get(k, []):
            if force_all or (not cat.get('posterPath') and not cat.get('failed')):
                tasks.append((cat, k))

    total = len(tasks)
    log(f"  📋 총 {total}개의 메타데이터 업데이트 필요")
    count = 0
    for cat, cat_key in tasks:
        # 검색 시 경로 정보를 넘겨서 로그에 찍히게 함
        info = get_tmdb_info_server(cat['name'], ignore_cache=force_all, log_path=f"{cat_key}/{cat.get('path')}")
        cat.update(info)
        count += 1
        if count % 10 == 0:
            log(f"  ⏳ 매칭 중... ({count}/{total})")
            save_cache()
        time.sleep(0.1)

    build_home_recommend(); save_cache()
    log("🏁 [METADATA] 모든 작업 완료")

def scan_recursive(bp, prefix, rb=None):
    cats = []
    exts = VIDEO_EXTS
    p, rel_base = get_real_path(bp), get_real_path(rb) if rb else get_real_path(bp)

    log(f"    [SCAN] 경로 진입: {p}")
    if not os.path.exists(p):
        log(f"    ⚠️ 경로 없음: {p}")
        return cats

    all_f = []
    file_count = 0

    def fast_walk_iterative(target_path):
        nonlocal file_count
        stack = [target_path]
        while stack:
            current = stack.pop()
            try:
                with os.scandir(current) as it:
                    for entry in it:
                        if entry.is_dir():
                            if not any(ex in entry.name for ex in EXCLUDE_FOLDERS) and not entry.name.startswith('.'):
                                stack.append(entry.path)
                        elif entry.is_file():
                            if entry.name.lower().endswith(exts):
                                all_f.append(entry.path)
                                file_count += 1
                                if file_count % 1000 == 0:
                                    log(f"    >>> {file_count}개 파일 발견 중... ({entry.name[:20]})")
            except: pass

    log(f"    🔎 고속 탐색 시작 (os.scandir 반복문)...")
    fast_walk_iterative(p)

    log(f"    📦 탐색 완료! 총 {file_count}개 파일 분석 및 그룹화 시작...")
    all_f.sort()
    curr, movies = "", []
    for fp in all_f:
        dp = os.path.dirname(fp)
        if dp != curr:
            if movies:
                rel_path = nfc(os.path.relpath(curr, rel_base))

                # [핵심 수정] 가짜 제목 교정 로직 삽입 (규칙: 기존 로직 보존 및 주석 유지)
                name = nfc(os.path.basename(curr))
                if REGEX_FORBIDDEN_TITLE.match(name) or name.lower() in ["season", "series", "episode"]:
                    parent_dir = os.path.dirname(curr)
                    parent_name = nfc(os.path.basename(parent_dir))
                    if parent_name and not REGEX_FORBIDDEN_TITLE.match(parent_name):
                        name = parent_name # 부모 폴더명을 진짜 제목으로 채택

                cats.append({"name": name, "movies": movies, "path": rel_path})
            curr, movies = dp, []
        movies.append(get_movie_info(fp, rel_base, prefix))
    if movies:
        rel_path = nfc(os.path.relpath(curr, rel_base))
        name = nfc(os.path.basename(curr))
        if REGEX_FORBIDDEN_TITLE.match(name):
            parent_name = nfc(os.path.basename(os.path.dirname(curr)))
            if parent_name: name = parent_name
        cats.append({"name": name, "movies": movies, "path": rel_path})

    log(f"    ✅ 그룹화 완료: {len(cats)}개 카테고리 생성")
    return cats

def get_movie_info(fp, base, prefix):
    rel = nfc(os.path.relpath(fp, base))
    tid = hashlib.md5(f"{prefix}_{rel}".encode()).hexdigest() + ".jpg"
    return {"id": tid, "title": os.path.basename(fp), "videoUrl": f"/video_serve?type={prefix}&path={urllib.parse.quote(rel)}", "thumbnailUrl": f"/thumb_serve?type={prefix}&id={tid}&path={urllib.parse.quote(rel)}"}

def build_home_recommend():
    log("🏠 [HOME] 고속 추천 목록 빌드 중...")
    def prep(items, prefix):
        res = []
        for it in items:
            c = it.copy(); c['movies'] = []
            if c.get('path') and not c['path'].startswith(prefix): c['path'] = f"{prefix}/{c['path']}"
            res.append(c)
        return res
    m, a, k, f = prep(GLOBAL_CACHE.get("movies", []), "영화"), prep(GLOBAL_CACHE.get("animations_all", []), "애니메이션"), prep(GLOBAL_CACHE.get("koreantv", []), "국내TV"), prep(GLOBAL_CACHE.get("foreigntv", []), "외국TV")
    all_p = list(m + a + k + f); random.shuffle(all_p)
    GLOBAL_CACHE["home_recommend"] = [
        {"title": "지금 가장 핫한 인기작", "items": all_p[:20]},
        {"title": "방금 올라온 최신 영화", "items": m[:20]},
        {"title": "지금 인기 있는 시리즈", "items": (k + f)[:20]},
        {"title": "추천 애니메이션", "items": a[:20]}
    ]

def perform_full_scan(reason="필요"):
    log(f"\n🔄 사유: {reason} -> 백그라운드 탐색 시작 (우선순위 순)")
    # 요청하신 순서: 애니메이션 -> 외국TV -> 국내TV -> 영화 -> 방송중
    t = [
        ("애니메이션", ANI_DIR, "anim_all", "animations_all"),
        ("외국TV", FOREIGN_TV_DIR, "ftv", "foreigntv"),
        ("국내TV", KOREAN_TV_DIR, "ktv", "koreantv"),
        ("영화", MOVIES_ROOT_DIR, "movie", "movies"),
        ("방송중", AIR_DIR, "air", "air")
    ]
    for label, path, prefix, cache_key in t:
        if GLOBAL_CACHE.get(cache_key) and len(GLOBAL_CACHE[cache_key]) > 0:
             log(f"  ⏭️ [{label}] 이미 로드된 데이터가 있음. 건너뜁니다.")
             continue

        log(f"  📂 [{label}] 탐색 시작")
        try:
            results = scan_recursive(path, prefix)
            GLOBAL_CACHE[cache_key] = results
            log(f"  ✅ [{label}] 완료! 즉시 반영 중")
            build_home_recommend(); save_cache() # 카테고리 끝날 때마다 즉시 노출
        except Exception as e:
            log(f"  ❌ [{label}] 오류: {e}")

    log("💾 모든 탐색 완료. 메타데이터 업데이트를 시작합니다.")
    threading.Thread(target=fetch_metadata_async, daemon=True).start()

def load_cache():
    if not os.path.exists(CACHE_FILE):
        log(f"⚠️ [WARNING] 캐시 파일을 찾을 수 없습니다: {CACHE_FILE}")
        return False

    try:
        with open(CACHE_FILE, 'r', encoding='utf-8') as f:
            content = f.read()
            if not content.strip(): return False

            # [깨진 JSON 심폐소생 로직]
            try:
                d = json.loads(content)
            except json.JSONDecodeError as je:
                log(f"❌ [ERROR] 캐시 파일이 문법적으로 깨져있습니다: {je}")
                log("🛠️ [HEAL] 캐시 파일 자동 복구(Heal)를 시도합니다...")
                healed = False
                # 끊긴 지점에 따라 닫는 태그들을 조합하여 시도
                for suffix in ["}", "}]", "]}", "}}]"]:
                    try:
                        d = json.loads(content + suffix)
                        log(f"✅ [HEAL] '{suffix}'를 추가하여 데이터 복구에 성공했습니다!")
                        healed = True
                        break
                    except: continue

                if not healed:
                    shutil.copy(CACHE_FILE, CACHE_FILE + ".bak")
                    log(f"💾 복구 실패. 깨진 캐시를 백업했습니다: {CACHE_FILE}.bak")
                    return False

            file_version = d.get("version", "알 수 없음")
            log(f"🔍 [CACHE] 캐시 파일 로드 시도 (버전: {file_version})")

            # [강제 로드 로직] 규칙: 버전이 달라도 데이터를 로드하여 스캔 방지
            GLOBAL_CACHE.update(d)
            GLOBAL_CACHE["version"] = CACHE_VERSION
            log(f"📂 [CACHE] 기존 데이터 강제 로드 성공 (v{file_version} -> v{CACHE_VERSION})")

            # [즉시 교정] 로드된 캐시의 제목을 즉시 교정하여 앱에 반영
            log("🛠️ [MIGRATION] 기존 캐시 제목 교정 작업 시작...")
            for k in ["animations_all", "foreigntv", "koreantv", "movies", "air"]:
                items_list = GLOBAL_CACHE.get(k, [])
                if not items_list: continue

                for item in items_list:
                    current_name = item.get('name', '')
                    if REGEX_FORBIDDEN_TITLE.match(current_name) or current_name.lower() in ["season", "series"]:
                        path_val = item.get('path', '')
                        path_parts = path_val.split('/')
                        if len(path_parts) >= 2:
                            item['name'] = nfc(path_parts[-2])
            log("✅ [MIGRATION] 제목 교정 완료!")
            return True
    except Exception as e:
        log(f"❌ [ERROR] 캐시 로드 중 치명적 오류 발생: {e}")
    return False

def save_cache():
    try:
        # 안전한 저장을 위해 임시 파일에 쓰고 이름을 바꾸는 방식 채택 (파일 깨짐 방지)
        temp_file = CACHE_FILE + ".tmp"
        with open(temp_file, 'w', encoding='utf-8') as f:
            json.dump(GLOBAL_CACHE, f, ensure_ascii=False)
        os.replace(temp_file, CACHE_FILE)
    except: pass

def init_server():
    log(f"📺 NAS Server v{CACHE_VERSION} 즉시 시작")
    has_cache = load_cache()
    if has_cache: build_home_recommend()

    # 서버 응답을 위해 탐색은 무조건 백그라운드 스레드로 실행
    threading.Thread(target=perform_full_scan, args=("시스템 시작",), daemon=True).start()

init_server()

# --- [API 엔드포인트] ---
@app.route('/home')
def get_home(): return jsonify(GLOBAL_CACHE.get("home_recommend", []))
@app.route('/scan')
def manual_scan(): threading.Thread(target=perform_full_scan, args=("사용자 요청",)).start(); return "스캔 시작"
@app.route('/refresh_metadata')
def refresh_metadata(): threading.Thread(target=fetch_metadata_async, kwargs={"force_all": True}).start(); return "메타데이터 재매칭 시작"

@app.route('/debug_match')
def debug_match():
    q = request.args.get('q', '')
    s = request.args.get('search', '')
    if not q: return "Usage: /debug_match?q=대상폴더명&search=검색키워드"
    info = get_tmdb_info_server(q, ignore_cache=True, search_override=s)
    target_q = nfc(q)
    updated_count = 0
    for k in ["animations_all", "foreigntv", "koreantv", "movies", "air"]:
        for cat in GLOBAL_CACHE.get(k, []):
            if nfc(cat['name']) == target_q:
                cat.update(info)
                updated_count += 1
    if updated_count > 0:
        save_cache()
        build_home_recommend()
        return jsonify({"status": "success", "folder": q, "query_used": s if s else q, "data": info})
    else:
        return jsonify({"status": "partial_success", "message": "캐시는 생성되었으나 현재 목록에서 폴더명을 찾을 수 없습니다.", "data": info})

def process_data(data, lite=False, is_search=False):
    # 페이징 지원
    limit = request.args.get('limit', type=int)
    offset = request.args.get('offset', type=int, default=0)

    result = data
    if request.args.get('random') == 'true':
        result = list(data)
        rng = random.Random(datetime.now().strftime("%Y%m%d"))
        rng.shuffle(result)

    if offset: result = result[offset:]
    if limit: result = result[:limit]

    if lite:
        return [{"name": c.get('name',''), "path": c.get('path',''), "movies": c.get('movies', []) if is_search else [], "genreIds": c.get('genreIds', []), "posterPath": c.get('posterPath'), "year": c.get('year'), "overview": c.get('overview'), "rating": c.get('rating'), "seasonCount": c.get('seasonCount'), "failed": c.get('failed', False)} for c in result]
    return result

def filter_by_path(pool, keyword):
    target = nfc(keyword).replace(" ", "").lower()
    return [c for c in pool if target in nfc(c.get('path', '')).replace(" ", "").lower()]

@app.route('/air')
@app.route('/air_animations')
@app.route('/air_dramas')
def get_air():
    pool = GLOBAL_CACHE.get("air", [])
    if "animations" in request.path: pool = filter_by_path(pool, "애니메이션")
    elif "dramas" in request.path: pool = filter_by_path(pool, "드라마")
    return jsonify(process_data(pool, request.args.get('lite') == 'true'))

@app.route('/animations_all')
@app.route('/anim_raftel')
@app.route('/anim_series')
def get_animations_all(): return jsonify(process_data(GLOBAL_CACHE.get("animations_all", []), request.args.get('lite') == 'true'))

@app.route('/foreigntv')
@app.route('/ftv_us')
@app.route('/ftv_cn')
@app.route('/ftv_jp')
@app.route('/ftv_docu')
@app.route('/ftv_etc')
def get_foreigntv(): return jsonify(process_data(GLOBAL_CACHE.get("foreigntv", []), request.args.get('lite') == 'true'))

@app.route('/koreantv')
@app.route('/ktv_drama')
@app.route('/ktv_sitcom')
@app.route('/ktv_variety')
@app.route('/ktv_edu')
@app.route('/ktv_docu')
def get_koreantv(): return jsonify(process_data(GLOBAL_CACHE.get("koreantv", []), request.args.get('lite') == 'true'))

@app.route('/movies')
@app.route('/movies_latest')
@app.route('/movies_uhd')
@app.route('/movies_title')
def get_movies(): return jsonify(process_data(GLOBAL_CACHE.get("movies", []), request.args.get('lite') == 'true'))

@app.route('/search')
def search_videos():
    q = request.args.get('q', '').lower()

    # 카테고리별로 prefix 매핑
    mapping = [
        ("영화", GLOBAL_CACHE.get('movies', [])),
        ("애니메이션", GLOBAL_CACHE.get('animations_all', [])),
        ("외국TV", GLOBAL_CACHE.get('foreigntv', [])),
        ("국내TV", GLOBAL_CACHE.get('koreantv', [])),
        ("방송중", GLOBAL_CACHE.get('air', []))
    ]

    res = []
    for prefix, pool in mapping:
        for cat in pool:
            if q in cat['name'].lower():
                nc = cat.copy()
                if nc.get('path') and not nc['path'].startswith(prefix):
                    nc['path'] = f"{prefix}/{nc['path']}"
                res.append(nc)
            else:
                fm = [m for m in cat.get('movies', []) if q in m['title'].lower()]
                if fm:
                    nc = cat.copy()
                    nc['movies'] = fm
                    if nc.get('path') and not nc['path'].startswith(prefix):
                        nc['path'] = f"{prefix}/{nc['path']}"
                    res.append(nc)
    return jsonify(process_data(res, lite=request.args.get('lite') == 'true', is_search=True))

@app.route('/list')
def get_list():
    path = request.args.get('path')
    if not path: return jsonify([])
    real_path, type_code = resolve_nas_path(path)
    if not real_path or not os.path.exists(real_path): return jsonify([])
    base_dir = PATH_MAP.get(path.split('/')[0], (None, None))[0]
    res, movies = [], []
    for entry in sorted(os.listdir(real_path)):
        fe = os.path.join(real_path, entry)
        if os.path.isdir(fe):
            if any(ex in entry for ex in EXCLUDE_FOLDERS): continue

            # [핵심 수정] 상세페이지 재생 버튼 활성화를 위한 하위 폴더 스캔
            sub_movies = []
            try:
                for f in sorted(os.listdir(fe)):
                    if f.lower().endswith(VIDEO_EXTS):
                        sub_movies.append(get_movie_info(os.path.join(fe, f), base_dir, type_code))
            except: pass

            name = nfc(entry)
            # 가짜 제목 교정: Season 1 등 금지된 제목이면 부모 이름을 제목으로 조합
            if REGEX_FORBIDDEN_TITLE.match(name):
                p_name = nfc(os.path.basename(real_path))
                if p_name: name = f"{p_name} ({name})"

            res.append(attach_tmdb_info({"name": name, "path": nfc(os.path.relpath(fe, base_dir)), "movies": sub_movies}))
        elif entry.lower().endswith(VIDEO_EXTS):
            movies.append(get_movie_info(fe, base_dir, type_code))

    if movies:
        res.append({"name": nfc(os.path.basename(real_path)), "path": nfc(os.path.relpath(real_path, base_dir)), "movies": movies})

    return jsonify(res)

@app.route('/video_serve')
def video_serve():
    path, prefix = request.args.get('path'), request.args.get('type')
    base = {"ftv": FOREIGN_TV_DIR, "ktv": KOREAN_TV_DIR, "air": AIR_DIR, "anim_all": ANI_DIR, "movie": MOVIES_ROOT_DIR}.get(prefix)
    fp = get_real_path(os.path.join(base, nfc(urllib.parse.unquote(path))))
    return send_file(fp, conditional=True)

@app.route('/thumb_serve')
def thumb_serve():
    path, prefix, tid = request.args.get('path'), request.args.get('type'), request.args.get('id')
    try:
        t_raw = float(request.args.get('t', '300'))
        t = int(round(t_raw / 10.0) * 10)
    except: t = 300
    base = {"ftv": FOREIGN_TV_DIR, "ktv": KOREAN_TV_DIR, "air": AIR_DIR, "anim_all": ANI_DIR, "movie": MOVIES_ROOT_DIR}.get(prefix)
    vp = get_real_path(os.path.join(base, nfc(urllib.parse.unquote(path))))
    if os.path.isdir(vp):
        fs = sorted([f for f in os.listdir(vp) if f.lower().endswith(VIDEO_EXTS)])
        if fs: vp = os.path.join(vp, fs[0])
    tp = os.path.join(DATA_DIR, f"seek_{t}_{tid}")
    if not os.path.exists(tp):
        try: subprocess.run([FFMPEG_PATH, "-y", "-ss", str(t), "-i", vp, "-vframes", "1", "-q:v", "5", "-vf", "scale=320:-1", tp], timeout=10)
        except: pass
    return send_file(tp, mimetype='image/jpeg') if os.path.exists(tp) else ("Not Found", 404)

if __name__ == '__main__':
    app.run(host='0.0.0.0', port=5000, threaded=True)
