package org.cbioportal.application.rest;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import org.cbioportal.application.security.CancerStudyPermissionEvaluator;
import org.cbioportal.domain.wsi.WsiStudySlidePatient;
import org.cbioportal.domain.wsi.WsiStudySlidesPage;
import org.cbioportal.domain.wsi.WsiStudySlidesQuery;
import org.cbioportal.domain.wsi.WsiStudySlidesService;
import org.cbioportal.legacy.utils.security.AccessLevel;
import org.cbioportal.legacy.web.config.TestConfig;
import org.cbioportal.legacy.web.parameter.StudyViewFilter;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.security.access.expression.method.DefaultMethodSecurityExpressionHandler;
import org.springframework.security.access.expression.method.MethodSecurityExpressionHandler;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@RunWith(SpringJUnit4ClassRunner.class)
@WebMvcTest
@ContextConfiguration(
    classes = {
      WsiStudySlidesController.class,
      TestConfig.class,
      WsiStudySlidesControllerTest.MethodSecurityTestConfig.class
    })
public class WsiStudySlidesControllerTest {

  private static final String PATH = "/api/wsi/v2/study-slides/patients/fetch";
  private static final String STUDY_BODY = "{\"studyViewFilter\":{\"studyIds\":[\"study\"]}";

  @TestConfiguration
  @EnableMethodSecurity(prePostEnabled = true)
  static class MethodSecurityTestConfig {

    @Bean
    MethodSecurityExpressionHandler methodSecurityExpressionHandler(
        CancerStudyPermissionEvaluator cancerStudyPermissionEvaluator) {
      DefaultMethodSecurityExpressionHandler expressionHandler =
          new DefaultMethodSecurityExpressionHandler();
      expressionHandler.setPermissionEvaluator(cancerStudyPermissionEvaluator);
      return expressionHandler;
    }
  }

  @Autowired private MockMvc mockMvc;

  @MockitoBean private WsiStudySlidesService service;

  @MockitoBean private CancerStudyPermissionEvaluator cancerStudyPermissionEvaluator;

  @Test
  public void returnsUnauthorizedWithoutAuthentication() throws Exception {
    perform(STUDY_BODY + "}").andExpect(status().isUnauthorized());
    verifyNoInteractions(service);
  }

  @Test
  @WithMockUser
  public void returnsForbiddenWhenStudyAccessIsDenied() throws Exception {
    allowStudyAccess(false);

    perform(STUDY_BODY + "}").andExpect(status().isForbidden());
    verifyNoInteractions(service);
  }

  @Test
  @WithMockUser
  public void rejectsMalformedRequests() throws Exception {
    allowStudyAccess(true);

    perform(STUDY_BODY + ",\"stainGroups\":[\"Trichrome\"]}").andExpect(status().isBadRequest());
    perform(STUDY_BODY + ",\"pageSize\":101}").andExpect(status().isBadRequest());
    perform(STUDY_BODY + ",\"pageNumber\":-1}").andExpect(status().isBadRequest());
    perform(STUDY_BODY + ",\"patientIdPrefix\":\"" + "P".repeat(65) + "\"}")
        .andExpect(status().isBadRequest());
    verifyNoInteractions(service);
  }

  @Test
  @WithMockUser
  public void returnsThePageForAuthorizedUsers() throws Exception {
    allowStudyAccess(true);
    Map<String, Long> counts = Map.of("H&E", 1L, "IHC", 0L, "Other", 0L, "Unknown", 0L);
    WsiStudySlidesQuery expected =
        new WsiStudySlidesQuery(List.of("H&E"), "P-1", "study", "P-1", 2, 25);
    when(service.getStudySlides(
            argThat(filter -> filter.getStudyIds().equals(List.of("study"))), eq(expected)))
        .thenReturn(
            new WsiStudySlidesPage(
                1,
                1,
                1,
                counts,
                0L,
                2,
                25,
                List.of(new WsiStudySlidePatient("study", "P-1", 1, 1, counts))));

    perform(
            STUDY_BODY
                + ",\"stainGroups\":[\"H&E\"],\"patientIdPrefix\":\" P-1 \","
                + "\"locateStudyId\":\"study\",\"locatePatientId\":\"P-1\","
                + "\"pageNumber\":2,\"pageSize\":25}")
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", "private, no-store"))
        .andExpect(content().contentTypeCompatibleWith("application/json"))
        .andExpect(
            content()
                .json(
                    "{\"totalPatients\":1,\"totalSlides\":1,\"totalViewableSlides\":1,"
                        + "\"locatedIndex\":0,\"pageNumber\":2,\"pageSize\":25,"
                        + "\"patients\":[{\"studyId\":\"study\",\"patientId\":\"P-1\","
                        + "\"slideCount\":1,\"viewableSlideCount\":1}]}"));
  }

  private void allowStudyAccess(boolean allowed) {
    when(cancerStudyPermissionEvaluator.hasPermission(
            any(Authentication.class),
            any(StudyViewFilter.class),
            eq("StudyViewFilter"),
            eq(AccessLevel.READ)))
        .thenReturn(allowed);
  }

  private ResultActions perform(String body) throws Exception {
    return mockMvc.perform(
        post(PATH).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body));
  }
}
