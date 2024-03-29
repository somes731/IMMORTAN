/*
 * Copyright 2019 ACINQ SAS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fr.acinq.eclair.blockchain.electrum

import fr.acinq.bitcoin._
import fr.acinq.bitcoin.DeterministicWallet._
import fr.acinq.eclair.blockchain.EclairWallet._
import fr.acinq.eclair.blockchain.electrum.ElectrumClient._

import scala.util.{Failure, Success, Try}
import akka.actor.{ActorRef, FSM, PoisonPill, Props}
import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey}
import fr.acinq.eclair.blockchain.electrum.db.WalletDb
import fr.acinq.eclair.blockchain.bitcoind.rpc.Error
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.transactions.Transactions
import fr.acinq.eclair.blockchain.TxAndFee
import scala.annotation.tailrec
import scodec.bits.ByteVector

/**
 * Simple electrum wallet.
 * See the documentation at https://electrumx-spesmilo.readthedocs.io/en/latest/
 *
 * Typical workflow:
 *
 * client ---- header update ----> wallet
 * client ---- status update ----> wallet
 * client <--- ask history   ----- wallet
 * client ---- history       ----> wallet
 * client <--- ask tx        ----- wallet
 * client ---- tx            ----> wallet
 */
class ElectrumWallet(seed: ByteVector, client: ActorRef, params: ElectrumWallet.WalletParameters) extends FSM[ElectrumWallet.State, ElectrumWallet.Data] {

  import Blockchain.RETARGETING_PERIOD
  import ElectrumWallet._
  import params._

  val master: ExtendedPrivateKey = DeterministicWallet.generate(seed)

  val accountMaster: ExtendedPrivateKey = accountKey(master, chainHash)

  val changeMaster: ExtendedPrivateKey = changeKey(master, chainHash)

  client ! ElectrumClient.AddStatusListener(self)

  /**
   * If the wallet is ready and its state changed since the last time it was ready:
   * - publish a WalletReady notification
   * - persist state data
   *
   * @param data wallet data
   * @return the input data with an updated 'last ready message' if needed
   */
  def persistAndNotify(data: ElectrumWallet.Data): ElectrumWallet.Data = {
    if (data.isReady(swipeRange)) {
      data.lastReadyMessage match {
        case Some(value) if value == data.readyMessage =>
          log.debug("ready message {} has already been sent", value)
          data
        case _ =>
          log.info(s"checking wallet")
          val ready = data.readyMessage
          log.info(s"wallet is ready with $ready")
          context.system.eventStream.publish(ready)
          params.walletDb.persist(PersistentData(data))
          data.copy(lastReadyMessage = Some(ready))
      }
    } else data
  }

  startWith(DISCONNECTED, {
    val blockchain = params.chainHash match {
      // regtest is a special case, there are no checkpoints and we start with a single header
      case Block.RegtestGenesisBlock.hash => Blockchain.fromGenesisBlock(Block.RegtestGenesisBlock.hash, Block.RegtestGenesisBlock.header)
      case _ =>
        val checkpoints = CheckPoint.load(params.chainHash, params.walletDb)
        Blockchain.fromCheckpoints(params.chainHash, checkpoints)
    }
    val headers = params.walletDb.getHeaders(blockchain.checkpoints.size * RETARGETING_PERIOD, Int.MaxValue)
    log.info(s"loading ${headers.size} headers from db")
    val blockchain1 = Blockchain.addHeadersChunk(blockchain, blockchain.checkpoints.size * RETARGETING_PERIOD, headers)
    val data = Try(params.walletDb.readPersistentData) match {
      case Success(Some(persisted)) =>
        val firstAccountKeys = (0 until persisted.accountKeysCount).map(i => derivePrivateKey(accountMaster, i)).toVector
        val firstChangeKeys = (0 until persisted.changeKeysCount).map(i => derivePrivateKey(changeMaster, i)).toVector

        Data(blockchain1,
          firstAccountKeys,
          firstChangeKeys,
          status = persisted.status,
          transactions = persisted.transactions,
          heights = persisted.heights,
          history = persisted.history,
          proofs = persisted.proofs,
          pendingHistoryRequests = Set.empty,
          pendingHeadersRequests = Set.empty,
          pendingTransactionRequests = Set.empty,
          pendingTransactions = persisted.pendingTransactions,
          lastReadyMessage = None)
      case Success(None) =>
        log.info(s"wallet db is empty, starting with a default wallet")
        val firstAccountKeys = (0 until params.swipeRange).map(i => derivePrivateKey(accountMaster, i)).toVector
        val firstChangeKeys = (0 until params.swipeRange).map(i => derivePrivateKey(changeMaster, i)).toVector
        Data(params, blockchain1, firstAccountKeys, firstChangeKeys)
      case Failure(exception) =>
        log.info(s"cannot read wallet db ($exception), starting with a default wallet")
        val firstAccountKeys = (0 until params.swipeRange).map(i => derivePrivateKey(accountMaster, i)).toVector
        val firstChangeKeys = (0 until params.swipeRange).map(i => derivePrivateKey(changeMaster, i)).toVector
        Data(params, blockchain1, firstAccountKeys, firstChangeKeys)
    }
    log.info(s"restored wallet balance=${data.balance}")
    data
  })

  when(DISCONNECTED) {
    case Event(ElectrumClient.ElectrumReady(_, _, _), data) =>
      // subscribe to headers stream, server will reply with its current tip
      client ! ElectrumClient.HeaderSubscription(self)
      goto(WAITING_FOR_TIP) using data
  }

  when(WAITING_FOR_TIP) {
    case Event(ElectrumClient.HeaderSubscriptionResponse(height, header), data) =>
      if (height < data.blockchain.height) {
        log.info(s"electrum server is behind at $height we're at ${data.blockchain.height}, disconnecting")
        sender ! PoisonPill
        goto(DISCONNECTED) using data
      } else if (data.blockchain.bestchain.isEmpty) {
        log.info("performing full sync")
        // now ask for the first header after our latest checkpoint
        client ! ElectrumClient.GetHeaders(data.blockchain.checkpoints.size * RETARGETING_PERIOD, RETARGETING_PERIOD)
        goto(SYNCING) using data
      } else if (header == data.blockchain.tip.header) {
        // nothing to sync
        data.accountKeys.foreach(key => client ! ElectrumClient.ScriptHashSubscription(computeScriptHashFromPublicKey(key.publicKey), self))
        data.changeKeys.foreach(key => client ! ElectrumClient.ScriptHashSubscription(computeScriptHashFromPublicKey(key.publicKey), self))
        goto(RUNNING) using persistAndNotify(data)
      } else {
        client ! ElectrumClient.GetHeaders(data.blockchain.tip.height + 1, RETARGETING_PERIOD)
        log.info(s"syncing headers from ${data.blockchain.height} to $height, ready=${data.isReady(params.swipeRange)}")
        goto(SYNCING) using data
      }
  }

