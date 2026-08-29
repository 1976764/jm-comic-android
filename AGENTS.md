# JM 项目记忆（从 Trae 导入）

> 来源：Trae 项目记忆 `C:\Users\Yuan\.trae-cn\memory\projects\-c-Users-Yuan-AndroidStudioProjects-JM--p2-a826b367672ab9d64a0d`
> （`project_memory.md` 的 98 条硬约束 + 2026-08-13 ~ 2026-08-17 每日 topics 会话摘要）
> 导入整理日期：2026-08-17。以下均为踩坑后沉淀的约束，改动相关代码前先对照本条。

## 项目概览

JM 漫画阅读器 Android App：
- Kotlin + Jetpack Compose（单 Activity，全 Compose UI）
- Chaquopy 17.0.0 内嵌 Python 3.13，`jm_bridge.py` 桥接 **jmcomic** 库（API 文档：https://jmcomic.readthedocs.io/zh-cn/latest/api/client/）
- 图片职责分离：Python 负责取图 URL / 解密存本地文件，Android 负责下载 / 缓存 / 显示（Coil/Glide）
- 当前进行中（2026-08-17）：漫画 zip 下载（`download_album`）+ 下载管理器 + 离线阅读

## 构建环境（硬约束）

- **Gradle 8.7**（官方支持 Java 21 运行时，Android Studio JBR 21.0.8）；**不要用 8.4**（8.4 与 Java 21 运行时不兼容，会报 "Current thread does not hold the state lock"）
- **AGP 8.3.2**（Chaquopy 17.0.0 与 AGP 8.7+ 不兼容）
- 构建目录配置用 Gradle 8.x 推荐 API：`layout.buildDirectory.set(file(...))`；**禁止直接赋值 `buildDir`**（否则 `:app:compileDebugKotlin` 报 state lock 错误）
- `gradle.properties` 必须含 `org.gradle.configureondemand=false`（避免 state lock 冲突）
- 曾遇 NTFS 目录损坏（build/intermediates），可将构建目录指到新路径（build_new / build_v2）绕过
- Release 构建必须开启 `isMinifyEnabled = true` + `isShrinkResources = true`
- ProGuard 必须包含 Chaquopy、Kotlin、数据模型、Coil、Navigation 的 keep 规则，防止 R8 破坏运行时反射与 Python 桥
- Python wheels：已为 Python 3.13 / **arm64-v8a** 构建全部 8 个原生包（含 cffi 2.0.0、curl-cffi，NDK 27.3 + sdkmanager）
- 模拟器注意：MuMu 模拟器的 native bridge 会把 `__system_property_get@LIBC` 截断成 `operty_get`（dlopen 失败）→ **用 ARM64 真机测试**
- APK 体积：clean 后 debug ≈34.45MB、release ≈28.38MB；R8 结果增量复用，不必每次 clean

## 数据加载

- 详情页必须复用列表页已有数据（封面、标题、作者等），只请求缺失字段再 merge（`ComicDetailViewModel.mergeDetail()`）；列表页传来的非空字段优先保留
- 阅读器直接复用详情页章节数据（episodes 透传），避免冗余 `getAlbumDetail` 请求
- **详情页预加载（2026-08-17 新增）**：`ComicDetailViewModel.maybeStartPreload()` —— 进入详情页且未下载、未在下载时，自动①拉第一章章节信息（暖 Python `_photo_cache` + 写 `photos/` 磁盘缓存）②`downloadImagesBatch` 预下载第一章全部原图到 `photos_raw/`（阅读器打开后只需本地解扰）③预拉第二章章节信息；`onCleared()`（离开详情页）取消
- **章节信息加载不再整页遮罩动画**（2026-08-17）：`ReaderScreen` 的 `isLoading` 分支从全屏转圈页改为顶部 2dp 细进度条 + 轻提示；配合预加载 + 元数据缓存保留，打开章节通常瞬间完成
- 章节加载动画：`isLoading` 初始为 false；`loadChapter` 先查缓存，命中则不显示加载动画；切换章节用 `currentEpisodeId` 检查，防止旧章节状态覆盖新章节
- 分类详情页（最新 / 最多喜欢 / 总排名）**不用首页缓存**，始终拉新数据（`useCache=false`），且刷新不得污染首页缓存
- 首页列表缓存键必须含 orderBy：`home_${time}_${category}_${orderBy}.json`（曾因缺 orderBy 导致跨分类缓存污染）
- `HomeViewModel` 仅在 `useCache=true` 且查询匹配首页默认值（orderBy=mv, category=0, time=a）时读写缓存
- `CategoryDetailScreen` 构造 ViewModel 时必须传 `useCache=false`

## 登录会话

