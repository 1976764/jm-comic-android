"""Small, JSON-based boundary between Kotlin and embedded Python.

All Kotlin -> Python calls go through ``invoke(operation, payload_json)``
which dispatches to an allow-listed handler.  Every handler returns a
``str`` that can be parsed as JSON on the Kotlin side.
"""

import importlib.util
import json
import platform
import threading
import traceback


# ---------------------------------------------------------------------------
# Module-level state for the jmcomic client.
# Persisted across calls so the user stays logged in.
# ---------------------------------------------------------------------------
_jm_option = None   # JmOption instance
_jm_client = None   # JmApiClient / JmHtmlClient instance
_username = None    # str -- stored locally because JmApiClient.login() doesn't set _username
_user_info = {}     # dict -- user info captured from the login response
_best_domain = None  # str -- best API domain picked by test_domains(), applied to all clients


def _call_api(api_func, *args, **kwargs):
    """Call a jmcomic API function with automatic domain fallback.

    If the call fails (e.g., the domain override from test_domains is bad),
    clear the override, recreate the client, and retry once.
    """
    global _best_domain, _jm_client
    client = _ensure_client()
    try:
        return api_func(client, *args, **kwargs)
    except Exception as first_exc:
        # The domain override might be causing the failure.
        # Clear it and recreate the client with default domains.
        _best_domain = None
        _jm_client = None
        try:
            client = _ensure_client()
            return api_func(client, *args, **kwargs)
        except Exception:
            raise first_exc


def health_check() -> str:
    """Return runtime capability information without performing network I/O."""
    return json.dumps({
        "python": platform.python_version(),
        "jmcomicInstalled": importlib.util.find_spec("jmcomic") is not None,
    })


def test_curl_cffi() -> str:
    """Check that the packaged native extension loads on the device."""
    try:
        import curl_cffi

        return json.dumps({
            "success": True,
            "package": "curl_cffi",
            "version": getattr(curl_cffi, "__version__", "unknown"),
        })
    except Exception as exc:  # Return native loader failures to Kotlin verbatim.
        return json.dumps({"success": False, "error": repr(exc)})


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def _ensure_jmcomic():
    """Import jmcomic lazily and return the module (raises ImportError if absent)."""
    import jmcomic
    return jmcomic


def _ensure_client():
    """Ensure a jmcomic client exists, creating a default one if needed.

    This allows browsing (categories_filter, get_album_detail, search) without
    requiring the user to log in first.  The API client auto-fetches cookies
    via ``setting()`` so anonymous browsing works.  If a best domain was
    selected by ``test_domains``, it is applied to the fresh client so all
    subsequent requests go through the fastest domain.
    """
    global _jm_option, _jm_client
    if _jm_client is None:
        jmcomic = _ensure_jmcomic()
        _jm_option = jmcomic.JmOption.default()
        _jm_client = _jm_option.new_jm_client()
        _apply_best_domain(_jm_option, _jm_client)
    return _jm_client


def _apply_best_domain(option=None, client=None):
    """Force the best measured domain onto a jmcomic option/client (if present).

    - ``option.client.domain`` feeds every client created from that option,
    - ``client.domain_list`` is the live list the client rotates through.
    A custom single-domain list is never clobbered by jmcomic's official
    auto domain update (update_old_api_domain only replaces the default list).
    """
    if not _best_domain:
        return
    if option is not None:
        try:
            option.client.domain = [_best_domain]
        except Exception:
            pass
    if client is not None:
        try:
            client.domain_list = [_best_domain]
        except Exception:
            pass


def _get_cover_url(album_id) -> str:
    """Construct the cover image URL for an album.

    Uses ``JmcomicText.get_album_cover_url`` which builds a CDN URL in the
    format ``https://{domain}/media/albums/{id}_3x4.jpg``.
    """
    try:
        try:
            from jmcomic import JmcomicText
        except ImportError:
            from jmcomic.jm_toolkit import JmcomicText
        return JmcomicText.get_album_cover_url(str(album_id), size='_3x4')
    except Exception:
        return ""


def _serialize_content_item(aid, info) -> dict:
    """Convert a ``(album_id, info_dict)`` tuple from JmPageContent into a JSON-safe dict."""
    return {
        "id": str(aid),
        "title": info.get("name", "") if isinstance(info, dict) else "",
        "author": info.get("author", "") if isinstance(info, dict) else "",
        "cover_url": _get_cover_url(aid),
    }


# ---------------------------------------------------------------------------
# Login / logout / status
# ---------------------------------------------------------------------------

def login(payload: dict) -> str:
    """Authenticate against the JM server.

    Payload keys:
        username (str, required)
        password (str, required)

    On success returns ``{"ok": true, "username": "..."}``.
    On failure returns ``{"ok": false, "error": "..."}``.
    """
    global _jm_option, _jm_client, _username, _user_info

    username = (payload.get("username") or "").strip()
    password = payload.get("password") or ""

    if not username or not password:
        return json.dumps({"ok": False, "error": "用户名和密码不能为空"})

    try:
        jmcomic = _ensure_jmcomic()

        # Create a fresh option + client for each login attempt so stale
        # cookies from a previous session don't interfere.
        _jm_option = jmcomic.JmOption.default()
        _jm_client = _jm_option.new_jm_client()
        _apply_best_domain(_jm_option, _jm_client)

        # JmApiClient.login(username, password) -- POSTs to /login and stores
        # session cookies on the client.  Returns a JmApiResp containing
        # user profile data (uid, level, coin, album_favorites, etc.).
        resp = _jm_client.login(username, password)
        _jm_client._username = username
        _username = username
        _user_info = {"username": username}

        # Try to extract richer user info from the login response.
        try:
            resdata = getattr(resp, "resdata", None)
            if resdata is None:
                resdata = getattr(resp, "data", None)
            if isinstance(resdata, dict):
                _user_info.update(resdata)
                _user_info["username"] = username
        except Exception:
            pass

        # Export cookies so Kotlin can persist them for session restoration.
        # The client stores cookies as a plain dict in postman meta_data;
        # serialising it to JSON is trivial.
        try:
            session_cookies = dict(_jm_client['cookies'])
        except Exception:
            session_cookies = {}

        return json.dumps({
            "ok": True,
            "username": username,
            "user_info": _user_info,
            "cookies": session_cookies,
        })
    except Exception as exc:
        # Reset state on failure so we don't keep a half-initialised client.
        _jm_client = None
        _jm_option = None
        _username = None
        _user_info = {}
        return json.dumps({
            "ok": False,
            "error": str(exc),
            "traceback": traceback.format_exc(),
        })