  when(SYNCING) {
    case Event(ElectrumClient.GetHeadersResponse(start, headers, _), data) =>
      if (headers.isEmpty) {
        // ok, we're all synced now
        log.info(s"headers sync complete, tip=${data.blockchain.tip}")
        data.accountKeys.foreach(key => client ! ElectrumClient.ScriptHashSubscription(computeScriptHashFromPublicKey(key.publicKey), self))
        data.changeKeys.foreach(key => client ! ElectrumClient.ScriptHashSubscription(computeScriptHashFromPublicKey(key.publicKey), self))
        goto(RUNNING) using persistAndNotify(data)
      } else {
        Try(Blockchain.addHeaders(data.blockchain, start, headers)) match {
          case Success(blockchain1) =>
            val (blockchain2, saveme) = Blockchain.optimize(blockchain1)
            saveme.grouped(RETARGETING_PERIOD).foreach(chunk => params.walletDb.addHeaders(chunk.head.height, chunk.map(_.header)))
            log.info(s"requesting new headers chunk at ${blockchain2.tip.height}")
            client ! ElectrumClient.GetHeaders(blockchain2.tip.height + 1, RETARGETING_PERIOD)
            goto(SYNCING) using data.copy(blockchain = blockchain2)
          case Failure(error) =>
            log.error("electrum server sent bad headers, disconnecting", error)
            sender ! PoisonPill
            goto(DISCONNECTED) using data
        }
      }

    case Event(ElectrumClient.HeaderSubscriptionResponse(height, header), _) =>
      // we can ignore this, we will request header chunks until the server has nothing left to send us
      log.debug("ignoring header {} at {} while syncing", header, height)
      stay()
  }

