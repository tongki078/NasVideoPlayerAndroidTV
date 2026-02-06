import os, subprocess, hashlib, urllib.parse, unicodedata, threading, time, json, re, sys, traceback, shutil, requests, random, mimetypes
from flask import Flask, jsonify, send_from_directory, request, Response, redirect, send_file
from flask_cors import CORS

app = Flask(__name__)
CORS(app)

# MIME 타입 추가 등록 (NAS 환경 및 안드로이드 TV 대응)
if not mimetypes.types_map.get('.mkv'): mimetypes.add_type('video/x-matroska', '.mkv')
if not mimetypes.types_map.get('.ts'): mimetypes.add_type('video/mp2t', '.ts')
if not mimetypes.types_map.get('.tp'): mimetypes.add_type('video/mp2t', '.tp')

# --- [1. 설정 및 경로] ---
MY_IP = "192.168.0.2"
DATA_DIR = "/volume2/video/thumbnails"
CACHE_FILE = "/volume2/video/video_cache.json"
HLS_ROOT = "/dev/shm/videoplayer_hls"
CACHE_VERSION = "8.2"

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

def nfc(text): return unicodedata.normalize('NFC', text) if text else ""
def nfd(text): return unicodedata.normalize('NFD', text) if text else ""

REGEX_EXT = re.compile(r'\.[a-zA-Z0-9]{2,4}$')
REGEX_HANGUL_ALPHA = re.compile(r'([가-힣])([a-zA-Z0-9])')
REGEX_ALPHA_HANGUL = re.compile(r'([a-zA-Z0-9])([가-힣])')
REGEX_START_NUM = re.compile(r'^\d+[.\s_-]+')
REGEX_START_PREFIX = re.compile(r'^[a-zA-Z]\d+[.\s_-]+')
REGEX_BRACKET_NUM = re.compile(r'^\[\d+\]\s*')
REGEX_YEAR = re.compile(r'\((19|20)\d{2}\)|(?<!\d)(19|20)\d{2}(?!\d)')
REGEX_BRACKETS = re.compile(r'\[.*?\]|\(.*?\)')
REGEX_TAGS = re.compile(r'(?i)[.\s_](?:더빙|자막|무삭제|\d{3,4}p|WEB-DL|WEBRip|Bluray|HDRip|BDRip|DVDRip|H\.?26[45]|x26[45]|HEVC|AAC|DTS|AC3|DDP|Dual|Atmos|REPACK|10bit|REMUX|OVA|OAD|ONA|TV판|극장판|FLAC|xvid|DivX|MKV|MP4|AVI|속편|1부|2부|파트|완결|상|하).*')
REGEX_SPECIAL_CHARS = re.compile(r'[._\-::!?【】『』「」"\'#@*※]')
REGEX_SPACES = re.compile(r'\s+')
REGEX_EP_SUFFIX = re.compile(r'(?i)[.\s_](?:S\d+E\d+|S\d+|E\d+|\d+\s*(?:화|회|기)|Season\s*\d+|Part\s*\d+).*')

def clean_title_complex(title):
    if not title: return ""
    cleaned = REGEX_EXT.sub('', title)
    cleaned = REGEX_HANGUL_ALPHA.sub(r'\1 \2', cleaned)
    cleaned = REGEX_ALPHA_HANGUL.sub(r'\1 \2', cleaned)
    cleaned = REGEX_START_NUM.sub('', cleaned)
    cleaned = REGEX_START_PREFIX.sub('', cleaned)
    cleaned = REGEX_BRACKET_NUM.sub('', cleaned)
    ym = REGEX_YEAR.search(cleaned)
    if ym: cleaned = cleaned.replace(ym.group(), ' ')
    cleaned = REGEX_BRACKETS.sub(' ', cleaned)
    cleaned = REGEX_EP_SUFFIX.sub('', cleaned)
    cleaned = REGEX_TAGS.sub(' ', cleaned)
    cleaned = REGEX_SPECIAL_CHARS.sub(' ', cleaned)
    return REGEX_SPACES.sub(' ', cleaned).strip()

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

