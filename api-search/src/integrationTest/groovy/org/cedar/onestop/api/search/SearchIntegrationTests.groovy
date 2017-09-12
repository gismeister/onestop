package org.cedar.onestop.api.search

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.apache.http.HttpEntity
import org.apache.http.entity.ContentType
import org.apache.http.nio.entity.NStringEntity
import org.elasticsearch.client.RestClient
import org.elasticsearch.client.Response
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.RequestEntity
import org.springframework.test.context.ActiveProfiles
import org.springframework.web.client.RestTemplate
import spock.lang.Specification
import spock.lang.Unroll

import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT

@Unroll
@ActiveProfiles("integration")
@SpringBootTest(classes = [Application, IntegrationTestConfig], webEnvironment = RANDOM_PORT)
class SearchIntegrationTests extends Specification {

  @Autowired
  RestClient restClient

  @Value('${local.server.port}')
  private String port

  @Value('${server.context-path}')
  private String contextPath

  @Value('${elasticsearch.index.prefix:}${elasticsearch.index.search.name}')
  private String SEARCH_INDEX

  @Value('${elasticsearch.index.search.collectionType}')
  private String COLLECTION_TYPE

  @Value('${elasticsearch.index.search.granuleType}')
  private String GRANULE_TYPE

  private MediaType contentType = MediaType.APPLICATION_JSON_UTF8

  private RestTemplate restTemplate
  private String searchBaseUriString
  private URI searchBaseUri

  void setup() {
    def json = new JsonSlurper()
    def cl = ClassLoader.systemClassLoader
    def indexJson = cl.getResourceAsStream('searchIndex.json').text
    def indexSettings = new NStringEntity(indexJson, ContentType.APPLICATION_JSON)
    String endpoint = "${SEARCH_INDEX}"
    Response response = restClient.performRequest('DELETE', '_all')
    println("DELETE _all: ${response}")
    response = restClient.performRequest('PUT', endpoint, Collections.EMPTY_MAP, indexSettings)
    println("PUT new index: ${response}")

    println("Failing some stuff...") // fixme debug
    try {
      restClient.performRequest('GET', 'potato')
    } catch(e) {
      println("Failed response: ${e.response}")
    }

    Map ids = [
      'GHRSST': [
        'C1': '920d8155-f764-4777-b7e5-14442b7275b8',
        'C2': '882511bc-e99e-4597-b634-47a59ddf9fda',
        'C3': '42ea683d-e4e7-434c-8823-abff32e00f34',
        'G1': '29fc971a-16e5-428f-9a7e-3e147bfff46f',
        'G2': 'cd524d1b-9c07-486f-b136-07e5f35a9a69',
        'G3': '3aa2865d-a63d-44a7-a00b-4eab57fcad3f'
      ],
      'DEM': [
        'C1': 'e7a36e60-1bcb-47b1-ac0d-3c2a2a743f9b',
        'C2': 'e5820283-3686-44d0-8edd-28a086eb500e',
        'C3': '1415b3db-c602-4dbb-a502-4091fe9df1cf',
        'G1': '654f007b-9388-4ea1-b089-040a7fe1d7a4',
        'G2': '6f040968-0463-483f-a1fa-4400faaff8bd',
        'G3': '8232aca2-5e82-42b0-9e45-17c3263254c5'
      ]
    ]

    for (e in ['GHRSST', 'DEM']) {
      for (c in ['C1', 'C2', 'C3']) {
        def metadata = cl.getResourceAsStream("data/${e}/${c}.json").text
        def id = ids[e][c]
        endpoint = "/${SEARCH_INDEX}/collection/${id}"
        HttpEntity record = new NStringEntity(metadata, ContentType.APPLICATION_JSON)
        response = restClient.performRequest('PUT', endpoint, Collections.EMPTY_MAP, record)
        println("PUT new collection: ${response}")
      }
      for (g in ['G1', 'G2', 'G3']) {
        def metadata = cl.getResourceAsStream("data/${e}/${g}.json").text
        def id = ids[e][g]
        endpoint = "/${SEARCH_INDEX}/granule/${id}"
        HttpEntity record = new NStringEntity(metadata, ContentType.APPLICATION_JSON)
        response = restClient.performRequest('PUT', endpoint, Collections.EMPTY_MAP, record)
        println("PUT new granule: ${response}")
      }
    }

    endpoint = "/${SEARCH_INDEX}/_refresh"
    response = restClient.performRequest('POST', endpoint)
    println("Refresh search index: ${response}")

    restTemplate = new RestTemplate()
    restTemplate.errorHandler = new TestResponseErrorHandler()
    searchBaseUriString = "http://localhost:${port}/${contextPath}/search"
    searchBaseUri = searchBaseUriString.toURI()
  }

