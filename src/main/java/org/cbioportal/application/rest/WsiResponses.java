package org.cbioportal.application.rest;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Helpers for the WSI study slides endpoint, whose responses are private to the requesting user.
 */
final class WsiResponses {

  private WsiResponses() {}

  /** A response that no shared cache may store and that varies with the caller's credentials. */
  static ResponseEntity.BodyBuilder privateResponse(HttpStatus status) {
    return ResponseEntity.status(status)
        .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
        .header(HttpHeaders.VARY, "Authorization, Cookie");
  }
}