def restore_session(payload: dict) -> str:
    """Restore a saved session by injecting cookies into a fresh client.

    Creates a new ``JmOption`` + ``JmApiClient``, sets the saved cookies,
    and returns immediately without a verification network call.  This
    makes app startup instant — cookies are tested lazily when the app
    makes its first authenticated API request.  If that request fails,
    the caller can fall back to a full login at that point.

    Payload keys:
        cookies   (dict, required) -- saved cookie dict from login()
        username  (str, required)
        user_info (dict, optional) -- saved user info
    """
    global _jm_option, _jm_client, _username, _user_info

    cookies = payload.get("cookies")
    username = payload.get("username", "")
    user_info = payload.get("user_info", {})

    if not cookies or not username:
        return json.dumps({"ok": False, "error": "cookies 和 username 不能为空"})

    try:
        jmcomic = _ensure_jmcomic()

        # Create a fresh client and inject the saved cookies.
        _jm_option = jmcomic.JmOption.default()
        _jm_client = _jm_option.new_jm_client()
        _apply_best_domain(_jm_option, _jm_client)
        _jm_client['cookies'] = cookies
        _jm_client._username = username
        _username = username
        _user_info = user_info if isinstance(user_info, dict) else {}
        _user_info["username"] = username

        # No verification call — return success immediately.
        # Cookies will be tested naturally by the first API request.
        return json.dumps({
            "ok": True,
            "username": _username,
            "user_info": _user_info,
        })
    except Exception as exc:
        _jm_client = None
        _jm_option = None
        _username = None
        _user_info = {}
        return json.dumps({"ok": False, "error": str(exc)})


def logout(_payload: dict) -> str:
    """Discard the current client and option, clearing all session state."""
    global _jm_option, _jm_client, _username, _user_info
    _jm_client = None
    _jm_option = None
    _username = None
    _user_info = {}
    return json.dumps({"ok": True})


def login_status(_payload: dict) -> str:
    """Return whether a client is active and the username if available."""
    if _jm_client is None or _username is None:
        return json.dumps({"loggedIn": False})
    return json.dumps({
        "loggedIn": True,
        "username": _username,
        "user_info": _user_info,
    })


def get_user_info(_payload: dict) -> str:
    """Return stored user info captured during login."""
    if not _user_info:
        return json.dumps({"ok": False, "error": "未登录"})
    return json.dumps({"ok": True, "user_info": _user_info})


# ---------------------------------------------------------------------------
# Browsing -- does NOT require login (uses _ensure_client)
# ---------------------------------------------------------------------------

def categories_filter(payload: dict) -> str:
    """Fetch comics filtered by category / time / order.

    Payload keys (all optional):
        page     (int, default 1)
        time     (str, default "a")   -- "a"=all, "t"=today, "w"=week, "m"=month
        category (str, default "0")   -- "0"=all, "doujin", "single", "short", "hanman", etc.
        order_by (str, default "mv")  -- "mr"=latest, "mv"=most viewed, "tf"=likes, "tr"=score
    """
    page = int(payload.get("page", 1))
    time = payload.get("time", "a")
    category = payload.get("category", "0")
    order_by = payload.get("order_by", "mv")

    try:
        result = _call_api(lambda c: c.categories_filter(
            page=page,
            time=time,
            category=category,
            order_by=order_by,
        ))

        items = []
        for aid, info in result.content:
            items.append(_serialize_content_item(aid, info))

        return json.dumps({
            "ok": True,
            "items": items,
            "total": result.total,
            "page": page,
        })
    except Exception as exc:
        return json.dumps({
            "ok": False,
            "error": str(exc),
            "traceback": traceback.format_exc(),
        })


def get_album_detail(payload: dict) -> str:
    """Fetch full detail for a single album.

    Payload keys:
        album_id (str/int, required)
    """
    album_id = str(payload.get("album_id", "")).strip()
    if not album_id:
        return json.dumps({"ok": False, "error": "album_id不能为空"})

    try:
        album = _call_api(lambda c: c.get_album_detail(album_id))

        episodes = []
        for ep in album.episode_list:
            # episode_list items are (photo_id, photo_index, photo_title)
            pid, pindex, pname = ep
            episodes.append({
                "id": str(pid),
                "index": str(pindex),
                "title": str(pname),
            })

        return json.dumps({
            "ok": True,
            "album": {
                "id": str(album.album_id),
                "title": album.name,
                "author": album.author,
                "description": album.description or "",
                "tags": list(album.tags) if album.tags else [],
                "page_count": album.page_count,
                "pub_date": getattr(album, "pub_date", ""),
                "update_date": getattr(album, "update_date", ""),
                "cover_url": _get_cover_url(album_id),
                "episodes": episodes,
            },
        })
    except Exception as exc:
        return json.dumps({
            "ok": False,
            "error": str(exc),
            "traceback": traceback.format_exc(),
        })