  def 'Valid query-only search with facets returns OK with expected results'() {
    setup:
    def request = """\
        {
          "queries":
            [
              { "type": "queryText", "value": "temperature"}
            ],
          "facets" : true
        }""".stripIndent()

    def requestEntity = RequestEntity
        .post(searchBaseUri)
        .contentType(contentType)
        .body(request)

    when:
    def result = restTemplate.exchange(requestEntity, Map)

    then: "Search returns OK"
    result.statusCode == HttpStatus.OK
    result.headers.getContentType() == contentType

    and: "Result contains 3 items"
    def items = result.body.data
    items.size() == 3

    and: "Expected results are returned"
    def actualIds = items.collect { it.attributes.fileIdentifier }
    actualIds.containsAll([
        'gov.noaa.nodc:GHRSST-EUR-L4UHFnd-MED',
        'gov.noaa.nodc:GHRSST-OSDPD-L2P-MTSAT1R',
        'gov.noaa.nodc:GHRSST-Geo_Polar_Blended_Night-OSPO-L4-GLOB'
    ])

    and: 'The correct number of facets is returned'
    def aggs = result.body.meta.facets
    aggs.size() == 6

    and: 'The facets are as expected'
    aggs.science != null
    aggs.instruments != null
    aggs.platforms != null
    aggs.projects != null
    aggs.dataCenters != null
    aggs.dataResolution != null

    and: 'The cleaned aggregations are actually cleaned'
    def locationTerms = aggs.locations.collect { it }
    // Bad planted keywords should be removed
    !locationTerms.contains('Alaska')
    !locationTerms.contains('Alaska > Unalaska')
  }

  def 'Valid filter-only search returns OK with expected results'() {
    setup:
    def request = """\
        {
          "filters":
            [
              {"type": "geometry", "relation": "contains", "geometry": {"type": "Point", "coordinates": [145.5, 12.34]}}
            ],
          "facets": false
        }""".stripIndent()

    def requestEntity = RequestEntity
        .post(searchBaseUri)
        .contentType(contentType)
        .body(request)

    when:
    def result = restTemplate.exchange(requestEntity, Map)

    then: "Search returns OK"
    result.statusCode == HttpStatus.OK
    result.headers.getContentType() == contentType

    and: "Result contains 2 items"
    def items = result.body.data
    items.size() == 2

    and: "Expected results are returned"
    def actualIds = items.collect { it.attributes.fileIdentifier }
    actualIds.containsAll([
        'gov.noaa.nodc:GHRSST-Geo_Polar_Blended_Night-OSPO-L4-GLOB',
        'gov.noaa.ngdc.mgg.dem:4870'
    ])
  }

  def 'Valid query-and-filter search returns OK with expected result'() {
    setup:
    def request = """\
        {
          "queries":
            [
              { "type": "queryText", "value": "temperature"}
            ],
          "filters":
            [
              {"type": "datetime", "before": "2007-12-31T23:59:59.999Z", "after": "2007-01-01T00:00:00Z"}
            ]
        }""".stripIndent()

    def requestEntity = RequestEntity
        .post(searchBaseUri)
        .contentType(contentType)
        .body(request)

    when:
    def result = restTemplate.exchange(requestEntity, Map)

    then: "Search returns OK"
    result.statusCode == HttpStatus.OK
    result.headers.getContentType() == contentType

    and: "Result contains 1 item"
    def items = result.body.data
    items.size() == 1

    and: "Expected result is returned"
    def actualIds = items.collect { it.attributes.fileIdentifier }
    actualIds.containsAll([
        'gov.noaa.nodc:GHRSST-EUR-L4UHFnd-MED'
    ])
  }

