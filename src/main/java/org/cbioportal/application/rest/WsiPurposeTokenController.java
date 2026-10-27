package org.cbioportal.application.rest;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.cbioportal.application.security.CancerStudyPermissionEvaluator;
import org.cbioportal.legacy.utils.security.AccessLevel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
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

  /** Local development stacks may opt into issuing capabilities without portal authentication. */
  @Value("${wsi.local-auth-bypass:false}")
  private boolean localAuthBypass;

  /** Absent when portal authentication is off; then no study capability is issued. */
  private final ObjectProvider<CancerStudyPermissionEvaluator> cancerStudyPermissionEvaluator;

  public WsiPurposeTokenController(
      ObjectProvider<CancerStudyPermissionEvaluator> cancerStudyPermissionEvaluator) {
    this.cancerStudyPermissionEvaluator = cancerStudyPermissionEvaluator;
  }

  /**
   * Issues a study-scoped capability for the annotation service ({@code purpose=annotations}) or
   * the slide assistant ({@code purpose=agent}). Any other or missing purpose is a 400: slide
   * access is per slide, from the resources access endpoint only.
   */
  @GetMapping("/access-token")
  @PreAuthorize(
      "!isAuthenticated() or hasPermission(#studyId, 'CancerStudyId', "
          + "T(org.cbioportal.legacy.utils.security.AccessLevel).READ)")
  public ResponseEntity<?> issueAccessToken(
      @RequestParam(required = false) String studyId,
      @RequestParam(required = false) String purpose) {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    boolean anonymous = isAnonymous(authentication);
    if (anonymous && !localAuthBypass) {
      return privateResponse(HttpStatus.UNAUTHORIZED).build();
    }
    if (anonymous) {
      authentication = localDevelopmentAuthentication();
    }
    if (studyId == null
        || studyId.isBlank()
        || purpose == null
        || !PURPOSE_SCOPES.containsKey(purpose)) {
      return privateResponse(HttpStatus.BAD_REQUEST).build();
    }
    if (!anonymous) {
      CancerStudyPermissionEvaluator evaluator = cancerStudyPermissionEvaluator.getIfAvailable();
      if (evaluator == null
          || !evaluator.hasPermission(authentication, studyId, "CancerStudyId", AccessLevel.READ)) {
        return privateResponse(HttpStatus.FORBIDDEN).build();
      }
    }
    if (accessTokenSecret == null
        || accessTokenSecret.getBytes(StandardCharsets.UTF_8).length < 32) {
      return privateResponse(HttpStatus.SERVICE_UNAVAILABLE).build();
    }

    int ttl = Math.max(60, Math.min(accessTokenTtlSeconds, 300));
    Instant issuedAt = Instant.now();
    String token =
        issuePurposeToken(authentication, studyId, purpose, issuedAt, issuedAt.plusSeconds(ttl));
    return privateResponse(HttpStatus.OK)
        .body(Map.of("access_token", token, "token_type", "Bearer", "expires_in", ttl));
  }

  private String issuePurposeToken(
      Authentication authentication,
      String studyId,
      String purpose,
      Instant issuedAt,
      Instant expiresAt) {
    return Jwts.builder()
        .setHeaderParam("typ", "JWT")
        .setSubject(authentication.getName())
        .setAudience(accessTokenAudience)
        .claim("scope", PURPOSE_SCOPES.get(purpose))
        .claim("study_id", studyId)
        .setIssuedAt(Date.from(issuedAt))
        .setExpiration(Date.from(expiresAt))
        .signWith(SignatureAlgorithm.HS256, accessTokenSecret.getBytes(StandardCharsets.UTF_8))
        .compact();
  }

  private static boolean isAnonymous(Authentication authentication) {
    return authentication == null
        || !authentication.isAuthenticated()
        || authentication instanceof AnonymousAuthenticationToken;
  }

  /** A response that no shared cache may store and that varies with the caller's credentials. */
  private static ResponseEntity.BodyBuilder privateResponse(HttpStatus status) {
    return ResponseEntity.status(status)
        .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
        .header(HttpHeaders.VARY, "Authorization, Cookie");
  }

  private static Authentication localDevelopmentAuthentication() {
    return new UsernamePasswordAuthenticationToken("local-development", null, List.of());
  }
}
