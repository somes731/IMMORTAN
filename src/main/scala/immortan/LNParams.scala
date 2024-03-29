package immortan

import immortan.utils._
import fr.acinq.eclair._
import fr.acinq.bitcoin._
import fr.acinq.eclair.wire._
import immortan.crypto.Tools._
import fr.acinq.eclair.Features._
import scala.concurrent.duration._
import fr.acinq.eclair.blockchain.electrum._
import scodec.bits.{ByteVector, HexStringSyntax}
import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey}
import scala.concurrent.{Await, ExecutionContextExecutor}
import immortan.sqlite.{DBInterface, PreparedQuery, RichCursor}
import fr.acinq.eclair.router.Router.{PublicChannel, RouterConf}
import fr.acinq.eclair.transactions.{DirectedHtlc, RemoteFulfill}
import fr.acinq.eclair.channel.{ChannelKeys, LocalParams, PersistentChannelData}
import akka.actor.{ActorRef, ActorSystem, PoisonPill, Props, SupervisorStrategy}
import fr.acinq.eclair.blockchain.electrum.db.WalletDb
import fr.acinq.eclair.router.ChannelUpdateExt
import java.util.concurrent.atomic.AtomicLong
import immortan.SyncMaster.ShortChanIdSet
import immortan.crypto.Noise.KeyPair
import immortan.crypto.CanBeShutDown
import akka.util.Timeout
import scala.util.Try


object LNParams {
  val blocksPerDay: Int = 144 // On average we can expect this many blocks per day
  val ncFulfillSafetyBlocks: Int = 36 // Force-close and redeem on chain if NC peer stalls state update and this many blocks are left until expiration
  val hcFulfillSafetyBlocks: Int = 144 // Offer to publish preimage on chain if HC peer stalls state update and this many blocks are left until expiration
  val cltvRejectThreshold: Int = hcFulfillSafetyBlocks + 36 // Reject incoming payment if CLTV expiry is closer than this to current chain tip when HTLC arrives
  val incomingFinalCltvExpiry: CltvExpiryDelta = CltvExpiryDelta(hcFulfillSafetyBlocks + 72) // Ask payer to set final CLTV expiry to current chain tip + this many blocks

  val routingCltvExpiryDelta: CltvExpiryDelta = CltvExpiryDelta(144 * 2) // Ask relayer to set CLTV expiry delta for our channel to this much blocks
  val maxCltvExpiryDelta: CltvExpiryDelta = CltvExpiryDelta(1008) // A relative expiry per single channel hop can not exceed this much blocks
  val maxToLocalDelay: CltvExpiryDelta = CltvExpiryDelta(2016) // We ask peer to delay their payment for this long in case of force-close
  val maxFundingSatoshis: Satoshi = Satoshi(10000000000L) // Proposed channels of capacity more than this are not allowed
  val maxReserveToFundingRatio: Double = 0.05 // %
  val maxOffChainFeeRatio: Double = 0.01 // %
  val maxNegotiationIterations: Int = 50
  val maxChainConnectionsCount: Int = 5
  val maxAcceptedHtlcs: Int = 483

  val shouldSendUpdateFeerateDiff = 5.0
  val shouldRejectPaymentFeerateDiff = 15.0
  val shouldForceClosePaymentFeerateDiff = 25.0

  val minInvoiceExpiryDelta: CltvExpiryDelta = CltvExpiryDelta(18) // If payee does not provide an explicit relative CLTV this is what we use by default
  val minForceClosableIncomingHtlcAmountToFeeRatio = 3 // When incoming HTLC gets (nearly) expired, how much higher than trim threshold should it be for us to force-close
  val minForceClosableOutgoingHtlcAmountToFeeRatio = 4 // When outgoing HTLC becomes problematic, how much higher than trim threshold should it be for us to force-close
  val minPayment: MilliSatoshi = MilliSatoshi(1000L) // We can neither send nor receive LN payments which are below this value
  val minFundingSatoshis: Satoshi = Satoshi(200000L) // Proposed channels of capacity less than this are not allowed
  val minDustLimit: Satoshi = Satoshi(546L)
  val minDepthBlocks: Int = 2