  when(RUNNING) {
    case Event(ElectrumClient.HeaderSubscriptionResponse(_, header), data) if data.blockchain.tip.header == header => stay

    case Event(ElectrumClient.HeaderSubscriptionResponse(height, header), data) =>
      log.info(s"got new tip ${header.blockId} at $height")

      val difficulty = Blockchain.getDifficulty(data.blockchain, height, params.walletDb)

      if (!difficulty.forall(target => header.bits == target)) {
        log.error(s"electrum server send bad header (difficulty is not valid), disconnecting")
        sender ! PoisonPill
        stay()
      } else {
        Try(Blockchain.addHeader(data.blockchain, height, header)) match {
          case Success(blockchain1) =>
            val (blockchain2, saveme) = Blockchain.optimize(blockchain1)
            saveme.grouped(RETARGETING_PERIOD).foreach(chunk => params.walletDb.addHeaders(chunk.head.height, chunk.map(_.header)))
            stay using persistAndNotify(data.copy(blockchain = blockchain2))
          case Failure(error) =>
            log.error(error, s"electrum server sent bad header, disconnecting")
            sender ! PoisonPill
            stay() using data
        }
      }

    case Event(ElectrumClient.ScriptHashSubscriptionResponse(scriptHash, status), data) if data.status.get(scriptHash).contains(status) =>
      val missing = data.missingTransactions(scriptHash)
      missing.foreach(txid => client ! GetTransaction(txid))
      stay using persistAndNotify(data.copy(pendingHistoryRequests = data.pendingTransactionRequests ++ missing))

    case Event(ElectrumClient.ScriptHashSubscriptionResponse(scriptHash, status), data) if !data.accountKeyMap.contains(scriptHash) && !data.changeKeyMap.contains(scriptHash) =>
      log.warning(s"received status=$status for scriptHash=$scriptHash which does not match any of our keys")
      stay

    case Event(ElectrumClient.ScriptHashSubscriptionResponse(scriptHash, status), data) if status.isEmpty =>
      val data1 = data.copy(status = data.status + (scriptHash -> status)) // empty status, nothing to do
      stay using persistAndNotify(data1)

    case Event(ElectrumClient.ScriptHashSubscriptionResponse(scriptHash, status), data) =>
      val key = data.accountKeyMap.getOrElse(scriptHash, data.changeKeyMap(scriptHash))
      val isChange = data.changeKeyMap.contains(scriptHash)
      log.info(s"received status=$status for scriptHash=$scriptHash key=${segwitAddress(key, chainHash)} isChange=$isChange")

      // let's retrieve the tx history for this key
      client ! ElectrumClient.GetScriptHashHistory(scriptHash)

      val (newAccountKeys, newChangeKeys) = data.status.get(Try(ByteVector32.fromValidHex(status)).getOrElse(ByteVector32.Zeroes)) match {
        case None =>
          // first time this script hash is used, need to generate a new key
          val newKey = if (isChange) derivePrivateKey(changeMaster, data.changeKeys.last.path.lastChildNumber + 1) else derivePrivateKey(accountMaster, data.accountKeys.last.path.lastChildNumber + 1)
          val newScriptHash = computeScriptHashFromPublicKey(newKey.publicKey)
          log.info(s"generated key with index=${newKey.path.lastChildNumber} scriptHash=$newScriptHash key=${segwitAddress(newKey, chainHash)} isChange=$isChange")
          // listens to changes for the newly generated key
          client ! ElectrumClient.ScriptHashSubscription(newScriptHash, self)
          if (isChange) (data.accountKeys, data.changeKeys :+ newKey) else (data.accountKeys :+ newKey, data.changeKeys)
        case Some(_) => (data.accountKeys, data.changeKeys)
      }

      val data1 = data.copy(
        accountKeys = newAccountKeys,
        changeKeys = newChangeKeys,
        status = data.status + (scriptHash -> status),
        pendingHistoryRequests = data.pendingHistoryRequests + scriptHash)

      stay using persistAndNotify(data1)

    case Event(ElectrumClient.GetScriptHashHistoryResponse(scriptHash, items), data) =>
      log.debug("scriptHash={} has history={}", scriptHash, items)
      val shadow_items = data.history.get(scriptHash) match {
        case Some(existing_items) => existing_items.filterNot(item => items.exists(_.tx_hash == item.tx_hash))
        case None => Nil
      }
      shadow_items.foreach(item => log.warning(s"keeping shadow item for txid=${item.tx_hash}"))
      val items0 = items ++ shadow_items

      val pendingHeadersRequests1 = collection.mutable.HashSet.empty[GetHeaders]
      pendingHeadersRequests1 ++= data.pendingHeadersRequests

      /**
       * If we don't already have a header at this height, or a pending request to download the header chunk it's in,
       * download this header chunk.
       * We don't have this header because it's most likely older than our current checkpoint, downloading the whole header
       * chunk (2016 headers) is quick and they're easy to verify.
       */
      def downloadHeadersIfMissing(height: Int): Unit = {
        if (data.blockchain.getHeader(height).orElse(params.walletDb.getHeader(height)).isEmpty) {
          // we don't have this header, probably because it is older than our checkpoints
          // request the entire chunk, we will be able to check it efficiently and then store it
          val start = (height / RETARGETING_PERIOD) * RETARGETING_PERIOD
          val request = GetHeaders(start, RETARGETING_PERIOD)
          // there may be already a pending request for this chunk of headers
          if (!pendingHeadersRequests1.contains(request)) {
            client ! request
            pendingHeadersRequests1.add(request)
          }
        }
      }

      val (heights1, pendingTransactionRequests1) = items0.foldLeft((data.heights, data.pendingTransactionRequests)) {
        case ((heights, hashes), item) if !data.transactions.contains(item.tx_hash) && !data.pendingTransactionRequests.contains(item.tx_hash) =>
          // we retrieve the tx if we don't have it and haven't yet requested it
          client ! GetTransaction(item.tx_hash)
          if (item.height > 0) { // don't ask for merkle proof for unconfirmed transactions
            downloadHeadersIfMissing(item.height)
            client ! GetMerkle(item.tx_hash, item.height)
          }
          (heights + (item.tx_hash -> item.height), hashes + item.tx_hash)
        case ((heights, hashes), item) =>
          // otherwise we just update the height
          (heights + (item.tx_hash -> item.height), hashes)
      }

      // we now have updated height for all our transactions,
      heights1.collect {
        case (txid, height0) =>
          (data.heights.get(txid), height0) match {
            case (None, height) if height <= 0 =>
            // height=0 => unconfirmed, height=-1 => unconfirmed and one input is unconfirmed
            case (None, height) if height > 0 =>
              // first time we get a height for this tx: either it was just confirmed, or we restarted the wallet
              downloadHeadersIfMissing(height.toInt)
              client ! GetMerkle(txid, height.toInt)
            case (Some(previousHeight), height) if previousHeight != height =>
              // there was a reorg
              if (height > 0) {
                downloadHeadersIfMissing(height.toInt)
                client ! GetMerkle(txid, height.toInt)
              }
            case (Some(previousHeight), height) if previousHeight == height && height > 0 && !data.proofs.contains(txid) =>
              downloadHeadersIfMissing(height.toInt)
              client ! GetMerkle(txid, height.toInt)
            case (Some(previousHeight), height) if previousHeight == height =>
            // no reorg, nothing to do
          }
      }
      val data1 = data.copy(
        heights = heights1,
        history = data.history + (scriptHash -> items0),
        pendingHistoryRequests = data.pendingHistoryRequests - scriptHash,
        pendingTransactionRequests = pendingTransactionRequests1,
        pendingHeadersRequests = pendingHeadersRequests1.toSet)
      stay using persistAndNotify(data1)

    case Event(ElectrumClient.GetHeadersResponse(start, headers, _), data) =>
      Try(Blockchain.addHeadersChunk(data.blockchain, start, headers)) match {
        case Success(blockchain1) =>
          params.walletDb.addHeaders(start, headers)
          stay() using data.copy(blockchain = blockchain1)
        case Failure(error) =>
          log.error("electrum server sent bad headers, disconnecting", error)
          sender ! PoisonPill
          goto(DISCONNECTED) using data
      }

    case Event(GetTransactionResponse(tx, context_opt), data) =>
      log.debug(s"received transaction ${tx.txid}")
      data.computeTransactionDelta(tx) match {
        case Some((received, sent, feeOpt)) =>
          log.info(s"successfully connected txid=${tx.txid}")
          context.system.eventStream.publish(data.transactionReceived(tx, feeOpt, received, sent))
          // when we have successfully processed a new tx, we retry all pending txes to see if they can be added now
          data.pendingTransactions.foreach(self ! GetTransactionResponse(_, context_opt))
          val data1 = data.copy(transactions = data.transactions + (tx.txid -> tx), pendingTransactionRequests = data.pendingTransactionRequests - tx.txid, pendingTransactions = Nil)
          stay using persistAndNotify(data1)
        case None =>
          // missing parents
          log.info(s"couldn't connect txid=${tx.txid}")
          val data1 = data.copy(pendingTransactionRequests = data.pendingTransactionRequests - tx.txid, pendingTransactions = data.pendingTransactions :+ tx)
          stay using persistAndNotify(data1)
      }

    case Event(ServerError(GetTransaction(txid, _), error), data) if data.pendingTransactionRequests.contains(txid) =>
      // server tells us that txid belongs to our wallet history, but cannot provide tx ?
      log.error(s"server cannot find history tx $txid: $error")
      sender ! PoisonPill
      goto(DISCONNECTED) using data


    case Event(response@GetMerkleResponse(txid, _, height, _, _), data) =>
      data.blockchain.getHeader(height).orElse(params.walletDb.getHeader(height)) match {
        case Some(header) if header.hashMerkleRoot == response.root =>
          log.info(s"transaction $txid has been verified")
          val data1 = if (!data.transactions.contains(txid) && !data.pendingTransactionRequests.contains(txid) && !data.pendingTransactions.exists(_.txid == txid)) {
            log.warning(s"we received a Merkle proof for transaction $txid that we don't have")
            data
          } else {
            data.copy(proofs = data.proofs + (txid -> response))
          }
          stay using data1
        case Some(_) =>
          log.error(s"server sent an invalid proof for $txid, disconnecting")
          sender ! PoisonPill
          stay() using data.copy(transactions = data.transactions - txid)
        case None =>
          // this is probably because the tx is old and within our checkpoints => request the whole header chunk
          val start = (height / RETARGETING_PERIOD) * RETARGETING_PERIOD
          val request = GetHeaders(start, RETARGETING_PERIOD)
          val pendingHeadersRequest1 = if (data.pendingHeadersRequests.contains(request)) {
            data.pendingHeadersRequests
          } else {
            client ! request
            self ! response
            data.pendingHeadersRequests + request
          }
          stay() using data.copy(pendingHeadersRequests = pendingHeadersRequest1)
      }

    case Event(bc@ElectrumClient.BroadcastTransaction(tx), _) =>
      log.info(s"broadcasting txid=${tx.txid}")
      client forward bc
      stay

    case Event(CommitTransaction(tx), data) =>
      log.info(s"committing txid=${tx.txid}")
      val data1 = data.commitTransaction(tx)
      // we use the initial state to compute the effect of the tx
      // note: we know that computeTransactionDelta and the fee will be defined, because we built the tx ourselves so
      // we know all the parents
      val (received, sent, Some(fee)) = data.computeTransactionDelta(tx).get
      // we notify here because the tx won't be downloaded again (it has been added to the state at commit)
      context.system.eventStream.publish(data1.transactionReceived(tx, Some(fee), received, sent))
      stay using persistAndNotify(data1) replying true
  }

