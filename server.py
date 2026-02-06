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
CACHE_VERSION = "4.8"

TMDB_API_KEY = "eyJhbGciOiJIUzI1NiJ9.eyJhdWQiOiI3OGNiYWQ0ZjQ3NzcwYjYyYmZkMTcwNTA2NDIwZDQyYyIsIm5iZiI6MTY1MzY3NTU4MC45MTUsInN1YiI6IjYyOTExNjNjMTI0MjVjMDA1MjI0ZGQzNCIsInNjb3BlcyI6WyJhcGlfcmVhZCJdLCJ2ZXJzaW9uIjoxfQ.3YU0WuIx_WDo6nTRKehRtn4N5I4uCgjI1tlpkqfsUhk"
TMDB_BASE_URL = "https://api.themoviedb.org/3"

os.makedirs(DATA_DIR, exist_ok=True)
if os.path.exists(HLS_ROOT): shutil.rmtree(HLS_ROOT, ignore_errors=True)
os.makedirs(HLS_ROOT, exist_ok=True)

# 실제 NAS 경로 설정
PARENT_VIDEO_DIR = "/volume2/video/GDS3/GDRIVE/VIDEO"
FOREIGN_TV_DIR = os.path.join(PARENT_VIDEO_DIR, "외국TV")
KOREAN_TV_DIR = os.path.join(PARENT_VIDEO_DIR, "국내TV")
MOVIES_ROOT_DIR = os.path.join(PARENT_VIDEO_DIR, "영화")
ANI_DIR = os.path.join(PARENT_VIDEO_DIR, "일본 애니메이션")
AIR_DIR = os.path.join(PARENT_VIDEO_DIR, "방송중")

EXCLUDE_FOLDERS = ["성인", "19금", "Adult", "@eaDir", "#recycle"]
FFMPEG_PATH = "ffmpeg"
for p in ["/usr/local/bin/ffmpeg", "/var/packages/ffmpeg/target/bin/ffmpeg", "/usr/bin/ffmpeg"]:
    if os.path.exists(p): FFMPEG_PATH = p; break

GLOBAL_CACHE = {
    "air": [], "movies": [], "foreigntv": [], "koreantv": [],
    "animations_all": [], "search_index": [], "tmdb": {}, "home_recommend": [], "version": CACHE_VERSION
}

# TV 시리즈 제목 정제를 위한 정규식
CLEAN_REGEX = re.compile(r'(?i)[.\s_-]+(?:S\d+E\d+|S\d+|E\d+|EP\d+|\d+화|\d+회|시즌\d+|\d+기|\(\d+\)).*')

def nfc(text): return unicodedata.normalize('NFC', text) if text else ""
def nfd(text): return unicodedata.normalize('NFD', text) if text else ""

def get_real_path(path):
    if not path: return ""
    if os.path.exists(path): return path
    p_nfc = nfc(path)
    if os.path.exists(p_nfc): return p_nfc
    p_nfd = nfd(path)
    if os.path.exists(p_nfd): return p_nfd
    parent = os.path.dirname(path)
    if os.path.exists(parent):
        target_name = nfc(os.path.basename(path))
        try:
            for entry in os.listdir(parent):
                if nfc(entry) == target_name: return os.path.join(parent, entry)
        except: pass
    return path

# --- [TMDB 및 메타데이터 로직] ---
def get_tmdb_info_server(title):
    if title in GLOBAL_CACHE["tmdb"]: return GLOBAL_CACHE["tmdb"][title]
    clean_title = re.sub(r'\(.*?\)|\[.*?\]', '', title).strip()
    clean_title = CLEAN_REGEX.sub('', clean_title).strip()
    if not clean_title: return {"genreIds": [], "posterPath": None}
    try:
        headers = {"Authorization": f"Bearer {TMDB_API_KEY}"}
        resp = requests.get(f"{TMDB_BASE_URL}/search/multi", params={"query": clean_title, "language": "ko-KR"}, headers=headers, timeout=5)
        data = resp.json()
        if data.get('results'):
            best = data['results'][0]
            info = {"genreIds": best.get('genre_ids', []), "posterPath": best.get('poster_path')}
            GLOBAL_CACHE["tmdb"][title] = info
            return info
    except Exception as e:
        print(f"⚠️ TMDB 검색 실패 ({title}): {e}", flush=True)
    return {"genreIds": [], "posterPath": None}