def _serialize_comment(c):
    """Serialize a JmAlbumComment to a JSON-serializable dict."""
    likes = getattr(c, 'likes', None)
    return {
        "id": str(getattr(c, 'comment_id', '') or getattr(c, 'cid', '') or ""),
        "user_id": str(getattr(c, 'user_id', '') or ""),
        "username": getattr(c, 'nickname', None) or getattr(c, 'username', None) or "",
        "content": getattr(c, 'content', "") or "",
        "created_at": str(getattr(c, 'created_at', '') or getattr(c, 'time', '') or ""),
        "likes": likes if likes is not None else -1,
        "is_spoiler": bool(getattr(c, 'is_spoiler', False)),
        "replies": [_serialize_comment(r) for r in (getattr(c, 'replies', None) or [])],
    }


def get_album_comments(payload: dict) -> str:
    """Fetch comments for an album.

    Payload keys:
        album_id (str, required)
        page     (int, optional, default 1)
    """
    album_id = str(payload.get("album_id", "")).strip()
    if not album_id:
        return json.dumps({"ok": False, "error": "album_id不能为空"})

    page = int(payload.get("page", 1))

    try:
        comment_page = _call_api(
            lambda c: c.album_pagination(album_id, page=page)
        )

        comments = [_serialize_comment(c) for c in comment_page]

        return json.dumps({
            "ok": True,
            "total": getattr(comment_page, 'total', 0) or 0,
            "page": page,
            "page_count": getattr(comment_page, 'page_count', None) or 1,
            "comments": comments,
        })
    except Exception as exc:
        return json.dumps({
            "ok": False,
            "error": str(exc),
            "traceback": traceback.format_exc(),
        })


def search(payload: dict) -> str:
    """Search for comics by keyword.

    Payload keys:
        query    (str, required) -- search keyword or JM id
        page     (int, optional, default 1)
        order_by (str, optional, default "mr")
        time     (str, optional, default "a")
        category (str, optional, default "all")
        main_tag (int, optional, default 0)
                 -- 0=站内, 1=作品, 2=作者, 3=标签, 4=演员
    """
    query = (payload.get("query") or "").strip()
    if not query:
        return json.dumps({"ok": False, "error": "搜索关键词不能为空"})

    page = int(payload.get("page", 1))
    order_by = payload.get("order_by", "mr")
    time = payload.get("time", "a")
    category = payload.get("category", "all")
    main_tag = int(payload.get("main_tag", 0))

    try:
        result = _call_api(lambda c: c.search(
            search_query=query,
            page=page,
            main_tag=main_tag,
            order_by=order_by,
            time=time,
            category=category,
            sub_category=None,
        ))

        items = []
        for aid, info in result.content:
            items.append(_serialize_content_item(aid, info))

        return json.dumps({
            "ok": True,
            "items": items,
            "total": result.total,
            "page": page,
            # 搜索禁漫车号(纯数字ID)时站点会重定向到本子详情页，
            # jmcomic 会用 wrap_single_album 包装成只含 1 个条目的搜索结果。
            # 此种结果没有下一页，标记 single 让客户端停止无限滚动。
            "single": bool(getattr(result, "is_single_album", False)),
        })
    except Exception as exc:
        return json.dumps({
            "ok": False,
            "error": str(exc),
            "traceback": traceback.format_exc(),
        })


# ---------------------------------------------------------------------------
# Reader -- progressive image loading (no login required)
# ---------------------------------------------------------------------------

# Cache JmPhotoDetail objects so repeated calls for the same chapter
# don't trigger redundant network requests.
_photo_cache = {}


def _get_photo(photo_id: str):
    """Get (cached) JmPhotoDetail with scramble id fetched."""
    if photo_id not in _photo_cache:
        _photo_cache[photo_id] = _call_api(
            lambda c: c.get_photo_detail(
                photo_id, fetch_album=False, fetch_scramble_id=True
            )
        )
    return _photo_cache[photo_id]


def _read_image_size(filepath):
    """Read image width/height from file header without PIL.

    Supports JPEG, PNG, and WebP — the three formats JM serves.
    Returns (width, height) or (None, None) if the format is unknown.
    """
    import struct

    with open(filepath, 'rb') as f:
        head = f.read(32)

    # PNG: 8-byte signature + IHDR chunk (width, height as big-endian uint32)
    if head[:8] == b'\x89PNG\r\n\x1a\n':
        w, h = struct.unpack('>II', head[16:24])
        return w, h

    # JPEG: parse SOF0/SOF1/SOF2 markers for dimensions
    if head[:3] == b'\xff\xd8\xff':
        with open(filepath, 'rb') as f:
            f.read(2)  # skip SOI marker
            while True:
                marker_bytes = f.read(2)
                if len(marker_bytes) < 2:
                    break
                m = struct.unpack('>H', marker_bytes)[0]
                # SOF markers: 0xFFC0-0xFFCF (excluding 0xFFC4, 0xFFC8, 0xFFCC)
                if m in (0xffc0, 0xffc1, 0xffc2, 0xffc3,
                         0xffc5, 0xffc6, 0xffc7,
                         0xffc9, 0xffca, 0xffcb,
                         0xffcd, 0xffce, 0xffcf):
                    f.read(3)  # skip length(2) + precision(1)
                    h, w = struct.unpack('>HH', f.read(4))
                    return w, h
                else:
                    length = struct.unpack('>H', f.read(2))[0]
                    f.read(length - 2)

    # WebP: RIFF container
    if head[:4] == b'RIFF' and head[8:12] == b'WEBP':
        chunk_type = head[12:16]
        if chunk_type == b'VP8 ':
            # VP8 lossy: width/height at offset 26-29 (little-endian uint16, 14 bits)
            w = struct.unpack('<H', head[26:28])[0] & 0x3fff
            h = struct.unpack('<H', head[28:30])[0] & 0x3fff
            return w, h
        elif chunk_type == b'VP8L':
            # VP8 lossless: dimensions packed in a uint32 at offset 21-24
            bits = struct.unpack('<I', head[21:25])[0]
            w = (bits & 0x3fff) + 1
            h = ((bits >> 14) & 0x3fff) + 1
            return w, h
        elif chunk_type == b'VP8X':
            # VP8 extended: width-1 at offset 24-26, height-1 at offset 27-29
            w = struct.unpack('<I', head[24:27] + b'\x00')[0] + 1
            h = struct.unpack('<I', head[27:30] + b'\x00')[0] + 1
            return w, h

    return None, None


