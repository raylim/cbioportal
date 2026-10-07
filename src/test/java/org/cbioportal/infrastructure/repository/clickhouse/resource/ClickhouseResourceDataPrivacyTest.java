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
 * slide table with only their allowlisted metadata; and for every other row public metadata stays
 * searchable while wsi_serving stays private, whatever its TYPE. Uses the wsi_resource_table_study
 * fixture: WSI_SAMPLE rows 900501/900502 (with hidden block_key and reference_sample_id values),
 * WSI_PATIENT row 900507, and EXTERNAL_SLIDES rows 900505/900506 whose serving paths contain
 * "secretpath" and sort in the opposite order ("zzz" for 900505, "aaa" for 900506) to the rows'
 * ids.
 */
@RunWith(SpringRunner.class)
@Import({MyBatisConfig.class, ClickhouseResourceDataRepository.class})
@DataJpaTest
@DirtiesContext
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(initializers = AbstractTestcontainers.Initializer.class)
public class ClickhouseResourceDataPrivacyTest {

  private static final String STUDY = "wsi_resource_table_study";
  private static final String WSI = "WSI_SAMPLE";
  private static final String WSI_PATIENT = "WSI_PATIENT";
  private static final String EXTERNAL = "EXTERNAL_SLIDES";
  private static final String NOTES = "PATHOLOGY_NOTES";
  private static final List<String> WSI_ROW_IDS = List.of("900501", "900502");
  private static final List<String> WSI_SLIDE_KEYS =
      List.of("5d41402abc4b2a76b9719d911017c592", "7d793037a0760186574b0282f2f435e7");