def attach_tmdb_info(cat):
    """카테고리 객체에 캐시된 TMDB 정보를 부착"""
    name = cat.get('name')
    if name and name in GLOBAL_CACHE["tmdb"]:
        info = GLOBAL_CACHE["tmdb"][name]
        cat['genreIds'] = info.get('genreIds', [])
        cat['posterPath'] = info.get('posterPath')
    return cat

def fetch_metadata_async():
    print("🌐 TMDB 메타데이터 업데이트 시작...", flush=True)
    keys = ["movies", "animations_all", "air", "foreigntv", "koreantv"]
    updated_count = 0
    for k in keys:
        for cat in GLOBAL_CACHE.get(k, []):
            info = get_tmdb_info_server(cat['name'])
            cat['genreIds'] = info.get("genreIds", [])
            cat['posterPath'] = info.get("posterPath")
            updated_count += 1
            if updated_count % 50 == 0:
                print(f"  ... {updated_count}개 항목 처리 중", flush=True)

    build_home_recommend()
    save_cache()
    print(f"✅ TMDB 업데이트 완료 (총 {updated_count}개 항목)", flush=True)

def build_home_recommend():
    print("🏠 홈 추천 섹션 구성 중...", flush=True)
    movies, animations = GLOBAL_CACHE.get("movies", []), GLOBAL_CACHE.get("animations_all", [])
    sections, all_pool = [], movies + animations

    if all_pool:
        popular = random.sample(all_pool, min(len(all_pool), 20))
        sections.append({"title": "지금 가장 핫한 인기작", "items": process_data(popular, True)})

    latest = [c for c in movies if nfc("최신") in nfc(c.get('path', ''))]
    if latest: sections.append({"title": "최신 개봉 영화", "items": process_data(latest, True)})

    if animations: sections.append({"title": "인기 애니메이션", "items": process_data(animations, True)})

    # TMDB 장르 기반 테마 분류
    theme_configs = [
        ("박진감 넘치는 액션 & 어드벤처", [28, 12, 10759]),
        ("유쾌한 웃음! 코미디", [35]),
        ("몰입감 최고의 드라마", [18]),
        ("환상적인 판타지 세계", [14, 10765]),
        ("아이들을 위한 애니메이션", [16, 10762])
    ]

    for title, ids in theme_configs:
        items = [c for c in all_pool if any(gid in (c.get('genreIds') or []) for gid in ids)]
        if items:
            sample_size = min(len(items), 20)
            sections.append({"title": title, "items": process_data(random.sample(items, sample_size), True)})

    GLOBAL_CACHE["home_recommend"] = sections
    print(f"✅ 홈 추천 구성 완료 ({len(sections)}개 섹션)", flush=True)

# --- [스캔 및 캐시 로직] ---
def is_excluded(path):
    name = os.path.basename(path)
    return any(ex in name for os.path.basename(path) in EXCLUDE_FOLDERS) or name.startswith('.')

def get_movie_info(full_path, base_dir, route_prefix):
    try: rel_path = nfc(os.path.relpath(full_path, base_dir))
    except: rel_path = nfc(os.path.basename(full_path))
    thumb_id = hashlib.md5(f"{route_prefix}_{rel_path}".encode()).hexdigest() + ".jpg"
    return {
        "id": thumb_id, "title": os.path.basename(full_path),
        "videoUrl": f"http://{MY_IP}:5000/video_serve?type={route_prefix}&path={urllib.parse.quote(rel_path)}",
        "thumbnailUrl": f"http://{MY_IP}:5000/thumb_serve?type={route_prefix}&id={thumb_id}&path={urllib.parse.quote(rel_path)}"
    }

