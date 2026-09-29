import hashlib
import json
import os
import re
import sys
import time
import urllib.error
import urllib.request

from lanzou_upload import Lanzou, LanzouError

VERSION_RE = re.compile(r"^v?\d+(\.\d+)*$")


def norm_version(tag: str) -> str:
    """把 tag 归一化成版本号：'v3.3'/'V3.3'/'3.3' -> '3.3'"""
    v = (tag or "").strip()
    if v[:1].lower() == "v" and v[1:2].isdigit():
        v = v[1:]
    return v


def ver_key(name: str):
    """把版本名字符串转成可排序的元组，如 '3.12' -> (3, 12)"""
    return tuple(int(p) for p in re.findall(r"\d+", name))


def compute_token(note_pwd: str, hour: int) -> str:
    """X-Notice-Token = SHA-256(NOTICE_PASSWORD : floor(当前毫秒/3600000))"""
    return hashlib.sha256(f"{note_pwd}:{hour}".encode()).hexdigest()


def post_lanzou_info(api_base: str, note_pwd: str, version: str,
                     lanzou_url: str, lanzou_code: str):
    """上报蓝奏云信息；token 每次调用前重算，失败时按 当前/前一/后一小时 重试"""
    payload = {"version": version, "lanzou_url": lanzou_url, "lanzou_code": lanzou_code}
    hour = int(time.time() * 1000) // 3600000
    for delta in (0, -1, 1):  # 服务端接受当前、前一、后一小时
        token = compute_token(note_pwd, hour + delta)
        req = urllib.request.Request(
            api_base.rstrip("/") + "/api/lanzou/upload",
            data=json.dumps(payload).encode("utf-8"),
            headers={"Content-Type": "application/json", "X-Notice-Token": token},
            method="POST",
        )
        try:
            with urllib.request.urlopen(req, timeout=30) as resp:
                body = resp.read().decode("utf-8")
                data = json.loads(body)
                if data.get("success"):
                    print("lanzou 信息上报成功:", json.dumps(data, ensure_ascii=False))
                    return data
                print(f"服务端 success=false (hour+{delta}): {body[:300]}")
        except urllib.error.HTTPError as e:
            print(f"HTTP {e.code} (hour+{delta}): {e.read()[:200]}")
        except Exception as e:
            print(f"上报出错 (hour+{delta}): {e}")
    raise LanzouError("lanzou 信息上报失败（三个小时窗口均未成功）")


def main():
    tag = os.environ.get("RELEASE_TAG", "")
    if not tag and len(sys.argv) > 1:
        tag = sys.argv[1]
    if not tag:
        raise SystemExit("缺少 RELEASE_TAG")

    username = os.environ.get("LANZOU_USERNAME")
    password = os.environ.get("LANZOU_PASSWORD")
    if not username or not password:
        raise SystemExit("缺少 LANZOU_USERNAME / LANZOU_PASSWORD")

    note_pwd = os.environ.get("NOTICE_PASSWORD", "")
    if not note_pwd:
        raise SystemExit("缺少 NOTICE_PASSWORD")

    api_base = os.environ.get("API_BASE", "https://jm-api.xinsis.com")
    asset = os.environ.get("ASSET_NAME", "JM-arm64-release.apk")
    keep = int(os.environ.get("KEEP_VERSIONS", "3"))

    if not os.path.isfile(asset):
        raise SystemExit(f"未找到资产文件: {asset}")
    version = norm_version(tag)

    lz = Lanzou(username, password)
    lz.login()
    print(f"登录成功 uid = {lz.uid}，版本 = {version}")

    # 1) 建（或复用）版本文件夹
    folder_id = None
    try:
        for f in lz.list_folders("-1"):
            if f.get("name") == version:
                folder_id = f.get("fol_id")
                break
    except LanzouError as e:
        print(f"警告: 文件夹列表拉取失败，改为直接创建：{e}")
    if folder_id:
        print(f"复用版本文件夹 {version} -> id={folder_id}")
    else:
        folder_id = lz.create_folder(version)
        print(f"创建版本文件夹 {version} -> id={folder_id}")

    # 2) 上传 APK 到该版本文件夹，拿到分享外链 + 提取码
    share = lz.upload_file(asset, folder_id=folder_id)
    lanzou_url = share.url
    lanzou_code = share.pwd or ""
    print("外链   :", lanzou_url)
    print("提取码 :", lanzou_code or "(无)")

    # 3) 只保留 keep 个版本文件夹，删除其余“版本命名”的文件夹
    #    （仅删符合 v\d(.\d)* 命名的，避免误删无关文件夹）
    #    若列表接口失败则跳过清理，保证上传不中断
    try:
        version_folders = [
            f for f in lz.list_folders("-1")
            if VERSION_RE.match((f.get("name") or "").strip())
        ]
    except LanzouError as e:
        print(f"警告: 无法拉取文件夹列表，跳过旧版本清理：{e}")
        version_folders = []
    version_folders.sort(key=lambda f: ver_key(f["name"]), reverse=True)
    current_fid = str(folder_id)
    others = [f for f in version_folders if str(f.get("fol_id")) != current_fid]
    doomed = others[keep - 1:] if keep >= 1 else others
    for f in doomed:
        try:
            print(f"删除旧版本 {f['name']} ({f['fol_id']}): "
                  f"{lz.delete_folder(f['fol_id'])}")
        except LanzouError as e:
            print(f"删除 {f['name']} 失败: {e}")

    # 4) 上报蓝奏云链接与提取码
    post_lanzou_info(api_base, note_pwd, version, lanzou_url, lanzou_code)
    print("完成")


if __name__ == "__main__":
    main()