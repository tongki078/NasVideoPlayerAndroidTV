import os, subprocess, hashlib, urllib.parse, unicodedata, threading, time, json, re, sys, traceback, shutil, requests, random
from flask import Flask, jsonify, send_from_directory, request, Response, redirect, send_file
from flask_cors import CORS

app = Flask(__name__)
CORS(app)

# --- [1. 설정 및 경로] ---
MY_IP = "192.168.0.2"
DATA_DIR = "/volume2/video/thumbnails"
CACHE_FILE = "/volume2/video/video_cache.json"
HLS_ROOT = "/dev/shm/videoplayer_hls"
CACHE_VERSION = "3.1" # 홈 추천 시스템 추가

# TMDB 설정 (서버가 직접 미리 분류하기 위함)
TMDB_API_KEY = "eyJhbGciOiJIUzI1NiJ9.eyJhdWQiOiI3OGNiYWQ0ZjQ3NzcwYjYyYmZkMTcwNTA2NDIwZDQyYyIsIm5iZiI6MTY1MzY3NTU4MC45MTUsInN1YiI6IjYyOTExNjNjMTI0MjVjMDA1MjI0ZGQzNCIsInNjb3BlcyI6WyJhcGlfcmVhZCJdLCJ2ZXJzaW9uIjoxfQ.3YU0WuIx_WDo6nTRKehRtn4N5I4uCgjI1tlpkqfsUhk"
TMDB_BASE_URL = "https://api.themoviedb.org/3"

os.makedirs(DATA_DIR, exist_ok=True)
if os.path.exists(HLS_ROOT): shutil.rmtree(HLS_ROOT, ignore_errors=True)
os.makedirs(HLS_ROOT, exist_ok=True)

# 실제 NAS 경로 설정
FOREIGN_TV_DIR = "/volume2/video/GDS3/GDRIVE/VIDEO/외국TV"
KOREAN_TV_DIR = "/volume2/video/GDS3/GDRIVE/VIDEO/국내TV"
MOVIES_ROOT_DIR = "/volume2/video/GDS3/GDRIVE/VIDEO/영화"
ANI_DIR = "/volume2/video/GDS3/GDRIVE/VIDEO/일본 애니메이션"
AIR_DIR = "/volume2/video/GDS3/GDRIVE/VIDEO/방송중"

EXCLUDE_FOLDERS = ["성인", "19금", "Adult", "@eaDir", "#recycle"]
FFMPEG_PATH = "ffmpeg"
for p in ["/usr/local/bin/ffmpeg", "/var/packages/ffmpeg/target/bin/ffmpeg", "/usr/bin/ffmpeg"]:
    if os.path.exists(p): FFMPEG_PATH = p; break

GLOBAL_CACHE = {
    "air": [], "movies": [], "foreigntv": [], "koreantv": [],
    "animations_all": [], "search_index": [], "tmdb": {}, "home_recommend": [], "version": CACHE_VERSION
}

def nfc(text): return unicodedata.normalize('NFC', text) if text else ""
def nfd(text): return unicodedata.normalize('NFD', text) if text else ""

def get_real_path(path):
    if not path: return ""
    p_nfc = nfc(path)
    if os.path.exists(p_nfc): return p_nfc
    p_nfd = nfd(path)
    if os.path.exists(p_nfd): return p_nfd
    return p_nfc

def simplify(text): return re.sub(r'[^가-힣a-zA-Z0-9]', '', nfc(text)).lower() if text else ""

# [핵심] 서버측 TMDB 캐싱 로직
def get_tmdb_info_server(title):
    if title in GLOBAL_CACHE["tmdb"]: return GLOBAL_CACHE["tmdb"][title]
    clean_title = re.sub(r'\(.*?\)|\[.*?\]', '', title).strip()
    try:
        headers = {"Authorization": f"Bearer {TMDB_API_KEY}"}
        resp = requests.get(f"{TMDB_BASE_URL}/search/multi", params={"query": clean_title, "language": "ko-KR"}, headers=headers, timeout=5)
        data = resp.json()
        if data.get('results'):
            best = data['results'][0]
            info = {"genreIds": best.get('genre_ids', []), "posterPath": best.get('poster_path')}
            GLOBAL_CACHE["tmdb"][title] = info
            return info
    except: pass
    return {"genreIds": [], "posterPath": None}