  // Variables to be assigned at runtime

  var secret: WalletSecret = _
  var chainHash: ByteVector32 = _
  var chainWallet: WalletExt = _
  var cm: ChannelMaster = _

  var ourInit: Init = _
  var routerConf: RouterConf = _
  var syncParams: SyncParams = _
  var trampoline: TrampolineOn = _
  var denomination: Denomination = _
  var feeRatesInfo: FeeRatesInfo = _
  var fiatRatesInfo: FiatRatesInfo = _

  // Last known chain tip (zero is unknown)
  val blockCount: AtomicLong = new AtomicLong(0L)

  // Chain wallet has lost connection this long time ago
  // can only happen if wallet has connected, then disconnected
  val lastDisconnect: AtomicLong = new AtomicLong(Long.MaxValue)

  def isOperational: Boolean =
    null != chainHash && null != secret && null != chainWallet && null != syncParams &&
      null != trampoline && null != feeRatesInfo && null != fiatRatesInfo && null != denomination &&
      null != cm && null != cm.inProcessors && null != routerConf && null != ourInit

  implicit val timeout: Timeout = Timeout(30.seconds)
  implicit val system: ActorSystem = ActorSystem("immortan-actor-system")
  implicit val ec: ExecutionContextExecutor = scala.concurrent.ExecutionContext.Implicits.global

  def createWallet(walletDb: WalletDb, seed: ByteVector): WalletExt = {
    val clientPool = system.actorOf(SimpleSupervisor.props(Props(new ElectrumClientPool(blockCount, chainHash)), "pool", SupervisorStrategy.Resume))
    val watcher = system.actorOf(SimpleSupervisor.props(Props(new ElectrumWatcher(blockCount, clientPool)), "watcher", SupervisorStrategy.Resume))
    val wallet = system.actorOf(ElectrumWallet.props(seed, clientPool, ElectrumWallet.WalletParameters(chainHash, walletDb)), "wallet")
    val catcher = system.actorOf(Props(new WalletEventsCatcher), "catcher")
    val eclairWallet = new ElectrumEclairWallet(wallet, chainHash)
    WalletExt(eclairWallet, catcher, clientPool, watcher)
  }

  def createInit: Init = {
    val networks: InitTlv = InitTlv.Networks(chainHash :: Nil)
    val tlvStream: TlvStream[InitTlv] = TlvStream(networks)

    Init(Features(
      (ChannelRangeQueries, FeatureSupport.Optional),
      (ChannelRangeQueriesExtended, FeatureSupport.Optional),
      (OptionDataLossProtect, FeatureSupport.Optional),
      (BasicMultiPartPayment, FeatureSupport.Optional),
      (VariableLengthOnion, FeatureSupport.Optional),
      (TrampolineRouting, FeatureSupport.Optional),
      (StaticRemoteKey, FeatureSupport.Optional),
      (HostedChannels, FeatureSupport.Optional),
      (PaymentSecret, FeatureSupport.Optional),
      (ChainSwap, FeatureSupport.Optional),
      (Wumbo, FeatureSupport.Optional)
    ), tlvStream)
  }

  // We make sure force-close pays directly to wallet
  def makeChannelParams(chainWallet: WalletExt, isFunder: Boolean, fundingAmount: Satoshi): LocalParams = {
    val walletKey = Await.result(chainWallet.wallet.getReceiveAddresses, atMost = 40.seconds).values.head.publicKey
    makeChannelParams(Script.write(Script.pay2wpkh(walletKey).toList), walletKey, isFunder, fundingAmount)
  }

