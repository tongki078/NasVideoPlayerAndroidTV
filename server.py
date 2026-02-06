import os, subprocess, hashlib, urllib.parse, unicodedata, threading, time, json, re, sys, traceback, shutil, requests, random, mimetypes
from flask import Flask, jsonify, send_from_directory, request, Response, redirect, send_file
from flask_cors import CORS
from concurrent.futures import ThreadPoolExecutor

app = Flask(__name__)
CORS(app)

# MIME 타입 추가 등록 (NAS 환경 대응)
if not mimetypes.types_map.get('.mkv'): mimetypes.add_type('video/x-matroska', '.mkv')
if not mimetypes.types_map.get('.ts'): mimetypes.add_type('video/mp2t', '.ts')
if not mimetypes.types_map.get('.tp'): mimetypes.add_type('video/mp2t', '.tp')

# --- [1. 설정 및 경로] ---
MY_IP = "192.168.0.2"
DATA_DIR = "/volume2/video/thumbnails"
CACHE_FILE = "/volume2/video/video_cache.json"
TMDB_CACHE_DIR = "/volume2/video/tmdb_cache"  # TMDB 메타데이터 저장 폴더
HLS_ROOT = "/dev/shm/videoplayer_hls"
CACHE_VERSION = "9.3"

TMDB_API_KEY = "eyJhbGciOiJIUzI1NiJ9.eyJhdWQiOiI3OGNiYWQ0ZjQ3NzcwYjYyYmZkMTcwNTA2NDIwZDQyYyIsIm5iZiI6MTY1MzY3NTU4MC45MTUsInN1YiI6IjYyOTExNjNjMTI0MjVjMDA1MjI0ZDQzNCIsInNjb3BlcyI6WyJhcGlfcmVhZCJdLCJ2ZXJzaW9uIjoxfQ.3YU0WuIx_WDo6nTRKehRtn4N5I4uCgjI1tlpkqfsUhk"
TMDB_BASE_URL = "https://api.themoviedb.org/3"

os.makedirs(DATA_DIR, exist_ok=True)
os.makedirs(TMDB_CACHE_DIR, exist_ok=True) # TMDB 폴더 생성
if os.path.exists(HLS_ROOT): shutil.rmtree(HLS_ROOT, ignore_errors=True)
os.makedirs(HLS_ROOT, exist_ok=True)

# 실제 NAS 경로 설정
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

REGEX_EXT = re.compile(r'\.[a-zA-Z0-9]{2,4}$')
REGEX_HANGUL_ALPHA = re.compile(r'([가-힣])([a-zA-Z0-9])')
REGEX_ALPHA_HANGUL = re.compile(r'([a-zA-Z0-9])([가-힣])')
REGEX_START_NUM = re.compile(r'^\d+[.\s_-]+')
REGEX_EP_SUFFIX = re.compile(r'(?i)[.\s_](?:S\d+E\d+|S\d+|E\d+|\d+\s*(?:화|회|기)|Season\s*\d+|Part\s*\d+).*')

def clean_title_complex(title):
    if not title: return ""
    cleaned = REGEX_EXT.sub('', title)
    cleaned = REGEX_HANGUL_ALPHA.sub(r'\1 \2', cleaned)
    cleaned = REGEX_ALPHA_HANGUL.sub(r'\1 \2', cleaned)
    cleaned = REGEX_START_NUM.sub('', cleaned)
    cleaned = REGEX_EP_SUFFIX.sub('', cleaned)
    return cleaned.strip()

def get_real_path(path):
    if not path: return path
    if os.path.exists(path): return path
    p_nfc, p_nfd = nfc(path), nfd(path)
    if os.path.exists(p_nfc): return p_nfc
    if os.path.exists(p_nfd): return p_nfd
    parent = os.path.dirname(path)
    if os.path.exists(parent):
        try:
            tn = nfc(os.path.basename(path))
            for e in os.listdir(parent):
                if nfc(e) == tn: return os.path.join(parent, e)
        except: pass
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
        print(f"[PATH] Resolve: {app_path} -> {resolved} ({type_code})", flush=True)
        return resolved, type_code
    print(f"[PATH] Resolve Failed: {app_path}", flush=True)
    return None, None

