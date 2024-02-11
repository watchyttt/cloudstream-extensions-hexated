package com.hexated

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.APIHolder.unixTimeMS
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.nicehttp.Requests
import com.lagradost.nicehttp.Session
import com.lagradost.cloudstream3.extractors.helper.AesHelper.cryptoAESHandler
import com.lagradost.nicehttp.RequestBodyTypes
import kotlinx.coroutines.delay
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.select.Elements

val session = Session(Requests().baseClient)

object SoraExtractor : SoraStream() {

    suspend fun invokeGoku(
        title: String? = null,
        year: Int? = null,
        season: Int? = null,
        lastSeason: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val headers = mapOf("X-Requested-With" to "XMLHttpRequest")

        fun Document.getServers(): List<Pair<String, String>> {
            return this.select("a").map { it.attr("data-id") to it.text() }
        }

        val media =
            app.get("$gokuAPI/ajax/movie/search?keyword=$title", headers = headers).document.select(
                "div.item"
            ).find { ele ->
                val url = ele.selectFirst("a.movie-link")?.attr("href")
                val titleMedia = ele.select("h3.movie-name").text()
                val titleSlug = title.createSlug()
                val yearMedia = ele.select("div.info-split > div:first-child").text().toIntOrNull()
                val lastSeasonMedia =
                    ele.select("div.info-split > div:nth-child(2)").text().substringAfter("SS")
                        .substringBefore("/").trim().toIntOrNull()
                (titleMedia.equals(title, true) || titleMedia.createSlug()
                    .equals(titleSlug) || url?.contains("$titleSlug-") == true) && (if (season == null) {
                    yearMedia == year && url?.contains("/movie/") == true
                } else {
                    lastSeasonMedia == lastSeason && url?.contains("/series/") == true
                })
            } ?: return

        val serversId = if (season == null) {
            val movieId = app.get(
                fixUrl(
                    media.selectFirst("a")?.attr("href")
                        ?: return, gokuAPI
                )
            ).url.substringAfterLast("/")
            app.get(
                "$gokuAPI/ajax/movie/episode/servers/$movieId",
                headers = headers
            ).document.getServers()
        } else {
            val seasonId = app.get(
                "$gokuAPI/ajax/movie/seasons/${
                    media.selectFirst("a.btn-wl")?.attr("data-id") ?: return
                }", headers = headers
            ).document.select("a.ss-item").find { it.ownText().equals("Season $season", true) }
                ?.attr("data-id")
            val episodeId = app.get(
                "$gokuAPI/ajax/movie/season/episodes/${seasonId ?: return}",
                headers = headers
            ).document.select("div.item").find {
                it.selectFirst("strong")?.text().equals("Eps $episode:", true)
            }?.selectFirst("a")?.attr("data-id")

            app.get(
                "$gokuAPI/ajax/movie/episode/servers/${episodeId ?: return}",
                headers = headers
            ).document.getServers()
        }

