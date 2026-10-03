package com.sxsy45.app

import android.net.Uri
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.util.Collections

/**
 * 站点广告 / 外链推广分区的统一屏蔽规则。
 *
 * 依据是站点离线快照（`forum.php`）与 Discuz! X3.5 官方模板源码的真实结构，不是猜的：
 *
 *  1) 弹出广告 = Discuz「popadv 弹出广告」插件，页面底部会输出
 *     `<div id="popadv_popmenu">`（内含广告图 + 「广告」角标 + 关闭按钮）、
 *     `<div id="popadv_popmask">` 遮罩，并在其后加载 `popadv.js`，
 *     再由内联脚本 `popadv_attachEvent(window,'load',popadv_load,document)` 在 window.load 时弹出。
 *     → 这里直接把 `popadv` 相关的资源请求换成**同名的空实现**，脚本照常加载成功但什么都不做，
 *       弹窗容器保持服务端渲染的 `display:none`，从源头不会有广告（比「先显示再隐藏」更干净）。
 *
 *  2) **外链推广分区**（点了就302 到站外推广站的分区）。
 *     这类分区**不能靠硬编码 fid 黑名单**——站点随时会新增（实测首页快照里已有5 个：
 *     fid=19抖阴小视频、21 涩里番、25 海角社区、40 黄果短剧、41 电子姬AI，
 *     而老黑名单只有 19/21/25，于是 40/41 就漏出来了）。
 *     真正的通用判据来自 Discuz 官方模板 `template/default/forum/discuz.php:279`：
 *     ```
 *     <!--{if $forum['redirect']}-->
 *         <a href="$forumurl" class="xi2">{lang url_link}</a>   ← lang_template.php:238
 *     ```
 *     即 **只要版块是 redirect 型，模板就会在该格子里输出固定文案「链接到外部地址」**
 *     （`forumdisplay_subforum.php:36` 的子版块列表同理）。
 *     所以：**格子文本含「链接到外部地址」 ⇔ 该分区是外链推广分区**，
 *     与 fid 无关，站点新加多少个都能自动命中。
 *     实测分布：仅版块列表页的 `td.fl_g` 命中，帖子页/主题页出现次数为 0，绝不误伤正文。
 *
 * 加规则的地方：[isAdUrl]（资源层）、[isExternalLinkCell]（版块层 DOM 判据）、
 * [rememberExternalFids]（运行期自适应），页面内清理脚本见 [cleanupJs]。
 */
object AdBlocker {

    /**
     * Discuz 语言包 `lang_template.php` 里 `url_link` 的中文原文，
     * 外链 redirect 型分区在版块列表里必渲染这句。
     * 同时保留繁体 `鏈接到外部地址` 与英文 `url_link` 兜底，防止站点改语言包后漏掉。
     */
    private val EXT_LINK_MARKS = listOf("链接到外部地址", "鏈接到外部地址", "url_link")

    /**
     * 已知的内网分区 fid 白名单式兜底：这些 fid 出现在快照里但**不是**外链分区，
     * 防止极端情况下误伤（正常分区不该含外链文案，正常情况下不会被判中，这里只是保险）。
     */
    private val NEVER_EXTERNAL_FIDS = emptySet<Int>()

    /**
     * 运行期动态发现的外链分区 fid。
     *
     * 页面注入脚本发现一个新的外链格子时，会通过 JS 桥把它上报到这里（见 [rememberExternalFids]），
     * 于是**导航拦截**（面包屑/最新回复/搜索结果里残留的外链分区链接）也能跟着自适应，
     * 不再需要为了屏蔽一个新分区去改代码、重装App。
     * 只增不减：站点把某个分区改成内网时，最多多点一次打不开，不会造成内容误隐藏。
     */
    private val learnedExternalFids: MutableSet<Int> =
        Collections.synchronizedSet(LinkedHashSet<Int>())