# --- [TMDB 및 메타데이터] ---
def get_tmdb_cache_path(title):
    h = hashlib.md5(nfc(title).encode()).hexdigest()
    return os.path.join(TMDB_CACHE_DIR, f"{h}.json")

def get_tmdb_info_server(title):
    if not title: return {"genreIds": [], "posterPath": None, "failed": True}

    cp = get_tmdb_cache_path(title)
    if os.path.exists(cp):
        try:
            with open(cp, 'r', encoding='utf-8') as f:
                data = json.load(f)
                if data: return data
        except: pass

    ct = clean_title_complex(title)
    if not ct:
        info = {"genreIds": [], "posterPath": None, "failed": True}
        try:
            with open(cp, 'w', encoding='utf-8') as f: json.dump(info, f, ensure_ascii=False)
        except: pass
        return info

    try:
        headers = {"Authorization": f"Bearer {TMDB_API_KEY}"}
        resp = requests.get(f"{TMDB_BASE_URL}/search/multi", params={"query": ct, "language": "ko-KR"}, headers=headers, timeout=5)
        resp.raise_for_status()
        data = resp.json()

        info = {"genreIds": [], "posterPath": None, "failed": False}
        if data.get('results'):
            res = [r for r in data['results'] if r.get('media_type') != 'person']
            if res:
                best = res[0]
                info = {"genreIds": best.get('genre_ids', []), "posterPath": best.get('poster_path'), "tmdbId": best.get('id'), "mediaType": best.get('media_type'), "failed": False}

        if not info.get("posterPath"):
            info["failed"] = True
            print(f"[TMDB] No results for: {ct}", flush=True)

        try:
            with open(cp, 'w', encoding='utf-8') as f: json.dump(info, f, ensure_ascii=False)
        except: pass
        return info
    except Exception as e:
        print(f"[TMDB] API Error for {title}: {e}", flush=True)
        return {"genreIds": [], "posterPath": None, "failed": False}

def attach_tmdb_info(cat):
    name = cat.get('name')
    if name:
        cp = get_tmdb_cache_path(name)
        if os.path.exists(cp):
            try:
                with open(cp, 'r', encoding='utf-8') as f:
                    info = json.load(f)
                    cat['genreIds'], cat['posterPath'] = info.get('genreIds', []), info.get('posterPath')
            except: pass
    return cat

def fetch_metadata_async():
    print(f"[METADATA] TMDB 업데이트 프로세스 시작 (Cache: {TMDB_CACHE_DIR})", flush=True)
    tasks = []

    for k in ["foreigntv", "koreantv", "air", "animations_all", "movies"]:
        for cat in GLOBAL_CACHE.get(k, []):
            name = cat.get('name')
            if not name: continue
            cp = get_tmdb_cache_path(name)

            # 1. 파일 캐시가 있으면 메모리에 반영
            if os.path.exists(cp):
                try:
                    with open(cp, 'r', encoding='utf-8') as f:
                        info = json.load(f)
                        if not cat.get('posterPath') and info.get('posterPath'):
                            cat['genreIds'], cat['posterPath'] = info.get('genreIds', []), info.get('posterPath')
                        if info.get('posterPath') or info.get('failed'):
                            continue
                except: pass

            # 2. 파일은 없는데 메모리에 정보가 있는 경우 (마이그레이션)
            if cat.get('posterPath') and not os.path.exists(cp):
                info = {"genreIds": cat.get('genreIds', []), "posterPath": cat.get('posterPath'), "failed": False}
                try:
                    with open(cp, 'w', encoding='utf-8') as f:
                        json.dump(info, f, ensure_ascii=False)
                except: pass
                continue

            # 3. 정보가 아예 없는 경우 태스크 추가
            if not cat.get('posterPath'):
                tasks.append(cat)

    if not tasks:
        print("[METADATA] 업데이트할 항목 없음", flush=True)
        return

    print(f"[METADATA] {len(tasks)}개 항목 API 업데이트 진행...", flush=True)
    updated_count = 0
    with ThreadPoolExecutor(max_workers=5) as executor:
        results = list(executor.map(lambda c: (c, get_tmdb_info_server(c['name'])), tasks))
        for cat, info in results:
            if info.get('posterPath'):
                cat['genreIds'], cat['posterPath'] = info.get('genreIds', []), info.get('posterPath')
                updated_count += 1
    if updated_count > 0: build_home_recommend(); save_cache()
    print(f"[METADATA] 완료 ({updated_count}개 갱신)", flush=True)

