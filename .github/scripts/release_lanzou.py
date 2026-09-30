import hashlib
import json
import os
import sys
import time
import urllib.error
import urllib.request

from lanzou_upload import Lanzou, LanzouError


def norm_version(tag: str) -> str:
    """把 tag 归一化成版本号：'v3.3'/'V3.3'/'3.3' -> '3.3'"""
    v = (tag or "").strip()
    if v[:1].lower() == "v" and v[1:2].isdigit():
        v = v[1:]
    return v


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

    api_base = os.environ.get("API_BASE") or "https://jm-api.xinsis.com"
    asset = os.environ.get("ASSET_NAME", "JM-arm64-release.apk")

    if not os.path.isfile(asset):
        raise SystemExit(f"未找到资产文件: {asset}")
    version = norm_version(tag)

    lz = Lanzou(username, password)
    lz.login()
    print(f"登录成功 uid = {lz.uid}，版本 = {version}")

    # 1) 直接上传到根目录；上传前先删除根目录下已有的同名 APK，避免堆放多份
    try:
        for f in lz.list_files("-1"):
            if str(f.get("name")) == asset:
                print(f"删除根目录旧文件 {asset} (id={f.get('id')}): "
                      f"{lz.delete_file(f.get('id'))}")
                break
    except LanzouError as e:
        print(f"警告: 拉取根目录文件失败，跳过旧文件清理：{e}")

    # 2) 上传 APK 到根目录，拿到分享外链 + 提取码
    share = lz.upload_file(asset, folder_id="-1")
    lanzou_url = share.url
    lanzou_code = share.pwd or ""
    print("外链   :", lanzou_url)
    print("提取码 :", lanzou_code or "(无)")

    # 3) 上报蓝奏云链接与提取码
    post_lanzou_info(api_base, note_pwd, version, lanzou_url, lanzou_code)
    print("完成")


if __name__ == "__main__":
    main()