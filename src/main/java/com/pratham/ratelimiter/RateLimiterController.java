package com.pratham.ratelimiter;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RateLimiterController {

    @Autowired
    private RateLimiterService rateLimiterService;

    // The real, production-style endpoint: atomic, Lua-script backed.
    @GetMapping("/api/check")
    public ResponseEntity<String> check(@RequestParam String userId) {
        boolean allowed = rateLimiterService.isAllowedAtomic(userId);
        if (allowed) {
            return ResponseEntity.ok("allowed");
        }
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body("rejected");
    }

    // The intentionally-broken endpoint, kept only to demonstrate the
    // race condition for comparison. Not something you'd ship.
    @GetMapping("/api/check-naive")
    public ResponseEntity<String> checkNaive(@RequestParam String userId) {
        boolean allowed = rateLimiterService.isAllowedNaive(userId);
        if (allowed) {
            return ResponseEntity.ok("allowed");
        }
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body("rejected");
    }
}
