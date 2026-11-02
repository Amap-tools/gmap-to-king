package com.gmap.gmaptoking

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import org.json.JSONObject
import org.json.JSONTokener
import java.util.Locale

private const val TOTAL_RESOLVE_BUDGET_MS = 22_000L
private const val SINGLE_RELOAD_AFTER_MS = 12_000L
private const val ATTEMPT_POLL_MS = 1_500L
private const val MOBILE_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

private const val A6_PACKAGE = "com.kingwaytek.navikinga6"
private const val A6_ACTIVITY = "com.kingwaytek.ui.OutsideSharedActivity"
private const val A5I_3D_PACKAGE = "com.kingwaytek.a5i_3d.plus"
private const val A5I_3D_ACTIVITY = "com.kingwaytek.ui.OutsideSharedActivity"
private const val A5I_PACKAGE = "com.kingwaytek"
private const val A5I_ACTIVITY = "com.kingwaytek.OpeningActivity"
private const val N5_PACKAGE = "com.kingwaytek.naviking"
private const val N5_TRIAL_PACKAGE = "com.kingwaytek.naviking.std"
private const val N5_ACTIVITY = "com.kingwaytek.OpeningActivity"

private val NAVIKING_3D_PACKAGES = listOf(
    "com.kingwaytek.naviking3d",
    "com.kingwaytek.naviking3d.google.std"
)

private const val PREF_NAME = "gmaptoking"
private const val PREF_PACKAGE = "target_package"
private const val PREF_ACTIVITY = "target_activity"
private const val PREF_MODE = "target_mode"
private const val PREF_LABEL = "target_label"

data class NavChoice(
    val label: String,
    val packageName: String,
    val activityName: String,
    val shareText: Boolean
)

fun loadChoices(context: Context): List<NavChoice> {
    val pm = context.packageManager
    val geoChoices = LinkedHashMap<String, NavChoice>()
    val shareChoices = LinkedHashMap<String, NavChoice>()

    val geoIntent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=0,0"))
    for (info in pm.queryIntentActivities(geoIntent, PackageManager.MATCH_DEFAULT_ONLY)) {
        val choice = toChoice(pm, info, shareText = false) ?: continue
        geoChoices["${choice.packageName}/${choice.activityName}"] = choice
    }

    val shareIntent = Intent(Intent.ACTION_SEND).apply { type = "text/plain" }
    for (info in pm.queryIntentActivities(shareIntent, PackageManager.MATCH_DEFAULT_ONLY)) {
        val choice = toChoice(pm, info, shareText = true) ?: continue
        shareChoices["${choice.packageName}/${choice.activityName}"] = choice
    }

    val keys = LinkedHashSet<String>()
    keys.addAll(geoChoices.keys)
    keys.addAll(shareChoices.keys)

    return keys.mapNotNull { key ->
        val geo = geoChoices[key]
        val share = shareChoices[key]
        listOfNotNull(geo, share)
    }.flatten()
        .filter { it.packageName != context.packageName }
        .filter { !isAmapChoice(it) }
        .groupBy { "${it.packageName}/${it.activityName}" }
        .flatMap { (_, group) ->
            if (group.size == 1) {
                group
            } else {
                group.map { choice ->
                    val method = if (choice.shareText) "方式二" else "方式一"
                    choice.copy(label = "${choice.label}（$method）")
                }
            }
        }
        .sortedBy { it.label.lowercase(Locale.ROOT) }
}

private fun isAmapChoice(choice: NavChoice): Boolean {
    val packageName = choice.packageName.lowercase(Locale.ROOT)
    val label = choice.label.lowercase(Locale.ROOT)
    return packageName.contains("autonavi") ||
            packageName.contains("amap") ||
            label.contains("高德") ||
            label.contains("amap")
}

private fun toChoice(
    pm: PackageManager,
    info: ResolveInfo,
    shareText: Boolean
): NavChoice? {
    val activity = info.activityInfo ?: return null
    val label = info.loadLabel(pm)?.toString()?.trim().orEmpty()
        .ifBlank { activity.packageName }
    return NavChoice(label, activity.packageName, activity.name, shareText)
}