def is_excluded(path):
    name = os.path.basename(path)
    return any(ex in name for ex in EXCLUDE_FOLDERS) or name.startswith('.')

def get_movie_info(full_path, base_dir, route_prefix):
    try:
        rel_path = nfc(os.path.relpath(full_path, base_dir))
    except:
        rel_path = nfc(os.path.basename(full_path))
    thumb_id = hashlib.md5(f"{route_prefix}_{rel_path}".encode()).hexdigest() + ".jpg"
    return {
        "id": thumb_id,
        "title": nfc(os.path.basename(full_path)),
        "videoUrl": f"http://{MY_IP}:5000/video_serve?type={route_prefix}&path={urllib.parse.quote(rel_path)}",
        "thumbnailUrl": f"http://{MY_IP}:5000/thumb_serve?type={route_prefix}&id={thumb_id}&path={urllib.parse.quote(rel_path)}"
    }

def scan_recursive(base_path, route_prefix, rel_base=None):
    categories = []
    exts = ('.mp4', '.mkv', '.avi', '.wmv', '.flv', '.ts', '.tp', '.m4v')
    p = get_real_path(base_path)
    rb = get_real_path(rel_base) if rel_base else p
    if not os.path.exists(p): return categories

    all_files = []
    for root, dirs, files in os.walk(p):
        dirs[:] = [d for d in dirs if not is_excluded(os.path.join(root, d))]
        if is_excluded(root): continue
        for f in files:
            if f.lower().endswith(exts):
                all_files.append(os.path.join(root, f))

    all_files.sort()

    current_dir = ""
    movies = []
    for full_path in all_files:
        dir_path = os.path.dirname(full_path)
        if dir_path != current_dir:
            if movies:
                name = nfc(os.path.basename(current_dir))
                tmdb_info = get_tmdb_info_server(name) # 서버에서 미리 조회
                categories.append({
                    "name": name,
                    "movies": movies,
                    "path": nfc(os.path.relpath(current_dir, rb)),
                    "genreIds": tmdb_info["genreIds"],
                    "posterPath": tmdb_info["posterPath"]
                })
            current_dir = dir_path
            movies = []
        movies.append(get_movie_info(full_path, rb, route_prefix))

    if movies:
        name = nfc(os.path.basename(current_dir))
        tmdb_info = get_tmdb_info_server(name)
        categories.append({
            "name": name,
            "movies": movies,
            "path": nfc(os.path.relpath(current_dir, rb)),
            "genreIds": tmdb_info["genreIds"],
            "posterPath": tmdb_info["posterPath"]
        })
    return categories

def paginate(data, limit, offset, lite=False):
    sliced = data[offset:offset + limit] if limit > 0 else data[offset:]
    if lite:
        return [{"name": c.get('name',''), "path": c.get('path',''), "movies": [], "genreIds": c.get('genreIds', []), "posterPath": c.get('posterPath')} for c in sliced]
    return sliced

# [추가] 홈 추천 데이터 빌드 로직
def build_home_recommend():
    print("🏠 홈 추천 데이터 생성 중...")
    movies = GLOBAL_CACHE.get("movies", [])
    animations = GLOBAL_CACHE.get("animations_all", [])

    sections = []

    # 1. 인기작 (랜덤 샘플링)
    try:
        popular_pool = random.sample(movies, min(len(movies), 15)) + random.sample(animations, min(len(animations), 15))
        random.shuffle(popular_pool)
        sections.append({"title": "지금 가장 핫한 인기작", "items": paginate(popular_pool, 15, 0, lite=True)})
    except: pass

    # 2. 최신 영화
    latest_movies = [c for c in movies if c.get('path', '').startswith(nfc("최신"))]
    if latest_movies:
        sections.append({"title": "최신 영화", "items": paginate(latest_movies, 15, 0, lite=True)})

    # 3. 인기 애니메이션
    if animations:
        sections.append({"title": "인기 애니메이션", "items": paginate(animations, 15, 0, lite=True)})

    # 4. 장르별 분류
    genre_configs = [
        ("시간 순삭! 액션 & 판타지", [28, 12, 10759]),
        ("유쾌한 웃음! 코미디 & 일상", [35, 10762]),
        ("가슴 뭉클! 감동 드라마", [18, 10749])
    ]

    all_pool = movies + animations
    for title, ids in genre_configs:
        genre_items = [c for c in all_pool if any(gid in (c.get('genreIds') or []) for gid in ids)]
        if genre_items:
            selected = random.sample(genre_items, min(len(genre_items), 15))
            sections.append({"title": title, "items": paginate(selected, 15, 0, lite=True)})

    GLOBAL_CACHE["home_recommend"] = sections
    print(f"✅ 홈 추천 데이터 생성 완료 ({len(sections)} 섹션)")