  /**
   * Fixture row id by url. Rows no longer carry their resource_data_id, and each fixture row has a
   * distinct url, so the url names the row.
   */
  private static final Map<String, String> ROW_ID_BY_URL =
      Map.of(
          "https://portal.example.org/wsi/patient/WSI-TABLE-PATIENT?studyId=wsi_resource_table_study&slideKey=5d41402abc4b2a76b9719d911017c592",
          "900501",
          "https://portal.example.org/wsi/patient/WSI-TABLE-PATIENT?studyId=wsi_resource_table_study&slideKey=7d793037a0760186574b0282f2f435e7",
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
    for (String search : Arrays.asList(null, "Gomori", "syn-img-t007")) {
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
    List<ResourceTableRow> rows =
        repository.getResourceTableRows(query(WSI, null, null, null, null));

    assertThat(rows)
        .extracting(ClickhouseResourceDataPrivacyTest::rowId)
        .containsExactly("900501", "900502");
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
        .doesNotContain("syn-img");
  }

  @Test
  public void slideTableSearchMatchesOnlyPublicText() {
    assertThat(ids(query(WSI, "Masson", null, null, null))).containsExactly("900501", "900502");
    assertThat(ids(query(WSI, "40x", null, null, null))).containsExactly("900501");
    for (String term :
        List.of(
            "liver wedge",
            "hiddenblock",
            "WSI-HIDDEN-REF",
            "wsipath",
            "syn-img-t001",
            "block_key")) {
      ResourceTableQuery query = query(WSI, term, null, null, null);
      assertThat(repository.getResourceTableRows(query)).as(term).isEmpty();
      assertThat(repository.getResourceTableCounts(query).rowCount()).as(term).isZero();
    }
  }

  @Test
  public void slideTableIgnoresFiltersAndSortsOnHiddenKeys() {
    for (ResourceColumnFilter filter :
        List.of(
            new ResourceColumnFilter("metadata:block_key", "in", List.of("block:hiddenblockone")),
            new ResourceColumnFilter("metadata:reference_sample_id", "equals", List.of("x")),
            new ResourceColumnFilter("metadata:slide_key", "in", WSI_SLIDE_KEYS.subList(0, 1)),
            new ResourceColumnFilter("metadata:wsi_serving", "contains", List.of("wsipath-1")),
            new ResourceColumnFilter("metadata:part_description", "in", List.of("liver margin")))) {
      ResourceTableQuery filtered = query(WSI, null, null, null, List.of(filter));
      assertThat(ids(filtered)).as(filter.toString()).containsExactly("900501", "900502");
      assertThat(repository.getResourceTableCounts(filtered).rowCount())
          .as(filter.toString())
          .isEqualTo(2);
    }
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
    // block_key and reference_sample_id order the rows 900502-first when descending; the guard
    // orders by row id instead.
    for (String sortBy :
        List.of("metadata:block_key", "metadata:reference_sample_id", "metadata:wsi_serving")) {
      assertThat(ids(query(WSI, null, sortBy, "ASC", null)))
          .as(sortBy)
          .containsExactly("900501", "900502");
      assertThat(ids(query(WSI, null, sortBy, "DESC", null)))
          .as(sortBy)
          .containsExactly("900502", "900501");
    }
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
  public void slideTableColumnsAreTheContractAndHiddenKeysAreNeverFaceted() {
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
  }

  @Test
  public void metadataEndpointDescribesTheSlideTableWithoutHiddenValues() {
    GetResourceTableMetadataUseCase useCase = new GetResourceTableMetadataUseCase(repository);

    ResourceTableMetadataResult result = useCase.execute(query(WSI, null, null, null, null));

    assertThat(result.totalRowCount()).isEqualTo(2);
    assertThat(result.filteredSampleCount()).isEqualTo(2);
    assertThat(result.toString())
        .doesNotContain("hiddenblock")
        .doesNotContain("WSI-HIDDEN-REF")
        .doesNotContain("wsipath")
        .doesNotContain("metadata:slide_key")
        .doesNotContain("metadata:block_key");
    ResourceTableMetadataResult external = useCase.execute(query(EXTERNAL, null, null, null, null));
    assertThat(external.totalRowCount()).isEqualTo(2);
    assertThat(external.columns())
        .extracting(ResourceColumnInfo::id)
        .doesNotContain("metadata:wsi_serving");
    assertThat(external.toString()).doesNotContain("secretpath");
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
      assertThat(rows.toString()).doesNotContain(WSI_SLIDE_KEYS.get(0)).doesNotContain("Masson");
      assertThat(repository.getResourceTableMetadata(query).facets().toString())
          .doesNotContain("Masson")
          .doesNotContain("liver");
    }
  }

  @Test
  public void slideTableDerivedRowsHoldOnlyAllowlistedMetadata() {
    List<String> documents =
        jdbcTemplate.queryForList(
            "SELECT metadata FROM wsi_slide_table_derived ORDER BY resource_data_id", String.class);

    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count() FROM wsi_slide_table_derived WHERE cancer_study_id = 9005",
                Long.class))
        .isEqualTo(2);
    assertThat(documents)
        .allSatisfy(
            document -> {
              assertThat(document)
                  .doesNotContain("wsi_serving")
                  .doesNotContain("hiddenblock")
                  .doesNotContain("WSI-HIDDEN-REF")
                  .doesNotContain("slide_key");
              assertThat(OBJECT_MAPPER.readTree(document).fieldNames())
                  .toIterable()
                  .isSubsetOf(WsiDeidentification.STUDY_TABLE_METADATA_KEYS);
            });
    assertThat(
            jdbcTemplate.queryForList(
                "SELECT DISTINCT resource_id FROM wsi_slide_table_derived", String.class))
        .containsExactly(WSI);
  }

  /** The SQL that builds wsi_slide_table_derived must allowlist exactly the Java contract. */
  @Test
  public void derivedTableSqlAllowlistMatchesTheContract() throws Exception {
    for (String resource :
        List.of(
            "/db-scripts/clickhouse/populate_derived_tables.sql",
            "/db-scripts/clickhouse/migrate/migrate_schema.sql",
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

  @Test
  public void searchDoesNotMatchServingPaths() {
    for (String term : List.of("secretpath", "private-bucket", "zzz-secretpath-1.svs", "s3://")) {
      ResourceTableQuery query = query(EXTERNAL, term, null, null, null);
      assertThat(repository.getResourceTableRows(query)).as(term).isEmpty();
      assertThat(repository.getResourceTableCounts(query).rowCount()).as(term).isZero();
    }
  }

  @Test
  public void searchMatchesMetadataOfRowsWithNullType() {
    ResourceTableQuery query = query(NOTES, "tumor board", null, null, null);

    List<ResourceTableRow> rows = repository.getResourceTableRows(query);

    assertThat(ids(query)).containsExactly("900503");
    assertThat(rows.get(0).type()).isNull();
    assertThat(repository.getResourceTableCounts(query).rowCount()).isEqualTo(1);
    assertThat(repository.getResourceTableRows(query(NOTES, "secretpath", null, null, null)))
        .isEmpty();
  }

  // ---- Filters, sorting, facets and discovery ----

  @Test
  public void filterOnServingMetadataDoesNotChangeRowsOrCounts() {
    ResourceTableQuery unfiltered = query(EXTERNAL, null, null, null, null);
    List<String> expectedIds = ids(unfiltered);
    long expectedCount = repository.getResourceTableCounts(unfiltered).rowCount();
    assertThat(expectedIds).hasSize(2);

    for (ResourceColumnFilter filter :
        List.of(
            new ResourceColumnFilter("metadata:wsi_serving", "contains", List.of("secretpath")),
            new ResourceColumnFilter("metadata:wsi_serving", "contains", List.of("no-such-path")),
            new ResourceColumnFilter("metadata:wsi_serving", "in", List.of()),
            new ResourceColumnFilter("metadata:wsi_serving", "notEquals", List.of("x")))) {
      ResourceTableQuery filtered = query(EXTERNAL, null, null, null, List.of(filter));
      assertThat(ids(filtered))
          .as(filter.toString())
          .containsExactlyInAnyOrderElementsOf(expectedIds);
      assertThat(repository.getResourceTableCounts(filtered).rowCount())
          .as(filter.toString())
          .isEqualTo(expectedCount);
    }
  }

  @Test
  public void sortOnServingMetadataDoesNotFollowServingPaths() {
    // Sorting by the serving path would put 900506 ("aaa...") first ascending and 900505
    // ("zzz...") first descending. The guard orders by row id instead.
    assertThat(ids(query(EXTERNAL, null, "metadata:wsi_serving", "ASC", null)))
        .containsExactly("900505", "900506");
    assertThat(ids(query(EXTERNAL, null, "metadata:wsi_serving", "DESC", null)))
        .containsExactly("900506", "900505");
  }

  @Test
  public void servingMetadataIsNeitherDiscoveredNorFaceted() {
    for (String resourceId : List.of(EXTERNAL, NOTES)) {
      ResourceTableQuery query = query(resourceId, null, null, null, null);

      ResourceTableMetadataView metadata = repository.getResourceTableMetadata(query);

      assertThat(metadata.columns())
          .extracting(ResourceColumnInfo::id)
          .doesNotContain("metadata:wsi_serving");
      assertThat(metadata.facets()).doesNotContainKey("metadata:wsi_serving");
      assertThat(metadata.facetRanges()).doesNotContainKey("metadata:wsi_serving");
      assertThat(
              mapper.getResourceTableMetadataFacets(
                  query, new String[] {"wsi_serving"}, FACET_LIMIT))
          .isEmpty();
    }
    assertThat(
            repository
                .getResourceTableMetadata(query(EXTERNAL, null, null, null, null))
                .facets()
                .get("metadata:stain_name"))
        .hasSize(2);
  }

  // ---- Row responses ----

  @Test
  public void rowsNeverContainServingMetadata() {
    for (String resourceId : List.of(EXTERNAL, NOTES)) {
      List<ResourceTableRow> rows =
          repository.getResourceTableRows(query(resourceId, null, null, null, null));

      assertThat(rows).hasSize(2);
      assertThat(rows)
          .allSatisfy(row -> assertThat(row.metadata()).doesNotContainKey("wsi_serving"));
    }
    ResourceTableRow note =
        repository.getResourceTableRows(query(NOTES, "tumor board", null, null, null)).get(0);
    assertThat(note.metadata()).containsEntry("note", "Reviewed by tumor board");
    ResourceTableRow slide =
        repository.getResourceTableRows(query(EXTERNAL, "Periodic acid", null, null, null)).get(0);
    assertThat(slide.metadata())
        .containsEntry("stain_name", "Periodic acid-Schiff")
        .containsEntry("part_description", "left kidney core biopsy");
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

  private List<String> ids(ResourceTableQuery query) {
    return repository.getResourceTableRows(query).stream()
        .map(ClickhouseResourceDataPrivacyTest::rowId)
        .toList();
  }

  private static String rowId(ResourceTableRow row) {
    return ROW_ID_BY_URL.getOrDefault(row.url(), row.url());
  }
}
