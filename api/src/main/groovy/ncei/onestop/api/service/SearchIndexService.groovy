package ncei.onestop.api.service

import groovy.util.logging.Slf4j
import org.elasticsearch.action.search.SearchRequestBuilder
import org.elasticsearch.action.search.SearchResponse
import org.elasticsearch.client.Client
import org.elasticsearch.index.query.QueryBuilder
import org.elasticsearch.search.aggregations.Aggregations
import org.elasticsearch.search.aggregations.bucket.terms.Terms
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

import javax.annotation.PostConstruct

@Slf4j
@Service
class SearchIndexService {

  @Value('${elasticsearch.index.search.name}')
  private String SEARCH_INDEX

  @Value('${elasticsearch.index.search.collectionType}')
  private String COLLECTION_TYPE

  @Value('${elasticsearch.index.search.granuleType}')
  private String GRANULE_TYPE

  private Client client
  private SearchRequestParserService searchRequestParserService
  private IndexAdminService indexAdminService

  @Autowired
  public SearchIndexService(Client client,
                            SearchRequestParserService searchRequestParserService,
                            IndexAdminService indexAdminService) {
    this.client = client
    this.searchRequestParserService = searchRequestParserService
    this.indexAdminService = indexAdminService
  }

  public void refresh() {
    indexAdminService.refresh(SEARCH_INDEX)
  }

  public void drop() {
    indexAdminService.drop(SEARCH_INDEX)
  }

  @PostConstruct
  public void ensure() {
    def searchExists = client.admin().indices().prepareAliasesExist(SEARCH_INDEX).execute().actionGet().exists
    if (!searchExists) {
      def realName = indexAdminService.create(SEARCH_INDEX, [GRANULE_TYPE, COLLECTION_TYPE])
      client.admin().indices().prepareAliases().addAlias(realName, SEARCH_INDEX).execute().actionGet()
    }
  }

  public void recreate() {
    drop()
    ensure()
  }

  public Map search(Map searchParams) {
    def response = queryElasticSearch(searchParams)
    response
  }

  private Map queryElasticSearch(Map params) {
    def query = searchRequestParserService.parseSearchQuery(params)
    def getCollections = searchRequestParserService.shouldReturnCollections(params)
    def getFacets = params.facets as boolean
    def pageParams = params.page as Map

    def searchBuilder = searchRequestBuilder(query, getFacets, getCollections)
    return getCollections ? getCollectionResults(searchBuilder, pageParams) : getGranuleResults(searchBuilder, pageParams)
  }

  private SearchRequestBuilder searchRequestBuilder(QueryBuilder query, boolean getFacets, boolean getCollections) {
    def builder = client.prepareSearch(SEARCH_INDEX).setTypes(GRANULE_TYPE).setQuery(query)

    if (getFacets) {
      def aggregations = searchRequestParserService.createGCMDAggregations(true)
      aggregations.each { a -> builder = builder.addAggregation(a) }
    }

    if (getCollections) {
      builder = builder.addAggregation(searchRequestParserService.createCollectionsAggregation())
      builder = builder.setSize(0)
    }

    log.debug("ES query:${builder}")
    return builder
  }

  private Map getCollectionResults(SearchRequestBuilder searchRequestBuilder, Map pageParams) {
    def searchResponse = searchRequestBuilder.execute().actionGet()
    List<String> collections = searchResponse.aggregations.get('collections')?.getBuckets()?.collect({ it.key as String })

    if (!collections) {
      return [
          data: [],
          meta: [
              total: 0,
              took: searchResponse.tookInMillis
          ]
      ]
    }

    def rangeStart = pageParams?.offset ?: 0
    def numToRetrieve = Math.min(pageParams?.max ?: 100, collections.size())
    def rangeEnd = rangeStart + numToRetrieve - 1
    def collectionsToRetrieve = collections[rangeStart..rangeEnd]
    def multiGetItemResponses = client.prepareMultiGet().add(SEARCH_INDEX, COLLECTION_TYPE, collectionsToRetrieve).get()
    def result = [
        data: multiGetItemResponses.responses.collect {
          [type: it.type, id: it.id, attributes: it.response.getSourceAsMap()]
        },
        meta: [
            total: collections.size(),
            took: searchResponse.tookInMillis // TODO - add time from multi-get?
        ]
    ]

    def facets = prepareFacets(searchResponse, true)
    if (facets) {
      result.meta.facets = facets
    }

    return result
  }

  private getGranuleResults(SearchRequestBuilder searchRequestBuilder, Map pageParams) {
    int from = pageParams?.offset ?: 0
    int size = pageParams?.max ?: 100
    searchRequestBuilder = searchRequestBuilder.setFrom(from).setSize(size)

    def searchResponse = searchRequestBuilder.execute().actionGet()
    def result = [
        data: searchResponse.hits.hits.collect({ [type: it.type, id: it.id, attributes: it.source] }),
        meta: [
            took : searchResponse.tookInMillis,
            total: searchResponse.hits.totalHits,
        ]
    ]

    def facets = prepareFacets(searchResponse, true)
    if (facets) {
      result.meta.facets = facets
    }

    return result
  }

  private static final topLevelKeywords = [
      'science': [
          'Agriculture', 'Atmosphere', 'Biological Classification', 'Biosphere', 'Climate Indicators',
          'Cryosphere', 'Human Dimensions', 'Land Surface', 'Oceans', 'Paleoclimate', 'Solid Earth',
          'Spectral/Engineering', 'Sun-Earth Interactions', 'Terrestrial Hydrosphere'
      ],
      'location': [
          'Continent', 'Geographic Region', 'Ocean', 'Solid Earth', 'Space', 'Vertical Location'
      ]
  ]

  private Map prepareFacets(SearchResponse searchResponse, boolean collections) {
    def aggregations = searchResponse.aggregations
    if (!aggregations) {
      return null
    }
    def facetNames = searchRequestParserService.facetNameMappings.keySet()
    def hasFacets = false
    def result = [:]
    facetNames.each { name ->
      def topLevelKeywords = topLevelKeywords[name]
      def buckets = aggregations?.get(name)?.getBuckets()
      if (buckets) {
        hasFacets = true
      }
      result[name] = cleanAggregation(topLevelKeywords, buckets, collections)
    }
    return hasFacets ? result : null
  }

  private Map cleanAggregation(List<String> topLevelKeywords, List<Terms.Bucket> originalAgg, boolean collections) {
    def cleanAgg = [:]
    originalAgg.each { e ->
      def term = e.key as String
      def count = collections ?
          e.getAggregations().get('byCollection').getBuckets().size() :
          e.docCount

      if(!topLevelKeywords) {
        cleanAgg.put(term, [count: count])
      }
      else {
        if (term.contains('>')) {
          def splitTerms = term.split('>', 2)
          if (topLevelKeywords.contains(splitTerms[0].trim())) {
            cleanAgg.put(term, [count: count])
          }
        }
        else {
          if(topLevelKeywords.contains(term)) {
            cleanAgg.put(term, [count: count])
          }
        }
      }
    }
    return cleanAgg
  }
}
