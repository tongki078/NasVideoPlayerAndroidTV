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
CACHE_VERSION = "10.1"

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

# --- [정규식 및 클리닝] ---
REGEX_EXT = re.compile(r'\.[a-zA-Z0-9]{2,4}$')
REGEX_YEAR = re.compile(r'\((19|20)\d{2}\)|(?<!\d)(19|20)\d{2}(?!\d)')
REGEX_EP_MARKER = re.compile(r'(?i)(?:^|[.\s_]|(?<=[가-힣]))(?:S\d+E\d+|S\d+|E\d+|\d+\s*(?:화|회|기)|Season\s*\d+|Part\s*\d+|Disk\s*\d+|Disc\s*\d+|CD\s*\d+).*')
REGEX_FORBIDDEN_TITLE = re.compile(r'(?i)^\s*(Season\s*\d+|Part\s*\d+|EP\s*\d+|\d+화|\d+회|\d+기|시즌\s*\d+|S\d+|E\d+|Disk\s*\d+|Disc\s*\d+|CD\s*\d+|Specials?|Extras?)\s*$', re.I)

def clean_title_complex(title):
    if not title: return "", None
    title = nfc(title)
    cleaned = REGEX_EXT.sub('', title)
    year_match = REGEX_YEAR.search(cleaned)
    year = year_match.group().replace('(', '').replace(')', '') if year_match else None
    cleaned = REGEX_YEAR.sub(' ', cleaned)
    cleaned = REGEX_EP_MARKER.sub(' ', cleaned)

    # 괄호 안의 내용이 제목일 수 있으므로 완전히 삭제하지 않고 내부 정보만 활용
    cleaned = re.sub(r'\[.*?\]', ' ', cleaned)
    cleaned = re.sub(r'\(.*?\)', ' ', cleaned)
    cleaned = re.sub(r'(?<!\d)\.|\.(?!\d)', ' ', cleaned)
    cleaned = re.sub(r'[\_\-!?【】『』「」"\'#@*※:]', ' ', cleaned)
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

def get_cached_tmdb_info(title):
    """실시간 네트워크 호출 없이 캐시된 TMDB 정보만 반환"""
    if not title: return {}
    title_pure = nfc(title).split('/')[-1]
    cp = os.path.join(TMDB_CACHE_DIR, f"{hashlib.md5(title_pure.encode()).hexdigest()}.json")
    if os.path.exists(cp):
        try:
            with open(cp, 'r', encoding='utf-8') as f:
                data = json.load(f)
                return data if not data.get('failed') else {}
        except: pass
    return {}

def get_tmdb_info_server(title, ignore_cache=False, log_path=None, search_override=None):
    if not title: return {"failed": True}
    title_pure = nfc(title).split('/')[-1]
    cp = os.path.join(TMDB_CACHE_DIR, f"{hashlib.md5(title_pure.encode()).hexdigest()}.json")
    if not ignore_cache and os.path.exists(cp):
        try:
            with open(cp, 'r', encoding='utf-8') as f: return json.load(f)
        except: pass

    query_text = search_override if search_override else title_pure
    ct, year = clean_title_complex(query_text)

    if not search_override and (REGEX_FORBIDDEN_TITLE.match(ct) or ct.lower() in ["season", "series", "video", "episode"]):
        info = {"failed": True, "forbidden": True}
        with open(cp, 'w', encoding='utf-8') as f: json.dump(info, f, ensure_ascii=False)
        return info

    params = {"query": ct, "language": "ko-KR", "include_adult": "true", "region": "KR"}
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

def fetch_metadata_async(force_all=False):
    log("🚀 [METADATA] 백그라운드 매칭 시작")
    tasks = []
    for k in ["animations_all", "foreigntv", "koreantv", "movies", "air"]:
        for cat in GLOBAL_CACHE.get(k, []):
            if force_all or (not cat.get('posterPath') and not cat.get('failed')):
                tasks.append((cat, k))
    total = len(tasks)
    count = 0
    for cat, cat_key in tasks:
        info = get_tmdb_info_server(cat['name'], ignore_cache=force_all, log_path=f"{cat_key}/{cat.get('path')}")
        cat.update(info)
        count += 1
        if count % 10 == 0:
            log(f"  ⏳ 매칭 중... ({count}/{total})")
            save_cache()
        time.sleep(0.1)
    build_home_recommend(); save_cache()
    log("🏁 [METADATA] 모든 작업 완료")

