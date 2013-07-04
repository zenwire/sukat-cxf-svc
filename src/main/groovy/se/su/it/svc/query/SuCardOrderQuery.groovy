package se.su.it.svc.query

import groovy.sql.GroovyRowResult
import groovy.sql.Sql
import groovy.util.logging.Slf4j
import org.apache.commons.dbcp.BasicDataSource
import se.su.it.svc.commons.SvcCardOrderVO

import java.sql.Timestamp

@Slf4j
class SuCardOrderQuery {

  def suCardDataSource

  private final int DEFAULT_ORDER_STATUS = 3 // WEB (online order)

  public List findAllCardOrdersForUid(String uid) {

    ArrayList cardOrders = []

    if (!uid) {
      return cardOrders
    }

    log.info "Querying card orders for uid: $uid"

    List rows = doListQuery(findAllCardsQuery, [uid:uid])

    if (!rows) { return [] }

    log.info "Found ${rows?.size()} order entries in the database for $uid."

    cardOrders = handleOrderListResult(rows)

    return cardOrders
  }

  private List doListQuery(String query, Map args) {
    Closure queryClosure = { Sql sql ->
      if (!sql) { return null }
      return sql?.rows(query, args)
    }

    return withConnection(queryClosure)
  }

  private ArrayList handleOrderListResult(List rows) {
    def cardOrders = []

    for (row in rows) {
      try {
        SvcCardOrderVO svcCardOrderVO = new SvcCardOrderVO(row as GroovyRowResult)
        cardOrders << svcCardOrderVO
      } catch (ex) {
        log.error "Failed to add order $row to orders.", ex
      }
    }
    cardOrders
  }

  private String getFindAllCardsQuery() {
    return "SELECT r.id,serial,owner,printer,createTime,firstname,lastname,streetaddress1,streetaddress2,locality,zipcode,value,description FROM request r JOIN address a ON r.address = a.id JOIN status s ON r.status = s.id WHERE r.owner = :uid"
  }


  public String orderCard(SvcCardOrderVO cardOrderVO) {
    String uuid = null

    try {
      Map addressArgs = getAddressQueryArgs(cardOrderVO)
      Map requestArgs = getRequestQueryArgs(cardOrderVO)

      Closure queryClosure = { Sql sql ->
        if (!sql) { return false }

        def cardOrders = sql?.rows(findActiveCardOrdersQuery, [owner:cardOrderVO.owner])

        log.debug "Active card orders returned: ${cardOrders?.size()}"

        if (cardOrders?.size() > 0) {
          log.error "Can't order new card since an order already exists."
          for (order in cardOrders) {
            log.debug "Order: $order"
          }
          return false
        }

        uuid = findFreeUUID(sql)
        requestArgs.id = uuid

        try {
          sql.withTransaction {
            doCardOrderInsert(sql, addressArgs, requestArgs)
          }
        } catch (ex) {
          log.error "Error in SQL card order transaction.", ex
          return false
        }
        return true
      }

      if (withConnection(queryClosure)) {
        log.info "Card order successfully added to database!"
      }


    } catch (ex) {
      log.error "Failed to create card order for ${cardOrderVO?.owner}", ex
      return null
    }

    log.info "Returning $uuid"

    return uuid
  }

  private boolean doCardOrderInsert(Sql sql, Map addressArgs, Map requestArgs) {
    String addressQuery = insertAddressQuery
    String requestQuery = insertRequestQuery
    String statusQuery = insertStatusHistoryQuery

    log.debug "Sending: $addressQuery with arguments $addressArgs"
    def addressResponse = sql?.executeInsert(addressQuery, addressArgs)
    log.debug "Address response is $addressResponse"
    def addressId = addressResponse[0][0]
    log.debug "Recieved: $addressId as response."

    /** Get the address id and set it as the request address id. */
    requestArgs['address'] = addressId
    log.debug "Sending: $requestQuery with arguments $requestArgs"
    sql?.executeInsert(requestQuery, requestArgs)
    String comment = "Created by " + requestArgs?.owner + " while activating account"

    def statusResponse = sql?.executeInsert(statusQuery,
        [status:DEFAULT_ORDER_STATUS,
            request:requestArgs.id,
            comment: comment,
            createTime:new Timestamp(new Date().getTime())
        ])

    log.debug "Status response: $statusResponse"
    return true
  }