  def 'Valid query-and-exclude-global search returns OK with expected results'() {
    setup:
    def request = """\
        {
          "queries":
            [
              {"type": "queryText", "value": "ghrsst"}
            ],
          "filters":
            [
              {"type": "excludeGlobal", "value": true}
            ]
        }""".stripIndent()

    def requestEntity = RequestEntity
        .post(searchBaseUri)
        .contentType(contentType)
        .body(request)

    when:
    def result = restTemplate.exchange(requestEntity, Map)

    then: "Search returns OK"
    result.statusCode == HttpStatus.OK
    result.headers.getContentType() == contentType

    and: "Result contains 2 items"
    def items = result.body.data
    items.size() == 2

    and: "Expected result is returned"
    def actualIds = items.collect { it.attributes.fileIdentifier }
    actualIds.containsAll([
        'gov.noaa.nodc:GHRSST-EUR-L4UHFnd-MED',
        'gov.noaa.nodc:GHRSST-OSDPD-L2P-MTSAT1R'
    ])
  }

  def 'Time filter with #situation an item\'s time range returns the correct results'() {
    setup:
    def ghrsst1FileId = 'gov.noaa.nodc:GHRSST-EUR-L4UHFnd-MED'
    def request = [filters: [[type: 'datetime']]]
    if (before) {
      request.filters[0].before = before
    }
    if (after) {
      request.filters[0].after = after
    }

    def requestEntity = RequestEntity
        .post(searchBaseUri)
        .contentType(contentType)
        .body(JsonOutput.toJson(request))

    when:
    def result = restTemplate.exchange(requestEntity, Map)
    def ids = result.body.data.collect { it.attributes.fileIdentifier }

    then:
    result.statusCode == HttpStatus.OK
    ids.contains(ghrsst1FileId) == matches

    where: // NOTE: time range for GHRSST/1.xml is: 2005-01-30 <-> 2008-01-14
    after                  | before                 | matches | situation
    '2005-01-01T00:00:00Z' | '2005-01-02T00:00:00Z' | false   | 'range that is fully before'
    '2005-01-01T00:00:00Z' | '2008-01-01T00:00:00Z' | true    | 'range that overlaps the beginning of'
    '2005-02-01T00:00:00Z' | '2008-01-01T00:00:00Z' | true    | 'range that is fully within'
    '2005-01-01T00:00:00Z' | '2008-02-01T00:00:00Z' | true    | 'range that fully encloses'
    '2005-02-01T00:00:00Z' | '2008-02-01T00:00:00Z' | true    | 'range that overlaps the end of'
    '2008-02-01T00:00:00Z' | '2008-02-02T00:00:00Z' | false   | 'range that is fully after'
    '2005-01-01T00:00:00Z' | null                   | true    | 'start time before'
    '2005-02-01T00:00:00Z' | null                   | true    | 'start time within'
    '2008-02-01T00:00:00Z' | null                   | false   | 'start time after'
    null                   | '2005-01-01T00:00:00Z' | false   | 'end time before'
    null                   | '2008-01-01T00:00:00Z' | true    | 'end time within'
    null                   | '2008-02-01T00:00:00Z' | true    | 'end time after'
  }

  def 'Search with pagination specified returns OK with expected results'() {
    setup:
    def request = """\
        {
          "queries":
            [
              { "type": "queryText", "value": "temperature"}
            ],
          "page":
            {
              "max": 1, "offset": 0}
            }
        }""".stripIndent()

    def requestEntity = RequestEntity
        .post(searchBaseUri)
        .contentType(contentType)
        .body(request)

    when:
    def result = restTemplate.exchange(requestEntity, Map)

    then: "Search returns OK"
    result.statusCode == HttpStatus.OK
    result.headers.getContentType() == contentType

    and: "Result contains 1 item"
    def items = result.body.data
    items.size() == 1

    and: "Expected result is returned"
    def actualIds = items.collect { it.attributes.fileIdentifier }
    actualIds.containsAll([
        'gov.noaa.nodc:GHRSST-Geo_Polar_Blended_Night-OSPO-L4-GLOB'
    ])
  }