def scan_recursive(bp, prefix, rb=None, display_name=None):
    cats = []
    exts = VIDEO_EXTS
    p, rel_base = nfc(get_real_path(bp)), nfc(get_real_path(rb) if rb else get_real_path(bp))
    all_f = []
    stack = [p]
    while stack:
        current = stack.pop()
        try:
            with os.scandir(current) as it:
                for entry in it:
                    if entry.is_dir():
                        if not any(ex in entry.name for ex in EXCLUDE_FOLDERS) and not entry.name.startswith('.'):
                            stack.append(entry.path)
                    elif entry.is_file() and entry.name.lower().endswith(exts):
                        all_f.append(nfc(entry.path))
        except: pass
    all_f.sort()
    curr, movies = "", []
    for fp in all_f:
        dp = nfc(os.path.dirname(fp))
        if dp != curr:
            if movies:
                rel_path = nfc(os.path.relpath(curr, rel_base))
                if display_name: rel_path = display_name if rel_path == "." else f"{display_name}/{rel_path}"
                name = nfc(os.path.basename(curr))
                if REGEX_FORBIDDEN_TITLE.match(name) or name.lower() in ["season", "series", "episode"]:
                    parent_dir = os.path.dirname(curr)
                    parent_name = nfc(os.path.basename(parent_dir))
                    if parent_name and not REGEX_FORBIDDEN_TITLE.match(parent_name): name = parent_name
                cats.append({"name": name, "movies": movies, "path": rel_path})
            curr, movies = dp, []
        movies.append(get_movie_info(fp, rel_base, prefix))
    if movies:
        rel_path = nfc(os.path.relpath(curr, rel_base))
        if display_name: rel_path = display_name if rel_path == "." else f"{display_name}/{rel_path}"
        name = nfc(os.path.basename(curr))
        if REGEX_FORBIDDEN_TITLE.match(name):
            parent_name = nfc(os.path.basename(os.path.dirname(curr)))
            if parent_name: name = parent_name
        cats.append({"name": name, "movies": movies, "path": rel_path})
    return cats

def get_movie_info(fp, base, prefix):
    rel = nfc(os.path.relpath(nfc(fp), nfc(base)))
    tid = hashlib.md5(f"{prefix}_{rel}".encode()).hexdigest() + ".jpg"
    return {"id": tid, "title": os.path.basename(fp), "videoUrl": f"/video_serve?type={prefix}&path={urllib.parse.quote(rel)}", "thumbnailUrl": f"/thumb_serve?type={prefix}&id={tid}&path={urllib.parse.quote(rel)}"}

def build_home_recommend():
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
    log(f"🔄 사유: {reason} -> 백그라운드 탐색 시작")
    t = [("애니메이션", ANI_DIR, "anim_all", "animations_all"), ("외국TV", FOREIGN_TV_DIR, "ftv", "foreigntv"), ("국내TV", KOREAN_TV_DIR, "ktv", "koreantv"), ("영화", MOVIES_ROOT_DIR, "movie", "movies"), ("방송중", AIR_DIR, "air", "air")]
    for label, path, prefix, cache_key in t:
        try:
            results = scan_recursive(path, prefix, display_name=label)
            GLOBAL_CACHE[cache_key] = results
            build_home_recommend(); save_cache()
        except: pass
    threading.Thread(target=fetch_metadata_async, daemon=True).start()

def fix_cache_paths():
    log("🔧 [FIX] 캐시 경로 접두사 보정 시작")
    mapping = {"movies": "영화", "foreigntv": "외국TV", "koreantv": "국내TV", "animations_all": "애니메이션", "air": "방송중"}
    for k, p in mapping.items():
        items = GLOBAL_CACHE.get(k, [])
        if not items: continue
        p_nfc = nfc(p)
        for c in items:
            raw_path = c.get('path', '')
            if not raw_path: continue
            path = nfc(raw_path)
            if not path.startswith(p_nfc + "/"):
                if path == p_nfc: c['path'] = p_nfc
                elif path == ".": c['path'] = p_nfc
                else: c['path'] = f"{p_nfc}/{path}"
            else:
                c['path'] = path
    log("✅ [FIX] 캐시 경로 보정 완료")

def load_cache():
    if not os.path.exists(CACHE_FILE): return False
    try:
        with open(CACHE_FILE, 'r', encoding='utf-8') as f:
            d = json.load(f)
            if d.get("version") != CACHE_VERSION: return False
            GLOBAL_CACHE.update(d)
            return True
    except: return False

def save_cache():
    try:
        with open(CACHE_FILE, 'w', encoding='utf-8') as f:
            json.dump(GLOBAL_CACHE, f, ensure_ascii=False)
    except: pass