def scan_recursive(base_path, route_prefix, rel_base=None):
    print(f"    [스캔] {base_path} 탐색 시작...", flush=True)
    categories = []
    exts = ('.mp4', '.mkv', '.avi', '.wmv', '.flv', '.ts', '.tp', '.m4v')
    p = get_real_path(base_path)
    rb = get_real_path(rel_base) if rel_base else p
    if not os.path.exists(p):
        print(f"    [스캔] 경로 없음: {p}", flush=True)
        return categories

    all_files = []
    for root, dirs, files in os.walk(p):
        dirs[:] = [d for d in dirs if not is_excluded(os.path.join(root, d))]
        if is_excluded(root): continue
        for f in files:
            if f.lower().endswith(exts): all_files.append(os.path.join(root, f))

    print(f"    [스캔] {len(all_files)}개의 미디어 파일 발견", flush=True)
    if not all_files: return categories
    all_files.sort()

    current_dir = ""
    movies = []
    for full_path in all_files:
        dir_path = os.path.dirname(full_path)
        if dir_path != current_dir:
            if movies:
                name = nfc(os.path.basename(current_dir))
                cat = {"name": name, "movies": movies, "path": nfc(os.path.relpath(current_dir, rb))}
                categories.append(attach_tmdb_info(cat))
            current_dir = dir_path
            movies = []
        movies.append(get_movie_info(full_path, rb, route_prefix))

    if movies:
        name = nfc(os.path.basename(current_dir))
        cat = {"name": name, "movies": movies, "path": nfc(os.path.relpath(current_dir, rb))}
        categories.append(attach_tmdb_info(cat))

    print(f"    [스캔] {len(categories)}개의 카테고리 그룹화 완료", flush=True)
    return categories

def process_data(data, lite=False):
    if lite:
        return [{"name": c.get('name',''), "path": c.get('path',''), "movies": [], "genreIds": c.get('genreIds', []), "posterPath": c.get('posterPath')} for c in data]
    return data

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
    for label, path, prefix in scan_targets:
        try:
            print(f"\n▶ [{label}] 스캔 중: {path}", flush=True)
            res = scan_recursive(path, prefix)
            key = 'movies' if prefix == 'movie' else ('animations_all' if prefix == 'anim_all' else prefix)
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
                data = json.load(f)
                if data.get("version") == CACHE_VERSION:
                    GLOBAL_CACHE.update(data)
                    print(f"📦 캐시 파일 로드 완료 (v{CACHE_VERSION})", flush=True)
                    return True
                else:
                    print(f"⚠️ 캐시 버전 불일치. 재스캔 유도.", flush=True)
        except: pass
    return False

def save_cache():
    try:
        with open(CACHE_FILE, 'w', encoding='utf-8') as f:
            json.dump(GLOBAL_CACHE, f, ensure_ascii=False)
            print(f"💾 캐시 저장 완료", flush=True)
    except: pass

def update_index():
    if not load_cache():
        perform_full_scan()
    else:
        threading.Thread(target=fetch_metadata_async, daemon=True).start()

    while True:
        time.sleep(3600 * 6)
        perform_full_scan()

threading.Thread(target=update_index, daemon=True).start()

# --- [API 엔드포인트] ---
@app.route('/scan')
def manual_scan(): threading.Thread(target=perform_full_scan).start(); return "스캔 시작됨"
@app.route('/home')
def get_home(): return jsonify(GLOBAL_CACHE.get("home_recommend", []))

@app.route('/air')
def get_air():
    lt = request.args.get('lite', 'false').lower() == 'true'
    return jsonify(process_data(GLOBAL_CACHE.get("air", []), lt))

@app.route('/movies')
def get_movies():
    lt = request.args.get('lite', 'false').lower() == 'true'
    return jsonify(process_data(GLOBAL_CACHE.get("movies", []), lt))

@app.route('/movies_title')
def get_movies_title():
    lt = request.args.get('lite', 'false').lower() == 'true'
    data = [c for c in GLOBAL_CACHE.get("movies", []) if nfc("제목") in nfc(c.get('path', ''))]
    return jsonify(process_data(data, lt))

