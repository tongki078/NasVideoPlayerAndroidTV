import os, subprocess, hashlib, urllib.parse, unicodedata, threading, time, json, re, sys, traceback, shutil
from flask import Flask, jsonify, send_from_directory, request, Response, redirect, send_file
from flask_cors import CORS

app = Flask(__name__)
CORS(app)

# --- [1. 설정 및 경로] ---
MY_IP = "192.168.0.2"
DATA_DIR = "/volume2/video/thumbnails"
CACHE_FILE = "/volume2/video/video_cache.json"
HLS_ROOT = "/dev/shm/videoplayer_hls"
CACHE_VERSION = "1.7"

os.makedirs(DATA_DIR, exist_ok=True)
if os.path.exists(HLS_ROOT): shutil.rmtree(HLS_ROOT, ignore_errors=True)
os.makedirs(HLS_ROOT, exist_ok=True)

# 실제 NAS 경로 설정
FOREIGN_TV_DIR = "/volume2/video/GDS3/GDRIVE/VIDEO/외국TV"
KOREAN_TV_DIR = "/volume2/video/GDS3/GDRIVE/VIDEO/국내TV"
MOVIES_ROOT_DIR = "/volume2/video/GDS3/GDRIVE/VIDEO/영화"
ANI_DIR = "/volume2/video/GDS3/GDRIVE/VIDEO/일본 애니메이션"
AIR_DIR = "/volume2/video/GDS3/GDRIVE/VIDEO/방송중" # 통합된 방송중 루트 폴더

EXCLUDE_FOLDERS = ["성인", "19금", "Adult"]
FFMPEG_PATH = "ffmpeg"
for p in ["/usr/local/bin/ffmpeg", "/var/packages/ffmpeg/target/bin/ffmpeg", "/usr/bin/ffmpeg"]:
    if os.path.exists(p): FFMPEG_PATH = p; break

GLOBAL_CACHE = {
    "movies": [], "air": [],
    "foreigntv": [], "koreantv": [], "animations_all": [], "search_index": [],
}


def nfc(text): return unicodedata.normalize('NFC', text) if text else ""


def nfd(text): return unicodedata.normalize('NFD', text) if text else ""


def get_real_path(path):
    if not path: return ""
    if os.path.exists(nfc(path)): return nfc(path)
    if os.path.exists(nfd(path)): return nfd(path)
    # 마지막 시도로, path를 다시 nfc로 변환하여 시도 (macOS에서 복사된 경로 문제 해결)
    return nfc(path)


def simplify(text): return re.sub(r'[^가-힣a-zA-Z0-9]', '', nfc(text)).lower() if text else ""


def is_excluded(path): return any(nfc(ex) in nfc(path) for ex in EXCLUDE_FOLDERS)


def kill_old_processes(current_sid=None):
    global FFMPEG_PROCS
    for sid in list(FFMPEG_PROCS.keys()):
        if sid != current_sid:
            try:
                p = FFMPEG_PROCS[sid]
                if p.poll() is None: p.terminate(); p.wait(timeout=2)
            except:
                pass
            shutil.rmtree(os.path.join(HLS_ROOT, sid), ignore_errors=True)
            if sid in FFMPEG_PROCS: del FFMPEG_PROCS[sid]


def get_movie_info(full_path, base_dir, route_prefix):
    rel_path = nfc(os.path.relpath(full_path, base_dir))
    thumb_id = hashlib.md5(f"{route_prefix}_{rel_path}".encode()).hexdigest() + ".jpg"
    return {
        "id": thumb_id, "title": os.path.basename(full_path),
        "videoUrl": f"http://{MY_IP}:5000/video_serve?type={route_prefix}&path={urllib.parse.quote(rel_path)}",
        "thumbnailUrl": f"http://{MY_IP}:5000/thumb_serve?type={route_prefix}&id={thumb_id}&path={urllib.parse.quote(rel_path)}"
    }


