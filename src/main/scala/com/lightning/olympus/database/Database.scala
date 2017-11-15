package com.lightning.olympus.database

import com.mongodb.casbah.Imports._
import fr.acinq.bitcoin.{BinaryData, Transaction}
import com.lightning.wallet.ln.Scripts.cltvBlocks
import com.lightning.olympus.Utils.StringSeq
import com.mongodb.casbah.MongoCollection
import com.lightning.olympus.BlindData
import language.implicitConversions
import java.math.BigInteger
import java.util.Date


abstract class Database {
  // For signature-based auth
  def keyExists(key: String): Boolean

  // Recording on-chain transactions
  def putTx(txids: StringSeq, hex: String)
  def getTxs(txids: StringSeq): StringSeq

  // Scheduling txs to spend
  def putScheduled(tx: Transaction): Unit
  def getScheduled(depth: Int): Seq[Transaction]

  // Clear tokens storage and cheking
  def getPendingTokens(seskey: String): Option[BlindData]
  def putPendingTokens(data: BlindData, seskey: String)
  def isClearTokenUsed(clearToken: String): Boolean
  def putClearToken(clearToken: String)

  // Checking incoming LN payment status
  def isPaymentFulfilled(hash: BinaryData): Boolean

  // Storing arbitrary data in Olympus database
  def putData(key: String, prefix: String, data: String)
  def getData(key: String): List[String]
}

class MongoDatabase extends Database {
  val olympus: MongoDB = MongoClient("localhost")("olympus")
  val clearTokens: MongoDB = MongoClient("localhost")("clearTokens")
  val eclair: MongoCollection = MongoClient("localhost")("eclair")("paymentRequest")

  implicit def obj2Long(source: Object): Long = source.toString.toLong
  implicit def obj2String(source: Object): String = source.toString

  // For signature-based auth users need to save their keys in this collection
  def keyExists(key: String) = olympus("authKeys").findOne("key" $eq key).isDefined

  // Blind tokens management, k is sesPrivKey
  def putPendingTokens(data: BlindData, seskey: String) =
    olympus("blindTokens").update("seskey" $eq seskey, $set("seskey" -> seskey, "k" -> data.k.toString,
      "paymentHash" -> data.paymentHash.toString, "tokens" -> data.tokens, "createdAt" -> new Date),
      upsert = true, multi = false, WriteConcern.Safe)

  def getPendingTokens(seskey: String) =
    olympus("blindTokens").findOne("seskey" $eq seskey) map { result =>
      val tokens = result.get("tokens").asInstanceOf[BasicDBList].map(_.toString).toList
      BlindData(BinaryData(result get "paymentHash"), new BigInteger(result get "k"), tokens)
    }

  // Many collections in total to store clear tokens because we have to keep every token
  def putClearToken(clear: String) = clearTokens(clear take 1).insert("clearToken" $eq clear)
  def isClearTokenUsed(clear: String) = clearTokens(clear take 1).findOne("clearToken" $eq clear).isDefined
  def getTxs(txids: StringSeq) = olympus("spentTxs").find("txids" $in txids).map(_ as[String] "hex").toList

  def putTx(txids: StringSeq, hex: String) =
    olympus("spentTxs").update("hex" $eq hex, $set("txids" -> txids, "hex" -> hex,
      "createdAt" -> new Date), upsert = true, multi = false, WriteConcern.Safe)

  def putScheduled(tx: Transaction) =
    olympus("scheduledTxs").update("txid" $eq tx.txid.toString, $set("txid" -> tx.txid.toString,
      "tx" -> Transaction.write(tx).toString, "cltv" -> cltvBlocks(tx), "createdAt" -> new Date),
      upsert = true, multi = false, WriteConcern.Safe)

  def getScheduled(depth: Int) = {
    val res = olympus("scheduledTxs").find("cltv" $lt depth).map(_ as[String] "tx")
    for (raw <- res.toList) yield Transaction read BinaryData(raw)
  }

  // Checking incoming LN payment status
  def isPaymentFulfilled(hash: BinaryData) = {
    val result = eclair.findOne("hash" $eq hash.toString)
    result.map(_ as[Boolean] "isFulfilled") exists identity
  }

  // Storing arbitrary data
  def putData(key: String, prefix: String, data: String) =
    olympus("userData").update("prefix" $eq prefix, $set("key" -> key, "prefix" -> prefix,
      "data" -> data, "createdAt" -> new Date), upsert = true, multi = false, WriteConcern.Safe)

  def getData(key: String) = {
    val desc = DBObject("date" -> -1)
    val allResults = olympus("userData").find("key" $eq key)
    allResults.sort(desc).take(1).map(_ as[String] "data").toList
  }
}