@app.route('/movies_uhd')
def get_movies_uhd():
    lt = request.args.get('lite', 'false').lower() == 'true'
    data = [c for c in GLOBAL_CACHE.get("movies", []) if nfc("UHD") in nfc(c.get('path', ''))]
    return jsonify(process_data(data, lt))

@app.route('/movies_latest')
def get_movies_latest():
    lt = request.args.get('lite', 'false').lower() == 'true'
    data = [c for c in GLOBAL_CACHE.get("movies", []) if nfc("최신") in nfc(c.get('path', ''))]
    return jsonify(process_data(data, lt))

@app.route('/animations_all')
def get_animations_all():
    lt = request.args.get('lite', 'false').lower() == 'true'
    return jsonify(process_data(GLOBAL_CACHE.get("animations_all", []), lt))

@app.route('/anim_raftel')
def get_anim_raftel():
    lt = request.args.get('lite', 'false').lower() == 'true'
    data = [c for c in GLOBAL_CACHE.get("animations_all", []) if nfc("라프텔") in nfc(c.get('path', ''))]
    return jsonify(process_data(data, lt))

@app.route('/anim_series')
def get_anim_series():
    lt = request.args.get('lite', 'false').lower() == 'true'
    data = [c for c in GLOBAL_CACHE.get("animations_all", []) if nfc("라프텔") not in nfc(c.get('path', ''))]
    return jsonify(process_data(data, lt))

@app.route('/ftv_us')
def get_ftv_us():
    lt = request.args.get('lite', 'false').lower() == 'true'
    data = [c for c in GLOBAL_CACHE.get("foreigntv", []) if any(k in nfc(c.get('path', '')) for k in ["미국", "미드"])]
    return jsonify(process_data(data, lt))

@app.route('/ftv_cn')
def get_ftv_cn():
    lt = request.args.get('lite', 'false').lower() == 'true'
    data = [c for c in GLOBAL_CACHE.get("foreigntv", []) if any(k in nfc(c.get('path', '')) for k in ["중국", "중드"])]
    return jsonify(process_data(data, lt))

@app.route('/ftv_jp')
def get_ftv_jp():
    lt = request.args.get('lite', 'false').lower() == 'true'
    data = [c for c in GLOBAL_CACHE.get("foreigntv", []) if any(k in nfc(c.get('path', '')) for k in ["일본", "일드"])]
    return jsonify(process_data(data, lt))

@app.route('/ftv_docu')
def get_ftv_docu():
    lt = request.args.get('lite', 'false').lower() == 'true'
    data = [c for c in GLOBAL_CACHE.get("foreigntv", []) if nfc("다큐") in nfc(c.get('path', ''))]
    return jsonify(process_data(data, lt))

@app.route('/ftv_etc')
def get_ftv_etc():
    lt = request.args.get('lite', 'false').lower() == 'true'
    exclude = ["미국", "미드", "중국", "중드", "일본", "일드", "다큐"]
    data = [c for c in GLOBAL_CACHE.get("foreigntv", []) if not any(nfc(ex) in nfc(c.get('path', '')) for ex in exclude)]
    return jsonify(process_data(data, lt))

@app.route('/ktv_drama')
def get_ktv_drama():
    lt = request.args.get('lite', 'false').lower() == 'true'
    data = [c for c in GLOBAL_CACHE.get("koreantv", []) if nfc("드라마") in nfc(c.get('path', ''))]
    return jsonify(process_data(data, lt))

@app.route('/ktv_sitcom')
def get_ktv_sitcom():
    lt = request.args.get('lite', 'false').lower() == 'true'
    data = [c for c in GLOBAL_CACHE.get("koreantv", []) if nfc("시트콤") in nfc(c.get('path', ''))]
    return jsonify(process_data(data, lt))