def _calc_scramble_num(photo, photo_id: str, filename: str) -> int:
    """Calculate the strip count for descrambling a JM image.

    Uses ``JmImageTool.get_num`` with explicit parameters to avoid
    any ambiguity from ``get_num_by_detail`` attribute resolution.
    """
    try:
        from jmcomic import JmImageTool
    except ImportError:
        from jmcomic.jm_toolkit import JmImageTool

    filename_no_ext = filename.rsplit(".", 1)[0] if "." in filename else filename
    try:
        return int(JmImageTool.get_num(
            int(photo.scramble_id),
            int(photo_id),
            filename_no_ext,
        ))
    except Exception:
        return 0


def get_photo_info(payload: dict) -> str:
    """Return chapter metadata instantly (no image downloads).

    Also downloads the **first** raw image to read its dimensions, which
    are used as the default aspect ratio for all loading placeholders.
    Manga pages in the same chapter typically share the same ratio, so
    placeholder heights match final image heights — zero scroll jump.

    Payload keys:
        photo_id   (str, required)
        cache_dir  (str, required) -- used to check cache hits
    """
    photo_id = str(payload.get("photo_id", "")).strip()
    cache_dir = str(payload.get("cache_dir", "")).strip()
    if not photo_id or not cache_dir:
        return json.dumps({"ok": False, "error": "photo_id 和 cache_dir 不能为空"})

    try:
        import os
        photo = _get_photo(photo_id)

        raw_dir = os.path.join(cache_dir, "photos_raw", photo_id)
        decoded_dir = os.path.join(cache_dir, "photos_decoded", photo_id)

        images = []
        for i in range(len(photo.page_arr)):
            filename = photo.page_arr[i]
            num = _calc_scramble_num(photo, photo_id, filename)

            # Check if the descrambled (final) image already exists.
            decoded_path = os.path.join(decoded_dir, filename)
            cached = os.path.exists(decoded_path)

            images.append({
                "index": i,
                "filename": filename,
                "num": num,
                "cached": cached,
            })

        # Download the first raw image to determine the default aspect ratio.
        # This lets Kotlin reserve the correct height for all placeholders.
        default_w, default_h = 0, 0
        if images:
            first = images[0]
            first_filename = first["filename"]
            first_raw_path = os.path.join(raw_dir, first_filename)

            if not os.path.exists(first_raw_path):
                os.makedirs(raw_dir, exist_ok=True)
                try:
                    img_detail = photo.create_image_detail(0)
                    client = _ensure_client()
                    client.download_by_image_detail(
                        img_detail,
                        first_raw_path,
                        decode_image=False,
                    )
                except Exception:
                    pass

            if os.path.exists(first_raw_path):
                default_w, default_h = _read_image_size(first_raw_path)

        return json.dumps({
            "ok": True,
            "photo_id": photo_id,
            "title": photo.name,
            "page_count": len(images),
            "images": images,
            "default_width": default_w,
            "default_height": default_h,
        })
    except Exception as exc:
        return json.dumps({
            "ok": False,
            "error": str(exc),
            "traceback": traceback.format_exc(),
        })


def download_image(payload: dict) -> str:
    """Download a single raw image by index (decode_image=False, no PIL).

    Payload keys:
        photo_id   (str, required)
        cache_dir  (str, required)
        index      (int, required) -- 0-based page index
    """
    photo_id = str(payload.get("photo_id", "")).strip()
    cache_dir = str(payload.get("cache_dir", "")).strip()
    index = int(payload.get("index", -1))
    if not photo_id or not cache_dir or index < 0:
        return json.dumps({"ok": False, "error": "photo_id, cache_dir, index 不能为空"})

    try:
        import os
        photo = _get_photo(photo_id)

        if index >= len(photo.page_arr):
            return json.dumps({"ok": False, "error": f"index {index} out of range ({len(photo.page_arr)})"})

        filename = photo.page_arr[index]
        save_dir = os.path.join(cache_dir, "photos_raw", photo_id)
        os.makedirs(save_dir, exist_ok=True)
        save_path = os.path.join(save_dir, filename)

        # Download raw bytes only if not already cached.
        if not os.path.exists(save_path):
            img_detail = photo.create_image_detail(index)
            client = _ensure_client()
            client.download_by_image_detail(
                img_detail,
                save_path,
                decode_image=False,
            )

        num = _calc_scramble_num(photo, photo_id, filename)

        # Read image dimensions so Kotlin can use the exact aspect ratio.
        img_w, img_h = _read_image_size(save_path)

        return json.dumps({
            "ok": True,
            "path": save_path,
            "index": index,
            "filename": filename,
            "num": num,
            "width": img_w or 0,
            "height": img_h or 0,
        })
    except Exception as exc:
        return json.dumps({
            "ok": False,
            "error": str(exc),
            "traceback": traceback.format_exc(),
        })


