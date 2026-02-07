import os, subprocess, hashlib, urllib.parse, unicodedata, threading, time, json, re, sys, traceback, shutil, requests, random, mimetypes
from flask import Flask, jsonify, send_from_directory, request, Response, redirect, send_file
from flask_cors import CORS

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
CACHE_VERSION = "9.5" # 9.5 유지 (자동 재스캔 방지)

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
FFMPEG_PATH = "ffmpeg"
for p in ["/usr/local/bin/ffmpeg", "/var/packages/ffmpeg/target/bin/ffmpeg", "/usr/bin/ffmpeg"]:
    if os.path.exists(p): FFMPEG_PATH = p; break

GLOBAL_CACHE = {
    "air": [], "movies": [], "foreigntv": [], "koreantv": [],
    "animations_all": [], "search_index": [], "home_recommend": [], "version": CACHE_VERSION
}

def nfc(text): return unicodedata.normalize('NFC', text) if text else ""
def nfd(text): return unicodedata.normalize('NFD', text) if text else ""

# --- [정규식] ---
REGEX_EXT = re.compile(r'\.[a-zA-Z0-9]{2,4}$')
REGEX_YEAR = re.compile(r'\((19|20)\d{2}\)|(?<!\d)(19|20)\d{2}(?!\d)')
REGEX_BRACKETS = re.compile(r'\[.*?\]|\(.*?\)')
REGEX_JUNK = re.compile(r'(?i)\s*(?:더빙|자막|극장판|BD|TV|Web|OAD|OVA|ONA|Full|무삭제|감독판|확장판|최종화|TV판|완결|속편|상|하|1부|2부|파트)\s*')
REGEX_EP_MARKER = re.compile(r'(?i)(?:^|[.\s_]|(?<=[가-힣]))(?:S\d+E\d+|S\d+|E\d+|\d+\s*(?:화|회|기)|Season\s*\d+|Part\s*\d+).*')
REGEX_TECHNICAL = re.compile(r'(?i)[.\s_](?:\d{3,4}p|WEB-DL|WEBRip|Bluray|HDRip|BDRip|DVDRip|H\.?26[45]|x26[45]|HEVC|AAC|DTS|AC3|DDP|Dual|Atmos|REPACK|10bit|REMUX|FLAC|xvid|DivX|MKV|MP4|AVI).*')
REGEX_SPECIAL = re.compile(r'[._\-!?【】『』「」"\'#@*※:]')
REGEX_SPACES = re.compile(r'\s+')
REGEX_FORBIDDEN_TITLE = re.compile(r'(?i)^\s*(Season\s*\d+|Part\s*\d+|EP\s*\d+|\d+화|\d+회|\d+기|시즌\s*\d+|S\d+|E\d+)\s*$', re.I)

def clean_title_complex(title):
    if not title: return "", None
    title = nfc(title)
    cleaned = REGEX_EXT.sub('', title)
    year_match = REGEX_YEAR.search(cleaned)
    year = year_match.group().replace('(', '').replace(')', '') if year_match else None
    cleaned = REGEX_YEAR.sub(' ', cleaned)
    cleaned = REGEX_JUNK.sub(' ', cleaned)
    cleaned = REGEX_EP_MARKER.sub(' ', cleaned)
    cleaned = REGEX_BRACKETS.sub(' ', cleaned)
    cleaned = REGEX_TECHNICAL.sub('', cleaned)
    cleaned = REGEX_SPECIAL.sub(' ', cleaned)
    cleaned = REGEX_SPACES.sub(' ', cleaned).strip()
    if not cleaned or len(cleaned) < 2:
        cleaned = REGEX_EXT.sub('', title)
        cleaned = REGEX_YEAR.sub('', cleaned).strip()
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

