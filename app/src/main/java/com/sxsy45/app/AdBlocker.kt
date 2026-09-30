package com.sxsy45.app

import android.net.Uri
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream

/**
 * 站点广告 / 垃圾版块的统一屏蔽规则。
 *
 * 依据是站点首页离线快照（`forum.php`）的真实结构，不是猜的：
 *
 *  1) 弹出广告 = Discuz「popadv 弹出广告」插件，页面底部会输出
 *     `<div id="popadv_popmenu">`（内含广告图 + 「广告」角标 + 关闭按钮）、
 *     `<div id="popadv_popmask">` 遮罩，并在其后加载 `popadv.js`，
 *     再由内联脚本 `popadv_attachEvent(window,'load',popadv_load,document)` 在 window.load 时弹出。
 *     → 这里直接把 `popadv` 相关的资源请求换成**同名的空实现**，脚本照常加载成功但什么都不做，
 *       弹窗容器保持服务端渲染的 `display:none`，从源头不会有广告（比「先显示再隐藏」更干净）。
 *  2) 综合区里有三个「外部链接」型版块（Discuz 里这类版块点击后 302 到站外推广站）：
 *     抖阴小视频 fid=19、涩里番 fid=21、海角社区 fid=25。
 *     → 这里给出版块黑名单：页面里连 DOM 一起摘掉，导航到这些 fid 也直接拦下。
 *
 * 加规则的地方就两处：[isAdUrl]（资源层）与 [HIDDEN_FIDS]（版块层），
 * 页面内清理脚本见 [cleanupJs]。
 */
object AdBlocker {

    /**
     * 要屏蔽的版块（fid）。来自站点首页快照：
     * 19 = 抖阴小视频、21 = 涩里番、25 = 海角社区，三者均为「链接到外部地址」型版块。
     */
    val HIDDEN_FIDS = setOf(19, 21, 25)

    /** 版块黑名单的中文名，仅用于日志/提示，便于对照排查 */
    val HIDDEN_FORUM_NAMES = mapOf(19 to "抖阴小视频", 21 to "涩里番", 25 to "海角社区")

    /** 资源层拦截：URL 里出现这些片段即视为广告资源 */
    private val BLOCKED_URL_PARTS = listOf(
        "popadv",            // 站点在用的弹出广告插件（脚本本体 + 点击统计接口）
        "advid=", "adv_id=", "adshow", "adimage", "adcode", "advert", "adsense",
        "/ads/", "ads.php", "ad_click", "click_ad", "ggimg", "guanggao", "ad_banner"
    )