- 启动跳过密码重登：`restore_session()` 直接注入缓存 cookie，**不调用 `favorite_folder()` 网络校验**（原流程 Step 2 校验常失败导致 Step 3 密码重登 + 加载）
- cookie 过期时用保存的凭据**静默重登录**，不打断 UI（`ProfileViewModel` 继承 `AndroidViewModel` 以访问 SharedPreferences）
- 冷启动优先展示缓存的用户信息

## 缓存清理（冷启动自动执行）

- 每次冷启动自动清除上次的漫画**阅读图片**缓存：`photos_raw/`、`photos_decoded/`
- **保留** `home_*.json`（首页列表缓存）、`favorites.json`（收藏缓存）、**以及 `albums/`（详情）和 `photos/`（章节信息）元数据**——元数据仅几 KB，保留可让章节信息/详情页冷启动后秒开、无加载动画（注意：这修改了 Trae 原记忆里"清理 albums/ 和 photos/"的约束，用户 2026-08-17 明确只要清图片）
- 在 `MainActivity.onCreate()` 的 `CacheManager.init()` 之后，用 `lifecycleScope.launch(Dispatchers.IO)` 后台执行 `clearReadingCache()`，不阻塞 UI
- 不清除当前阅读会话正在使用的解密图；清理要原子，避免部分删除导致显示问题

## 图片加载架构

- `AsyncJmApiClient` **全局复用一次**（`option.new_jm_async_client()`，利用内部 AsyncSession），禁止每张图新建实例；App 销毁时正确关闭防泄漏
- 并发：前台 8-12、后台 4-8（`JMComic` 默认 30 要调低：`download.threading.image` 对应配置）；失败指数退避重试；请求超时防挂起；移动数据弱网降并发
- Python 返回图片 URL 或解密后文件路径，**不要跨语言传原始字节数组**
- Android 用 Coil/Glide 负责下载缓存、预加载、解码；内存缓存大小按 app 内存平衡配置
- 预加载策略：当前 ±1 页立即加载，+2~5 高优先级，+6~10 低优先级，远处页面释放 bitmap；离开阅读器取消预加载；`updateVisibleIndex()` 按可见页管理
- **图片逐张即时显示（2026-08-17）**：`BATCH_SIZE` 从 8 降到 **3**（更早出图）；`descrambleBatch()` 改为每张解扰完成**立即** `updateImageState`（不再等整批处理完一起显示），`updateImageState` 用 `synchronized(stateLock)` 保证并行 worker 写 `_state` 不丢更新
- **章节循环预加载（2026-08-17）**：`ReaderViewModel.preloadNextChapter()` —— 每打开第 N 章（`applyPhotoInfo` 触发）就预加载第 N+1 章的章节信息 + 前 6 张原图（`preloadedChapters` 去重、已下载漫画跳过）；详情页负责第 1 章，阅读器延续后续链条
- **解扰并行化（2026-08-17）**：`ReaderViewModel.descrambleBatch()` 用 `Dispatchers.IO.limitedParallelism(2)` 并行解扰每批（8 张）已下载图片（全分辨率内存大，2 并发平衡），下载循环与重排逻辑共用该 helper
- 用 `get_scramble_id()`（走内置缓存）而非 `fetch_scramble_id()`；scramble_id 每章取一次并复用
- `get_photo_detail()` 不需要额外字段时设 `fetch_album=False`、`fetch_scramble_id=False`
- 宽高比：用 `BitmapFactory.Options` + `inJustDecodeBounds=true` 从缓存/下载文件读**实际尺寸**（`readImageAspectRatio()`），不依赖默认或旧比例、不依赖 Python 元数据；失败才回退 `defaultAspectRatio`
- 解码 + 读尺寸都在 `Dispatchers.IO`，不阻塞 UI；采样率匹配显示尺寸减少内存

## UI 细节

- 顶部漫画标题栏固定 **48dp**（+ 状态栏高度），用 `Surface > Row` 结构（不要 `TopAppBar`），去掉双重状态栏 padding
- 详情页整页用 `LazyColumn`（不要 Column+verticalScroll）；章节列表 `items()` + `key = { it.id }`；`EpisodeItem` 独立 composable 缩小重组范围
- 章节项用 `Surface(onClick=...)`（带 shape）代替 `Modifier.clip().clickable()`；elevation 默认 0dp；固定宽度对齐父容器
- 转场动画：退出用 **slideOutHorizontally + fadeOut 组合（220ms）**，保证旧页移出屏幕、可见的新页立即响应触摸（单纯 fadeOut 会导致"页面可见但触摸被拦截"）；前进页从右滑入
- 回顶按钮深/浅色模式曾反色：`containerColor` 与 `contentColor` 互换即修
- 阅读器功能弹窗：标题 `Text` 加 `Modifier.weight(1f)` + `maxLines = 1` + `TextOverflow.Ellipsis`，去掉冗余 Spacer，防止长章节名把弹窗撑到超宽