# --- [TMDB API] ---
def search_tmdb_api(query, year=None):
    if not query or len(query) < 2 or REGEX_FORBIDDEN_TITLE.match(query): return None
    params = {"query": query, "language": "ko-KR", "include_adult": "false"}
    headers = {"Authorization": f"Bearer {TMDB_API_KEY}"} if TMDB_API_KEY.startswith("eyJ") else {}
    if not headers: params["api_key"] = TMDB_API_KEY
    try:
        resp = requests.get(f"{TMDB_BASE_URL}/search/multi", params=params, headers=headers, timeout=5)
        data = resp.json()
        results = [r for r in data.get('results', []) if r.get('media_type') in ['movie', 'tv']]
        if results:
            if year:
                for r in results:
                    r_year = (r.get('release_date') or r.get('first_air_date') or "").split('-')[0]
                    if r_year == year: return r
                return None # 연도 지정 시 매칭 실패하면 None
            return results[0]
    except: pass
    return None

def get_tmdb_info_server(title, ignore_cache=False):
    if not title: return {"failed": True}
    cp = get_tmdb_cache_path(title)
    if not ignore_cache and os.path.exists(cp):
        try:
            with open(cp, 'r', encoding='utf-8') as f:
                d = json.load(f)
                if d and (not d.get("failed") or d.get("forbidden")): return d
        except: pass

    ct, extracted_year = clean_title_complex(title)
    if REGEX_FORBIDDEN_TITLE.match(ct) or ct.lower() in ["season", "series", "video", "episode"]:
        info = {"failed": True, "forbidden": True}
        with open(cp, 'w', encoding='utf-8') as f: json.dump(info, f, ensure_ascii=False)
        return info

    print(f"  [TMDB-SEARCH] '{title}' -> '{ct}' ({extracted_year})", flush=True)
    best = None
    if extracted_year: best = search_tmdb_api(ct, extracted_year)
    if not best: best = search_tmdb_api(ct)
    if not best:
        words = ct.split(' ')
        if len(words) > 2: best = search_tmdb_api(f"{words[0]} {words[1]}")

    if best:
        m_type, t_id = best.get('media_type'), best.get('id')
        try:
            headers = {"Authorization": f"Bearer {TMDB_API_KEY}"} if TMDB_API_KEY.startswith("eyJ") else {}
            params = {} if headers else {"api_key": TMDB_API_KEY}
            d_resp = requests.get(f"{TMDB_BASE_URL}/{m_type}/{t_id}?language=ko-KR&append_to_response=content_ratings", params=params, headers=headers, timeout=5)
            d_data = d_resp.json()
            year = (d_data.get('release_date') or d_data.get('first_air_date') or "").split('-')[0]
            rating = None
            if 'content_ratings' in d_data:
                r_list = d_data['content_ratings'].get('results', [])
                kr = next((r['rating'] for r in r_list if r.get('iso_3166_1') == 'KR'), None)
                if kr: rating = f"{kr}+" if kr.isdigit() else kr
            info = {"genreIds": [g['id'] for g in d_data.get('genres', [])], "posterPath": d_data.get('poster_path'), "year": year, "overview": d_data.get('overview'), "rating": rating, "seasonCount": d_data.get('number_of_seasons'), "failed": False}
            with open(cp, 'w', encoding='utf-8') as f: json.dump(info, f, ensure_ascii=False)
            return info
        except: pass
    with open(cp, 'w', encoding='utf-8') as f: json.dump({"failed": True}, f, ensure_ascii=False)
    return {"failed": True}

def get_tmdb_cache_path(title):
    h = hashlib.md5(nfc(title).encode()).hexdigest()
    return os.path.join(TMDB_CACHE_DIR, f"{h}.json")

def attach_tmdb_info(cat):
    name = cat.get('name')
    if name:
        info = get_tmdb_info_server(name)
        cat.update(info)
    return cat

def fetch_metadata_async(force_all=False):
    print(f"🚀 [METADATA] 시작 (강제: {force_all})", flush=True)
    tasks = []
    for k in ["foreigntv", "koreantv", "air", "animations_all", "movies"]:
        for cat in GLOBAL_CACHE.get(k, []):
            if force_all or (not cat.get('posterPath') and not cat.get('failed')):
                tasks.append(cat)
    if not tasks: return
    for cat in tasks:
        info = get_tmdb_info_server(cat['name'], ignore_cache=force_all)
        cat.update(info)
        time.sleep(0.1)
    build_home_recommend(); save_cache()
    print(f"🏁 [METADATA] 완료", flush=True)

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
    GLOBAL_CACHE["home_recommend"] = [{"title": "지금 가장 핫한 인기작", "items": all_p[:20]}, {"title": "방금 올라온 최신 영화", "items": m[:20]}, {"title": "지금 가장 인기 있는 시리즈", "items": (k + f)[:20]}, {"title": "추천 애니메이션", "items": a[:20]}]

