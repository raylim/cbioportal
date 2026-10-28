package org.cbioportal.application.rest;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Issues study-scoped capabilities for the slide annotation service and the slide assistant. */
@RestController
@RequestMapping("/api/wsi")
public class WsiPurposeTokenController {

  /**
   * Study-scoped capabilities by purpose. They name no slide and carry no {@code wsi_auth_version},
   * so the tile service never accepts them for pixels: slides are reachable only through the
   * per-row capability from {@link ResourceAccessController}.
   */
  static final Map<String, String> PURPOSE_SCOPES =
      Map.of(
          "annotations", "annotations:read annotations:write",
          "agent", "agent:chat research:read annotations:read annotations:write");

  @Value("${wsi.access-token-secret:}")
  private String accessTokenSecret;

  @Value("${wsi.access-token-audience:cbioportal-wsi}")
  private String accessTokenAudience;

  @Value("${wsi.access-token-ttl-seconds:300}")
  private int accessTokenTtlSeconds;

  /**
   * Issues a study-scoped capability for the annotation service ({@code purpose=annotations}) or
   * the slide assistant ({@code purpose=agent}). Any other purpose is a 400: slide access is per
   * slide, from the resources access endpoint only.
   */
  @GetMapping("/access-token")
  @PreAuthorize(
      "hasPermission(#studyId, 'CancerStudyId', T(org.cbioportal.legacy.utils.security.AccessLevel).READ)")
  public ResponseEntity<Map<String, Object>> issueAccessToken(
      @RequestParam String studyId, @RequestParam String purpose) {
    String scope = PURPOSE_SCOPES.get(purpose);
    if (scope == null) {
      return ResponseEntity.badRequest().build();
    }
    Instant issuedAt = Instant.now();
    String token =
        Jwts.builder()
            .setHeaderParam("typ", "JWT")
            .setSubject(subject())
            .setAudience(accessTokenAudience)
            .claim("scope", scope)
            .claim("study_id", studyId)
            .setIssuedAt(Date.from(issuedAt))
            .setExpiration(Date.from(issuedAt.plusSeconds(accessTokenTtlSeconds)))
            .signWith(SignatureAlgorithm.HS256, accessTokenSecret.getBytes(StandardCharsets.UTF_8))
            .compact();
    return ResponseEntity.ok(
        Map.of("access_token", token, "token_type", "Bearer", "expires_in", accessTokenTtlSeconds));
  }

  /** The caller's name, or {@code anonymousUser} when the portal has no login or nobody is in. */
  private static String subject() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    return authentication == null || authentication.getName() == null
        ? "anonymousUser"
        : authentication.getName();
  }
}