fun selectedChoice(context: Context): NavChoice? {
    val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    val packageName = prefs.getString(PREF_PACKAGE, "").orEmpty()
    val activityName = prefs.getString(PREF_ACTIVITY, "").orEmpty()
    if (packageName.isBlank() || activityName.isBlank()) return null
    return NavChoice(
        label = prefs.getString(PREF_LABEL, packageName).orEmpty(),
        packageName = packageName,
        activityName = activityName,
        shareText = prefs.getBoolean(PREF_MODE, false)
    )
}

fun saveChoice(context: Context, choice: NavChoice) {
    context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        .edit()
        .putString(PREF_PACKAGE, choice.packageName)
        .putString(PREF_ACTIVITY, choice.activityName)
        .putString(PREF_LABEL, choice.label)
        .putBoolean(PREF_MODE, choice.shareText)
        .apply()
}

fun clearChoice(context: Context) {
    context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        .edit()
        .remove(PREF_PACKAGE)
        .remove(PREF_ACTIVITY)
        .remove(PREF_LABEL)
        .remove(PREF_MODE)
        .apply()
}

class MainActivity : ComponentActivity() {

    private var sharedText by mutableStateOf("")
    private var detectedUrl by mutableStateOf("")
    private var shareEventId by mutableIntStateOf(0)
    private var showPicker by mutableStateOf(false)
    private var pendingLat by mutableStateOf("")
    private var pendingLon by mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        updateSharedContent(intent)

