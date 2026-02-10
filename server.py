import os, subprocess, hashlib, urllib.parse, unicodedata, threading, time, json, re, sys, traceback, shutil, requests, random, mimetypes
from flask import Flask, jsonify, send_from_directory, request, Response, redirect, send_file
from flask_cors import CORS
from concurrent.futures import ThreadPoolExecutor

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
CACHE_VERSION = "9.6"  # 속도 최적화 버전

# TMDB API KEY (Bearer 또는 API Key)
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
FFMPEG_PATH = "ffmpeg"
for p in ["/usr/local/bin/ffmpeg", "/var/packages/ffmpeg/target/bin/ffmpeg", "/usr/bin/ffmpeg"]:
    if os.path.exists(p): FFMPEG_PATH = p; break

GLOBAL_CACHE = {
    "air": [], "movies": [], "foreigntv": [], "koreantv": [],
    "animations_all": [], "search_index": [], "home_recommend": [], "version": CACHE_VERSION
}

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

def get_tmdb_info_server(title, ignore_cache=False):
    if not title: return {"failed": True}
    title_pure = nfc(title).split('/')[-1]
    cp = os.path.join(TMDB_CACHE_DIR, f"{hashlib.md5(title_pure.encode()).hexdigest()}.json")
    if not ignore_cache and os.path.exists(cp):
        try:
            with open(cp, 'r', encoding='utf-8') as f: return json.load(f)
        except: pass

    ct, year = clean_title_complex(title_pure)
    if REGEX_FORBIDDEN_TITLE.match(ct) or ct.lower() in ["season", "series", "video", "episode"]:
        info = {"failed": True, "forbidden": True}
        with open(cp, 'w', encoding='utf-8') as f: json.dump(info, f, ensure_ascii=False)
        return info

    print(f"  [TMDB-SEARCH] '{title_pure}' -> '{ct}' ({year})", flush=True)
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
        info = get_tmdb_info_server(name)
        cat.update(info)
    return cat

def fetch_metadata_async(force_all=False):
    print(f"🚀 [METADATA] 백그라운드 매칭 시작", flush=True)
    tasks = []
    for k in ["foreigntv", "koreantv", "air", "animations_all", "movies"]:
        for cat in GLOBAL_CACHE.get(k, []):
            if force_all or (not cat.get('posterPath') and not cat.get('failed')): tasks.append(cat)

    total = len(tasks)
    print(f"  📋 총 {total}개의 메타데이터 업데이트 필요", flush=True)
    count = 0
    for cat in tasks:
        info = get_tmdb_info_server(cat['name'], ignore_cache=force_all)
        cat.update(info)
        count += 1
        if count % 10 == 0:
            print(f"  ⏳ 매칭 중... ({count}/{total})", flush=True)
            save_cache() # 10개마다 중간 저장
        time.sleep(0.1)

    build_home_recommend(); save_cache()
    print(f"🏁 [METADATA] 모든 작업 완료", flush=True)

def scan_recursive(bp, prefix, rb=None):
    cats = []
    exts = ('.mp4', '.mkv', '.avi', '.wmv', '.flv', '.ts', '.tp', '.m4v', '.m2ts', '.mov')
    p, rel_base = get_real_path(bp), get_real_path(rb) if rb else get_real_path(bp)
    if not os.path.exists(p):
        print(f"    ⚠️ 경로 없음: {p}")
        return cats

    print(f"    🔎 파일 탐색 중...", flush=True)
    all_f = []
    file_count = 0
    last_log_time = time.time()

    for root, dirs, files in os.walk(p):
        dirs[:] = [d for d in dirs if not any(ex in d for ex in EXCLUDE_FOLDERS) and not d.startswith('.')]

        # 탐색 중인 폴더 로그 (5초마다 하나씩 출력하여 너무 많은 로그 방지)
        if time.time() - last_log_time > 5:
            print(f"    ... 탐색 중: {os.path.basename(root)}", flush=True)
            last_log_time = time.time()

        for f in files:
            if f.lower().endswith(exts):
                all_f.append(os.path.join(root, f))
                file_count += 1
                if file_count % 2000 == 0:
                    print(f"    ... {file_count}개 파일 발견", flush=True)

    print(f"    📦 총 {file_count}개 파일 분석 및 그룹화 시작...", flush=True)
    all_f.sort()
    curr, movies = "", []
    for fp in all_f:
        dp = os.path.dirname(fp)
        if dp != curr:
            if movies:
                rel_path = nfc(os.path.relpath(curr, rel_base))
                cats.append({"name": nfc(os.path.basename(curr)), "movies": movies, "path": rel_path})
            curr, movies = dp, []
        movies.append(get_movie_info(fp, rel_base, prefix))
    if movies:
        rel_path = nfc(os.path.relpath(curr, rel_base))
        cats.append({"name": nfc(os.path.basename(curr)), "movies": movies, "path": rel_path})
    return cats