# --- [TMDB 및 메타데이터] ---
def get_tmdb_info_server(title):
    if title in GLOBAL_CACHE["tmdb"]: return GLOBAL_CACHE["tmdb"][title]
    ct = clean_title_complex(title)
    if not ct: return {"genreIds": [], "posterPath": None}
    try:
        headers = {"Authorization": f"Bearer {TMDB_API_KEY}"}
        resp = requests.get(f"{TMDB_BASE_URL}/search/multi", params={"query": ct, "language": "ko-KR"}, headers=headers, timeout=5)
        data = resp.json()
        if data.get('results'):
            res = [r for r in data['results'] if r.get('media_type') != 'person']
            if res:
                best = res[0]
                info = {"genreIds": best.get('genre_ids', []), "posterPath": best.get('poster_path'), "tmdbId": best.get('id')}
                GLOBAL_CACHE["tmdb"][title] = info
                return info
    except: pass
    return {"genreIds": [], "posterPath": None}

def attach_tmdb_info(cat):
    name = cat.get('name')
    if name and name in GLOBAL_CACHE["tmdb"]:
        info = GLOBAL_CACHE["tmdb"][name]
        cat['genreIds'], cat['posterPath'] = info.get('genreIds', []), info.get('posterPath')
    return cat

def fetch_metadata_async():
    print("[METADATA] 시작...", flush=True)
    for k in ["foreigntv", "koreantv", "air", "animations_all", "movies"]:
        items = GLOBAL_CACHE.get(k, [])
        for cat in items:
            info = get_tmdb_info_server(cat['name'])
            cat['genreIds'], cat['posterPath'] = info.get("genreIds", []), info.get("posterPath")
    build_home_recommend(); save_cache()
    print("[METADATA] 완료.", flush=True)

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
    return {
        "id": tid,
        "title": os.path.basename(fp),
        "videoUrl": f"/video_serve?type={prefix}&path={urllib.parse.quote(rel)}",
        "thumbnailUrl": f"/thumb_serve?type={prefix}&id={tid}&path={urllib.parse.quote(rel)}"
    }

def scan_recursive(bp, prefix, rb=None):
    cats = []
    exts = ('.mp4', '.mkv', '.avi', '.wmv', '.flv', '.ts', '.tp', '.m4v', '.m2ts', '.mov')
    p, rel_base = get_real_path(bp), get_real_path(rb) if rb else get_real_path(bp)

    print(f"[SCAN] >>> 스캔 진입: {p}", flush=True)
    if not os.path.exists(p):
        print(f"[SCAN] 경로 없음: {p}", flush=True)
        return cats

    all_f = []
    found_in_scan = 0
    for root, dirs, files in os.walk(p):
        dirs[:] = [d for d in dirs if not is_excluded(os.path.join(root, d))]
        if is_excluded(root): continue
        for f in files:
            if f.lower().endswith(exts):
                all_f.append(os.path.join(root, f))
                found_in_scan += 1
                if found_in_scan % 1000 == 0:
                    print(f"[SCAN] 파일 찾는 중... 현재 {found_in_scan}개 발견", flush=True)

    print(f"[SCAN] 총 {len(all_f)}개 파일 처리 시작...", flush=True)
    all_f.sort()

    curr, movies = "", []
    count = 0
    total = len(all_f)

    for fp in all_f:
        count += 1
        dp = os.path.dirname(fp)
        if dp != curr:
            if movies:
                rel_path = nfc(os.path.relpath(curr, rel_base))
                cat = {"name": nfc(os.path.basename(curr)), "movies": movies, "path": rel_path}
                cats.append(attach_tmdb_info(cat))
            curr, movies = dp, []
        movies.append(get_movie_info(fp, rel_base, prefix))

    if movies:
        rel_path = nfc(os.path.relpath(curr, rel_base))
        cat = {"name": nfc(os.path.basename(curr)), "movies": movies, "path": rel_path}
        cats.append(attach_tmdb_info(cat))

    print(f"[SCAN] <<< 스캔 종료: {p} ({len(cats)} 폴더)", flush=True)
    return cats

def is_excluded(path):
    n = os.path.basename(path)
    return any(ex in n for ex in EXCLUDE_FOLDERS) or n.startswith('.')

def process_data(data, lite=False):
    if lite: return [{"name": c.get('name',''), "path": c.get('path',''), "movies": [], "genreIds": c.get('genreIds', []), "posterPath": c.get('posterPath')} for c in data]
    return data

