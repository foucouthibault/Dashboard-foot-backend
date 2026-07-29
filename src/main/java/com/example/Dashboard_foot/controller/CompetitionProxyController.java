package com.example.Dashboard_foot.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

@RestController
@RequestMapping("/api/competitions")
public class CompetitionProxyController {

    private final RestTemplate restTemplate;
    
    @Value("${football-data.api.base-url:https://api.football-data.org/v4}")
    private String apiBaseUrl;
    
    @Value("${football-data.api.token:}")
    private String apiToken;

    public CompetitionProxyController(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @GetMapping("/{id}")
    @Cacheable(value = "footballData", key = "#id")
    public ResponseEntity<String> proxyCompetition(@PathVariable String id, 
                                                   @RequestHeader(required = false) HttpHeaders headers) {
        String url = apiBaseUrl + "/competitions/" + id;
        return proxyRequest(url, headers);
    }

    @GetMapping("/{id}/standings")
    @Cacheable(value = "footballData", key = "'standings_' + #id")
    public ResponseEntity<String> proxyStandings(@PathVariable String id, 
                                                 @RequestHeader(required = false) HttpHeaders headers) {
        String url = apiBaseUrl + "/competitions/" + id + "/standings";
        return proxyRequest(url, headers);
    }

    @GetMapping("/{id}/matches")
    @Cacheable(value = "footballData", key = "'matches_' + #id")
    public ResponseEntity<String> proxyMatches(@PathVariable String id, 
                                               @RequestHeader(required = false) HttpHeaders headers) {
        String url = apiBaseUrl + "/competitions/" + id + "/matches";
        return proxyRequest(url, headers);
    }

    private ResponseEntity<String> proxyRequest(String url, HttpHeaders incomingHeaders) {
        HttpHeaders requestHeaders = new HttpHeaders();
        
        // Propager certains headers si nécessaire (sauf Accept-Encoding pour éviter le gzip)
        if (incomingHeaders != null) {
            incomingHeaders.forEach((key, values) -> {
                if (key.equalsIgnoreCase("accept") || 
                    key.equalsIgnoreCase("accept-language")) {
                    requestHeaders.put(key, values);
                }
            });
        }
        // Forcer Accept à application/json
        requestHeaders.setAccept(java.util.Collections.singletonList(MediaType.APPLICATION_JSON));
        
        // Ajouter le token d'authentification pour football-data.org
        if (apiToken != null && !apiToken.isEmpty()) {
            requestHeaders.set("X-Auth-Token", apiToken);
        }
        
        HttpEntity<String> entity = new HttpEntity<>(requestHeaders);
        
        try {
            ResponseEntity<String> response = restTemplate.exchange(
                url, 
                HttpMethod.GET, 
                entity, 
                String.class
            );
            
            // Nettoyer les headers de réponse pour éviter les problèmes de compression
            HttpHeaders responseHeaders = new HttpHeaders();
            response.getHeaders().forEach((key, values) -> {
                // Supprimer Transfer-Encoding et Content-Encoding pour retourner du JSON brut
                if (!key.equalsIgnoreCase("Transfer-Encoding") && 
                    !key.equalsIgnoreCase("Content-Encoding")) {
                    responseHeaders.put(key, values);
                }
            });
            // Forcer Content-Type à application/json
            responseHeaders.setContentType(MediaType.APPLICATION_JSON);
            
            return new ResponseEntity<>(response.getBody(), responseHeaders, response.getStatusCode());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body("{" + "\"error\":\"Failed to proxy request: " + e.getMessage() + "\"" + "}");
        }
    }
}
