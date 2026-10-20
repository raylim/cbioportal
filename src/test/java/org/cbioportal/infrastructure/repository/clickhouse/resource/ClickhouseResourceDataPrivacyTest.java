package org.cbioportal.infrastructure.repository.clickhouse.resource;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.cbioportal.domain.resource.ResourceColumnFilter;
import org.cbioportal.domain.resource.ResourceColumnInfo;
import org.cbioportal.domain.resource.ResourceFacetOption;
import org.cbioportal.domain.resource.ResourceMetadataKeyStats;
import org.cbioportal.domain.resource.ResourceTableCounts;
import org.cbioportal.domain.resource.ResourceTableMetadataResult;
import org.cbioportal.domain.resource.ResourceTableMetadataView;
import org.cbioportal.domain.resource.ResourceTableQuery;
import org.cbioportal.domain.resource.ResourceTableRow;
import org.cbioportal.domain.resource.ResourceTableTab;
import org.cbioportal.domain.resource.ResourceTabsRequest;
import org.cbioportal.domain.resource.usecase.GetResourceTableMetadataUseCase;
import org.cbioportal.domain.wsi.WsiDeidentification;
import org.cbioportal.infrastructure.repository.clickhouse.AbstractTestcontainers;
import org.cbioportal.infrastructure.repository.clickhouse.config.MyBatisConfig;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringRunner;

/**
 * WSI_PATIENT rows never reach the generic resource table; WSI_SAMPLE rows are served as the study
 * slide table with only their allowlisted metadata; and every other resource's public metadata
 * stays searchable. Uses the wsi_resource_table_study fixture: WSI_SAMPLE rows 900501/900502 (with
 * hidden block_key and reference_sample_id values), WSI_PATIENT row 900507, EXTERNAL_SLIDES rows
 * 900505/900506 and PATHOLOGY_NOTES rows 900503/900504.
 */
@RunWith(SpringRunner.class)
@Import({MyBatisConfig.class, ClickhouseResourceDataRepository.class})
@DataJpaTest
@DirtiesContext
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(initializers = AbstractTestcontainers.Initializer.class)
public class ClickhouseResourceDataPrivacyTest {

  private static final String STUDY = "wsi_resource_table_study";
  private static final String UNMATCHED_STUDY = "wsi_unmatched_study";
  private static final String WSI = "WSI_SAMPLE";
  private static final String WSI_PATIENT = "WSI_PATIENT";
  private static final String EXTERNAL = "EXTERNAL_SLIDES";
  private static final String NOTES = "PATHOLOGY_NOTES";
  private static final List<String> WSI_ROW_IDS = List.of("900501", "900502");
  private static final String WSI_SLIDE_KEY = "5d41402abc4b";

  /**
   * Fixture row id by url. Rows no longer carry their internal_id, and each fixture row has a
   * distinct url, so the url names the row.
   */
  private static final Map<String, String> ROW_ID_BY_URL =
      Map.of(
          "https://portal.example.org/wsi/patient/WSI-TABLE-PATIENT?studyId=wsi_resource_table_study&slideKey=5d41402abc4b",
          "900501",
          "https://portal.example.org/wsi/patient/WSI-TABLE-PATIENT?studyId=wsi_resource_table_study&slideKey=7d793037a076",
          "900502",
          "https://example.com/notes/1.pdf",
          "900503",
          "https://example.com/notes/2.pdf",
          "900504",
          "https://slides.example.org/viewer/ext-1",
          "900505",
          "https://slides.example.org/viewer/ext-2",
          "900506");

  private static final int FACET_LIMIT = 501;
  private static final int KEY_SAMPLE_ROWS = 100_000;
  private static final long KEY_MAX_MEMORY_BYTES = 2L * 1024 * 1024 * 1024;

  @Autowired private ClickhouseResourceDataRepository repository;
  @Autowired private ClickhouseResourceDataMapper mapper;
  @Autowired private JdbcTemplate jdbcTemplate;

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  // ---- WSI_PATIENT is invisible to the generic resource table ----

  @Test
  public void wsiPatientIsNotATabAndWsiSampleIsTheSlideTable() {
    List<ResourceTableTab> tabs =
        repository.getResourceTableTabs(new ResourceTabsRequest(List.of(STUDY), null, null));

    assertThat(tabs)
        .extracting(ResourceTableTab::resourceId)
        .containsExactlyInAnyOrder(WSI, EXTERNAL, NOTES)
        .doesNotContain(WSI_PATIENT);
    assertThat(mapper.getResourceTableTabs(new ResourceTabsRequest(List.of(STUDY), null, null)))
        .extracting(ResourceTableTab::resourceId)
        .doesNotContain(WSI_PATIENT);
  }