    /** 版块层拦截的 fid 名，仅用于日志/提示，便于对照排查 */
    private val forumNames: MutableMap<Int, String> =
        Collections.synchronizedMap(HashMap<Int, String>())

    /** 初始化时用快照里已知的外链分区名，便于日志对照（不参与判定） */
    init {
        forumNames[19] = "抖阴小视频"
        forumNames[21] = "涩里番"
        forumNames[25] = "海角社区"
        forumNames[40] = "黄果短剧"
        forumNames[41] = "电子姬AI"
    }

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

    /**
     * JS 桥回调：页面清理脚本每发现一个此前未知的外链分区 fid，就会上报一次。
     * 这样导航拦截能自动跟上，**站点新增外链分区无需更新 App**。
     */
    fun rememberExternalFids(csv: String?) {
        if (csv.isNullOrBlank()) return
        val added = ArrayList<String>()
        for (part in csv.split(',')) {
            val fid = part.trim().toIntOrNull() ?: continue
            if (fid <= 0 || fid in NEVER_EXTERNAL_FIDS) continue
            if (learnedExternalFids.add(fid)) added.add(fid.toString())
        }
        if (added.isNotEmpty()) {
            DebugLog.log("AD", "新发现外链分区 fid=${added.joinToString("/")}，已纳入屏蔽")
        }
    }

    /** 已动态学习到的外链分区 fid（快照 + 运行期新增），供日志展示 */
    fun knownExternalFids(): List<Int> = synchronized(learnedExternalFids) {
        learnedExternalFids.sorted()
    }

    /** 供脚本使用的判据文本（含中文/繁体/英文三种，JS 里统一小写比对） */
    fun extLinkMarksForJs(): String = EXT_LINK_MARKS.joinToString("|") { Regex.escape(it) }

    /** 该地址是否指向已知的外链分区（forumdisplay&fid=N / forum-N-1.html） */
    fun isHiddenForumUrl(url: String?): Boolean {
        val fid = forumIdOf(url) ?: return false
        if (fid in NEVER_EXTERNAL_FIDS) return false
        return synchronized(learnedExternalFids) { fid in learnedExternalFids }
    }

    /** 外链分区名（仅用于提示文案，可能为 null） */
    fun forumNameOf(fid: Int?): String? = fid?.let { forumNames[it] }

