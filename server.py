import os, subprocess, hashlib, urllib.parse, unicodedata, threading, time, json, re, sys, traceback, shutil, requests, random, mimetypes
from flask import Flask, jsonify, send_from_directory, request, Response, redirect, send_file
from flask_cors import CORS
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime
from collections import deque

app = Flask(__name__)
CORS(app)

# MIME 타입 추가 등록 (재생 관련 문제 방지)
if not mimetypes.types_map.get('.mkv'): mimetypes.add_type('video/x-matroska', '.mkv')
if not mimetypes.types_map.get('.ts'): mimetypes.add_type('video/mp2t', '.ts')
if not mimetypes.types_map.get('.tp'): mimetypes.add_type('video/mp2t', '.tp')

# --- [1. 설정 및 경로] ---
MY_IP = "192.168.0.2"
DATA_DIR = "/volume2/video/thumbnails"
CACHE_FILE = "/volume2/video/video_cache.json"
TMDB_CACHE_DIR = "/volume2/video/tmdb_cache"
HLS_ROOT = "/dev/shm/videoplayer_hls"
CACHE_VERSION = "9.7" # 기존 버전 유지

# TMDB 관련 전역 메모리 캐시 (추가)
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
REGEX_FORBIDDEN_TITLE = re.compile(r'(?i)^\s*(Season\s*\d+|Part\s*\d+|EP\s*\d+|\d+화|\d+회|\d+기|시즌\s*\d+|S\d+|E\d+|Disk\s*\d+|Disc\s*\d+|CD\s*\d+|Specials?|Extras?|Bonus|미분류|기타|새\s*폴더|VIDEO|GDS3|GDRIVE|NAS|share)\s*$', re.I)

def natural_sort_key(s):
    if s is None: return []
    return [int(text) if text.isdigit() else text.lower() for text in re.split(r'(\d+)', nfc(str(s)))]

def clean_title_complex(title):
    if not title: return "", None
    title = nfc(title)
    # [3대 원칙 준수 - 추가 부분] 제목 앞의 숫자 인덱스 제거 로직 보강 (0, 1, 2 등 모든 숫자 대응)
    title = re.sub(r'^\d+\s+', '', title)

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

# --- [추가 유틸리티] ---
def load_tmdb_memory_cache():
    """서버 시작 시 TMDB 캐시를 메모리로 로드"""
    if not os.path.exists(TMDB_CACHE_DIR): return
    for f in os.listdir(TMDB_CACHE_DIR):
        if f.endswith(".json"):
            try:
                with open(os.path.join(TMDB_CACHE_DIR, f), 'r', encoding='utf-8') as file:
                    data = json.load(file)
                    if not data.get('failed'): TMDB_MEMORY_CACHE[f.replace(".json", "")] = data
            except: pass

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

# --- [메모리 실시간 통합 로직 - 3대 원칙 준수 추가] ---
def merge_folders_to_series_in_memory(items):
    if not items: return []
    merged = {}
    for item in items:
        # 통합 키 생성 시 제목 앞 숫자 제거 규칙 적용
        raw_name = item.get('name', 'Unknown')
        pure_name, _ = clean_title_complex(raw_name)
        if not pure_name: pure_name = raw_name

        if pure_name not in merged:
            merged[pure_name] = item.copy()
            merged[pure_name]['name'] = pure_name
            if 'movies' not in merged[pure_name]: merged[pure_name]['movies'] = []
        else:
            if 'movies' in item and item['movies']:
                merged[pure_name]['movies'].extend(item['movies'])
            if not merged[pure_name].get('posterPath') and item.get('posterPath'):
                merged[pure_name].update({k: v for k, v in item.items() if k != 'movies' and k != 'path'})

    result = list(merged.values())
    for r in result:
        if 'movies' in r:
            seen_ids = set()
            unique_movies = []
            for m in r['movies']:
                if m['id'] not in seen_ids:
                    unique_movies.append(m)
                    seen_ids.add(m['id'])
            r['movies'] = sorted(unique_movies, key=lambda x: natural_sort_key(x.get('title', '')))
    return sorted(result, key=lambda x: natural_sort_key(x.get('name', '')))

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