  private Map getRequestQueryArgs(SvcCardOrderVO cardOrderVO) {
    /** id and address will be set later in the process and serials should be unset. */
    return [
        id: null,
        owner: cardOrderVO.owner,
        serial: null,
        printer: cardOrderVO.printer,
        createTime: new Timestamp(new Date().getTime()),
        firstname: cardOrderVO.firstname,
        lastname: cardOrderVO.lastname,
        address: null,
        status: DEFAULT_ORDER_STATUS
    ]
  }

  private static Map getAddressQueryArgs(SvcCardOrderVO cardOrderVO) {
    return [
        streetaddress1: cardOrderVO.streetaddress1,
        streetaddress2: cardOrderVO.streetaddress2,
        locality: cardOrderVO.locality,
        zipcode: cardOrderVO.zipcode
    ]
  }

  private static String findFreeUUID(Sql sql) {
    String uuid = null
    boolean newUUID = false

    while (!newUUID) {
      uuid = UUID.randomUUID().toString()
      log.info "findFreeUUID: Querying for uuid: ${uuid}"

      def rows = sql.rows(findFreeUUIDQuery, [uuid: uuid])

      if (rows?.size() == 0) {
        newUUID = true
      } else {
        log.info "${uuid} was already taken, retrying."
      }
    }
    return uuid
  }

  private static String getInsertAddressQuery() {
    return "INSERT INTO address VALUES(null, :streetaddress1, :streetaddress2, :locality, :zipcode)"
  }

  private static String getInsertRequestQuery() {
    return "INSERT INTO request VALUES(:id, :owner, :serial, :printer, :createTime, :address, :status, :firstname, :lastname)"
  }

  private static String getFindActiveCardOrdersQuery() {
    return "SELECT r.id, serial, owner, printer, createTime, firstname, lastname, streetaddress1, streetaddress2, locality, zipcode, value, description FROM request r JOIN address a ON r.address = a.id JOIN status s ON r.status = s.id WHERE r.owner = :owner AND status in (1,2,3)"
  }

  private static String getFindFreeUUIDQuery() {
    return "SELECT id FROM request WHERE id = :uuid"
  }

  private static String getInsertStatusHistoryQuery() {
    return "INSERT INTO status_history VALUES (null, :status, :request, :comment, :createTime)"
  }

  private static String getMarkCardAsDiscardedQuery() {
    return "UPDATE request SET status = :discardedStatus WHERE id = :id"
  }

  public boolean markCardAsDiscarded(String uuid, String uid) {
    // set request status to something.
    Closure queryClosure = { Sql sql ->
      sql.withTransaction {
        try {
          doMarkCardAsDiscarded(sql, uuid, uid)
        } catch (ex) {
          log.error "Failed to mark card as discarded in sucard db.", ex
          return false
        }
        return true
      }
    }

    return (withConnection(queryClosure))
    // create a history entry

  }

  private static void doMarkCardAsDiscarded(Sql sql, String uuid, String uid) {
    sql?.executeUpdate(markCardAsDiscardedQuery, [id:uuid])
    sql?.executeInsert(insertStatusHistoryQuery, [
        status:5,
        request: uuid,
        comment: "Discarded by " + uid,
        createTime: new Timestamp(new Date().getTime())
    ])
  }

  private withConnection = { Closure query ->
    def response = null
    Sql sql = null
    try {
      sql = new Sql(suCardDataSource as BasicDataSource)
      response = query(sql)
    } catch (ex) {
      log.error "Connection to SuCardDB failed", ex
      throw(ex)
    } finally {
      try {
        sql.close()
      } catch (ex) {
        log.error "Failed to close connection", ex
      }
    }
    return response
  }

}
