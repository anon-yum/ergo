package org.ergoplatform.nodeView.mempool

import org.ergoplatform.ErgoBox.BoxId
import org.ergoplatform.mining.emission.EmissionRules
import org.ergoplatform.modifiers.mempool.{ErgoTransaction, UnconfirmedTransaction}
import org.ergoplatform.nodeView.mempool.OrderedTxPool.WeightedTxId
import org.ergoplatform.nodeView.state.{ErgoState, UtxoState}
import org.ergoplatform.settings.{ErgoSettings, MonetarySettings, NodeConfigurationSettings}
import scorex.core.transaction.state.TransactionValidation
import scorex.util.{ModifierId, ScorexLogging, bytesToId}
import OrderedTxPool.weighted
import spire.syntax.all.cfor

import scala.annotation.tailrec
import scala.collection.mutable
import scala.util.Try


/**
  * Immutable memory pool implementation.
  *
  * @param pool     - Ordered transaction pool. Acts as related transaction storage, and is
  *                 used for implementing all transaction-related methods
  * @param stats    - Mempool statistics, that allows to track
  *                 information about mempool's state and transactions in it.
  */
class ErgoMemPool private[mempool](pool: OrderedTxPool,
                                   private[mempool] val stats : MemPoolStatistics)(implicit settings: ErgoSettings)
  extends ErgoMemPoolReader with ScorexLogging {

  import ErgoMemPool._
  import EmissionRules.CoinsInOneErgo


  private val nodeSettings: NodeConfigurationSettings = settings.nodeSettings
  private implicit val monetarySettings: MonetarySettings = settings.chainSettings.monetary

  override def size: Int = pool.size

  override def modifierById(modifierId: ModifierId): Option[UnconfirmedTransaction] = pool.get(modifierId)

  override def take(limit: Int): Iterable[UnconfirmedTransaction] = pool.orderedTransactions.values.take(limit)

  def random(limit: Int): Iterable[UnconfirmedTransaction] = {
    val result = mutable.WrappedArray.newBuilder[UnconfirmedTransaction]
    val txSeq = pool.orderedTransactions.values.to[Vector]
    val total = txSeq.size
    val start = if (total <= limit) {
      0
    } else {
      val max = total - limit
      // max > 0 always
      scala.util.Random.nextInt(max)
    }

    cfor(start)(_ < Math.min(start + limit, total), _ + 1) { idx =>
      val tx = txSeq.apply(idx)
      result += tx
    }

    result.result()
  }

  override def getAll: Seq[UnconfirmedTransaction] = pool.orderedTransactions.values.toSeq

  override def getAll(ids: Seq[ModifierId]): Seq[UnconfirmedTransaction] = ids.flatMap(pool.get)

  /**
    * Returns all transactions resided in pool sorted by weight in descending order
    */
  override def getAllPrioritized: Seq[UnconfirmedTransaction] = pool.orderedTransactions.values.toSeq

  /**
    * Method to put a transaction into the memory pool. Validation of tha transactions against
    * the state is done in NodeVieHolder. This put() method can check whether a transaction is valid
    * @param tx
    * @return Success(updatedPool), if transaction successfully added to the pool, Failure(_) otherwise
    */
  def put(unconfirmedTx: UnconfirmedTransaction): Try[ErgoMemPool] = put(Seq(unconfirmedTx))

  def put(unconfirmedTxs: Iterable[UnconfirmedTransaction]): Try[ErgoMemPool] = Try {
    putWithoutCheck(unconfirmedTxs.filterNot(unconfirmedTx => pool.contains(unconfirmedTx.transaction.id)))
  }

  def putWithoutCheck(txs: Iterable[UnconfirmedTransaction]): ErgoMemPool = {
    val updatedPool = txs.toSeq.distinct.foldLeft(pool) { case (acc, tx) => acc.put(tx) }
    new ErgoMemPool(updatedPool, stats)
  }

  def remove(unconfirmedTransaction: UnconfirmedTransaction): ErgoMemPool = {
    val tx = unconfirmedTransaction.transaction
    val wtx = pool.transactionsRegistry.get(tx.id)
    val updStats = wtx.map(wgtx => stats.add(System.currentTimeMillis(), wgtx))
      .getOrElse(MemPoolStatistics(System.currentTimeMillis(), 0, System.currentTimeMillis()))
    new ErgoMemPool(pool.remove(unconfirmedTransaction), updStats)
  }

  def filter(condition: UnconfirmedTransaction => Boolean): ErgoMemPool = {
    new ErgoMemPool(pool.filter(condition), stats)
  }

  def filter(txs: Seq[UnconfirmedTransaction]): ErgoMemPool = filter(t => !txs.exists(_.transaction.id == t.transaction.id))

  /**
    * Invalidate transaction and delete it from pool
    *
    * @param tx - Transaction to invalidate
    */
  def invalidate(unconfirmedTransaction: UnconfirmedTransaction): ErgoMemPool = {
    new ErgoMemPool(pool.invalidate(unconfirmedTransaction), stats)
  }

  /**
    * @return inputs spent by the mempool transactions
    */
  override def spentInputs: Iterator[BoxId] = pool.inputs.keysIterator

  // Check if transaction is double-spending inputs spent in the mempool.
  // If so, the new transacting is replacing older ones if it has bigger weight (fee/byte) than them on average.
  // Otherwise, the new transaction being rejected.
  private def acceptIfNoDoubleSpend(unconfirmedTransaction: UnconfirmedTransaction): (ErgoMemPool, ProcessingOutcome) = {
    val tx = unconfirmedTransaction.transaction
    val doubleSpendingWtxs = tx.inputs.flatMap { inp =>
      pool.inputs.get(inp.boxId)
    }.toSet

    if(doubleSpendingWtxs.nonEmpty) {
      val ownWtx = weighted(tx)
      val doubleSpendingTotalWeight = doubleSpendingWtxs.map(_.weight).sum / doubleSpendingWtxs.size
      if (ownWtx.weight > doubleSpendingTotalWeight) {
        val doubleSpendingTxs = doubleSpendingWtxs.map(wtx => pool.orderedTransactions(wtx)).toSeq
        new ErgoMemPool(pool.put(unconfirmedTransaction).remove(doubleSpendingTxs), stats) -> ProcessingOutcome.Accepted
      } else {
        this -> ProcessingOutcome.DoubleSpendingLoser(doubleSpendingWtxs.map(_.id))
      }
    } else {
      new ErgoMemPool(pool.put(unconfirmedTransaction), stats) -> ProcessingOutcome.Accepted
    }
  }

  def process(unconfirmedTx: UnconfirmedTransaction, state: ErgoState[_]): (ErgoMemPool, ProcessingOutcome) = {
    val tx = unconfirmedTx.transaction
    log.info(s"Processing mempool transaction: $tx")
    val blacklistedTransactions = nodeSettings.blacklistedTransactions
    if(blacklistedTransactions.nonEmpty && blacklistedTransactions.contains(tx.id)) {
      new ErgoMemPool(pool.invalidate(unconfirmedTx), stats) -> ProcessingOutcome.Invalidated(new Exception("blacklisted tx"))
    } else {
      val fee = extractFee(tx)
      val minFee = settings.nodeSettings.minimalFeeAmount
      val canAccept = pool.canAccept(unconfirmedTx)

      if (fee >= minFee) {
        if (canAccept) {
          state match {
            case utxo: UtxoState =>
              // Allow proceeded transaction to spend outputs of pooled transactions.
              val utxoWithPool = utxo.withUnconfirmedTransactions(getAll)
              if (tx.inputIds.forall(inputBoxId => utxoWithPool.boxById(inputBoxId).isDefined)) {
                utxoWithPool.validateWithCost(unconfirmedTx.transaction, Some(utxo.stateContext), nodeSettings.maxTransactionCost, None).fold(
                  ex => new ErgoMemPool(pool.invalidate(unconfirmedTx), stats) -> ProcessingOutcome.Invalidated(ex),
                  _ => acceptIfNoDoubleSpend(unconfirmedTx)
                )
              } else {
                this -> ProcessingOutcome.Declined(new Exception("not all utxos in place yet"))
              }
            case validator: TransactionValidation =>
              // transaction validation currently works only for UtxoState, so this branch currently
              // will not be triggered probably
              validator.validateWithCost(unconfirmedTx, nodeSettings.maxTransactionCost).fold(
                ex => new ErgoMemPool(pool.invalidate(unconfirmedTx), stats) -> ProcessingOutcome.Invalidated(ex),
                _ => acceptIfNoDoubleSpend(unconfirmedTx)
              )
            case _ =>
              // Accept transaction in case of "digest" state. Transactions are not downloaded in this mode from other
              // peers though, so such transactions can come from the local wallet only.
              acceptIfNoDoubleSpend(unconfirmedTx)
          }
        } else {
          this -> ProcessingOutcome.Declined(
            new Exception(s"Pool can not accept transaction ${tx.id}, it is invalidated earlier or the pool is full"))
        }
      } else {
        this -> ProcessingOutcome.Declined(
          new Exception(s"Min fee not met: ${minFee.toDouble / CoinsInOneErgo} ergs required, " +
            s"${fee.toDouble / CoinsInOneErgo} ergs given")
        )
      }
    }
  }

  def weightedTransactionIds(limit: Int): Seq[WeightedTxId] = pool.orderedTransactions.keysIterator.take(limit).toSeq

  private def extractFee(tx: ErgoTransaction): Long = {
    tx.outputs
      .filter(_.ergoTree == settings.chainSettings.monetary.feeProposition)
      .map(_.value)
      .sum
  }

  /**
    * Get average fee for the specified wait time interval
    *
    * @param expectedWaitTimeMinutes - maximal amount of time for which transaction can be kept in mempool
    * @param txSize                  - size of transaction (in bytes)
    * @return recommended fee value for transaction to be proceeded in specified time
    */
  def getRecommendedFee(expectedWaitTimeMinutes: Int, txSize: Int): Long = {
    @tailrec def loop(waitMinutes: Int): Option[Long] =
      Try(stats.histogram(waitMinutes)).toOption match {
        case Some(bin) if bin.nTxns != 0 => Some(bin.totalFee / bin.nTxns * txSize / 1024)
        case _ if waitMinutes < expectedWaitTimeMinutes => loop(waitMinutes + 1)
        case _ => None
      }

    loop(waitMinutes = 0).getOrElse(settings.nodeSettings.minimalFeeAmount)
  }

  /**
    * Calculate position in mempool corresponding to the specified fee and
    * estimate time of serving this transaction based on average rate of placing
    * transactions in blockchain
    * @param txFee  - transaction fee
    * @param txSize - size of transaction (in bytes)
    * @return average time for this transaction to be placed in block
    */
  def getExpectedWaitTime(txFee : Long, txSize : Int): Long  = {
    // Create dummy transaction entry
    val feePerKb = txFee * 1024 / txSize
    val dummyModifierId = bytesToId(Array.fill(32)(0.toByte))
    val wtx = WeightedTxId(dummyModifierId, feePerKb, feePerKb, 0)

    // Find position of entry in mempool
    val posInPool = pool.orderedTransactions.keySet.until(wtx).size

    // Time since statistics measurement interval (needed to calculate average tx rate)
    val elapsed = System.currentTimeMillis() - stats.startMeasurement
    if (stats.takenTxns != 0) {
      elapsed * posInPool / stats.takenTxns
    } else {
      0
    }
  }

  /**
    * @return read-only copy of this history
    */
  def getReader: ErgoMemPoolReader = this
}

object ErgoMemPool {

  sealed trait ProcessingOutcome

  object ProcessingOutcome {

    /**
      * Object signalling that a transaction is accepted to the memory pool
      */
    case object Accepted extends ProcessingOutcome

    /**
      * Class signalling that a valid transaction was rejected as it is double-spending inputs of mempool transactions
      * and has no bigger weight (fee/byte) than them on average.
      * @param winnerTxIds - identifiers of transactions won in replace-by-fee auction
      */
    case class DoubleSpendingLoser(winnerTxIds: Set[ModifierId]) extends ProcessingOutcome

    /**
      * Class signalling that a transaction declined from being accepted into the memory pool
      */
    case class Declined(e: Throwable) extends ProcessingOutcome


    /**
      * Class signalling that a transaction turned out to be invalid when checked in the mempool
      */
    case class Invalidated(e: Throwable) extends ProcessingOutcome

  }

  /**
    * Create empty mempool
    * @param settings - node settings (to get mempool settings from)
    * @return empty mempool
    */
  def empty(settings: ErgoSettings): ErgoMemPool =
    new ErgoMemPool(OrderedTxPool.empty(settings),
      MemPoolStatistics(System.currentTimeMillis(), 0, System.currentTimeMillis()))(settings)

}