def build_home_recommend():
    pool = GLOBAL_CACHE.get("movies", []) + GLOBAL_CACHE.get("animations_all", [])
    if pool:
        popular = random.sample(pool, min(len(pool), 20))
        GLOBAL_CACHE["home_recommend"] = [{"title": "지금 가장 핫한 인기작", "items": process_data(popular, True)}]

# --- [스캔 로직] ---
def get_movie_info(fp, base, prefix):
    try: rel = nfc(os.path.relpath(fp, base))
    except: rel = nfc(os.path.basename(fp))
    tid = hashlib.md5(f"{prefix}_{rel}".encode()).hexdigest() + ".jpg"
    return {"id": tid, "title": os.path.basename(fp), "videoUrl": f"/video_serve?type={prefix}&path={urllib.parse.quote(rel)}", "thumbnailUrl": f"/thumb_serve?type={prefix}&id={tid}&path={urllib.parse.quote(rel)}"}

def scan_recursive(bp, prefix, rb=None):
    cats = []
    exts = ('.mp4', '.mkv', '.avi', '.wmv', '.flv', '.ts', '.tp', '.m4v', '.m2ts', '.mov')
    p, rel_base = get_real_path(bp), get_real_path(rb) if rb else get_real_path(bp)
    if not os.path.exists(p):
        print(f"[{prefix}] 경로 없음: {p}", flush=True)
        return cats
    print(f"[{prefix}] 스캔 시작: {p}", flush=True)
    all_f = []
    for root, dirs, files in os.walk(p):
        dirs[:] = [d for d in dirs if not is_excluded(os.path.join(root, d))]
        if is_excluded(root): continue
        for f in files:
            if f.lower().endswith(exts): all_f.append(os.path.join(root, f))

    all_f.sort()
    curr, movies = "", []
    total = len(all_f)
    for idx, fp in enumerate(all_f):
        if idx % 100 == 0: print(f"[{prefix}] 진행 중: {idx}/{total}", flush=True)
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
    print(f"[{prefix}] 스캔 완료: {len(cats)}개 발견", flush=True)
    return cats

def is_excluded(path):
    n = os.path.basename(path)
    return any(ex in n for ex in EXCLUDE_FOLDERS) or n.startswith('.')

def perform_full_scan():
    print("\n" + "="*50)
    print(f"🔄 파일 시스템 전체 스캔 시작 (v{CACHE_VERSION})", flush=True)
    print("="*50)
    scan_targets = [
        ("방송중", AIR_DIR, "air"),
        ("애니메이션", ANI_DIR, "anim_all"),
        ("영화", MOVIES_ROOT_DIR, "movie"),
        ("외국TV", FOREIGN_TV_DIR, "ftv"),
        ("국내TV", KOREAN_TV_DIR, "ktv")
    ]

    # 캐시 키 맵핑 (prefix -> GLOBAL_CACHE key)
    key_mapping = {
        "air": "air",
        "anim_all": "animations_all",
        "movie": "movies",
        "ftv": "foreigntv",
        "ktv": "koreantv"
    }

    for label, path, prefix in scan_targets:
        try:
            print(f"\n▶ [{label}] 스캔 중: {path}", flush=True)
            res = scan_recursive(path, prefix)
            key = key_mapping.get(prefix, prefix)
            GLOBAL_CACHE[key] = res
            print(f"✅ [{label}] 완료: {len(res)}개 항목 발견", flush=True)
        except Exception as e:
            print(f"❌ [{label}] 스캔 중 오류 발생: {e}", flush=True)
            traceback.print_exc()

    build_home_recommend()
    save_cache()
    threading.Thread(target=fetch_metadata_async, daemon=True).start()
    print("\n" + "="*50)
    print("🏁 전체 스캔 프로세스 완료", flush=True)
    print("="*50 + "\n")