  @Test
  public void wsiPatientRowsAreNeverReturnedCountedOrDescribed() {
    for (String search : Arrays.asList(null, "Gomori")) {
      ResourceTableQuery query = query(WSI_PATIENT, search, null, null, null);

      assertThat(repository.getResourceTableRows(query)).isEmpty();
      assertThat(mapper.getResourceTableRows(query)).isEmpty();
      assertThat(repository.getResourceTableCounts(query).rowCount()).isZero();
      assertThat(mapper.getResourceDefinitionCustomMetadata(query)).isEmpty();
      ResourceTableMetadataView metadata = repository.getResourceTableMetadata(query);
      assertThat(metadata.columns()).isEmpty();
      assertThat(metadata.facets()).isEmpty();
      assertThat(metadata.facetRanges()).isEmpty();
    }
    for (String resourceId : List.of(WSI, EXTERNAL, NOTES)) {
      assertThat(ids(query(resourceId, "Gomori", null, null, null))).as(resourceId).isEmpty();
    }
  }

  // ---- WSI_SAMPLE is served through its allowlisted contract only ----

  @Test
  public void slideTableRowsCarryOnlyAllowlistedMetadata() {
    List<String> documents =
        jdbcTemplate.queryForList("SELECT metadata FROM wsi_slide_table_derived", String.class);
    assertThat(documents)
        .isNotEmpty()
        .allSatisfy(
            document ->
                assertThat(OBJECT_MAPPER.readTree(document).fieldNames())
                    .toIterable()
                    .isSubsetOf(WsiDeidentification.STUDY_TABLE_METADATA_KEYS));
    assertThat(
            jdbcTemplate.queryForList(
                "SELECT DISTINCT resource_id FROM wsi_slide_table_derived", String.class))
        .containsExactly(WSI);

    List<ResourceTableRow> rows =
        repository.getResourceTableRows(query(WSI, null, null, null, null));

    assertThat(rows)
        .extracting(ClickhouseResourceDataPrivacyTest::rowId)
        .containsExactly("900501", "900502");
    // The caption repeats the stain, part and block columns, so the table leaves it empty.
    assertThat(rows).extracting(ResourceTableRow::displayName).containsOnlyNulls();
    assertThat(rows)
        .allSatisfy(
            row ->
                assertThat(row.metadata().keySet())
                    .isSubsetOf(WsiDeidentification.STUDY_TABLE_METADATA_KEYS)
                    .contains("stain_name", "part_number", "block_number", "magnification")
                    .doesNotContain("part_description"));
    assertThat(rows.toString())
        .doesNotContain("hiddenblock")
        .doesNotContain("WSI-HIDDEN-REF")
        .doesNotContain("wsipath")
        .doesNotContain("syn-img")
        .doesNotContain("sealed_source");
  }

  @Test
  public void slideTableSearchFiltersAndSortsSeeOnlyPublicMetadata() {
    // wsi_slide_table_derived holds no hidden key or value, so a search, filter or sort on one
    // matches nothing.
    assertThat(ids(query(WSI, "Masson", null, null, null))).containsExactly("900501", "900502");
    assertThat(ids(query(WSI, "40x", null, null, null))).containsExactly("900501");
    assertThat(
            ids(
                query(
                    WSI,
                    null,
                    null,
                    null,
                    List.of(
                        new ResourceColumnFilter("metadata:magnification", "in", List.of("20x"))))))
        .containsExactly("900502");
    for (String term :
        List.of(
            "liver wedge",
            "hiddenblock",
            "WSI-HIDDEN-REF",
            "wsipath",
            "sealed_source",
            "block_key")) {
      ResourceTableQuery query = query(WSI, term, null, null, null);
      assertThat(repository.getResourceTableRows(query)).as(term).isEmpty();
      assertThat(repository.getResourceTableCounts(query).rowCount()).as(term).isZero();
    }
    for (ResourceColumnFilter filter :
        List.of(
            new ResourceColumnFilter("metadata:block_key", "in", List.of("block:hiddenblockone")),
            new ResourceColumnFilter("metadata:reference_sample_id", "equals", List.of("x")),
            new ResourceColumnFilter("metadata:slide_key", "in", List.of(WSI_SLIDE_KEY)),
            new ResourceColumnFilter("metadata:part_description", "in", List.of("liver margin")))) {
      ResourceTableQuery filtered = query(WSI, null, null, null, List.of(filter));
      assertThat(ids(filtered)).as(filter.toString()).isEmpty();
      assertThat(repository.getResourceTableCounts(filtered).rowCount())
          .as(filter.toString())
          .isZero();
    }
    // block_key and reference_sample_id would order the rows 900502-first when descending; with no
    // value to sort on, both directions fall back to row order.
    for (String sortBy : List.of("metadata:block_key", "metadata:reference_sample_id")) {
      for (String direction : List.of("ASC", "DESC")) {
        assertThat(ids(query(WSI, null, sortBy, direction, null)))
            .as(sortBy + " " + direction)
            .containsExactly("900501", "900502");
      }
    }
  }

