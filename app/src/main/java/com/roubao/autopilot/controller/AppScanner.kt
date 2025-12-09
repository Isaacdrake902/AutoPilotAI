package com.roubao.autopilot.controller

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * App 扫描器 - 获取所有已安装应用信息
 * 只在启动时扫描一次，缓存到内存和文件
 */
class AppScanner(private val context: Context) {

    companion object {
        private const val CACHE_FILE = "installed_apps.json"
        // 内存缓存 (应用生命周期内有效)
        @Volatile
        private var cachedApps: List<AppInfo>? = null
    }

    data class AppInfo(
        val packageName: String,
        val appName: String,
        val isSystem: Boolean
    )

    /**
     * 获取应用列表 (优先内存 -> 文件 -> 扫描)
     */
    fun getApps(): List<AppInfo> {
        // 1. 内存缓存
        cachedApps?.let { return it }

        // 2. 文件缓存
        val cacheFile = File(context.filesDir, CACHE_FILE)
        if (cacheFile.exists()) {
            val loaded = loadFromFile(cacheFile)
            if (loaded.isNotEmpty()) {
                cachedApps = loaded
                println("[AppScanner] 从文件加载 ${loaded.size} 个应用")
                return loaded
            }
        }

        // 3. 扫描并缓存
        return refreshApps()
    }

    /**
     * 强制刷新应用列表 (扫描 + 保存)
     */
    fun refreshApps(): List<AppInfo> {
        println("[AppScanner] 扫描已安装应用...")
        val apps = scanAllApps()
        cachedApps = apps

        // 保存到文件
        val cacheFile = File(context.filesDir, CACHE_FILE)
        saveToFile(apps, cacheFile)
        println("[AppScanner] 已缓存 ${apps.size} 个应用到 ${cacheFile.absolutePath}")

        return apps
    }

    /**
     * 扫描所有已安装应用
     */
    private fun scanAllApps(): List<AppInfo> {
        val pm = context.packageManager
        val apps = mutableListOf<AppInfo>()

        try {
            val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            for (appInfo in packages) {
                val appName = pm.getApplicationLabel(appInfo).toString()
                val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0

                apps.add(AppInfo(
                    packageName = appInfo.packageName,
                    appName = appName,
                    isSystem = isSystem
                ))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return apps.sortedBy { it.appName }
    }

    /**
     * 转换为 JSON 格式
     */
    private fun toJson(apps: List<AppInfo>): String {
        val jsonArray = JSONArray()
        for (app in apps) {
            val obj = JSONObject()
            obj.put("package", app.packageName)
            obj.put("name", app.appName)
            obj.put("system", app.isSystem)
            jsonArray.put(obj)
        }
        return jsonArray.toString()
    }

    /**
     * 保存到文件
     */
    private fun saveToFile(apps: List<AppInfo>, file: File) {
        try {
            file.writeText(toJson(apps))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 从文件加载
     */
    private fun loadFromFile(file: File): List<AppInfo> {
        val apps = mutableListOf<AppInfo>()
        try {
            val jsonArray = JSONArray(file.readText())
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                apps.add(AppInfo(
                    packageName = obj.getString("package"),
                    appName = obj.getString("name"),
                    isSystem = obj.optBoolean("system", false)
                ))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return apps
    }

    /**
     * 根据名称模糊搜索包名 (客户端智能匹配，省 token)
     */
    fun findPackage(query: String): String? {
        val apps = getApps()
        val lowerQuery = query.lowercase().trim()

        // 1. 精确匹配应用名
        apps.find { it.appName.equals(query, ignoreCase = true) }?.let {
            return it.packageName
        }

        // 2. 精确匹配包名
        apps.find { it.packageName.equals(query, ignoreCase = true) }?.let {
            return it.packageName
        }

        // 3. 应用名包含查询词
        apps.find { it.appName.lowercase().contains(lowerQuery) }?.let {
            return it.packageName
        }

        // 4. 包名包含查询词
        apps.find { it.packageName.lowercase().contains(lowerQuery) }?.let {
            return it.packageName
        }

        // 5. 拼音/英文匹配常见应用
        val commonApps = mapOf(
            "settings" to "设置",
            "shezhi" to "设置",
            "camera" to "相机",
            "xiangji" to "相机",
            "phone" to "电话",
            "dianhua" to "电话",
            "message" to "短信",
            "duanxin" to "短信",
            "browser" to "浏览器",
            "liulanqi" to "浏览器",
            "wechat" to "微信",
            "weixin" to "微信",
            "alipay" to "支付宝",
            "zhifubao" to "支付宝",
            "taobao" to "淘宝",
            "jd" to "京东",
            "jingdong" to "京东",
            "douyin" to "抖音",
            "tiktok" to "抖音",
            "weibo" to "微博",
            "qq" to "QQ",
            "meituan" to "美团",
            "didi" to "滴滴",
            "ele" to "饿了么",
            "eleme" to "饿了么",
            "baidu" to "百度",
            "map" to "地图",
            "ditu" to "地图",
            "music" to "音乐",
            "yinyue" to "音乐",
            "video" to "视频",
            "shipin" to "视频",
            "gallery" to "相册",
            "xiangce" to "相册",
            "photos" to "相册",
            "clock" to "时钟",
            "shizhong" to "时钟",
            "alarm" to "闹钟",
            "naozhong" to "闹钟",
            "calendar" to "日历",
            "rili" to "日历",
            "calculator" to "计算器",
            "jisuanqi" to "计算器",
            "file" to "文件",
            "wenjian" to "文件"
        )

        commonApps[lowerQuery]?.let { mappedName ->
            apps.find { it.appName.contains(mappedName) }?.let {
                return it.packageName
            }
        }

        return null
    }
}