def download_images_batch(payload: dict) -> str:
    """Download multiple raw images concurrently by index.

    Uses ThreadPoolExecutor for concurrent downloads (max 8 workers),
    skipping already-cached raw images. Returns results for all requested
    indices, including per-image success/failure status.

    Payload keys:
        photo_id   (str, required)
        cache_dir  (str, required)
        indices    (list of int, required) -- 0-based page indices
    """
    photo_id = str(payload.get("photo_id", "")).strip()
    cache_dir = str(payload.get("cache_dir", "")).strip()
    indices = payload.get("indices", [])
    if not photo_id or not cache_dir or not indices:
        return json.dumps({"ok": False, "error": "photo_id, cache_dir, indices 不能为空"})

    try:
        import os
        from concurrent.futures import ThreadPoolExecutor

        photo = _get_photo(photo_id)
        raw_dir = os.path.join(cache_dir, "photos_raw", photo_id)
        os.makedirs(raw_dir, exist_ok=True)

        def download_one(index):
            try:
                if index >= len(photo.page_arr):
                    return {"ok": False, "index": index, "error": f"index {index} out of range"}

                filename = photo.page_arr[index]
                save_path = os.path.join(raw_dir, filename)

                if not os.path.exists(save_path):
                    img_detail = photo.create_image_detail(index)
                    client = _ensure_client()
                    client.download_by_image_detail(
                        img_detail,
                        save_path,
                        decode_image=False,
                    )

                num = _calc_scramble_num(photo, photo_id, filename)
                img_w, img_h = _read_image_size(save_path)

                return {
                    "ok": True,
                    "index": index,
                    "filename": filename,
                    "path": save_path,
                    "num": num,
                    "width": img_w or 0,
                    "height": img_h or 0,
                }
            except Exception as exc:
                return {"ok": False, "index": index, "error": str(exc)}

        with ThreadPoolExecutor(max_workers=8) as executor:
            results = list(executor.map(download_one, indices))

        return json.dumps({"ok": True, "images": results})
    except Exception as exc:
        return json.dumps({
            "ok": False,
            "error": str(exc),
            "traceback": traceback.format_exc(),
        })

def download_chapter(payload: dict) -> str:
    """Download ALL images for a single chapter (photo) with decode_image=False.

    This is the download-service counterpart of ``download_images_batch``.
    It downloads every page in the chapter concurrently (ThreadPoolExecutor,
    8 workers) and saves raw (still-scrambled) images to ``output_dir``.
    The Kotlin side then calls ``ImageDescrambler`` to descramble each image.

    Using ``decode_image=False`` avoids PIL entirely — the same approach
    that fixed the reader's WebP decoding issue in Chaquopy.

    Payload keys:
        photo_id    (str, required) -- chapter ID
        output_dir  (str, required) -- directory to save raw images
    """
    photo_id = str(payload.get("photo_id", "")).strip()
    output_dir = str(payload.get("output_dir", "")).strip()

    if not photo_id or not output_dir:
        return json.dumps({"ok": False, "error": "photo_id 和 output_dir 不能为空"})

    try:
        import os
        from concurrent.futures import ThreadPoolExecutor

        photo = _get_photo(photo_id)
        os.makedirs(output_dir, exist_ok=True)

        def download_one(index):
            try:
                if index >= len(photo.page_arr):
                    return {"ok": False, "index": index, "error": f"index {index} out of range"}

                filename = photo.page_arr[index]
                save_path = os.path.join(output_dir, filename)

                if not os.path.exists(save_path):
                    img_detail = photo.create_image_detail(index)
                    client = _ensure_client()
                    client.download_by_image_detail(
                        img_detail,
                        save_path,
                        decode_image=False,
                    )

                num = _calc_scramble_num(photo, photo_id, filename)
                img_w, img_h = _read_image_size(save_path)

                return {
                    "ok": True,
                    "index": index,
                    "filename": filename,
                    "path": save_path,
                    "num": num,
                    "width": img_w or 0,
                    "height": img_h or 0,
                }
            except Exception as exc:
                return {"ok": False, "index": index, "error": str(exc)}

        indices = list(range(len(photo.page_arr)))
        with ThreadPoolExecutor(max_workers=8) as executor:
            results = list(executor.map(download_one, indices))

        return json.dumps({
            "ok": True,
            "photo_id": photo_id,
            "title": photo.name,
            "page_count": len(results),
            "images": results,
        })
    except Exception as exc:
        return json.dumps({
            "ok": False,
            "error": str(exc),
            "traceback": traceback.format_exc(),
        })


def favorite_folder(payload: dict) -> str:
    """Fetch the user's favorite albums.

    Payload keys (all optional):
        page     (int, default 1)
        order_by (str, default "mr")
        folder_id (str, default "0")
    """
    if _jm_client is None or _username is None:
        return json.dumps({"ok": False, "error": "未登录，请先登录"})

    page = int(payload.get("page", 1))
    order_by = payload.get("order_by", "mr")
    folder_id = payload.get("folder_id", "0")

    try:
        result = _jm_client.favorite_folder(
            page=page,
            order_by=order_by,
            folder_id=folder_id,
        )

        items = []
        for aid, info in result.content:
            items.append(_serialize_content_item(aid, info))

        print(f"[favorite_folder] page={page}, total={result.total}, items={len(items)}")
        return json.dumps({
            "ok": True,
            "items": items,
            "total": result.total,
            "page": page,
        })
    except Exception as exc:
        print(f"[favorite_folder] error: {exc}")
        return json.dumps({
            "ok": False,
            "error": str(exc),
            "traceback": traceback.format_exc(),
        })