def init_server():
    log(f"📺 NAS Server v{CACHE_VERSION} 시작")
    if load_cache():
        fix_cache_paths()
        build_home_recommend()
    threading.Thread(target=perform_full_scan, args=("시스템 시작",), daemon=True).start()

init_server()

# --- [API 엔드포인트 및 필터링] ---
def process_data(data, lite=False):
    limit = request.args.get('limit', type=int)
    offset = request.args.get('offset', type=int, default=0)
    result = data
    if request.args.get('random') == 'true':
        result = list(data); random.shuffle(result)
    if offset: result = result[offset:]
    if limit: result = result[:limit]

    if lite:
        return [{"name": c.get('name',''), "path": c.get('path',''), "genreIds": c.get('genreIds', []), "posterPath": c.get('posterPath'), "year": c.get('year'), "overview": c.get('overview'), "rating": c.get('rating'), "seasonCount": c.get('seasonCount'), "failed": c.get('failed', False)} for c in result]
    return result

def filter_by_path(pool, keyword):
    synonyms = {"미국": ["미국", "미드", "us", "usa"], "중국": ["중국", "중드", "cn", "china"], "일본": ["일본", "일드", "jp", "japan"], "기타": ["기타", "etc"], "다큐": ["다큐", "docu"], "드라마": ["드라마", "drama"], "시트콤": ["시트콤", "sitcom"], "예능": ["예능", "variety"], "교양": ["교양", "edu"], "다큐멘터리": ["다큐", "docu", "다큐멘터리"]}
    targets = [nfc(t).lower() for t in synonyms.get(keyword, [keyword])]
    return [c for c in pool if any(t in nfc(c.get('path','')).lower() or t in nfc(c.get('name','')).lower() for t in targets)]

@app.route('/home')
def get_home(): return jsonify(GLOBAL_CACHE.get("home_recommend", []))

@app.route('/foreigntv')
def get_ftv(): return jsonify(process_data(GLOBAL_CACHE.get("foreigntv", []), request.args.get('lite') == 'true'))
@app.route('/ftv_us')
def get_ftv_us(): return jsonify(process_data(filter_by_path(GLOBAL_CACHE.get("foreigntv", []), "미국"), request.args.get('lite') == 'true'))
@app.route('/ftv_cn')
def get_ftv_cn(): return jsonify(process_data(filter_by_path(GLOBAL_CACHE.get("foreigntv", []), "중국"), request.args.get('lite') == 'true'))
@app.route('/ftv_jp')
def get_ftv_jp(): return jsonify(process_data(filter_by_path(GLOBAL_CACHE.get("foreigntv", []), "일본"), request.args.get('lite') == 'true'))
@app.route('/ftv_docu')
def get_ftv_docu(): return jsonify(process_data(filter_by_path(GLOBAL_CACHE.get("foreigntv", []), "다큐"), request.args.get('lite') == 'true'))
@app.route('/ftv_etc')
def get_ftv_etc(): return jsonify(process_data(filter_by_path(GLOBAL_CACHE.get("foreigntv", []), "기타"), request.args.get('lite') == 'true'))

@app.route('/koreantv')
def get_ktv(): return jsonify(process_data(GLOBAL_CACHE.get("koreantv", []), request.args.get('lite') == 'true'))
@app.route('/ktv_drama')
def get_ktv_drama(): return jsonify(process_data(filter_by_path(GLOBAL_CACHE.get("koreantv", []), "드라마"), request.args.get('lite') == 'true'))
@app.route('/ktv_sitcom')
def get_ktv_sitcom(): return jsonify(process_data(filter_by_path(GLOBAL_CACHE.get("koreantv", []), "시트콤"), request.args.get('lite') == 'true'))
@app.route('/ktv_variety')
def get_ktv_variety(): return jsonify(process_data(filter_by_path(GLOBAL_CACHE.get("koreantv", []), "예능"), request.args.get('lite') == 'true'))
@app.route('/ktv_edu')
def get_ktv_edu(): return jsonify(process_data(filter_by_path(GLOBAL_CACHE.get("koreantv", []), "교양"), request.args.get('lite') == 'true'))
@app.route('/ktv_docu')
def get_ktv_docu(): return jsonify(process_data(filter_by_path(GLOBAL_CACHE.get("koreantv", []), "다큐멘터리"), request.args.get('lite') == 'true'))

