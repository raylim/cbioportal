package org.cbioportal.application.rest;

import java.util.List;
import org.cbioportal.domain.wsi.WsiStudySlidesPage;
import org.cbioportal.domain.wsi.WsiStudySlidesQuery;
import org.cbioportal.domain.wsi.WsiStudySlidesService;
import org.cbioportal.legacy.web.parameter.StudyViewFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Lists the patients with whole-slide images in a study-view cohort. */
@RestController
@RequestMapping("/api/wsi/v2/study-slides")
public class WsiStudySlidesController {

  static final int DEFAULT_PAGE_SIZE = 50;
  static final int MAX_PAGE_SIZE = 100;
  static final int MAX_SEARCH_LENGTH = 64;

  /**
   * A study-view cohort plus list options.
   *
   * @param studyViewFilter the study-view filter defining the cohort
   * @param stainGroups stain groups to keep ({@code H&E}, {@code IHC}, {@code Other}, {@code
   *     Unknown}); empty or null keeps all
   * @param matchLevels specimen match levels to keep ({@code PART}, {@code BLOCK}, {@code
   *     UNMATCHED}); empty or null keeps all
   * @param search keeps slides whose patient or sample ID contains this, ignoring case
   * @param locateStudyId with {@code locatePatientId}, a patient whose list position is returned
   * @param locatePatientId see {@code locateStudyId}
   * @param pageNumber zero-based page, default 0
   * @param pageSize patients per page, 1 to 100, default 50
   */
  public record WsiStudySlidesRequest(
      StudyViewFilter studyViewFilter,
      List<String> stainGroups,
      List<String> matchLevels,
      String search,
      String locateStudyId,
      String locatePatientId,
      Integer pageNumber,
      Integer pageSize) {}

  private final WsiStudySlidesService service;

  @Value("${wsi.local-auth-bypass:false}")
  private boolean localAuthBypass;

  public WsiStudySlidesController(WsiStudySlidesService service) {
    this.service = service;
  }

  @PostMapping(
      value = "/patients/fetch",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  @PreAuthorize(
      "!isAuthenticated() or hasPermission(#request?.studyViewFilter(), 'StudyViewFilter', "
          + "T(org.cbioportal.legacy.utils.security.AccessLevel).READ)")
  public ResponseEntity<WsiStudySlidesPage> fetchStudySlidePatients(
      @RequestBody WsiStudySlidesRequest request) {
    if (isRefusedAnonymous()) {
      return privateResponse(HttpStatus.UNAUTHORIZED).build();
    }

    WsiStudySlidesQuery query = toQuery(request);
    if (query == null) {
      return privateResponse(HttpStatus.BAD_REQUEST).build();
    }
    return privateResponse(HttpStatus.OK)
        .contentType(MediaType.APPLICATION_JSON)
        .body(service.getStudySlides(request.studyViewFilter(), query));
  }

  /** Returns the validated query, or null when the request is malformed. */
  static WsiStudySlidesQuery toQuery(WsiStudySlidesRequest request) {
    if (request == null) {
      return null;
    }
    int pageNumber = request.pageNumber() == null ? 0 : request.pageNumber();
    int pageSize = request.pageSize() == null ? DEFAULT_PAGE_SIZE : request.pageSize();
    if (pageNumber < 0 || pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
      return null;
    }
    boolean locate = request.locateStudyId() != null && request.locatePatientId() != null;
    return toQuery(
        request.studyViewFilter(),
        request.stainGroups(),
        request.matchLevels(),
        request.search(),
        locate ? request.locateStudyId() : null,
        locate ? request.locatePatientId() : null,
        pageNumber,
        pageSize);
  }

  private static WsiStudySlidesQuery toQuery(
      StudyViewFilter studyViewFilter,
      List<String> requestedStainGroups,
      List<String> requestedMatchLevels,
      String requestedSearch,
      String locateStudyId,
      String locatePatientId,
      int pageNumber,
      int pageSize) {
    if (studyViewFilter == null || studyViewFilter.getUniqueStudyIds().isEmpty()) {
      return null;
    }
    List<String> stainGroups = requestedStainGroups == null ? List.of() : requestedStainGroups;
    List<String> matchLevels = requestedMatchLevels == null ? List.of() : requestedMatchLevels;
    if (!WsiStudySlidesQuery.STAIN_GROUPS.containsAll(stainGroups)
        || !WsiStudySlidesQuery.MATCH_LEVELS.containsAll(matchLevels)) {
      return null;
    }
    String search = requestedSearch == null ? null : requestedSearch.trim();
    if (search != null && search.length() > MAX_SEARCH_LENGTH) {
      return null;
    }
    return new WsiStudySlidesQuery(
        List.copyOf(stainGroups),
        List.copyOf(matchLevels),
        search == null || search.isEmpty() ? null : search,
        locateStudyId,
        locatePatientId,
        pageNumber,
        pageSize);
  }

  private boolean isRefusedAnonymous() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    boolean anonymous =
        authentication == null
            || !authentication.isAuthenticated()
            || authentication instanceof AnonymousAuthenticationToken;
    return anonymous && !localAuthBypass;
  }

  private static ResponseEntity.BodyBuilder privateResponse(HttpStatus status) {
    return ResponseEntity.status(status)
        .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
        .header(HttpHeaders.VARY, "Authorization, Cookie");
  }
}
