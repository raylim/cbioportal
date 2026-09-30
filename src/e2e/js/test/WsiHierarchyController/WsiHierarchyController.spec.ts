import { expect } from 'chai';
import axios from 'axios';
import crypto from 'crypto';
import fs from 'fs';
import https from 'https';
import path from 'path';

const config = {
  serverUrl: process.env.CBIOPORTAL_URL || 'http://localhost:8080',
  tileServerUrl: process.env.WSI_TILE_SERVER_URL || 'http://localhost:8081',
  frontendUrl: process.env.CBIOPORTAL_FRONTEND_URL || 'http://localhost:3000',
  frontendAllowSelfSignedTls:
    process.env.WSI_FRONTEND_ALLOW_SELF_SIGNED_TLS === 'true',
  authSecret: process.env.WSI_AUTH_SECRET || 'local-development-wsi-secret-change-me-32chars',
  authAudience: process.env.WSI_AUTH_AUDIENCE || 'cbioportal-wsi',
  basicLoginPassword: process.env.WSI_BASIC_LOGIN_PASSWORD || 'wsi-ci-password',
  blockTileSlideId: process.env.WSI_TEST_BLOCK_SLIDE_ID || '',
  partTileSlideId: process.env.WSI_TEST_PART_SLIDE_ID || '',
  unmatchedTileSlideId: process.env.WSI_TEST_UNMATCHED_SLIDE_ID || '',
};

const hasAuthenticatedWsiSetup = Boolean(
  process.env.WSI_AUTH_SECRET && process.env.WSI_LOCAL_AUTH_BYPASS !== 'true'
);
const requireAuthenticatedWsiSetup =
  process.env.WSI_REQUIRE_AUTHENTICATED_SETUP === 'true';
const hasTileSetup = Boolean(process.env.WSI_AUTH_SECRET);
const localAuthBypass = process.env.WSI_LOCAL_AUTH_BYPASS === 'true';
const hasExplicitFrontend = Boolean(process.env.CBIOPORTAL_FRONTEND_URL);
const hasExplicitTileIds = Boolean(
  process.env.WSI_TEST_BLOCK_SLIDE_ID &&
    process.env.WSI_TEST_PART_SLIDE_ID &&
    process.env.WSI_TEST_UNMATCHED_SLIDE_ID
);

if (requireAuthenticatedWsiSetup && !hasAuthenticatedWsiSetup) {
  throw new Error(
    'Authenticated WSI validation was requested, but WSI_LOCAL_AUTH_BYPASS is enabled or WSI_AUTH_SECRET is missing'
  );
}

type Slide = {
  imageId: string;
  // Resource-data identity of the slide: WSI_SAMPLE for sample-matched
  // (PART/BLOCK) slides, WSI_PATIENT for unmatched ones. resourceDataId is the
  // importer-allocated resource_data.RESOURCE_DATA_ID and is never hard-coded.
  // Slide access is requested by imageId, not by these ids.
  resourceId: 'WSI_SAMPLE' | 'WSI_PATIENT';
  resourceDataId?: string;
  sampleId: string | null;
  matchLevel: 'PART' | 'BLOCK' | 'UNMATCHED';
  canServeTiles: boolean;
  procedureDateDays: number | null;
  timepointSource: string | null;
};
type Block = { slides: Slide[] };
type Part = { blocks: Block[] };
type Sample = { sampleId: string | null; parts: Part[] };
type PatientHierarchy = {
  referenceSampleId: string | null;
  sampleGroups: Sample[];
};
type SlideMetadata = {
  dimensions: { width: number; height: number };
  levels: number;
  level_dimensions: Array<{ width: number; height: number }>;
  tile_size?: number;
  objective_power?: number;
  vendor?: string;
};
type SlideAccess = { tileMetadata: SlideMetadata };
type HierarchyFixture = {
  study_id: string;
  patient_id: string;
  hierarchy: PatientHierarchy;
};
type ResourceTableRow = {
  resourceId: string;
  resourceDataId: string;
  patientId: string | null;
  sampleId: string | null;
  url: string;
  displayName: string | null;
  type: string | null;
  metadata: Record<string, unknown> | null;
};
type ResourceTableResult = {
  columns: Array<{ id: string; label: string; source: string }>;
  rows: ResourceTableRow[];
  totalRowCount: number;
};

