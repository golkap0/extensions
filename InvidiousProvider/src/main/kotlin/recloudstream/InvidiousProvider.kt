package com.ngefilm

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.getQualityFromName
import org.jsoup.nodes.Element

/**
 * NGEFILM21 — CloudStream 3 Extension
 * Domain: https://new37.ngefilm.site/
 * CMS: WordPress (Theme: Muvipro/GMV)
 *
 * Berdasarkan dokumentasi ngefilm-selectors.md
 */
class NgefilmProvider : MainAPI() {
    override var mainUrl = "https://new37.ngefilm.site"
    override var name = "Ngefilm21"
    override var lang = "id"

    override val hasMainPage = true
    override val hasQuickSearch = false
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val usesWebView = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Live)

    // ═══════════════════════════════════════════════════════════════
    //  HOMEPAGE
    // ═══════════════════════════════════════════════════════════════

    override val mainPage = mainPageOf(
        Pair("1", "Film Terbaru"),
        Pair("2", "Series Terbaru"),
        Pair("3", "Drama Korea"),
        Pair("4", "Trending"),
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page <= 1) mainUrl else "$mainUrl/page/$page/"
        val doc = app.get(url).document

        val items = doc.select("article").mapNotNull { it.toSearchResult(this) }

        return newHomePageResponse(request.name, items)
    }

    // ═══════════════════════════════════════════════════════════════
    //  SEARCH
    // ═══════════════════════════════════════════════════════════════

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get(
            "$mainUrl/",
            params = mapOf(
                "s" to query,
                "post_type[]" to "post",
                "post_type[]" to "tv"
            )
        ).document

        return doc.select("article").mapNotNull { it.toSearchResult(this) }
    }

    // ═══════════════════════════════════════════════════════════════
    //  LOAD — Detail Film / Series
    // ═══════════════════════════════════════════════════════════════

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val page = doc.selectFirst(".gmr-box-content.gmr-single")
            ?: doc.selectFirst(".site-content")
            ?: doc

        // Poster
        val poster = page.selectFirst("img[src*='ngefilm'], img.wp-post-image, img[src]")?.attr("src") ?: ""

        // Judul
        val title = page.selectFirst("h1.entry-title")?.text()
            ?: page.selectFirst("title")?.text()?.substringBefore(" -")?.trim()
            ?: throw ErrorLoadingException("Tidak dapat menemukan judul film")

        // Metadata
        val year = page.selectFirst("a[href*='/year/']")?.text()?.toIntOrNull()
        val qualityStr = page.selectFirst("a[href*='/quality/']")?.text()
        val country = page.selectFirst("a[href*='/country/']")?.text()
        val genres = page.select("a[href*='/Genre/']").mapNotNull { it.text().trim().ifEmpty { null } }
        val tags = page.select("a[href*='/tag/']").mapNotNull { it.text().trim().ifEmpty { null } }
        val cast = page.select("a[href*='/cast/']").mapNotNull { it.text().trim().ifEmpty { null } }
        val ratingElem = page.selectFirst(".gmr-rating-content")
        val rating = ratingElem?.text()?.trim()?.toRatingInt()
        val plot = page.selectFirst(".entry-content.entry-content-single")?.text()
            ?: page.selectFirst(".entry-content")?.text() ?: ""
        val duration = page.selectFirst(".gmr-duration-item")?.text()
            ?: page.selectFirst("span[class*='duration']")?.text()
        val trailer = page.selectFirst("a.gmr-trailer-popup")?.attr("href")

        // Tipe
        val isTvSeries = url.contains("/tv/") || url.contains("-season-")

        // Kumpulkan semua tags
        val allTags = genres + tags.filter { it !in genres }

        if (isTvSeries) {
            return loadTvSeries(
                url = url,
                page = page,
                title = title,
                poster = poster,
                year = year,
                quality = qualityStr,
                tags = allTags,
                cast = cast,
                plot = plot,
                duration = duration,
                trailer = trailer,
                rating = rating
            )
        } else {
            return loadMovie(
                url = url,
                page = page,
                title = title,
                poster = poster,
                year = year,
                quality = qualityStr,
                tags = allTags,
                cast = cast,
                plot = plot,
                duration = duration,
                trailer = trailer,
                rating = rating
            )
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  LOAD MOVIE
    // ═══════════════════════════════════════════════════════════════

    private suspend fun loadMovie(
        url: String,
        page: Element,
        title: String,
        poster: String,
        year: Int?,
        quality: String?,
        tags: List<String>,
        cast: List<String>,
        plot: String,
        duration: String?,
        trailer: String?,
        rating: Int?
    ): LoadResponse {
        // Kumpulkan semua source dari server tabs
        val sources = mutableListOf<String>()

        // Server tabs: Server 1, Server 2, Server 3, Server 4
        val serverTabs = page.select(".muvipro-player-tabs a")
        if (serverTabs.isNotEmpty()) {
            serverTabs.forEach { tab ->
                val playerUrl = tab.attr("href")
                val serverName = tab.text().trim()
                if (playerUrl.isNotBlank() && serverName.isNotBlank()) {
                    val watchUrl = if (playerUrl.startsWith("?")) {
                        "$url$playerUrl"
                    } else {
                        fixUrl(playerUrl)
                    }
                    sources.add(watchUrl)
                }
            }
        }

        // Fallback 1: iframe langsung
        if (sources.isEmpty()) {
            val iframe = page.selectFirst(".gmr-embed-responsive iframe")
            val iframeSrc = iframe?.attr("data-litespeed-src")
                ?: iframe?.attr("src")
            if (!iframeSrc.isNullOrBlank() && iframeSrc != "about:blank") {
                sources.add(fixUrl(iframeSrc))
            }
        }

        // Fallback 2: URL halaman itu sendiri
        if (sources.isEmpty()) {
            sources.add(url)
        }

        return newMovieLoadResponse(title, url, TvType.Movie, sources) {
            this.posterUrl = poster
            this.year = year
            this.plot = plot
            this.tags = tags
            this.quality = parseQuality(quality)
            addDuration(duration)
            addActors(cast)
            this.rating = rating
            addTrailer(trailer)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  LOAD TV SERIES
    // ═══════════════════════════════════════════════════════════════

    private suspend fun loadTvSeries(
        url: String,
        page: Element,
        title: String,
        poster: String,
        year: Int?,
        quality: String?,
        tags: List<String>,
        cast: List<String>,
        plot: String,
        duration: String?,
        trailer: String?,
        rating: Int?
    ): LoadResponse {
        val episodes = mutableListOf<Episode>()

        // Parse season dari URL
        val seasonNum = Regex("""-season-(\d+)""").find(url)?.groupValues?.get(1)?.toIntOrNull() ?: 1

        // Strategi 1: Cari daftar episode dari AJAX endpoint Muvipro
        // Coba ambil data-post-id atau ID dari halaman
        val postId = page.selectFirst("article")?.attr("id")?.removePrefix("post-")
            ?: page.selectFirst("[data-post-id]")?.attr("data-post-id")

        if (postId != null) {
            // Muvipro/GMV biasanya punya AJAX untuk load episode
            try {
                val ajaxUrl = "$mainUrl/wp-admin/admin-ajax.php"
                val epResponse = app.post(
                    ajaxUrl,
                    data = mapOf(
                        "action" to "muvipro_get_episode",
                        "post_id" to postId,
                        "season" to seasonNum.toString()
                    ),
                    referer = url
                ).document

                val episodeItems = epResponse.select("a[href]")
                if (episodeItems.isNotEmpty()) {
                    episodeItems.forEachIndexed { index, el ->
                        val epUrl = fixUrl(el.attr("href"))
                        val epTitle = el.text().trim().ifEmpty { "Episode ${index + 1}" }
                        val epNum = Regex("""(\d+)""").find(epTitle)?.groupValues?.get(1)?.toIntOrNull()
                            ?: (index + 1)

                        episodes.add(
                            newEpisode(epUrl) {
                                this.name = epTitle
                                this.episode = epNum
                                this.season = seasonNum
                            }
                        )
                    }
                }
            } catch (_: Exception) {
                // Silent — fallback ke strategi berikutnya
            }
        }

        // Strategi 2: Cari dari halaman (link bertanda episode)
        if (episodes.isEmpty()) {
            val episodeLinks = page.select("a[href*='/episode/'], a[href*='/tv/']")
            if (episodeLinks.isNotEmpty()) {
                episodeLinks.forEachIndexed { index, el ->
                    val epUrl = el.attr("href")
                    if (epUrl.isNotBlank() && !epUrl.contains("#") && !epUrl.startsWith("javascript")) {
                        val epTitle = el.text().trim()
                        val epNum = Regex("""(\d+)""").find(epTitle)?.groupValues?.get(1)?.toIntOrNull()
                            ?: (index + 1)

                        episodes.add(
                            newEpisode(fixUrl(epUrl)) {
                                this.name = epTitle.ifEmpty { "Episode $epNum" }
                                this.episode = epNum
                                this.season = seasonNum
                            }
                        )
                    }
                }
            }
        }

        // Strategi 3: Fallback — satu entry per season
        if (episodes.isEmpty()) {
            episodes.add(
                newEpisode(url) {
                    this.name = "Season $seasonNum"
                    this.episode = 1
                    this.season = seasonNum
                }
            )
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.year = year
            this.plot = plot
            this.tags = tags
            this.quality = parseQuality(quality)
            addDuration(duration)
            addActors(cast)
            this.rating = rating
            addTrailer(trailer)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  LOAD LINKS — Ambil Video dari Iframe/Player
    // ═══════════════════════════════════════════════════════════════

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false

        // Tentukan target URL
        val targetUrl = if (data.startsWith("?")) {
            fixUrl(data)
        } else {
            data
        }

        val doc = app.get(targetUrl, referer = mainUrl).document

        // ── Strategi 1: Iframe player ──
        found = tryExtractIframe(doc, targetUrl, subtitleCallback, callback) || found

        // ── Strategi 2: Server tabs — load dari ?player=N ──
        if (!found) {
            val serverTabs = doc.select(".muvipro-player-tabs a")
            for (tab in serverTabs) {
                val href = tab.attr("href")
                if (href.startsWith("?player=")) {
                    val tabUrl = "${mainUrl}$href"
                    try {
                        val tabDoc = app.get(tabUrl, referer = targetUrl).document
                        found = tryExtractIframe(tabDoc, tabUrl, subtitleCallback, callback) || found
                    } catch (_: Exception) { }
                    if (found) break
                }
            }
        }

        // ── Strategi 3: AJAX admin-ajax.php ──
        if (!found) {
            found = tryExtractAjax(doc, targetUrl, subtitleCallback, callback) || found
        }

        // ── Strategi 4: Cari di script tags ──
        if (!found) {
            found = tryExtractFromScripts(doc, subtitleCallback, callback) || found
        }

        // ── Strategi 5: Fallback — langsung ekstrak URL ──
        if (!found) {
            found = loadExtractor(targetUrl, mainUrl, subtitleCallback, callback)
        }

        return found
    }

    // ═══════════════════════════════════════════════════════════════
    //  EKSTRAKTOR HELPER
    // ═══════════════════════════════════════════════════════════════

    /**
     * Cari dan ekstrak dari iframe player (.gmr-embed-responsive iframe)
     */
    private suspend fun tryExtractIframe(
        doc: Element,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Cari semua iframe yang relevan
        val iframes = doc.select("iframe")
            .filter { it.attr("src") != "about:blank" || it.hasAttr("data-litespeed-src") }

        for (iframe in iframes) {
            // data-litespeed-src adalah lazy load. Setelah klik tombol #timeloading,
            // src akan diisi dari atribut ini.
            var src = iframe.attr("data-litespeed-src")
                .ifEmpty { iframe.attr("src") }
                .ifEmpty { continue }

            if (src == "about:blank") continue
            if (src.length < 10) continue // filter src pendek/ga valid

            val finalUrl = fixUrl(src)
            if (loadExtractor(finalUrl, referer, subtitleCallback, callback)) {
                return true
            }
        }
        return false
    }

    /**
     * Coba AJAX endpoint WordPress (action: muvipro_get_player)
     */
    private suspend fun tryExtractAjax(
        doc: Element,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Cari data-id atau data-linkid dari elemen player
        val playerData = doc.select("[data-id], [data-linkid]")
        for (el in playerData) {
            val id = el.attr("data-id").ifEmpty { el.attr("data-linkid") }
            if (id.isBlank()) continue

            try {
                val response = app.post(
                    "$mainUrl/wp-admin/admin-ajax.php",
                    data = mapOf(
                        "action" to "muvipro_get_player",
                        "id" to id
                    ),
                    referer = referer
                ).text

                // Cari src iframe di response
                val iframeSrc = Regex("""src=["']([^"']+)["']""").find(response)?.groupValues?.get(1)
                if (!iframeSrc.isNullOrBlank()) {
                    if (loadExtractor(fixUrl(iframeSrc), referer, subtitleCallback, callback)) {
                        return true
                    }
                }

                // Cari URL langsung
                val directUrl = Regex("""(https?://[^"'\s]+\.(?:m3u8|mp4)[^"'\s]*)""").find(response)?.groupValues?.get(1)
                if (!directUrl.isNullOrBlank()) {
                    callback(
                        ExtractorLink(
                            name,
                            name,
                            directUrl,
                            referer,
                            getQualityFromName(""),
                            directUrl.contains(".m3u8")
                        )
                    )
                    return true
                }
            } catch (_: Exception) { }
        }
        return false
    }

    /**
     * Cari URL player di dalam script tags
     */
    private suspend fun tryExtractFromScripts(
        doc: Element,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val scripts = doc.select("script")
        for (script in scripts) {
            val html = script.html()
            if (html.isBlank()) continue

            // Cari pola URL player/embed
            val patterns = listOf(
                Regex("""(?:url|src|player|embed|file)\s*[:=]\s*["']([^"']+)["']"""),
                Regex("""(https?://[^"'\s]+player[^"'\s]*)"""),
                Regex("""(https?://[^"'\s]+\.(?:m3u8|mp4)[^"'\s]*)"""),
            )

            for (pattern in patterns) {
                val match = pattern.find(html)
                if (match != null) {
                    val videoUrl = match.groupValues[1]
                    if (videoUrl.contains("http") &&
                        !videoUrl.contains("google.com") &&
                        !videoUrl.contains("recaptcha") &&
                        !videoUrl.contains("facebook.com")
                    ) {
                        if (loadExtractor(fixUrl(videoUrl), mainUrl, subtitleCallback, callback)) {
                            return true
                        }
                    }
                }
            }
        }
        return false
    }

    // ═══════════════════════════════════════════════════════════════
    //  HELPERS
    // ═══════════════════════════════════════════════════════════════

    private val qualityMap = mapOf(
        "BluRay" to SearchQuality.BLURAY,
        "WEB-DL" to SearchQuality.WEB_DL,
        "WEBRip" to SearchQuality.WEB_DL,
        "HDRip" to SearchQuality.HDRIP,
        "HD" to SearchQuality.HD,
        "HDTS" to SearchQuality.HD,
        "CAM" to SearchQuality.CAM,
        "DVD" to SearchQuality.DVD,
        "DVDSCR" to SearchQuality.DVD,
        "4K" to SearchQuality.FOUR_K,
        "1080p" to SearchQuality.FHD,
        "720p" to SearchQuality.HD,
        "480p" to SearchQuality.SD,
        "360p" to SearchQuality.SD,
    )

    private fun parseQuality(value: String?): SearchQuality? {
        if (value == null) return null
        return qualityMap.entries.firstOrNull { (key, _) ->
            value.contains(key, ignoreCase = true)
        }?.value ?: getQualityFromName(value)
    }

    /**
     * Konversi elemen HTML artikel (di grid) menjadi SearchResponse
     */
    private fun Element.toSearchResult(provider: NgefilmProvider): SearchResponse? {
        val linkEl = this.selectFirst("a[href]") ?: return null
        val href = fixUrl(linkEl.attr("href"))
        val title = this.selectFirst("h2, h3")?.text()
            ?: linkEl.text().trim()
            ?: return null

        val img = this.selectFirst("img")
        val posterUrl = img?.attr("src")
            ?: img?.attr("data-src")
            ?: img?.attr("data-lazy-src")
            ?: ""

        val year = this.selectFirst("a[href*='/year/']")?.text()?.toIntOrNull()
        val qualityStr = this.selectFirst("a[href*='/quality/']")?.text()
        val quality = parseQuality(qualityStr)

        val isTvSeries = href.contains("/tv/") || this.`is`("article[class*='tv']")

        return if (isTvSeries) {
            TvSeriesSearchResponse(
                title,
                href,
                provider.name,
                TvType.TvSeries,
                posterUrl,
                year = year,
                quality = quality
            )
        } else {
            MovieSearchResponse(
                title,
                href,
                provider.name,
                TvType.Movie,
                posterUrl,
                year = year,
                quality = quality
            )
        }
    }
}