def perform_full_scan():
    print(f"🔄 전체 인덱싱 시작 (버전 {CACHE_VERSION})", flush=True)
    t = [
        ("foreigntv", FOREIGN_TV_DIR, "ftv"),
        ("koreantv", KOREAN_TV_DIR, "ktv"),
        ("air", AIR_DIR, "air"),
        ("animations_all", ANI_DIR, "anim_all"),
        ("movies", MOVIES_ROOT_DIR, "movie")
    ]
    for k, p, pr in t:
        print(f"[{k}] 스캔 시작...", flush=True)
        try:
            res = scan_recursive(p, pr)
            GLOBAL_CACHE[k] = res
            save_cache()
        except:
            print(f"[{k}] 오류 발생:\n{traceback.format_exc()}", flush=True)

    print("✅ 모든 스캔 완료. 메타데이터 업데이트 스레드 구동.", flush=True)
    build_home_recommend(); save_cache(); threading.Thread(target=fetch_metadata_async, daemon=True).start()

def load_cache():
    if os.path.exists(CACHE_FILE):
        try:
            with open(CACHE_FILE, 'r', encoding='utf-8') as f:
                d = json.load(f)
                if d.get("version") == CACHE_VERSION:
                    GLOBAL_CACHE.update(d)
                    print(f"✅ [CACHE] JSON 캐시 파일을 성공적으로 로드했습니다. (버전: {CACHE_VERSION})", flush=True)
                    return True
                else:
                    print(f"⚠️ [CACHE] 캐시 버전 불일치 (파일: {d.get('version')}, 현재: {CACHE_VERSION})", flush=True)
        except Exception as e:
            print(f"❌ [CACHE] 로드 중 오류: {e}", flush=True)
    return False

def save_cache():
    try:
        with open(CACHE_FILE, 'w', encoding='utf-8') as f: json.dump(GLOBAL_CACHE, f, ensure_ascii=False)
    except: pass

def init_server():
    print(f"🚀 서버 초기화 (v{CACHE_VERSION})", flush=True)
    loaded = load_cache()

    # 탭 필터링 등에 필요한 메타데이터 업데이트 스레드 실행
    threading.Thread(target=fetch_metadata_async, daemon=True).start()

    # 인덱싱 이어가기 로직
    def background_resume():
        targets = [
            ("foreigntv", FOREIGN_TV_DIR, "ftv"),
            ("koreantv", KOREAN_TV_DIR, "ktv"),
            ("air", AIR_DIR, "air"),
            ("animations_all", ANI_DIR, "anim_all"),
            ("movies", MOVIES_ROOT_DIR, "movie")
        ]

        # 버전이 다르거나 로드 실패했다면 전체 스캔
        if not loaded:
            perform_full_scan()
            return

        # 버전은 같지만 데이터가 비어있는 카테고리만 골라서 스캔 (Resume)
        for k, p, pr in targets:
            if not GLOBAL_CACHE.get(k):
                print(f"[{k}] 누락된 인덱싱 발견. 스캔 시작...", flush=True)
                try:
                    res = scan_recursive(p, pr)
                    GLOBAL_CACHE[k] = res
                    save_cache()
                except: pass
        print("✅ 모든 누락된 인덱싱 작업 완료.", flush=True)

    threading.Thread(target=background_resume, daemon=True).start()

threading.Thread(target=init_server, daemon=True).start()

# --- [API] ---
@app.route('/scan')
def manual_scan(): threading.Thread(target=perform_full_scan).start(); return "스캔 시작"
@app.route('/home')
def get_home(): return jsonify(GLOBAL_CACHE.get("home_recommend", []))
@app.route('/air')
def get_air(): return jsonify(process_data(GLOBAL_CACHE.get("air", []), request.args.get('lite') == 'true'))
@app.route('/movies')
def get_movies(): return jsonify(process_data(GLOBAL_CACHE.get("movies", []), request.args.get('lite') == 'true'))
@app.route('/movies_latest')
def get_movies_latest(): return jsonify(process_data(GLOBAL_CACHE.get("movies", []), request.args.get('lite') == 'true'))
@app.route('/animations_all')
def get_animations_all(): return jsonify(process_data(GLOBAL_CACHE.get("animations_all", []), request.args.get('lite') == 'true'))

def filter_by_path(pool_key, keyword):
    target = nfc(keyword).replace(" ", "").lower()
    pool = GLOBAL_CACHE.get(pool_key, [])
    return [
        c for c in pool
        if nfc(c.get('path', '')).replace(" ", "").lower().startswith(target)
    ]

