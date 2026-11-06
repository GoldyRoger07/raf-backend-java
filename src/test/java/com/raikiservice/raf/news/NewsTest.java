package com.raikiservice.raf.news;

import com.raikiservice.raf.common.error.ErrorCode;
import com.raikiservice.raf.news.service.NewsService;
import com.raikiservice.raf.news.web.dto.NewsDtos.NewsItem;
import com.raikiservice.raf.news.web.dto.NewsDtos.NewsRequest;
import com.raikiservice.raf.news.web.dto.NewsDtos.NewsResponse;
import com.raikiservice.raf.support.IntegrationTest;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

import static org.assertj.core.api.Assertions.assertThat;

/** Actualites : brouillon invisible du joueur, publication, epinglage, suppression. */
class NewsTest extends IntegrationTest {

    @Autowired private NewsService newsService;

    @Test
    void aDraftIsHiddenUntilPublishedAndKeepsItsFirstPublicationDate() {
        NewsResponse draft = newsService.create(null, new NewsRequest("Brouillon", "Texte", null, false, false));
        assertThat(draft.publishedAt()).isNull();
        assertThat(feedIds()).doesNotContain(draft.id());

        NewsResponse published = newsService.update(null, draft.id(),
                new NewsRequest("Publiee", "Texte", null, false, true));
        assertThat(published.publishedAt()).isNotNull();
        assertThat(feedIds()).contains(draft.id());

        newsService.update(null, draft.id(), new NewsRequest("Publiee", "Texte", null, false, false));
        assertThat(feedIds()).doesNotContain(draft.id());
        NewsResponse again = newsService.update(null, draft.id(),
                new NewsRequest("Publiee", "Texte", null, false, true));
        assertThat(again.publishedAt()).isEqualTo(published.publishedAt());
    }

    @Test
    void aPinnedNewsComesFirstAndADeletedOneDisappears() {
        NewsResponse pinned = newsService.create(null, new NewsRequest("Epinglee", "Texte", null, true, true));
        NewsResponse recent = newsService.create(null, new NewsRequest("Recente", "Texte", null, false, true));

        assertThat(feedIds().indexOf(pinned.id())).isLessThan(feedIds().indexOf(recent.id()));

        newsService.delete(null, pinned.id());
        newsService.delete(null, recent.id());
        assertThat(feedIds()).doesNotContain(pinned.id(), recent.id());
    }

    @Test
    void anUnknownImageIsRefused() {
        assertApiError(ErrorCode.RESOURCE_NOT_FOUND, () ->
                newsService.create(null, new NewsRequest("Titre", "Texte", Long.MAX_VALUE, false, true)));
    }

    private java.util.List<Long> feedIds() {
        return newsService.feed(PageRequest.of(0, 500)).getContent().stream().map(NewsItem::id).toList();
    }
}