// A known patient in the public fixture study that has no WSI resource rows.
const NO_SLIDES_PATIENT_ID = 'WSI-CI-NO-SLIDES-PATIENT';
// A WHOLE_SLIDE_IMAGE row with a complete wsi_serving object but outside the
// WSI_SAMPLE/WSI_PATIENT resources. Only wsi_hierarchy_ci_seed.sql creates it
// (the converter cannot emit it), so against an imported stack this case is
// indistinguishable from an unknown image; against the seed it proves the
// access lookup is restricted to the WSI resources.
const NON_WSI_RESOURCE_IMAGE_ID = 'wsi-ci-other-slide';
// Strings that only occur inside the private wsi_serving object of the
// fixture rows (plus the object key itself). None may ever reach the generic
// resource table API.
const PRIVATE_SERVING_MARKERS = [
  'wsi_serving',
  's3://',
  'file:///app/testdata',
  'CMU-1-Small-Region',
  'tile_metadata_json',
  'source_url',
  'thumbnail_url',
];

const currentDir = path.resolve(process.cwd(), 'test/WsiHierarchyController');
const fixturePath = path.join(
  currentDir,
  'msk_spectrum_tme_2022.wsi_hierarchy.jsonl'
);
const fixture = JSON.parse(
  fs.readFileSync(fixturePath, 'utf8').trim()
) as HierarchyFixture;

function collectSlideIds(hierarchy: PatientHierarchy): string[] {
  return hierarchy.sampleGroups.flatMap(sample =>
    sample.parts.flatMap(part =>
      part.blocks.flatMap(block => block.slides.map(slide => slide.imageId))
    )
  );
}

function findSlide(
  hierarchy: PatientHierarchy,
  matchLevel: Slide['matchLevel']
): Slide {
  const slide = hierarchy.sampleGroups
    .flatMap(sample => sample.parts)
    .flatMap(part => part.blocks)
    .flatMap(block => block.slides)
    .find(candidate => candidate.matchLevel === matchLevel);
  expect(slide, `missing ${matchLevel} slide`).to.not.equal(undefined);
  return slide!;
}

function allSlides(hierarchy: PatientHierarchy): Slide[] {
  return hierarchy.sampleGroups
    .flatMap(sample => sample.parts)
    .flatMap(part => part.blocks)
    .flatMap(block => block.slides);
}

/**
 * resourceDataId is allocated by the importer, so the committed fixture omits
 * it. Assert every live slide carries a positive integer id and strip it so the
 * rest of the response can be compared with the fixture exactly.
 */
function withoutResourceDataIds(hierarchy: PatientHierarchy): PatientHierarchy {
  const copy = JSON.parse(JSON.stringify(hierarchy)) as PatientHierarchy;
  allSlides(copy).forEach(slide => {
    expect(slide.resourceDataId, `resourceDataId for ${slide.imageId}`)
      .to.be.a('string')
      .and.match(/^[1-9][0-9]*$/);
    delete slide.resourceDataId;
  });
  return copy;
}

function accessUrl(studyId: string, patientId: string, imageId: string): string {
  // The image ID is a query parameter because image IDs may contain a slash.
  return `${config.serverUrl}/api/wsi/v2/resources/${encodeURIComponent(studyId)}/${encodeURIComponent(patientId)}/access?imageId=${encodeURIComponent(imageId)}`;
}

function slideAccessUrl(slide: Slide): string {
  return accessUrl(fixture.study_id, fixture.patient_id, slide.imageId);
}

function assertNoPrivateServingData(label: string, payload: unknown) {
  const serialized = JSON.stringify(payload);
  PRIVATE_SERVING_MARKERS.forEach(marker => {
    expect(serialized, `${label} leaks ${marker}`).to.not.contain(marker);
  });
}

function base64url(value: object): string {
  return Buffer.from(JSON.stringify(value)).toString('base64url');
}