def _album_chapter_entry(photo):
    """Build {title, files: [{filename, num}]} for one chapter's descramble map.

    Pure computation — no network — so it can run in before_photo while the
    album is still downloading, letting the Kotlin side descramble each
    chapter's files as they land.
    """
    files = []
    try:
        photo_id = str(getattr(photo, "id", ""))
        for fn in photo.page_arr:
            fn = str(fn)
            files.append({"filename": fn, "num": _calc_scramble_num(photo, photo_id, fn)})
    except Exception:
        pass
    return {
        "title": str(getattr(photo, "name", "") or ""),
        "files": files,
    }


def _merge_descramble_entry(path, key, entry):
    """Merge one chapter entry into the descramble sidecar (single-writer, under lock)."""
    try:
        existing = {}
        try:
            with open(path, "r", encoding="utf-8") as f:
                existing = json.load(f)
        except Exception:
            existing = {}
        existing[key] = entry
        with open(path, "w", encoding="utf-8") as f:
            json.dump(existing, f, ensure_ascii=False)
    except Exception:
        pass


def download_album_api(payload: dict) -> str:
    """Download an entire album with jmcomic's official ``download_album`` API.

    A fresh ``JmOption`` is built per call so the shared module-level option is
    never polluted.  The downloader is a custom ``JmDownloader`` subclass that
    reuses the app's shared (logged-in) client and reports progress through
    thread-safe ``before_photo`` / ``after_image`` hooks into a machine-readable
    JSON sidecar file that the Kotlin side polls.

    Multithreading: jmcomic downloads chapters on ``threading.photo`` parallel
    workers and each chapter's images on ``threading.image`` workers (defaults
    2 and 10, overridable).  Images are saved RAW (scrambled, ``decode=False``
    — no PIL) as ``{output_dir}/{photo_id}/{filename}``; Kotlin descrambles
    them afterwards via ``ImageDescrambler``.

    After the download completes, a descramble map is written to
    ``descramble_file``: ``{photo_id: {title, files: [{filename, num, path}]}}``
    so Kotlin can descramble every file in place.

    Payload keys:
        album_id       (str, required)
        output_dir     (str, required) -- base directory (jmcomic adds {photo_id}/)
        progress_file  (str, optional) -- JSON progress sidecar, polled by Kotlin
        descramble_file(str, optional) -- JSON descramble map sidecar
        image_threads  (int, default 10) -- concurrent image downloads per chapter
        photo_threads  (int, default 2)  -- concurrent chapters
    """
    album_id = str(payload.get("album_id", "")).strip()
    output_dir = str(payload.get("output_dir", "")).strip()
    progress_file = str(payload.get("progress_file", "")).strip()
    descramble_file = str(payload.get("descramble_file", "")).strip()
    try:
        image_threads = max(1, int(payload.get("image_threads", 10)))
        photo_threads = max(1, int(payload.get("photo_threads", 2)))
    except (TypeError, ValueError):
        image_threads, photo_threads = 10, 2

    if not album_id or not output_dir:
        return json.dumps({"ok": False, "error": "album_id 和 output_dir 不能为空"})

    state = {
        "status": "downloading",
        "total_images": 0,
        "downloaded_images": 0,
        "total_chapters": 0,
        "current_chapter": 0,
        "chapter_title": "",
        "error": None,
    }
    state_lock = threading.Lock()

    def write_progress():
        if not progress_file:
            return
        try:
            with open(progress_file, "w", encoding="utf-8") as f:
                json.dump(state, f, ensure_ascii=False)
        except Exception:
            pass

    try:
        import os
        import time

        jmcomic = _ensure_jmcomic()
        _ensure_client()

        from jmcomic.jm_downloader import JmDownloader
        from jmcomic.jm_option import DirRule
        from jmcomic.jm_task_context import jm_task_context

        # Fresh option for this download only — the shared option stays untouched.
        # NOTE: DirRule parses its DSL at construction (into parser_list); the
        # plain `dir_rule.rule = ...` / `dir_rule.base_dir = ...` assignments are
        # ineffective, so build a fresh DirRule instead.
        option = jmcomic.JmOption.default()
        option.dir_rule = DirRule("Bd_Pid", base_dir=output_dir)  # {output_dir}/{photo_id}/
        option.download.threading.image = image_threads
        option.download.threading.photo = photo_threads
        option.download.image.decode = False      # raw scrambled files; Kotlin descrambles
        option.download.cache = True              # skip already-downloaded files (resume)

        # Compute totals up-front so progress has a denominator immediately.
        album = _ensure_client().get_album_detail(album_id)
        state["total_images"] = album.page_count if hasattr(album, "page_count") else 0
        state["total_chapters"] = len(album.episode_list)
        write_progress()

        class _AlbumProgressDownloader(JmDownloader):
            """JmDownloader that reuses the shared client and reports progress."""

            def create_client(self):
                # Reuse the app's shared (logged-in) client so cookies apply.
                return _ensure_client()

            def before_photo(self, photo, **kwargs):
                super().before_photo(photo)
                with state_lock:
                    state["current_chapter"] += 1
                    state["chapter_title"] = str(getattr(photo, "name", "") or "")
                    write_progress()
                    # Write this chapter's descramble map entry immediately so
                    # Kotlin can start descrambling its files while the album
                    # is still downloading.
                    if descramble_file:
                        _merge_descramble_entry(
                            descramble_file,
                            str(getattr(photo, "id", "")),
                            _album_chapter_entry(photo),
                        )

            def after_image(self, image=None, img_save_path=None, **kwargs):
                super().after_image(image, img_save_path)
                with state_lock:
                    state["downloaded_images"] += 1
                    write_progress()

        with jm_task_context(
            download_type="album",
            jm_id=str(album_id),
            task_started_at=time.perf_counter(),
        ):
            dler = _AlbumProgressDownloader(option)
            dler.download_album(album_id)

        failed_images = len(dler.download_failed_image)
        failed_photos = len(dler.download_failed_photo)
        state["status"] = "done"
        write_progress()

        # Build the descramble map: {photo_id: {title, files: [{filename, num, path}]}}
        descramble_map = {}
        if os.path.isdir(output_dir):
            for dirname in sorted(os.listdir(output_dir)):
                chapter_dir = os.path.join(output_dir, dirname)
                if not os.path.isdir(chapter_dir):
                    continue
                photo = None
                try:
                    photo = _get_photo(dirname)
                except Exception:
                    photo = None
                files = []
                for fn in sorted(os.listdir(chapter_dir)):
                    fp = os.path.join(chapter_dir, fn)
                    if not os.path.isfile(fp):
                        continue
                    if not fn.lower().endswith((".jpg", ".jpeg", ".png", ".webp", ".gif")):
                        continue
                    num = _calc_scramble_num(photo, dirname, fn) if photo is not None else 0
                    files.append({"filename": fn, "num": num})
                if files:
                    descramble_map[dirname] = {
                        "title": str(getattr(photo, "name", "") or "") if photo is not None else "",
                        "files": files,
                    }
        if descramble_file:
            try:
                with open(descramble_file, "w", encoding="utf-8") as f:
                    json.dump(descramble_map, f, ensure_ascii=False)
            except Exception:
                pass

        total_files = sum(len(entry["files"]) for entry in descramble_map.values())
        return json.dumps({
            "ok": True,
            "album_id": album_id,
            "output_dir": output_dir,
            "total_images": state["total_images"],
            "total_files": total_files,
            "chapter_dirs": sorted(descramble_map.keys()),
            "failed_images": failed_images,
            "failed_photos": failed_photos,
        })
    except Exception as exc:
        state["status"] = "error"
        state["error"] = str(exc)
        write_progress()
        return json.dumps({"ok": False, "error": str(exc), "traceback": traceback.format_exc()})