def get_movie_info(fp, base, prefix):
    rel = nfc(os.path.relpath(fp, base))
    tid = hashlib.md5(f"{prefix}_{rel}".encode()).hexdigest() + ".jpg"
    return {"id": tid, "title": os.path.basename(fp), "videoUrl": f"/video_serve?type={prefix}&path={urllib.parse.quote(rel)}", "thumbnailUrl": f"/thumb_serve?type={prefix}&id={tid}&path={urllib.parse.quote(rel)}"}

def build_home_recommend():
    print("🏠 [HOME] 고속 추천 목록 사전 빌드 중...", flush=True)
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
    print(f"\n🔄 사유: {reason} -> 전체 파일 스캔 시작", flush=True)
    t = [
        ("방송중", AIR_DIR, "air", "air"),
        ("애니메이션", ANI_DIR, "anim_all", "animations_all"),
        ("영화", MOVIES_ROOT_DIR, "movie", "movies"),
        ("외국TV", FOREIGN_TV_DIR, "ftv", "foreigntv"),
        ("국내TV", KOREAN_TV_DIR, "ktv", "koreantv")
    ]
    for label, path, prefix, cache_key in t:
        print(f"  📂 [{label}] 스캔 시작: {path}", flush=True)
        try:
            results = scan_recursive(path, prefix)
            GLOBAL_CACHE[cache_key] = results
            print(f"  ✅ [{label}] 완료: {len(results)}개 카테고리 발견", flush=True)
        except Exception as e:
            print(f"  ❌ [{label}] 오류: {e}", flush=True)
            traceback.print_exc()

    build_home_recommend(); save_cache()
    print(f"💾 캐시 저장 완료. 메타데이터 조회를 시작합니다.", flush=True)
    threading.Thread(target=fetch_metadata_async, daemon=True).start()

def load_cache():
    if os.path.exists(CACHE_FILE):
        try:
            with open(CACHE_FILE, 'r', encoding='utf-8') as f:
                d = json.load(f)
                if d.get("version") == CACHE_VERSION: GLOBAL_CACHE.update(d); return True
        except: pass
    return False

def save_cache():
    try:
        with open(CACHE_FILE, 'w', encoding='utf-8') as f: json.dump(GLOBAL_CACHE, f, ensure_ascii=False)
    except: pass

def init_server():
    print(f"📺 NAS Server v{CACHE_VERSION} 시작", flush=True)
    if not load_cache(): perform_full_scan(reason="최초 실행")
    else:
        build_home_recommend()
        # 캐시가 있어도 누락된 메타데이터 조회를 위해 백그라운드 스레드 실행
        threading.Thread(target=fetch_metadata_async, daemon=True).start()

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
    if not q: return "Usage: /debug_match?q=폴더명"
    ct, year = clean_title_complex(q)
    params = {"query": ct, "language": "ko-KR", "include_adult": "false"}
    if year: params["year"] = year
    headers = {"Authorization": f"Bearer {TMDB_API_KEY}"} if TMDB_API_KEY.startswith("eyJ") else {}
    if not headers: params["api_key"] = TMDB_API_KEY
    search_data = {}
    try:
        resp = requests.get(f"{TMDB_BASE_URL}/search/multi", params=params, headers=headers, timeout=5)
        search_data = resp.json()
    except Exception as e: search_data = {"error": str(e)}
    final_info = get_tmdb_info_server(q, ignore_cache=True)
    return jsonify({"input_original": q, "step1_cleaned_title": ct, "step1_extracted_year": year, "step2_raw_tmdb_results": search_data.get('results', []), "step3_final_processed_info": final_info})