  // We make sure that funder and fundee key path end differently
  def makeChannelParams(defaultFinalScriptPubkey: ByteVector, walletStaticPaymentBasepoint: PublicKey, isFunder: Boolean, fundingAmount: Satoshi): LocalParams =
    makeChannelParams(defaultFinalScriptPubkey, walletStaticPaymentBasepoint, isFunder, ChannelKeys.newKeyPath(isFunder), fundingAmount)

  // Note: we set local maxHtlcValueInFlightMsat to channel capacity to simplify calculations
  def makeChannelParams(defFinalScriptPubkey: ByteVector, walletStaticPaymentBasepoint: PublicKey, isFunder: Boolean, keyPath: DeterministicWallet.KeyPath, fundingAmount: Satoshi): LocalParams =
    LocalParams(ChannelKeys.fromPath(secret.keys.master, keyPath), minDustLimit, UInt64(fundingAmount.toMilliSatoshi.toLong), channelReserve = (fundingAmount * 0.001).max(minDustLimit),
      minPayment, maxToLocalDelay, maxAcceptedHtlcs = 6, isFunder, defFinalScriptPubkey, walletStaticPaymentBasepoint)

  def currentBlockDay: Long = blockCount.get / blocksPerDay

  def isChainDisconnectTooLong: Boolean = lastDisconnect.get < System.currentTimeMillis - 60 * 60 * 1000L * 2

  def incorrectDetails(amount: MilliSatoshi): FailureMessage = IncorrectOrUnknownPaymentDetails(amount, blockCount.get)

  def peerSupportsExtQueries(theirInit: Init): Boolean = Features.canUseFeature(ourInit.features, theirInit.features, ChannelRangeQueriesExtended)
}

class SyncParams {
  val blw: RemoteNodeInfo = RemoteNodeInfo(PublicKey(hex"03144fcc73cea41a002b2865f98190ab90e4ff58a2ce24d3870f5079081e42922d"), NodeAddress.unresolved(9735, host = 5, 9, 83, 143), "BLW Den")
  val lightning: RemoteNodeInfo = RemoteNodeInfo(PublicKey(hex"03baa70886d9200af0ffbd3f9e18d96008331c858456b16e3a9b41e735c6208fef"), NodeAddress.unresolved(9735, host = 45, 20, 67, 1), "LIGHTNING")
  val conductor: RemoteNodeInfo = RemoteNodeInfo(PublicKey(hex"03c436af41160a355fc1ed230a64f6a64bcbd2ae50f12171d1318f9782602be601"), NodeAddress.unresolved(9735, host = 18, 191, 89, 219), "Conductor")
  val silentBob: RemoteNodeInfo = RemoteNodeInfo(PublicKey(hex"02e9046555a9665145b0dbd7f135744598418df7d61d3660659641886ef1274844"), NodeAddress.unresolved(9735, host = 31, 17, 70, 80), "SilentBob")
  val lntxbot: RemoteNodeInfo = RemoteNodeInfo(PublicKey(hex"02c16cca44562b590dd279c942200bdccfd4f990c3a69fad620c10ef2f8228eaff"), NodeAddress.unresolved(9735, host = 5, 2, 67, 89), "LNTXBOT")
  val acinq: RemoteNodeInfo = RemoteNodeInfo(PublicKey(hex"03864ef025fde8fb587d989186ce6a4a186895ee44a926bfc370e2c366597a3f8f"), NodeAddress.unresolved(9735, host = 34, 239, 230, 56), "ACINQ")

  val phcSyncNodes: Set[RemoteNodeInfo] = Set.empty // Semi-trusted PHC-enabled nodes which can be used as seeds for PHC sync
  val syncNodes: Set[RemoteNodeInfo] = Set(lightning, conductor, silentBob, lntxbot, acinq) // Nodes with extended queries support used as seeds for normal sync

  val maxPHCCapacity: MilliSatoshi = MilliSatoshi(1000000000000000L) // PHC can not be larger than 10 000 BTC
  val minPHCCapacity: MilliSatoshi = MilliSatoshi(50000000000L) // PHC can not be smaller than 0.5 BTC
  val minNormalChansForPHC = 5 // How many normal chans a node must have to be eligible for PHCs
  val maxPHCPerNode = 2 // How many PHCs a node can have in total