# ---------------------------------------------------------------------------
# Domain speed test
# ---------------------------------------------------------------------------

def _probe_tcp(domain: str, timeout: float):
    """One TCP-443 connect probe. Returns (ok, latency_ms)."""
    import socket
    import time
    start = time.perf_counter()
    try:
        sock = socket.create_connection((domain, 443), timeout=timeout)
        sock.close()
        return True, (time.perf_counter() - start) * 1000
    except Exception:
        return False, 0.0


def test_domains(payload: dict) -> str:
    """Probe candidate API domains (TCP 443) for latency, pick the best.

    Each domain is probed [probes] times (concurrently across domains); the
    best domain is chosen by: success first -> lowest average latency.
    The winner is applied to the current and future jmcomic clients
    (``option.client.domain`` / ``client.domain_list``), so all
    subsequent API requests use it.

    Payload keys:
        domains (list of str, required) -- e.g. ["www.cdnhjk.net", ...]
        probes  (int, default 3)         -- probes per domain
        timeout (float, default 3.0)     -- per-probe connect timeout (s)
    """
    domains = payload.get("domains") or []
    if not isinstance(domains, list) or not domains:
        return json.dumps({"ok": False, "error": "domains 不能为空"})
    try:
        probes = max(1, int(payload.get("probes", 3)))
        timeout = max(0.5, float(payload.get("timeout", 3.0)))
    except (TypeError, ValueError):
        probes, timeout = 3, 3.0

    from concurrent.futures import ThreadPoolExecutor

    def probe_one(domain):
        lats = []
        for _ in range(probes):
            ok, ms = _probe_tcp(domain, timeout)
            if ok:
                lats.append(ms)
        return {
            "domain": domain,
            "avg_latency_ms": round(sum(lats) / len(lats), 1) if lats else None,
            "success": len(lats) > 0,
        }

    with ThreadPoolExecutor(max_workers=min(6, len(domains))) as executor:
        results = list(executor.map(probe_one, domains))

    results.sort(key=lambda r: (
        not r["success"],
        r["avg_latency_ms"] if r["avg_latency_ms"] is not None else float("inf"),
    ))

    best = results[0] if results and results[0]["success"] else None
    if best is not None:
        global _best_domain
        _best_domain = best["domain"]
        _apply_best_domain(_jm_option, _jm_client)

    return json.dumps({
        "ok": True,
        "best": best,
        "results": results,
        "applied": _best_domain,
    })


def set_domain(payload: dict) -> str:
    """Force the API client to use a specific domain (from the settings page).

    Payload keys:
        domain (str, required) -- e.g. "www.cdnutc.me"
    """
    domain = str(payload.get("domain", "")).strip()
    if not domain:
        return json.dumps({"ok": False, "error": "domain 不能为空"})

    global _best_domain
    _best_domain = domain
    _apply_best_domain(_jm_option, _jm_client)
    return json.dumps({"ok": True, "applied": _best_domain})


