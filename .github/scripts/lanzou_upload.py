#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
蓝奏云 (lanzou) 登录 -> 上传文件 -> 获取分享外链 + 提取码

基于 Reqable 抓包分析复现，全流程：

  1. GET  https://accounts.woozooo.com/accounts.php?action=login&ref=up.woozooo.com
                初始化 accounts 会话 + 过 acw_sc__v2 反爬 JS 前的 cookie
  2. POST https://accounts.woozooo.com/accounts.php
                登录，body: task=uselogin&username=..&password=..&ref=up.woozooo.com
                成功返回 zt=1, msgs=中转鉴权地址 https://up.woozooo.com/acc.php?t=<token>
  3. GET  https://up.woozooo.com/acc.php?t=<token>
                服务端下发 phpdisk_info / uag / ylogin / ylogins / lanzou_ifo 等 cookie
  4. POST https://up.woozooo.com/html5up.php
                multipart 上传（fileVal=upload_file, formData={task:1,vie:2,ve:2}）
                成功返回 text[].is_newd（分享域名） + f_id（提取码），外链 = is_newd/f_id

用法（命令行）：
  python lanzou_upload.py "账号" "密码" /path/to/file.zip [目标文件夹id(默认-1=根目录)]

集成：
  from lanzou_upload import Lanzou
  lz = Lanzou("账号", "密码")
  lz.login()
  share = lz.upload_file("a.zip")          # -> {url, pwd, name}
  print(share["url"], share["pwd"])        # 外链 + 提取码