def scan_task(key, directory, prefix):
    global GLOBAL_CACHE
    print(f"🔍 스캔 중: {key}...")
    results = scan_recursive(directory, prefix)
    GLOBAL_CACHE[key] = results
    print(f"✅ 스캔 완료: {key}")

def perform_full_scan():
    print(f"🔄 전체 인덱싱 시작 (병렬 처리)...")
    tasks = [("air", AIR_DIR, "air"), ("movies", MOVIES_ROOT_DIR, "movie"),
             ("foreigntv", FOREIGN_TV_DIR, "ftv"), ("koreantv", KOREAN_TV_DIR, "ktv"),
             ("animations_all", ANI_DIR, "anim_all")]
    threads = []
    for k, d, p in tasks:
        t = threading.Thread(target=scan_task, args=(k, d, p))
        t.start(); threads.append(t)
    for t in threads: t.join()

    # [수정] 스캔 완료 후 홈 추천 빌드
    build_home_recommend()

    new_idx = []
    for k in ["air", "movies", "foreigntv", "koreantv", "animations_all"]:
        for cat in GLOBAL_CACHE.get(k, []):
            for m in cat['movies']: new_idx.append({"movie": m, "category": cat['name'], "key": simplify(m['title'])})
    GLOBAL_CACHE["search_index"] = new_idx
    save_cache()
    print(f"🚀 인덱싱 완료")

def load_cache():
    global GLOBAL_CACHE
    if os.path.exists(CACHE_FILE):
        try:
            with open(CACHE_FILE, 'r', encoding='utf-8') as f:
                data = json.load(f)
                GLOBAL_CACHE.update(data)
                return data.get("version") == CACHE_VERSION
        except: pass
    return False

def save_cache():
    try:
        with open(CACHE_FILE, 'w', encoding='utf-8') as f: json.dump(GLOBAL_CACHE, f, ensure_ascii=False, indent=2)
    except: pass

def update_index():
    load_cache()
    perform_full_scan()
    while True: time.sleep(3600 * 6); perform_full_scan()

threading.Thread(target=update_index, daemon=True).start()

# --- [API 엔드포인트] ---

@app.route('/home')
def get_home():
    return jsonify(GLOBAL_CACHE.get("home_recommend", []))

@app.route('/air')
def get_air():
    limit, offset = int(request.args.get('limit', 0)), int(request.args.get('offset', 0))
    lite = request.args.get('lite', 'false').lower() == 'true'
    return jsonify(paginate(GLOBAL_CACHE.get("air", []), limit, offset, lite))

@app.route('/movies')
def get_movies():
    route, limit, offset = request.args.get('route', ''), int(request.args.get('limit', 0)), int(request.args.get('offset', 0))
    lite = request.args.get('lite', 'false').lower() == 'true'
    data = GLOBAL_CACHE.get("movies", [])
    if route:
        normalized_route = nfc(route)
        data = [c for c in data if c.get('path', '').startswith(normalized_route)]
    return jsonify(paginate(data, limit, offset, lite))