    /** 资源层拦截：已知广告联盟域名（站点若挂第三方广告会走这些） */
    private val BLOCKED_HOST_PARTS = listOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com",
        "adservice.google.", "pagead2.", "cpro.baidu.com", "pos.baidu.com",
        "union.baidu.com", "alimama.", "tanx.com", "admaster.", "ipinyou.com"
    )

    /**
     * popadv.js 的替代实现：函数全部存在但什么都不做。
     * 站点内联脚本会照常注册 `window.load` 回调，只是回调是空的，
     * 弹窗容器不会被设成 display:block —— 不报错、不闪烁、也没有广告。
     */
    private const val POPADV_STUB = """
function popadv_attachEvent(){}
function popadv_load(){return false;}
function popadv_loadadv(){return false;}
function popadv_loadCheck(){return false;}
function popadv_loading(){return false;}
function popadv_showadv(){return false;}
function popadv_closeadv(){return false;}
function popadv_getdata(){return null;}
function popadv_setdata(){}
function popadv_getadv(){return null;}
function popadv_setadv(){return false;}
function popadv_aparttime(){return true;}
function popadv_intertime(){return true;}
"""

    /** 该资源是否应该被拦掉（广告脚本 / 广告图 / 广告点击统计接口） */
    fun isAdUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val l = url.lowercase()
        if (BLOCKED_URL_PARTS.any { l.contains(it) }) return true
        val host = try { Uri.parse(l).host?.lowercase().orEmpty() } catch (e: Exception) { "" }
        return host.isNotEmpty() && BLOCKED_HOST_PARTS.any { host.contains(it) }
    }

    /** 广告资源的替代响应：popadv 给空实现脚本，其余给空体 */
    fun blockedResponse(url: String?): WebResourceResponse {
        val l = url?.lowercase().orEmpty()
        val isPopadv = l.contains("popadv")
        val isJs = l.contains(".js")
        val body = if (isPopadv && isJs) POPADV_STUB else ""
        val mime = if (isJs) "application/javascript" else "text/plain"
        return WebResourceResponse(mime, "utf-8", ByteArrayInputStream(body.toByteArray()))
    }

    /** 该地址是否指向被屏蔽的版块（forumdisplay&fid=N / forum-N-1.html / 发帖页 fid=N） */
    fun isHiddenForumUrl(url: String?): Boolean {
        val fid = forumIdOf(url) ?: return false
        return fid in HIDDEN_FIDS
    }

    /** 从 URL 里取版块 id */
    fun forumIdOf(url: String?): Int? {
        if (url.isNullOrBlank()) return null
        Regex("[?&]fid=(\\d+)").find(url)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        Regex("/forum-(\\d+)-").find(url)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        return null
    }

    /**
     * 页面内清理脚本（onPageFinished 注入，此时文档已完整解析）。
     *
     * **只在版块列表表格 `table.fl_tb` 里动手，且只删「我们摘掉的那些格子」所在的行** ——
     * 其它表格、其它元素一律不碰。这一点很重要：
     *  - 帖子页/其它页面没有 `table.fl_tb`，脚本直接返回，连碰都不碰；
     *  - 绝不能按「这一行没文字就删掉」来清理：帖子页大量布局表格里就有只放图片/占位符的行，
     *    删掉会破坏列宽，整页排版就错位了；也不能在文档还没解析完时执行（那时行还是空的）。
     */
    fun cleanupJs(): String = """
(function(){
  var FIDS=[${HIDDEN_FIDS.joinToString(",")}];
  var tables=document.querySelectorAll('table.fl_tb');
  if(tables.length){
    try{
      var parents=[], t, i, j;
      for(t=0;t<tables.length;t++){
        var as=tables[t].querySelectorAll('a[href]');
        for(i=0;i<as.length;i++){
          var h=as[i].getAttribute('href')||'';
          var m=h.match(/[?&]fid=(\d+)/)||h.match(/\/forum-(\d+)-/);
          if(!m || FIDS.indexOf(parseInt(m[1],10))<0) continue;
          var cell=as[i], hops=0;
          while(cell && cell.nodeType===1 && hops++<6){
            if(cell.tagName==='TD'||cell.tagName==='LI') break;
            if(cell.className && (''+cell.className).indexOf('fl_g')>=0) break;
            cell=cell.parentNode;
          }
          // 兜底：找不到格子就只摘那个链接，绝不往上摘整块
          if(!cell || cell.nodeType!==1 || !tables[t].contains(cell)) cell=as[i];
          if(cell.parentNode && tables[t].contains(cell)){
            parents.push(cell.parentNode);
            cell.parentNode.removeChild(cell);
          }
        }
      }
      // 只处理「因为摘了格子才变空」的行，且必须仍在版块表内
      for(j=0;j<parents.length;j++){
        var row=parents[j];
        if(!row || row.nodeType!==1 || row.tagName!=='TR') continue;
        var inTable=false;
        for(t=0;t<tables.length;t++){ if(tables[t].contains(row)){ inTable=true; break; } }
        if(!inTable) continue;
        if(row.querySelector('.fl_g')) continue;
        if((row.textContent||'').replace(/\s|\u00a0/g,'').length>0) continue;
        if(row.parentNode) row.parentNode.removeChild(row);
      }
    }catch(e){}
  }
  // 弹窗广告的固定定位容器：任何页面都要清，删掉不影响布局
  try{
    var ids=['popadv_popmenu','popadv_popmask'];
    for(var n=0;n<ids.length;n++){
      var el=document.getElementById(ids[n]);
      if(el && el.parentNode){ el.parentNode.removeChild(el); }
    }
  }catch(e){}
})();
""".trimIndent()
}