def process_data(data, lite=False):
    if lite: return [{"name": c.get('name',''), "path": c.get('path',''), "movies": [], "genreIds": c.get('genreIds', []), "posterPath": c.get('posterPath'), "year": c.get('year'), "overview": c.get('overview'), "rating": c.get('rating'), "seasonCount": c.get('seasonCount'), "failed": c.get('failed', False)} for c in data]
    return data

def filter_by_path(pool, keyword):
    target = nfc(keyword).replace(" ", "").lower()
    return [c for c in pool if target in nfc(c.get('path', '')).replace(" ", "").lower()]

# 방송중 카테고리 관련 라우터
@app.route('/air')
def get_air(): return jsonify(process_data(GLOBAL_CACHE.get("air", []), request.args.get('lite') == 'true'))
@app.route('/air_animations')
def get_air_animations():
    res = [c for c in GLOBAL_CACHE.get("air", []) if any(k in c.get('path', '') for k in ["라프텔", "애니"])]
    return jsonify(process_data(res, request.args.get('lite') == 'true'))
@app.route('/air_dramas')
def get_air_dramas():
    res = [c for c in GLOBAL_CACHE.get("air", []) if "드라마" in c.get('path', '')]
    return jsonify(process_data(res, request.args.get('lite') == 'true'))

# 애니 카테고리 관련 라우터
@app.route('/animations_all')
def get_animations_all(): return jsonify(process_data(GLOBAL_CACHE.get("animations_all", []), request.args.get('lite') == 'true'))
@app.route('/anim_raftel')
def get_anim_raftel(): return jsonify(process_data(filter_by_path(GLOBAL_CACHE.get("animations_all", []), "라프텔"), request.args.get('lite') == 'true'))
@app.route('/anim_series')
def get_anim_series(): return jsonify(process_data(filter_by_path(GLOBAL_CACHE.get("animations_all", []), "시리즈"), request.args.get('lite') == 'true'))

# 외국TV 카테고리 관련 라우터
@app.route('/foreigntv')
def get_foreigntv(): return jsonify(process_data(GLOBAL_CACHE.get("foreigntv", []), request.args.get('lite') == 'true'))
@app.route('/ftv_us')
def get_ftv_us(): return jsonify(process_data(filter_by_path(GLOBAL_CACHE.get("foreigntv", []), "미국 드라마"), request.args.get('lite') == 'true'))
@app.route('/ftv_cn')
def get_ftv_cn(): return jsonify(process_data(filter_by_path(GLOBAL_CACHE.get("foreigntv", []), "중국 드라마"), request.args.get('lite') == 'true'))
@app.route('/ftv_jp')
def get_ftv_jp(): return jsonify(process_data(filter_by_path(GLOBAL_CACHE.get("foreigntv", []), "일본 드라마"), request.args.get('lite') == 'true'))
@app.route('/ftv_docu')
def get_ftv_docu(): return jsonify(process_data(filter_by_path(GLOBAL_CACHE.get("foreigntv", []), "다큐"), request.args.get('lite') == 'true'))
@app.route('/ftv_etc')
def get_ftv_etc(): return jsonify(process_data(filter_by_path(GLOBAL_CACHE.get("foreigntv", []), "기타"), request.args.get('lite') == 'true'))

