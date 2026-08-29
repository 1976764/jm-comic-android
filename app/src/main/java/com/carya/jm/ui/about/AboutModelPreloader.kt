package com.carya.jm.ui.about

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.webkit.WebViewAssetLoader
import android.os.Build
/**
 * 关于页 3D 模型（WebView + model-viewer.js）预加载器。
 *
 * 生命周期由调用方（"我的"页）驱动：
 * - [preload]：进入"我的"页时调用 —— 创建 WebView 并加载 about_model.html，
 *   以 INVISIBLE 状态挂在 Activity 的 content FrameLayout 上。
 *   INVISIBLE（非 GONE）仍会参与测量/布局，尺寸非零，
 *   model-viewer 内部的 ResizeObserver 才会初始化并加载模型。
 * - [obtain]：进入关于页时调用 —— 取走已预热的 WebView（模型已加载/加载中），
 *   迁移到 Compose 层直接显示，实现"秒开"。
 * - [release]：离开"我的"页时调用 —— 延迟销毁（延迟窗口内若关于页取走则取消），
 *   避免导航"我的 → 关于"瞬间把即将复用的 WebView 销毁。
 */
object AboutModelPreloader {

    /** 离开"我的"页后多久销毁预加载的 WebView（毫秒） */
    private const val DESTROY_DELAY_MS = 1500L

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 预加载的 WebView（未被取走时才非空） */
    private var webView: WebView? = null

    /** WebView 当前挂载的宿主容器 */
    private var hostView: ViewGroup? = null

    private var pendingDestroy: Runnable? = null

    /**
     * 进入"我的"页时调用：创建（或复活）预加载的 WebView 并开始加载 3D 模型页面。
     * 幂等：已存在时直接复用。
     */
    fun preload(context: Context) {
        cancelPendingDestroy()

        if (webView != null) {
            // 已有预加载实例（例如快速切回"我的"页），直接复用
            return
        }

        val activity = context as? Activity ?: return
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return

        val heightPx = (context.resources.displayMetrics.heightPixels / 3f).toInt()

        val wv = createAboutWebView(context, heightPx)
        wv.visibility = View.INVISIBLE

        webView = wv
        hostView = content
        content.addView(
            wv,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                heightPx,
            ),
        )
    }

    /**
     * 进入关于页时调用：取走预加载的 WebView。
     * 返回 null 表示没有可复用的实例（调用方需自行创建）。
     */
    fun obtain(context: Context, heightDp: Int): WebView? {
        cancelPendingDestroy()

        val wv = webView ?: return null
        webView = null
        hostView = null

        // 从预加载宿主上摘下，交由 Compose 层管理
        (wv.parent as? ViewGroup)?.removeView(wv)
        wv.visibility = View.VISIBLE
        val density = context.resources.displayMetrics.density
        wv.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            (heightDp * density).toInt(),
        )

        // 快速进入场景：WebView 首次加载尚未完成就被摘挂（detach → reattach），
        // Chromium 的加载流程可能就此停摆，模型永远不会出现。
        // 等重新挂载完成后检查模型就绪标记，未就绪则 reload 一次 ——
        // 此时 WebView 已稳定挂载且可见，重走加载不会被中断。
        wv.post {
            wv.evaluateJavascript("(window.__modelReady === true)") { result ->
                if (result != "true") {
                    wv.reload()
                }
            }
        }
        return wv
    }

    /**
     * 离开"我的"页时调用：延迟销毁预加载实例。
     * 若在延迟窗口内被关于页 [obtain] 取走，则取消销毁。
     */
    fun release() {
        val wv = webView ?: return
        val host = hostView

        val task = Runnable {
            pendingDestroy = null
            destroyWebView(wv, host)
        }
        pendingDestroy = task
        mainHandler.postDelayed(task, DESTROY_DELAY_MS)
    }

    /** 立即销毁一个已脱离预加载器管理的 WebView（关于页退出时调用） */
    fun destroyWebView(view: WebView) {
        destroyWebView(view, null)
    }

    private fun destroyWebView(view: WebView, host: ViewGroup?) {
        try {
            view.stopLoading()
            (view.parent as? ViewGroup)?.removeView(view)
            if (view.parent != null && host != null) {
                host.removeView(view)
            }
            view.destroy()
        } catch (_: Exception) {
        }
    }

    private fun cancelPendingDestroy() {
        pendingDestroy?.let { mainHandler.removeCallbacks(it) }
        pendingDestroy = null
    }

    /**
     * 构建加载 about_model.html 的 WebView（预加载与关于页共用同一套配置）。
     * 通过 WebViewAssetLoader 以 https://appassets.androidplatform.net 域名提供 assets，
     * 绕过 file:// 下 Fetch/XHR 的跨源限制。
     */
    fun createAboutWebView(
        context: Context,
        heightPx: Int
    ): WebView {

        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler(
                "/assets/",
                WebViewAssetLoader.AssetsPathHandler(context)
            )
            .build()

        return WebView(context).apply {

            // 显式使用硬件渲染
            setLayerType(
                View.LAYER_TYPE_HARDWARE,
                null
            )

            // 提高 WebView Renderer 优先级
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                setRendererPriorityPolicy(
                    WebView.RENDERER_PRIORITY_IMPORTANT,
                    true
                )
            }

            // WebView 尺寸
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                heightPx
            )

            // 透明背景
            setBackgroundColor(
                android.graphics.Color.TRANSPARENT
            )

            // 禁止过度滚动效果
            overScrollMode =
                View.OVER_SCROLL_NEVER

            settings.apply {

                javaScriptEnabled = true

                domStorageEnabled = true

                databaseEnabled = true

                allowFileAccess = true

                allowContentAccess = true

                javaScriptCanOpenWindowsAutomatically = true

                mediaPlaybackRequiresUserGesture = false

                cacheMode = WebSettings.LOAD_DEFAULT

                // 不让 WebView 自动缩放页面
                loadWithOverviewMode = false
                useWideViewPort = false
            }

            webViewClient = object : WebViewClient() {

                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest
                ): WebResourceResponse? {

                    return assetLoader
                        .shouldInterceptRequest(
                            request.url
                        )
                }
            }

            loadUrl(
                "https://appassets.androidplatform.net/assets/about_model.html"
            )
        }
    }
}