  val minCapacity: MilliSatoshi = MilliSatoshi(500000000L) // 500k sat
  val maxNodesToSyncFrom = 3 // How many disjoint peers to use for majority sync
  val acceptThreshold = 1 // ShortIds and updates are accepted if confirmed by more than this peers
  val messagesToAsk = 500 // Ask for this many messages from peer before they say this chunk is done
  val chunksToWait = 4 // Wait for at least this much chunk iterations from any peer before recording results
}

class TestNetSyncParams extends SyncParams {
  val endurance: RemoteNodeInfo = RemoteNodeInfo(PublicKey(hex"03933884aaf1d6b108397e5efe5c86bcf2d8ca8d2f700eda99db9214fc2712b134"), NodeAddress.unresolved(9735, host = 76, 223, 71, 211), "Endurance")
  val localhost: RemoteNodeInfo = RemoteNodeInfo(PublicKey(hex"03933884aaf1d6b108397e5efe5c86bcf2d8ca8d2f700eda99db9214fc2712b134"), NodeAddress.unresolved(9735, host = 10, 0, 2, 2), "localhost")
  override val syncNodes: Set[RemoteNodeInfo] = Set(endurance, localhost)
  override val minCapacity: MilliSatoshi = MilliSatoshi(1000000000L)
  override val maxNodesToSyncFrom = 1
  override val acceptThreshold = 0
}

// Important: LNParams.format must be defined

case class RemoteNodeInfo(nodeId: PublicKey, address: NodeAddress, alias: String) {
  lazy val nodeSpecificExtendedKey: DeterministicWallet.ExtendedPrivateKey = LNParams.secret.keys.ourFakeNodeIdKey(nodeId)
  lazy val nodeSpecificPair: KeyPairAndPubKey = KeyPairAndPubKey(KeyPair(nodeSpecificPubKey.value, nodeSpecificPrivKey.value), nodeId)
  lazy val nodeSpecificPrivKey: PrivateKey = nodeSpecificExtendedKey.privateKey
  lazy val nodeSpecificPubKey: PublicKey = nodeSpecificPrivKey.publicKey
}

case class WalletSecret(outstandingProviders: Set[NodeAnnouncement], keys: LightningNodeKeys, mnemonic: List[String], seed: ByteVector)

case class WalletExt(wallet: ElectrumEclairWallet, eventsCatcher: ActorRef, clientPool: ActorRef, watcher: ActorRef) extends CanBeShutDown {
  override def becomeShutDown: Unit = List(eventsCatcher, clientPool, watcher).foreach(_ ! PoisonPill)
}

case class UpdateAddHtlcExt(theirAdd: UpdateAddHtlc, remoteInfo: RemoteNodeInfo)

case class SwapInStateExt(state: SwapInState, nodeId: PublicKey)

case class LastChainBalance(confirmed: Satoshi, unconfirmed: Satoshi, stamp: Long) {
  def isTooLongAgo: Boolean = System.currentTimeMillis - 3600 * 24 * 7 * 1000L > stamp
  val totalBalance: MilliSatoshi = confirmed.toMilliSatoshi + unconfirmed
}

// Interfaces

trait NetworkBag {
  def addChannelAnnouncement(ca: ChannelAnnouncement, newSqlPQ: PreparedQuery)
  def addChannelUpdateByPosition(cu: ChannelUpdate, newSqlPQ: PreparedQuery, updSqlPQ: PreparedQuery)
  def addExcludedChannel(shortId: ShortChannelId, untilStamp: Long, newSqlPQ: PreparedQuery) // Disregard position
  def removeChannelUpdate(shortId: ShortChannelId, killSqlPQ: PreparedQuery)

  def addChannelUpdateByPosition(cu: ChannelUpdate)
  def removeChannelUpdate(shortId: ShortChannelId)