def load_cache():
    if os.path.exists(CACHE_FILE):
        try:
            with open(CACHE_FILE, 'r', encoding='utf-8') as f:
                d = json.load(f)
                if d.get("version") == CACHE_VERSION:
                    GLOBAL_CACHE.update(d); print(f"✅ [CACHE] JSON 캐시 로드 완료", flush=True); return True
        except: pass
    return False

def save_cache():
    try:
        with open(CACHE_FILE, 'w', encoding='utf-8') as f: json.dump(GLOBAL_CACHE, f, ensure_ascii=False)
    except: pass

def init_server():
    loaded = load_cache()
    threading.Thread(target=fetch_metadata_async, daemon=True).start()
    def background_resume():
        if not loaded: perform_full_scan()
        else:
            for k, p, pr in [("foreigntv", FOREIGN_TV_DIR, "ftv"), ("koreantv", KOREAN_TV_DIR, "ktv"), ("air", AIR_DIR, "air"), ("animations_all", ANI_DIR, "anim_all"), ("movies", MOVIES_ROOT_DIR, "movie")]:
                if not GLOBAL_CACHE.get(k):
                    print(f"[{k}] 캐시 비어있음. 부분 스캔 시작...", flush=True)
                    GLOBAL_CACHE[k] = scan_recursive(p, pr); save_cache()
    threading.Thread(target=background_resume, daemon=True).start()

init_server()

# --- [API 엔드포인트] ---
@app.route('/scan')
def manual_scan(): threading.Thread(target=perform_full_scan).start(); return "스캔 시작"
@app.route('/home')
def get_home(): return jsonify(GLOBAL_CACHE.get("home_recommend", []))
@app.route('/air')
def get_air(): return jsonify(process_data(GLOBAL_CACHE.get("air", []), request.args.get('lite') == 'true'))

@app.route('/animations')
def get_animations():
    res = [c for c in GLOBAL_CACHE.get("air", []) if any(k in c.get('path', '') for k in ["라프텔", "애니"])]
    return jsonify(process_data(res, request.args.get('lite') == 'true'))

@app.route('/dramas')
def get_dramas():
    res = [c for c in GLOBAL_CACHE.get("air", []) if "드라마" in c.get('path', '')]
    return jsonify(process_data(res, request.args.get('lite') == 'true'))

def process_data(data, lite=False):
    if lite: return [{"name": c.get('name',''), "path": c.get('path',''), "movies": [], "genreIds": c.get('genreIds', []), "posterPath": c.get('posterPath')} for c in data]
    return data

def filter_by_path(pool, keyword):
    target = nfc(keyword).replace(" ", "").lower()
    print(f"[FILTER] '{keyword}' 필터링 (총 {len(pool)}개)", flush=True)
    res = [c for c in pool if target in nfc(c.get('path', '')).replace(" ", "").lower()]
    print(f"[FILTER] -> {len(res)}개 발견", flush=True)
    return res

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

@app.route('/koreantv')
def get_koreantv(): return jsonify(process_data(GLOBAL_CACHE.get("koreantv", []), request.args.get('lite') == 'true'))
@app.route('/ktv_drama')
def get_ktv_drama(): return jsonify(process_data(filter_by_path(GLOBAL_CACHE.get("koreantv", []), "드라마"), request.args.get('lite') == 'true'))
@app.route('/ktv_variety')
def get_ktv_variety(): return jsonify(process_data(filter_by_path(GLOBAL_CACHE.get("koreantv", []), "예능"), request.args.get('lite') == 'true'))
@app.route('/ktv_sitcom')
def get_ktv_sitcom(): return jsonify(process_data(filter_by_path(GLOBAL_CACHE.get("koreantv", []), "시트콤"), request.args.get('lite') == 'true'))

