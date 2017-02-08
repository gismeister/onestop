import { combineReducers } from 'redux-seamless-immutable'

import search from './behavior/search'
import routing from './behavior/routing'
import errors from './behavior/error'
import request from './behavior/request'

import config from './domain/config'
import results from './domain/results'

import cardDetails from './ui/cardDetails'
import loading from './ui/loading'
import granuleDetails from './ui/granuleDetails'
import facetHierarchies from './ui/facetHierarchies'

const domain = combineReducers({
  config,
  results
})

const ui = combineReducers({
  cardDetails,
  loading,
  granuleDetails,
  facetHierarchies
})

const behavior = combineReducers({
  request,
  search,
  routing,
  errors
})

// TODO: Pass search state elements to query removing the need for state duplication
const reducer = (state, action) => {
  return {
    domain: domain(state && state.domain || undefined, action),
    behavior: behavior(state && state.behavior || undefined, action),
    ui: ui(state && state.ui || undefined, action)
  }
}

export default reducer
