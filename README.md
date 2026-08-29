# JM

> 一款基于 Android 原生开发的 JM Comic 漫画客户端，采用 Jetpack Compose + Python 混合架构。

## 项目简介

JM 是一款 Android 漫画阅读应用，通过 [Chaquopy](https://chaquo.com/chaquopy/) 将 Python `jmcomic` 库嵌入 Android 原生应用，实现漫画的搜索、浏览、阅读与下载功能。前端使用 Jetpack Compose 构建 Material 3 风格的现代化 UI，后端通过 Python 桥接层调用 jmcomic 库完成数据获取与图片处理。

## 技术栈

| 层级 | 技术 | 说明 |
|------|------|------|
| UI 框架 | Jetpack Compose | 全声明式 UI，Material 3 设计 |
| 架构模式 | MVVM | ViewModel + StateFlow 驱动 UI 状态 |
| Python 桥接 | Chaquopy 17.0.0 | Android 内嵌 Python 运行时 |
| 漫画数据源 | jmcomic (Python) | 登录、收藏、章节获取、图片下载 |
| 图片加载 | Coil 2.7.0 | 高效异步图片加载与缓存 |
| 导航 | Navigation Compose | 单 Activity 多页面路由 |
| 语言 | Kotlin + Python | Kotlin 负责 UI 与业务逻辑，Python 负责网络请求与数据处理 |

## 核心功能

### 首页与分类

- **首页推荐** — 2 列网格布局，无限滚动加载漫画列表
- **分类浏览** — 按标签分类，支持分类详情页无限滚动
- **搜索** — 关键词搜索漫画，实时结果展示

### 漫画详情

- **详情展示** — 封面、标题、作者、简介、章节列表
- **章节选择** — 章节列表懒加载，点击进入阅读器
- **性能优化** — LazyColumn 替代 Column+verticalScroll，滑动流畅

### 阅读器

- **连续滚动阅读** — 无限滚动，图片间无拼接痕迹
- **批量并发下载** — Python ThreadPoolExecutor（8 线程）批量下载，提升加载速度
- **滚动位置优先** — 根据用户当前阅读位置动态调整下载优先级，前方预加载 8 张、后方保留 2 张
- **图片解密** — 下载原始加密图片后，Kotlin 端通过 Canvas 条带重排完成解密
- **尺寸预留** — 提前解析图片尺寸，使用 `aspectRatio` 预留高度，避免加载后滚动位置跳动
- **磁盘缓存** — 解密后图片缓存到本地，二次阅读秒加载

### 用户系统

- **登录认证** — 账号密码登录
- **会话持久化** — Cookie 三级恢复机制：
  1. 进程内检查（秒级恢复）
  2. Cookie 恢复（自动验证有效性）
  3. 密码重新登录（Cookie 失效时触发）
- **我的收藏** — 个人页展示前 3 个收藏漫画，点击「更多收藏」进入全量收藏列表（无限滚动）

### 下载管理

- **后台下载** — 独立 DownloadService 支持漫画离线下载
- **下载状态** — 实时显示下载进度与状态

## 架构设计

```
┌─────────────────────────────────────────┐
│              UI Layer (Compose)          │
│   HomeScreen / ReaderScreen / Profile    │
│   SearchScreen / DetailScreen / ...      │
└──────────────┬──────────────────────────┘
               │ StateFlow
┌──────────────▼──────────────────────────┐
│           ViewModel Layer                │
│   HomeViewModel / ReaderViewModel /      │
│   AuthViewModel / FavoritesViewModel /   │
│   ComicDetailViewModel / ...             │
└──────────────┬──────────────────────────┘
               │ Function Call
┌──────────────▼──────────────────────────┐
│          Kotlin Data Layer               │
│   PythonService / CacheManager /         │
│   DownloadManager / DownloadService      │
└──────────────┬──────────────────────────┘
               │ Chaquopy RPC
┌──────────────▼──────────────────────────┐
│         Python Bridge (jm_bridge.py)     │
│   jmcomic 库调用 / 图片下载与处理 /       │
│   会话管理 / 批量并发下载                  │
└─────────────────────────────────────────┘
```

## 项目结构

```
app/src/main/
├── java/com/carya/jm/
│   ├── MainActivity.kt                    # 应用入口
│   ├── data/
│   │   ├── model/Models.kt                # 数据模型
│   │   ├── python/PythonService.kt        # Python 桥接服务
│   │   ├── cache/CacheManager.kt          # 缓存管理
│   │   └── download/                      # 下载管理
│   └── ui/
│       ├── theme/                         # 主题 (颜色/字体/Material 3)
│       ├── components/                    # 通用组件 (ComicCard / BackToTopButton)
│       ├── navigation/AppNavigation.kt    # 路由配置
│       ├── home/                          # 首页 (无限滚动网格)
│       ├── category/                      # 分类 (分类页 + 分类详情)
│       ├── search/                        # 搜索
│       ├── detail/                        # 漫画详情
│       ├── reader/                        # 阅读器 (并发下载 + 图片解密)
│       ├── profile/                       # 个人中心 (收藏预览 + 全量收藏)
│       ├── auth/                          # 认证 (三级会话恢复)
│       └── download/                      # 下载管理
├── python/
│   └── jm_bridge.py                       # Python 桥接 (jmcomic 库封装)
└── res/                                   # 资源文件 (图标/主题/字符串)
```

## 关键技术实现

### Python ↔ Kotlin 混合架构

通过 Chaquopy 在 Android 应用中嵌入 Python 运行时，`jm_bridge.py` 通过操作注册表机制暴露接口，`PythonService.kt` 统一调用。Python 负责网络请求与数据处理，Kotlin 负责 UI 渲染与图片解密。

### 图片解密流程

1. Python 端以 `decode_image=False` 下载原始加密图片字节
2. Python 解析图片文件头获取尺寸信息返回给 Kotlin
3. Kotlin 通过 `JmImageTool.get_num()` 计算条带数
4. `ImageDescrambler` 使用 `BitmapFactory` + `Canvas.drawBitmap` 完成条带重排
5. 解密后图片缓存到 `photos_decrypted/` 目录，二次阅读直接加载

### 批量并发下载

- Python `ThreadPoolExecutor(max_workers=8)` 并发下载多张图片
- Kotlin `ReaderViewModel` 每轮请求 8 张（`BATCH_SIZE=8`）
- `buildPriorityBatch()` 根据用户滚动位置动态排序下载优先级
- 用户滚动时 `reprioritizeIfNeeded()` 取消当前批次，重建优先级队列

### 会话三级恢复

```
App启动 → 检查进程内登录状态
         ├── 已登录 → 直接进入
         └── 未登录 → 尝试 Cookie 恢复
                      ├── Cookie 有效 → 自动恢复
                      └── Cookie 失效 → 密码重新登录
```

## 构建

```bash
# 环境要求
# - Android Studio
# - Python 3.13+ (Chaquopy 需要)
# - JDK 17+

# 编译
./gradlew assembleRelease --no-daemon --no-parallel

# 安装到设备
./gradlew installRelease --no-daemon --no-parallel
```

## 依赖

- **Chaquopy** 17.0.0 — Android Python 运行时
- **Jetpack Compose** — 声明式 UI 框架
- **Coil** 2.7.0 — 图片加载库
- **Navigation Compose** — 页面导航
- **jmcomic** (Python) — 漫画数据源库

---

*本项目仅供学习交流使用。*