@app.route('/animations_all')
def get_anim(): return jsonify(process_data(GLOBAL_CACHE.get("animations_all", []), request.args.get('lite') == 'true'))
@app.route('/anim_raftel')
def get_anim_r(): return jsonify(process_data(filter_by_path(GLOBAL_CACHE.get("animations_all", []), "라프텔"), request.args.get('lite') == 'true'))
@app.route('/anim_series')
def get_anim_s(): return jsonify(process_data(filter_by_path(GLOBAL_CACHE.get("animations_all", []), "시리즈"), request.args.get('lite') == 'true'))

@app.route('/movies')
@app.route('/movies_all')
@app.route('/movies_latest')
@app.route('/movies_uhd')
@app.route('/movies_title')
def get_movies():
    pool = GLOBAL_CACHE.get("movies", [])
    if "uhd" in request.path: pool = [c for c in pool if "uhd" in (c.get('path') or "").lower() or "4k" in (c.get('path') or "").lower()]
    elif "title" in request.path: pool = sorted(pool, key=lambda x: x.get('name', ''))
    return jsonify(process_data(pool, request.args.get('lite') == 'true'))

@app.route('/search')
def search_videos():
    q = request.args.get('q', '').lower()
    lite_req = request.args.get('lite') == 'true'
    res = []
    for k in ["movies", "animations_all", "foreigntv", "koreantv", "air"]:
        for cat in GLOBAL_CACHE.get(k, []):
            if q in cat['name'].lower(): res.append(cat.copy())
    return jsonify(process_data(res, lite=lite_req))

@app.route('/list')
def get_list():
    path = request.args.get('path')
    if not path: return jsonify([])
    log(f"🔎 [LIST] 경로 요청: {path}")
    real_path, type_code = resolve_nas_path(path)
    if not real_path or not os.path.exists(real_path):
        log(f"❌ [LIST] 경로 해석 실패: {path}")
        return jsonify([])
    cat_prefix = nfc(path.split('/')[0])
    base_dir = PATH_MAP.get(cat_prefix, (None, None))[0]
    res, movies = [], []
    try:
        for entry in sorted(os.listdir(real_path)):
            fe = os.path.join(real_path, entry)
            if os.path.isdir(fe):
                if any(ex in entry for ex in EXCLUDE_FOLDERS): continue
                name = nfc(entry)
                rel_path = nfc(os.path.relpath(fe, base_dir))
                # 실시간 TMDB 호출 대신 캐시된 정보만 사용 (응답 속도 개선)
                item = {"name": name, "path": f"{cat_prefix}/{rel_path}"}
                item.update(get_cached_tmdb_info(name))
                res.append(item)
            elif entry.lower().endswith(VIDEO_EXTS):
                movies.append(get_movie_info(fe, base_dir, type_code))
    except: pass
    if movies:
        rel_path = nfc(os.path.relpath(real_path, base_dir))
        info = {"name": nfc(os.path.basename(real_path)), "path": f"{cat_prefix}/{rel_path}", "movies": movies}
        info.update(get_cached_tmdb_info(info['name']))
        res.append(info)
    return jsonify(res)

@app.route('/video_serve')
def video_serve():
    path, prefix = request.args.get('path'), request.args.get('type')
    base = {"ftv": FOREIGN_TV_DIR, "ktv": KOREAN_TV_DIR, "air": AIR_DIR, "anim_all": ANI_DIR, "movie": MOVIES_ROOT_DIR}.get(prefix)
    return send_file(get_real_path(os.path.join(base, nfc(urllib.parse.unquote(path)))), conditional=True)

@app.route('/thumb_serve')
def thumb_serve():
    path, prefix, tid = request.args.get('path'), request.args.get('type'), request.args.get('id')
    base = {"ftv": FOREIGN_TV_DIR, "ktv": KOREAN_TV_DIR, "air": AIR_DIR, "anim_all": ANI_DIR, "movie": MOVIES_ROOT_DIR}.get(prefix)
    vp = get_real_path(os.path.join(base, nfc(urllib.parse.unquote(path))))
    if os.path.isdir(vp):
        fs = sorted([f for f in os.listdir(vp) if f.lower().endswith(VIDEO_EXTS)])
        if fs: vp = os.path.join(vp, fs[0])
    tp = os.path.join(DATA_DIR, f"seek_300_{tid}")
    if not os.path.exists(tp):
        try: subprocess.run([FFMPEG_PATH, "-y", "-ss", "300", "-i", vp, "-vframes", "1", "-q:v", "5", "-vf", "scale=320:-1", tp], timeout=10)
        except: pass
    return send_file(tp, mimetype='image/jpeg') if os.path.exists(tp) else ("Not Found", 404)

if __name__ == '__main__':
    app.run(host='0.0.0.0', port=5000, threaded=True)