# 국내TV 카테고리 관련 라우터
@app.route('/koreantv')
def get_koreantv(): return jsonify(process_data(GLOBAL_CACHE.get("koreantv", []), request.args.get('lite') == 'true'))
@app.route('/ktv_drama')
def get_ktv_drama(): return jsonify(process_data(filter_by_path(GLOBAL_CACHE.get("koreantv", []), "드라마"), request.args.get('lite') == 'true'))
@app.route('/ktv_variety')
def get_ktv_variety(): return jsonify(process_data(filter_by_path(GLOBAL_CACHE.get("koreantv", []), "예능"), request.args.get('lite') == 'true'))
@app.route('/ktv_sitcom')
def get_ktv_sitcom(): return jsonify(process_data(filter_by_path(GLOBAL_CACHE.get("koreantv", []), "시트콤"), request.args.get('lite') == 'true'))
@app.route('/ktv_edu')
def get_ktv_edu(): return jsonify(process_data(filter_by_path(GLOBAL_CACHE.get("koreantv", []), "교양"), request.args.get('lite') == 'true'))
@app.route('/ktv_docu')
def get_ktv_docu(): return jsonify(process_data(filter_by_path(GLOBAL_CACHE.get("koreantv", []), "다큐"), request.args.get('lite') == 'true'))

# 영화 카테고리 관련 라우터
@app.route('/movies')
def get_movies(): return jsonify(process_data(GLOBAL_CACHE.get("movies", []), request.args.get('lite') == 'true'))
@app.route('/movies_latest')
def get_movies_latest(): return jsonify(process_data(filter_by_path(GLOBAL_CACHE.get("movies", []), "최신"), request.args.get('lite') == 'true'))
@app.route('/movies_uhd')
def get_movies_uhd(): return jsonify(process_data(filter_by_path(GLOBAL_CACHE.get("movies", []), "UHD"), request.args.get('lite') == 'true'))
@app.route('/movies_title')
def get_movies_title(): return jsonify(process_data(filter_by_path(GLOBAL_CACHE.get("movies", []), "제목"), request.args.get('lite') == 'true'))

@app.route('/search')
def search_videos():
    q = request.args.get('q', '').lower()
    pool = GLOBAL_CACHE['movies'] + GLOBAL_CACHE['animations_all'] + GLOBAL_CACHE['foreigntv'] + GLOBAL_CACHE['koreantv'] + GLOBAL_CACHE['air']
    res = []
    for cat in pool:
        if q in cat['name'].lower(): res.append(cat)
        else:
            fm = [m for m in cat.get('movies', []) if q in m['title'].lower()]
            if fm: nc = cat.copy(); nc['movies'] = fm; res.append(nc)
    return jsonify(process_data(res, request.args.get('lite') == 'true'))

@app.route('/list')
def get_list():
    path = request.args.get('path')
    if not path: return jsonify([])
    real_path, type_code = resolve_nas_path(path)
    if not real_path or not os.path.exists(real_path): return jsonify([])
    base_dir = PATH_MAP.get(path.split('/')[0], (None, None))[0]
    res, movies, exts = [], [], ('.mp4', '.mkv', '.avi', '.ts', '.tp')
    for entry in sorted(os.listdir(real_path)):
        fe = os.path.join(real_path, entry)
        if os.path.isdir(fe): res.append(attach_tmdb_info({"name": nfc(entry), "path": nfc(os.path.relpath(fe, base_dir)), "movies": []}))
        elif entry.lower().endswith(exts): movies.append(get_movie_info(fe, base_dir, type_code))
    if movies: res.append({"name": nfc(os.path.basename(real_path)), "path": nfc(os.path.relpath(real_path, base_dir)), "movies": movies})
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
        fs = sorted([f for f in os.listdir(vp) if f.lower().endswith(('.mp4', '.mkv', '.avi'))])
        if fs: vp = os.path.join(vp, fs[0])
    tp = os.path.join(DATA_DIR, f"seek_{t}_{tid}")
    if not os.path.exists(tp):
        try: subprocess.run([FFMPEG_PATH, "-y", "-ss", str(t), "-i", vp, "-vframes", "1", "-q:v", "5", "-vf", "scale=320:-1", tp], timeout=10)
        except: pass
    return send_file(tp, mimetype='image/jpeg') if os.path.exists(tp) else ("Not Found", 404)

if __name__ == '__main__':
    app.run(host='0.0.0.0', port=5000, threaded=True)