  @Test
  public void slideTableListsOnlyViewableSlides() {
    // 900508 is a WSI_SAMPLE slide the viewer cannot open (can_serve_tiles false).
    ResourceTableQuery query = query(WSI, null, null, null, null);
    assertThat(ids(query)).containsExactly("900501", "900502");
    assertThat(ids(query(WSI, "Unviewable", null, null, null))).isEmpty();
    ResourceTableCounts counts = repository.getResourceTableCounts(query);
    assertThat(counts.rowCount()).isEqualTo(2);
    assertThat(counts.sampleCount()).isEqualTo(2);
    assertThat(repository.getResourceTableMetadata(query).facets().get("metadata:stain_name"))
        .extracting(ResourceFacetOption::value)
        .containsExactly("Masson trichrome");
    assertThat(repository.getResourceTableTabs(new ResourceTabsRequest(List.of(STUDY), null, null)))
        .filteredOn(tab -> tab.resourceId().equals(WSI))
        .singleElement()
        .satisfies(
            tab -> {
              assertThat(tab.totalCount()).isEqualTo(2);
              assertThat(tab.sampleCount()).isEqualTo(2);
            });
  }

  @Test
  public void slideTableListsUnmatchedViewableSlidesWithoutASample() {
    // wsi_unmatched_study: 901001 is matched to a sample, 901002 is a viewable WSI_PATIENT slide
    // with no sample, and 901003 is a WSI_PATIENT slide the viewer cannot open.
    ResourceTableQuery query = queryIn(UNMATCHED_STUDY, WSI, null);
    List<ResourceTableRow> rows = repository.getResourceTableRows(query);

    assertThat(rows)
        .extracting(ResourceTableRow::sampleId)
        .containsExactly("WSI-UNMATCHED-SAMPLE-1", null);
    ResourceTableRow unmatched = rows.get(1);
    assertThat(unmatched.patientId()).isEqualTo("WSI-UNMATCHED-PATIENT");
    assertThat(unmatched.url()).endsWith("slideKey=eccbc87e4b5c");
    assertThat(unmatched.metadata())
        .containsEntry("stain_name", "Toluidine blue")
        .containsEntry("match_level", "UNMATCHED");
    assertThat(rows.toString()).doesNotContain("Unviewable");

    ResourceTableCounts counts = repository.getResourceTableCounts(query);
    assertThat(counts.rowCount()).isEqualTo(2);
    assertThat(counts.patientCount()).isEqualTo(1);
    assertThat(counts.sampleCount()).isEqualTo(1);
    assertThat(
            repository.getResourceTableTabs(
                new ResourceTabsRequest(List.of(UNMATCHED_STUDY), null, null)))
        .extracting(ResourceTableTab::resourceId, ResourceTableTab::totalCount)
        .containsExactly(org.assertj.core.groups.Tuple.tuple(WSI, 2L));
    assertThat(ids(queryIn(UNMATCHED_STUDY, WSI, "Toluidine"))).hasSize(1);
    assertThat(repository.getResourceTableRows(queryIn(UNMATCHED_STUDY, WSI_PATIENT, null)))
        .isEmpty();
  }

  @Test
  public void slideTablePartAndBlockAreNumbers() {
    // Part "10" (900501) and "2" (900502): text order would put 10 first.
    assertThat(ids(query(WSI, null, "metadata:part_number", "ASC", null)))
        .containsExactly("900502", "900501");
    assertThat(ids(query(WSI, null, "metadata:part_number", "DESC", null)))
        .containsExactly("900501", "900502");
    assertThat(
            ids(
                query(
                    WSI,
                    null,
                    null,
                    null,
                    List.of(
                        new ResourceColumnFilter(
                            "metadata:block_number", "between", List.of("2", "5"))))))
        .containsExactly("900502");
  }

