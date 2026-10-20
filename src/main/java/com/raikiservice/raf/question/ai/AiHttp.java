package com.raikiservice.raf.question.ai;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.function.Consumer;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.ObjectMapper;

/** Appel HTTP commun aux adaptateurs : delais, et erreurs traduites pour l'administrateur. */
final class AiHttp {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    AiHttp(Duration readTimeout, ObjectMapper objectMapper) {
        // Client HTTP du JDK : contrairement a HttpURLConnection, il garde le corps
        // des reponses 401, qui explique a l'administrateur que la cle est refusee.
        // HTTP/1.1 : pas de tentative de passage en HTTP/2 sur http://, que des
        // serveurs locaux (Ollama) ou des proxys gerent mal.
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(CONNECT_TIMEOUT)
                .build());
        // Generer 30 questions prend facilement une minute.
        factory.setReadTimeout(readTimeout == null ? Duration.ofSeconds(180) : readTimeout);
        this.restClient = RestClient.builder().requestFactory(factory).build();
        this.objectMapper = objectMapper;
    }

    /**
     * POST JSON ; renvoie le corps brut de la reponse. Le corps part serialise,
     * avec sa longueur : envoye en flux (chunked), certains serveurs le lisent vide.
     */
    String post(String url, Consumer<HttpHeaders> headers, Map<String, Object> body) {
        try {
            return restClient.post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(headers)
                    .body(objectMapper.writeValueAsBytes(body))
                    .retrieve()
                    .body(String.class);
        } catch (RestClientResponseException e) {
            throw new AiGenerationException("Le fournisseur d'IA a repondu " + e.getStatusCode().value()
                    + " : " + excerpt(e.getResponseBodyAsString()), e);
        } catch (ResourceAccessException e) {
            throw new AiGenerationException("Fournisseur d'IA injoignable ou trop lent : " + e.getMessage(), e);
        }
    }

    static String excerpt(String text) {
        if (text == null) {
            return "";
        }
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= 300 ? flat : flat.substring(0, 300) + "...";
    }

    static String withoutTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