def scan_recursive(base_path, route_prefix, rel_base=None):
    categories = []
    exts = ('.mp4', '.mkv', '.avi', '.wmv', '.flv', '.ts')
    p, rb = get_real_path(base_path), get_real_path(rel_base) if rel_base else get_real_path(base_path)
    if not os.path.exists(p): return categories

    # AIR_DIR의 경우, 하위 폴더(예: 라프텔 애니메이션, 드라마)를 하나의 Category로 처리
    if route_prefix == "air":
        for folder_name in sorted(os.listdir(p)):
            full_folder_path = os.path.join(p, folder_name)
            if os.path.isdir(full_folder_path) and not is_excluded(folder_name):
                movies = []
                # 하위 폴더 전체를 재귀적으로 스캔하여 모든 영화 파일을 모음
                for root, dirs, files in os.walk(full_folder_path):
                    dirs[:] = [d for d in dirs if not is_excluded(os.path.join(root, d))]
                    if is_excluded(root): continue
                    movies.extend([get_movie_info(os.path.join(root, f), rb, route_prefix) for f in sorted(files) if f.lower().endswith(exts)])

                if movies:
                    categories.append({"name": nfc(folder_name), "movies": movies})
        return categories

    # 다른 카테고리는 기존 로직 유지 (폴더 구조대로)
    for root, dirs, files in os.walk(p):
        dirs[:] = [d for d in dirs if not is_excluded(os.path.join(root, d))]
        if is_excluded(root): continue
        movies = [get_movie_info(os.path.join(root, f), rb, route_prefix) for f in sorted(files) if
                  f.lower().endswith(exts)]
        if movies: categories.append({"name": nfc(os.path.basename(root)), "movies": movies})
    return categories


def perform_full_scan():
    global GLOBAL_CACHE
    print(f"🔄 전체 인덱싱 시작...")
    new_cache = {}
    try:
        # [수정] AIR_DIR를 한 번만 스캔하도록 로직 변경
        new_cache["air"] = scan_recursive(AIR_DIR, "air")

        new_cache["movies"] = scan_recursive(MOVIES_ROOT_DIR, "movie")
        new_cache["foreigntv"] = scan_recursive(FOREIGN_TV_DIR, "ftv")
        new_cache["koreantv"] = scan_recursive(KOREAN_TV_DIR, "ktv")
        new_cache["animations_all"] = scan_recursive(ANI_DIR, "anim_all")

        new_idx = []
        # [수정] air의 하위 카테고리도 인덱스에 포함 (AIR_DIR 내의 모든 영화)
        for k in ["air", "movies", "foreigntv", "koreantv", "animations_all"]:
            for cat in new_cache.get(k, []):
                for m in cat['movies']: new_idx.append(
                    {"movie": m, "category": cat['name'], "key": simplify(m['title'])})
        new_cache["search_index"] = new_idx
        new_cache["version"] = CACHE_VERSION

        GLOBAL_CACHE.update(new_cache)
        save_cache()
        print(f"🚀 인덱싱 완료 (항목: {len(new_idx)}개)")
    except:
        traceback.print_exc()


def load_cache():
    global GLOBAL_CACHE
    if os.path.exists(CACHE_FILE):
        try:
            with open(CACHE_FILE, 'r', encoding='utf-8') as f:
                data = json.load(f)
                GLOBAL_CACHE.update(data)
                return data.get("version") == CACHE_VERSION
        except:
            pass
    return False


def save_cache():
    try:
        data = GLOBAL_CACHE.copy();
        data["version"] = CACHE_VERSION
        with open(CACHE_FILE, 'w', encoding='utf-8') as f:
            json.dump(data, f, ensure_ascii=False, indent=2)
    except:
        pass


def update_index():
    if not load_cache() or not GLOBAL_CACHE.get("koreantv"): perform_full_scan()
    while True: time.sleep(3600 * 6); perform_full_scan()


FFMPEG_PROCS = {}
threading.Thread(target=update_index, daemon=True).start()


