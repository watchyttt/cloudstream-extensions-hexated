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
    

}
