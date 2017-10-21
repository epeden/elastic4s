package com.sksamuel.elastic4s.aws

import java.time.format.DateTimeFormatter
import java.time.{ZoneOffset, ZonedDateTime}

import com.amazonaws.auth.{AWSCredentialsProvider, AWSSessionCredentials}
import com.sksamuel.elastic4s.aws.Crypto._

import org.apache.http.{Header, HttpRequest}


/**
  * AWS request signer (version 4) that follows the documentation given by amazon
  * See <a href="http://docs.aws.amazon.com/general/latest/gr/sigv4_signing.html">request signing documentation</a>
  *
  * @param awsCredentialProvider - capable of providing aws user credentials
  * @param region                - amazon region (i.e. eu-west-1)
  * @param service               - service requested, in this context, should always be elastic search, identified by the string "es"
  */
class Aws4RequestSigner(awsCredentialProvider: AWSCredentialsProvider, region: String, service: String = "es") {

  require(awsCredentialProvider.getCredentials != null, "AWS Credentials are mandatory. AWSCredentialsProvider provided none.")

  val credentials = awsCredentialProvider.getCredentials
  val dateHeader = "X-Amz-Date"
  val authHeader = "Authorization"
  val securityTokenHeader = "X-Amz-Security-Token"

  def withAws4Headers(request: HttpRequest): HttpRequest = {

    val (date, dateTime) = buildDateAndDateTime()

    setHostHeader(request)

    /* Insert aws date time header*/
    request.setHeader(dateHeader, dateTime)

    val canonicalRequest = CanonicalRequest(request)
    val stringToSign = StringToSign(service, region, canonicalRequest, date, dateTime)

    val authHeaderValue = buildAuthenticationHeader(canonicalRequest, stringToSign)
    request.addHeader(authHeader, authHeaderValue)

    /* If the credentials are temporary (session credentials), add an additional security header */
    credentials match {
      case c: AWSSessionCredentials ⇒ request.addHeader(securityTokenHeader, c.getSessionToken)
      case _                        ⇒
    }

    request
  }

  /* Build date and dateTime in a protected method so it is possible to override it in tests */
  protected def buildDateAndDateTime(): (String, String) = {
    val now = ZonedDateTime.now(ZoneOffset.UTC)
    val dateTime = now.format(DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'"))
    val date = now.format(DateTimeFormatter.ofPattern("yyyyMMdd"))
    (date, dateTime)
  }

  private def buildAuthenticationHeader(canonicalRequest: CanonicalRequest, stringToSign: StringToSign) = {
    val credentialStr = s"Credential=${credentials.getAWSAccessKeyId}/${stringToSign.credentialsScope}"
    val signedHeadersStr = s"SignedHeaders=${canonicalRequest.signedHeaders}"
    val signatureStr = s"Signature=${buildSignature(stringToSign)}"
    s"${Crypto.Algorithm} $credentialStr, $signedHeadersStr, $signatureStr"
  }

  private def buildSignature(stringToSign: StringToSign) = {
    val signatureKey = buildKeyToSign(stringToSign.date)
    val signature = sign(stringToSign.toString, signatureKey)
    hexOf(signature).toLowerCase
  }

  private def buildKeyToSign(dateStr: String): Array[Byte] = {
    val kSecret = ("AWS4" + credentials.getAWSSecretKey).getBytes("utf-8")
    val dateKey = sign(dateStr, kSecret)
    val regionKey = sign(region, dateKey)
    val serviceKey = sign(service, regionKey)
    sign("aws4_request", serviceKey)
  }

  /* If host header is not found: should create new  Host header. Currently could not retrieve host from Apache HttpRequest.*/
  private def setHostHeader: HttpRequest => HttpRequest = {
    val found = (header: Header) => header.getValue.replaceFirst(":[0-9]+", "")
    setHeader("Host", found)
  }

  private def setHeader(h: String, f: Header => String)(request: HttpRequest): HttpRequest = {
    request.getAllHeaders.find(_.getName == h) match {
      case Some(header) ⇒ request.setHeader(h, f(header))
      case _ ⇒
    }
    request
  }
}
