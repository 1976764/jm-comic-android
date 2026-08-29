package com.carya.jm.ui.category

/**
 * 分类页条目：描述一个可点击的分类/排行榜/标签。
 *
 * [kind] 决定加载方式：
 *  - "filter"：走 categories_filter 接口（[category] + [orderBy] + [time]）
 *  - "search"：走 search 接口按标签搜索（[query]，main_tag=3）
 */
data class CategoryEntry(
    val label: String,
    val kind: String,
    val category: String = "0",
    val orderBy: String = "mr",
    val time: String = "a",
    val query: String = "",
)

/** 分类页分组。 */
data class CategoryGroup(
    val title: String,
    val entries: List<CategoryEntry>,
)

/**
 * 站点侧栏的完整分类列表。
 *
 * - "filter" 条目使用 jmcomic categories_filter 接口支持的官方分类/排序参数
 *   （category: doujin/single/short/hanman/meiman/another/3D；
 *    order: mr=最新, tf=最多爱心, mv=总排名; time: a/w/m 配合 mv 组成週/月排名）
 * - 注意：移动端 API 只识别上述 category 值；english_site / allages / chinese
 *   等其它 slug 会被服务器**静默回退成"全部"**（2026-08-18 实测确认），
 *   因此无效分类项一律不放在这里。
 * - "search" 条目为站内标签（主題A漫/角色扮演/特殊PLAY/其他），
 *   通过 search 接口 + main_tag=3（按标签搜索）实现。
 */
val CATEGORY_GROUPS: List<CategoryGroup> = listOf(
    CategoryGroup(
        title = "更多分类",
        entries = listOf(
            CategoryEntry("最新A漫", kind = "filter", category = "0", orderBy = "mr"),
            CategoryEntry("同人", kind = "filter", category = "doujin", orderBy = "mr"),
            CategoryEntry("单本", kind = "filter", category = "single", orderBy = "mr"),
            CategoryEntry("短篇", kind = "filter", category = "short", orderBy = "mr"),
            CategoryEntry("韩漫", kind = "filter", category = "hanman", orderBy = "mr"),
            CategoryEntry("美漫", kind = "filter", category = "meiman", orderBy = "mr"),
            CategoryEntry("Cosplay", kind = "filter", category = "doujin_cosplay", orderBy = "mr"),
            CategoryEntry("3D", kind = "filter", category = "3D", orderBy = "mr"),
            CategoryEntry("禁漫汉化组", kind = "search", query = "禁漫汉化组"),
            CategoryEntry("其他类", kind = "filter", category = "another", orderBy = "mr"),
        ),
    ),
    CategoryGroup(
        title = "漫画排行榜",
        entries = listOf(
            CategoryEntry("最新", kind = "filter", category = "0", orderBy = "mr"),
            CategoryEntry("最多喜欢", kind = "filter", category = "0", orderBy = "tf"),
            CategoryEntry("总排名", kind = "filter", category = "0", orderBy = "mv", time = "a"),
            CategoryEntry("月排名", kind = "filter", category = "0", orderBy = "mv", time = "m"),
            CategoryEntry("周排名", kind = "filter", category = "0", orderBy = "mv", time = "w"),
        ),
    ),
    CategoryGroup(
        title = "主题A漫",
        entries = listOf(
            "剧情向", "校园", "纯爱", "人妻", "师生", "近亲", "百合",
            "YAOI", "性转", "NTR", "伪娘", "痴女", "全彩", "女性向",
        ).map { CategoryEntry(it, kind = "search", query = it) },
    ),
    CategoryGroup(
        title = "角色／扮演",
        entries = listOf(
            "萝莉", "熟女", "正太", "巨乳", "贫乳", "女王", "教师",
            "女仆", "护士", "泳装", "眼镜", "连裤袜", "其他制服", "兔女郎",
        ).map { CategoryEntry(it, kind = "search", query = it) },
    ),
    CategoryGroup(
        title = "特殊PLAY",
        entries = listOf(
            "群交", "足交", "SM", "肛交", "阿黑颜", "药物", "扶他",
            "调教", "野外露出", "催眠", "自慰", "触手", "兽交",
        ).map { CategoryEntry(it, kind = "search", query = it) },
    ),
    CategoryGroup(
        title = "其他",
        entries = listOf(
            "CG集", "重口", "猎奇", "非日", "血腥暴力",
        ).map { CategoryEntry(it, kind = "search", query = it) },
    ),
)

/** 所有条目展平（用于按 label 查找）。 */
val ALL_CATEGORY_ENTRIES: List<CategoryEntry> = CATEGORY_GROUPS.flatMap { it.entries }

/** 按显示名查找分类条目。 */
fun findCategoryEntry(label: String): CategoryEntry? =
    ALL_CATEGORY_ENTRIES.firstOrNull { it.label == label }
