package com.hexated

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import org.jsoup.nodes.Element
import java.net.URI

open class Full4movie : Movierulzhd(){
    override var mainUrl = "https://full4movies.quest"
    var directUrl = ""
    override var name = "full4movie"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
    )

    override val mainPage = mainPageOf(
        "category/web-series/" to "Web Series",
        "category/bollywood-movies-download" to "Bollywood",
    )
    override suspend fun getMainPage(
        page:Int,
        request:MainPageRequest
    ): HomePageResponse{
        val document = app.get("$mainUrl/${request.data}/page/$page").document
        val home =
            document.select("div.posts-wrapper div.content").mapNotNull {
                it.toSearchResult()
            }
        return newHomePageResponse(request.name, home)
    }
    private fun getProperLink(uri: String): String {
        return when {
            uri.contains("web-series") -> {
                var title = uri.substringAfter("$mainUrl/")
                title = Regex("(.+?)-season").find(title)?.groupValues?.get(1).toString()
                "$mainUrl/tvshows/$title"
            }

            else -> {
                uri
            }
        }
    }
    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("h2 > a")?.text() ?: return null
        val href = getProperLink(fixUrl(this.selectFirst("h2 > a")!!.attr("href")))
        val posterUrl = fixUrlNull(this.select("div.img-wrap img").last()?.getImageAttr())
        val quality = getQualityFromString(this.select("span.quality").text())
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
            this.quality = quality
        }

    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("div.posts-wrapper").map {
            val title =
                it.selectFirst("h2 > a")!!.text().replace(Regex("\\(\\d{4}\\)"), "").trim()
            val href = getProperLink(it.selectFirst("h2 > a")!!.attr("href"))
            val posterUrl = it.selectFirst("img")!!.attr("src").toString()
            newMovieSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        }
    }
    override suspend fun load(url: String): LoadResponse {
        val request = app.get(url)
        val document = request.document
        directUrl = getBaseUrl(request.url)
        val title = url.toString()
        val poster = fixUrlNull(document.selectFirst("img.aligncenter")?.getImageAttr())

         newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
         }
        
    }
    private fun getBaseUrl(url: String): String {
        return URI(url).let {
            "${it.scheme}://${it.host}"
        }
    }

    

}