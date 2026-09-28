#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
filetourl.org 免费上传流程（逆向所得，4 步）：

  1) POST https://filetourl.org/_serverFn/<prepare-anon-id>
     body = TanStack seroval 编码的 {"data": {"storagePath": "<uuid><ext>", "sizeBytes": N}}
     -> {"result": {"bucket": "uploads", "token": "<signed JWT>", "publicUrl": "..."}}

  2) PUT https://<project>.supabase.co/storage/v1/object/upload/sign/<bucket>/<path>?token=<token>
     headers: apikey / authorization = anon JWT, x-upsert: false
     body: multipart/form-data，字段 cacheControl=3600 + 一个空名字段装文件

  3) POST https://filetourl.org/_serverFn/<record-anon-id>
     body = seroval({"data": {"storagePath", "publicUrl", "fileName", "sizeBytes"}})
     -> {"result": {"id", "slug", "tier", "expiresAt"}}

  4) 直链 = https://filetourl.org/f/<slug>

用法：
  python3 filetourl_upload.py <本地文件路径> [输出文件]
输出：
  直链写入 [输出文件]（默认 ./filetourl_url.txt），同时打印到 stdout。
"""
import json
import mimetypes
import os
import sys
import urllib.error
import urllib.request
import uuid

BASE = "https://filetourl.org"
SUPA = "https://vhoajpcndgfwbljltobo.supabase.co"
PREP_ID = "ee34296c5252b17ed4527feae4c14180eac02ace01f7ea0ffe758a08a34561b7"
REC_ID = "0e00059b9d19c9f638ecf962b9e934eae8f19bc129011fc1939c072695d54c16"

# Supabase anon key（公开的，前端内置）
ANON_JWT = (
    "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9."
    "eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InZob2FqcGNuZGdmd2Jsamx0b2JvIiwicm9sZSI6ImFub24i"
    "LCJpYXQiOjE3ODAxMzIzMDEsImV4cCI6MjA5NTcwODMwMX0."
    "y6Gjis0tGavjdFbhoOO00QJm6L29WRaAsUBCnZb5n18"
)

UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"
)

# 免费额度上限：49MB（超过会被服务端拒绝）
MAX_FREE_BYTES = 49 * 1024 * 1024

PROXY = os.environ.get("FTU_PROXY", "").strip()
_opener = None
if PROXY:
    _opener = urllib.request.build_opener(
        urllib.request.ProxyHandler({"http": PROXY, "https": PROXY})
    )


def _urlopen(req, timeout=120):
    if _opener is not None:
        return _opener.open(req, timeout=timeout)
    return urllib.request.urlopen(req, timeout=timeout)


# ---------------- seroval 编码器 ----------------
def seroval(root):
    """把 python 对象编码成 TanStack server function 的 seroval 载荷。"""
    refs, counter, marked = {}, [0], set()

    def enc(v):
        if isinstance(v, bool):
            return {"t": 2} if v else {"t": 3}
        if v is None:
            return {"t": 1, "s": ""}
        if isinstance(v, str):
            return {"t": 1, "s": v}
        if isinstance(v, (int, float)):
            return {"t": 0, "s": v}
        if isinstance(v, dict):
            if id(v) in refs:
                marked.add(refs[id(v)])
                return {"t": 4, "i": refs[id(v)]}
            nid = counter[0]
            counter[0] += 1
            refs[id(v)] = nid
            return {
                "t": 10,
                "i": nid,
                "p": {"k": list(v.keys()), "v": [enc(x) for x in v.values()]},
                "o": 0,
            }
        raise TypeError("unsupported type: %r" % (v,))

    return {"t": enc(root), "f": 127, "m": sorted(marked)}


def sf_post(fn_id, payload, label):
    url = BASE + "/_serverFn/" + fn_id
    data = json.dumps(seroval({"data": payload})).encode()
    headers = {
        "Content-Type": "application/json",
        "x-tsr-serverFn": "true",
        "Accept": "application/x-tss-framed, application/x-ndjson, application/json",
        "Origin": BASE,
        "Referer": BASE + "/",
        "User-Agent": UA,
    }
    req = urllib.request.Request(url, data=data, headers=headers, method="POST")
    try:
        with _urlopen(req, timeout=120) as resp:
            body = resp.read().decode("utf-8", "replace")
            print("[%s] HTTP %s" % (label, resp.status))
            return json.loads(body)
    except urllib.error.HTTPError as exc:
        detail = exc.read().decode("utf-8", "replace")[:600]
        print("[%s] HTTP %s -> %s" % (label, exc.code, detail))
        raise


UNDEF = object()


def decode_resp(node):
    """把 seroval 响应节点还原成 python 值。"""
    if not isinstance(node, dict):
        return node
    t = node.get("t")
    if t in (0, 1):
        return node.get("s")
    if t == 2:  # 常量节点
        return {
            0: None,
            1: UNDEF,
            2: True,
            3: False,
            4: -0.0,
            5: float("inf"),
            6: float("-inf"),
            7: float("nan"),
        }.get(node.get("s"), node)
    if t in (10, 11):
        return {k: decode_resp(v) for k, v in zip(node["p"]["k"], node["p"]["v"])}
    if t == 25:  # 服务端抛出的错误节点 $TSR/Error
        return {"__error__": decode_resp(node.get("s"))}
    return node


def extract_error(out):
    """从解码后的响应里提取错误信息文本，没有错误则返回 None。"""
    if not isinstance(out, dict):
        return None
    err = out.get("error")
    if err is UNDEF or err is None:
        return None
    if isinstance(err, dict) and "__error__" in err:
        err = err["__error__"]
    if isinstance(err, dict) and "message" in err:
        return str(err["message"])
    return str(err)


def unwrap(out, step):
    """取出 result；若服务端返回 error 则抛出带原因的异常。"""
    message = extract_error(out)
    if message:
        raise RuntimeError("%s 失败：%s" % (step, message))
    result = out.get("result") if isinstance(out, dict) else out
    if result is UNDEF or result is None:
        raise RuntimeError("%s 失败：响应中没有 result（%s）" % (step, out))
    return result


def prepare(path, size):
    raw = sf_post(PREP_ID, {"storagePath": path, "sizeBytes": size}, "1 prepare")
    result = unwrap(decode_resp(raw), "prepare")
    if not isinstance(result, dict) or not result.get("token"):
        raise RuntimeError("prepare 失败: %s" % (result,))
    return result


def supabase_put(bucket, path, token, file_path, content_type):
    boundary = "----WebKitFormBoundary" + uuid.uuid4().hex[:16]
    with open(file_path, "rb") as fh:
        blob = fh.read()

    parts = []
    parts.append(
        (
            "--" + boundary + "\r\n"
            'Content-Disposition: form-data; name="cacheControl"\r\n\r\n'
            "3600\r\n"
        ).encode()
    )
    parts.append(
        (
            "--" + boundary + "\r\n"
            'Content-Disposition: form-data; name=""; filename="%s"\r\n'
            "Content-Type: %s\r\n\r\n" % (path, content_type)
        ).encode()
    )
    parts.append(blob)
    parts.append(("\r\n--" + boundary + "--\r\n").encode())
    body = b"".join(parts)

    url = "%s/storage/v1/object/upload/sign/%s/%s?token=%s" % (SUPA, bucket, path, token)
    headers = {
        "Content-Type": "multipart/form-data; boundary=" + boundary,
        "apikey": ANON_JWT,
        "authorization": "Bearer " + ANON_JWT,
        "x-upsert": "false",
        "Origin": BASE,
        "Referer": BASE + "/",
        "User-Agent": UA,
        "Content-Length": str(len(body)),
    }
    req = urllib.request.Request(url, data=body, headers=headers, method="PUT")
    try:
        with _urlopen(req, timeout=600) as resp:
            print("[2 storage] HTTP %s %s" % (resp.status, resp.read().decode("utf-8", "replace")[:200]))
    except urllib.error.HTTPError as exc:
        detail = exc.read().decode("utf-8", "replace")[:600]
        print("[2 storage] HTTP %s -> %s" % (exc.code, detail))
        raise


def record(path, public_url, file_name, size):
    raw = sf_post(
        REC_ID,
        {
            "storagePath": path,
            "publicUrl": public_url,
            "fileName": file_name,
            "sizeBytes": size,
        },
        "3 record",
    )
    out = decode_resp(raw)
    result = unwrap(out, "record")
    if not isinstance(result, dict) or not result.get("slug"):
        raise RuntimeError("record 失败: %s" % (result,))
    return result


def main():
    if len(sys.argv) < 2:
        print("用法: python3 filetourl_upload.py <本地文件路径> [输出文件]")
        return 2

    local = sys.argv[1]
    out_file = sys.argv[2] if len(sys.argv) > 2 else "./filetourl_url.txt"

    if not os.path.isfile(local):
        print("错误：文件不存在 -> %s" % local)
        return 1

    size = os.path.getsize(local)
    if size > MAX_FREE_BYTES:
        print("错误：文件 %d 字节，超过免费额度上限 %d 字节" % (size, MAX_FREE_BYTES))
        return 1

    ext = os.path.splitext(local)[1] or ".bin"
    path = str(uuid.uuid4()) + ext
    name = os.path.basename(local)
    ctype = mimetypes.guess_type(local)[0] or "application/octet-stream"

    print("文件: %s" % local)
    print("大小: %d 字节" % size)
    print("远端路径: %s" % path)

    prep = prepare(path, size)
    bucket = prep.get("bucket") or "uploads"
    supabase_put(bucket, path, prep["token"], local, ctype)

    public_url = prep.get("publicUrl") or (
        "%s/storage/v1/object/public/%s/%s" % (SUPA, bucket, path)
    )
    rec = record(path, public_url, name, size)

    link = "%s/f/%s" % (BASE, rec["slug"])
    print("filetourl 直链: %s" % link)

    with open(out_file, "w", encoding="utf-8") as fh:
        fh.write(link)

    return 0


if __name__ == "__main__":
    sys.exit(main())