"""

import argparse
import json
import os
import re
import socket
import time
from concurrent.futures import FIRST_COMPLETED, ThreadPoolExecutor, wait
from dataclasses import dataclass, field
from pathlib import Path
from typing import Dict, List, Optional

import requests
from requests.adapters import HTTPAdapter

ACC_URL = "https://accounts.woozooo.com"
UP_URL = "https://up.woozooo.com"
# 阿里云盾 acw_sc__v2 反爬：m 为 1..40 的排列，p 为异或密钥（均从挑战页 JS 反解）
_ACW_M = [0xf,0x23,0x1d,0x18,0x21,0x10,0x1,0x26,0xa,0x9,0x13,0x1f,0x28,0x1b,0x16,0x17,
          0x19,0xd,0x6,0xb,0x27,0x12,0x14,0x8,0xe,0x15,0x20,0x1a,0x2,0x1e,0x7,0x4,
          0x11,0x5,0x3,0x1c,0x22,0x25,0xc,0x24]
_ACW_P = "3000176000856006061501533003690027800375"

UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/154.0.0.0 Safari/537.36")


def solve_acw(arg1: str) -> str:
    """复刻挑战页 JS：重排 arg1 后与密钥 p 逐字节异或，生成 acw_sc__v2 cookie 值"""
    q = [''] * len(arg1)
    for z, mv in enumerate(_ACW_M):
        q[z] = arg1[mv - 1]
    u = ''.join(q)
    out = []
    for i in range(0, len(u), 2):
        b = int(u[i:i + 2], 16) ^ int(_ACW_P[i:i + 2], 16)
        h = format(b, 'x')
        out.append(h if len(h) == 2 else '0' + h)
    return ''.join(out)


# 对慢速/不稳的网络（如境外→国内上传）调优 TCP：加大发送缓冲、关闭 Nagle、
# 开启 keepalive，避免大文件在写入阶段因发送缓冲满而触发 socket 写超时。
_UPLOAD_SOCKET_OPTS = [
    (socket.SOL_SOCKET, socket.SO_KEEPALIVE, 1),
    (socket.IPPROTO_TCP, socket.TCP_NODELAY, 1),
    (socket.SOL_SOCKET, socket.SO_SNDBUF, 1024 * 1024),
    (socket.SOL_SOCKET, socket.SO_RCVBUF, 1024 * 1024),
]


class _TunedAdapter(HTTPAdapter):
    def __init__(self, *args, **kwargs):
        kwargs.setdefault("max_retries", 1)
        super().__init__(*args, **kwargs)

    def init_poolmanager(self, *args, **kwargs):
        kwargs["socket_options"] = _UPLOAD_SOCKET_OPTS
        super().init_poolmanager(*args, **kwargs)


# ---------------- 免费代理（用于把大文件从境外→国内绕过卡顿） ----------------

PROXY_SOURCE = "https://proxy5.net/api/free-proxies.php?v=994827"
_PROXY_TEST_TARGET = "https://up.woozooo.com/mydisk.php?item=files&action=index"


def _socks_ok() -> bool:
    """requests 走 socks 需要 PySocks（requests[socks]），未安装时只支持 http 代理"""
    try:
        import socks  # noqa: F401
        return True
    except Exception:
        return False


def _fetch_proxy_records(timeout: int = 25) -> list:
    """拉取免费代理列表；proxy5 前面有 Cloudflare 反爬，拿不到 JSON 时返回空列表"""
    try:
        r = requests.get(
            PROXY_SOURCE,
            headers={"User-Agent": UA,
                     "Accept": "application/json,text/plain,*/*"},
            timeout=timeout,
        )
        j = r.json()
    except Exception as e:
        print(f"[proxy] 拉取代理列表失败（多因 Cloudflare 反爬拦截）: {e}")
        return []
    if isinstance(j, list):
        return j
    return j.get("results") or []


def _filter_cn_proxies(records: list, top: int = 15) -> list:
    """只保留中国大陆代理，协议优先 HTTP/HTTPS，其次 SOCKS5（需 PySocks）"""
    out = []
    for rec in records:
        if not isinstance(rec, dict):
            continue
        cc = (rec.get("country_code") or "").upper()
        ctry = (rec.get("country") or "").lower()
        if cc != "CN" and "china" not in ctry and "中国" not in ctry:
            continue
        ip = rec.get("ip_address")
        port = rec.get("port")
        if not ip or not port:
            continue
        protos = [str(p).upper() for p in (rec.get("protocols") or [])]
        scheme = "http"          # HTTP 与 HTTPS 都用 http:// 方式配给 requests（CONNECT 隧道）
        if not any(p in ("HTTP", "HTTPS") for p in protos):
            if "SOCKS5" in protos and _socks_ok():
                scheme = "socks5h"
            else:
                continue
        out.append({
            "scheme": scheme,
            "ip": ip, "port": int(port),
            "working": bool(rec.get("is_working")),
            "latency": rec.get("latency") if isinstance(rec.get("latency"), (int, float)) else 9999,
        })
    out.sort(key=lambda p: (0 if p["working"] else 1, p["latency"]))
    return out[:top]


def _test_proxy(p: dict, timeout: int = 12) -> bool:
    """通过代理实际请求一次蓝奏云控制台，能返回即视为可用"""
    url = f"{p['scheme']}://{p['ip']}:{p['port']}"
    proxies = {"http": url, "https": url}
    try:
        r = requests.get(_PROXY_TEST_TARGET, proxies=proxies, timeout=timeout,
                         headers={"User-Agent": UA})
        return 200 <= r.status_code < 500
    except Exception:
        return False


def _proxies_env_list() -> list:
    """支持从环境变量 LANZOU_PROXIES 手动喂一组 ip:port（逗号/换行分隔），作为代理来源的兜底"""
    raw = os.environ.get("LANZOU_PROXIES", "")
    out = []
    for chunk in re.split(r"[\s,;]+", raw.strip()):
        chunk = chunk.strip()
        m = re.match(r"^(?:http|https|socks5h)://(.+)$", chunk)
        if m:
            chunk = m.group(1)
        if ":" not in chunk:
            continue
        ip, port = chunk.rsplit(":", 1)
        try:
            out.append({"scheme": "http", "ip": ip.strip(), "port": int(port),
                        "working": True, "latency": 0})
        except ValueError:
            pass
    return out


class LanzouError(RuntimeError):
    pass


@dataclass
class Share:
    """一次上传得到的分享信息"""
    url: str            # 分享外链，形如 https://wwxx.lanzouf.com/<f_id>
    pwd: str            # 提取码（蓝奏云默认无提取码则为空串）
    f_id: str           # 分享 ID / 提取码
    name: str           # 服务端展示的文件名
    id: str = ""        # 文件数字 ID（删除文件时使用 file_id）
    onof: str = "0"     # "1" 表示该文件设置了提取码


@dataclass
class Lanzou:
    username: str
    password: str
    timeout: int = 15
    upload_timeout: int = 3600  # 上传大文件的读写超时（秒）：境外→国内容易高延迟，需放得很宽
    upload_retries: int = 5     # 上传遇到网络错误时的重试次数
    cookies: Dict[str, str] = field(default_factory=dict)

    def __post_init__(self):
        self.s = requests.Session()
        self.s.headers.update({"User-Agent": UA})
        self.s.mount("https://", _TunedAdapter())  # 调优 TCP，降低大文件写超时概率
        self.s.mount("http://", _TunedAdapter())
        self.uid: Optional[str] = None
        self.vei: Optional[str] = None  # 页面级签名，随会话变化，需从控制台页刮取

    def _get_vei(self) -> str:
        """从控制台页刮取 vei 签名（task=5/47 都要求它）"""
        if self.vei:
            return self.vei
        p = self._request(
            "GET", f"{UP_URL}/mydisk.php?item=files&action=index&u={self.uid}").text
        m = re.search(r"['\"]vei['\"]\s*:\s*['\"]([^'\"]+)['\"]", p)
        if m:
            self.vei = m.group(1)
        return self.vei or "VFBeXABSDABXBFJWAVs="

    def _request(self, method: str, url: str, *, data=None, files=None,
                 headers=None, timeout: Optional[int] = None,
                 proxies: Optional[Dict[str, str]] = None) -> requests.Response:
        """统一请求封装：网络错误与非 2xx 统一转为 LanzouError"""
        try:
            r = self.s.request(
                method, url, data=data, files=files, headers=headers,
                timeout=timeout if timeout is not None else self.timeout,
                proxies=proxies,
            )
            r.raise_for_status()
            return r
        except requests.exceptions.RequestException as e:
            raise LanzouError(f"请求失败 {method} {url}: {e}") from e

    def _pick_upload_proxies(self, limit: int = 5, max_tested: int = 30,
                             concurrency: int = 15, test_timeout: int = 8) -> List[Dict[str, str]]:
        """并发挑选并实测可用的大陆代理，返回可直接传给 requests.proxies 的列表。

        优先级：环境变量 LANZOU_PROXIES > proxy5.net 大陆代理列表。
        所有候选都会真实连通性实测；为节省时间采用并发测试，
        最多测试 max_tested 个，一旦凑齐 limit 个可用立即返回。
        """
        candidates = _proxies_env_list()
        if candidates:
            print(f"[proxy] 使用环境变量提供的 {len(candidates)} 个代理（并发实测中…）")
        else:
            candidates = _filter_cn_proxies(_fetch_proxy_records())
            if candidates:
                print(f"[proxy] 从 proxy5 取到 {len(candidates)} 个大陆代理（并发实测中…）")

        if not candidates:
            print("[proxy] 无可用候选代理，本次直连上传")
            return []

        picked: List[Dict[str, str]] = []
        checked = 0
        ex = ThreadPoolExecutor(max_workers=concurrency)
        try:
            futures = {}
            for p in candidates:
                if checked >= max_tested:
                    break
                futures[ex.submit(_test_proxy, p, test_timeout)] = p
                checked += 1
            while futures:
                done, _ = wait(futures, return_when=FIRST_COMPLETED)
                for fut in done:
                    p = futures.pop(fut)
                    if fut.result():
                        url = f"{p['scheme']}://{p['ip']}:{p['port']}"
                        picked.append({"http": url, "https": url})
                        print(f"[proxy] 可用 ({len(picked)}): {url}")
                        if len(picked) >= limit:
                            print(f"[proxy] 已凑齐 {limit} 个可用代理，停止测试")
                            return picked
        finally:
            # wait=False 立即返回，取消剩余排队测试，避免凑够后仍空等
            ex.shutdown(wait=False, cancel_futures=True)
        print(f"[proxy] 共实测 {checked} 个，可用 {len(picked)} 个"
              f"{'，不足，本次直连上传' if not picked else ''}")
        return picked

    # ---------------- 登录 ----------------

    def login(self, retries: int = 3):
        """完成 1-3 步，登录并鉴权；登录成功后可访问网盘控制台

        :param retries: 命中反爬导致登录响应无法解析时，重新取挑战重试的次数
        """
        for attempt in range(max(1, retries)):
            # 1. 访问登录页，初始化 cookie 并获取 acw_sc__v2 挑战值
            g = self._request(
                "GET", f"{ACC_URL}/accounts.php?action=login&ref=up.woozooo.com")
            m = re.search(r"var arg1='([0-9a-fA-F]+)'", g.text)
            if m:  # 命中阿里云盾反爬，需计算并回写挑战 cookie
                self.s.cookies.set("acw_sc__v2", solve_acw(m.group(1)),
                                   domain="accounts.woozooo.com")

            # 2. 提交登录
            r = self._request(
                "POST", f"{ACC_URL}/accounts.php",
                data={"task": "uselogin", "username": self.username,
                      "password": self.password, "ref": "up.woozooo.com"},
                headers={
                    "X-Requested-With": "XMLHttpRequest",
                    "Accept": "application/json, text/javascript, */*",
                    "Referer": f"{ACC_URL}/accounts.php?action=login&ref=up.woozooo.com",
                },
            )
            try:
                data = r.json()
                break
            except json.JSONDecodeError:
                if attempt + 1 < max(1, retries):
                    continue  # 仍被反爬拦截，重取挑战后重试
                raise LanzouError(
                    f"登录响应无法解析（可能仍被反爬拦截）: {r.text[:200]}")

        if data.get("zt") != 1:
            raise LanzouError(f"登录失败: {data.get('msgs')}")

        # 3. 访问中转鉴权地址（设置 phpdisk_info / ylogin / lanzou_ifo 等 cookie）
        redirect_url = data["msgs"]  # https://up.woozooo.com/acc.php?t=<token>
        a = self._request("GET", redirect_url,
                          headers={"Referer": f"{ACC_URL}/"})
        a.raise_for_status()

        # uid = ylogin cookie（形如 5461944）
        self.uid = self.s.cookies.get("ylogin") or self.s.cookies.get("ylogins")
        if not self.uid:
            # 兜底：从控制台 HTML 的手机号跳转链接里解析 u=xxx
            m = self._request("GET", f"{UP_URL}/mydisk.php")
            mm = re.search(r"u=(\d+)", m.text)
            self.uid = mm.group(1) if mm else None
        if not self.uid:
            raise LanzouError("登录后无法解析用户 ID")
        return self.uid

    # ---------------- 上传 ----------------

    def upload_file(self, path: str, folder_id: str = "-1",
                    filename: Optional[str] = None) -> Share:
        """
        第 4 步：上传文件到指定文件夹，返回外链 + 提取码。

        :param path: 本地文件路径（或 bytes）
        :param folder_id: 目标文件夹 id，默认 -1 为根目录
        :param filename: 可选，覆盖上传用文件名
        :return: Share
        """
        if isinstance(path, (str, Path)):
            file_obj = Path(path)
            if not file_obj.is_file():
                raise LanzouError(f"文件不存在: {file_obj}")
            fname = filename or file_obj.name
            try:
                data = file_obj.read_bytes()
            except OSError as e:
                raise LanzouError(f"读取文件失败 {file_obj}: {e}") from e
        else:  # bytes
            fname = filename or "upload.bin"
            data = path

        self.s.headers.update({
            "Origin": UP_URL,
            "Referer": f"{UP_URL}/mydisk.php?item=files&action=index&u={self.uid}",
        })

        # WebUploader 用字段：upload_file 为文件，另带 task/vie/ve
        form_data = {"task": "1", "vie": "2", "ve": "2", "folder_id": folder_id}
        files = {"upload_file": (fname, data, "application/octet-stream")}

        # 先挑一批实测可用的大陆代理，避免境外→国内直连大文件写超时；
        # 强制新建连接上传，避免复用可能已过期的 keep-alive 连接被 CDN 掐断；
        # 大文件需要很长的读写超时（标量=连接+读写都用同一个宽限值）；
        # 网络类错误做退避重试，且每次尝试轮换一个已验证的代理
        size_mb = len(data) / (1024 * 1024)
        print(f"开始上传 {fname} ({size_mb:.1f} MB) 至文件夹 {folder_id} ...")
        upload_proxies = self._pick_upload_proxies()
        r = None
        for attempt in range(self.upload_retries):
            t0 = time.time()
            try:
                proxies = upload_proxies[attempt % len(upload_proxies)] if upload_proxies else None
                r = self._request(
                    "POST", f"{UP_URL}/html5up.php",
                    data=form_data, files=files,
                    headers={"Connection": "close"},
                    timeout=self.upload_timeout,
                    proxies=proxies,
                )
                break
            except LanzouError as e:
                is_net = "请求失败" in str(e)
                if not is_net or attempt == self.upload_retries - 1:
                    raise
                print(f"上传网络错误，重试 {attempt + 1}/{self.upload_retries}: {e} "
                      f"（本次耗时 {time.time() - t0:.0f}s）")
                time.sleep(5 * (attempt + 1))  # 5s/10s/.. 退避，缓解瞬时限流
        if r is None:
            raise LanzouError("上传失败（网络重试均失败）")
        text = r.text
        try:
            resp = json.loads(text)
        except json.JSONDecodeError:
            # 反爬或未登录时会返回封装文本
            raise LanzouError(
                f"上传响应无法解析（可能未登录或会话过期）: {text[:200]}")

        zt = resp.get("zt")
        if zt == 9:
            raise LanzouError("未登录，会话已失效，请重新 login()")
        if zt != 1 and zt != 0:
            raise LanzouError(f"上传失败 zt={zt}: {resp.get('info', text[:200])}")
        if zt == 0:
            raise LanzouError(f"上传失败: {resp.get('info') or resp.get('msgs') or text[:200]}")

        items: List[dict] = resp.get("text") or []
        if not items:
            raise LanzouError("上传成功但未返回分享信息")

        first = items[0]
        domain = str(first.get("is_newd", "")).rstrip("/")
        fid = str(first.get("f_id") or first.get("id") or "")
        file_id = str(first.get("id") or "")
        onof = str(first.get("onof", "0"))

        # 提取码：优先响应里的显式 pwd 字段；没有时按蓝奏云惯例，
        # onof=1（需输入口令访问）以 f_id 作为访问码，否则视为无提取码。
        raw_pwd = str(first.get("pwd") or first.get("code") or "").strip().strip("/")
        if raw_pwd and "/" in raw_pwd:
            raw_pwd = raw_pwd.rsplit("/", 1)[-1]
        pwd = raw_pwd or (fid if onof == "1" else "")

        url = self.build_share_url(domain, fid)
        print(f"[resolve] share fields={sorted(str(k) for k in first.keys())} "
              f"onof={onof} f_id={fid} 提取码={pwd!r}")
        return Share(url=url, pwd=pwd, f_id=fid, name=first.get("name", fname),
                     id=file_id, onof=onof)

    @staticmethod
    def build_share_url(domain: str, fid: str) -> str:
        """拼接分享外链 URL"""
        if not domain or not fid:
            raise LanzouError("缺少分享域名或提取码，无法生成外链")
        protocol = domain if domain.startswith("http") else "https://" + domain
        return f"{protocol}/{fid}"

    # ---------------- 辅助：从指定文件夹拿已有文件的分享 ID（可选） ----------------

    def list_folders(self, parent_id: str = "-1", page: int = 1) -> List[dict]:
        """列举某文件夹下的子文件夹（task=47），返回每条含 name/fol_id/onof 等

        注意：list_files 只返回"文件"；文件夹必须用本方法单独列举。
        """
        params = {"task": "47", "folder_id": str(parent_id), "pg": str(page),
                  "vei": self._get_vei()}
        r = self._request(
            "POST", f"{UP_URL}/doupload.php",
            data=params,
            headers={
                "X-Requested-With": "XMLHttpRequest",
                "Content-Type": "application/x-www-form-urlencoded; charset=UTF-8",
                "Origin": UP_URL,
                "Referer": f"{UP_URL}/mydisk.php?item=files&action=index&u={self.uid}",
            },
        )
        j = r.json()
        # 空文件夹列表时服务端返回 zt=2（非错误），按空列表处理
        if j.get("zt") not in ("1", "2", 1, 2):
            raise LanzouError(f"获取文件夹列表失败: {j}")
        return j.get("text") or []

    def list_files(self, folder_id: str = "-1", page: int = 1) -> List[dict]:
        """列举文件夹下文件（task=5），返回每条含 id / f_id / is_newd / name / onof"""
        params = {  # vei 为页面级签名，实时从控制台页刮取
            "task": "5", "folder_id": folder_id, "pg": page,
            "vei": self._get_vei(),
        }
        r = self._request(
            "POST", f"{UP_URL}/doupload.php?uid={self.uid}", data=params,
            headers={
                "X-Requested-With": "XMLHttpRequest",
                "Content-Type": "application/x-www-form-urlencoded; charset=UTF-8",
                "Origin": UP_URL,
                "Referer": f"{UP_URL}/mydisk.php?item=files&action=index&u={self.uid}",
            },
        )
        j = r.json()
        if j.get("zt") not in ("1", "2", 1, 2):
            raise LanzouError(f"获取文件列表失败: {j}")
        return j.get("text") or []

    # ---------------- 删除 ----------------

    def delete_file(self, file_id: str) -> str:
        """删除文件（file_id 为数字 ID，来自上传返回的 Share.id 或 list_files 的 id）"""
        if not self.uid:
            self.uid = self.s.cookies.get("ylogin") or self.s.cookies.get("ylogins")
        self.s.headers.update({
            "Origin": UP_URL,
            "Referer": f"{UP_URL}/mydisk.php?item=files&action=index&u={self.uid}",
        })
        r = self._request(
            "POST", f"{UP_URL}/doupload.php",
            data={"task": "6", "file_id": str(file_id)},
            headers={"X-Requested-With": "XMLHttpRequest",
                     "Content-Type": "application/x-www-form-urlencoded; charset=UTF-8"},
        )
        try:
            j = r.json()
        except json.JSONDecodeError:
            raise LanzouError(f"删除响应无法解析（可能未登录或会话过期）: {r.text[:200]}")
        if j.get("zt") != 1:
            raise LanzouError(f"删除失败 zt={j.get('zt')}: {j.get('info') or j}")
        return str(j.get("info", "已删除"))

    # ---------------- 文件夹 ----------------

    def create_folder(self, folder_name: str, parent_id: str = "0",
                      folder_description: str = "") -> str:
        """创建文件夹，返回新文件夹的数字 ID"""
        if not self.uid:
            self.uid = self.s.cookies.get("ylogin") or self.s.cookies.get("ylogins")
        self.s.headers.update({
            "Origin": UP_URL,
            "Referer": f"{UP_URL}/mydisk.php?item=files&action=index&u={self.uid}",
        })
        r = self._request(
            "POST", f"{UP_URL}/doupload.php",
            data={"task": "2", "parent_id": str(parent_id),
                  "folder_name": folder_name, "folder_description": folder_description},
            headers={"X-Requested-With": "XMLHttpRequest",
                     "Content-Type": "application/x-www-form-urlencoded; charset=UTF-8"},
        )
        try:
            j = r.json()
        except json.JSONDecodeError:
            raise LanzouError(f"创建文件夹响应无法解析: {r.text[:200]}")
        if j.get("zt") != 1:
            raise LanzouError(f"创建文件夹失败 zt={j.get('zt')}: {j.get('info') or j}")
        return str(j.get("text", ""))

    def delete_folder(self, folder_id: str) -> str:
        """删除文件夹（folder_id 为文件夹数字 ID）"""
        if not self.uid:
            self.uid = self.s.cookies.get("ylogin") or self.s.cookies.get("ylogins")
        self.s.headers.update({
            "Origin": UP_URL,
            "Referer": f"{UP_URL}/mydisk.php?item=files&action=index&u={self.uid}",
        })
        r = self._request(
            "POST", f"{UP_URL}/doupload.php",
            data={"task": "3", "folder_id": str(folder_id)},
            headers={"X-Requested-With": "XMLHttpRequest",
                     "Content-Type": "application/x-www-form-urlencoded; charset=UTF-8"},
        )
        try:
            j = r.json()
        except json.JSONDecodeError:
            raise LanzouError(f"删除文件夹响应无法解析: {r.text[:200]}")
        if j.get("zt") != 1:
            raise LanzouError(f"删除文件夹失败 zt={j.get('zt')}: {j.get('info') or j}")
        return str(j.get("info", "删除成功"))


def main():
    ap = argparse.ArgumentParser(description="蓝奏云上传/删除/文件夹管理，并获取分享外链 + 提取码")
    ap.add_argument("username", nargs="?",
                    help="蓝奏云账号（缺省时读环境变量 LANZOU_USERNAME）")
    ap.add_argument("password", nargs="?",
                    help="蓝奏云密码（缺省时读环境变量 LANZOU_PASSWORD）")
    ap.add_argument("file", nargs="?", help="要上传的本地文件路径（文件夹操作时不需要）")
    ap.add_argument("folder", nargs="?", default="-1", help="目标文件夹id(默认-1=根目录)")
    ap.add_argument("--delete", metavar="FILE_ID", default=None, help="删除指定数字ID的文件")
    ap.add_argument("--create-folder", metavar="NAME", default=None,
                    help="创建文件夹（可选 --parent 指定父文件夹ID）")
    ap.add_argument("--parent", metavar="ID", default="0", help="创建文件夹的父文件夹ID(默认0=根目录)")
    ap.add_argument("--delete-folder", metavar="FOLDER_ID", default=None,
                    help="删除指定数字ID的文件夹")
    args = ap.parse_args()

    # 凭据优先级：命令行参数 > 环境变量
    username = args.username or os.getenv("LANZOU_USERNAME")
    password = args.password or os.getenv("LANZOU_PASSWORD")
    if not username or not password:
        raise SystemExit(
            "缺少账号密码：请传入位置参数，或设置环境变量 "
            "LANZOU_USERNAME / LANZOU_PASSWORD")

    lz = Lanzou(username, password)
    uid = lz.login()
    print(f"登录成功，uid = {uid}")

    if args.delete_folder:
        info = lz.delete_folder(args.delete_folder)
        print(f"删除文件夹 {args.delete_folder}：{info}")
        return

    if args.create_folder:
        fid = lz.create_folder(args.create_folder, args.parent)
        print(f"创建文件夹「{args.create_folder}」成功，文件夹ID = {fid}")
        if not args.file:
            return

    if args.delete:
        info = lz.delete_file(args.delete)
        print(f"删除文件 {args.delete}：{info}")
        return

    if not args.file:
        ap.print_help()
        return

    share = lz.upload_file(args.file, args.folder)
    print("\n=== 分享信息 ===")
    print(f"外链地址: {share.url}")
    print(f"提取码  : {share.pwd or '(无提取码)'}")
    print(f"文件名  : {share.name}")
    print(f"文件ID  : {share.id}   (删除时用 --delete {share.id})")


if __name__ == "__main__":
    main()