@app.route('/list')
def get_list():
    path_query = nfc(request.args.get('path', ''))
    limit, offset = int(request.args.get('limit', 0)), int(request.args.get('offset', 0))
    if not path_query: return jsonify([])
    parts = path_query.split('/', 1)
    root_name, sub_path = parts[0], parts[1] if len(parts) > 1 else ""
    root_map = {"방송중": AIR_DIR, "애니메이션": ANI_DIR, "영화": MOVIES_ROOT_DIR, "외국TV": FOREIGN_TV_DIR, "국내TV": KOREAN_TV_DIR}
    prefix_map = {"방송중": "air", "애니메이션": "anim_all", "영화": "movie", "외국TV": "ftv", "국내TV": "ktv"}
    base = get_real_path(root_map.get(root_name))
    if not base: return jsonify([])
    target_path = get_real_path(os.path.normpath(os.path.join(base, sub_path.lstrip('/'))))
    if not os.path.exists(target_path): return jsonify([])
    sub_dirs = [nfc(n) for n in sorted(os.listdir(target_path)) if os.path.isdir(os.path.join(target_path, n)) and not is_excluded(n)]
    if sub_dirs:
        res = [{"name": d, "path": nfc(os.path.relpath(os.path.join(target_path, d), base)), "movies": [], "genreIds": [], "posterPath": None} for d in sub_dirs]
        return jsonify(paginate(res, limit, offset))
    movies_cats = scan_recursive(target_path, prefix_map.get(root_name, "movie"), rel_base=base)
    return jsonify(paginate(movies_cats, limit, offset))

@app.route('/anim_raftel')
def get_anim_raftel():
    limit, offset = int(request.args.get('limit', 0)), int(request.args.get('offset', 0))
    lite = request.args.get('lite', 'false').lower() == 'true'
    t = simplify("라프텔")
    raftel_list = [c for c in GLOBAL_CACHE.get("animations_all", []) if t in simplify(c.get('path', '')) or t in simplify(c.get('name', ''))]
    return jsonify(paginate(raftel_list, limit, offset, lite))

@app.route('/anim_series')
def get_anim_series():
    limit, offset = int(request.args.get('limit', 0)), int(request.args.get('offset', 0))
    lite = request.args.get('lite', 'false').lower() == 'true'
    t = simplify("라프텔")
    series_list = [c for c in GLOBAL_CACHE.get("animations_all", []) if t not in simplify(c.get('path', '')) and t not in simplify(c.get('name', ''))]
    return jsonify(paginate(series_list, limit, offset, lite))

@app.route('/movies_latest')
def get_movies_latest(): return get_movies_with_route("최신")
@app.route('/movies_uhd')
def get_movies_uhd(): return get_movies_with_route("UHD")
@app.route('/movies_title')
def get_movies_title(): return get_movies_with_route("제목")

def get_movies_with_route(route):
    limit, offset = int(request.args.get('limit', 0)), int(request.args.get('offset', 0))
    lite = request.args.get('lite', 'false').lower() == 'true'
    normalized_route = nfc(route)
    data = [c for c in data if c.get('path', '').startswith(normalized_route)]
    return jsonify(paginate(data, limit, offset, lite))

@app.route('/video_serve')
def serve_video():
    try:
        t, path_arg = request.args.get('type'), request.args.get('path')
        path = urllib.parse.unquote(path_arg).replace('+', ' ')
        base_map = {"movie": MOVIES_ROOT_DIR, "ftv": FOREIGN_TV_DIR, "ktv": KOREAN_TV_DIR, "anim_all": ANI_DIR, "air": AIR_DIR}
        base = get_real_path(base_map.get(t, AIR_DIR))
        actual_path = get_real_path(os.path.join(base, path.lstrip('/')))
        if not os.path.exists(actual_path): return "Not Found", 404
        return send_file(actual_path, conditional=True)
    except: return "Error", 500

@app.route('/thumb_serve')
def thumb_serve():
    t, tid, path_arg = request.args.get('type'), request.args.get('id'), request.args.get('path')
    thumb_path = os.path.join(DATA_DIR, tid)
    if os.path.exists(thumb_path): return send_from_directory(DATA_DIR, tid)
    try:
        path = urllib.parse.unquote(path_arg).replace('+', ' ')
        base_map = {"movie": MOVIES_ROOT_DIR, "ftv": FOREIGN_TV_DIR, "ktv": KOREAN_TV_DIR, "anim_all": ANI_DIR, "air": AIR_DIR}
        base = get_real_path(base_map.get(t, AIR_DIR))
        fp = get_real_path(os.path.join(base, path.lstrip('/')))
        subprocess.run([FFMPEG_PATH, '-ss', '00:03:00', '-i', fp, '-vframes', '1', '-q:v', '5', thumb_path, '-y'], timeout=15)
        return send_from_directory(DATA_DIR, tid)
    except: return "Not Found", 404

if __name__ == '__main__':
    app.run(host='0.0.0.0', port=5000, threaded=True)
