package com.btcontract.lncloud.crypto

import com.btcontract.lncloud.Utils.rand
import org.spongycastle.math.ec.ECPoint
import org.bitcoinj.core.ECKey
import java.math.BigInteger


// As seen on http://arxiv.org/pdf/1304.2094.pdf
class ECBlind(signerQ: ECPoint, signerR: ECPoint) {
  def makeList(number: Int) = for (_ <- 1 to number) yield makeParams
  def generator = Stream continually new BigInteger(1, rand getBytes 64)

  def makeParams: BlindParams = {
    val a = new ECKey(rand).getPrivKey
    val b = new ECKey(rand).getPrivKey
    val c = new ECKey(rand).getPrivKey

    val bInv = b.modInverse(ECKey.CURVE.getN)
    val abInvQ = signerQ.multiply(a.multiply(bInv) mod ECKey.CURVE.getN)
    val blindF = signerR.multiply(bInv).add(abInvQ).add(ECKey.CURVE.getG multiply c).normalize
    val hasZeroCoords = blindF.getAffineXCoord.isZero | blindF.getAffineYCoord.isZero
    if (hasZeroCoords) makeParams else BlindParams(blindF, a, b, c, bInv)
  }
}

// masterPub is signerQ
class ECBlindSign(masterPriv: BigInteger) {
  val masterPrivECKey = ECKey fromPrivate masterPriv
  val masterPubKeyHex = masterPrivECKey.getPublicKeyAsHex

  def blindSign(msg: BigInteger, k: BigInteger) =
    masterPriv.multiply(msg).add(k) mod ECKey.CURVE.getN

  def verifyClearSignature(clearMessage: BigInteger, clearSignature: BigInteger, key: ECPoint) = {
    val rm = key.getAffineXCoord.toBigInteger mod ECKey.CURVE.getN multiply clearMessage mod ECKey.CURVE.getN
    ECKey.CURVE.getG.multiply(clearSignature) == masterPrivECKey.getPubKeyPoint.multiply(rm).add(key)
  }
}

case class BlindParams(blindPubKey: ECPoint, a: BigInteger, b: BigInteger, c: BigInteger, bInv: BigInteger) {
  def blind(msg: BigInteger) = b.multiply(keyBigInt mod ECKey.CURVE.getN).multiply(msg).add(a) mod ECKey.CURVE.getN
  def unblind(sigHat: BigInteger) = bInv.multiply(sigHat).add(c) mod ECKey.CURVE.getN
  def keyBigInt = blindPubKey.getAffineXCoord.toBigInteger
}