  def 'Invalid search; returns BAD_REQUEST error when not conforming to schema'() {
    setup:
    def invalidSchemaRequest = """\
        {
          "filters": [
            {"type": "dateTime", "before": "2012-01-01", "after": "2011-01-01"}
          ]
        }""".stripIndent()
    def requestEntity = RequestEntity
        .post(searchBaseUri)
        .contentType(contentType)
        .body(invalidSchemaRequest)

    when:
    def result = restTemplate.exchange(requestEntity, Map)

    then: "Request invalid"
    result.statusCode == HttpStatus.BAD_REQUEST

    and: "result contains errors list"
    result.body.errors instanceof List
    result.body.errors.every { it.status == '400' }
    result.body.errors.every { it.detail instanceof String }
  }

  def 'Invalid search; returns UNSUPPORTED_MEDIA_TYPE error when request body not specified as json content'() {
    setup:
    def request = """\
        {
          "queries":
            [
              { "type": "queryText", "value": "temperature"}
            ]
        }""".stripIndent()
    def requestEntity = RequestEntity
        .post(searchBaseUri)
        .body(request)

    when:
    def result = restTemplate.exchange(requestEntity, Map)

    then: "Bad request"
    result.statusCode == HttpStatus.UNSUPPORTED_MEDIA_TYPE
    result.headers.getContentType() == contentType
    and: "result contains no items"
    result.body.data == null
  }

  def 'Invalid search; returns BAD_REQUEST error when no request body'() {
    def requestEntity = RequestEntity
        .post(searchBaseUri)
        .contentType(contentType)
        .body("")

    when:
    def result = restTemplate.exchange(requestEntity, Map)

    then: "Bad request"
    result.statusCode == HttpStatus.BAD_REQUEST
    result.headers.getContentType() == contentType
    and: "result contains no items"
    result.body.data == null
  }

  def 'Invalid search; returns BAD_REQUEST error when request body is malformed json'() {
    setup:
    def badJsonSearch = """\
        {
          "queries": [
            {"type": "queryText", "value": "}
          ]
        }""".stripIndent()
    def requestEntity = RequestEntity
        .post(searchBaseUri)
        .contentType(contentType)
        .body(badJsonSearch)

    when:
    def result = restTemplate.exchange(requestEntity, Map)

    then: "Bad request"
    result.statusCode == HttpStatus.BAD_REQUEST
    result.headers.getContentType() == contentType
    and: "result contains no items"
    result.body.data == null
    def errors = result.body.errors
    errors.any { it.title?.contains("Bad Request") }
  }

  def 'Invalid search; returns BAD_REQUEST error when request body is invalid'() {
    setup:
    def invalidJsonSearch = """\
        {
          "queries": [
            {"type": "NOTAREALTYPE", "value": "NONSENSE"}
          ]
        }""".stripIndent()
    def requestEntity = RequestEntity
        .post(searchBaseUri)
        .contentType(contentType)
        .body(invalidJsonSearch)

    when:
    def result = restTemplate.exchange(requestEntity, Map)

    then: "Bad request"
    result.statusCode == HttpStatus.BAD_REQUEST
    result.headers.getContentType() == contentType
    and: "result contains no items"
    result.body.data == null
    def errors = result.body.errors
    errors.any { it.title?.contains("Bad Request") }
  }

  def 'totalCounts reports counts of collections and granules'() {
    def requestEntity = RequestEntity.get(new URI("$searchBaseUri/totalCounts")).build()

    when:
    def result = restTemplate.exchange(requestEntity, Map)

    then:
    result.statusCode == HttpStatus.OK
    result.headers.getContentType() == contentType
    result.body.data.size() == 2
    result.body.data*.id.containsAll(['collection', 'granule'])
    result.body.data*.count.every({ it instanceof Number })
  }

}