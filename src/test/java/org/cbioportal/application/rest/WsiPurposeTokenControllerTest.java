package org.cbioportal.application.rest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.cbioportal.application.security.CancerStudyPermissionEvaluator;
import org.cbioportal.legacy.utils.security.AccessLevel;
import org.junit.After;
import org.junit.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

public class WsiPurposeTokenControllerTest {

  private static final String SECRET = "0123456789abcdef0123456789abcdef";

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final CancerStudyPermissionEvaluator cancerStudyPermissionEvaluator =
      mock(CancerStudyPermissionEvaluator.class);

  @SuppressWarnings("unchecked")
  private final ObjectProvider<CancerStudyPermissionEvaluator> permissionEvaluatorProvider =
      mock(ObjectProvider.class);

  @After
  public void tearDown() {
    SecurityContextHolder.clearContext();
  }

  @Test
  public void returnsAnnotationScopeWhenRequested() throws Exception {
    WsiPurposeTokenController controller = createStudyReaderController();

    ResponseEntity<?> response = controller.issueAccessToken("study-1", "annotations");

    assertEquals(200, response.getStatusCode().value());
    Map<?, ?> body = (Map<?, ?>) response.getBody();
    assertNotNull(body);
    assertEquals("Bearer", body.get("token_type"));
    assertEquals(300, body.get("expires_in"));
    JsonNode claims = claims((String) body.get("access_token"));
    assertEquals("private, no-store", response.getHeaders().getCacheControl());
    // The tile service accepts only HS256 tokens that declare typ JWT.
    JsonNode header =
        objectMapper.readTree(
            new String(
                Base64.getUrlDecoder().decode(((String) body.get("access_token")).split("\\.")[0]),
                StandardCharsets.UTF_8));
    assertEquals("JWT", header.get("typ").asText());
    assertEquals("HS256", header.get("alg").asText());
    assertEquals("annotations:read annotations:write", claims.get("scope").asText());
    assertEquals("study-1", claims.get("study_id").asText());
    assertNoSlideClaims(claims);
  }

  @Test
  public void returnsAgentScopesWhenRequested() throws Exception {
    WsiPurposeTokenController controller = createStudyReaderController();

    ResponseEntity<?> response = controller.issueAccessToken("study-1", "agent");

    assertEquals(200, response.getStatusCode().value());
    JsonNode claims = claims((String) ((Map<?, ?>) response.getBody()).get("access_token"));
    assertEquals(
        "agent:chat research:read annotations:read annotations:write",
        claims.get("scope").asText());
    assertEquals("study-1", claims.get("study_id").asText());
    assertNoSlideClaims(claims);
  }

  @Test
  public void neverIssuesAStudyWideSlideCapability() {
    WsiPurposeTokenController controller = createStudyReaderController();

    // Slide pixels are reachable only through the per-slide resources access endpoint.
    for (String purpose : new String[] {"wsi", "WSI", "Annotations", "tiles", "", " ", null}) {
      assertEquals(
          "purpose should be rejected: " + purpose,
          400,
          controller.issueAccessToken("study-1", purpose).getStatusCode().value());
    }
    verifyNoInteractions(cancerStudyPermissionEvaluator);
  }

  @Test
  public void requiresLoginForAStudyCapability() {
    WsiPurposeTokenController controller = createStudyReaderController();
    SecurityContextHolder.clearContext();

    assertEquals(
        401, controller.issueAccessToken("study-1", "annotations").getStatusCode().value());
  }

  @Test
  public void refusesAStudyCapabilityWhenPermissionsAreUnavailable() {
    WsiPurposeTokenController controller = createAuthenticatedController();

    assertEquals(
        403, controller.issueAccessToken("study-1", "annotations").getStatusCode().value());
  }

  @Test
  public void refusesAStudyCapabilityWithoutStudyReadPermission() {
    WsiPurposeTokenController controller = createStudyReaderController();

    assertEquals(403, controller.issueAccessToken("study-2", "agent").getStatusCode().value());
  }

  private JsonNode claims(String token) throws Exception {
    return objectMapper.readTree(
        new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]), StandardCharsets.UTF_8));
  }

  /** A study capability names no slide and cannot be presented to the tile service. */
  private static void assertNoSlideClaims(JsonNode claims) {
    for (String slideClaim :
        List.of(
            "slide_key",
            "image_id",
            "enc",
            "wsi_auth_version",
            "tile_source",
            "thumbnail_source",
            "tile_source_sha256",
            "thumbnail_source_sha256",
            "thumbnail_width",
            "thumbnail_height")) {
      assertFalse("study capability carries " + slideClaim, claims.has(slideClaim));
    }
    assertFalse(claims.get("scope").asText().contains("wsi:"));
  }

  private WsiPurposeTokenController createStudyReaderController() {
    WsiPurposeTokenController controller = createAuthenticatedController();
    when(permissionEvaluatorProvider.getIfAvailable()).thenReturn(cancerStudyPermissionEvaluator);
    when(cancerStudyPermissionEvaluator.hasPermission(
            any(Authentication.class), eq("study-1"), eq("CancerStudyId"), eq(AccessLevel.READ)))
        .thenReturn(true);
    return controller;
  }

  private WsiPurposeTokenController createAuthenticatedController() {
    WsiPurposeTokenController controller =
        new WsiPurposeTokenController(permissionEvaluatorProvider);
    ReflectionTestUtils.setField(controller, "accessTokenSecret", SECRET);
    ReflectionTestUtils.setField(controller, "accessTokenAudience", "cbioportal-wsi");
    ReflectionTestUtils.setField(controller, "accessTokenTtlSeconds", 300);
    TestingAuthenticationToken authentication =
        new TestingAuthenticationToken("user", "password", "ROLE_USER");
    authentication.setAuthenticated(true);
    SecurityContextHolder.getContext().setAuthentication(authentication);
    return controller;
  }
}