@app.route('/refresh')
def refresh_cache(): threading.Thread(target=perform_full_scan).start(); return jsonify({"status": "Started"})


@app.route('/movies')
def get_movies(): return jsonify(GLOBAL_CACHE.get("movies", []))


@app.route('/foreigntv')
def get_ftv(): return jsonify(GLOBAL_CACHE.get("foreigntv", []))


@app.route('/koreantv')
def get_ktv(): return jsonify(GLOBAL_CACHE.get("koreantv", []))


@app.route('/animations_all')
def get_all_anim(): return jsonify(GLOBAL_CACHE.get("animations_all", []))


@app.route('/air')
def get_air(): return jsonify(GLOBAL_CACHE.get("air", []))


# [수정] 더 이상 사용하지 않는 /animations, /latest, /dramas 경로 제거 (앱에서 호출하지 않도록 가정)
# 만약 앱에서 이 경로를 호출한다면, /air을 통해 데이터를 제공하도록 변경 필요


@app.route('/list')
def get_list():
    path_query = request.args.get('path', '')
    parts = path_query.split('/', 1)
    root_name, sub_path = parts[0], parts[1] if len(parts) > 1 else ""

    # [수정] AIR_DIR 경로를 포함하여 맵 재정의
    root_map = {"영화": MOVIES_ROOT_DIR, "외국TV": FOREIGN_TV_DIR, "국내TV": KOREAN_TV_DIR, "애니메이션": ANI_DIR,
                "방송중": AIR_DIR}
    prefix_map = {"영화": "movie", "외국TV": "ftv", "국내TV": "ktv", "애니메이션": "anim_all", "방송중": "air"}

    base = get_real_path(root_map.get(root_name))
    if not base: return jsonify([])
    target_path = get_real_path(os.path.normpath(os.path.join(base, sub_path.lstrip('/'))))
    if not os.path.exists(target_path): return jsonify([])

    # [수정] 방송중(/air) 카테고리인 경우, 하위 폴더의 리스트를 반환하지 않고,
    # 대신 파일 리스트를 반환하는 로직을 살려야 하는데,
    # /list의 역할은 하위 폴더 리스트를 보여주는 것이 주 목적이므로,
    # AIR_DIR 내의 서브 폴더 리스트를 반환합니다.

    if root_name == "방송중":
        # AIR_DIR 내의 하위 폴더를 리스트로 반환 (예: 라프텔 애니메이션, 드라마)
        if sub_path == "":
            sub_dirs = [nfc(n) for n in sorted(os.listdir(target_path)) if
                        os.path.isdir(os.path.join(target_path, n)) and not is_excluded(n)]
            return jsonify([{"name": d, "movies": []} for d in sub_dirs])

    # 기존 로직: 하위 폴더 리스트 반환
    sub_dirs = [nfc(n) for n in sorted(os.listdir(target_path)) if
                os.path.isdir(os.path.join(target_path, n)) and not is_excluded(n)]

    if sub_dirs and not sub_path:
        # 최상위 경로에서 하위 폴더가 있으면 폴더 리스트 반환
        return jsonify([{"name": d, "movies": []} for d in sub_dirs])


    # 하위 폴더가 없거나, 깊숙한 경로인 경우, 해당 경로의 영화 파일 리스트를 반환
    # 이 부분은 현재 클라이언트 앱의 MovieRow 로딩 로직과 맞지 않으므로,
    # 클라이언트 앱의 로직을 변경하지 않는 선에서 최대한 단순화하여 기존 로직을 따릅니다.
    movies_cats = scan_recursive(target_path, prefix_map.get(root_name, "movie"), rel_base=base)
    return jsonify(movies_cats) # movies_cats 자체가 Category 리스트를 반환하므로, 그대로 반환

@app.route('/search')
def search():
    q = simplify(request.args.get('q', ''))
    return jsonify([{"name": item['category'], "movies": [item['movie']]} for item in GLOBAL_CACHE["search_index"] if
                    q in item['key']][:50])


