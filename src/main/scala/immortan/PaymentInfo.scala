package immortan

import immortan.utils.ImplicitJsonFormats._
import fr.acinq.eclair.channel.{DATA_CLOSING, DATA_NEGOTIATING, HasNormalCommitments}
import immortan.crypto.Tools.{Bytes, Fiat2Btc, Any2Some, SEPARATOR, ratio}
import immortan.fsm.{IncomingPaymentProcessor, SendMultiPart, SplitInfo}
import fr.acinq.bitcoin.{ByteVector32, Satoshi, Transaction}
import fr.acinq.eclair.wire.{FullPaymentTag, PaymentTagTlv}
import fr.acinq.bitcoin.Crypto.PublicKey
import immortan.utils.PaymentRequestExt
import fr.acinq.eclair.MilliSatoshi
import scodec.bits.ByteVector
import java.util.Date


object PaymentInfo {
  final val NO_ACTION = "no-action"
  final val NOT_SENDABLE_IN_FLIGHT = 0
  final val NOT_SENDABLE_SUCCESS = 1
}

object PaymentStatus {
  final val SUCCEEDED = 3
  final val ABORTED = 2
  final val PENDING = 1
  final val INIT = 0
}

sealed trait TransactionDetails {
  lazy val date: Date = new Date(seenAt)
  val seenAt: Long
}

case class SplitParams(prExt: PaymentRequestExt, action: Option[PaymentAction], description: PaymentDescription, cmd: SendMultiPart, chainFee: MilliSatoshi)
case class PaymentInfo(prString: String, preimage: ByteVector32, status: Int, seenAt: Long, descriptionString: String, actionString: String, paymentHash: ByteVector32,
                       paymentSecret: ByteVector32, received: MilliSatoshi, sent: MilliSatoshi, fee: MilliSatoshi, balanceSnapshot: MilliSatoshi, fiatRatesString: String,
                       chainFee: MilliSatoshi, incoming: Long) extends TransactionDetails {

  val isIncoming: Boolean = 1 == incoming
  val fullTag: FullPaymentTag = FullPaymentTag(paymentHash, paymentSecret, if (isIncoming) PaymentTagTlv.FINAL_INCOMING else PaymentTagTlv.LOCALLY_SENT)
  lazy val action: Option[PaymentAction] = if (actionString == PaymentInfo.NO_ACTION) None else to[PaymentAction](actionString).asSome
  lazy val description: PaymentDescription = to[PaymentDescription](descriptionString)
  lazy val prExt: PaymentRequestExt = PaymentRequestExt.fromRaw(prString)
  lazy val fiatRateSnapshot: Fiat2Btc = to[Fiat2Btc](fiatRatesString)

  def receivedRatio(fsm: IncomingPaymentProcessor): Long =
    ratio(received, fsm.lastAmountIn)
}

// Payment actions

sealed trait PaymentAction {
  val domain: Option[String]
  val finalMessage: String
}

case class MessageAction(domain: Option[String], message: String) extends PaymentAction {
  val finalMessage = s"<br>${message take 144}"
}

case class UrlAction(domain: Option[String], description: String, url: String) extends PaymentAction {
  val finalMessage = s"<br>${description take 144}<br><br><font color=#0000FF><tt>$url</tt></font><br>"
  require(domain.forall(url.contains), "Payment action domain mismatch")
}

case class AESAction(domain: Option[String], description: String, ciphertext: String, iv: String) extends PaymentAction {
  val ciphertextBytes: Bytes = ByteVector.fromValidBase64(ciphertext).take(1024 * 4).toArray // up to ~2kb of encrypted data
  val ivBytes: Bytes = ByteVector.fromValidBase64(iv).take(24).toArray // 16 bytes
  val finalMessage = s"<br>${description take 144}"
}

// Payment descriptions

sealed trait PaymentDescription {
  val finalDescription: Option[String]
  val split: Option[SplitInfo]
  val label: Option[String]
  val invoiceText: String
  val queryText: String
}

case class PlainDescription(split: Option[SplitInfo], label: Option[String], invoiceText: String) extends PaymentDescription {
  val finalDescription: Option[String] = label orElse Some(invoiceText).find(_.nonEmpty)
  val queryText: String = s"$invoiceText ${label getOrElse new String}"
}

case class PlainMetaDescription(split: Option[SplitInfo], label: Option[String], invoiceText: String, meta: String) extends PaymentDescription {
  val finalDescription: Option[String] = label orElse List(meta, invoiceText).find(_.nonEmpty)
  val queryText: String = s"$invoiceText $meta ${label getOrElse new String}"
}

// Relayed preimages

