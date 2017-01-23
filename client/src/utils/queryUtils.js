import _ from 'lodash'
import { recenterGeometry } from './geoUtils'

export const assembleSearchRequestString = (state, granules = false) => {
  return JSON.stringify(assembleSearchRequest(state, granules))
}

export const assembleSearchRequest = (state, granules = false) => {
  const searchAndFacets = state.searchAndFacets || {}
  const facets = searchAndFacets.facets || {}
  const search = searchAndFacets.search || {}
  const geometry = search.geometry || {}
  const temporal = search.temporal || {}
  const appState = state.appState || {}
  const collectionSelect = appState.collectionSelect || {}

  const queries = assembleQueries(search)
  let filters = _.concat(
      assembleFacetFilters(facets),
      assembleGeometryFilters(geometry),
      assembleTemporalFilters(temporal)
  )
  if (granules) {
    filters = _.concat(assembleSelectedCollectionsFilters(collectionSelect))
  }
  filters =  _.flatten(_.compact(filters))

  return {queries: queries, filters: filters, facets: !granules}
}

const assembleQueries = ({queryText}) => {
  if (queryText && queryText.text) {
    return [{type: 'queryText', value: queryText.text}]
  }
  return []
}

const assembleFacetFilters = ({selectedFacets}) => {
  return _.map(selectedFacets, (v, k) => ({'type':'facet', 'name': k, 'values': v}))
}

const assembleGeometryFilters = ({geoJSON}) => {
  if (geoJSON && geoJSON.geometry) {
    const recenteredGeometry = recenterGeometry(geoJSON.geometry)
    return {type: 'geometry', geometry: recenteredGeometry}
  }
}

const assembleTemporalFilters = ({startDateTime, endDateTime}) => {
  if (startDateTime && endDateTime) {
    return {type: 'datetime', after: startDateTime, before: endDateTime}
  } else if (startDateTime) {
    return {type: 'datetime', after: startDateTime}
  } else if (endDateTime) {
    return {type: 'datetime', before: endDateTime}
  }
}

const assembleSelectedCollectionsFilters = ({selectedIds}) => {
  if (selectedIds.length > 0) {
    return {type: 'collection', values: selectedIds}
  }
}
