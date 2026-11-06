package com.raikiservice.raf.news.web;

import com.raikiservice.raf.news.service.NewsService;
import com.raikiservice.raf.news.web.dto.NewsDtos.NewsItem;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/news")
@RequiredArgsConstructor
@Tag(name = "Actualites")
public class NewsController {

    private final NewsService newsService;

    @GetMapping
    @Operation(summary = "Fil d'actualite du joueur : publiees, epinglees d'abord")
    public Page<NewsItem> feed(@PageableDefault(size = 10) Pageable pageable) {
        return newsService.feed(pageable);
    }
}