  whenUnhandled {
    case Event(IsDoubleSpent(tx), data) =>
      // detect if one of our transaction (i.e a transaction that spends from our wallet) has been double-spent
      val isDoubleSpent = data.heights
        .filter { case (_, height) => computeDepth(data.blockchain.height, height) >= 2 } // we only consider tx that have been confirmed
        .flatMap { case (txid, _) => data.transactions.get(txid) } // we get the full tx
        .exists(spendingTx => spendingTx.txIn.map(_.outPoint).toSet.intersect(tx.txIn.map(_.outPoint).toSet).nonEmpty && spendingTx.txid != tx.txid) // look for a tx that spend the same utxos and has a different txid
      stay() replying IsDoubleSpentResponse(tx, data.computeTransactionDepth(tx.txid), isDoubleSpent)

    case Event(ElectrumClient.ElectrumDisconnected, data) =>
      log.info(s"wallet got disconnected")
      // remove status for each script hash for which we have pending requests
      // this will make us query script hash history for these script hashes again when we reconnect
      goto(DISCONNECTED) using data.copy(
        status = data.status -- data.pendingHistoryRequests,
        pendingHistoryRequests = Set(),
        pendingTransactionRequests = Set(),
        pendingHeadersRequests = Set(),
        lastReadyMessage = None
      )

    case Event(GetCurrentReceiveAddresses, data) => stay replying GetCurrentReceiveAddressesResponse(data.currentReceiveAddresses)

    case Event(GetBalance, data) =>
      val (confirmed, unconfirmed) = data.balance
      stay replying GetBalanceResponse(confirmed, unconfirmed)

    case Event(GetData, data) => stay replying GetDataResponse(data)

    case Event(GetXpub, _) =>
      val (xpub, path) = computeXpub(master, chainHash)
      stay replying GetXpubResponse(xpub, path)

    case Event(CompleteTransaction(tx, feeRatePerKw, sequenceFlag), data) =>
      Try(data.completeTransaction(tx, feeRatePerKw, dustLimit, allowSpendUnconfirmed, sequenceFlag)) match {
        case Success(txAndFee) => stay replying CompleteTransactionResponse(Some(txAndFee))
        case _ => stay replying CompleteTransactionResponse(None)
      }

    case Event(SendAll(publicKeyScript, extraUtxos, feeRatePerKw, sequenceFlag), data) =>
      Try(data.spendAll(publicKeyScript, extraUtxos, feeRatePerKw, dustLimit, sequenceFlag)) match {
        case Success(txAndFee) => stay replying SendAllResponse(Some(txAndFee))
        case _ => stay replying SendAllResponse(None)
      }

    case Event(ElectrumClient.BroadcastTransaction(tx), _) =>
      val notConnected = Some(Error(code = -1, "wallet is not connected"))
      stay replying ElectrumClient.BroadcastTransactionResponse(tx, notConnected)
  }

  initialize()
}

object ElectrumWallet {
  def props(seed: ByteVector, client: ActorRef, params: WalletParameters): Props = Props(new ElectrumWallet(seed, client, params))

  case class WalletParameters(chainHash: ByteVector32, walletDb: WalletDb, dustLimit: Satoshi = 546L.sat, swipeRange: Int = 10, allowSpendUnconfirmed: Boolean = true)

  // @formatter:off
  sealed trait State
  case object DISCONNECTED extends State
  case object WAITING_FOR_TIP extends State
  case object SYNCING extends State
  case object RUNNING extends State

  sealed trait Request
  sealed trait Response

  case object GetBalance extends Request
  case class GetBalanceResponse(confirmed: Satoshi, unconfirmed: Satoshi) extends Response

  case object GetXpub extends Request
  case class GetXpubResponse(xpub: String, path: String) extends Response

  case object GetCurrentReceiveAddresses extends Request
  case class GetCurrentReceiveAddressesResponse(a2p: Address2PrivKey) extends Response

  case object GetData extends Request
  case class GetDataResponse(state: Data) extends Response

  case class CompleteTransaction(tx: Transaction, feeRatePerKw: FeeratePerKw, sequenceFlag: Long) extends Request
  case class CompleteTransactionResponse(result: Option[TxAndFee] = None) extends Response

  case class SendAll(publicKeyScript: ByteVector, extraUtxos: List[TxOut], feeRatePerKw: FeeratePerKw, sequenceFlag: Long) extends Request
  case class SendAllResponse(result: Option[TxAndFee] = None) extends Response

  case class CommitTransaction(tx: Transaction) extends Request

  case class SendTransaction(tx: Transaction) extends Request
  case class SendTransactionReponse(tx: Transaction) extends Response

  case object InsufficientFunds extends Response
  case class AmountBelowDustLimit(dustLimit: Satoshi) extends Response

  case class GetPrivateKey(address: String) extends Request
  case class GetPrivateKeyResponse(address: String, key: Option[ExtendedPrivateKey]) extends Response

  case class IsDoubleSpent(tx: Transaction) extends Request
  case class IsDoubleSpentResponse(tx: Transaction, depth: Long, isDoubleSpent: Boolean) extends Response