## 分类页

- 移动端 API（`/categories/filter?c=`）只识别 `0/doujin/single/short/another/hanman/meiman/3D` 等官方 slug；**`english_site`、`allages`、`chinese` 等其它值会被服务器静默回退成"全部"**（2026-08-18 用 jmcomic 2.7.4 实测确认：c=english_site 与 c=allages 返回和 c=0 完全相同的列表）→ `CategoryGroups.kt` 中已**移除 "English Manga"(english_site) 和 "一般向韩漫"(allages) 两个无效分类项**；以后新增分类必须先在移动端 API 上验证该 slug 有效
- ProfileScreen 默认显示 3 条收藏 + 超过 3 条时显示"更多收藏 (N)"按钮 → 进入 FavoritesScreen
- FavoritesScreen：2 列网格（ComicCard），无限滚动（距底部 4 项触发 loadMore），ID 去重，用 `total` 判断 hasMore，含 loading/error/empty 状态、刷新、回顶、"没有更多"

## 收藏功能（2026-08-18 新增）

- **详情页收藏按钮**：`ComicDetailScreen` 顶栏心形按钮（实心红=已收藏/点击取消；空心=点击收藏）；`ComicDetailViewModel.isFavorited`（启动时读 `getCachedFavorites` 缓存初始化，**不发请求**）+ `toggleFavorite()`：乐观更新 → Python `toggle_favorite`（jmcomic 移动端 `add_favorite_album` POST /favorite 为**切换语义**：已收藏再次调用即取消）→ 失败回滚；成功后**本地收藏缓存即时增删**（`updateFavoritesCache`，用详情数据构造 ComicItem 头插/移除）+ **后台 `favorite_folder` 刷新写缓存并校正状态**（`refreshFavoritesFromServer`）
- **收藏缓存优先（不额外请求）**：`ProfileViewModel` — `init` 读缓存直接展示；`loadFavorites()` 有缓存即返回；`refreshFavoritesInBackground()` **有缓存则跳过网络请求**；仅当用户收藏/取消收藏后（`toggleFavorite` → 后台刷新写缓存，`CacheManager.favoritesVersion` +1）→ `ProfileViewModel` 收集 `favoritesVersion` 自动重读缓存同步
- **缓存版本号**：`CacheManager.favoritesVersion: StateFlow<Int>`，每次 `cacheFavorites` 写入 +1，供同会话 UI（我的页）监听到变化后重新读缓存，避免额外请求
- 注：收藏需要登录；未登录时 `toggleFavorite` 会失败并回滚（心形不变）

## 下载功能（2026-08-17 已重构为 download_album 方案）

- **多本并发下载（2026-08-18）**：`DownloadService` 从单 job + 单 progressFlow 重构为 **albumId → Job + ConcurrentHashMap 进度表**，暴露 `activeDownloads: StateFlow<Map<String, DownloadProgress>>`；每本独立 job/进度/通知（通知 id = NOTIFICATION_ID + albumId.hashCode()%10000 + 1），前台为汇总通知（"正在下载 N 本" + 总进度）；完成/失败条目保留 3 秒后清理，无进行中下载时停服。`ComicDetailViewModel.downloadAlbum` 只禁止**同一本**重复下载；`DownloadScreen` 渲染多个 DownloadingItem 卡片；`cancelDownload(context, albumId)` 支持按本取消

## 域名测速（2026-08-17 新增）

- **启动测速**：`MainActivity` 延迟 1.5s 后在后台调用 `PythonService.testDomains(PythonService.DOMAIN_CANDIDATES)`（候选：`www.cdnhjk.net` / `www.cdngwc.cc` / `www.cdngwc.net` / `www.cdngwc.club` / `www.cdnutc.me`），对每个域名做 N=3 次 **TCP:443 连接探测**（`_probe_tcp`，并发线程池）统计平均延迟和丢包率，排序：成功优先 → 丢包率低 → 延迟低
- **应用机制**：最优域名写入 `_best_domain`，通过 `_apply_best_domain(option, client)` 生效——`client.domain_list = [best]`（立即生效）且 `option.client.domain = [best]`（后续新建 client 生效）；`_ensure_client` / `login` / `restore_session` 创建 client 后都会调用
- **设置页域名选择（2026-08-17）**：`SettingsScreen` 增加"网络域名"区块——顶部显示**当前使用域名**、`重新测速`按钮（刷新各域名延迟/丢包）、`自动选择（推荐）`行 + 每个候选域名的延迟（ms）和丢包率列表；点选某域名 → `AppSettings` 记录 `selectedDomain` + `domainManual=true` 并调 Python `set_domain` 立即生效；点"自动选择"→ 重新测速并跟随最优。手动选择后**启动自动测速不再覆盖**（`domainManual` 判断）；测速结果存 `AppSettings.domainTestResults`（results 数组 JSON）供设置页展示
- **与 jmcomic 官方域名机制兼容**：`update_old_api_domain` 只在当前列表等于默认列表时才替换，所以自定义单域名列表不会被 `auto_update_domain` 官方自动更新覆盖（无需关 `FLAG_API_CLIENT_AUTO_UPDATE_DOMAIN`）
- 测速失败（全域名不可达）时保持 jmcomic 默认域名，不影响使用；`applied` 为 null