function makeToken(
  studyId: string,
  imageId: string,
  source = 'file:///app/testdata/CMU-1-Small-Region.svs',
  thumbnail = 'file:///app/testdata/3020691.jpg',
  secret = config.authSecret,
  overrides: Record<string, unknown> = {}
): string {
  const now = Math.floor(Date.now() / 1000);
  const header = base64url({ alg: 'HS256', typ: 'JWT' });
  const payload = base64url({
    sub: 'wsi-ci-user',
    aud: config.authAudience,
    scope: 'wsi:read',
    study_id: studyId,
    image_id: imageId,
    wsi_auth_version: 2,
    tile_source_sha256: crypto.createHash('sha256').update(source).digest('hex'),
    thumbnail_source_sha256: crypto.createHash('sha256').update(thumbnail).digest('hex'),
    thumbnail_width: 256,
    thumbnail_height: 232,
    iat: now,
    exp: now + 300,
    ...overrides,
  });
  const signature = crypto
    .createHmac('sha256', secret)
    .update(`${header}.${payload}`)
    .digest('base64url');
  return `${header}.${payload}.${signature}`;
}

function bearer(token: string) {
  return { headers: { Authorization: `Bearer ${token}` } };
}

function sourceRequest(token: string, source: string) {
  return {
    headers: { Authorization: `Bearer ${token}`, 'X-WSI-Source': source },
  };
}

function cookieHeader(response: any): string {
  return (response.headers['set-cookie'] || [])
    .map((cookie: string) => cookie.split(';', 1)[0])
    .join('; ');
}

async function login(): Promise<string> {
  const response = await axios.post(
    `${config.serverUrl}/j_spring_security_check`,
    `j_username=wsi-ci-user&j_password=${encodeURIComponent(config.basicLoginPassword)}&user_id=wsi-ci-user`,
    {
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      maxRedirects: 0,
      validateStatus: status => status === 302,
    }
  );
  expect(cookieHeader(response)).to.not.equal('');
  return cookieHeader(response);
}

function sessionRequest(cookie: string) {
  return { headers: { Cookie: cookie } };
}

async function authenticatedRequestOptions() {
  return localAuthBypass ? undefined : sessionRequest(await login());
}

async function statusOf(request: Promise<any>): Promise<number> {
  try {
    return (await request).status;
  } catch (error: any) {
    expect(error.response, 'request did not return an HTTP response').to.not.equal(undefined);
    return error.response.status;
  }
}