@app.route('/video_serve')
def serve_video():
    ua = request.headers.get('User-Agent', '').lower()
    is_ios = any(x in ua for x in ['iphone', 'ipad', 'ipod', 'avfoundation'])

    if 'linux' in ua or 'android' in ua: is_ios = False

    try:
        t, path_arg = request.args.get('type'), request.args.get('path')
        if not path_arg: return "Path missing", 400

        path = urllib.parse.unquote(path_arg).replace('+', ' ')

        # [수정] base_map에 "air" 타입 추가
        base_map = {
            "movie": MOVIES_ROOT_DIR, "ftv": FOREIGN_TV_DIR, "ktv": KOREAN_TV_DIR,
            "anim_all": ANI_DIR, "air": AIR_DIR
        }
        # [수정] 기본값으로 AIR_DIR 대신 MOVIES_ROOT_DIR을 사용하도록 변경 (안전성)
        base = get_real_path(base_map.get(t, MOVIES_ROOT_DIR))

        actual_path = get_real_path(os.path.join(base, path.lstrip('/')))

        if not os.path.exists(actual_path): return "File Not Found", 404

        if actual_path.lower().endswith('.mp4'): return send_file(actual_path, conditional=True)

        if not is_ios: return send_file(actual_path, conditional=True)

        if is_ios:
            sid = hashlib.md5(actual_path.encode()).hexdigest()
            kill_old_processes(sid)

            sdir = os.path.join(HLS_ROOT, sid)
            os.makedirs(sdir, exist_ok=True)
            video_m3u8 = os.path.join(sdir, "video.m3u8")

            if not os.path.exists(video_m3u8):
                cmd = [FFMPEG_PATH, '-y', '-i', actual_path, '-c:v', 'libx264', '-preset', 'ultrafast', '-crf', '28',
                       '-sn', '-c:a', 'aac', '-b:a', '128k', '-ac', '2', '-f', 'hls', '-hls_time', '6',
                       '-hls_list_size', '0', video_m3u8]
                FFMPEG_PROCS[sid] = subprocess.Popen(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                with open(os.path.join(sdir, "index.m3u8"), "w") as f:
                    f.write("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=2000000\nvideo.m3u8\n")
                for _ in range(40):
                    if os.path.exists(video_m3u8): break
                    time.sleep(0.5)

            return redirect(f"http://{MY_IP}:5000/hls/{sid}/index.m3u8")
        else:
            return send_file(actual_path, conditional=True)

    except Exception as e:
        traceback.print_exc()
        return f"Server Error: {str(e)}", 500


@app.route('/hls/<sid>/<filename>')
def serve_hls(sid, filename): return send_from_directory(os.path.join(HLS_ROOT, sid), filename)


@app.route('/thumb_serve')
def thumb_serve():
    t, tid, path_arg = request.args.get('type'), request.args.get('id'), request.args.get('path')
    thumb_path = os.path.join(DATA_DIR, tid)
    if os.path.exists(thumb_path): return send_from_directory(DATA_DIR, tid)
    try:
        path = urllib.parse.unquote(path_arg).replace('+', ' ')
        # [수정] base_map에 "air" 타입 추가
        base_map = {"movie": MOVIES_ROOT_DIR, "ftv": FOREIGN_TV_DIR, "ktv": KOREAN_TV_DIR, "anim_all": ANI_DIR, "air": AIR_DIR}
        base = get_real_path(base_map.get(t, MOVIES_ROOT_DIR))
        fp = get_real_path(os.path.join(base, path.lstrip('/')))
        subprocess.run([FFMPEG_PATH, '-ss', '00:03:00', '-i', fp, '-vframes', '1', '-q:v', '5', thumb_path, '-y'],
                       timeout=15)
        return send_from_directory(DATA_DIR, tid)
    except:
        return "Not Found", 404


@app.route('/stop_all')
def stop_all(): kill_old_processes(); return "OK", 200


if __name__ == '__main__':
    app.run(host='0.0.0.0', port=5000, threaded=True)