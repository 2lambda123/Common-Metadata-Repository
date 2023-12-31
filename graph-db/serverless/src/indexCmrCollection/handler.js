import 'array-foreach-async'

import { deleteCmrCollection } from '../utils/cmr/deleteCmrCollection'
import { fetchCmrCollection } from '../utils/cmr/fetchCmrCollection'
import { fetchCollectionPermittedGroups } from '../utils/cmr/fetchCollectionPermittedGroups'
import { getConceptType } from '../utils/cmr/getConceptType'
import { getEchoToken } from '../utils/cmr/getEchoToken'
import { indexCmrCollection } from '../utils/cmr/indexCmrCollection'
import { initializeGremlinConnection } from '../utils/gremlin/initializeGremlinConnection'

let gremlinConnection
let token

const updateActionType = 'concept-update'
const deleteActionType = 'concept-delete'

const indexCmrCollections = async (event) => {
  // Prevent creating more tokens than necessary
  if (token === undefined) {
    token = await getEchoToken()
  }

  // Prevent connecting to Gremlin more than necessary
  if (!gremlinConnection) {
    gremlinConnection = initializeGremlinConnection()
  }

  let recordCount = 0
  let skipCount = 0

  const { Records: updatedConcepts } = event

  await updatedConcepts.forEachAsync(async (message) => {
    const { body } = message

    const { 'concept-id': conceptId, 'revision-id': revisionId, action } = JSON.parse(body)

    if (!['collection'].includes(getConceptType(conceptId))) {
      // SQS queue subscription is to ingest_exchange, which is shared across concepts
      console.log(`Concept [${conceptId}] was not a collection and will not be indexed`)

      return
    }

    if (action !== updateActionType && action !== deleteActionType) {
      console.log(`Action [${action}] was unsupported for concept [${conceptId}]`)

      return
    }

    if (getConceptType(conceptId) === 'collection' && action === updateActionType) {
      try {
        // Retrieve collection records from CMR
        const collection = await fetchCmrCollection(conceptId, token)

        const { data = {} } = collection
        const { items = [] } = data

        if (items.length === 0) {
          console.log(`Skip indexing of collection [${conceptId}] as it is not found in CMR`)

          skipCount += 1
        } else {
          const [firstCollection] = items

          // TODO: slow down how frequently we are sending responses to the access-control application
          // since this effects all collection calls that get indexed it may help with the collection problems
          // await new Promise((rate) => setTimeout(rate, 500))
          // Fetch the permitted groups for this collection from access-control
          const groupList = await fetchCollectionPermittedGroups(conceptId, token)

          console.log(`Start indexing concept [${conceptId}], revision-id [${revisionId}]`)

          // Index collection into the graphDb
          await indexCmrCollection(firstCollection, groupList, gremlinConnection)

          recordCount += 1
        }
      } catch (error) {
        console.log(`Error indexing collection [${conceptId}], Exception was thrown in index-collection handler ${error}`)
      }
    }
    if (getConceptType(conceptId) === 'collection' && action === deleteActionType) {
      console.log(`Start deleting concept [${conceptId}], revision-id [${revisionId}]`)

      await deleteCmrCollection(conceptId, gremlinConnection)

      recordCount += 1
    }
  })

  let body = `Successfully indexed ${recordCount} collection(s).`

  if (skipCount > 0) {
    body += ` Skipped ${skipCount} collection(s).`
  }

  return {
    isBase64Encoded: false,
    statusCode: 200,
    body
  }
}

export default indexCmrCollections