  def listChannelAnnouncements: Iterable[ChannelAnnouncement]
  def listChannelUpdates: Iterable[ChannelUpdateExt]
  def listChannelsWithOneUpdate: ShortChanIdSet
  def listExcludedChannels: Set[Long]

  def incrementScore(cu: ChannelUpdateExt)
  def getRoutingData: Map[ShortChannelId, PublicChannel]
  def removeGhostChannels(ghostIds: ShortChanIdSet, oneSideIds: ShortChanIdSet)
  def processCompleteHostedData(pure: CompleteHostedRoutingData)
  def processPureData(data: PureRoutingData)
}

// Bag of stored payments and successful relays

trait PaymentBag {
  def getPreimage(hash: ByteVector32): Try[ByteVector32]
  def setPreimage(paymentHash: ByteVector32, preimage: ByteVector32)
  def addRelayedPreimageInfo(fullTag: FullPaymentTag, preimage: ByteVector32,
                             relayed: MilliSatoshi, earned: MilliSatoshi)

  def addSearchablePayment(search: String, paymentHash: ByteVector32)
  def searchPayments(rawSearchQuery: String): RichCursor

  def replaceOutgoingPayment(prex: PaymentRequestExt, desc: PaymentDescription, action: Option[PaymentAction],
                             finalAmount: MilliSatoshi, balanceSnap: MilliSatoshi, fiatRateSnap: Fiat2Btc,
                             chainFee: MilliSatoshi)

  def replaceIncomingPayment(prex: PaymentRequestExt, preimage: ByteVector32, description: PaymentDescription,
                             balanceSnap: MilliSatoshi, fiatRateSnap: Fiat2Btc, chainFee: MilliSatoshi)

  def getPaymentInfo(paymentHash: ByteVector32): Try[PaymentInfo]

  // These MUST be the only two methods capable of updating payment state to SUCCEEDED
  def updOkIncoming(receivedAmount: MilliSatoshi, paymentHash: ByteVector32)
  def updOkOutgoing(fulfill: RemoteFulfill, fee: MilliSatoshi)
  def updAbortedOutgoing(paymentHash: ByteVector32)

  def listRecentRelays(limit: Int): RichCursor
  def listRecentPayments(limit: Int): RichCursor

  def toRelayedPreimageInfo(rc: RichCursor): RelayedPreimageInfo
  def toPaymentInfo(rc: RichCursor): PaymentInfo
}

trait DataBag {
  def putSecret(secret: WalletSecret)
  def tryGetSecret: Try[WalletSecret]

  def putFeeRatesInfo(data: FeeRatesInfo)
  def tryGetFeeRatesInfo: Try[FeeRatesInfo]

  def putReport(paymentHash: ByteVector32, report: String)
  def tryGetReport(paymentHash: ByteVector32): Try[String]

  def putBranding(nodeId: PublicKey, branding: HostedChannelBranding)
  def tryGetBranding(nodeId: PublicKey): Try[HostedChannelBranding]

  def putSwapInState(nodeId: PublicKey, state: SwapInState)
  def tryGetSwapInState(nodeId: PublicKey): Try[SwapInStateExt]
}

object ChannelBag {
  case class Hash160AndCltv(hash160: ByteVector, cltvExpiry: CltvExpiry)
}

trait ChannelBag {
  val db: DBInterface
  def all: Iterable[PersistentChannelData]
  def put(data: PersistentChannelData): PersistentChannelData
  def delete(channelId: ByteVector32)

  def htlcInfos(commitNumer: Long): Iterable[ChannelBag.Hash160AndCltv]
  def putHtlcInfo(sid: ShortChannelId, commitNumber: Long, paymentHash: ByteVector32, cltvExpiry: CltvExpiry)
  def putHtlcInfos(htlcs: Seq[DirectedHtlc], sid: ShortChannelId, commitNumber: Long)
  def rmHtlcInfos(sid: ShortChannelId)
}