    /** 记录外链分区名，供拦截提示展示 */
    fun rememberForumName(fid: Int?, name: String?) {
        if (fid == null || fid <= 0 || name.isNullOrBlank()) return
        val clean = name.trim()
        if (clean.isEmpty() || clean.length > 24) return
        forumNames.putIfAbsent(fid, clean)
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
     * **判据**：只处理 `table.fl_tb`（版块列表）里的 `td.fl_g` 格子和 `li`（子版块列表），
     * 且只摘「文本里含 Discuz 外链分区固定文案『链接到外部地址』」的那些格子。
     *这样做的可靠性来自官方模板 `discuz.php:279`：该文案只在 `$forum['redirect']` 分支输出，
     * 所以**命中即等价于「这是外链推广分区」**，站点新增 fid 也会自动命中；
     * 而且实测帖子页/主题页完全没有这个文案，不会误伤正文与布局。
     *
     * 另外两条安全阀：
     *  - 绝不按「这一行没文字就删掉」清理：帖子页大量布局表格里就有只放图片/占位符的行，
     *    删掉会破坏列宽、整页排版错位；
     *  - 只在文档完整解析后（onPageFinished）执行，那时行才有内容。
     *
     * 顺带把发现的 fid 上报原生（[rememberExternalFids]），让导航层拦截同步自适应。
     */
    fun cleanupJs(): String {
        val marks = EXT_LINK_MARKS.joinToString(",") { "'" + it.replace("'", "") + "'" }
        return """
(function(){
  var MARKS=[$marks];
  var found={}, names={};
  function isExtText(s){
    if(!s) return false;
    for(var i=0;i<MARKS.length;i++){ if(s.indexOf(MARKS[i])>=0) return true; }
    return false;
  }
  function fidOfCell(cell){
    var as=cell.querySelectorAll('a[href]'), m;
    for(var i=0;i<as.length;i++){
      var h=as[i].getAttribute('href')||'';
      m=h.match(/[?&]fid=(\d+)/)||h.match(/\/forum-(\d+)-/);
      if(m) return parseInt(m[1],10);
    }
    return 0;
  }
  function nameOfCell(cell){
    var dt=cell.querySelector('dt'), a, t;
    if(!dt) return '';
    a=dt.querySelector('a');
    if(!a) return '';
    t=(a.textContent||'').replace(/\s|\u00a0/g,'');
    return t.length>24?t.slice(0,24):t;
  }
  var cells=[], sc=document.querySelectorAll('td.fl_g'), i;
  for(i=0;i<sc.length;i++) cells.push(sc[i]);
  sc=document.querySelectorAll('li.normal');
  for(i=0;i<sc.length;i++) cells.push(sc[i]);

  var rows=[], t, c, fid, kept;
  for(t=0;t<cells.length;t++){
    c=cells[t];
    if(!isExtText(c.textContent)) continue;
    // 必须仍在文档里（避免重复处理已摘掉的）
    if(!c.parentNode) continue;
    // 只摘最内层的 fl_g：若它是外层 td 里的嵌套 fl_g，跳过避免连带
    kept=[];
    var ch=c.children, k, hasInner=false;
    for(k=0;k<ch.length;k++){
      if(ch[k].querySelector && ch[k].querySelector('td.fl_g')){ hasInner=true; break; }
    }
    if(hasInner) continue;
    fid=fidOfCell(c);
    if(fid>0){ found[fid]=1; names[fid]=nameOfCell(c); }
    var row=c.parentNode;
    if(row && row.nodeType===1 && (row.tagName==='TR'||row.tagName==='LI'||row.tagName==='UL'||row.tagName==='DIV')){
      rows.push(row);
    }
    if(row) row.removeChild(c);
  }

  // 摘完格子后，把因此变空且仍在版块表内的行也清掉（版式收紧，不留空洞）
  var tables=document.querySelectorAll('table.fl_tb'), j, row, inTable;
  for(j=0;j<rows.length;j++){
    row=rows[j];
    if(!row || !row.parentNode) continue;
    if(row.tagName==='TR'){
      inTable=false;
      for(t=0;t<tables.length;t++){ if(tables[t]===row.parentNode || tables[t].contains(row)){ inTable=true; break; } }
      if(!inTable) continue;
      if(row.querySelector('.fl_g')) continue;
      if((row.textContent||'').replace(/\s|\u00a0/g,'').length>0) continue;
      if(row.parentNode) row.parentNode.removeChild(row);
    }else if(row.tagName==='UL'){
      if((row.textContent||'').replace(/\s|\u00a0/g,'').length>0) continue;
      if(row.parentNode) row.parentNode.removeChild(row);
    }
  }

  // 上报新发现的外链分区 fid 与版块名，供原生导航层拦截自适应、提示更具体
  var ids=Object.keys(found);
  if(ids.length){
    try{
      if(window.DiscuzApp && window.DiscuzApp.reportExternalForums){
        window.DiscuzApp.reportExternalForums(ids.join(','));
        for(var q=0;q<ids.length;q++){
          if(names[ids[q]]) window.DiscuzApp.reportExternalForumName(ids[q], names[ids[q]]);
        }
      }
    }catch(e){}
  }

  // 弹窗广告的固定定位容器：任何页面都要清，删掉不影响布局
  try{
    var ids2=['popadv_popmenu','popadv_popmask'];
    for(var n=0;n<ids2.length;n++){
      var el=document.getElementById(ids2[n]);
      if(el && el.parentNode){ el.parentNode.removeChild(el); }
    }
  }catch(e){}
})();
""".trimIndent()
    }
}