        setContent {
            MaterialTheme {
                GmapToNaviKingScreen(
                    sharedText = sharedText,
                    detectedUrl = detectedUrl,
                    shareEventId = shareEventId,
                    showPicker = showPicker,
                    pendingLat = pendingLat,
                    pendingLon = pendingLon,
                    onOpenNaviKing = { lat, lon ->
                        openNaviKing(lat, lon)
                    },
                    onPick = { choice, lat, lon ->
                        saveChoice(this, choice)
                        val opened = openChoice(choice, lat, lon)
                        if (opened) {
                            showPicker = false
                            finish()
                        } else {
                            Toast.makeText(this, "打不開 ${choice.label}", Toast.LENGTH_LONG).show()
                        }
                    },
                    onClose = {
                        finish()
                    }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        updateSharedContent(intent)
    }

    private fun updateSharedContent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND) {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                ?.trim()
                .orEmpty()

            sharedText = text
            detectedUrl = extractGoogleMapsUrl(text)
            showPicker = false

            if (detectedUrl.isNotBlank()) {
                shareEventId += 1
            } else if (text.isNotBlank()) {
                Toast.makeText(
                    this,
                    "分享內容中找不到 Google Maps 連結",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun openNaviKing(lat: String, lon: String): Boolean {
        selectedChoice(this)?.let { saved ->
            if (openChoice(saved, lat, lon)) {
                return true
            }
        }

        if (openKnownOrder(lat, lon)) {
            return true
        }

        pendingLat = lat
        pendingLon = lon
        showPicker = true
        Toast.makeText(
            this,
            "找不到已知導航王，請從清單選一個。",
            Toast.LENGTH_LONG
        ).show()
        return false
    }

    private fun openKnownOrder(lat: String, lon: String): Boolean {
        if (openByShareText(A6_PACKAGE, A6_ACTIVITY, lat, lon)) return true
        if (openByShareText(A5I_3D_PACKAGE, A5I_3D_ACTIVITY, lat, lon)) return true
        if (openByGeo(A5I_PACKAGE, A5I_ACTIVITY, lat, lon)) return true
        if (openNaviKing3d(lat, lon)) return true
        if (openByGeo(N5_PACKAGE, N5_ACTIVITY, lat, lon)) return true
        if (openByGeo(N5_TRIAL_PACKAGE, N5_ACTIVITY, lat, lon)) return true
        return false
    }

    private fun openChoice(choice: NavChoice, lat: String, lon: String): Boolean {
        return if (choice.shareText) {
            openByShareText(choice.packageName, choice.activityName, lat, lon)
        } else {
            openByGeo(choice.packageName, choice.activityName, lat, lon)
        }
    }

    private fun openByShareText(
        packageName: String,
        activityName: String,
        lat: String,
        lon: String
    ): Boolean {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "$lat,$lon")
            setClassName(packageName, activityName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

        return try {
            startActivity(shareIntent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }

    private fun openByGeo(
        packageName: String,
        activityName: String,
        lat: String,
        lon: String
    ): Boolean {
        val uri = Uri.parse("geo:0,0?q=$lat,$lon")
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            setClassName(packageName, activityName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }

        return try {
            startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }

    private fun openNaviKing3d(lat: String, lon: String): Boolean {
        for (pkg in NAVIKING_3D_PACKAGES) {
            if (openByGeo(pkg, "com.kingwaytek.OpeningActivity", lat, lon)) {
                return true
            }
        }

        return false
    }
}

fun extractGoogleMapsUrl(text: String): String {
    val urlPattern = Regex("""https?://[^\s]+""")
    val url = urlPattern.find(text)?.value.orEmpty()

    return if (
        url.contains("maps.app.goo.gl") ||
        url.contains("goo.gl/maps") ||
        url.contains("google.com/maps") ||
        url.contains("maps.google.com") ||
        url.contains("google.co") && url.contains("/maps")
    ) {
        url
    } else {
        ""
    }
}

fun extractPlaceCoordinates(url: String): Pair<String, String>? {
    val dataPattern = Regex(
        """!3d(-?\d+(?:\.\d+)?)!4d(-?\d+(?:\.\d+)?)"""
    )

    val dataMatch = dataPattern.find(url)

    if (dataMatch != null) {
        return Pair(
            dataMatch.groupValues[1],
            dataMatch.groupValues[2]
        )
    }

    return extractQueryCoordinates(url)
}

fun extractQueryCoordinates(url: String): Pair<String, String>? {
    return try {
        val uri = Uri.parse(url)
        val coordinatePattern = Regex(
            """^(-?\d+(?:\.\d+)?),\s*(-?\d+(?:\.\d+)?)$"""
        )

        val candidateKeys = listOf("q", "query", "ll", "destination")

        for (key in candidateKeys) {
            val value = uri.getQueryParameter(key)?.trim()
                ?: continue

            val match = coordinatePattern.find(value)
                ?: continue

            return Pair(
                match.groupValues[1],
                match.groupValues[2]
            )
        }

        null
    } catch (error: Exception) {
        null
    }
}

fun extractViewportCoordinates(url: String): Pair<String, String>? {
    val atPattern = Regex(
        """/@(-?\d+(?:\.\d+)?),(-?\d+(?:\.\d+)?)"""
    )

    val atMatch = atPattern.find(url) ?: return null

    return Pair(
        atMatch.groupValues[1],
        atMatch.groupValues[2]
    )
}

fun isJunkTitle(title: String): Boolean {
    val lower = title.lowercase(Locale.ROOT)

    if (
        title.startsWith("http://") ||
        title.startsWith("https://") ||
        title.startsWith("about:") ||
        lower.startsWith("www.") ||
        lower.contains("://") ||
        lower.contains("google.com") ||
        lower.contains("goo.gl") ||
        lower.contains("dynamic link") ||
        lower.contains("not found") ||
        lower.contains("page not found") ||
        lower.contains("error")
    ) {
        return true
    }

    return false
}

fun isFailureTitle(title: String): Boolean {
    val lower = title.lowercase(Locale.ROOT)

    return lower.contains("dynamic link") ||
            lower.contains("not found") ||
            lower.contains("page not found")
}

fun cleanGoogleTitle(rawTitle: String?): String {
    if (rawTitle.isNullOrBlank()) {
        return ""
    }

    var title = rawTitle.trim()

    if (isJunkTitle(title)) {
        return ""
    }

    val suffixes = listOf(
        " - Google 地圖",
        " - Google Maps",
        " – Google 地圖",
        " – Google Maps",
        " - Google 地图"
    )

    for (suffix in suffixes) {
        if (title.endsWith(suffix)) {
            title = title.removeSuffix(suffix).trim()
            break
        }
    }

    if (
        title.isBlank() ||
        title.equals("Google Maps", ignoreCase = true) ||
        title == "Google 地圖" ||
        title == "Google 地图" ||
        isJunkTitle(title)
    ) {
        return ""
    }

    return title
}

fun stripAddressFromName(rawName: String): String {
    var name = rawName.trim()

    if (isJunkTitle(name)) {
        return ""
    }

    name = name.replace(Regex("""^\d{3,6}"""), "").trim()

    val cutIndex = name.indexOfLast { char ->
        char == '號' || char == '号' || char == '樓' || char == '楼' || char == '室'
    }

    if (cutIndex >= 0 && cutIndex + 1 < name.length) {
        val candidate = name.substring(cutIndex + 1).trim()

        if (candidate.isNotBlank()) {
            return candidate
        }
    }

    return name.ifBlank {
        ""
    }
}

fun extractPlaceName(
    sharedText: String,
    finalGoogleUrl: String,
    freshTitle: String,
    storedPageTitle: String
): String {
    val freshName = cleanGoogleTitle(freshTitle)

    if (freshName.isNotBlank()) {
        return freshName
    }

    val storedName = cleanGoogleTitle(storedPageTitle)

    if (storedName.isNotBlank()) {
        return storedName
    }

    val sharedFirstLine = sharedText
        .lineSequence()
        .map { line -> line.trim() }
        .firstOrNull { line ->
            line.isNotBlank() &&
                    !line.startsWith("http://") &&
                    !line.startsWith("https://")
        }
        .orEmpty()

    if (sharedFirstLine.isNotBlank()) {
        val stripped = stripAddressFromName(sharedFirstLine)

        if (stripped.isNotBlank()) {
            return stripped
        }
    }

    return try {
        val uri = Uri.parse(finalGoogleUrl)
        val segments = uri.pathSegments

        val placeIndex = segments.indexOf("place")

        if (
            placeIndex >= 0 &&
            placeIndex + 1 < segments.size
        ) {
            val decoded = Uri.decode(
                segments[placeIndex + 1].replace("+", " ")
            ).trim()

            val coordinatePattern = Regex(
                """^-?\d+(?:\.\d+)?,\s*-?\d+(?:\.\d+)?$"""
            )

            when {
                decoded.isBlank() -> "地圖標記"
                coordinatePattern.matches(decoded) -> decoded
                else -> stripAddressFromName(decoded).ifBlank {
                    "地圖標記"
                }
            }
        } else {
            "地圖標記"
        }
    } catch (error: Exception) {
        "地圖標記"
    }
}

fun showToast(context: Context, message: String) {
    Toast.makeText(
        context,
        message,
        Toast.LENGTH_SHORT
    ).show()
}

fun resetWebView(webView: WebView, clearCookies: Boolean) {
    webView.stopLoading()
    webView.clearHistory()
    webView.clearCache(true)
    webView.clearFormData()

    if (clearCookies) {
        val cookieManager = CookieManager.getInstance()
        cookieManager.removeAllCookies(null)
        cookieManager.flush()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GmapToNaviKingScreen(
    sharedText: String,
    detectedUrl: String,
    shareEventId: Int,
    showPicker: Boolean,
    pendingLat: String,
    pendingLon: String,
    onOpenNaviKing: (String, String) -> Boolean,
    onPick: (NavChoice, String, String) -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current

    var resolveRequestId by remember {
        mutableIntStateOf(0)
    }

    var loadRequestId by remember {
        mutableIntStateOf(0)
    }

    var currentAttempt by remember {
        mutableIntStateOf(0)
    }

    var handledRequestId by remember {
        mutableIntStateOf(0)
    }

    var latitude by remember {
        mutableStateOf("")
    }

    var longitude by remember {
        mutableStateOf("")
    }

    var viewportLatitude by remember {
        mutableStateOf("")
    }

    var viewportLongitude by remember {
        mutableStateOf("")
    }

    var pageTitle by remember {
        mutableStateOf("")
    }

    val latestShareEventId by rememberUpdatedState(shareEventId)
    val latestSharedText by rememberUpdatedState(sharedText)
    val latestDetectedUrl by rememberUpdatedState(detectedUrl)
    val latestLoadRequestId by rememberUpdatedState(loadRequestId)
    val latestAttempt by rememberUpdatedState(currentAttempt)

    fun resetResult() {
        latitude = ""
        longitude = ""
        viewportLatitude = ""
        viewportLongitude = ""
        pageTitle = ""
        handledRequestId = 0
    }

    fun startResolving() {
        resetResult()
        currentAttempt = 0
        showToast(context, "跳轉中…")
        resolveRequestId += 1
    }

    fun finalizeCoordinates(
        webView: WebView?,
        lat: String,
        lon: String,
        sourceUrl: String
    ) {
        val currentPlaceName = extractPlaceName(
            sharedText = latestSharedText,
            finalGoogleUrl = sourceUrl,
            freshTitle = webView?.title.orEmpty(),
            storedPageTitle = pageTitle
        )

        latitude = lat
        longitude = lon

        webView?.stopLoading()

        if (
            latestShareEventId > 0 &&
            handledRequestId != resolveRequestId
        ) {
            handledRequestId = resolveRequestId

            val opened = onOpenNaviKing(lat, lon)
            if (opened) {
                showToast(
                    context,
                    "成功取得座標，正在開啟導航王：$currentPlaceName"
                )
                onClose()
            }
        }
    }

    LaunchedEffect(shareEventId) {
        if (
            shareEventId > 0 &&
            detectedUrl.isNotBlank()
        ) {
            startResolving()
        }
    }

    LaunchedEffect(Unit) {
        if (shareEventId == 0) {
            showToast(
                context,
                "請在 Google Maps 選擇地點後按「分享」給本 App"
            )
        }
    }

    LaunchedEffect(resolveRequestId) {
        val currentRequestId = resolveRequestId

        if (currentRequestId == 0) {
            return@LaunchedEffect
        }

        val resolveStartTimeMs = System.currentTimeMillis()
        currentAttempt = 1
        loadRequestId += 1
        var reloaded = false
        var lastDotToastMs = resolveStartTimeMs

        while (
            System.currentTimeMillis() - resolveStartTimeMs <
            TOTAL_RESOLVE_BUDGET_MS
        ) {
            if (currentRequestId != resolveRequestId) {
                return@LaunchedEffect
            }

            if (latitude.isNotBlank() && longitude.isNotBlank()) {
                return@LaunchedEffect
            }

            val nowMs = System.currentTimeMillis()
            val elapsedMs = nowMs - resolveStartTimeMs

            if (nowMs - lastDotToastMs >= 2_000L) {
                val dotCount = ((elapsedMs / 2_000L) % 3).toInt() + 1
                showToast(context, "跳轉中" + ".".repeat(dotCount))
                lastDotToastMs = nowMs
            }

            if (!reloaded && elapsedMs >= SINGLE_RELOAD_AFTER_MS) {
                reloaded = true
                currentAttempt = 2
                loadRequestId += 1
            }

            delay(ATTEMPT_POLL_MS)
        }

        if (
            currentRequestId == resolveRequestId &&
            viewportLatitude.isNotBlank() &&
            viewportLongitude.isNotBlank()
        ) {
            finalizeCoordinates(
                webView = null,
                lat = viewportLatitude,
                lon = viewportLongitude,
                sourceUrl = latestDetectedUrl
            )
            return@LaunchedEffect
        }

        showToast(
            context,
            "如果幾秒鐘後仍未跳轉，請再試一次"
        )

        onClose()
    }

    fun processGoogleMapsUrl(
        webView: WebView,
        url: String
    ) {
        if (url.isBlank()) {
            return
        }

        if (url.startsWith("about:")) {
            return
        }

        val rawTitle = webView.title

        val cleanedTitle = cleanGoogleTitle(rawTitle)

        if (cleanedTitle.isNotBlank()) {
            pageTitle = cleanedTitle
        }

        val placeCoordinates = extractPlaceCoordinates(url)

        if (placeCoordinates != null) {
            if (
                latitude == placeCoordinates.first &&
                longitude == placeCoordinates.second
            ) {
                return
            }

            finalizeCoordinates(
                webView = webView,
                lat = placeCoordinates.first,
                lon = placeCoordinates.second,
                sourceUrl = url
            )

            return
        }

        val viewportCoordinates = extractViewportCoordinates(url)
        if (viewportCoordinates != null) {
            viewportLatitude = viewportCoordinates.first
            viewportLongitude = viewportCoordinates.second
        }
    }

    if (shareEventId == 0 && !showPicker) {
        var selected by remember { mutableStateOf(selectedChoice(context)?.takeUnless { isAmapChoice(it) }) }
        val choices = remember { loadChoices(context) }
        var expanded by remember { mutableStateOf(false) }
        val currentLabel = selected?.label ?: "自動（依序嘗試）"

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "Gmap 轉導航王",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.fillMaxWidth(),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "1. 在 Google Maps 打開要前往的地點\n" +
                        "2. 按下「分享」按鈕\n" +
                        "3. 選擇「GmapToKing」",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.fillMaxWidth(),
                textAlign = androidx.compose.ui.text.style.TextAlign.Start
            )

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = "傳送到",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.fillMaxWidth(),
                textAlign = androidx.compose.ui.text.style.TextAlign.Start
            )

            Spacer(modifier = Modifier.height(8.dp))

            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = !expanded },
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedTextField(
                    value = currentLabel,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("導航 App") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                    modifier = Modifier
                        .menuAnchor()
                        .fillMaxWidth()
                )
                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("自動（依序嘗試）") },
                        onClick = {
                            clearChoice(context)
                            selected = null
                            expanded = false
                            showToast(context, "已改回自動")
                        }
                    )
                    choices.forEach { choice ->
                        DropdownMenuItem(
                            text = { Text(choice.label) },
                            onClick = {
                                saveChoice(context, choice)
                                selected = choice
                                expanded = false
                                showToast(context, "已設定：${choice.label}")
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "自動會依序嘗試 導航王A6、A5i 3D+、A5i、3D、N5。\n" +
                        "若GmapToKing找不到導航王可以從清單中指定。\n" +
                        "清單中同一個 App 出現方式一、方式二時，可都嘗試看看。",
                style = MaterialTheme.typography.bodyMedium,
                fontSize = 14.sp,
                modifier = Modifier.fillMaxWidth(),
                textAlign = androidx.compose.ui.text.style.TextAlign.Start
            )
        }
    }

    if (showPicker) {
        val choices = remember(showPicker) { loadChoices(context) }
        var expanded by remember { mutableStateOf(true) }
        var pickedLabel by remember { mutableStateOf("請選擇") }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "找不到已知導航王",
                style = MaterialTheme.typography.headlineMedium
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "請選一個可接收座標的 App。選過會記住，下次優先開它。",
                style = MaterialTheme.typography.bodyLarge
            )
            Spacer(modifier = Modifier.height(16.dp))
            if (choices.isEmpty()) {
                Text(
                    text = "系統沒有回報可收座標的 App。",
                    style = MaterialTheme.typography.bodyLarge
                )
            } else {
                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = !expanded },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = pickedLabel,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("導航 App") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                        modifier = Modifier
                            .menuAnchor()
                            .fillMaxWidth()
                    )
                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        choices.forEach { choice ->
                            DropdownMenuItem(
                                text = { Text(choice.label) },
                                onClick = {
                                    pickedLabel = choice.label
                                    expanded = false
                                    onPick(choice, pendingLat.ifBlank { latitude }, pendingLon.ifBlank { longitude })
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    AndroidView(
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.userAgentString = MOBILE_USER_AGENT
                settings.cacheMode = WebSettings.LOAD_DEFAULT
                settings.loadsImagesAutomatically = false
                settings.blockNetworkImage = true

                val cookieManager = CookieManager.getInstance()
                cookieManager.setAcceptCookie(true)
                cookieManager.setAcceptThirdPartyCookies(this, true)

                fun readCurrentUrlFromJavaScript() {
                    evaluateJavascript(
                        "(function() {" +
                                "var html = '';" +
                                "try { html = document.documentElement.innerHTML || ''; } catch (e) {}" +
                                "return JSON.stringify({ href: location.href || '', html: html.slice(0, 120000) });" +
                                "})();"
                    ) { javascriptResult ->
                        try {
                            val payload = JSONTokener(javascriptResult).nextValue()
                            val json = when (payload) {
                                is JSONObject -> payload
                                is String -> JSONObject(payload)
                                else -> return@evaluateJavascript
                            }
                            val actualUrl = json.optString("href")
                            val html = json.optString("html")

                            if (actualUrl.isNotBlank()) {
                                processGoogleMapsUrl(
                                    webView = this,
                                    url = actualUrl
                                )
                            }

                            val fromHtml = extractPlaceCoordinates(html)
                            if (fromHtml != null) {
                                processGoogleMapsUrl(
                                    webView = this,
                                    url = "!3d${fromHtml.first}!4d${fromHtml.second}"
                                )
                            }
                        } catch (error: Exception) {
                            // 等待其他 WebView 導向事件。
                        }
                    }
                }

                webViewClient = object : WebViewClient() {

                    override fun shouldOverrideUrlLoading(
                        view: WebView,
                        request: WebResourceRequest
                    ): Boolean {
                        val url = request.url.toString()

                        if (
                            url.startsWith("https://") ||
                            url.startsWith("http://")
                        ) {
                            processGoogleMapsUrl(
                                webView = view,
                                url = url
                            )

                            return false
                        }

                        return true
                    }

                    override fun onReceivedError(
                        view: WebView,
                        request: WebResourceRequest,
                        error: WebResourceError
                    ) {
                        // 短網址轉址中間常有暫時錯誤，不中斷這次解析。
                    }

                    override fun onReceivedHttpError(
                        view: WebView,
                        request: WebResourceRequest,
                        errorResponse: WebResourceResponse
                    ) {
                        // 轉址頁的暫時 HTTP 錯誤不拿來重開 WebView。
                    }

                    override fun onReceivedSslError(
                        view: WebView,
                        handler: SslErrorHandler,
                        error: SslError
                    ) {
                        handler.cancel()
                    }

                    override fun onPageStarted(
                        view: WebView,
                        url: String,
                        favicon: Bitmap?
                    ) {
                        processGoogleMapsUrl(
                            webView = view,
                            url = url
                        )
                    }

                    override fun doUpdateVisitedHistory(
                        view: WebView,
                        url: String,
                        isReload: Boolean
                    ) {
                        processGoogleMapsUrl(
                            webView = view,
                            url = url
                        )
                    }

                    override fun onPageFinished(
                        view: WebView,
                        url: String
                    ) {
                        processGoogleMapsUrl(
                            webView = view,
                            url = url
                        )

                        view.postDelayed({ readCurrentUrlFromJavaScript() }, 1_200)
                        view.postDelayed({ readCurrentUrlFromJavaScript() }, 3_000)
                        view.postDelayed({ readCurrentUrlFromJavaScript() }, 6_000)
                        view.postDelayed({ readCurrentUrlFromJavaScript() }, 10_000)
                    }
                }
            }
        },
        update = { webView ->
            if (
                latestLoadRequestId > 0 &&
                webView.tag != latestLoadRequestId
            ) {
                webView.tag = latestLoadRequestId
                resetWebView(
                    webView = webView,
                    clearCookies = false
                )
                webView.settings.userAgentString = MOBILE_USER_AGENT
                webView.loadUrl(latestDetectedUrl)
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .alpha(0f)
    )
}