def toggle_favorite(payload: dict) -> str:
    """收藏 / 取消收藏（切换语义）。

    直接用 ``req_api`` 发 GET /favorite?aid={album_id}（``params`` 走查询字符串）。
    不使用库内置 ``add_favorite_album``，因为后者用 ``data=`` 传参，curl_cffi
    会把 ``data`` 放进 GET 请求体（POSTFIELDS），JM 服务器不处理 GET body，
    导致 ``aid`` 丢失、服务器返回收藏列表而非切换收藏——静默失败。

    Payload keys:
        album_id (str, required) -- 禁漫车号
    """
    album_id = str(payload.get("album_id", "")).strip()
    if not album_id:
        return json.dumps({"ok": False, "error": "album_id 不能为空"})

    if _username is None:
        return json.dumps({"ok": False, "error": "请先登入"})

    try:
        client = _ensure_client()

        # 确保 client._username 已设置（login / restore_session 可能遗漏）
        if getattr(client, '_username', None) is None:
            client._username = _username

        errors = []

        # 方式1: req_api GET /favorite?aid={album_id}（params 发查询字符串，服务器正确处理）
        if hasattr(client, 'req_api'):
            try:
                resp = client.req_api('/favorite', params={'aid': album_id})
                if hasattr(client, 'require_resp_status_ok'):
                    client.require_resp_status_ok(resp)
                print(f"[toggle_favorite] method1 req_api GET params: success")
                return json.dumps({"ok": True})
            except Exception as exc1:
                err1 = str(exc1)
                errors.append(f"req_api GET params: {err1}")
                print(f"[toggle_favorite] method1 req_api GET params failed: {err1}")
                if any(kw in err1 for kw in ["登入", "登錄", "登录", "login", "401", "cookie", "unauthorized"]):
                    return json.dumps({"ok": False, "error": err1})

        # 方式2: req_api POST /favorite（data 放 body，POST 语义正确）
        if hasattr(client, 'req_api'):
            try:
                resp = client.req_api('/favorite', get=False, data={'aid': album_id})
                if hasattr(client, 'require_resp_status_ok'):
                    client.require_resp_status_ok(resp)
                print(f"[toggle_favorite] method2 req_api POST: success")
                return json.dumps({"ok": True})
            except Exception as exc2:
                err2 = str(exc2)
                errors.append(f"req_api POST: {err2}")
                print(f"[toggle_favorite] method2 req_api POST failed: {err2}")
                if any(kw in err2 for kw in ["登入", "登錄", "登录", "login", "401", "cookie", "unauthorized"]):
                    return json.dumps({"ok": False, "error": err2})

        # 方式3: HTML 端点 POST /ajax/favorite_album
        try:
            resp = client.post(
                '/ajax/favorite_album',
                data={
                    'album_id': album_id,
                    'fid': '0',
                },
            )
            try:
                res = resp.json()
            except Exception:
                res = None

            if isinstance(res, dict) and res.get('status') == 1:
                print(f"[toggle_favorite] method3 html endpoint: success")
                return json.dumps({"ok": True})
            elif isinstance(res, dict):
                msg = res.get('msg', '')
                # "此圖片已經在您最喜愛的清單！" 視为成功
                if '已經在' in msg or '已经在' in msg or '最喜愛' in msg:
                    print(f"[toggle_favorite] method3 html endpoint: already favorited")
                    return json.dumps({"ok": True, "already": True})
                errors.append(f"html endpoint: {msg or resp.status_code}")
                print(f"[toggle_favorite] method3 html endpoint failed: {msg or resp.status_code}")
            else:
                errors.append(f"html endpoint: status={resp.status_code}")
                print(f"[toggle_favorite] method3 html endpoint failed: status={resp.status_code}")
        except Exception as exc3:
            errors.append(f"html endpoint: {exc3}")
            print(f"[toggle_favorite] method3 html endpoint exception: {exc3}")

        return json.dumps({"ok": False, "error": " | ".join(errors)})
    except Exception as exc:
        return json.dumps({"ok": False, "error": str(exc), "traceback": traceback.format_exc()})


# ---------------------------------------------------------------------------
# Dispatch table -- extend this to add new operations.
# ---------------------------------------------------------------------------
_OPERATIONS = {
    "health": lambda p: health_check(),
    "test_curl_cffi": lambda p: test_curl_cffi(),
    "login": login,
    "restore_session": restore_session,
    "logout": logout,
    "login_status": login_status,
    "get_user_info": get_user_info,
    "categories_filter": categories_filter,
    "get_album_detail": get_album_detail,
    "get_album_comments": get_album_comments,
    "get_photo_info": get_photo_info,
    "download_image": download_image,
    "download_images_batch": download_images_batch,
    "download_chapter": download_chapter,
    "download_album_api": download_album_api,
    "test_domains": test_domains,
    "set_domain": set_domain,
    "toggle_favorite": toggle_favorite,
    "search": search,
    "favorite_folder": favorite_folder,
}


def invoke(operation: str, payload_json: str = "{}") -> str:
    """Dispatch approved operations and always return JSON.

    Add authorized functions through a small allow-list: never use UI input to
    execute arbitrary Python or dynamically resolve arbitrary call targets.
    """
    try:
        payload = json.loads(payload_json)
        if not isinstance(payload, dict):
            raise ValueError("payload must be a JSON object")

        handler = _OPERATIONS.get(operation)
        if handler is None:
            return json.dumps({
                "ok": False,
                "error": "unsupported_operation",
                "message": f"Operation '{operation}' is not configured.",
            })
        return handler(payload)

    except (TypeError, ValueError, json.JSONDecodeError) as exc:
        return json.dumps({"ok": False, "error": "invalid_request", "message": str(exc)})
    except Exception as exc:
        return json.dumps({
            "ok": False,
            "error": "unexpected",
            "message": str(exc),
            "traceback": traceback.format_exc(),
        })