# --- [스캔 및 탐색] ---
def scan_recursive(bp, prefix, display_name=None):
    series_map = {} # rel_path -> series_obj
    base = nfc(get_real_path(bp)); exts = VIDEO_EXTS; all_files = []
    stack = [base]
    while stack:
        curr = stack.pop()
        try:
            with os.scandir(curr) as it:
                # 스캔 시에도 이름순 정렬
                entries = sorted(list(it), key=lambda e: natural_sort_key(e.name))
                for entry in entries:
                    if entry.is_dir():
                        if not any(ex in entry.name for ex in EXCLUDE_FOLDERS) and not entry.name.startswith('.'): stack.append(entry.path)
                    elif entry.is_file() and entry.name.lower().endswith(exts): all_files.append(nfc(entry.path))
        except: pass
    all_files.sort(key=natural_sort_key)
    for fp in all_files:
        dp = nfc(os.path.dirname(fp)); rel_path = get_series_root_path(dp, base)
        if rel_path not in series_map:
            name = get_meaningful_name(dp); full_path = display_name if rel_path == "." else f"{display_name}/{rel_path}"
            series_map[rel_path] = {"name": name, "path": full_path, "movies": [], "genreIds": [], "posterPath": None}
        movie_id = hashlib.md5(fp.encode()).hexdigest()
        series_map[rel_path]["movies"].append({"id": movie_id, "title": os.path.basename(fp), "videoUrl": f"/video_serve?type={prefix}&path={urllib.parse.quote(os.path.relpath(fp, base))}", "thumbnailUrl": f"/thumb_serve?type={prefix}&id={movie_id}&path={urllib.parse.quote(os.path.relpath(fp, base))}"})
    return list(series_map.values())

def fetch_metadata_async():
    log("🚀 [METADATA] 시작")
    for k in ["animations_all", "foreigntv", "koreantv", "movies", "air"]:
        for cat in GLOBAL_CACHE.get(k, []):
            if not cat.get('posterPath') and not cat.get('failed'):
                cat.update(get_tmdb_info_server(cat['name']))
                save_cache(); time.sleep(0.1)
    log("🏁 [METADATA] 완료")

def build_home_recommend():
    m, a, k, f = GLOBAL_CACHE["movies"], GLOBAL_CACHE["animations_all"], GLOBAL_CACHE["koreantv"], GLOBAL_CACHE["foreigntv"]
    all_p = list(m + a + k + f); random.shuffle(all_p)
    GLOBAL_CACHE["home_recommend"] = [{"title": "지금 가장 핫한 인기작", "items": all_p[:20]}, {"title": "방금 올라온 최신 영화", "items": m[:20]}, {"title": "지금 인기 있는 시리즈", "items": (k + f)[:20]}, {"title": "추천 애니메이션", "items": a[:20]}]

def perform_full_scan():
    log("🔄 NAS 전역 스캔 시작 (백그라운드)")
    for label, cache_key in [("애니메이션", "animations_all"), ("외국TV", "foreigntv"), ("국내TV", "koreantv"), ("영화", "movies"), ("방송중", "air")]:
        path, prefix = PATH_MAP[label]
        GLOBAL_CACHE[cache_key] = scan_recursive(path, prefix, display_name=label)
    # 스캔 직후 메모리 통합 한 번 더 수행
    for k in ["foreigntv", "koreantv", "animations_all"]:
        GLOBAL_CACHE[k] = merge_folders_to_series_in_memory(GLOBAL_CACHE[k])
    build_home_recommend(); save_cache()
    threading.Thread(target=fetch_metadata_async, daemon=True).start()

def save_cache():
    try:
        with open(CACHE_FILE, 'w', encoding='utf-8') as f: json.dump(GLOBAL_CACHE, f, ensure_ascii=False)
    except: pass