def get_movie_info(fp, base, prefix):
    try: rel = nfc(os.path.relpath(fp, base))
    except: rel = nfc(os.path.basename(fp))
    tid = hashlib.md5(f"{prefix}_{rel}".encode()).hexdigest() + ".jpg"
    return {"id": tid, "title": os.path.basename(fp), "videoUrl": f"/video_serve?type={prefix}&path={urllib.parse.quote(rel)}", "thumbnailUrl": f"/thumb_serve?type={prefix}&id={tid}&path={urllib.parse.quote(rel)}"}

def scan_recursive(bp, prefix, rb=None):
    cats = []
    exts = ('.mp4', '.mkv', '.avi', '.wmv', '.flv', '.ts', '.tp', '.m4v', '.m2ts', '.mov')
    p, rel_base = get_real_path(bp), get_real_path(rb) if rb else get_real_path(bp)
    if not os.path.exists(p): return cats
    print(f"📂 [SCAN] {prefix.upper()} 시작: {p}", flush=True)
    all_f = []
    for root, dirs, files in os.walk(p):
        dirs[:] = [d for d in dirs if not any(ex in d for ex in EXCLUDE_FOLDERS) and not d.startswith('.')]
        for f in files:
            if f.lower().endswith(exts): all_f.append(os.path.join(root, f))
    all_f.sort()
    curr, movies = "", []
    for fp in all_f:
        dp = os.path.dirname(fp)
        if dp != curr:
            if movies:
                rel_path = nfc(os.path.relpath(curr, rel_base))
                cats.append(attach_tmdb_info({"name": nfc(os.path.basename(curr)), "movies": movies, "path": rel_path}))
            curr, movies = dp, []
        movies.append(get_movie_info(fp, rel_base, prefix))
    if movies:
        rel_path = nfc(os.path.relpath(curr, rel_base))
        cats.append(attach_tmdb_info({"name": nfc(os.path.basename(curr)), "movies": movies, "path": rel_path}))
    return cats

def perform_full_scan(reason="필요"):
    print(f"\n🔄 사유: {reason} -> 전체 파일 스캔 시작", flush=True)
    t = [("air", AIR_DIR, "air"), ("animations_all", ANI_DIR, "anim_all"), ("movies", MOVIES_ROOT_DIR, "movie"), ("foreigntv", FOREIGN_TV_DIR, "ftv"), ("koreantv", KOREAN_TV_DIR, "ktv")]
    for k, p, pr in t:
        try: GLOBAL_CACHE[k] = scan_recursive(p, pr)
        except: pass
    build_home_recommend(); save_cache()
    threading.Thread(target=fetch_metadata_async, daemon=True).start()

def load_cache():
    if os.path.exists(CACHE_FILE):
        try:
            with open(CACHE_FILE, 'r', encoding='utf-8') as f:
                d = json.load(f)
                if d.get("version") == CACHE_VERSION: GLOBAL_CACHE.update(d); print(f"✅ [BOOT] 로드 완료 (v{CACHE_VERSION})", flush=True); return True
        except: pass
    return False

def save_cache():
    try:
        with open(CACHE_FILE, 'w', encoding='utf-8') as f: json.dump(GLOBAL_CACHE, f, ensure_ascii=False)
        print(f"💾 [CACHE] 서버 상태 저장 완료", flush=True)
    except: pass

