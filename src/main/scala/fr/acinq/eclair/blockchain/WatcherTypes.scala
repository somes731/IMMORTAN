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

package fr.acinq.eclair.blockchain

import scala.util.{Success, Try}
import fr.acinq.bitcoin.{ByteVector32, Script, ScriptWitness, Transaction}
import fr.acinq.eclair.channel.BitcoinEvent
import fr.acinq.bitcoin.Crypto.PublicKey
import scodec.bits.ByteVector
import akka.actor.ActorRef

/**
 * Created by PM on 19/01/2016.
 */

// @formatter:off

sealed trait Watch {
  def replyTo: ActorRef
  def event: BitcoinEvent
}

/**
 * Watch for confirmation of a given transaction.
 *
 * @param replyTo         actor to notify once the transaction is confirmed.
 * @param txId            txid of the transaction to watch.
 * @param publicKeyScript when using electrum, we need to specify a public key script; any of the output scripts should work.
 * @param minDepth        number of confirmations.
 * @param event           channel event related to the transaction.
 */
final case class WatchConfirmed(replyTo: ActorRef, txId: ByteVector32, publicKeyScript: ByteVector, minDepth: Long, event: BitcoinEvent) extends Watch

object WatchConfirmed {
  // if we have the entire transaction, we can get the publicKeyScript from any of the outputs
  def apply(replyTo: ActorRef, tx: Transaction, event: BitcoinEvent, minDepth: Long): WatchConfirmed =
    WatchConfirmed(replyTo, tx.txid, tx.txOut.map(_.publicKeyScript).headOption.getOrElse(ByteVector.empty), minDepth, event)

  def extractPublicKeyScript(witness: ScriptWitness): ByteVector = Try(PublicKey fromBin witness.stack.last) match {
    case Success(pubKey) => Script.write(Script pay2wpkh pubKey) // if last element of the witness is a public key, then this is a p2wpkh
    case _ => Script.write(Script pay2wsh witness.stack.last) // otherwise this is a p2wsh
  }
}

/**
 * Watch for transactions spending the given outpoint.
 *
 * NB: an event will be triggered *every time* a transaction spends the given outpoint. This can be useful when:
 *  - we see a spending transaction in the mempool, but it is then replaced (RBF)
 *  - we see a spending transaction in the mempool, but a conflicting transaction "wins" and gets confirmed in a block
 *
 * @param replyTo         actor to notify when the outpoint is spent.
 * @param txId            txid of the outpoint to watch.
 * @param outputIndex     index of the outpoint to watch.
 * @param publicKeyScript electrum requires us to specify a public key script; the script of the outpoint must be provided.
 * @param event           channel event related to the outpoint.
 * @param hints           txids of potential spending transactions; most of the time we know the txs, and it allows for optimizations.
 *                        This argument can safely be ignored by watcher implementations.
 */
final case class WatchSpent(replyTo: ActorRef, txId: ByteVector32, outputIndex: Int, publicKeyScript: ByteVector, event: BitcoinEvent, hints: Set[ByteVector32] = Set.empty) extends Watch

object WatchSpent {
  // if we have the entire transaction, we can get the publicKeyScript from the relevant output
  def apply(replyTo: ActorRef, tx: Transaction, outputIndex: Int, event: BitcoinEvent): WatchSpent =
    WatchSpent(replyTo, tx.txid, outputIndex, tx.txOut(outputIndex).publicKeyScript, event)
}

/**
 * Watch for the first transaction spending the given outpoint. We assume that txid is already confirmed or in the
 * mempool (i.e. the outpoint exists).
 *
 * NB: an event will be triggered only once when we see a transaction that spends the given outpoint. If you want to
 * react to the transaction spending the outpoint, you should use [[WatchSpent]] instead.
 *
 * @param replyTo         actor to notify when the outpoint is spent.
 * @param txId            txid of the outpoint to watch.
 * @param outputIndex     index of the outpoint to watch.
 * @param publicKeyScript electrum requires us to specify a public key script; the script of the outpoint must be provided.
 * @param event           channel event related to the outpoint.
 */
final case class WatchSpentBasic(replyTo: ActorRef, txId: ByteVector32, outputIndex: Int, publicKeyScript: ByteVector, event: BitcoinEvent) extends Watch

object WatchSpentBasic {
  // if we have the entire transaction, we can get the publicKeyScript from the relevant output
  def apply(replyTo: ActorRef, tx: Transaction, outputIndex: Int, event: BitcoinEvent): WatchSpentBasic =
    WatchSpentBasic(replyTo, tx.txid, outputIndex, tx.txOut(outputIndex).publicKeyScript, event)
}

trait WatchEvent {
  def event: BitcoinEvent
}

/**
 * This event is sent when a [[WatchConfirmed]] condition is met.
 *
 * @param event       channel event related to the transaction that has been confirmed.
 * @param blockHeight block in which the transaction was confirmed.
 * @param txIndex     index of the transaction in the block.
 * @param tx          transaction that has been confirmed.
 */
final case class WatchEventConfirmed(event: BitcoinEvent, blockHeight: Int, txIndex: Int, tx: Transaction) extends WatchEvent

/**
 * This event is sent when a [[WatchSpent]] condition is met.
 *
 * @param event channel event related to the outpoint that was spent.
 * @param tx    transaction spending the watched outpoint.
 */
final case class WatchEventSpent(event: BitcoinEvent, tx: Transaction) extends WatchEvent

/**
 * This event is sent when a [[WatchSpentBasic]] condition is met.
 *
 * @param event channel event related to the outpoint that was spent.
 */
final case class WatchEventSpentBasic(event: BitcoinEvent) extends WatchEvent

final case class PublishAsap(tx: Transaction)

final case class GetTxWithMeta(txid: ByteVector32)
final case class GetTxWithMetaResponse(txid: ByteVector32, tx_opt: Option[Transaction], lastBlockTimestamp: Long)