def load_cache():
    if not os.path.exists(CACHE_FILE): return False
    try:
        with open(CACHE_FILE, 'r', encoding='utf-8') as f:
            d = json.load(f)
            if d.get("version") == CACHE_VERSION:
                GLOBAL_CACHE.update(d)
                # 캐시 로드 후 메모리 실시간 통합 수행 (핵심!)
                log("🧠 9.7 캐시 로드 완료. 메모리 실시간 통합 중...")
                for k in ["foreigntv", "koreantv", "animations_all"]: GLOBAL_CACHE[k] = merge_folders_to_series_in_memory(GLOBAL_CACHE[k])
                return True
    except: pass
    return False

@app.route('/home')
def get_home(): return jsonify(GLOBAL_CACHE.get("home_recommend", []))

def process_api(data, filter_keyword=None):
    pool = data
    if filter_keyword:
        synonyms = {"미국": ["미국", "미드", "us"], "중국": ["중국", "중드", "cn"], "일본": ["일본", "일드", "jp"], "기타": ["기타", "etc"], "다큐": ["다큐", "docu"], "드라마": ["드라마"], "시트콤": ["시트콤"], "예능": ["예능"], "교양": ["교양"]}
        targets = [nfc(t).lower() for t in synonyms.get(filter_keyword, [filter_keyword])]
        pool = [c for c in data if any(t in nfc(c.get('path', '')).lower() or t in nfc(c.get('name', '')).lower() for t in targets)]
    limit = request.args.get('limit', type=int, default=5000); offset = request.args.get('offset', type=int, default=0)
    res = pool[offset:offset+limit]
    if request.args.get('lite') == 'true':
        return [{"name": c.get('name'), "path": c.get('path'), "genreIds": c.get('genreIds'), "posterPath": c.get('posterPath'), "year": c.get('year'), "overview": c.get('overview'), "rating": c.get('rating'), "seasonCount": c.get('seasonCount'), "failed": c.get('failed')} for c in res]
    return res

@app.route('/foreigntv')
def get_ftv(): return jsonify(process_api(GLOBAL_CACHE["foreigntv"]))
@app.route('/ftv_us')
def get_ftv_us(): return jsonify(process_api(GLOBAL_CACHE["foreigntv"], "미국"))
@app.route('/ftv_cn')
def get_ftv_cn(): return jsonify(process_api(GLOBAL_CACHE["foreigntv"], "중국"))
@app.route('/ftv_jp')
def get_ftv_jp(): return jsonify(process_api(GLOBAL_CACHE["foreigntv"], "일본"))
@app.route('/ftv_docu')
def get_ftv_docu(): return jsonify(process_api(GLOBAL_CACHE["foreigntv"], "다큐"))
@app.route('/ftv_etc')
def get_ftv_etc(): return jsonify(process_api(GLOBAL_CACHE["foreigntv"], "기타"))

@app.route('/koreantv')
def get_ktv(): return jsonify(process_api(GLOBAL_CACHE["koreantv"]))
@app.route('/ktv_drama')
def get_ktv_drama(): return jsonify(process_api(GLOBAL_CACHE["koreantv"], "드라마"))
@app.route('/ktv_sitcom')
def get_ktv_sitcom(): return jsonify(process_api(GLOBAL_CACHE["koreantv"], "시트콤"))
@app.route('/ktv_variety')
def get_ktv_variety(): return jsonify(process_api(GLOBAL_CACHE["koreantv"], "예능"))
@app.route('/ktv_edu')
def get_ktv_edu(): return jsonify(process_api(GLOBAL_CACHE["koreantv"], "교양"))
@app.route('/ktv_docu')
def get_ktv_docu(): return jsonify(process_api(GLOBAL_CACHE["koreantv"], "다큐멘터리"))

@app.route('/animations_all')
def get_anim(): return jsonify(process_api(GLOBAL_CACHE["animations_all"]))
@app.route('/anim_raftel')
def get_anim_r(): return jsonify(process_api(GLOBAL_CACHE["animations_all"], "라프텔"))
@app.route('/anim_series')
def get_anim_s(): return jsonify(process_api(GLOBAL_CACHE["animations_all"], "시리즈"))

