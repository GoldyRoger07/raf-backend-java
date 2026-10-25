package com.raikiservice.raf.media.web;

import java.util.concurrent.TimeUnit;

import com.raikiservice.raf.media.service.LocalMediaStorage;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Sert le dossier des medias sous {@code /media/**}.
 *
 * <p>En Docker, nginx sert ce dossier lui-meme et cette route n'est pas
 * sollicitee. Elle fait fonctionner les images en developpement, quand le
 * backend tourne seul ({@code app.media.public-url} pointe alors sur lui).
 */
@Configuration
@RequiredArgsConstructor
public class MediaResourceConfig implements WebMvcConfigurer {

    private final LocalMediaStorage storage;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/media/**")
                .addResourceLocations(storage.root().toUri().toString())
                // Le nom d'un fichier ne designe jamais qu'un seul contenu.
                .setCacheControl(CacheControl.maxAge(365, TimeUnit.DAYS).cachePublic().immutable());
    }
}