  @Test
  public void slideTableMetadataIsTheContractAndNeverExposesHiddenKeys() {
    ResourceTableQuery query = query(WSI, null, null, null, null);

    ResourceTableMetadataView metadata = repository.getResourceTableMetadata(query);

    assertThat(metadata.columns())
        .extracting(ResourceColumnInfo::id)
        .containsExactlyElementsOf(
            WsiDeidentification.STUDY_TABLE_METADATA_KEYS.stream()
                .map(key -> ResourceColumnInfo.METADATA_COLUMN_PREFIX + key)
                .toList());
    assertThat(metadata.facets().get("metadata:stain_name"))
        .extracting(ResourceFacetOption::value)
        .containsExactly("Masson trichrome");
    assertThat(metadata.facets().get("metadata:magnification")).hasSize(2);
    assertThat(metadata.facets().toString())
        .doesNotContain("hiddenblock")
        .doesNotContain("WSI-HIDDEN-REF")
        .doesNotContain("wsipath");
    assertThat(
            mapper.getResourceTableMetadataKeyStats(query, KEY_SAMPLE_ROWS, KEY_MAX_MEMORY_BYTES))
        .extracting(ResourceMetadataKeyStats::key)
        .containsExactlyInAnyOrder("block_number", "magnification", "part_number", "stain_name");
    for (String key : List.of("block_key", "reference_sample_id", "slide_key", "wsi_serving")) {
      assertThat(mapper.getResourceTableMetadataFacets(query, new String[] {key}, FACET_LIMIT))
          .as(key)
          .isEmpty();
      assertThat(mapper.getResourceTableMetadataRanges(query, new String[] {key}))
          .as(key)
          .isEmpty();
    }

    ResourceTableMetadataResult result =
        new GetResourceTableMetadataUseCase(repository).execute(query);
    assertThat(result.totalRowCount()).isEqualTo(2);
    assertThat(result.filteredSampleCount()).isEqualTo(2);
    assertThat(result.toString())
        .doesNotContain("hiddenblock")
        .doesNotContain("WSI-HIDDEN-REF")
        .doesNotContain("wsipath")
        .doesNotContain("metadata:slide_key")
        .doesNotContain("metadata:block_key");
  }

  @Test
  public void otherResourcesNeverSurfaceSlideRowsOrValues() {
    for (String resourceId : List.of(EXTERNAL, NOTES)) {
      ResourceTableQuery query = query(resourceId, null, null, null, null);
      List<ResourceTableRow> rows = repository.getResourceTableRows(query);

      assertThat(rows)
          .extracting(ClickhouseResourceDataPrivacyTest::rowId)
          .doesNotContainAnyElementsOf(WSI_ROW_IDS);
      assertThat(rows).extracting(ResourceTableRow::resourceId).containsOnly(resourceId);
      assertThat(rows.toString()).doesNotContain(WSI_SLIDE_KEY).doesNotContain("Masson");
      assertThat(repository.getResourceTableMetadata(query).facets().toString())
          .doesNotContain("Masson")
          .doesNotContain("liver");
    }
  }

  /** The SQL that builds wsi_slide_table_derived must allowlist exactly the Java contract. */
  @Test
  public void derivedTableSqlAllowlistMatchesTheContract() throws Exception {
    for (String resource :
        List.of(
            "/db-scripts/clickhouse/populate_derived_tables.sql",
            "/clickhouse/populate_derived_tables.sql")) {
      String sql;
      try (var in = getClass().getResourceAsStream(resource)) {
        assertThat(in).as(resource).isNotNull();
        sql = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
      }
      int insert = sql.indexOf("INSERT INTO wsi_slide_table_derived");
      assertThat(insert).as(resource).isNotNegative();
      var matcher =
          java.util.regex.Pattern.compile("has\\(\\[([^\\]]*)\\]").matcher(sql.substring(insert));
      assertThat(matcher.find()).as(resource).isTrue();
      List<String> keys =
          Arrays.stream(matcher.group(1).split(","))
              .map(key -> key.trim().replace("'", ""))
              .toList();
      assertThat(keys)
          .as(resource)
          .containsExactlyElementsOf(WsiDeidentification.STUDY_TABLE_METADATA_KEYS);
    }
  }

  // ---- Search ----

  @Test
  public void searchMatchesPublicSlideStainAndSpecimenText() {
    assertThat(ids(query(EXTERNAL, "Periodic acid", null, null, null))).containsExactly("900505");
    assertThat(ids(query(EXTERNAL, "H&E", null, null, null))).containsExactly("900506");
    assertThat(ids(query(EXTERNAL, "kidney", null, null, null)))
        .containsExactlyInAnyOrder("900505", "900506");
    assertThat(ids(query(EXTERNAL, "right kidney margin", null, null, null)))
        .containsExactly("900506");
  }

  private ResourceTableQuery query(
      String resourceId,
      String search,
      String sortBy,
      String direction,
      List<ResourceColumnFilter> filters) {
    return new ResourceTableQuery(
        List.of(STUDY), resourceId, null, null, search, 0, 10, sortBy, direction, filters);
  }

  private static ResourceTableQuery queryIn(String study, String resourceId, String search) {
    return new ResourceTableQuery(
        List.of(study), resourceId, null, null, search, 0, 10, null, null, null);
  }

  private List<String> ids(ResourceTableQuery query) {
    return repository.getResourceTableRows(query).stream()
        .map(ClickhouseResourceDataPrivacyTest::rowId)
        .toList();
  }

  private static String rowId(ResourceTableRow row) {
    return ROW_ID_BY_URL.getOrDefault(row.url(), row.url());
  }
}