@app.route('/movies')
@app.route('/movies_all')
@app.route('/movies_latest')
@app.route('/movies_uhd')
@app.route('/movies_title')
def get_movies():
    pool = GLOBAL_CACHE["movies"]
    if "uhd" in request.path: pool = [c for c in pool if "uhd" in (c.get('path') or "").lower() or "4k" in (c.get('path') or "").lower()]
    elif "title" in request.path: pool = sorted(pool, key=lambda x: natural_sort_key(x.get('name', '')))
    return jsonify(process_api(pool))

@app.route('/search')
def search_videos():
    q = request.args.get('q', '').lower(); res = []
    for k in ["movies", "animations_all", "foreigntv", "koreantv", "air"]:
        for cat in GLOBAL_CACHE.get(k, []):
            if q in cat['name'].lower() or q in cat.get('path','').lower(): res.append(cat.copy())
    return jsonify(process_api(res))

@app.route('/list')
def get_list():
    path = request.args.get('path'); real_path, type_code = resolve_nas_path(path)
    if not real_path or not os.path.exists(real_path): return jsonify([])
    cat_prefix = nfc(path.split('/')[0]); base_dir = PATH_MAP[cat_prefix][0]; res, movies = [], []
    try:
        for entry in sorted(os.listdir(real_path), key=natural_sort_key):
            fe = os.path.join(real_path, entry)
            if os.path.isdir(fe) and not any(ex in entry for ex in EXCLUDE_FOLDERS):
                item = {"name": nfc(entry), "path": f"{cat_prefix}/{nfc(os.path.relpath(fe, base_dir))}"}
                h = hashlib.md5(nfc(get_meaningful_name(fe)).encode()).hexdigest(); item.update(TMDB_MEMORY_CACHE.get(h, {}))
                res.append(item)
            elif entry.lower().endswith(VIDEO_EXTS):
                movie_id = hashlib.md5(fe.encode()).hexdigest()
                movies.append({"id": movie_id, "title": entry, "videoUrl": f"/video_serve?type={type_code}&path={urllib.parse.quote(os.path.relpath(fe, base_dir))}", "thumbnailUrl": f"/thumb_serve?type={type_code}&id={movie_id}&path={urllib.parse.quote(os.path.relpath(fe, base_dir))}"})
    except: pass
    if movies: res.append({"name": os.path.basename(real_path), "path": path, "movies": sorted(movies, key=lambda x: natural_sort_key(x['title']))})
    return jsonify(res)

@app.route('/video_serve')
def video_serve():
    path, prefix = request.args.get('path'), request.args.get('type')
    try:
        base = next(v[0] for k, v in PATH_MAP.items() if v[1] == prefix)
        return send_file(get_real_path(os.path.join(base, nfc(urllib.parse.unquote(path)))), conditional=True)
    except: return "Not Found", 404

@app.route('/thumb_serve')
def thumb_serve():
    path, prefix, tid = request.args.get('path'), request.args.get('type'), request.args.get('id')
    try:
        base = next(v[0] for k, v in PATH_MAP.items() if v[1] == prefix); vp = get_real_path(os.path.join(base, nfc(urllib.parse.unquote(path))))
        if os.path.isdir(vp):
            fs = sorted([f for f in os.listdir(vp) if f.lower().endswith(VIDEO_EXTS)]); vp = os.path.join(vp, fs[0]) if fs else vp
        tp = os.path.join(DATA_DIR, f"seek_300_{tid}")
        if not os.path.exists(tp): subprocess.run([FFMPEG_PATH, "-y", "-ss", "300", "-i", vp, "-vframes", "1", "-q:v", "5", "-vf", "scale=320:-1", tp], timeout=10)
        return send_file(tp, mimetype='image/jpeg') if os.path.exists(tp) else ("Not Found", 404)
    except: return "Not Found", 404

if __name__ == '__main__':
    log(f"📺 NAS Server v{CACHE_VERSION} 시작 (9.7 캐시 보존 모드)")
    load_tmdb_memory_cache()
    if not load_cache(): perform_full_scan()
    else: log("✅ 기존 9.7 캐시 로드 및 메모리 통합 완료")
    app.run(host='0.0.0.0', port=5000, threaded=True)