## 下载功能（2026-08-17 已重构为 download_album 方案）

- 详情页下载按钮 → `DownloadService.startDownload`（传 albumId/title/author/coverUrl/episodes），内部走 **jmcomic 官方 `download_album`**：Python `download_album_api` 构建独立 JmOption（`Bd_Pid` 规则 → `{output_dir}/{photo_id}/{filename}`，`decode=False` 存原始加密图），**多线程**：`threading.image=10`（每章并发取图）+ `threading.photo=2`（章节并行，可在 payload 覆盖），`download.cache=True`（跳过已存在文件，断点续传友好）
- 关键坑：jmcomic 2.7.4 的 `call_all_plugin` 只认 `REGISTRY_PLUGIN` 里注册的插件 key，直接塞匿名函数会**静默不触发**（旧实现进度回调从未生效）→ 正确做法是**子类化 `JmDownloader`**，覆写 `before_photo`/`after_image`（必须调 `super()` 保持 `download_success_dict` 记账），并覆写 `create_client()` 返回共享的 `_ensure_client()`（保证登录 cookie 生效）
- 进度：Python 回调加 `threading.Lock`，把 `{status,total_images,downloaded_images,total_chapters,current_chapter,chapter_title,error}` 写入 `cacheDir/download_progress_{albumId}.json`；Kotlin 在独立协程跑阻塞的 `downloadAlbumApi`，另一个协程每 400ms 轮询进度文件更新 UI/通知
- 解扰：`download_album` 下载期间 Python 在 `before_photo`（章节开始）就把该章 `{filename, num}` 映射合并写进 `cacheDir/download_descramble_{albumId}.json`（`_album_chapter_entry` + `_merge_descramble_entry`，纯计算无网络）；**Kotlin 的 worker 协程与下载并行**——轮询映射 + 磁盘，已落盘的文件立即用 `limitedParallelism(2..4)` 解扰（`collectPendingWork` 用 `processedPaths` 并发去重，失败文件释放名额留给最终 drain 重试）；下载完成后最终扫描补齐完整映射（`{photo_id:{title,files:[{filename,num}]}}`，不含 path，Kotlin 由 `offlineDir/{photo_id}/{filename}` 推导），`worker.join()` 后 drain 剩余文件
- **解扰提速要点**：`ImageDescrambler` 原来 WebP **无损(quality=100)** 重编码极慢（几百 ms/张）→ 改为按输入格式快速编码：WebP 有损 q92（API 30+ 用 `WEBP_LOSSY`）、JPEG q92、PNG 无损；配合 2~4 线程并行 + 与下载并行，整体大幅提速（同时惠及阅读器逐张解扰路径）
- **设置页**：`ui/settings/SettingsScreen.kt`（"我的"页设置区块新增"设置"入口，未登录态右上角齿轮也可进）；`AppSettings`（SharedPreferences 单例，MainActivity init）提供 `storeZipToDownloads`（**默认 false**）：关闭时下载只存应用内部（离线阅读用），不往设备默认下载目录写 zip；开启时才 `createZip` + `saveZipToDownloads`（Downloads/JM）
- 收尾：zip 打包 → MediaStore 导出到 Downloads/JM → `DownloadManager.addDownload` 记录元数据（离线阅读用）
- 注意：Python 长调用期间 Chaquopy 解释器被独占，UI 的其它 Python 操作会排队等待；下载取消只停 Kotlin 侧，Python 下载会跑完（下次 cache=True 跳过已下文件）
- **401 "请先登入会员" 自愈（2026-08-18）**：更多收藏页 `FavoritesViewModel`（已改为 AndroidViewModel）在 `favoriteFolder` 返回鉴权错误（`请先登入`/`401`/`cookie` 关键字）时，用 `auth` SharedPreferences 里保存的凭据**静默重登录一次**（`python.login`，成功后写回 session_json）再重试请求；`loadMore` 同样在 401 时自动重登后重试。根因：`restore_session` 冷启动恢复的旧 cookies 可能已失效/域名不匹配（收藏接口是唯一需要鉴权的请求），浏览接口无需鉴权所以正常——遇到 401 由本页自愈而不是提示错误页
- 参考 API：https://jmcomic.readthedocs.io/zh-cn/latest/api/client/