  sealed trait WalletEvent
  case class TransactionReceived(tx: Transaction, depth: Long, received: Satoshi, sent: Satoshi, walletAddreses: List[String], feeOpt: Option[Satoshi] = None) extends WalletEvent
  case class WalletReady(confirmedBalance: Satoshi, unconfirmedBalance: Satoshi, height: Long, timestamp: Long) extends WalletEvent {
    val totalBalance: Satoshi = confirmedBalance + unconfirmedBalance
  }

  /**
   *
   * @param key public key
   * @return the address of the p2sh-of-p2wpkh script for this key
   */
  def segwitAddress(key: PublicKey, chainHash: ByteVector32): String = {
    val script = Script.pay2wpkh(key)
    val hash = Crypto.hash160(Script.write(script))
    (chainHash: @unchecked) match {
      case Block.RegtestGenesisBlock.hash | Block.TestnetGenesisBlock.hash => Base58Check.encode(Base58.Prefix.ScriptAddressTestnet, hash)
      case Block.LivenetGenesisBlock.hash => Base58Check.encode(Base58.Prefix.ScriptAddress, hash)
    }
  }

  def segwitAddress(key: ExtendedPrivateKey, chainHash: ByteVector32): String = segwitAddress(key.publicKey, chainHash)

  def segwitAddress(key: PrivateKey, chainHash: ByteVector32): String = segwitAddress(key.publicKey, chainHash)

  /**
   *
   * @param key public key
   * @return a p2sh-of-p2wpkh script for this key
   */
  def computePublicKeyScript(key: PublicKey) = Script.pay2sh(Script.pay2wpkh(key))

  /**
   *
   * @param key public key
   * @return the hash of the public key script for this key, as used by Electrum's hash-based methods
   */
  def computeScriptHashFromPublicKey(key: PublicKey): ByteVector32 = Crypto.sha256(Script.write(computePublicKeyScript(key))).reverse

  def accountPath(chainHash: ByteVector32): List[Long] = (chainHash: @unchecked) match {
    case Block.RegtestGenesisBlock.hash | Block.TestnetGenesisBlock.hash => hardened(49) :: hardened(1) :: hardened(0) :: Nil
    case Block.LivenetGenesisBlock.hash => hardened(49) :: hardened(0) :: hardened(0) :: Nil
  }

  /**
   * use BIP49 (and not BIP44) since we use p2sh-of-p2wpkh
   *
   * @param master master key
   * @return the BIP49 account key for this master key: m/49'/1'/0'/0 on testnet/regtest, m/49'/0'/0'/0 on mainnet
   */
  def accountKey(master: ExtendedPrivateKey, chainHash: ByteVector32): ExtendedPrivateKey =
    DeterministicWallet.derivePrivateKey(master, accountPath(chainHash) ::: 0L :: Nil)

  /**
   * Compute the wallet's xpub
   *
   * @param master    master key
   * @param chainHash chain hash
   * @return a (xpub, path) tuple where xpub is the encoded account public key, and path is the derivation path for the account key
   */
  def computeXpub(master: ExtendedPrivateKey, chainHash: ByteVector32): (String, String) = {
    val xpub = DeterministicWallet.publicKey(DeterministicWallet.derivePrivateKey(master, accountPath(chainHash)))
    val prefix = (chainHash: @unchecked) match {
      case Block.LivenetGenesisBlock.hash => DeterministicWallet.ypub
      case Block.RegtestGenesisBlock.hash | Block.TestnetGenesisBlock.hash => DeterministicWallet.upub
    }
    (DeterministicWallet.encode(xpub, prefix), xpub.path.toString())
  }

  /**
   * use BIP49 (and not BIP44) since we use p2sh-of-p2wpkh
   *
   * @param master master key
   * @return the BIP49 change key for this master key: m/49'/1'/0'/1 on testnet/regtest, m/49'/0'/0'/1 on mainnet
   */
  def changeKey(master: ExtendedPrivateKey, chainHash: ByteVector32): ExtendedPrivateKey =
    DeterministicWallet.derivePrivateKey(master, accountPath(chainHash) ::: 1L :: Nil)

  def totalAmount(utxos: Seq[Utxo]): Satoshi = Satoshi(utxos.map(_.item.value).sum)

  def totalAmount(utxos: Set[Utxo]): Satoshi = totalAmount(utxos.toSeq)

  /**
   *
   * @param weight       transaction weight
   * @param feeRatePerKw fee rate
   * @return the fee for this tx weight
   */
  def computeFee(weight: Int, feeRatePerKw: Long): Satoshi = Satoshi((weight * feeRatePerKw) / 1000)

  /**
   *
   * @param txIn transaction input
   * @return Some(pubkey) if this tx input spends a p2sh-of-p2wpkh(pub), None otherwise
   */
  def extractPubKeySpentFrom(txIn: TxIn): Option[PublicKey] = {
    Try {
      // we're looking for tx that spend a pay2sh-of-p2wkph output
      require(txIn.witness.stack.size == 2)
      val pub = txIn.witness.stack(1)
      val OP_PUSHDATA(script, _) :: Nil = Script.parse(txIn.signatureScript)
      val publicKey = PublicKey(pub)
      if (Script.write(Script.pay2wpkh(publicKey)) == script) {
        Some(publicKey)
      } else None
    } getOrElse None
  }

  def computeDepth(currentHeight: Long, txHeight: Long): Long =
    if (txHeight <= 0) {
      // txHeight is 0 if tx in unconfirmed, and -1 if one of its inputs is unconfirmed
      0
    } else {
      currentHeight - txHeight + 1
    }

  case class Utxo(key: ExtendedPrivateKey, item: ElectrumClient.UnspentItem) {
    def outPoint: OutPoint = item.outPoint
  }