case class RelayedPreimageInfo(paymentHashString: String, paymentSecretString: String, preimageString: String,
                               relayed: MilliSatoshi, earned: MilliSatoshi, seenAt: Long) extends TransactionDetails {

  lazy val preimage: ByteVector32 = ByteVector32.fromValidHex(preimageString)
  lazy val paymentHash: ByteVector32 = ByteVector32.fromValidHex(paymentHashString)
  lazy val paymentSecret: ByteVector32 = ByteVector32.fromValidHex(paymentSecretString)
  lazy val fullTag: FullPaymentTag = FullPaymentTag(paymentHash, paymentSecret, PaymentTagTlv.TRAMPLOINE_ROUTED)
}

// Tx descriptions

case class TxInfo(txString: String, txidString: String, depth: Long, receivedSat: Satoshi, sentSat: Satoshi, feeSat: Satoshi,
                  seenAt: Long, descriptionString: String, balanceSnapshot: MilliSatoshi, fiatRatesString: String,
                  incoming: Long, doubleSpent: Long) extends TransactionDetails {

  val isIncoming: Boolean = 1L == incoming
  val isDoubleSpent: Boolean = 1L == doubleSpent
  val isDeeplyBuried: Boolean = depth >= LNParams.minDepthBlocks
  lazy val fiatRateSnapshot: Fiat2Btc = to[Fiat2Btc](fiatRatesString)
  lazy val description: TxDescription = to[TxDescription](descriptionString)
  lazy val txid: ByteVector32 = ByteVector32.fromValidHex(txidString)
  lazy val tx: Transaction = Transaction.read(txString)
}

sealed trait TxDescription {
  def queryText(txid: ByteVector32): String
  def label: Option[String] = None
}

case class PlainTxDescription(addresses: List[String], override val label: Option[String] = None) extends TxDescription {
  def queryText(txid: ByteVector32): String = txid.toHex + SEPARATOR + addresses.mkString(SEPARATOR) + SEPARATOR + label.getOrElse(new String)
}

sealed trait ChanTxDescription extends TxDescription {
  def nodeId: PublicKey
}

case class OpReturnTxDescription(nodeId: PublicKey, preimage: ByteVector32) extends ChanTxDescription {
  def queryText(txid: ByteVector32): String = txid.toHex + SEPARATOR + nodeId.toString + SEPARATOR + preimage.toHex
}

case class ChanFundingTxDescription(nodeId: PublicKey) extends ChanTxDescription {
  def queryText(txid: ByteVector32): String = txid.toHex + SEPARATOR + nodeId.toString
}

case class ChanRefundingTxDescription(nodeId: PublicKey) extends ChanTxDescription {
  def queryText(txid: ByteVector32): String = txid.toHex + SEPARATOR + nodeId.toString
}

case class HtlcClaimTxDescription(nodeId: PublicKey) extends ChanTxDescription {
  def queryText(txid: ByteVector32): String = txid.toHex + SEPARATOR + nodeId.toString
}

case class PenaltyTxDescription(nodeId: PublicKey) extends ChanTxDescription {
  def queryText(txid: ByteVector32): String = txid.toHex + SEPARATOR + nodeId.toString
}

object TxDescription {
  def define(chans: Iterable[Channel], walletAddresses: List[String], tx: Transaction): TxDescription =
    defineChannelRelation(chans, tx) getOrElse PlainTxDescription(walletAddresses)

  def defineChannelRelation(chans: Iterable[Channel], tx: Transaction): Option[TxDescription] = chans.map(_.data).collectFirst {
    case hasCommits: HasNormalCommitments if hasCommits.commitments.commitInput.outPoint.txid == tx.txid => ChanFundingTxDescription(hasCommits.commitments.remoteInfo.nodeId)
    case closing: DATA_CLOSING if closing.revokedCommitPublished.flatMap(_.penaltyTxs).exists(_.txid == tx.txid) => PenaltyTxDescription(closing.commitments.remoteInfo.nodeId)
    case negs: DATA_NEGOTIATING if negs.closingTxProposed.flatten.exists(_.unsignedTx.txid == tx.txid) => ChanRefundingTxDescription(negs.commitments.remoteInfo.nodeId)
    case negs: DATA_NEGOTIATING if negs.bestUnpublishedClosingTxOpt.exists(_.txid == tx.txid) => ChanRefundingTxDescription(negs.commitments.remoteInfo.nodeId)
    case closing: DATA_CLOSING if closing.balanceLeftoverRefunds.exists(_.txid == tx.txid) => ChanRefundingTxDescription(closing.commitments.remoteInfo.nodeId)
    case closing: DATA_CLOSING if closing.paymentLeftoverRefunds.exists(_.txid == tx.txid) => HtlcClaimTxDescription(closing.commitments.remoteInfo.nodeId)
  }
}