def init_server():
    print(f"📺 NAS Server v{CACHE_VERSION} 시작", flush=True)
    loaded = load_cache()
    threading.Thread(target=fetch_metadata_async, daemon=True).start()
    def background_resume():
        if not loaded: perform_full_scan(reason="최초 실행")
        else:
            for k, p, pr in [("foreigntv", FOREIGN_TV_DIR, "ftv"), ("koreantv", KOREAN_TV_DIR, "ktv"), ("air", AIR_DIR, "air"), ("animations_all", ANI_DIR, "anim_all"), ("movies", MOVIES_ROOT_DIR, "movie")]:
                if not GLOBAL_CACHE.get(k):
                    GLOBAL_CACHE[k] = scan_recursive(p, pr)
                    save_cache()
            build_home_recommend()
    threading.Thread(target=background_resume, daemon=True).start()

init_server()

# --- [API] ---
@app.route('/scan')
def manual_scan(): threading.Thread(target=perform_full_scan, args=("사용자 요청",)).start(); return "스캔 시작"

@app.route('/home')
def get_home(): return jsonify(GLOBAL_CACHE.get("home_recommend", []))

@app.route('/air')
def get_air(): return jsonify(process_data(GLOBAL_CACHE.get("air", []), request.args.get('lite') == 'true'))

def process_data(data, lite=False):
    if lite: return [{"name": c.get('name',''), "path": c.get('path',''), "movies": [], "genreIds": c.get('genreIds', []), "posterPath": c.get('posterPath'), "year": c.get('year'), "overview": c.get('overview'), "rating": c.get('rating'), "seasonCount": c.get('seasonCount'), "failed": c.get('failed', False)} for c in data]
    return data

def filter_by_path(pool, keyword):
    target = nfc(keyword).replace(" ", "").lower()
    return [c for c in pool if target in nfc(c.get('path', '')).replace(" ", "").lower()]

@app.route('/animations_all')
def get_animations_all(): return jsonify(process_data(GLOBAL_CACHE.get("animations_all", []), request.args.get('lite') == 'true'))
@app.route('/foreigntv')
def get_foreigntv(): return jsonify(process_data(GLOBAL_CACHE.get("foreigntv", []), request.args.get('lite') == 'true'))
@app.route('/koreantv')
def get_koreantv(): return jsonify(process_data(GLOBAL_CACHE.get("koreantv", []), request.args.get('lite') == 'true'))
@app.route('/movies')
def get_movies(): return jsonify(process_data(GLOBAL_CACHE.get("movies", []), request.args.get('lite') == 'true'))

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

@app.route('/video_serve')
def video_serve():
    path, prefix = request.args.get('path'), request.args.get('type')
    base = {"ftv": FOREIGN_TV_DIR, "ktv": KOREAN_TV_DIR, "air": AIR_DIR, "anim_all": ANI_DIR, "movie": MOVIES_ROOT_DIR}.get(prefix)
    if not base: return "Invalid Type", 400
    fp = get_real_path(os.path.join(base, nfc(urllib.parse.unquote(path))))
    if os.path.exists(fp): return send_file(fp, conditional=True, mimetype=mimetypes.guess_type(fp)[0] or 'video/mp4')
    return "Not Found", 404

@app.route('/thumb_serve')
def thumb_serve():
    path, prefix, tid = request.args.get('path'), request.args.get('type'), request.args.get('id')
    base = {"ftv": FOREIGN_TV_DIR, "ktv": KOREAN_TV_DIR, "air": AIR_DIR, "anim_all": ANI_DIR, "movie": MOVIES_ROOT_DIR}.get(prefix)
    vp = get_real_path(os.path.join(base, nfc(urllib.parse.unquote(path))))
    if os.path.isdir(vp):
        fs = sorted([f for f in os.listdir(vp) if f.lower().endswith(('.mp4', '.mkv', '.avi'))])
        if fs: vp = os.path.join(vp, fs[0])
    tp = os.path.join(DATA_DIR, tid)
    if not os.path.exists(tp):
        try: subprocess.run([FFMPEG_PATH, "-y", "-ss", "00:05:00", "-i", vp, "-vframes", "1", "-q:v", "2", tp], timeout=15)
        except: pass
    return send_file(tp, mimetype='image/jpeg') if os.path.exists(tp) else ("Not Found", 404)

if __name__ == '__main__':
    app.run(host='0.0.0.0', port=5000, threaded=True)