  /**
   * Wallet state, which stores data returned by Electrum servers.
   * Most items are indexed by script hash (i.e. by pubkey script sha256 hash).
   * Height follows Electrum's conventions:
   * - h > 0 means that the tx was confirmed at block #h
   * - 0 means unconfirmed, but all input are confirmed
   * < 0 means unconfirmed, and some inputs are unconfirmed as well
   *
   * @param blockchain                 blockchain
   * @param accountKeys                account keys
   * @param changeKeys                 change keys
   * @param status                     script hash -> status; "" means that the script hash has not been used yet
   * @param transactions               wallet transactions
   * @param heights                    transactions heights
   * @param history                    script hash -> history
   * @param pendingHistoryRequests     requests pending a response from the electrum server
   * @param pendingTransactionRequests requests pending a response from the electrum server
   * @param pendingTransactions        transactions received but not yet connected to their parents
   */
  case class Data(blockchain: Blockchain,
                  accountKeys: Vector[ExtendedPrivateKey],
                  changeKeys: Vector[ExtendedPrivateKey],
                  status: Map[ByteVector32, String],
                  transactions: Map[ByteVector32, Transaction],
                  heights: Map[ByteVector32, Int],
                  history: Map[ByteVector32, List[ElectrumClient.TransactionHistoryItem]],
                  proofs: Map[ByteVector32, GetMerkleResponse],
                  pendingHistoryRequests: Set[ByteVector32],
                  pendingTransactionRequests: Set[ByteVector32],
                  pendingHeadersRequests: Set[GetHeaders],
                  pendingTransactions: List[Transaction],
                  lastReadyMessage: Option[WalletReady]) {

    val chainHash: ByteVector32 = blockchain.chainHash

    lazy val accountKeyMap: Map[ByteVector32, ExtendedPrivateKey] = accountKeys.map(key => computeScriptHashFromPublicKey(key.publicKey) -> key).toMap

    lazy val changeKeyMap: Map[ByteVector32, ExtendedPrivateKey] = changeKeys.map(key => computeScriptHashFromPublicKey(key.publicKey) -> key).toMap

    private lazy val firstUnusedAccountKeys = accountKeys.view.filter(key => status.get(computeScriptHashFromPublicKey(key.publicKey)).contains("")).take(MAX_RECEIVE_ADDRESSES)

    private lazy val firstUnusedChangeKeys = changeKeys.find(key => status.get(computeScriptHashFromPublicKey(key.publicKey)).contains(""))

    private lazy val publicScriptMap = (accountKeys ++ changeKeys).map(key => Script.write(computePublicKeyScript(key.publicKey)) -> key).toMap

    lazy val utxos: Seq[Utxo] = history.keys.toSeq.flatMap(getUtxos)

    /**
     * The wallet is ready if all current keys have an empty status, and we don't have
     * any history/tx request pending
     * NB: swipeRange * 2 because we have account keys and change keys
     */
    def isReady(swipeRange: Int): Boolean =
      status.values.count(_.isEmpty) >= swipeRange * 2 &&
        pendingTransactionRequests.isEmpty &&
        pendingHistoryRequests.isEmpty


    def readyMessage: WalletReady = {
      val (confirmed, unconfirmed) = balance
      WalletReady(confirmed, unconfirmed, blockchain.tip.height, blockchain.tip.header.time)
    }

    /**
     *
     * @return the ids of transactions that belong to our wallet history for this script hash but that we don't have
     *         and have no pending requests for.
     */
    def missingTransactions(scriptHash: ByteVector32): Set[ByteVector32] = {
      val txids = history.getOrElse(scriptHash, Nil).map(_.tx_hash).filterNot(transactions.contains).toSet
      txids -- pendingTransactionRequests
    }

    def currentReceiveAddresses: Address2PrivKey = {
      val privateKeys = if (firstUnusedAccountKeys.isEmpty) accountKeys.take(MAX_RECEIVE_ADDRESSES) else firstUnusedAccountKeys
      val key2Address = segwitAddress(_: ExtendedPrivateKey, chainHash)
      privateKeys.map(key2Address).zip(privateKeys).toMap
    }

    def currentChangeKey: ExtendedPrivateKey = firstUnusedChangeKeys.getOrElse(changeKeys.head)

    def currentChangeAddress: String = segwitAddress(currentChangeKey, chainHash)

    def isMine(txIn: TxIn): Boolean = extractPubKeySpentFrom(txIn).map(computePublicKeyScript).map(Script.write).exists(publicScriptMap.contains)

    def isSpend(txIn: TxIn, publicKey: PublicKey): Boolean = extractPubKeySpentFrom(txIn).contains(publicKey)

    /**
     * @return true if txIn spends from an address that matches scriptHash
     */
    def isSpend(txIn: TxIn, scriptHash: ByteVector32): Boolean = extractPubKeySpentFrom(txIn).exists(pub => computeScriptHashFromPublicKey(pub) == scriptHash)

    def isReceive(txOut: TxOut, scriptHash: ByteVector32): Boolean = publicScriptMap.get(txOut.publicKeyScript).exists(key => computeScriptHashFromPublicKey(key.publicKey) == scriptHash)

    def isMine(txOut: TxOut): Boolean = publicScriptMap.contains(txOut.publicKeyScript)

    def computeTransactionDepth(txid: ByteVector32): Long = heights.get(txid).map(height => if (height > 0) computeDepth(blockchain.tip.height, height) else 0).getOrElse(0)

    /**
     *
     * @param scriptHash script hash
     * @return the list of UTXOs for this script hash (including unconfirmed UTXOs)
     */
    def getUtxos(scriptHash: ByteVector32): Seq[Utxo] = {
      history.get(scriptHash) match {
        case None => Seq()
        case Some(items) if items.isEmpty => Seq()
        case Some(items) =>
          // this is the private key for this script hash
          val key = accountKeyMap.getOrElse(scriptHash, changeKeyMap(scriptHash))

          // find all transactions that send to or receive from this script hash
          // we use collect because we may not yet have received all transactions in the history
          val txs = items collect { case item if transactions.contains(item.tx_hash) => transactions(item.tx_hash) }

          // find all tx outputs that send to our script hash
          val unspents = items.collect { case item if transactions.contains(item.tx_hash) =>
            val tx = transactions(item.tx_hash)
            val outputs = tx.txOut.zipWithIndex.filter { case (txOut, index) => isReceive(txOut, scriptHash) }
            outputs.map { case (txOut, index) => Utxo(key, ElectrumClient.UnspentItem(item.tx_hash, index, txOut.amount.toLong, item.height)) }
          }.flatten

          // and remove the outputs that are being spent. this is needed because we may have unconfirmed UTXOs
          // that are spend by unconfirmed transactions
          unspents.filterNot(utxo => txs.exists(_.txIn.exists(_.outPoint == utxo.outPoint)))
      }
    }

    /**
     *
     * @param scriptHash script hash
     * @return the (confirmed, unconfirmed) balance for this script hash. This balance may not
     *         be up-to-date if we have not received all data we've asked for yet.
     */
    def calculateBalance(scriptHash: ByteVector32): (Satoshi, Satoshi) = {
      history.get(scriptHash) match {
        case None => (0.sat, 0.sat)

        case Some(items) if items.isEmpty => (0.sat, 0.sat)

        case Some(items) =>
          val (confirmedItems, unconfirmedItems) = items.partition(_.height > 0)
          val confirmedTxs = confirmedItems.collect { case item if transactions.contains(item.tx_hash) => transactions(item.tx_hash) }
          val unconfirmedTxs = unconfirmedItems.collect { case item if transactions.contains(item.tx_hash) => transactions(item.tx_hash) }

          def findOurSpentOutputs(txs: Seq[Transaction]): Seq[TxOut] = {
            val inputs = txs.flatMap(_.txIn).filter(txIn => isSpend(txIn, scriptHash))
            val spentOutputs = inputs.map(_.outPoint).flatMap(outPoint => transactions.get(outPoint.txid).map(_.txOut(outPoint.index.toInt)))
            spentOutputs
          }

          val confirmedSpents = findOurSpentOutputs(confirmedTxs)
          val confirmedReceived = confirmedTxs.flatMap(_.txOut).filter(txOut => isReceive(txOut, scriptHash))

          val unconfirmedSpents = findOurSpentOutputs(unconfirmedTxs)
          val unconfirmedReceived = unconfirmedTxs.flatMap(_.txOut).filter(txOut => isReceive(txOut, scriptHash))

          val confirmedBalance = confirmedReceived.map(_.amount).sum - confirmedSpents.map(_.amount).sum
          val unconfirmedBalance = unconfirmedReceived.map(_.amount).sum - unconfirmedSpents.map(_.amount).sum
          (confirmedBalance, unconfirmedBalance)
      }
    }

    lazy val balance: (Satoshi, Satoshi) =
      (accountKeyMap.keys ++ changeKeyMap.keys).toList.map(calculateBalance).foldLeft((0L.sat, 0L.sat)) {
        case ((confirmed, unconfirmed), (confirmed1, unconfirmed1)) => (confirmed + confirmed1, unconfirmed + unconfirmed1)
      }

    def transactionReceived(tx: Transaction, feeOpt: Option[Satoshi], received: Satoshi, sent: Satoshi): TransactionReceived = {
      val walletAddresses = tx.txOut.filter(isMine).map(_.publicKeyScript).flatMap(publicScriptMap.get).map(segwitAddress(_, chainHash))
      TransactionReceived(tx, computeTransactionDepth(tx.txid), received, sent, walletAddresses.toList, feeOpt)
    }

    /**
     * Computes the effect of this transaction on the wallet
     *
     * @param tx input transaction
     * @return an option:
     *         - Some(received, sent, fee) where sent if what the tx spends from us, received is what the tx sends to us,
     *           and fee is the fee for the tx) tuple where sent if what the tx spends from us, and received is what the tx sends to us
     *         - None if we are missing one or more parent txs
     */
    def computeTransactionDelta(tx: Transaction): Option[(Satoshi, Satoshi, Option[Satoshi])] = {
      val ourInputs = tx.txIn.filter(isMine)
      // we need to make sure that for all inputs spending an output we control, we already  have the parent tx
      // (otherwise we can't estimate our balance)
      val missingParent = ourInputs.exists(txIn => !transactions.contains(txIn.outPoint.txid))
      if (missingParent) {
        None
      } else {
        val sent = ourInputs.map(txIn => transactions(txIn.outPoint.txid).txOut(txIn.outPoint.index.toInt)).map(_.amount).sum
        val received = tx.txOut.filter(isMine).map(_.amount).sum
        // if all the inputs were ours, we can compute the fee, otherwise we can't
        val fee_opt = if (ourInputs.size == tx.txIn.size) Some(sent - tx.txOut.map(_.amount).sum) else None
        Some((received, sent, fee_opt))
      }
    }

    /**
     *
     * @param tx    input transaction
     * @param utxos input uxtos
     * @return a tx where all utxos have been added as inputs, signed with dummy invalid signatures. This
     *         is used to estimate the weight of the signed transaction
     */
    def addUtxosWithDummySig(tx: Transaction, utxos: Seq[Utxo], sequenceFlag: Long): Transaction =
      tx.copy(txIn = utxos.map { utxo =>
        // we use dummy signature here, because the result is only used to estimate fees
        val sig = ByteVector.fill(71)(1)
        val sigScript = Script.write(OP_PUSHDATA(Script.write(Script.pay2wpkh(utxo.key.publicKey))) :: Nil)
        val witness = ScriptWitness(sig :: utxo.key.publicKey.value :: Nil)
        TxIn(utxo.outPoint, signatureScript = sigScript, sequenceFlag, witness = witness)
      })

    /**
     *
     * @param tx           input tx that has no inputs
     * @param feeRatePerKw fee rate per kiloweight
     * @param dustLimit    dust limit
     * @return a (state, tx, fee) tuple where state has been updated and tx is a complete,
     *         fully signed transaction that can be broadcast.
     *         our utxos spent by this tx are locked and won't be available for spending
     *         until the tx has been cancelled. If the tx is committed, they will be removed
     */
    def completeTransaction(tx: Transaction, feeRatePerKw: FeeratePerKw, dustLimit: Satoshi, allowSpendUnconfirmed: Boolean, sequenceFlag: Long): TxAndFee = {
      require(tx.txIn.isEmpty, "cannot complete a tx that already has inputs")
      val amount = tx.txOut.map(_.amount).sum
      require(amount > dustLimit, "amount to send is below dust limit")

      val unlocked = {
        val usable = if (allowSpendUnconfirmed) utxos else utxos.filter(_.item.height > 0)
        usable.sortBy(_.item.value)
      }

      // computes the fee what we would have to pay for our tx with our candidate utxos and an optional change output
      def computeFee(candidates: Seq[Utxo], change: Option[TxOut]): Satoshi = {
        val tx1 = addUtxosWithDummySig(tx, candidates, sequenceFlag)
        val tx2 = change.map(o => tx1.addOutput(o)).getOrElse(tx1)
        Transactions.weight2fee(feeRatePerKw, tx2.weight())
      }

      val dummyChange = TxOut(Satoshi(0), computePublicKeyScript(currentChangeKey.publicKey))

      @tailrec
      def loop(current: Seq[Utxo], remaining: Seq[Utxo]): (Seq[Utxo], Option[TxOut]) = {
        totalAmount(current) match {
          case total if total - computeFee(current, None) < amount && remaining.isEmpty =>
            // not enough funds to send amount and pay fees even without a change output
            throw new IllegalArgumentException("insufficient funds")
          case total if total - computeFee(current, None) < amount =>
            // not enough funds, try with an additional input
            loop(remaining.head +: current, remaining.tail)
          case total if total - computeFee(current, None) <= amount + dustLimit =>
            // change output would be below dust, we don't add one and just overpay fees
            (current, None)
          case total if total - computeFee(current, Some(dummyChange)) <= amount + dustLimit && remaining.isEmpty =>
            // change output is above dust limit but cannot pay for it's own fee, and we have no more utxos => we overpay a bit
            (current, None)
          case total if total - computeFee(current, Some(dummyChange)) <= amount + dustLimit =>
            // try with an additional input
            loop(remaining.head +: current, remaining.tail)
          case total =>
            val fee = computeFee(current, Some(dummyChange))
            val change = dummyChange.copy(amount = total - amount - fee)
            (current, Some(change))
        }
      }

      val (selected, change_opt) = loop(Seq.empty[Utxo], unlocked)

      // sign our tx
      val tx1 = addUtxosWithDummySig(tx, selected, sequenceFlag)
      val tx2 = change_opt.map(out => tx1.addOutput(out)).getOrElse(tx1)
      val tx3 = signTransaction(tx2)

      // and add the completed tx to the locks
      val fee = selected.map(s => Satoshi(s.item.value)).sum - tx3.txOut.map(_.amount).sum
      TxAndFee(tx3, fee)
    }

    def signTransaction(tx: Transaction): Transaction = {
      tx.copy(txIn = tx.txIn.zipWithIndex.map { case (txIn, i) =>
        val utxo = utxos.find(_.outPoint == txIn.outPoint).getOrElse(throw new RuntimeException(s"cannot sign input that spends from ${txIn.outPoint}"))
        val key = utxo.key
        val sig = Transaction.signInput(tx, i, Script.pay2pkh(key.publicKey), SIGHASH_ALL, Satoshi(utxo.item.value), SigVersion.SIGVERSION_WITNESS_V0, key.privateKey)
        val sigScript = Script.write(OP_PUSHDATA(Script.write(Script.pay2wpkh(key.publicKey))) :: Nil)
        val witness = ScriptWitness(sig :: key.publicKey.value :: Nil)
        txIn.copy(signatureScript = sigScript, witness = witness)
      })
    }

    /**
     * remove all our utxos spent by this tx. call this method if the tx was broadcast successfully
     *
     * @param tx pending transaction
     * @return an updated state
     */
    def commitTransaction(tx: Transaction): Data = {
      // HACK! since we base our utxos computation on the history as seen by the electrum server (so that it is
      // reorg-proof out of the box), we need to update the history right away if we want to be able to build chained
      // unconfirmed transactions. A few seconds later electrum will notify us and the entry will be overwritten.
      // Note that we need to take into account both inputs and outputs, because there may be change.
      val history1 = (tx.txIn.filter(isMine).flatMap(extractPubKeySpentFrom).map(computeScriptHashFromPublicKey) ++ tx.txOut.filter(isMine).map(_.publicKeyScript).map(computeScriptHash))
        .foldLeft(history) {
          case (h, scriptHash) =>
            val entry = h.get(scriptHash) match {
              case None => List(TransactionHistoryItem(0, tx.txid))
              case Some(items) if items.map(_.tx_hash).contains(tx.txid) => items
              case Some(items) => TransactionHistoryItem(0, tx.txid) :: items
            }
            h + (scriptHash -> entry)
        }
      copy(transactions = transactions + (tx.txid -> tx), heights = heights + (tx.txid -> 0), history = history1)
    }

    def spendAll(publicKeyScript: ByteVector, extraUtxos: List[TxOut], feeRatePerKw: FeeratePerKw, dustLimit: Satoshi, sequenceFlag: Long): TxAndFee = {
      // use confirmed and unconfirmed balance
      val amount = balance._1 + balance._2
      val tx = Transaction(version = 2, txIn = Nil, txOut = TxOut(amount, publicKeyScript) :: extraUtxos, lockTime = 0)
      // use all uxtos, including locked ones
      val tx1 = addUtxosWithDummySig(tx, utxos, sequenceFlag)
      val fee = Transactions.weight2fee(feeRatePerKw, tx1.weight())
      require(amount - fee > dustLimit, "amount to send is below dust limit")
      val tx2 = tx1.copy(txOut = TxOut(amount - fee, publicKeyScript) :: extraUtxos)
      TxAndFee(signTransaction(tx2), fee)
    }

    def spendAll(publicKeyScript: Seq[ScriptElt], extraUtxos: List[TxOut], feeRatePerKw: FeeratePerKw, dustLimit: Satoshi, sequenceFlag: Long): TxAndFee =
      spendAll(Script.write(publicKeyScript), extraUtxos, feeRatePerKw, dustLimit, sequenceFlag)
  }

  object Data {
    def apply(params: ElectrumWallet.WalletParameters, blockchain: Blockchain, accountKeys: Vector[ExtendedPrivateKey], changeKeys: Vector[ExtendedPrivateKey] = Vector.empty): Data =
      Data(blockchain, accountKeys, changeKeys, Map.empty, Map.empty, Map.empty, Map.empty, Map.empty, Set.empty, Set.empty, Set.empty, List.empty, None)
  }

  case class InfiniteLoopException(data: Data, tx: Transaction) extends Exception

  case class PersistentData(accountKeysCount: Int,
                            changeKeysCount: Int,
                            status: Map[ByteVector32, String],
                            transactions: Map[ByteVector32, Transaction],
                            heights: Map[ByteVector32, Int],
                            history: Map[ByteVector32, List[ElectrumClient.TransactionHistoryItem]],
                            proofs: Map[ByteVector32, GetMerkleResponse],
                            pendingTransactions: List[Transaction])

  object PersistentData {
    def apply(data: Data) = new PersistentData(data.accountKeys.length, data.changeKeys.length, data.status, data.transactions, data.heights, data.history, data.proofs, data.pendingTransactions)
  }
}