@app.route('/foreigntv')
def get_foreigntv(): return jsonify(process_data(GLOBAL_CACHE.get("foreigntv", []), request.args.get('lite') == 'true'))
@app.route('/ftv_us')
def get_ftv_us(): return jsonify(process_data(filter_by_path("foreigntv", "미국 드라마"), request.args.get('lite') == 'true'))
@app.route('/koreantv')
def get_koreantv(): return jsonify(process_data(GLOBAL_CACHE.get("koreantv", []), request.args.get('lite') == 'true'))

@app.route('/search')
def search_videos():
    q = request.args.get('q', '').lower()
    pool = GLOBAL_CACHE['movies'] + GLOBAL_CACHE['animations_all'] + GLOBAL_CACHE['foreigntv'] + GLOBAL_CACHE['koreantv'] + GLOBAL_CACHE['air']
    res = []
    for cat in pool:
        if q in cat['name'].lower(): res.append(cat)
        else:
            fm = [m for m in cat.get('movies', []) if q in m['title'].lower()]
            if fm:
                nc = cat.copy(); nc['movies'] = fm; res.append(nc)
    return jsonify(res)

@app.route('/list')
def get_list():
    try:
        p = nfc(request.args.get('path', ''))
        if not p: return jsonify([])
        parts = p.split('/', 1)
        rn, sub = parts[0], parts[1] if len(parts) > 1 else ""
        rmap = {"방송중": AIR_DIR, "애니메이션": ANI_DIR, "영화": MOVIES_ROOT_DIR, "외국TV": FOREIGN_TV_DIR, "국내TV": KOREAN_TV_DIR}
        pm = {"방송중": "air", "애니메이션": "anim_all", "영화": "movie", "외국TV": "ftv", "국내TV": "ktv"}
        base = get_real_path(rmap.get(rn, ""))
        target = get_real_path(os.path.join(base, sub.lstrip('/')))
        if not os.path.exists(target): return jsonify([])
        if os.path.isdir(target):
            subs = [nfc(n) for n in sorted(os.listdir(target)) if os.path.isdir(os.path.join(target, n)) and not is_excluded(os.path.join(target, n))]
            if subs: return jsonify([attach_tmdb_info({"name": d, "path": nfc(os.path.relpath(os.path.join(target, d), base)), "movies": []}) for d in subs])
            return jsonify(scan_recursive(target, pm.get(rn, "movie"), rb=base))
    except: pass
    return jsonify([])

@app.route('/video_serve')
def serve_video():
    try:
        t, p = request.args.get('type'), request.args.get('path')
        if not p: return "Path missing", 400
        bmap = {"movie": MOVIES_ROOT_DIR, "ftv": FOREIGN_TV_DIR, "ktv": KOREAN_TV_DIR, "anim_all": ANI_DIR, "air": AIR_DIR}
        base_dir = bmap.get(t, AIR_DIR)
        fp = get_real_path(os.path.join(base_dir, p.lstrip('/')))

        mime_type, _ = mimetypes.guess_type(fp)
        if not mime_type:
            if fp.lower().endswith('.mkv'): mime_type = 'video/x-matroska'
            elif fp.lower().endswith(('.ts', '.tp')): mime_type = 'video/mp2t'
            else: mime_type = 'video/mp4'

        print(f"🎬 [VIDEO] 재생 시도: {fp} ({mime_type})", flush=True)
        return send_file(fp, mimetype=mime_type, conditional=True)
    except Exception as e:
        print(f"❌ [VIDEO] 에러 발생: {e}", flush=True)
        traceback.print_exc()
        return "Internal Server Error", 500

@app.route('/thumb_serve')
def thumb_serve():
    try:
        t, tid, p = request.args.get('type'), request.args.get('id'), request.args.get('path')
        tp = os.path.join(DATA_DIR, tid)
        if os.path.exists(tp): return send_from_directory(DATA_DIR, tid)
        bmap = {"movie": MOVIES_ROOT_DIR, "ftv": FOREIGN_TV_DIR, "ktv": KOREAN_TV_DIR, "anim_all": ANI_DIR, "air": AIR_DIR}
        fp = get_real_path(os.path.join(bmap.get(t, AIR_DIR), p.lstrip('/')))
        subprocess.run([FFMPEG_PATH, '-ss', '00:03:00', '-i', fp, '-vframes', '1', '-q:v', '5', tp, '-y'], timeout=15)
        return send_from_directory(DATA_DIR, tid)
    except: return "Not Found", 404

if __name__ == '__main__':
    app.run(host='0.0.0.0', port=5000, threaded=True)