@app.route('/animations_all')
def get_animations_all(): return jsonify(process_data(GLOBAL_CACHE.get("animations_all", []), request.args.get('lite') == 'true'))
@app.route('/movies')
def get_movies(): return jsonify(process_data(GLOBAL_CACHE.get("movies", []), request.args.get('lite') == 'true'))
@app.route('/movies_latest')
def get_movies_latest(): return jsonify(process_data(GLOBAL_CACHE.get("movies", []), request.args.get('lite') == 'true'))

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
    if os.path.isfile(real_path): real_path = os.path.dirname(real_path)
    base_dir = PATH_MAP.get(path.split('/')[0], (None, None))[0]
    res, movies, exts = [], [], ('.mp4', '.mkv', '.avi', '.wmv', '.flv', '.ts', '.tp', '.m4v', '.m2ts', '.mov')
    for entry in sorted(os.listdir(real_path)):
        fe = os.path.join(real_path, entry)
        if is_excluded(fe): continue
        if os.path.isdir(fe):
            res.append({"name": nfc(entry), "path": nfc(os.path.relpath(fe, base_dir)), "movies": []})
        elif entry.lower().endswith(exts):
            movies.append(get_movie_info(fe, base_dir, type_code))
    if movies: res.append({"name": nfc(os.path.basename(real_path)), "path": nfc(os.path.relpath(real_path, base_dir)), "movies": movies})
    return jsonify(res)

@app.route('/video_serve')
def video_serve():
    path, prefix = request.args.get('path'), request.args.get('type')
    if not path or not prefix: return "Missing Params", 400
    base = {"ftv": FOREIGN_TV_DIR, "ktv": KOREAN_TV_DIR, "air": AIR_DIR, "anim_all": ANI_DIR, "movie": MOVIES_ROOT_DIR}.get(prefix)
    if not base: return "Invalid Type", 400
    path = nfc(urllib.parse.unquote(path))
    fp = get_real_path(os.path.join(base, path))
    if os.path.exists(fp):
        print(f"[VIDEO] Serving: {fp}", flush=True)
        return send_file(fp, conditional=True, mimetype=mimetypes.guess_type(fp)[0] or 'video/mp4')
    print(f"[VIDEO] Not Found: {fp}", flush=True)
    return "Not Found", 404

@app.route('/thumb_serve')
def thumb_serve():
    path, prefix, tid = request.args.get('path'), request.args.get('type'), request.args.get('id')
    base = {"ftv": FOREIGN_TV_DIR, "ktv": KOREAN_TV_DIR, "air": AIR_DIR, "anim_all": ANI_DIR, "movie": MOVIES_ROOT_DIR}.get(prefix)
    if not path: return "Missing path", 400
    path = nfc(urllib.parse.unquote(path))
    vp = get_real_path(os.path.join(base, path))
    if os.path.isdir(vp):
        exts = ('.mp4', '.mkv', '.avi', '.wmv', '.flv', '.ts', '.tp', '.m4v', '.m2ts', '.mov')
        fs = sorted([f for f in os.listdir(vp) if f.lower().endswith(exts)])
        if fs: vp = os.path.join(vp, fs[0])
    tp = os.path.join(DATA_DIR, tid)
    if not os.path.exists(tp):
        try: subprocess.run([FFMPEG_PATH, "-y", "-ss", "00:05:00", "-i", vp, "-vframes", "1", "-q:v", "2", tp], timeout=15)
        except: pass
    return send_file(tp, mimetype='image/jpeg') if os.path.exists(tp) else ("Not Found", 404)

if __name__ == '__main__':
    print(f"🚀 서버 시작 (IP: {MY_IP}, Port: 5000)", flush=True)
    app.run(host='0.0.0.0', port=5000, threaded=True)