@app.route('/ktv_variety')
def get_ktv_variety():
    lt = request.args.get('lite', 'false').lower() == 'true'
    data = [c for c in GLOBAL_CACHE.get("koreantv", []) if nfc("예능") in nfc(c.get('path', ''))]
    return jsonify(process_data(data, lt))

@app.route('/ktv_edu')
def get_ktv_edu():
    lt = request.args.get('lite', 'false').lower() == 'true'
    data = [c for c in GLOBAL_CACHE.get("koreantv", []) if nfc("교양") in nfc(c.get('path', ''))]
    return jsonify(process_data(data, lt))

@app.route('/ktv_docu')
def get_ktv_docu():
    lt = request.args.get('lite', 'false').lower() == 'true'
    data = [c for c in GLOBAL_CACHE.get("koreantv", []) if nfc("다큐") in nfc(c.get('path', ''))]
    return jsonify(process_data(data, lt))

@app.route('/list')
def get_list():
    try:
        q = nfc(request.args.get('path', ''))
        if not q: return jsonify([])
        parts = q.split('/', 1)
        root_name, sub_path = parts[0], parts[1] if len(parts) > 1 else ""
        root_map = {"방송중": AIR_DIR, "애니메이션": ANI_DIR, "영화": MOVIES_ROOT_DIR, "외국TV": FOREIGN_TV_DIR, "국내TV": KOREAN_TV_DIR}
        pm = {"방송중": "air", "애니메이션": "anim_all", "영화": "movie", "외국TV": "ftv", "국내TV": "ktv"}
        base = get_real_path(root_map.get(root_name, ""))
        if not base: return jsonify([])
        target = get_real_path(os.path.normpath(os.path.join(base, sub_path.lstrip('/'))))
        if not os.path.exists(target): return jsonify([])

        if os.path.isdir(target):
            entries = sorted(os.listdir(target))
            sub_dirs = [nfc(n) for n in entries if os.path.isdir(os.path.join(target, n)) and not is_excluded(os.path.join(target, n))]
            if sub_dirs:
                res = [attach_tmdb_info({"name": d, "path": nfc(os.path.relpath(os.path.join(target, d), base)), "movies": [], "genreIds": [], "posterPath": None}) for d in sub_dirs]
                return jsonify(res)
            return jsonify(scan_recursive(target, pm.get(root_name, "movie"), rel_base=base))
        return jsonify([])
    except: return jsonify([])

@app.route('/video_serve')
def serve_video():
    try:
        t, path_arg = request.args.get('type'), request.args.get('path')
        path = urllib.parse.unquote(path_arg).replace('+', ' ')
        base_map = {"movie": MOVIES_ROOT_DIR, "ftv": FOREIGN_TV_DIR, "ktv": KOREAN_TV_DIR, "anim_all": ANI_DIR, "air": AIR_DIR}
        actual_path = get_real_path(os.path.join(base_map.get(t, AIR_DIR), path.lstrip('/')))
        return send_file(actual_path, conditional=True)
    except: return "Error", 500

@app.route('/thumb_serve')
def thumb_serve():
    t, tid, p_arg = request.args.get('type'), request.args.get('id'), request.args.get('path')
    thumb_path = os.path.join(DATA_DIR, tid)
    if os.path.exists(thumb_path): return send_from_directory(DATA_DIR, tid)
    try:
        path = urllib.parse.unquote(p_arg).replace('+', ' ')
        base_map = {"movie": MOVIES_ROOT_DIR, "ftv": FOREIGN_TV_DIR, "ktv": KOREAN_TV_DIR, "anim_all": ANI_DIR, "air": AIR_DIR}
        fp = get_real_path(os.path.join(base_map.get(t, AIR_DIR), path.lstrip('/')))
        subprocess.run([FFMPEG_PATH, '-ss', '00:03:00', '-i', fp, '-vframes', '1', '-q:v', '5', thumb_path, '-y'], timeout=15)
        return send_from_directory(DATA_DIR, tid)
    except: return "Not Found", 404

if __name__ == '__main__':
    app.run(host='0.0.0.0', port=5000, threaded=True)
