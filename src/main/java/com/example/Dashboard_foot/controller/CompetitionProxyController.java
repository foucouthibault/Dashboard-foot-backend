package com.example.Dashboard_foot.controller;

import com.example.Dashboard_foot.service.FootballDataProxyService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/competitions")
public class CompetitionProxyController {

    private static final String VALID_ID_PATTERN = "^[a-zA-Z0-9_-]+$";

    private final FootballDataProxyService footballDataProxyService;

    public CompetitionProxyController(FootballDataProxyService footballDataProxyService) {
        this.footballDataProxyService = footballDataProxyService;
    }

    private boolean isValidId(String id) {
        return id == null || !id.matches(VALID_ID_PATTERN);
    }

    private ResponseEntity<String> invalidIdResponse() {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body("{" + "\"error\":\"Invalid competition ID format\"" + "}");
    }

    @GetMapping("/{id}")
    public ResponseEntity<String> proxyCompetition(@PathVariable String id,
                                                   @RequestHeader(required = false) HttpHeaders headers) {
        if (isValidId(id)) {
            return invalidIdResponse();
        }
        return footballDataProxyService.getCompetition(id, headers);
    }

    @GetMapping("/{id}/standings")
    public ResponseEntity<String> proxyStandings(@PathVariable String id,
                                                 @RequestParam(required = false) String season,
                                                 @RequestHeader(required = false) HttpHeaders headers) {
        if (isValidId(id)) {
            return invalidIdResponse();
        }
        return footballDataProxyService.getStandings(id, season, headers);
    }

    @GetMapping("/{id}/matches")
    public ResponseEntity<String> proxyMatches(@PathVariable String id,
                                               @RequestHeader(required = false) HttpHeaders headers) {
        if (isValidId(id)) {
            return invalidIdResponse();
        }
        return footballDataProxyService.getMatches(id, headers);
    }

    @GetMapping("/{id}/scorers")
    public ResponseEntity<String> proxyScorers(@PathVariable String id,
                                              @RequestParam(required = false) Integer limit,
                                              @RequestParam(required = false) String season,
                                              @RequestHeader(required = false) HttpHeaders headers) {
        if (isValidId(id)) {
            return invalidIdResponse();
        }
        return footballDataProxyService.getScorers(id, limit, season, headers);
    }
}