describe('Authenticated WsiHierarchyController (resource-data) and tile contract', () => {
  const hierarchyUrl = `${config.serverUrl}/api/wsi/v2/hierarchy/${fixture.study_id}/${fixture.patient_id}`;
  const frontendHierarchyUrl = `${config.frontendUrl}/api/wsi/v2/hierarchy/${fixture.study_id}/${fixture.patient_id}`;
  const blockSlide = findSlide(fixture.hierarchy, 'BLOCK');
  const partSlide = findSlide(fixture.hierarchy, 'PART');
  const unmatchedSlide = findSlide(fixture.hierarchy, 'UNMATCHED');
  const blockTileSlideId = config.blockTileSlideId || blockSlide.imageId;
  const partTileSlideId = config.partTileSlideId || partSlide.imageId;
  const unmatchedTileSlideId = config.unmatchedTileSlideId || unmatchedSlide.imageId;

  // Access is requested with the imageIds the live hierarchy lists; fetch it
  // with the same credentials each test uses.
  async function liveHierarchy(requestOptions: any): Promise<PatientHierarchy> {
    const response = await axios.get<PatientHierarchy>(hierarchyUrl, requestOptions);
    expect(response.status).to.equal(200);
    return response.data;
  }

  function liveSlide(hierarchy: PatientHierarchy, imageId: string): Slide {
    const slide = allSlides(hierarchy).find(candidate => candidate.imageId === imageId);
    expect(slide, `live hierarchy is missing slide ${imageId}`).to.not.equal(undefined);
    return slide!;
  }

  it('requires login before returning the hierarchy or issuing a slide capability', async function () {
    if (!hasAuthenticatedWsiSetup) this.skip();
    expect(await statusOf(axios.get(hierarchyUrl))).to.equal(401);
    // The anonymous check precedes the row lookup, so a real slide is refused
    // with 401 before anything about it is revealed.
    expect(
      await statusOf(axios.get(accessUrl(fixture.study_id, fixture.patient_id, blockSlide.imageId)))
    ).to.equal(401);
  });

  it('issues a source-bound slide capability only after login and permission checks', async function () {
    if (!hasTileSetup) this.skip();
    const requestOptions = await authenticatedRequestOptions();
    const live = await liveHierarchy(requestOptions);
    const liveBlock = liveSlide(live, blockSlide.imageId);
    expect(liveBlock.resourceId).to.equal('WSI_SAMPLE');
    const authorized = await axios.get(slideAccessUrl(liveBlock), requestOptions);
    expect(authorized.status).to.equal(200);
    expect(authorized.headers['cache-control']).to.contain('no-store');
    expect(authorized.data.imageId).to.equal(blockSlide.imageId);
    expect(authorized.data.accessToken).to.be.a('string').and.not.empty;
    expect(authorized.data.sourceUrl).to.equal(
      'file:///app/testdata/CMU-1-Small-Region.svs'
    );

    if (!localAuthBypass) {
      // Study-level permission is evaluated before the row lookup, so a real
      // servable slide of the denied control study is refused with 403.
      expect(
        await statusOf(
          axios.get(accessUrl('wsi_ci_study_b', 'WSI-CI-B-PATIENT', '4020726'), requestOptions)
        )
      ).to.equal(403);
    }
    // An image of another patient (same study, and another study's patient),
    // an unknown image, and a WHOLE_SLIDE_IMAGE row outside the WSI resources
    // all resolve to no servable slide.
    for (const missing of [
      accessUrl(fixture.study_id, NO_SLIDES_PATIENT_ID, liveBlock.imageId),
      accessUrl(fixture.study_id, fixture.patient_id, '4020726'),
      accessUrl(fixture.study_id, fixture.patient_id, 'missing-slide'),
      accessUrl(fixture.study_id, fixture.patient_id, NON_WSI_RESOURCE_IMAGE_ID),
    ]) {
      expect(await statusOf(axios.get(missing, requestOptions)), missing).to.equal(404);
    }
  });

  it('returns the materialized hierarchy only for the authenticated study session', async function () {
    if (!hasTileSetup) this.skip();
    const requestOptions = await authenticatedRequestOptions();
    const response = await axios.get<PatientHierarchy>(hierarchyUrl, requestOptions);

    expect(response.status).to.equal(200);
    expect(response.headers['content-type']).to.contain('application/json');
    expect(response.headers['cache-control']).to.contain('private');
    expect(withoutResourceDataIds(response.data)).to.deep.equal(fixture.hierarchy);
    allSlides(response.data).forEach(slide => {
      expect(slide.resourceId, slide.imageId).to.equal(
        slide.matchLevel === 'UNMATCHED' ? 'WSI_PATIENT' : 'WSI_SAMPLE'
      );
    });
    // The hierarchy is public slide metadata only; serving URLs are issued by
    // the access endpoint alone.
    assertNoPrivateServingData('hierarchy', response.data);
    if (!localAuthBypass) {
      expect(
        await statusOf(
          axios.get(
            `${config.serverUrl}/api/wsi/v2/hierarchy/wsi_ci_study_b/WSI-CI-B-PATIENT`,
            requestOptions
          )
        )
      ).to.equal(403);
    }
  });

  const frontendIt = hasTileSetup && hasExplicitFrontend ? it : it.skip;
  frontendIt('matches the authenticated backend hierarchy through the frontend proxy', async function () {
    const requestOptions = await authenticatedRequestOptions();
    const frontendRequestOptions = {
      ...(requestOptions || {}),
      ...(config.frontendAllowSelfSignedTls && {
        httpsAgent: new https.Agent({ rejectUnauthorized: false }),
      }),
    };
    const [backendResponse, frontendResponse] = await Promise.all([
      axios.get<PatientHierarchy>(hierarchyUrl, requestOptions),
      axios.get<PatientHierarchy>(frontendHierarchyUrl, frontendRequestOptions),
    ]);

    expect(frontendResponse.status).to.equal(200);
    expect(frontendResponse.data).to.deep.equal(backendResponse.data);
  });

  it('covers block, part, and unmatched slide associations in the hierarchy', () => {
    const slideIds = collectSlideIds(fixture.hierarchy);
    const slides = fixture.hierarchy.sampleGroups
      .flatMap(sample => sample.parts)
      .flatMap(part => part.blocks)
      .flatMap(block => block.slides);
    expect(slideIds).to.have.members([
      '3020726',
      '3020691',
      '3020648',
      '3020649',
    ]);
    expect(slides.map(slide => slide.matchLevel)).to.have.members([
      'PART',
      'BLOCK',
      'UNMATCHED',
      'UNMATCHED',
    ]);
    slides.forEach(slide => {
      expect(slideIds).to.include(slide.imageId);
      if (slide.matchLevel === 'UNMATCHED') {
        expect(slide.sampleId).to.equal(null);
        expect(slide.resourceId).to.equal('WSI_PATIENT');
      } else {
        expect(slide.sampleId).to.be.a('string').and.not.empty;
        expect(slide.resourceId).to.equal('WSI_SAMPLE');
      }
    });
  });

  it('sources slide timepoints from the imported WSI resource metadata', async function () {
    if (!hasTileSetup) this.skip();
    const response = await axios.get<PatientHierarchy>(
      hierarchyUrl,
      await authenticatedRequestOptions()
    );
    const block = findSlide(response.data, 'BLOCK');
    const part = findSlide(response.data, 'PART');
    const unmatched = findSlide(response.data, 'UNMATCHED');

    [block, part].forEach(slide => {
      expect(slide.procedureDateDays).to.equal(-17);
      expect(slide.timepointSource).to.equal(
        'Recorded procedure date relative to first tumor sequencing'
      );
    });
    expect(unmatched.procedureDateDays).to.equal(null);
    expect(unmatched.timepointSource).to.equal('MISSING_PROCEDURE_DATE');
  });

  it('returns 404 for an unknown patient or study after authentication', async function () {
    if (!hasTileSetup) this.skip();
    const requestOptions = await authenticatedRequestOptions();
    expect(
      await statusOf(
        axios.get(
          `${config.serverUrl}/api/wsi/v2/hierarchy/${fixture.study_id}/missing-patient`,
          requestOptions
        )
      )
    ).to.equal(404);
    const unknownStudyStatus = await statusOf(
      axios.get(
        `${config.serverUrl}/api/wsi/v2/hierarchy/missing_wsi_study/${fixture.patient_id}`,
        requestOptions
      )
    );
    if (localAuthBypass) {
      expect(unknownStudyStatus).to.equal(404);
    } else {
      // With authentication on, the study permission evaluator runs first and
      // cannot grant READ on a study it does not know, so a 403 is equally a
      // refusal that reveals nothing; any 2xx is a failure.
      expect([403, 404]).to.include(unknownStudyStatus);
    }
  });

  it('returns an empty 200 hierarchy for a known patient without slides', async function () {
    if (!hasTileSetup) this.skip();
    const response = await axios.get<PatientHierarchy>(
      `${config.serverUrl}/api/wsi/v2/hierarchy/${fixture.study_id}/${NO_SLIDES_PATIENT_ID}`,
      await authenticatedRequestOptions()
    );
    expect(response.status).to.equal(200);
    expect(response.headers['cache-control']).to.contain('private');
    expect(response.data).to.deep.equal({ referenceSampleId: null, sampleGroups: [] });
  });

  it('never exposes wsi_serving through the generic resource table API', async function () {
    if (!hasTileSetup) this.skip();
    const requestOptions = await authenticatedRequestOptions();
    const tabs = await axios.post(
      `${config.serverUrl}/api/resource-table/tabs/fetch`,
      { studyIds: [fixture.study_id] },
      requestOptions
    );
    expect(tabs.status).to.equal(200);
    expect(tabs.data.map((tab: { resourceId: string }) => tab.resourceId)).to.include.members([
      'WSI_SAMPLE',
      'WSI_PATIENT',
    ]);
    assertNoPrivateServingData('resource-table tabs', tabs.data);

    const expectedImageIds: Record<string, string[]> = {
      WSI_SAMPLE: ['3020726', '3020691'],
      WSI_PATIENT: ['3020648', '3020649'],
    };
    for (const resourceId of Object.keys(expectedImageIds)) {
      const query = await axios.post<ResourceTableResult>(
        `${config.serverUrl}/api/resource-table/query/fetch`,
        { studyIds: [fixture.study_id], resourceId, pageNumber: 0, pageSize: 50 },
        requestOptions
      );
      expect(query.status).to.equal(200);
      // Non-vacuous: the rows and public metadata are really there.
      expect(query.data.rows.map(row => row.displayName ?? (row.metadata || {})['image_id']))
        .to.have.members(expectedImageIds[resourceId]);
      query.data.rows.forEach(row => {
        expect(row.resourceId).to.equal(resourceId);
        expect(row.type).to.equal('WHOLE_SLIDE_IMAGE');
        expect(row.metadata).to.not.equal(null);
        expect(row.metadata!).to.not.have.property('wsi_serving');
      });
      query.data.columns.forEach(column => {
        expect(column.id).to.not.match(/wsi_serving/);
      });
      assertNoPrivateServingData(`resource-table ${resourceId} query`, query.data);

      // Search must not match text that only exists inside wsi_serving.
      const search = await axios.post<ResourceTableResult>(
        `${config.serverUrl}/api/resource-table/query/fetch`,
        {
          studyIds: [fixture.study_id],
          resourceId,
          search: 'CMU-1-Small-Region',
          pageNumber: 0,
          pageSize: 50,
        },
        requestOptions
      );
      expect(search.status).to.equal(200);
      expect(search.data.rows, `${resourceId} search matched private serving data`).to.deep.equal([]);
    }

    // The seed's non-WSI WHOLE_SLIDE_IMAGE resource also carries wsi_serving;
    // the generic table must strip it there too.
    const tabIds: string[] = tabs.data.map((tab: { resourceId: string }) => tab.resourceId);
    if (tabIds.includes('WSI_CI_OTHER_SLIDES')) {
      const other = await axios.post<ResourceTableResult>(
        `${config.serverUrl}/api/resource-table/query/fetch`,
        { studyIds: [fixture.study_id], resourceId: 'WSI_CI_OTHER_SLIDES', pageNumber: 0, pageSize: 50 },
        requestOptions
      );
      expect(other.data.rows).to.have.length(1);
      assertNoPrivateServingData('resource-table WSI_CI_OTHER_SLIDES query', other.data);
    }
  });

  const tileIt = hasTileSetup ? it : it.skip;
  tileIt('binds tiles and thumbnails to the exact source in the capability', async function () {
    const source = 'file:///app/testdata/CMU-1-Small-Region.svs';
    const thumbnail = 'file:///app/testdata/3020691.jpg';
    const token = makeToken(fixture.study_id, blockTileSlideId, source, thumbnail);
    const tileUrl = `${config.tileServerUrl}/tiles/zxy/0/0/0`;
    const thumbnailUrl = `${config.tileServerUrl}/thumbnails?width=128&height=96`;
    expect((await axios.get(tileUrl, { ...sourceRequest(token, source), responseType: 'arraybuffer' })).status).to.equal(200);
    expect((await axios.get(thumbnailUrl, { ...sourceRequest(token, thumbnail), responseType: 'arraybuffer' })).status).to.equal(200);
    expect(
      await statusOf(
        axios.get(
          `${config.tileServerUrl}/tiles/zxy/0/0/0`,
          sourceRequest(token, 'file:///app/testdata/other.svs')
        )
      )
    ).to.equal(403);
  });

  tileIt('rejects missing, invalid, expired, over-maximum, and wrong-audience tokens', async function () {
    const source = 'file:///app/testdata/CMU-1-Small-Region.svs';
    const pathToTest = `${config.tileServerUrl}/tiles/zxy/0/0/0`;
    const sourceHeaders = { 'X-WSI-Source': source };
    expect(await statusOf(axios.get(pathToTest, { headers: sourceHeaders }))).to.equal(401);
    expect(await statusOf(axios.get(pathToTest, sourceRequest('not-a-jwt', source)))).to.equal(401);
    expect(await statusOf(axios.get(pathToTest, sourceRequest(makeToken(fixture.study_id, blockTileSlideId, source, 'file:///app/testdata/3020691.jpg', 'x'.repeat(32)), source)))).to.equal(401);
    expect(
      await statusOf(
        axios.get(pathToTest, sourceRequest(makeToken(fixture.study_id, blockTileSlideId, source, 'file:///app/testdata/3020691.jpg', config.authSecret, { aud: 'wrong-audience' }), source))
      )
    ).to.equal(401);
    const now = Math.floor(Date.now() / 1000);
    expect(
      await statusOf(
        axios.get(
          pathToTest,
          sourceRequest(makeToken(fixture.study_id, blockTileSlideId, source, 'file:///app/testdata/3020691.jpg', config.authSecret, { iat: now - 300, exp: now - 1 }), source)
        )
      )
    ).to.equal(401);
    expect(
      await statusOf(
        axios.get(
          pathToTest,
          sourceRequest(makeToken(fixture.study_id, blockTileSlideId, source, 'file:///app/testdata/3020691.jpg', config.authSecret, { exp: now + 901 }), source)
        )
      )
    ).to.equal(401);
  });

  tileIt('accepts a replacement token after refresh', async function () {
    const requestOptions = await authenticatedRequestOptions();
    const liveBlock = liveSlide(await liveHierarchy(requestOptions), blockTileSlideId);
    const first = await axios.get(slideAccessUrl(liveBlock), requestOptions);
    expect(first.data.accessToken).to.be.a('string').and.not.empty;
    const second = await axios.get(slideAccessUrl(liveBlock), requestOptions);
    // Issuance can occur within the same second, so a deterministic JWT may be
    // byte-identical.  The refresh contract is that the replacement is valid
    // and source-bound, not that its serialized value must differ.
    expect(second.data.accessToken).to.be.a('string').and.not.empty;
    const source = 'file:///app/testdata/CMU-1-Small-Region.svs';
    expect(
      (await axios.get(`${config.tileServerUrl}/tiles/zxy/0/0/0`, sourceRequest(second.data.accessToken, source))).status
    ).to.equal(200);
  });

  const hierarchyTileConsistencyIt =
    hasTileSetup && !hasExplicitTileIds ? it : it.skip;
  hierarchyTileConsistencyIt('reports tile-serving capability consistently with live behavior', async function () {
    const slides = fixture.hierarchy.sampleGroups
      .flatMap(sample => sample.parts)
      .flatMap(part => part.blocks)
      .flatMap(block => block.slides);
    const servableSlides = slides.filter(slide => slide.canServeTiles === true);
    const nonServableSlides = slides.filter(slide => slide.canServeTiles === false);
    expect(servableSlides.map(slide => slide.imageId)).to.have.members([
      blockSlide.imageId,
      partSlide.imageId,
      '3020649',
    ]);
    expect(nonServableSlides.map(slide => slide.imageId)).to.deep.equal([
      unmatchedSlide.imageId,
    ]);
    const requestOptions = await authenticatedRequestOptions();
    const live = await liveHierarchy(requestOptions);
    const metadataResponses = await Promise.all(
      servableSlides.map(slide =>
        axios.get<SlideAccess>(slideAccessUrl(liveSlide(live, slide.imageId)), requestOptions)
      )
    );
    metadataResponses.forEach(response => {
      expect(response.status).to.equal(200);
      expect(response.data.tileMetadata.levels).to.be.greaterThan(0);
    });
    // The non-servable unmatched slide is a WSI_PATIENT row with an empty
    // wsi_serving object: it is listed in the hierarchy but never issued.
    const nonServable = liveSlide(live, unmatchedTileSlideId);
    expect(nonServable.resourceId).to.equal('WSI_PATIENT');
    expect(await statusOf(axios.get(slideAccessUrl(nonServable), requestOptions))).to.equal(404);
  });
});