        serversId.apmap { (id, name) ->
            val iframe =
                app.get("$gokuAPI/ajax/movie/episode/server/sources/$id", headers = headers)
                    .parsedSafe<GokuServer>()?.data?.link
                    ?: return@apmap
            loadCustomExtractor(
                name,
                iframe,
                "$gokuAPI/",
                subtitleCallback,
                callback,
            )
        }
    }

    suspend fun invokeVidSrc(
        id: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        callback: (ExtractorLink) -> Unit
    ) {
        val url = if (season == null) {
            "$vidSrcAPI/embed/movie?tmdb=$id"
        } else {
            "$vidSrcAPI/embed/tv?tmdb=$id&season=$season&episode=$episode"
        }

        val iframedoc =
            app.get(url).document.select("iframe#player_iframe").attr("src").let { httpsify(it) }
        val doc = app.get(iframedoc, referer = url).document

        val index = doc.select("body").attr("data-i")
        val hash = doc.select("div#hidden").attr("data-h")
        val srcrcp = deobfstr(hash, index)

        val script = app.get(
            httpsify(srcrcp),
            referer = iframedoc
        ).document.selectFirst("script:containsData(Playerjs)")?.data()
        val video = script?.substringAfter("file:\"#9")?.substringBefore("\"")
            ?.replace(Regex("/@#@\\S+?=?="), "")?.let { base64Decode(it) }

        callback.invoke(
            ExtractorLink(
                "Vidsrc", "Vidsrc", video
                    ?: return, "https://vidsrc.stream/", Qualities.P1080.value, INFER_TYPE
            )
        )
    }

    suspend fun invokeDreamfilm(
        title: String? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val fixTitle = title.createSlug()
        val url = if (season == null) {
            "$dreamfilmAPI/$fixTitle"
        } else {
            "$dreamfilmAPI/series/$fixTitle/season-$season/episode-$episode"
        }

        val doc = app.get(url).document
        doc.select("div#videosen a").apmap {
            val iframe = app.get(it.attr("href")).document.selectFirst("div.card-video iframe")
                ?.attr("data-src")
            loadCustomExtractor(
                null,
                iframe
                    ?: return@apmap,
                "$dreamfilmAPI/",
                subtitleCallback,
                callback,
                Qualities.P1080.value
            )
        }
    }

    suspend fun invokeMultimovies(
        apiUrl: String,
        title: String? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val fixTitle = title.createSlug()
        val url = if (season == null) {
            "$apiUrl/movies/$fixTitle"
        } else {
            "$apiUrl/episodes/$fixTitle-${season}x${episode}"
        }
        val req = app.get(url)
        val directUrl = getBaseUrl(req.url)
        val iframe = req.document.selectFirst("div.pframe iframe")?.attr("src") ?: return
        if (!iframe.contains("youtube")) {
            loadExtractor(iframe, "$directUrl/", subtitleCallback) { link ->
                if (link.quality == Qualities.Unknown.value) {
                    callback.invoke(
                        ExtractorLink(
                            link.source,
                            link.name,
                            link.url,
                            link.referer,
                            Qualities.P1080.value,
                            link.type,
                            link.headers,
                            link.extractorData
                        )
                    )
                }
            }
        }
    }

    suspend fun invokeMoviefiction(
        title: String? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val fixTitle = title?.createSlug()
        val url = if (season == null) {
            "$moviefictionAPI/movies/$fixTitle"
        } else {
            "$moviefictionAPI/episode/$fixTitle-${season}x${episode}"
        }
        val req = app.get(url)
        val directUrl = getBaseUrl(req.url)
        req.document.select("ul.bx-lst.aa-tbs li a").apmap {
            val iframe = app.get(base64Decode(it.attr("data-src"))).document.selectFirst("iframe")
                ?.attr("src") ?: return@apmap
            loadExtractor(iframe, "$directUrl/", subtitleCallback) { link ->
                when {
                    link.name == "Bestx" && link.quality == Qualities.Unknown.value -> {
                        callback.invoke(
                            ExtractorLink(
                                "Moviefiction",
                                "Moviefiction",
                                link.url,
                                link.referer,
                                Qualities.P1080.value,
                                link.type,
                                link.headers,
                                link.extractorData
                            )
                        )
                    }
                    link.name != "Bestx" -> {
                        callback.invoke(link)
                    }
                }
            }
        }
    }

    suspend fun invokeAoneroom(
        title: String? = null,
        year: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val headers =
            mapOf("Authorization" to "Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJ1aWQiOjcyODc3MjQ5OTg4MzA0NzM5NzYsInV0cCI6MSwiZXhwIjoxNzEwMzg4NzczLCJpYXQiOjE3MDI2MTI3NzN9.Myt-gVHfPfQFbFyRX3WXtiiwvRzDwBrXTEKy1l-GDRU")
        val subjectId = app.post(
            "$aoneroomAPI/wefeed-mobile-bff/subject-api/search", data = mapOf(
                "page" to "1",
                "perPage" to "10",
                "keyword" to "$title",
                "subjectType" to if (season == null) "1" else "2",
            ), headers = headers
        ).parsedSafe<AoneroomResponse>()?.data?.items?.find {
            it.title.equals(title, true) && it.releaseDate?.substringBefore("-") == "$year"
        }?.subjectId

        val data = app.get(
            "$aoneroomAPI/wefeed-mobile-bff/subject-api/resource?subjectId=${subjectId ?: return}&page=1&perPage=20&all=0&startPosition=1&endPosition=1&pagerMode=0&resolution=480",
            headers = headers
        ).parsedSafe<AoneroomResponse>()?.data?.list?.findLast {
            it.se == (season ?: 0) && it.ep == (episode ?: 0)
        }

        callback.invoke(
            ExtractorLink(
                "Aoneroom", "Aoneroom", data?.resourceLink
                    ?: return, "", data.resolution ?: Qualities.Unknown.value, INFER_TYPE
            )
        )

        data.extCaptions?.map { sub ->
            subtitleCallback.invoke(
                SubtitleFile(
                    sub.lanName ?: return@map,
                    sub.url ?: return@map,
                )
            )
        }

    }

    suspend fun invokeWatchCartoon(
        title: String? = null,
        year: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val fixTitle = title.createSlug()
        val url = if (season == null) {
            "$watchCartoonAPI/movies/$fixTitle-$year"
        } else {
            "$watchCartoonAPI/episode/$fixTitle-season-$season-episode-$episode"
        }

        val req = app.get(url)
        val host = getBaseUrl(req.url)
        val doc = req.document

        val id = doc.select("link[rel=shortlink]").attr("href").substringAfterLast("=")
        doc.select("div.form-group.list-server option").apmap {
            val server = app.get(
                "$host/ajax-get-link-stream/?server=${it.attr("value")}&filmId=$id",
                headers = mapOf("X-Requested-With" to "XMLHttpRequest")
            ).text
            loadExtractor(server, "$host/", subtitleCallback) { link ->
                if (link.quality == Qualities.Unknown.value) {
                    callback.invoke(
                        ExtractorLink(
                            "WatchCartoon",
                            "WatchCartoon",
                            link.url,
                            link.referer,
                            Qualities.P720.value,
                            link.type,
                            link.headers,
                            link.extractorData
                        )
                    )
                }
            }
        }
    }

    suspend fun invokeNetmovies(
        title: String? = null,
        year: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val fixTitle = title.createSlug()
        val url = if (season == null) {
            "$netmoviesAPI/movies/$fixTitle-$year"
        } else {
            "$netmoviesAPI/episodes/$fixTitle-${season}x${episode}"
        }
        invokeWpmovies(null, url, subtitleCallback, callback)
    }

    suspend fun invokeZshow(
        title: String? = null,
        year: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val fixTitle = title.createSlug()
        val url = if (season == null) {
            "$zshowAPI/movie/$fixTitle-$year"
        } else {
            "$zshowAPI/episode/$fixTitle-season-$season-episode-$episode"
        }
        invokeWpmovies("ZShow", url, subtitleCallback, callback, encrypt = true)
    }

    suspend fun invokeMMovies(
        title: String? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val fixTitle = title.createSlug()
        val url = if (season == null) {
            "$mMoviesAPI/movies/$fixTitle"
        } else {
            "$mMoviesAPI/episodes/$fixTitle-${season}x${episode}"
        }

        invokeWpmovies(
            null,
            url,
            subtitleCallback,
            callback,
            true,
            hasCloudflare = true,
        )
    }

    private suspend fun invokeWpmovies(
        name: String? = null,
        url: String? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        fixIframe: Boolean = false,
        encrypt: Boolean = false,
        hasCloudflare: Boolean = false,
        interceptor: Interceptor? = null,
    ) {
        fun String.fixBloat(): String {
            return this.replace("\"", "").replace("\\", "")
        }

        val res = app.get(url ?: return, interceptor = if (hasCloudflare) interceptor else null)
        val referer = getBaseUrl(res.url)
        val document = res.document
        document.select("ul#playeroptionsul > li").map {
            Triple(it.attr("data-post"), it.attr("data-nume"), it.attr("data-type"))
        }.apmap { (id, nume, type) ->
            delay(1000)
            val json = app.post(
                url = "$referer/wp-admin/admin-ajax.php",
                data = mapOf(
                    "action" to "doo_player_ajax",
                    "post" to id,
                    "nume" to nume,
                    "type" to type
                ),
                headers = mapOf("Accept" to "*/*", "X-Requested-With" to "XMLHttpRequest"),
                referer = url,
                interceptor = if (hasCloudflare) interceptor else null
            )
            val source = tryParseJson<ResponseHash>(json.text)?.let {
                when {
                    encrypt -> {
                        val meta = tryParseJson<ZShowEmbed>(it.embed_url)?.meta ?: return@apmap
                        val key = generateWpKey(it.key ?: return@apmap, meta)
                        cryptoAESHandler(it.embed_url, key.toByteArray(), false)?.fixBloat()
                    }

                    fixIframe -> Jsoup.parse(it.embed_url).select("IFRAME").attr("SRC")
                    else -> it.embed_url
                }
            } ?: return@apmap
            when {
                !source.contains("youtube") -> {
                    loadCustomExtractor(name, source, "$referer/", subtitleCallback, callback)
                }
            }
        }
    }

    suspend fun invokeDoomovies(
        title: String? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val res = app.get("$doomoviesAPI/movies/${title.createSlug()}/")
        val host = getBaseUrl(res.url)
        val document = res.document
        document.select("ul#playeroptionsul > li")
            .filter { element -> element.select("span.flag img").attr("src").contains("/en.") }
            .map {
                Triple(
                    it.attr("data-post"),
                    it.attr("data-nume"),
                    it.attr("data-type")
                )
            }.apmap { (id, nume, type) ->
                val source = app.get(
                    "$host/wp-json/dooplayer/v2/${id}/${type}/${nume}",
                    headers = mapOf("X-Requested-With" to "XMLHttpRequest"),
                    referer = "$host/"
                ).parsed<ResponseHash>().embed_url
                if (!source.contains("youtube")) {
                    if (source.startsWith("https://voe.sx")) {
                        val req = app.get(source, referer = "$host/")
                        val server = getBaseUrl(req.url)
                        val script = req.text.substringAfter("wc0 = '").substringBefore("'")
                        val video =
                            tryParseJson<Map<String, String>>(base64Decode(script))?.get("file")
                        M3u8Helper.generateM3u8(
                            "Voe",
                            video ?: return@apmap,
                            "$server/",
                            headers = mapOf("Origin" to server)
                        ).forEach(callback)
                    } else {
                        loadExtractor(source, "$host/", subtitleCallback, callback)
                    }
                }
            }
    }

    suspend fun invokeNoverse(
        title: String? = null,
        season: Int? = null,
        episode: Int? = null,
        callback: (ExtractorLink) -> Unit
    ) {
        val fixTitle = title.createSlug()
        val url = if (season == null) {
            "$noverseAPI/movie/$fixTitle/download/"
        } else {
            "$noverseAPI/serie/$fixTitle/season-$season"
        }

        val doc = app.get(url).document

        val links = if (season == null) {
            doc.select("table.table-striped tbody tr").map {
                it.select("a").attr("href") to it.selectFirst("td")?.text()
            }
        } else {
            doc.select("table.table-striped tbody tr")
                .find { it.text().contains("Episode $episode") }?.select("td")?.map {
                    it.select("a").attr("href") to it.select("a").text()
                }
        } ?: return

        delay(4000)
        links.map { (link, quality) ->
            val name = quality?.replace(Regex("\\d{3,4}p"), "Noverse")?.replace(".", " ")
                ?: "Noverse"
            callback.invoke(
                ExtractorLink(
                    "Noverse",
                    name,
                    link,
                    "",
                    getQualityFromName("${quality?.substringBefore("p")?.trim()}p"),
                )
            )
        }

    }

    suspend fun invokeFilmxy(
        imdbId: String? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val url = if (season == null) {
            "${filmxyAPI}/movie/$imdbId"
        } else {
            "${filmxyAPI}/tv/$imdbId"
        }
        val filmxyCookies = getFilmxyCookies(url)
        val doc = app.get(url, cookies = filmxyCookies).document
        val script = doc.selectFirst("script:containsData(var isSingle)")?.data() ?: return

        val sourcesData =
            Regex("listSE\\s*=\\s?(.*?),[\\n|\\s]").find(script)?.groupValues?.get(1).let {
                tryParseJson<HashMap<String, HashMap<String, List<String>>>>(it)
            }
        val sourcesDetail =
            Regex("linkDetails\\s*=\\s?(.*?),[\\n|\\s]").find(script)?.groupValues?.get(1).let {
                tryParseJson<HashMap<String, HashMap<String, String>>>(it)
            }
        val subSourcesData =
            Regex("dSubtitles\\s*=\\s?(.*?),[\\n|\\s]").find(script)?.groupValues?.get(1).let {
                tryParseJson<HashMap<String, HashMap<String, HashMap<String, String>>>>(it)
            }

        val (seasonSlug, episodeSlug) = getEpisodeSlug(season, episode)

        val sources = if (season == null) {
            sourcesData?.get("movie")?.get("movie")
        } else {
            sourcesData?.get("s$seasonSlug")?.get("e$episodeSlug")
        } ?: return
        val subSources = if (season == null) {
            subSourcesData?.get("movie")?.get("movie")
        } else {
            subSourcesData?.get("s$seasonSlug")?.get("e$episodeSlug")
        }

        val scriptUser = doc.select("script").find { it.data().contains("var userNonce") }?.data()
            ?: return
        val userNonce =
            Regex("var\\suserNonce.*?[\"|'](\\S+?)[\"|'];").find(scriptUser)?.groupValues?.get(1)
        val userId =
            Regex("var\\suser_id.*?[\"|'](\\S+?)[\"|'];").find(scriptUser)?.groupValues?.get(1)

        val listSources = sources.withIndex().groupBy { it.index / 2 }
            .map { entry -> entry.value.map { it.value } }

        listSources.apmap { src ->
            val linkIDs = src.joinToString("") {
                "&linkIDs%5B%5D=$it"
            }.replace("\"", "")
            val json = app.post(
                "$filmxyAPI/wp-admin/admin-ajax.php",
                requestBody = "action=get_vid_links$linkIDs&user_id=$userId&nonce=$userNonce".toRequestBody(),
                referer = url,
                headers = mapOf(
                    "Accept" to "*/*",
                    "DNT" to "1",
                    "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                    "Origin" to filmxyAPI,
                    "X-Requested-With" to "XMLHttpRequest",
                ),
                cookies = filmxyCookies
            ).text.let { tryParseJson<HashMap<String, String>>(it) }

            src.map { source ->
                val link = json?.get(source)
                val quality = sourcesDetail?.get(source)?.get("resolution")
                val server = sourcesDetail?.get(source)?.get("server")
                val size = sourcesDetail?.get(source)?.get("size")

                callback.invoke(
                    ExtractorLink(
                        "Filmxy", "Filmxy $server [$size]", link
                            ?: return@map, "$filmxyAPI/", getQualityFromName(quality)
                    )
                )
            }
        }

        subSources?.mapKeys { sub ->
            subtitleCallback.invoke(
                SubtitleFile(
                    SubtitleHelper.fromTwoLettersToLanguage(sub.key)
                        ?: return@mapKeys, "https://www.mysubs.org/get-subtitle/${sub.value}"
                )
            )
        }

    }

    suspend fun invokeDramaday(
        title: String? = null,
        year: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        fun String.getQuality(): String? =
            Regex("""\d{3,4}[pP]""").find(this)?.groupValues?.getOrNull(0)

        fun String.getTag(): String? =
            Regex("""\d{3,4}[pP]\s*(.*)""").find(this)?.groupValues?.getOrNull(1)

        val slug = title.createSlug()
        val epsSlug = getEpisodeSlug(season, episode)
        val url = if (season == null) {
            "$dramadayAPI/$slug-$year/"
        } else {
            "$dramadayAPI/$slug/"
        }
        val res = app.get(url).document

        val servers = if (season == null) {
            val player = res.select("div.tabs__pane p a[href*=https://ouo]").attr("href")
            val ouo = bypassOuo(player)
            app.get(
                ouo
                    ?: return
            ).document.select("article p:matches(\\d{3,4}[pP]) + p:has(a)").flatMap { ele ->
                val entry = ele.previousElementSibling()?.text() ?: ""
                ele.select("a").map {
                    Triple(entry.getQuality(), entry.getTag(), it.attr("href"))
                }.filter {
                    it.third.startsWith("https://pixeldrain.com") || it.third.startsWith("https://krakenfiles.com")
                }
            }
        } else {
            val data = res.select("tbody tr:has(td[data-order=${epsSlug.second}])")
            val qualities =
                data.select("td:nth-child(2)").attr("data-order").split("<br>").map { it }
            val iframe = data.select("a[href*=https://ouo]").map { it.attr("href") }
            qualities.zip(iframe).map {
                Triple(it.first.getQuality(), it.first.getTag(), it.second)
            }
        }

        servers.filter { it.first == "720p" || it.first == "1080p" }.apmap {
            val server = if (it.third.startsWith("https://ouo")) bypassOuo(it.third) else it.third
            loadCustomTagExtractor(
                it.second,
                server
                    ?: return@apmap,
                "$dramadayAPI/",
                subtitleCallback,
                callback,
                getQualityFromName(it.first)
            )
        }

    }

    suspend fun invokeKimcartoon(
        title: String? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val fixTitle = title.createSlug()
        val doc = if (season == null || season == 1) {
            app.get("$kimcartoonAPI/Cartoon/$fixTitle").document
        } else {
            val res = app.get("$kimcartoonAPI/Cartoon/$fixTitle-Season-$season")
            if (res.url == "$kimcartoonAPI/") app.get("$kimcartoonAPI/Cartoon/$fixTitle-Season-0$season").document else res.document
        }

        val iframe = if (season == null) {
            doc.select("table.listing tr td a").firstNotNullOf { it.attr("href") }
        } else {
            doc.select("table.listing tr td a").find {
                it.attr("href").contains(Regex("(?i)Episode-0*$episode"))
            }?.attr("href")
        } ?: return
        val servers =
            app.get(fixUrl(iframe, kimcartoonAPI)).document.select("#selectServer > option")
                .map { fixUrl(it.attr("value"), kimcartoonAPI) }

        servers.apmap {
            app.get(it).document.select("#my_video_1").attr("src").let { iframe ->
                if (iframe.isNotEmpty()) {
                    loadExtractor(iframe, "$kimcartoonAPI/", subtitleCallback, callback)
                }
            }
        }
    }

    suspend fun invokeDumpStream(
        title: String? = null,
        year: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val (id, type) = getDumpIdAndType(title, year, season)
        val json = fetchDumpEpisodes("$id", "$type", episode) ?: return

        json.subtitlingList?.map { sub ->
            subtitleCallback.invoke(
                SubtitleFile(
                    getVipLanguage(
                        sub.languageAbbr
                            ?: return@map
                    ), sub.subtitlingUrl ?: return@map
                )
            )
        }
    }

    suspend fun invokeVidsrcto(
        imdbId: String?,
        season: Int?,
        episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val url = if (season == null) {
            "$vidsrctoAPI/embed/movie/$imdbId"
        } else {
            "$vidsrctoAPI/embed/tv/$imdbId/$season/$episode"
        }

        val mediaId = app.get(url).document.selectFirst("ul.episodes li a")?.attr("data-id")
            ?: return

        app.get(
            "$vidsrctoAPI/ajax/embed/episode/$mediaId/sources", headers = mapOf(
                "X-Requested-With" to "XMLHttpRequest"
            )
        ).parsedSafe<VidsrctoSources>()?.result?.apmap {
            val encUrl = app.get("$vidsrctoAPI/ajax/embed/source/${it.id}")
                .parsedSafe<VidsrctoResponse>()?.result?.url
            loadExtractor(
                vidsrctoDecrypt(
                    encUrl
                        ?: return@apmap
                ), "$vidsrctoAPI/", subtitleCallback, callback
            )
        }

        val subtitles = app.get("$vidsrctoAPI/ajax/embed/episode/$mediaId/subtitles").text
        tryParseJson<List<VidsrctoSubtitles>>(subtitles)?.map {
            subtitleCallback.invoke(SubtitleFile(it.label ?: "", it.file ?: return@map))
        }

    }

    suspend fun invokeKisskh(
        title: String? = null,
        season: Int? = null,
        episode: Int? = null,
        isAnime: Boolean = false,
        lastSeason: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val slug = title.createSlug() ?: return
        val type = when {
            isAnime -> "3"
            season == null -> "2"
            else -> "1"
        }
        val res = app.get(
            "$kissKhAPI/api/DramaList/Search?q=$title&type=$type",
            referer = "$kissKhAPI/"
        ).text.let {
            tryParseJson<ArrayList<KisskhResults>>(it)
        } ?: return

        val (id, contentTitle) = if (res.size == 1) {
            res.first().id to res.first().title
        } else {
            val data = res.find {
                val slugTitle = it.title.createSlug() ?: return
                when {
                    season == null -> slugTitle == slug
                    lastSeason == 1 -> slugTitle.contains(slug)
                    else -> (slugTitle.contains(slug) && it.title?.contains(
                        "Season $season",
                        true
                    ) == true)
                }
            } ?: res.find { it.title.equals(title) }
            data?.id to data?.title
        }

        val resDetail = app.get(
            "$kissKhAPI/api/DramaList/Drama/$id?isq=false", referer = "$kissKhAPI/Drama/${
                getKisskhTitle(contentTitle)
            }?id=$id"
        ).parsedSafe<KisskhDetail>() ?: return

        val epsId = if (season == null) {
            resDetail.episodes?.first()?.id
        } else {
            resDetail.episodes?.find { it.number == episode }?.id
        }

        app.get(
            "$kissKhAPI/api/DramaList/Episode/$epsId.png?err=false&ts=&time=",
            referer = "$kissKhAPI/Drama/${getKisskhTitle(contentTitle)}/Episode-${episode ?: 0}?id=$id&ep=$epsId&page=0&pageSize=100"
        ).parsedSafe<KisskhSources>()?.let { source ->
            listOf(source.video, source.thirdParty).apmap { link ->
                if (link?.contains(".m3u8") == true) {
                    M3u8Helper.generateM3u8(
                        "Kisskh",
                        link,
                        "$kissKhAPI/",
                        headers = mapOf("Origin" to kissKhAPI)
                    ).forEach(callback)
                } else {
                    loadExtractor(
                        link?.substringBefore("=http")
                            ?: return@apmap null, "$kissKhAPI/", subtitleCallback, callback
                    )
                }
            }
        }

        app.get("$kissKhAPI/api/Sub/$epsId").text.let { resSub ->
            tryParseJson<List<KisskhSubtitle>>(resSub)?.map { sub ->
                subtitleCallback.invoke(
                    SubtitleFile(
                        getLanguage(sub.label ?: return@map), sub.src
                            ?: return@map
                    )
                )
            }
        }


    }

    suspend fun invokeAnimes(
        title: String? = null,
        epsTitle: String? = null,
        date: String?,
        airedDate: String?,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {

        val (aniId, malId) = convertTmdbToAnimeId(
            title,
            date,
            airedDate,
            if (season == null) TvType.AnimeMovie else TvType.Anime
        )

        val malsync = app.get("$malsyncAPI/mal/anime/${malId ?: return}")
            .parsedSafe<MALSyncResponses>()?.sites

        val zoroIds = malsync?.zoro?.keys?.map { it }
        val aniwaveId = malsync?.nineAnime?.firstNotNullOf { it.value["url"] }

        argamap(
            {
                invokeAnimetosho(malId, season, episode, subtitleCallback, callback)
            },
            {
                invokeAniwatch(zoroIds, episode, subtitleCallback, callback)
            },
            {
                invokeAniwave(aniwaveId, episode, subtitleCallback, callback)
            },
            {
                if (season != null) invokeCrunchyroll(
                    aniId,
                    malId,
                    epsTitle,
                    season,
                    episode,
                    subtitleCallback,
                    callback
                )
            }
        )
    }

    private suspend fun invokeAniwave(
        url: String? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val res = app.get(url ?: return).document
        val id = res.select("div#watch-main").attr("data-id")
        val episodeId =
            app.get("$aniwaveAPI/ajax/episode/list/$id?vrf=${AniwaveUtils.encodeVrf(id)}")
                .parsedSafe<AniwaveResponse>()?.asJsoup()
                ?.selectFirst("ul.ep-range li a[data-num=${episode ?: 1}]")?.attr("data-ids")
                ?: return
        val servers =
            app.get("$aniwaveAPI/ajax/server/list/$episodeId?vrf=${AniwaveUtils.encodeVrf(episodeId)}")
                .parsedSafe<AniwaveResponse>()?.asJsoup()
                ?.select("div.servers > div[data-type!=sub] ul li") ?: return

        servers.apmap {
            val linkId = it.attr("data-link-id")
            val iframe =
                app.get("$aniwaveAPI/ajax/server/$linkId?vrf=${AniwaveUtils.encodeVrf(linkId)}")
                    .parsedSafe<AniwaveServer>()?.result?.decrypt()
            val audio = if (it.attr("data-cmid").endsWith("softsub")) "Raw" else "English Dub"
            loadCustomExtractor(
                "${it.text()} [$audio]",
                iframe ?: return@apmap,
                "$aniwaveAPI/",
                subtitleCallback,
                callback,
            )
        }
    }

    private suspend fun invokeAnimetosho(
        malId: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        fun Elements.getLinks(): List<Triple<String, String, Int>> {
            return this.flatMap { ele ->
                ele.select("div.links a:matches(KrakenFiles|GoFile)").map {
                    Triple(
                        it.attr("href"),
                        ele.select("div.size").text(),
                        getIndexQuality(ele.select("div.link a").text())
                    )
                }
            }
        }

        val (seasonSLug, episodeSlug) = getEpisodeSlug(season, episode)
        val jikan = app.get("$jikanAPI/anime/$malId/full").parsedSafe<JikanResponse>()?.data
        val aniId = jikan?.external?.find { it.name == "AniDB" }?.url?.substringAfterLast("=")
        val res =
            app.get("$animetoshoAPI/series/${jikan?.title?.createSlug()}.$aniId?filter[0][t]=nyaa_class&filter[0][v]=trusted").document

        val servers = if (season == null) {
            res.select("div.home_list_entry:has(div.links)").getLinks()
        } else {
            res.select("div.home_list_entry:has(div.link a:matches([\\.\\s]$episodeSlug[\\.\\s]|S${seasonSLug}E$episodeSlug))")
                .getLinks()
        }

        servers.filter { it.third in arrayOf(Qualities.P1080.value, Qualities.P720.value) }.apmap {
            loadCustomTagExtractor(
                it.second,
                it.first,
                "$animetoshoAPI/",
                subtitleCallback,
                callback,
                it.third
            )
        }

    }

    private suspend fun invokeAniwatch(
        animeIds: List<String?>? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val headers = mapOf(
            "X-Requested-With" to "XMLHttpRequest",
        )
        animeIds?.apmap { id ->
            val episodeId = app.get(
                "$aniwatchAPI/ajax/v2/episode/list/${id ?: return@apmap}",
                headers = headers
            ).parsedSafe<AniwatchResponses>()?.html?.let {
                Jsoup.parse(it)
            }?.select("div.ss-list a")?.find { it.attr("data-number") == "${episode ?: 1}" }
                ?.attr("data-id")

            val servers = app.get(
                "$aniwatchAPI/ajax/v2/episode/servers?episodeId=${episodeId ?: return@apmap}",
                headers = headers
            ).parsedSafe<AniwatchResponses>()?.html?.let { Jsoup.parse(it) }
                ?.select("div.item.server-item")?.map {
                    Triple(
                        it.text(),
                        it.attr("data-id"),
                        it.attr("data-type"),
                    )
                }

            servers?.apmap servers@{ server ->
                val iframe = app.get(
                    "$aniwatchAPI/ajax/v2/episode/sources?id=${server.second ?: return@servers}",
                    headers = headers
                ).parsedSafe<AniwatchResponses>()?.link
                    ?: return@servers
                val audio = if (server.third == "sub") "Raw" else "English Dub"
                loadCustomExtractor(
                    "${server.first} [$audio]",
                    iframe,
                    "$aniwatchAPI/",
                    subtitleCallback,
                    callback,
                )
            }
        }
    }

    suspend fun invokeLing(
        title: String? = null,
        year: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val fixTitle = title?.replace("–", "-")
        val url = if (season == null) {
            "$lingAPI/en/videos/films/?title=$fixTitle"
        } else {
            "$lingAPI/en/videos/serials/?title=$fixTitle"
        }

        val scriptData = app.get(url).document.select("div.blk.padding_b0 div.col-sm-30").map {
            Triple(
                it.selectFirst("div.video-body h5")?.text(),
                it.selectFirst("div.video-body > p")?.text(),
                it.selectFirst("div.video-body a")?.attr("href"),
            )
        }

        val script = if (scriptData.size == 1) {
            scriptData.first()
        } else {
            scriptData.find {
                it.first?.contains(
                    "$fixTitle",
                    true
                ) == true && it.second?.contains("$year") == true
            }
        }

        val doc = app.get(fixUrl(script?.third ?: return, lingAPI)).document
        val iframe = (if (season == null) {
            doc.selectFirst("a.video-js.vjs-default-skin")?.attr("data-href")
        } else {
            doc.select("div.blk div#tab_$season li")[episode!!.minus(1)].select("h5 a")
                .attr("data-href")
        })?.let { fixUrl(it, lingAPI) }

        val source = app.get(iframe ?: return)
        val link = Regex("((https:|http:)//.*\\.mp4)").find(source.text)?.value ?: return
        callback.invoke(
            ExtractorLink(
                "Ling",
                "Ling",
                "$link/index.m3u8",
                "$lingAPI/",
                Qualities.P720.value,
                INFER_TYPE
            )
        )

        source.document.select("div#player-tracks track").map {
            subtitleCallback.invoke(
                SubtitleFile(
                    SubtitleHelper.fromTwoLettersToLanguage(it.attr("srclang"))
                        ?: return@map null, it.attr("src")
                )
            )
        }

    }

    suspend fun invokeUhdmovies(
        title: String? = null,
        year: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        callback: (ExtractorLink) -> Unit
    ) {
        val fixTitle = title.createSlug()
        val (seasonSlug, episodeSlug) = getEpisodeSlug(season, episode)

        val url = if (season == null) {
            "$uhdmoviesAPI/download-$fixTitle-$year"
        } else {
            "$uhdmoviesAPI/download-$fixTitle"
        }

        val detailDoc = app.get(url).document

        val iSelector = if (season == null) {
            "div.entry-content p:has(:matches($year))"
        } else {
            "div.entry-content p:has(:matches((?i)(?:S\\s*$seasonSlug|Season\\s*$seasonSlug)))"
        }
        val iframeList = detailDoc.select(iSelector).mapNotNull {
            if (season == null) {
                it.text() to it.nextElementSibling()?.select("a")?.attr("href")
            } else {
                it.text() to it.nextElementSibling()?.select("a")?.find { child ->
                    child.select("span").text().equals("Episode $episode", true)
                }?.attr("href")
            }
        }.filter { it.first.contains(Regex("(2160p)|(1080p)")) }.reversed().takeLast(3)

        iframeList.apmap { (quality, link) ->
            val driveLink = bypassHrefli(link ?: return@apmap)
            val base = getBaseUrl(driveLink ?: return@apmap)
            val driveReq = app.get(driveLink)
            val driveRes = driveReq.document
            val bitLink = driveRes.select("a.btn.btn-outline-success").attr("href")
            val insLink =
                driveRes.select("a.btn.btn-danger:contains(Instant Download)").attr("href")
            val downloadLink = when {
                insLink.isNotEmpty() -> extractInstantUHD(insLink)
                driveRes.select("button.btn.btn-success").text()
                    .contains("Direct Download", true) -> extractDirectUHD(driveLink, driveReq)

                bitLink.isNullOrEmpty() -> {
                    val backupIframe = driveRes.select("a.btn.btn-outline-warning").attr("href")
                    extractBackupUHD(backupIframe ?: return@apmap)
                }

                else -> {
                    extractMirrorUHD(bitLink, base)
                }
            }

            val tags = getUhdTags(quality)
            val qualities = getIndexQuality(quality)
            val size = getIndexSize(quality)
            callback.invoke(
                ExtractorLink(
                    "UHDMovies", "UHDMovies $tags [$size]", downloadLink
                        ?: return@apmap, "", qualities
                )
            )
        }
    }

    suspend fun invokeDotmovies(
        title: String? = null,
        year: Int? = null,
        season: Int? = null,
        lastSeason: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        invokeWpredis(
            title,
            year,
            season,
            lastSeason,
            episode,
            subtitleCallback,
            callback,
            dotmoviesAPI
        )
    }

    suspend fun invokeVegamovies(
        title: String? = null,
        year: Int? = null,
        season: Int? = null,
        lastSeason: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        invokeWpredis(
            title,
            year,
            season,
            lastSeason,
            episode,
            subtitleCallback,
            callback,
            vegaMoviesAPI
        )
    }

    private suspend fun invokeWpredis(
        title: String? = null,
        year: Int? = null,
        season: Int? = null,
        lastSeason: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        api: String
    ) {
        val (seasonSlug, episodeSlug) = getEpisodeSlug(season, episode)
        var res = app.get("$api/search/$title").document
        val match = when (season) {
            null -> "$year"
            1 -> "Season 1"
            else -> "Season 1 – $lastSeason"
        }
        val media =
            res.selectFirst("div.blog-items article:has(h3.entry-title:matches((?i)$title.*$match)) a")
                ?.attr("href")

        res = app.get(media ?: return).document
        val hTag = if (season == null) "h5" else "h3"
        val aTag = if (season == null) "Download Now" else "V-Cloud"
        val sTag = if (season == null) "" else "(Season $season|S$seasonSlug)"
        val entries = res.select("div.entry-content > $hTag:matches((?i)$sTag.*(1080p|2160p))")
            .filter { element -> !element.text().contains("Download", true) }.takeLast(2)
        entries.apmap {
            val tags =
                """(?:1080p|2160p)(.*)""".toRegex().find(it.text())?.groupValues?.get(1)?.trim()
            val href = it.nextElementSibling()?.select("a:contains($aTag)")?.attr("href")
            val selector =
                if (season == null) "p a:contains(V-Cloud)" else "h4:matches(0?$episode) + p a:contains(V-Cloud)"
            val server = app.get(
                href ?: return@apmap, interceptor = wpRedisInterceptor
            ).document.selectFirst("div.entry-content > $selector")
                ?.attr("href") ?: return@apmap

            loadCustomTagExtractor(
                tags,
                server,
                "$api/",
                subtitleCallback,
                callback,
                getIndexQuality(it.text())
            )
        }
    }

    suspend fun invokeHdmovies4u(
        title: String? = null,
        imdbId: String? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        fun String.decodeLink(): String {
            return base64Decode(this.substringAfterLast("/"))
        }
        val (seasonSlug, episodeSlug) = getEpisodeSlug(season, episode)
        val media =
            app.get("$hdmovies4uAPI/?s=${if (season == null) imdbId else title}").document.let {
                val selector = if (season == null) "a" else "a:matches((?i)$title.*Season $season)"
                it.selectFirst("div.gridxw.gridxe $selector")?.attr("href")
            }
        val selector = if (season == null) "1080p|2160p" else "(?i)Episode.*(?:1080p|2160p)"
        app.get(media ?: return).document.select("section h4:matches($selector)").apmap { ele ->
            val (tags, size) = ele.select("span").map {
                it.text()
            }.let { it[it.lastIndex - 1] to it.last().substringAfter("-").trim() }
            val link = ele.nextElementSibling()?.select("a:contains(DriveTOT)")?.attr("href")
            val iframe = bypassBqrecipes(link?.decodeLink() ?: return@apmap).let {
                if (it?.contains("/pack/") == true) {
                    val href =
                        app.get(it).document.select("table tbody tr:contains(S${seasonSlug}E${episodeSlug}) a")
                            .attr("href")
                    bypassBqrecipes(href.decodeLink())
                } else {
                    it
                }
            }
            invokeDrivetot(iframe ?: return@apmap, tags, size, subtitleCallback, callback)
        }
    }

    suspend fun invokeGMovies(
        title: String? = null,
        year: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val fixTitle = title.createSlug()
        val url = if (season == null || season == 1) {
            "$gMoviesAPI/$fixTitle-$year"
        } else {
            "$gMoviesAPI/$fixTitle-$year-season-$season"
        }

        val doc = app.get(url).document

        val iframe = (if (season == null) {
            doc.select("div.is-content-justification-center div.wp-block-button").map {
                it.select("a").attr("href") to it.text()
            }
        } else {
            doc.select("div.is-content-justification-center").find {
                it.previousElementSibling()?.text()
                    ?.contains(Regex("(?i)episode\\s?$episode")) == true
            }?.select("div.wp-block-button")?.map {
                it.select("a").attr("href") to it.text()
            }
        })?.filter {
            it.second.contains(Regex("(?i)(4k|1080p)"))
        } ?: return

        iframe.apmap { (iframeLink, title) ->
            val size = Regex("(?i)\\s(\\S+gb|mb)").find(title)?.groupValues?.getOrNull(1)
            loadCustomTagExtractor(
                "[$size]",
                iframeLink,
                "$gMoviesAPI/",
                subtitleCallback,
                callback,
                getIndexQuality(title)
            )
        }
    }

    suspend fun invokeFDMovies(
        title: String? = null,
        season: Int? = null,
        episode: Int? = null,
        callback: (ExtractorLink) -> Unit
    ) {
        val fixTitle = title.createSlug()
        val url = if (season == null) {
            "$fdMoviesAPI/movies/$fixTitle"
        } else {
            "$fdMoviesAPI/episodes/$fixTitle-s${season}xe${episode}/"
        }

        val request = app.get(url)
        if (!request.isSuccessful) return

        val iframe = request.document.select("div#download tbody tr").map {
            FDMovieIFrame(
                it.select("a").attr("href"),
                it.select("strong.quality").text(),
                it.select("td:nth-child(4)").text(),
                it.select("img").attr("src")
            )
        }.filter {
            it.quality.contains(Regex("(?i)(1080p|4k)")) && it.type.contains(Regex("(gdtot|oiya|rarbgx)"))
        }
        iframe.apmap { (link, quality, size, type) ->
            val qualities = getFDoviesQuality(quality)
            val fdLink = bypassFdAds(link)
            val videoLink = when {
                type.contains("gdtot") -> {
                    val gdBotLink = extractGdbot(fdLink ?: return@apmap null)
                    extractGdflix(gdBotLink ?: return@apmap null)
                }

                type.contains("oiya") || type.contains("rarbgx") -> {
                    val oiyaLink = extractOiya(fdLink ?: return@apmap null)
                    if (oiyaLink?.contains("gdtot") == true) {
                        val gdBotLink = extractGdbot(oiyaLink)
                        extractGdflix(gdBotLink ?: return@apmap null)
                    } else {
                        oiyaLink
                    }
                }

                else -> {
                    return@apmap null
                }
            }

            callback.invoke(
                ExtractorLink(
                    "FDMovies", "FDMovies [$size]", videoLink
                        ?: return@apmap null, "", getQualityFromName(qualities)
                )
            )
        }

    }

    suspend fun invokeM4uhd(
        title: String? = null,
        year: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val slugTitle = title?.createSlug()
        val (seasonSlug, episodeSlug) = getEpisodeSlug(season, episode)
        val req = app.get("$m4uhdAPI/search/$slugTitle.html", timeout = 20)
        val referer = getBaseUrl(req.url)

        val media = req.document.select("div.row div.item > a").map { it.attr("href") }
        val mediaUrl = if(media.size == 1) {
            media.first()
        } else {
            media.find { it.contains("-$slugTitle-") && it.contains("-$year-") }
        }

        val link = fixUrl(mediaUrl ?: return, referer)
        val request = app.get(link, timeout = 20)
        var cookies = request.cookies
        val headers = mapOf("Accept" to "*/*", "X-Requested-With" to "XMLHttpRequest")

        val doc = request.document
        val token = doc.selectFirst("meta[name=csrf-token]")?.attr("content")
        val m4uData = if (season == null) {
            doc.select("div.le-server span").map { it.attr("data") }
        } else {
            val idepisode =
                doc.selectFirst("div.season > p:matches((?i)S$seasonSlug-E$episodeSlug) button")
                    ?.attr("idepisode")
                    ?: return
            val requestEmbed = app.post(
                "$referer/ajaxtv", data = mapOf(
                    "idepisode" to idepisode, "_token" to "$token"
                ), referer = link, headers = headers, cookies = cookies, timeout = 20
            )
            cookies = requestEmbed.cookies
            requestEmbed.document.select("div.le-server span").map { it.attr("data") }
        }

        m4uData.apmap { data ->
            val iframe = app.post(
                "$referer/ajax",
                data = mapOf("m4u" to data, "_token" to "$token"),
                referer = link,
                headers = headers,
                cookies = cookies,
                timeout = 20
            ).document.select("iframe").attr("src")

            loadExtractor(iframe, referer, subtitleCallback, callback)
        }

    }

    suspend fun invokeTvMovies(
        title: String? = null,
        season: Int? = null,
        episode: Int? = null,
        callback: (ExtractorLink) -> Unit
    ) {
        val fixTitle = title.createSlug()
        val url = if (season == null) {
            "$tvMoviesAPI/show/$fixTitle"
        } else {
            "$tvMoviesAPI/show/index-of-$fixTitle"
        }

        val server = getTvMoviesServer(url, season, episode) ?: return
        val videoData = extractCovyn(server.second ?: return)
        val quality =
            Regex("(\\d{3,4})p").find(server.first)?.groupValues?.getOrNull(1)?.toIntOrNull()

        callback.invoke(
            ExtractorLink(
                "TVMovies", "TVMovies [${videoData?.second}]", videoData?.first
                    ?: return, "", quality ?: Qualities.Unknown.value
            )
        )


    }

    private suspend fun invokeCrunchyroll(
        aniId: Int? = null,
        malId: Int? = null,
        epsTitle: String? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val id = getCrunchyrollId("${aniId ?: return}")
            ?: getCrunchyrollIdFromMalSync("${malId ?: return}")
        val audioLocal = listOf(
            "ja-JP",
            "en-US",
            "zh-CN",
        )
        val token = getCrunchyrollToken()
        val headers = mapOf("Authorization" to "${token.tokenType} ${token.accessToken}")
        val seasonIdData = app.get(
            "$crunchyrollAPI/content/v2/cms/series/${id ?: return}/seasons",
            headers = headers
        ).parsedSafe<CrunchyrollResponses>()?.data?.let { s ->
            if (s.size == 1) {
                s.firstOrNull()
            } else {
                s.find {
                    when (epsTitle) {
                        "One Piece" -> it.season_number == 13
                        "Hunter x Hunter" -> it.season_number == 5
                        else -> it.season_number == season
                    }
                } ?: s.find { it.season_number?.plus(1) == season }
            }
        }
        val seasonId = seasonIdData?.versions?.filter { it.audio_locale in audioLocal }
            ?.map { it.guid to it.audio_locale }
            ?: listOf(seasonIdData?.id to "ja-JP")

        seasonId.apmap { (sId, audioL) ->
            val streamsLink = app.get(
                "$crunchyrollAPI/content/v2/cms/seasons/${sId ?: return@apmap}/episodes",
                headers = headers
            ).parsedSafe<CrunchyrollResponses>()?.data?.find {
                it.title.equals(epsTitle, true) || it.slug_title.equals(
                    epsTitle.createSlug(),
                    true
                ) || it.episode_number == episode
            }?.streams_link?.substringAfter("/videos/")?.substringBefore("/streams") ?: return@apmap
            val sources = app.get(
                "$crunchyrollAPI/cms/v2${token.bucket}/videos/$streamsLink/streams?Policy=${token.policy}&Signature=${token.signature}&Key-Pair-Id=${token.key_pair_id}",
                headers = headers
            ).parsedSafe<CrunchyrollSourcesResponses>()

            listOf("adaptive_hls", "vo_adaptive_hls").map { hls ->
                val name = if (hls == "adaptive_hls") "Crunchyroll" else "Vrv"
                val audio = if (audioL == "en-US") "English Dub" else "Raw"
                val source = sources?.streams?.let {
                    if (hls == "adaptive_hls") it.adaptive_hls else it.vo_adaptive_hls
                }
                M3u8Helper.generateM3u8(
                    "$name [$audio]", source?.get("")?.get("url")
                        ?: return@map, "https://static.crunchyroll.com/"
                ).forEach(callback)
            }

            sources?.subtitles?.map { sub ->
                subtitleCallback.invoke(
                    SubtitleFile(
                        "${fixCrunchyrollLang(sub.key) ?: sub.key} [ass]", sub.value["url"]
                            ?: return@map null
                    )
                )
            }
        }
    }

    suspend fun invokeRStream(
        id: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        callback: (ExtractorLink) -> Unit
    ) {
        val url = if (season == null) {
            "$rStreamAPI/e/?tmdb=$id"
        } else {
            "$rStreamAPI/e/?tmdb=$id&s=$season&e=$episode"
        }

        val res = app.get(
            "$url&apikey=whXgvN4kVyoubGwqXpw26Oy3PVryl8dm",
            referer = "https://watcha.movie/"
        ).text
        val link = Regex("\"file\":\"(http.*?)\"").find(res)?.groupValues?.getOrNull(1)

        callback.invoke(
            ExtractorLink(
                "RStream",
                "RStream",
                link ?: return,
                "$rStreamAPI/",
                Qualities.P1080.value,
                INFER_TYPE
            )
        )
    }

    suspend fun invokeFlixon(
        tmdbId: Int? = null,
        imdbId: String? = null,
        season: Int? = null,
        episode: Int? = null,
        callback: (ExtractorLink) -> Unit,
        onionUrl: String = "https://onionplay.se/"
    ) {
        val request = if (season == null) {
            val res = app.get("$flixonAPI/$imdbId", referer = onionUrl)
            if (res.text.contains("BEGIN PGP SIGNED MESSAGE")) app.get(
                "$flixonAPI/$imdbId-1",
                referer = onionUrl
            ) else res
        } else {
            app.get("$flixonAPI/$tmdbId-$season-$episode", referer = onionUrl)
        }

        val script = request.document.selectFirst("script:containsData(= \"\";)")?.data()
        val collection = script?.substringAfter("= [")?.substringBefore("];")
        val num = script?.substringAfterLast("(value) -")?.substringBefore(");")?.trim()?.toInt()
            ?: return

        val iframe = collection?.split(",")?.map { it.trim().toInt() }?.map { nums ->
            nums.minus(num).toChar()
        }?.joinToString("")?.let { Jsoup.parse(it) }?.selectFirst("button.redirect")
            ?.attr("onclick")?.substringAfter("('")?.substringBefore("')")

        delay(1000)
        val unPacker = app.get(
            iframe
                ?: return, referer = "$flixonAPI/"
        ).document.selectFirst("script:containsData(JuicyCodes.Run)")?.data()
            ?.substringAfter("JuicyCodes.Run(")?.substringBefore(");")?.split("+")
            ?.joinToString("") { it.replace("\"", "").trim() }
            ?.let { getAndUnpack(base64Decode(it)) }

        val link = Regex("[\"']file[\"']:[\"'](.+?)[\"'],").find(
            unPacker
                ?: return
        )?.groupValues?.getOrNull(1)

        callback.invoke(
            ExtractorLink(
                "Flixon",
                "Flixon",
                link
                    ?: return,
                "https://onionplay.stream/",
                Qualities.P720.value,
                link.contains(".m3u8")
            )
        )

    }

    suspend fun invokeSmashyStream(
        tmdbId: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val url = if (season == null) {
            "$smashyStreamAPI/playere.php?tmdb=$tmdbId"
        } else {
            "$smashyStreamAPI/playere.php?tmdb=$tmdbId&season=$season&episode=$episode"
        }

        app.get(
            url,
            referer = "https://smashystream.xyz/"
        ).document.select("div#_default-servers a.server").map {
            it.attr("data-url") to it.text()
        }.apmap {
            when (it.second) {
                "Player F" -> {
                    invokeSmashyFfix(it.second, it.first, url, subtitleCallback, callback)
                }

                "Player SU" -> {
                    invokeSmashySu(it.second, it.first, url, callback)
                }

                else -> return@apmap
            }
        }

    }

    suspend fun invokeMoflix(
        tmdbId: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        callback: (ExtractorLink) -> Unit
    ) {
        val id = (if (season == null) {
            "tmdb|movie|$tmdbId"
        } else {
            "tmdb|series|$tmdbId"
        }).let { base64Encode(it.toByteArray()) }

        val loaderUrl = "$moflixAPI/api/v1/titles/$id?loader=titlePage"
        val url = if (season == null) {
            loaderUrl
        } else {
            val mediaId = app.get(loaderUrl, referer = "$moflixAPI/").parsedSafe<MoflixResponse>()?.title?.id
            "$moflixAPI/api/v1/titles/$mediaId/seasons/$season/episodes/$episode?loader=episodePage"
        }

        val res = app.get(url, referer = "$moflixAPI/").parsedSafe<MoflixResponse>()
        (res?.episode ?: res?.title)?.videos?.filter { it.category.equals("full", true) }
            ?.apmap { iframe ->
                val response = app.get(iframe.src ?: return@apmap, referer = "$moflixAPI/")
                val host = getBaseUrl(iframe.src)
                val doc = response.document.selectFirst("script:containsData(sources:)")?.data()
                val script = if (doc.isNullOrEmpty()) {
                    getAndUnpack(response.text)
                } else {
                    doc
                }
                val m3u8 = Regex("file:\\s*\"(.*?m3u8.*?)\"").find(
                    script ?: return@apmap
                )?.groupValues?.getOrNull(1)
                if (m3u8?.haveDub("$host/") == false) return@apmap
                callback.invoke(
                    ExtractorLink(
                        "Moflix",
                        "Moflix [${iframe.name}]",
                        m3u8 ?: return@apmap,
                        "$host/",
                        iframe.quality?.filter { it.isDigit() }?.toIntOrNull()
                            ?: Qualities.Unknown.value,
                        INFER_TYPE
                    )
                )
            }

    }

    //TODO only subs
    suspend fun invokeWatchsomuch(
        imdbId: String? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
    ) {
        val id = imdbId?.removePrefix("tt")
        val epsId = app.post(
            "$watchSomuchAPI/Watch/ajMovieTorrents.aspx",
            data = mapOf(
                "index" to "0",
                "mid" to "$id",
                "wsk" to "30fb68aa-1c71-4b8c-b5d4-4ca9222cfb45",
                "lid" to "",
                "liu" to ""
            ),
            headers = mapOf("X-Requested-With" to "XMLHttpRequest")
        ).parsedSafe<WatchsomuchResponses>()?.movie?.torrents?.let { eps ->
            if (season == null) {
                eps.firstOrNull()?.id
            } else {
                eps.find { it.episode == episode && it.season == season }?.id
            }
        } ?: return

        val (seasonSlug, episodeSlug) = getEpisodeSlug(season, episode)

        val subUrl = if (season == null) {
            "$watchSomuchAPI/Watch/ajMovieSubtitles.aspx?mid=$id&tid=$epsId&part="
        } else {
            "$watchSomuchAPI/Watch/ajMovieSubtitles.aspx?mid=$id&tid=$epsId&part=S${seasonSlug}E${episodeSlug}"
        }

        app.get(subUrl).parsedSafe<WatchsomuchSubResponses>()?.subtitles?.map { sub ->
            subtitleCallback.invoke(
                SubtitleFile(
                    sub.label ?: "", fixUrl(
                        sub.url
                            ?: return@map null, watchSomuchAPI
                    )
                )
            )
        }


    }

    suspend fun invokeShinobiMovies(
        apiUrl: String,
        api: String,
        title: String? = null,
        year: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        callback: (ExtractorLink) -> Unit,
    ) {
        invokeIndex(
            apiUrl,
            api,
            title,
            year,
            season,
            episode,
            callback,
        )
    }

    private suspend fun invokeIndex(
        apiUrl: String,
        api: String,
        title: String? = null,
        year: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        callback: (ExtractorLink) -> Unit,
        password: String = "",
    ) {
        val passHeaders = mapOf("Authorization" to password)
        val query = getIndexQuery(title, year, season, episode).let {
            if (api in mkvIndex) "$it mkv" else it
        }
        val body =
            """{"q":"$query","password":null,"page_token":null,"page_index":0}""".toRequestBody(
                RequestBodyTypes.JSON.toMediaTypeOrNull()
            )
        val data = mapOf("q" to query, "page_token" to "", "page_index" to "0")
        val search = if (api in encodedIndex) {
            decodeIndexJson(
                if (api in lockedIndex) app.post(
                    "${apiUrl}search",
                    data = data,
                    headers = passHeaders,
                    referer = apiUrl,
                    timeout = 120L
                ).text else app.post("${apiUrl}search", data = data, referer = apiUrl).text
            )
        } else {
            app.post("${apiUrl}search", requestBody = body, referer = apiUrl, timeout = 120L).text
        }
        val media = if (api in untrimmedIndex) searchIndex(
            title,
            season,
            episode,
            year,
            search,
            false
        ) else searchIndex(title, season, episode, year, search)
        media?.apmap { file ->
            val pathBody =
                """{"id":"${file.id ?: return@apmap null}"}""".toRequestBody(RequestBodyTypes.JSON.toMediaTypeOrNull())
            val pathData = mapOf(
                "id" to file.id,
            )
            val path = (if (api in encodedIndex) {
                if (api in lockedIndex) {
                    app.post(
                        "${apiUrl}id2path",
                        data = pathData,
                        headers = passHeaders,
                        referer = apiUrl,
                        timeout = 120L
                    )
                } else {
                    app.post("${apiUrl}id2path", data = pathData, referer = apiUrl, timeout = 120L)
                }
            } else {
                app.post(
                    "${apiUrl}id2path",
                    requestBody = pathBody,
                    referer = apiUrl,
                    timeout = 120L
                )
            }).text.let { path ->
                if (api in ddomainIndex) {
                    val worker = app.get(
                        "${fixUrl(path, apiUrl).encodeUrl()}?a=view",
                        referer = if (api in needRefererIndex) apiUrl else "",
                        timeout = 120L
                    ).document.selectFirst("script:containsData(downloaddomain)")?.data()
                        ?.substringAfter("\"downloaddomain\":\"")?.substringBefore("\",")?.let {
                            "$it/0:"
                        }
                    fixUrl(path, worker ?: return@apmap null)
                } else {
                    fixUrl(path, apiUrl)
                }
            }.encodeUrl()

            val size = "%.2f GB".format(
                bytesToGigaBytes(
                    file.size?.toDouble()
                        ?: return@apmap null
                )
            )
            val quality = getIndexQuality(file.name)
            val tags = getIndexQualityTags(file.name)

            callback.invoke(
                ExtractorLink(
                    api,
                    "$api $tags [$size]",
                    path,
                    if (api in needRefererIndex) apiUrl else "",
                    quality,
                )
            )

        }

    }

    suspend fun invokeGdbotMovies(
        title: String? = null,
        year: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        callback: (ExtractorLink) -> Unit,
    ) {
        val query = getIndexQuery(title, null, season, episode)
        val files = app.get("$gdbot/search?q=$query").document.select("ul.divide-y li").map {
            Triple(it.select("a").attr("href"), it.select("a").text(), it.select("span").text())
        }.filter {
            matchingIndex(
                it.second,
                null,
                title,
                year,
                season,
                episode,
            )
        }.sortedByDescending {
            it.third.getFileSize()
        }

        files.let { file ->
            listOfNotNull(
                file.find { it.second.contains("2160p", true) },
                file.find { it.second.contains("1080p", true) })
        }.apmap { file ->
            val videoUrl = extractGdflix(file.first)
            val quality = getIndexQuality(file.second)
            val tags = getIndexQualityTags(file.second)
            val size = Regex("(\\d+\\.?\\d+\\sGB|MB)").find(file.third)?.groupValues?.get(0)?.trim()

            callback.invoke(
                ExtractorLink(
                    "GdbotMovies",
                    "GdbotMovies $tags [$size]",
                    videoUrl ?: return@apmap null,
                    "",
                    quality,
                )
            )

        }

    }

    suspend fun invokeDahmerMovies(
        title: String? = null,
        year: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        callback: (ExtractorLink) -> Unit,
    ) {
        val url = if (season == null) {
            "$dahmerMoviesAPI/movies/${title?.replace(":", "")} ($year)/"
        } else {
            "$dahmerMoviesAPI/tvs/${title?.replace(":", " -")}/Season $season/"
        }

        val request = app.get(url, timeout = 60L)
        if (!request.isSuccessful) return
        val paths = request.document.select("a").map {
            it.text() to it.attr("href")
        }.filter {
            if (season == null) {
                it.first.contains(Regex("(?i)(1080p|2160p)"))
            } else {
                val (seasonSlug, episodeSlug) = getEpisodeSlug(season, episode)
                it.first.contains(Regex("(?i)S${seasonSlug}E${episodeSlug}"))
            }
        }.ifEmpty { return }

        paths.map {
            val quality = getIndexQuality(it.first)
            val tags = getIndexQualityTags(it.first)
            callback.invoke(
                ExtractorLink(
                    "DahmerMovies",
                    "DahmerMovies $tags",
                    (url + it.second).encodeUrl(),
                    "",
                    quality,
                )
            )

        }

    }

    suspend fun invoke2embed(
        imdbId: String?,
        season: Int?,
        episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val url = if (season == null) {
            "$twoEmbedAPI/embed/$imdbId"
        } else {
            "$twoEmbedAPI/embedtv/$imdbId&s=$season&e=$episode"
        }

        val framesrc = app.get(url).document.selectFirst("iframe#iframesrc")?.attr("data-src")
            ?: return
        val ref = getBaseUrl(framesrc)
        val id = framesrc.substringAfter("id=").substringBefore("&")
        loadExtractor("https://uqloads.xyz/e/$id", "$ref/", subtitleCallback, callback)

    }

    suspend fun invokeGhostx(
        title: String? = null,
        year: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        callback: (ExtractorLink) -> Unit,
    ) {
        invokeGpress(
            title,
            year,
            season,
            episode,
            callback,
            BuildConfig.GHOSTX_API,
            "Ghostx",
            base64Decode("X3NtUWFtQlFzRVRi"),
            base64Decode("X3NCV2NxYlRCTWFU")
        )
    }

    private suspend fun invokeGpress(
        title: String? = null,
        year: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        callback: (ExtractorLink) -> Unit,
        api: String,
        name: String,
        mediaSelector: String,
        episodeSelector: String,
    ) {
        fun String.decrypt(key: String): List<GpressSources>? {
            return tryParseJson<List<GpressSources>>(base64Decode(this).xorDecrypt(key))
        }

        val slug = getEpisodeSlug(season, episode)
        val query = if (season == null) {
            title
        } else {
            "$title Season $season"
        }
        val savedCookies = mapOf(
            base64Decode("X2lkZW50aXR5Z29tb3ZpZXM3") to base64Decode("NTJmZGM3MGIwMDhjMGIxZDg4MWRhYzBmMDFjY2E4MTllZGQ1MTJkZTAxY2M4YmJjMTIyNGVkNGFhZmI3OGI1MmElM0EyJTNBJTdCaSUzQTAlM0JzJTNBMTglM0ElMjJfaWRlbnRpdHlnb21vdmllczclMjIlM0JpJTNBMSUzQnMlM0E1MiUzQSUyMiU1QjIwNTAzNjYlMkMlMjJIblZSUkFPYlRBU09KRXI0NVl5Q004d2lIb2wwVjFrbyUyMiUyQzI1OTIwMDAlNUQlMjIlM0IlN0Q="),
        )

        var res = app.get("$api/search/$query", timeout = 20)
        val cookies = savedCookies + res.cookies
        val doc = res.document
        val media = doc.select("div.$mediaSelector").map {
            Triple(it.attr("data-filmName"), it.attr("data-year"), it.select("a").attr("href"))
        }.let { el ->
            if (el.size == 1) {
                el.firstOrNull()
            } else {
                el.find {
                    if (season == null) {
                        (it.first.equals(title, true) || it.first.equals(
                            "$title ($year)",
                            true
                        )) && it.second.equals("$year")
                    } else {
                        it.first.equals("$title - Season $season", true)
                    }
                }
            } ?: el.find { it.first.contains("$title", true) && it.second.equals("$year") }
        } ?: return

        val iframe = if (season == null) {
            media.third
        } else {
            app.get(fixUrl(media.third, api), cookies = cookies, timeout = 20)
                .document.selectFirst("div#$episodeSelector a:contains(Episode ${slug.second})")
                ?.attr("href")
        }

        res = app.get(fixUrl(iframe ?: return, api), cookies = cookies, timeout = 20)
        val url = res.document.select("meta[property=og:url]").attr("content")
        val headers = mapOf("X-Requested-With" to "XMLHttpRequest")
        val qualities = intArrayOf(2160, 1440, 1080, 720, 480, 360)

        val (serverId, episodeId) = if (season == null) {
            url.substringAfterLast("/") to "0"
        } else {
            url.substringBeforeLast("/").substringAfterLast("/") to url.substringAfterLast("/")
                .substringBefore("-")
        }
        val serverRes = app.get(
            "$api/user/servers/$serverId?ep=$episodeId",
            cookies = cookies,
            referer = url,
            headers = headers,
            timeout = 20
        )
        val script = getAndUnpack(serverRes.text)
        val key = """key\s*=\s*(\d+)""".toRegex().find(script)?.groupValues?.get(1) ?: return
        serverRes.document.select("ul li").apmap { el ->
            val server = el.attr("data-value")
            val encryptedData = app.get(
                "$url?server=$server&_=$unixTimeMS",
                cookies = cookies,
                referer = url,
                headers = headers,
                timeout = 20
            ).text
            val links = encryptedData.decrypt(key)
            links?.forEach { video ->
                qualities.filter { it <= video.max.toInt() }.forEach {
                    callback(
                        ExtractorLink(
                            name,
                            name,
                            video.src.split("360", limit = 3).joinToString(it.toString()),
                            "$api/",
                            it,
                        )
                    )
                }
            }
        }

    }

    suspend fun invokeShowflix(
        title: String? = null,
        year: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val where = if (season == null) "movieName" else "seriesName"
        val classes = if (season == null) "movies" else "series"
        val body = """
        {
            "where": {
                "$where": {
                    "${'$'}regex": "$title",
                    "${'$'}options": "i"
                }
            },
            "order": "-updatedAt",
            "_method": "GET",
            "_ApplicationId": "SHOWFLIXAPPID",
            "_JavaScriptKey": "SHOWFLIXMASTERKEY",
            "_ClientVersion": "js3.4.1",
            "_InstallationId": "d92d98d3-fa49-4103-8dcf-6347c86942a7"
        }
    """.trimIndent().toRequestBody(RequestBodyTypes.JSON.toMediaTypeOrNull())

        val data =
            app.post("https://parse.showflix.tk/parse/classes/$classes", requestBody = body).text
        val iframes = if (season == null) {
            val result = tryParseJson<ShowflixSearchMovies>(data)?.resultsMovies?.find {
                it.movieName.equals("$title ($year)", true)
            }
            listOf(
                "https://streamwish.to/e/${result?.streamwish}",
                "https://filelions.to/v/${result?.filelions}.html",
                "https://streamruby.com/e/${result?.streamruby}.html",
            )
        } else {
            val result = tryParseJson<ShowflixSearchSeries>(data)?.resultsSeries?.find {
                it.seriesName.equals(title, true)
            }
            listOf(
                result?.streamwish?.get("Season $season")?.get(episode!!),
                result?.filelions?.get("Season $season")?.get(episode!!),
                result?.streamruby?.get("Season $season")?.get(episode!!),
            )
        }

        iframes.apmap { iframe ->
            loadExtractor(iframe ?: return@apmap, "$showflixAPI/", subtitleCallback, callback)
        }

    }

    suspend fun invokeCinemaTv(
        imdbId: String? = null,
        title: String? = null,
        year: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val id = imdbId?.removePrefix("tt")
        val slug = title?.createSlug()
        val url = if (season == null) {
            "$cinemaTvAPI/movies/play/$id-$slug-$year"
        } else {
            "$cinemaTvAPI/shows/play/$id-$slug-$year"
        }

        val headers = mapOf(
            "x-requested-with" to "XMLHttpRequest",
        )
        val doc = app.get(url, headers = headers).document
        val script = doc.selectFirst("script:containsData(hash:)")?.data()
        val hash = Regex("hash:\\s*['\"](\\S+)['\"]").find(script ?: return)?.groupValues?.get(1)
        val expires = Regex("expires:\\s*(\\d+)").find(script)?.groupValues?.get(1)
        val episodeId = (if (season == null) {
            """id_movie:\s*(\d+)"""
        } else {
            """episode:\s*['"]$episode['"],[\n\s]+id_episode:\s*(\d+),[\n\s]+season:\s*['"]$season['"]"""
        }).let { it.toRegex().find(script)?.groupValues?.get(1) }

        val videoUrl = if (season == null) {
            "$cinemaTvAPI/api/v1/security/movie-access?id_movie=$episodeId&hash=$hash&expires=$expires"
        } else {
            "$cinemaTvAPI/api/v1/security/episode-access?id_episode=$episodeId&hash=$hash&expires=$expires"
        }

        val sources = app.get(
            videoUrl,
            referer = url,
            headers = headers
        ).parsedSafe<CinemaTvResponse>()

        sources?.streams?.mapKeys { source ->
            callback.invoke(
                ExtractorLink(
                    "CinemaTv",
                    "CinemaTv",
                    source.value,
                    "$cinemaTvAPI/",
                    getQualityFromName(source.key),
                    true
                )
            )
        }

        sources?.subtitles?.map { sub ->
            val file = sub.file.toString()
            subtitleCallback.invoke(
                SubtitleFile(
                    sub.language ?: return@map,
                    if (file.startsWith("[")) return@map else fixUrl(file, cinemaTvAPI),
                )
            )
        }

    }

    suspend fun invokeNinetv(
        tmdbId: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val url = if (season == null) {
            "$nineTvAPI/movie/$tmdbId"
        } else {
            "$nineTvAPI/tv/$tmdbId-$season-$episode"
        }

        val iframe = app.get(url, referer = "https://pressplay.top/").document.selectFirst("iframe")
            ?.attr("src")

        loadExtractor(iframe ?: return, "$nineTvAPI/", subtitleCallback, callback)

    }

    suspend fun invokeNowTv(
        tmdbId: Int? = null,
        imdbId: String? = null,
        season: Int? = null,
        episode: Int? = null,
        callback: (ExtractorLink) -> Unit,
        referer: String = "https://bflix.gs/"
    ) {
        suspend fun String.isSuccess(): Boolean {
            return app.get(this, referer = referer).isSuccessful
        }

        val slug = getEpisodeSlug(season, episode)
        var url =
            if (season == null) "$nowTvAPI/$tmdbId.mp4" else "$nowTvAPI/tv/$tmdbId/s${season}e${slug.second}.mp4"
        if (!url.isSuccess()) {
            url = if (season == null) {
                val temp = "$nowTvAPI/$imdbId.mp4"
                if (temp.isSuccess()) temp else "$nowTvAPI/$tmdbId-1.mp4"
            } else {
                "$nowTvAPI/tv/$imdbId/s${season}e${slug.second}.mp4"
            }
            if (!app.get(url, referer = referer).isSuccessful) return
        }
        callback.invoke(
            ExtractorLink(
                "NowTv",
                "NowTv",
                url,
                referer,
                Qualities.P1080.value,
            )
        )
    }

    suspend fun invokeRidomovies(
        tmdbId: Int? = null,
        imdbId: String? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val mediaSlug = app.get("$ridomoviesAPI/core/api/search?q=$imdbId")
            .parsedSafe<RidoSearch>()?.data?.items?.find {
            it.contentable?.tmdbId == tmdbId || it.contentable?.imdbId == imdbId
        }?.slug ?: return

        val id = season?.let {
            val episodeUrl = "$ridomoviesAPI/tv/$mediaSlug/season-$it/episode-$episode"
            app.get(episodeUrl).text.substringAfterLast("""postid\":\"""").substringBefore("""\""")
        } ?: mediaSlug

        val url =
            "$ridomoviesAPI/core/api/${if (season == null) "movies" else "episodes"}/$id/videos"
        app.get(url).parsedSafe<RidoResponses>()?.data?.apmap { link ->
            val iframe = Jsoup.parse(link.url ?: return@apmap).select("iframe").attr("data-src")
            if (iframe.startsWith("https://closeload.top")) {
                val unpacked = getAndUnpack(app.get(iframe, referer = "$ridomoviesAPI/").text)
                val video = Regex("=\"(aHR.*?)\";").find(unpacked)?.groupValues?.get(1)
                callback.invoke(
                    ExtractorLink(
                        "Ridomovies",
                        "Ridomovies",
                        base64Decode(video ?: return@apmap),
                        "${getBaseUrl(iframe)}/",
                        Qualities.P1080.value,
                        isM3u8 = true
                    )
                )
            } else {
                loadExtractor(iframe, "$ridomoviesAPI/", subtitleCallback, callback)
            }
        }
    }

    suspend fun invokeAllMovieland(
        imdbId: String? = null,
        season: Int? = null,
        episode: Int? = null,
        callback: (ExtractorLink) -> Unit,
        host: String = "https://dozzlegram-duj-i-280.site",
    ) {
        val res = app.get(
            "$host/play/$imdbId",
            referer = "$allmovielandAPI/"
        ).document.selectFirst("script:containsData(player =)")?.data()?.substringAfter("{")
            ?.substringBefore(";")?.substringBefore(")")
        val json = tryParseJson<AllMovielandPlaylist>("{${res ?: return}")
        val headers = mapOf("X-CSRF-TOKEN" to "${json?.key}")

        val serverRes = app.get(
            fixUrl(
                json?.file
                    ?: return, host
            ), headers = headers, referer = "$allmovielandAPI/"
        ).text.replace(Regex(""",\s*\[]"""), "")
        val servers = tryParseJson<ArrayList<AllMovielandServer>>(serverRes).let { server ->
            if (season == null) {
                server?.map { it.file to it.title }
            } else {
                server?.find { it.id.equals("$season") }?.folder?.find { it.episode.equals("$episode") }?.folder?.map {
                    it.file to it.title
                }
            }
        }

        servers?.apmap { (server, lang) ->
            val path = app.post(
                "${host}/playlist/${server ?: return@apmap}.txt",
                headers = headers,
                referer = "$allmovielandAPI/"
            ).text
            M3u8Helper.generateM3u8("Allmovieland [$lang]", path, "$allmovielandAPI/")
                .forEach(callback)
        }

    }

    suspend fun invokeEmovies(
        title: String? = null,
        year: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val slug = title.createSlug()
        val url = if (season == null) {
            "$emoviesAPI/watch-$slug-$year-1080p-hd-online-free/watching.html"
        } else {
            val first = "$emoviesAPI/watch-$slug-season-$season-$year-1080p-hd-online-free.html"
            val second = "$emoviesAPI/watch-$slug-$year-1080p-hd-online-free.html"
            if (app.get(first).isSuccessful) first else second
        }

        val res = app.get(url).document
        val id = (if (season == null) {
            res.selectFirst("select#selectServer option[sv=oserver]")?.attr("value")
        } else {
            res.select("div.le-server a").find {
                val num =
                    Regex("Episode (\\d+)").find(it.text())?.groupValues?.get(1)?.toIntOrNull()
                num == episode
            }?.attr("href")
        })?.substringAfter("id=")?.substringBefore("&")

        val server = app.get(
            "$emoviesAPI/ajax/v4_get_sources?s=oserver&id=${id ?: return}&_=${unixTimeMS}",
            headers = mapOf("X-Requested-With" to "XMLHttpRequest")
        ).parsedSafe<EMovieServer>()?.value

        val script = app.get(
            server
                ?: return, referer = "$emoviesAPI/"
        ).document.selectFirst("script:containsData(sources:)")?.data()
            ?: return
        val sources = Regex("sources:\\s*\\[(.*)],").find(script)?.groupValues?.get(1)?.let {
            tryParseJson<List<EMovieSources>>("[$it]")
        }
        val tracks = Regex("tracks:\\s*\\[(.*)],").find(script)?.groupValues?.get(1)?.let {
            tryParseJson<List<EMovieTraks>>("[$it]")
        }

        sources?.map { source ->
            M3u8Helper.generateM3u8(
                "Emovies", source.file
                    ?: return@map, "https://embed.vodstream.xyz/"
            ).forEach(callback)
        }

        tracks?.map { track ->
            subtitleCallback.invoke(
                SubtitleFile(
                    track.label ?: "",
                    track.file ?: return@map,
                )
            )
        }


    }

    suspend fun invokeSFMovies(
        tmdbId: Int? = null,
        title: String? = null,
        year: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        callback: (ExtractorLink) -> Unit,
    ) {
        val headers =
            mapOf("Authorization" to "Bearer 44d784c55e9a1e3dbb586f24b18b1cbcd1521673bd6178ef385890d2f989681fe22d05e291e2e0f03fce99cbc50cd520219e52cc6e30c944a559daf53a129af18349ec98f6a0e4e66b8d370a354f4f7fbd49df0ab806d533a3db71eecc7f75131a59ce8cffc5e0cc38e8af5919c23c0d904fbe31995308f065f0ff9cd1eda488")
        val data = app.get(
            "${BuildConfig.SFMOVIES_API}/api/mains?filters[title][\$contains]=$title",
            headers = headers
        ).parsedSafe<SFMoviesSearch>()?.data
        val media = data?.find {
            it.attributes?.contentId.equals("$tmdbId") || (it.attributes?.title.equals(
                title,
                true
            ) || it.attributes?.releaseDate?.substringBefore("-").equals("$year"))
        }
        val video = if (season == null || episode == null) {
            media?.attributes?.video
        } else {
            media?.attributes?.seriess?.get(season - 1)?.get(episode - 1)?.svideos
        } ?: return
        callback.invoke(
            ExtractorLink(
                "SFMovies",
                "SFMovies",
                fixUrl(video, getSfServer()),
                "",
                Qualities.P1080.value,
                INFER_TYPE
            )
        )
    }

}

